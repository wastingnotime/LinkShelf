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
    shell("uiautomator", "dump", "/sdcard/my-feed-wnt-221.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/my-feed-wnt-221.xml"))


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


assert "my-feed-wnt-221" in adb("emu", "avd", "name"), "Use this task's dedicated AVD"
print(shell("getprop", "ro.build.fingerprint"))
print(adb("install", "-r", str(root / "apps/mobile/app/build/outputs/apk/debug/app-debug.apk")))
print(shell("pm", "clear", package))
previous_airplane = shell("settings", "get", "global", "airplane_mode_on").strip()
previous_wifi = shell("settings", "get", "global", "wifi_on").strip()
previous_data = shell("settings", "get", "global", "mobile_data").strip()

def texts():
    return [n.get("text") for n in ui().iter("node")]

def screenshot(name):
    (root / f"runs/WNT-221-{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))

try:
    shell("cmd", "connectivity", "airplane-mode", "enable")
    shell("svc", "wifi", "disable")
    shell("svc", "data", "disable")
    assert shell("settings", "get", "global", "airplane_mode_on").strip() == "1"
    for suffix in ("a", "b", "c"):
        share(f"Offline link https://example.com/needle-{suffix}")
    rows = snapshot()
    assert len(rows) == 3 and all(r["previewStatus"] == "pending" for r in rows)
    assert [r["originalUrl"] for r in rows] == [f"https://example.com/needle-{s}" for s in ("c", "b", "a")]
    main()
    tap_text("Grouped")
    assert "example.com · Untitled links · 3" in texts()
    tap_text("Main")
    tap_text("Archive")  # Newest: c
    wait_for(lambda: "https://example.com/needle-c" not in texts())
    tap_text("Delete")  # Next newest: b
    wait_for(lambda: "https://example.com/needle-b" not in texts())
    tap_text("Grouped")
    assert "example.com · Untitled links · 1" in texts()
    screenshot("grouped")
    tap_text("Archived")
    assert any(t and t.startswith("Captured ") for t in texts())
    assert "https://example.com/needle-c" in texts()
    screenshot("archived")
    tap_text("Search")
    assert "1 results · Archived · newest first" in texts()
    field = next(n for n in ui().iter("node") if n.get("class") == "android.widget.EditText")
    coords = [int(n) for n in re.findall(r"\d+", field.get("bounds"))]
    shell("input", "tap", str((coords[0] + coords[2]) // 2), str((coords[1] + coords[3]) // 2))
    shell("input", "text", "needle")
    shell("input", "keyevent", "KEYCODE_BACK")  # Hide keyboard
    tap_text("Inbox")
    assert "1 results · Inbox · newest first" in texts()
    assert "https://example.com/needle-a" in texts()
    tap_text("All")
    assert "2 results · All · newest first" in texts()
    assert "https://example.com/needle-b" not in texts()
    screenshot("search")
    final = snapshot()
    by_url = {r["originalUrl"]: r for r in final}
    assert by_url["https://example.com/needle-c"]["archivedAt"] is not None
    assert by_url["https://example.com/needle-b"]["deletedAt"] is not None
    assert by_url["https://example.com/needle-a"]["archivedAt"] is None
    assert all(r["lastOpenedAt"] is None and r["previewStatus"] == "pending" for r in final)
    main()
    tap_text("Grouped")
    assert "example.com · Untitled links · 1" in texts()
    print("PASS: offline shares/restart, Main-to-Grouped coverage, archived date/group, explicit Inbox/Archived/All search, deleted exclusion and durable lifecycle")
finally:
    shell("cmd", "connectivity", "airplane-mode", "enable" if previous_airplane == "1" else "disable")
    shell("svc", "wifi", "enable" if previous_wifi == "1" else "disable")
    shell("svc", "data", "enable" if previous_data == "1" else "disable")
