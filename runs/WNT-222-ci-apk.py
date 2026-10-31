"""Install the downloaded CI APK on WNT-222's disposable AVD and check capture.
Run: python3 runs/WNT-222-ci-apk.py /absolute/path/to/app-debug.apk
"""
import hashlib
import json
from pathlib import Path
import runpy
import sys

apk = Path(sys.argv[1]).resolve()
sys.argv = [__file__, 'emulator-5596']
s = runpy.run_path(str(Path(__file__).with_name('WNT-222-smoke.py')), run_name='helpers')
assert 'my-feed-wnt-222' in s['adb']('emu', 'avd', 'name')
print('DOWNLOADED CI APK:', apk.name, 'BYTES:', apk.stat().st_size,
      'SHA256:', hashlib.sha256(apk.read_bytes()).hexdigest(), flush=True)
previous = {key: s['shell']('settings', 'get', 'global', key).strip()
            for key in ('airplane_mode_on', 'wifi_on', 'mobile_data')}
try:
    print(s['adb']('install', '-r', str(apk)))
    s['network'](False)
    s['share']('CI artifact delivery https://example.com/wnt222-ci-delivery')
    s['wait_for'](lambda: any(r['originalUrl'] == 'https://example.com/wnt222-ci-delivery' for r in s['snapshot']()))
    s['shell']('am', 'force-stop', s['package'])
    s['main']()
    s['wait_for'](lambda: 'https://example.com/wnt222-ci-delivery' in s['texts']())
    row = next(r for r in s['snapshot']() if r['originalUrl'] == 'https://example.com/wnt222-ci-delivery')
    assert row['archivedAt'] is None and row['deletedAt'] is None and row['previewStatus'] == 'pending'
    print('CI APK PERSISTED OFFLINE CAPTURE:', json.dumps(row), flush=True)
    s['evidence']('ci-apk')
    print('PASS: downloaded CI APK installs, captures offline without a form, and survives process restart', flush=True)
finally:
    s['shell']('cmd', 'connectivity', 'airplane-mode', 'enable' if previous['airplane_mode_on'] == '1' else 'disable')
    s['shell']('svc', 'wifi', 'enable' if previous['wifi_on'] == '1' else 'disable')
    s['shell']('svc', 'data', 'enable' if previous['mobile_data'] == '1' else 'disable')
