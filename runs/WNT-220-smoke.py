"""Production WorkManager smoke; clears only my-feed on a dedicated emulator."""
import json
import pathlib
import shlex
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

serial = sys.argv[1]
if serial != "emulator-5592":
    raise SystemExit("Use WNT-220's isolated emulator-5592; this clears app data")
package = "org.wastingnotime.linkshelf"
root = pathlib.Path(__file__).resolve().parents[1]


def adb(*args, binary=False, check=True, quiet=False):
    command = ["adb", "-s", serial, *args]
    if not quiet:
        print("+", shlex.join(command), flush=True)
    return subprocess.run(command, check=check, capture_output=True, text=not binary).stdout


def shell(*args):
    return adb("shell", shlex.join(args))


def main():
    shell("am", "start", "-W", "-n", f"{package}/.MainActivity")


def share(text):
    print(shell("am", "start", "-W", "-a", "android.intent.action.SEND", "-t", "text/plain",
                "--es", "android.intent.extra.TEXT", text, f"{package}/.ShareActivity"))


def snapshot(database="my-feed.db", query="SELECT * FROM saved_links ORDER BY id"):
    # Copy DB and WAL together. SQLite ignores incomplete final WAL frames.
    # Retry transient live-copy races while background writes are in progress.
    directory = "no_backup" if database == "androidx.work.workdb" else "databases"
    for _ in range(3):
        try:
            with tempfile.TemporaryDirectory() as folder:
                for suffix in ("", "-wal", "-shm"):
                    content = adb("exec-out", "run-as", package, "cat", f"{directory}/{database}{suffix}",
                                  binary=True, check=False, quiet=True)
                    if content:
                        pathlib.Path(folder, database + suffix).write_bytes(content)
                with sqlite3.connect(f"{folder}/{database}") as conn:
                    conn.row_factory = sqlite3.Row
                    return [dict(row) for row in conn.execute(query)]
        except sqlite3.DatabaseError:
            time.sleep(0.1)
    raise AssertionError("Could not obtain SQLite snapshot")


def wait_for(check, timeout=90):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(1)
    print(json.dumps(snapshot(), indent=2))
    print(shell("dumpsys", "jobscheduler", package))
    raise AssertionError("Timed out waiting for WorkManager state")


def ui():
    shell("uiautomator", "dump", "/sdcard/my-feed-wnt-220.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/my-feed-wnt-220.xml"))


print(shell("getprop", "ro.build.fingerprint"))
print(adb("install", "-r", str(root / "apps/mobile/app/build/outputs/apk/debug/app-debug.apk")))
print(shell("pm", "clear", package))
try:
    shell("cmd", "connectivity", "airplane-mode", "enable")
    shell("svc", "wifi", "disable")
    shell("svc", "data", "disable")
    share("Offline share https://ogp.me/")
    wait_for(lambda: len(snapshot()) == 1, 20)
    shell("am", "force-stop", package)
    offline = snapshot()[0]
    assert offline["previewStatus"] == "pending" and offline["title"] is None
    main()
    text = [n.get("text") for n in ui().iter("node")]
    assert "https://ogp.me/" in text and "Saved offline · preview pending" in text
    works = snapshot("androidx.work.workdb", "SELECT id,state,run_attempt_count FROM WorkSpec")
    assert works and all(w["run_attempt_count"] == 0 for w in works)
    print("OFFLINE CAPTURE AFTER PROCESS RESTART:", json.dumps(offline))
    print("CONSTRAINED WORK:", json.dumps(works))
    (root / "runs/WNT-220-offline.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))

    shell("cmd", "connectivity", "airplane-mode", "disable")
    shell("svc", "wifi", "enable")
    shell("svc", "data", "enable")
    wait_for(lambda: snapshot()[0]["previewStatus"] == "ready")
    enriched = snapshot()[0]
    print("ONLINE ENRICHMENT:", json.dumps(enriched))
    assert enriched["title"] and enriched["imagePath"]
    for key in ("id", "originalUrl", "canonicalUrl", "capturedAt", "archivedAt", "deletedAt", "lastOpenedAt", "revision"):
        assert enriched[key] == offline[key], key
    cached = adb("exec-out", "run-as", package, "cat", "files/previews/" + enriched["imagePath"], binary=True)
    assert 0 < len(cached) <= 2 * 1024 * 1024 and cached[:2] == b"\xff\xd8"
    (root / "runs/WNT-220-preview.jpg").write_bytes(cached)
    shell("am", "force-stop", package)
    shell("svc", "wifi", "disable")
    shell("svc", "data", "disable")
    main()
    assert any(n.get("text") == enriched["title"] for n in ui().iter("node"))
    (root / "runs/WNT-220-ready.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    assert snapshot()[0] == enriched
    print("PASS: cached title and JPEG available offline after process restart")

    shell("svc", "wifi", "enable")
    shell("svc", "data", "enable")
    share("http://127.0.0.1/private")
    wait_for(lambda: len(snapshot()) == 2 and snapshot()[1]["previewStatus"] == "failed")
    failed = snapshot()[1]
    assert failed["originalUrl"] == "http://127.0.0.1/private" and failed["title"] is None
    shell("am", "force-stop", package)
    main()
    text = [n.get("text") for n in ui().iter("node")]
    assert "http://127.0.0.1/private" in text and "Preview unavailable · link saved" in text and "Retry preview" in text
    print("BLOCKED TARGET FALLBACK:", json.dumps(failed))
    (root / "runs/WNT-220-failed.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))

    share("https://does-not-exist-wnt220.example.com/story")
    wait_for(lambda: len(snapshot()) == 3)
    def backoff_seen():
        return any(w["run_attempt_count"] > 0 and w["state"] == 0 for w in snapshot(
            "androidx.work.workdb", "SELECT id,state,run_attempt_count FROM WorkSpec"))
    wait_for(backoff_seen)
    works = snapshot("androidx.work.workdb", "SELECT id,state,run_attempt_count,backoff_policy,backoff_delay_duration FROM WorkSpec")
    print("DURABLE TRANSIENT RETRY:", json.dumps(works))
    assert snapshot()[2]["previewStatus"] == "pending"
    print("PASS: offline share/restart, network constraint recovery, OG metadata/image, offline cache, blocked fallback, persistent exponential retry")
finally:
    shell("cmd", "connectivity", "airplane-mode", "disable")
    shell("svc", "wifi", "enable")
    shell("svc", "data", "enable")
