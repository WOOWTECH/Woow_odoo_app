"""Dedicated emulator only. Never contact default adb or a USB transport."""
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path('/Users/elmolin/WOOW-mobile-app-analysis/research/apporo-odoo/simulator-validation/android/localization')
EVIDENCE.mkdir(exist_ok=True)
SDK = Path('/Users/elmolin/Library/Android/sdk')
RUNTIME = json.loads(Path('/Users/elmolin/WOOW-mobile-app-analysis/research/apporo-odoo/validation/android-emulator-runtime.json').read_text())
ENV = os.environ.copy()
ENV.update(RUNTIME['environmentPaths'])
ENV.update(ADB_SERVER_SOCKET='tcp:localhost:5038', ANDROID_ADB_SERVER_PORT='5038',
           ADB_MDNS_AUTO_CONNECT='0', ADB_MDNS_OPENSCREEN='0',
           ADB_LOCAL_TRANSPORT_MAX_PORT='0', ANDROID_SERIAL='emulator-5580',
           ANDROID_HOME=str(SDK), ANDROID_SDK_ROOT=str(SDK))
for key in ('HTTP_PROXY','HTTPS_PROXY','ALL_PROXY','http_proxy','https_proxy','all_proxy'):
    ENV.pop(key, None)
ADB = [str(SDK / 'platform-tools/adb'), '-P', '5038', '-s', 'emulator-5580']

def adb(*args, timeout=30):
    return subprocess.check_output(ADB + list(args), env=ENV, timeout=timeout, stderr=subprocess.STDOUT).decode().strip()

def shell(*args, timeout=30):
    return adb('shell', *args, timeout=timeout)

def identity():
    assert adb('emu', 'avd', 'name').splitlines()[0] == 'Apporo_Odoo_UI_API36'
    assert shell('getprop', 'ro.kernel.qemu') == '1'
