"""Production acceptance checks. Mutates only this task's isolated API 35 AVD.

Run: python3 runs/WNT-222-smoke.py emulator-5596
The reusable helpers also support the separately recorded real-app UI session.
"""
import json
import pathlib
import re
import shlex
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

serial = sys.argv[1]
if serial != 'emulator-5596':
    raise SystemExit('Use WNT-222 isolated emulator-5596; this clears my-feed data')
package = 'org.wastingnotime.linkshelf'
root = pathlib.Path(__file__).resolve().parents[1]


def adb(*args, binary=False, check=True, quiet=False):
    command = ['adb', '-s', serial, *args]
    if not quiet:
        print('+', shlex.join(command), flush=True)
    return subprocess.run(command, check=check, capture_output=True, text=not binary).stdout


def shell(*args):
    return adb('shell', shlex.join(args))


def wait_for(check, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.5)
    raise AssertionError('Timed out waiting for emulator state')


def main():
    print(shell('am', 'start', '-W', '-n', f'{package}/.MainActivity'))


def share(text):
    print(shell('am', 'start', '-W', '-a', 'android.intent.action.SEND', '-t', 'text/plain',
                '--es', 'android.intent.extra.TEXT', text, f'{package}/.ShareActivity'))
    wait_for(lambda: '.ShareActivity' not in shell('dumpsys', 'activity', 'activities'))


def ui():
    shell('uiautomator', 'dump', '/sdcard/my-feed-wnt-222.xml')
    return ET.fromstring(adb('exec-out', 'cat', '/sdcard/my-feed-wnt-222.xml'))


