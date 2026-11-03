"""Offline process-restart smoke. Clears my-feed data on a dedicated emulator only."""
import json
import pathlib
import shlex
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
import re

serial = sys.argv[1]
if not serial.startswith("emulator-"):
    raise SystemExit("Use a dedicated emulator; this smoke clears the app's data")
package = "org.wastingnotime.linkshelf"
root = pathlib.Path(__file__).resolve().parents[1]


def adb(*args, binary=False, check=True):
    command = ["adb", "-s", serial, *args]
    print("+", shlex.join(command), flush=True)
    return subprocess.run(command, check=check, capture_output=True,
                          text=not binary).stdout


def shell(*args):
    return adb("shell", shlex.join(args))


def wait_for(check):
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(0.3)
    raise AssertionError("Timed out waiting for emulator state")


def share(text):
    shell("am", "start", "-W", "-a", "android.intent.action.SEND", "-t", "text/plain",
          "--es", "android.intent.extra.TEXT", text, f"{package}/.ShareActivity")
    wait_for(lambda: ".ShareActivity" not in shell("dumpsys", "activity", "activities"))


def main():
    shell("am", "start", "-W", "-n", f"{package}/.MainActivity")


def ui():
    shell("uiautomator", "dump", "/sdcard/my-feed-wnt-219.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/my-feed-wnt-219.xml"))


def tap_text(text):
    nodes = [node for node in ui().iter("node") if node.get("text") == text]
    assert nodes, f"Missing control: {text}"
    coords = [int(n) for n in re.findall(r"\d+", nodes[0].get("bounds"))]
    shell("input", "tap", str((coords[0] + coords[2]) // 2), str((coords[1] + coords[3]) // 2))


def snapshot():
    shell("am", "force-stop", package)
    with tempfile.TemporaryDirectory() as folder:
        for suffix in ("", "-wal", "-shm"):
            path = f"databases/my-feed.db{suffix}"
            content = adb("exec-out", "run-as", package, "cat", path, binary=True, check=False)
            if content:
                pathlib.Path(folder, f"my-feed.db{suffix}").write_bytes(content)
        with sqlite3.connect(f"{folder}/my-feed.db") as conn:
            conn.row_factory = sqlite3.Row
            rows = [dict(row) for row in conn.execute(
                "SELECT * FROM saved_links ORDER BY capturedAt DESC, id DESC")]
    print(json.dumps(rows, indent=2), flush=True)
    return rows


print(shell("getprop", "ro.build.version.release"), shell("getprop", "ro.build.version.sdk"))
print(adb("install", "-r", str(root / "apps/mobile/app/build/outputs/apk/debug/app-debug.apk")))
print(shell("pm", "clear", package))
previous_airplane = shell("settings", "get", "global", "airplane_mode_on").strip()
try:
    shell("cmd", "connectivity", "airplane-mode", "enable")
    shell("svc", "wifi", "disable")
    shell("svc", "data", "disable")
    assert shell("settings", "get", "global", "airplane_mode_on").strip() == "1"
    first = "HTTPS://Example.com:443/offline?q=1#original"
    second = "https://second.example/story#original"
    share(f"Offline shared text {first}")
    saved = snapshot()
    assert len(saved) == 1 and saved[0]["originalUrl"] == first
    assert saved[0]["canonicalUrl"] == "https://example.com/offline?q=1"
    assert saved[0]["archivedAt"] is None and saved[0]["lastOpenedAt"] is None
    main()
    assert any(n.get("text") == first for n in ui().iter("node"))
    share(second)
    main()
    visible = [n.get("text") for n in ui().iter("node")]
    assert visible.index(second) < visible.index(first), "Main must be newest first"
    tap_text("Archive")  # Newest (second) card.
    wait_for(lambda: not any(n.get("text") == second for n in ui().iter("node")))
    archived = snapshot()
    assert archived[0]["originalUrl"] == second and archived[0]["archivedAt"] is not None
    share("HTTPS://SECOND.EXAMPLE:443/story#duplicate")
    restored = snapshot()
    assert len(restored) == 2
    assert restored[0]["id"] == archived[0]["id"]
    assert restored[0]["originalUrl"] == second
    assert restored[0]["capturedAt"] > archived[0]["capturedAt"]
    assert restored[0]["archivedAt"] is None and restored[0]["deletedAt"] is None
    assert restored[0]["lastOpenedAt"] is None
    main()
    visible = [n.get("text") for n in ui().iter("node")]
    assert visible.index(second) < visible.index(first)
    (root / "runs/WNT-219-main.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    print("PASS: offline SEND, no confirmation, process restart, newest-first Main, archive and duplicate restore")
finally:
    if previous_airplane != "1":
        shell("cmd", "connectivity", "airplane-mode", "disable")
        shell("svc", "wifi", "enable")
        shell("svc", "data", "enable")
