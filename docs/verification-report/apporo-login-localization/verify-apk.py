"""Capture only nonsecret identity; API key is compared in memory, never printed."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[3]
sdk = Path('/Users/elmolin/Library/Android/sdk/build-tools/36.0.0')
apk = root / 'app/build/outputs/apk/apporo/debug/app-apporo-debug.apk'
config = json.loads((root / 'app/src/apporoDebug/google-services.json').read_text())
client = next(c for c in config['client'] if c['client_info']['android_client_info']['package_name'] == 'com.apporo.odoo.debug')
resources = subprocess.check_output([str(sdk / 'aapt2'), 'dump', 'resources', str(apk)], text=True)
def value(name):
    match = re.search(r'\bstring/' + re.escape(name) + r'\b[^\n]*\n\s*\(\) "([^"\n]+)"', resources)
    assert match, f'resource missing: {name}'
    return match.group(1)
assert 'MOCK_ONLY_NOT_A_FIREBASE_KEY' not in resources and 'mockonly' not in resources
assert value('google_app_id') == client['client_info']['mobilesdk_app_id']
assert value('google_api_key') == client['api_key'][0]['current_key']
assert value('project_id') == config['project_info']['project_id'] == 'apporo-odoo-app'
assert value('gcm_defaultSenderId') == config['project_info']['project_number']
badging = subprocess.check_output([str(sdk / 'aapt2'), 'dump', 'badging', str(apk)], text=True)
assert "name='com.apporo.odoo.debug'" in badging
signer = subprocess.check_output([str(sdk / 'apksigner'), 'verify', '--print-certs', str(apk)], text=True)
receipt = dict(apk=str(apk), sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
               package='com.apporo.odoo.debug', syntheticMarkerAbsent=True, realFirebaseApiKeyMatches=True,
               firebaseNonsecretIdentity={k:value(k) for k in ('google_app_id','project_id','gcm_defaultSenderId')},
               signerCertificateSHA256=re.search(r'certificate SHA-256 digest: (\w+)', signer).group(1),
               labels=[line for line in badging.splitlines() if line.startswith('application-label')],
               packageBadging=badging.splitlines()[0])
(root / 'docs/verification-report/apporo-login-localization/apk-verification.json').write_text(json.dumps(receipt, indent=2))
print(json.dumps(receipt, indent=2))