def texts():
    return [n.get('text') for n in ui().iter('node')]


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    shell('input', 'tap', str((x1 + x2)//2), str((y1 + y2)//2))


def tap_text(text):
    tap_node(next(n for n in ui().iter('node') if n.get('text') == text))


def evidence(name):
    tree = ui()
    ET.ElementTree(tree).write(root / f'runs/WNT-222-{name}.xml', encoding='utf-8')
    (root / f'runs/WNT-222-{name}.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    print('UI:', [(n.get('text'), n.get('content-desc'), n.get('bounds'))
                  for n in tree.iter('node') if n.get('text') or n.get('content-desc')], flush=True)
    return tree


def snapshot(database='my-feed.db', query='SELECT * FROM saved_links ORDER BY capturedAt DESC,id DESC'):
    directory = 'no_backup' if database == 'androidx.work.workdb' else 'databases'
    for _ in range(3):
        try:
            with tempfile.TemporaryDirectory() as folder:
                for suffix in ('', '-wal', '-shm'):
                    content = adb('exec-out', 'run-as', package, 'cat', f'{directory}/{database}{suffix}',
                                  binary=True, check=False, quiet=True)
                    if content:
                        pathlib.Path(folder, database + suffix).write_bytes(content)
                with sqlite3.connect(f'{folder}/{database}') as db:
                    db.row_factory = sqlite3.Row
                    return [dict(row) for row in db.execute(query)]
        except sqlite3.DatabaseError:
            time.sleep(.1)
    raise AssertionError('Could not snapshot Room database')


def network(enabled):
    shell('cmd', 'connectivity', 'airplane-mode', 'disable' if enabled else 'enable')
    shell('svc', 'wifi', 'enable' if enabled else 'disable')
    shell('svc', 'data', 'enable' if enabled else 'disable')


def production():
    assert 'my-feed-wnt-222' in adb('emu', 'avd', 'name')
    print(shell('getprop', 'ro.build.fingerprint'))
    print(adb('install', '-r', str(root / 'apps/mobile/app/build/outputs/apk/debug/app-debug.apk')))
    print(shell('pm', 'clear', package))
    previous = {key: shell('settings', 'get', kind, key).strip() for kind, key in (
        ('global', 'airplane_mode_on'), ('global', 'wifi_on'), ('global', 'mobile_data'), ('system', 'font_scale'))}
    try:
        network(False)
        main()
        assert any(t and t.startswith('A quiet inbox.') for t in texts())
        evidence('empty')
        tap_text('Archived')
        assert 'No archived links yet.' in texts()
        shell('input', 'keyevent', 'KEYCODE_BACK')
        assert 'Your inbox · newest first' in texts()
        shell('input', 'keyevent', 'KEYCODE_BACK')
        resumed = next(line for line in shell('dumpsys', 'activity', 'activities').splitlines()
                       if 'topResumedActivity=' in line)
        print('AFTER ROOT BACK:', resumed)
        assert package not in resumed
        print('PASS: empty views and root Back exits activity')
        share('Offline share https://ogp.me/')
        wait_for(lambda: len(snapshot()) == 1)
        shell('am', 'force-stop', package)
        main()
        offline = snapshot()[0]
        assert offline['previewStatus'] == 'pending' and offline['title'] is None
        assert 'https://ogp.me/' in texts() and 'Saved offline · preview pending' in texts()
        works = snapshot('androidx.work.workdb', 'SELECT state,run_attempt_count FROM WorkSpec')
        assert works and all(w['run_attempt_count'] == 0 for w in works)
        evidence('offline')
        print('OFFLINE:', json.dumps(offline), 'WORK:', json.dumps(works))
        network(True)
        wait_for(lambda: snapshot()[0]['previewStatus'] == 'ready', 100)
        enriched = snapshot()[0]
        assert enriched['title'] and enriched['imagePath']
        for key in ('id', 'originalUrl', 'canonicalUrl', 'capturedAt', 'archivedAt', 'deletedAt', 'lastOpenedAt', 'revision'):
            assert enriched[key] == offline[key], key
        print('ENRICHED:', json.dumps(enriched))
        network(False)
        shell('am', 'force-stop', package)
        main()
        assert enriched['title'] in texts()
        cached = adb('exec-out', 'run-as', package, 'cat', 'files/previews/' + enriched['imagePath'], binary=True)
        assert 0 < len(cached) <= 2*1024*1024 and cached[:2] == b'\xff\xd8'
        evidence('cached')
        print('PASS: offline capture/restart, automatic production worker recovery, cached image/text offline')
        network(True)
        share('http://127.0.0.1/private')
        wait_for(lambda: len(snapshot()) == 2 and snapshot()[0]['previewStatus'] == 'failed')
        shell('am', 'force-stop', package)
        main()
        assert 'http://127.0.0.1/private' in texts()
        assert 'Preview unavailable · link saved' in texts() and 'Retry preview' in texts()
        evidence('failed')
        network(False)
        tap_text('Retry preview')
        wait_for(lambda: snapshot()[0]['previewStatus'] == 'pending')
        assert 'Saved offline · preview pending' in texts()
        print('PASS: failed preview URL/actions remain usable; Retry returns to constrained pending')
        shell('settings', 'put', 'system', 'font_scale', '2.0')
        shell('am', 'force-stop', package)
        main()
        evidence('large-card')
        # Scroll oversized card to reach accessible non-gesture actions.
        for _ in range(6):
            shell('input', 'swipe', '540', '1470', '540', '700', '500')
            if 'Archive' in texts():
                break
        assert 'Archive' in texts()
        evidence('large-actions')
        tap_text('Search')
        assert all(t in texts() for t in ('Inbox', 'Archived', 'All', 'Close search'))
        evidence('large-search')
        shell('input', 'keyevent', 'KEYCODE_BACK')
        assert 'Search' in texts()
        print('PASS: 200% font card scroll/actions, search scopes, Back')
        print('FINAL ROWS:', json.dumps(snapshot(), indent=2))
    finally:
        shell('settings', 'put', 'system', 'font_scale', previous['font_scale'])
        shell('cmd', 'connectivity', 'airplane-mode', 'enable' if previous['airplane_mode_on'] == '1' else 'disable')
        shell('svc', 'wifi', 'enable' if previous['wifi_on'] == '1' else 'disable')
        shell('svc', 'data', 'enable' if previous['mobile_data'] == '1' else 'disable')


if __name__ == '__main__':
    production()
