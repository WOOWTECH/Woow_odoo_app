"""Explicitly isolated follow-up; old evidence is read-only and never overwritten."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from device_common import ROOT, SDK, RUNTIME, ENV, ADB

EVIDENCE = Path('/Users/elmolin/WOOW-mobile-app-analysis/research/apporo-odoo/simulator-validation/android/localization-followup')
EVIDENCE.mkdir(exist_ok=True)
PACKAGE = 'com.apporo.odoo.debug'
HELPER = 'com.github.uiautomator'
IME = HELPER + '/.AdbKeyboard'

def adb(*args, timeout=30):
    # Do not let a post-shutdown command auto-start an unfiltered adb server.
    with socket.socket() as probe:
        if probe.connect_ex(('127.0.0.1', 5038)) != 0:
            raise RuntimeError('Dedicated adb server absent; refusing automatic startup')
    return subprocess.check_output(ADB + list(args), env=ENV, timeout=timeout, stderr=subprocess.STDOUT).decode().strip()

def shell(*args, timeout=30):
    return adb('shell', *args, timeout=timeout)

def identity():
    assert adb('emu', 'avd', 'name').splitlines()[0] == 'Apporo_Odoo_UI_API36'
    assert shell('getprop', 'ro.kernel.qemu') == '1'
    assert adb('devices').splitlines()[1:] == ['emulator-5580\tdevice']
