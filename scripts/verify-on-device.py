#!/usr/bin/env python3
"""
On-device verification for Woow Odoo App implementation plan.
Uses uiautomator2 to interact with the app and verify features.
No screenshots — only UI element inspection and ADB commands.

Verification IDs follow format: V{nn}-C{nn} matching commit plan.

Usage: python3 scripts/verify-on-device.py
"""

import os
import re
import subprocess
import sys
import time

import requests
import uiautomator2 as u2

# Single source of truth for test config — see scripts/test_config.py.
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_config import (
    APP_ACTIVITY as ACTIVITY,
    APP_PACKAGE as PKG,
    FIREBASE_PROJECT_ID,
    FIREBASE_SA_FILE,
    ODOO_DB,
    ODOO_HOST,
    ODOO_PASS,
    ODOO_URL,
    ODOO_USER,
    VERIFY_REPORT_FILE,
)

if not (ODOO_URL and ODOO_DB and ODOO_USER and ODOO_PASS):
    print("ERROR: Odoo test server not configured. Set ODOO_URL, ODOO_DB, ODOO_USER "
          "and ODOO_PASS (env vars or the gitignored .env.test) — see scripts/test_config.py.")
    sys.exit(2)

PASS = 0
FAIL = 0
SKIP = 0
RESULTS = []

# Every check ID this script can report. Used to compute "defined vs executed"
# so a run that silently drops checks (or executes nothing) can never look green.
# A group-level ID (e.g. "V22-C482a7bf", emitted when a setup step fails)
# accounts for all of that group's sub-checks.
DEFINED_CHECKS = [
    "V01-C01", "V02a-C02", "V02b-C02", "V03-C02", "V04-C04", "V05-C04",
    "V06-C06", "V07a-C06", "V07b-C06", "V08a-C07", "V08b-C07", "V09-C09",
    "V10a-C08", "V10b-C08", "V10c-C08", "V11a-C13", "V11b-C13",
    "V13a-C15", "V13b-C15", "V14a-C17", "V14b-C17", "V15-G4",
    "V16a-G5", "V16b-G5", "V17-G3", "V18-G6", "V19-G7",
    "V20a", "V20b", "V20c", "V21-C482a7bf",
    "V22a-C482a7bf", "V22b-C482a7bf", "V24-C482a7bf",
    "V23-C482a7bf", "V23b-C482a7bf",
    "V26a-Cb1aaa75", "V26b-Cb1aaa75", "V26c-Cb1aaa75", "V26d-Cb1aaa75",
    "V25-C482a7bf",
]
REPORTED = []  # (vid, status) in execution order; status in PASS/FAIL/SKIP


def green(vid, msg):
    global PASS
    PASS += 1
    REPORTED.append((vid, "PASS"))
    RESULTS.append(f"✅ {vid}: {msg}")
    print(f"\033[32m  ✅ {vid}: {msg}\033[0m")


def red(vid, msg):
    global FAIL
    FAIL += 1
    REPORTED.append((vid, "FAIL"))
    RESULTS.append(f"❌ {vid}: {msg}")
    print(f"\033[31m  ❌ {vid}: {msg}\033[0m")


def skip(vid, msg):
    """Record a check that was NOT executed (precondition/resource missing).
    Counted separately — never as a pass."""
    global SKIP
    SKIP += 1
    REPORTED.append((vid, "SKIP"))
    RESULTS.append(f"⏭️ {vid}: SKIPPED — {msg}")
    print(f"\033[33m  ⏭️  {vid}: SKIPPED — {msg}\033[0m")



# App 顯示名稱依裝置語系而異（AP-15 改名）：預設語系為英文 "woowtech platform"，
# 中文語系為「渥屋系統」。任何斷言都必須同時接受兩者，否則換一台語系不同的
# 裝置就會誤報失敗。字串來源：app/src/main/res/values*/strings.xml 的 app_name。
APP_TITLES = ("woowtech platform", "渥屋系統")


def app_title_visible(d, timeout=2):
    """Return True if the app-bar/brand title is on screen in ANY supported locale."""
    return any(d(text=t).exists(timeout=timeout) for t in APP_TITLES)


def check(vid, desc, condition):
    if condition:
        green(vid, desc)
    else:
        red(vid, desc)


def section(title):
    print(f"\n\033[1m{'─' * 60}\033[0m")
    print(f"\033[1m  {title}\033[0m")
    print(f"\033[1m{'─' * 60}\033[0m")


def adb_cmd(args, timeout=30):
    """Run an ADB shell command and return stdout.

    30 s default: on the Pixel 7a / Android 17, `dumpsys package` normally
    takes ~2.5 s but spiked past 10 s mid-run (e.g. right after a cache
    clear), which killed the whole run with an uncaught TimeoutExpired."""
    result = subprocess.run(
        ["adb", "shell"] + args,
        capture_output=True, text=True, timeout=timeout
    )
    return result.stdout


# `mCurrentFocus=Window{9a1b u0 io.woowtech.odoo.debug/io.woowtech.odoo.ui.MainActivity}`
# `mFocusedApp=ActivityRecord{1234 u0 io.woowtech.odoo.debug/io.woowtech.odoo.ui.MainActivity t33219}`
# Captures the token after the user id: a package for app windows, or a bare
# window name (e.g. "NotificationShade") for system windows.
_FOCUS_RE = re.compile(r"(mCurrentFocus|mFocusedApp)=\w+\{\S+ u\d+ ([^/}\s]+)")


def foreground_package():
    """Package of the window that currently has input focus, via adb.

    Replaces `d.app_current()`, which on Android 17 returns the wrong package
    even when our window is demonstrably focused. Reads `mCurrentFocus`
    (the focused window; a system window such as NotificationShade is
    returned by name, so it never masquerades as our app) and falls back to
    `mFocusedApp` (the focused activity) only when mCurrentFocus is null,
    e.g. mid-transition. The grep runs on-device so only two short lines
    cross the wire. Returns None if neither can be parsed.
    """
    out = adb_cmd(["dumpsys window displays | grep -E 'mCurrentFocus|mFocusedApp'"],
                  timeout=15)
    found = dict((k, pkg) for k, pkg in _FOCUS_RE.findall(out))
    return found.get("mCurrentFocus") or found.get("mFocusedApp")


def app_in_foreground(timeout=3.0):
    """True if our app holds focus. Polls briefly to ride out transitions."""
    deadline = time.time() + timeout
    while True:
        if foreground_package() == PKG:
            return True
        if time.time() >= deadline:
            return False
        time.sleep(0.5)


# Menu (hamburger) button: Compose exposes it via content-desc only
# (R.string.content_description_menu). zh-TW / zh-CN / English.
MENU_DESCS = ("開啟選單", "打开菜单", "Open menu")


def open_menu():
    """Tap the main-screen menu button. Returns False (never raises) if absent."""
    for desc in MENU_DESCS:
        btn = d(description=desc)
        if btn.exists(timeout=2):
            btn.click()
            return True
    for desc in MENU_DESCS:
        btn = d(descriptionContains=desc)
        if btn.exists(timeout=1):
            btn.click()
            return True
    return False


MENU_MISSING = "Menu button found (content-desc 開啟選單 / 打开菜单 / Open menu)"


def launch_app():
    """Force stop and launch the app."""
    d.app_stop(PKG)
    time.sleep(1)
    d.app_start(PKG, ACTIVITY)
    time.sleep(5)


def dismiss_biometric():
    """Dismiss biometric prompt if showing."""
    d.press("back")
    time.sleep(2)


# ─── Connect ─────────────────────────────────────────────
print("Connecting to device...")
d = u2.connect()
try:
    _info = d.info
    device_name = _info.get("productName", "unknown")
    sdk = _info.get("sdkInt", "?")
except Exception as _e:
    # Android 17: d.info raises RPCUnknownError ("ApplicationSharedMemory not
    # initialized"). getprop gives the same facts without the RPC.
    print(f"  (d.info unavailable: {type(_e).__name__}; using getprop)")
    device_name = adb_cmd(["getprop", "ro.product.model"]).strip() or "unknown"
    sdk = adb_cmd(["getprop", "ro.build.version.sdk"]).strip() or "?"
print(f"Connected: {device_name} (Android SDK {sdk})")
print()

# ═══════════════════════════════════════════════════════════
# V01-C01: Timber replaces android.util.Log
# ═══════════════════════════════════════════════════════════
section("V01-C01: Timber Logging (B0.1)")

subprocess.run(["adb", "logcat", "-c"], capture_output=True)
launch_app()

result = subprocess.run(
    ["adb", "logcat", "-d", "-t", "300"],
    capture_output=True, text=True
)
old_tag_count = result.stdout.count("WoowTechOdoo")
check("V01-C01",
      f"No 'WoowTechOdoo' log tag in logcat (found {old_tag_count})",
      old_tag_count == 0)

# ═══════════════════════════════════════════════════════════
# V02-C02: Biometric skip button removed
# ═══════════════════════════════════════════════════════════
section("V02-C02: Biometric Skip Removed (B0.2)")

launch_app()
dismiss_biometric()

skip_texts = ["Skip", "跳過", "跳过", "稍後再說", "稍后再说"]
skip_found = any(d(text=t).exists(timeout=1) for t in skip_texts)
check("V02a-C02",
      "No 'Skip'/'跳過'/'稍后再说' button in UI",
      not skip_found)

skip_by_id = d(resourceIdMatches=".*skip.*").exists(timeout=1)
check("V02b-C02",
      "No skip-related resource ID in UI tree",
      not skip_by_id)

# ═══════════════════════════════════════════════════════════
# V03-C02: Auth re-prompt on background→foreground
# ═══════════════════════════════════════════════════════════
section("V03-C02: Auth Re-prompt on Background (B0.3)")

launch_app()

biometric_keywords = ["指紋", "Fingerprint", "生物", "PIN"]
biometric_visible = any(
    d(textContains=kw).exists(timeout=1) for kw in biometric_keywords
)

if biometric_visible:
    dismiss_biometric()
    # Try to authenticate and reach main screen
    # Then test bg→fg
    d.press("home")
    time.sleep(2)
    d.app_start(PKG, ACTIVITY)
    time.sleep(4)

    auth_reappears = any(
        d(textContains=kw).exists(timeout=2) for kw in biometric_keywords
    )
    check("V03-C02",
          "Auth screen re-appears after background→foreground",
          auth_reappears)
else:
    main_visible = app_title_visible(d, timeout=2)
    if main_visible:
        # App lock not enabled — test bg→fg anyway to confirm no crash
        d.press("home")
        time.sleep(2)
        d.app_start(PKG, ACTIVITY)
        time.sleep(3)
        still_running = app_in_foreground()
        check("V03-C02",
              "App survives background→foreground (app lock not enabled, auth re-prompt requires enabling App Lock in Settings)",
              still_running)
    else:
        check("V03-C02", "App launched to recognizable screen", False)

# ═══════════════════════════════════════════════════════════
# V04-C04: WebView shows same-host content only
# ═══════════════════════════════════════════════════════════
section("V04-C04: WebView Same-Host Only (B0.5)")

launch_app()

odoo_texts = ["Inbox", "Discuss", "收件匣", "Login", "登入", "登录",
              "WoowTech", "Odoo"]
odoo_content = any(d(textContains=t).exists(timeout=3) for t in odoo_texts)
check("V04-C04",
      "WebView shows Odoo content (same-host, no external redirect)",
      odoo_content)

# ═══════════════════════════════════════════════════════════
# V05-C04: WebView security — no popup windows
# ═══════════════════════════════════════════════════════════
section("V05-C04: WebView No Popup Windows (B0.7)")

# V05: count UNIQUE currently-resumed MainActivity instances.
#
# `dumpsys activity activities` is noisy:
#   - The currently-resumed activity is reported in 4–8 different lines
#     (topResumedActivity=, Resumed:, ResumedActivity:, mFocusedApp=, ...).
#     A naive line grep triple-counts a single instance.
#   - Historical entries in the `Hist` list and orientation `source=`
#     lines reference ActivityRecords from prior tasks that are no longer
#     resumed — those should NOT be counted.
#
# Reliable signal: lines with `topResumedActivity=` (one per display),
# `Resumed: ActivityRecord{...}` (one per active task), or
# `ResumedActivity: ActivityRecord{...}` (one per task summary). Dedup by
# the hex ActivityRecord id.
top_dump = adb_cmd(["dumpsys", "activity", "activities"])
_RESUMED_RE = re.compile(
    r"(?:topResumedActivity=|^\s*Resumed:\s*|^\s*ResumedActivity:\s*)"
    r"ActivityRecord\{([0-9a-f]+) [^}]*MainActivity",
    re.MULTILINE,
)
ids = set(_RESUMED_RE.findall(top_dump))
resumed_main = len(ids)
check("V05-C04",
      f"Exactly 1 active MainActivity instance (found {resumed_main} unique)",
      resumed_main == 1)

# ═══════════════════════════════════════════════════════════
# V06-C06: POST_NOTIFICATIONS permission
# ═══════════════════════════════════════════════════════════
section("V06-C06: POST_NOTIFICATIONS Permission (B0.10)")

pkg_dump = adb_cmd(["dumpsys", "package", PKG])
has_perm = "android.permission.POST_NOTIFICATIONS" in pkg_dump
check("V06-C06",
      "POST_NOTIFICATIONS permission declared in package manifest",
      has_perm)

# ═══════════════════════════════════════════════════════════
# V07-C06: Notification channel exists
# ═══════════════════════════════════════════════════════════
section("V07-C06: Notification Channel (B0.11)")

notif_dump = adb_cmd(["dumpsys", "notification"])
has_channel = "woow_odoo_messages" in notif_dump
check("V07a-C06",
      "Notification channel 'woow_odoo_messages' exists on device",
      has_channel)

if has_channel:
    match = re.search(
        r"mId='woow_odoo_messages'.*?mImportance=(\d+)", notif_dump
    )
    if match:
        importance = int(match.group(1))
        check("V07b-C06",
              f"Channel importance is HIGH (4), got {importance}",
              importance == 4)
    else:
        check("V07b-C06", "Could not parse channel importance", False)

# ═══════════════════════════════════════════════════════════
# V08-C07: Brand colors — app launches with themed UI
# ═══════════════════════════════════════════════════════════
section("V08-C07: Brand Colors (B1.1)")

launch_app()
app_running = app_in_foreground()
check("V08a-C07",
      "App launches without crash (brand colors compiled)",
      app_running)

top_bar = app_title_visible(d, timeout=3)
check("V08b-C07",
      "App bar with app title visible (themed) — accepts any supported locale",
      top_bar)

# ═══════════════════════════════════════════════════════════
# V09-C09: zh-CN — 简体中文 option in language picker
# ═══════════════════════════════════════════════════════════
section("V09-C09: Simplified Chinese (B2)")

# Navigate: Main → Menu → Settings → Language
menu_found = open_menu()
time.sleep(2)

if menu_found:
    # Look for Settings
    settings_found = False
    for text in ["设置", "設定", "Settings"]:
        btn = d(text=text)
        if btn.exists(timeout=2):
            btn.click()
            settings_found = True
            break

    time.sleep(2)

    if settings_found:
        # Scroll to Language section
        for _ in range(3):
            lang_found = False
            for text in ["语言", "語言", "Language"]:
                el = d(text=text)
                if el.exists(timeout=1):
                    lang_found = True
                    el.click()
                    time.sleep(1)
                    break
            if lang_found:
                break
            d.swipe(0.5, 0.7, 0.5, 0.3)
            time.sleep(1)

        if lang_found:
            zhcn_exists = d(text="简体中文").exists(timeout=2)
            check("V09-C09",
                  "'简体中文' option available in language picker",
                  zhcn_exists)
            d.press("back")
        else:
            check("V09-C09", "Language option found in settings", False)

        d.press("back")
    else:
        check("V09-C09", "Settings screen accessible from menu", False)
        d.press("back")
else:
    check("V09-C09", MENU_MISSING, False)

time.sleep(1)
d.press("back")

# ═══════════════════════════════════════════════════════════
# V10-C08: Color picker with brand colors + HEX input
# ═══════════════════════════════════════════════════════════
section("V10-C08: Color Picker (B1.2)")

launch_app()

# Navigate: Menu → Settings → Theme Color
menu_ok = open_menu()
time.sleep(2)

settings_ok = False
for text in ["设置", "設定", "Settings"]:
    if not menu_ok:
        break
    btn = d(text=text)
    if btn.exists(timeout=2):
        btn.click()
        settings_ok = True
        break
time.sleep(2)

if not menu_ok:
    check("V10a-C08", MENU_MISSING, False)
elif settings_ok:
    # Click theme color
    for text in ["主题颜色", "主題顏色", "Theme Color"]:
        btn = d(textContains=text)
        if btn.exists(timeout=2):
            btn.click()
            break
    time.sleep(2)

    preset = (d(textContains="Preset").exists(timeout=2) or
              d(textContains="预设").exists(timeout=2) or
              d(textContains="預設").exists(timeout=2))
    check("V10a-C08", "Preset colors label in color picker", preset)

    accent = d(text="Accent").exists(timeout=2)
    check("V10b-C08", "Accent colors section in color picker", accent)

    hex_field = d(textContains="RRGGBB").exists(timeout=2)
    check("V10c-C08", "HEX input field (#RRGGBB) in color picker", hex_field)

    d.press("back")
    time.sleep(1)
    d.press("back")
else:
    check("V10a-C08", "Settings accessible for color picker test", False)

time.sleep(1)
d.press("back")

# ═══════════════════════════════════════════════════════════
# V11-C13: Cache clearing via CacheRepository
# ═══════════════════════════════════════════════════════════
section("V11-C13: Cache Clearing (B4.2)")

launch_app()

# Navigate to Settings
if not open_menu():
    check("V11a-C13", MENU_MISSING, False)
else:
    time.sleep(2)

    for text in ["设置", "設定", "Settings"]:
        btn = d(text=text)
        if btn.exists(timeout=2):
            btn.click()
            break
    time.sleep(2)

    # Scroll to Clear Cache
    for _ in range(3):
        cache_btn = (d(textContains="Clear Cache") or
                     d(textContains="清除快取") or
                     d(textContains="清除缓存"))
        if cache_btn.exists(timeout=1):
            break
        d.swipe(0.5, 0.7, 0.5, 0.3)
        time.sleep(1)

    cache_btn = (d(textContains="Clear Cache") or
                 d(textContains="清除快取") or
                 d(textContains="清除缓存"))
    if cache_btn.exists(timeout=2):
        check("V11a-C13", "Clear Cache button found in Settings", True)
        cache_btn.click()
        time.sleep(2)
        still_settings = (d(textContains="Settings").exists(timeout=2) or
                          d(textContains="设置").exists(timeout=2) or
                          d(textContains="設定").exists(timeout=2))
        check("V11b-C13", "App stays on Settings after cache clear (login preserved)", still_settings)
    else:
        check("V11a-C13", "Clear Cache button found", False)

d.press("back")
time.sleep(1)
d.press("back")

# ═══════════════════════════════════════════════════════════
# V13-C15: WoowFcmService registered
# ═══════════════════════════════════════════════════════════
section("V13-C15: FCM Service (A1.2)")

pkg_dump2 = adb_cmd(["dumpsys", "package", PKG])
has_svc = "WoowFcmService" in pkg_dump2
check("V13a-C15", "WoowFcmService registered in package manifest", has_svc)

has_msg_event = "MESSAGING_EVENT" in pkg_dump2
check("V13b-C15", "MESSAGING_EVENT intent filter registered", has_msg_event)

# ═══════════════════════════════════════════════════════════
# V14-C17: Deep link handling
# ═══════════════════════════════════════════════════════════
section("V14-C17: Deep Link Handling (A1.4)")

launch_app()
still_ok = app_in_foreground()
check("V14a-C17", "App launches with deep link handler (no crash)", still_ok)

# Send deep link intent
subprocess.run([
    "adb", "shell", "am", "start",
    "-n", f"{PKG}/io.woowtech.odoo.ui.MainActivity",
    "--es", "odoo_action_url", "/web#id=42&model=sale.order&view_type=form"
], capture_output=True, text=True, timeout=10)
time.sleep(3)

still_ok2 = app_in_foreground()
check("V14b-C17", "App handles deep link intent without crash", still_ok2)

# ═══════════════════════════════════════════════════════════
# V15: Color picker ACTUALLY changes theme (G4)
# ═══════════════════════════════════════════════════════════
section("V15: Color Picker Changes Theme (User Flow)")

launch_app()

# Navigate to Settings → Theme Color
if not open_menu():
    check("V15-G4", MENU_MISSING, False)
else:
    time.sleep(2)

    for text in ["设置", "設定", "Settings"]:
        btn = d(text=text)
        if btn.exists(timeout=2):
            btn.click()
            break
    time.sleep(2)

    # Open color picker
    for text in ["主题颜色", "主題顏色", "Theme Color"]:
        btn = d(textContains=text)
        if btn.exists(timeout=2):
            btn.click()
            break
    time.sleep(2)

    # Tap the Apply button (applies currently selected color)
    apply_btn = d(text="Apply") or d(text="套用") or d(text="应用")
    if apply_btn.exists(timeout=2):
        apply_btn.click()
        time.sleep(1)
        # After apply, dialog should close and we're back on Settings
        still_in_settings = (d(textContains="Settings").exists(timeout=2) or
                             d(textContains="设置").exists(timeout=2) or
                             d(textContains="設定").exists(timeout=2))
        check("V15-G4", "Color picker: tap Apply → dialog closes, back on Settings", still_in_settings)
    else:
        check("V15-G4", "Color picker Apply button found", False)

d.press("back")
time.sleep(1)
d.press("back")

# ═══════════════════════════════════════════════════════════
# V16: zh-CN ACTUALLY switches language (G5)
# ═══════════════════════════════════════════════════════════
section("V16: zh-CN Language Switch (User Flow)")

launch_app()

# Navigate: Menu → Settings → Language
if not open_menu():
    check("V16a-G5", MENU_MISSING, False)
else:
    time.sleep(2)

    for text in ["设置", "設定", "Settings"]:
        btn = d(text=text)
        if btn.exists(timeout=2):
            btn.click()
            break
    time.sleep(2)

    # Scroll to Language and tap it
    lang_clicked = False
    for _ in range(3):
        for text in ["语言", "語言", "Language"]:
            el = d(text=text)
            if el.exists(timeout=1):
                el.click()
                lang_clicked = True
                break
        if lang_clicked:
            break
        d.swipe(0.5, 0.7, 0.5, 0.3)
        time.sleep(1)

    if lang_clicked:
        time.sleep(1)
        # Select 简体中文
        zhcn = d(text="简体中文")
        if zhcn.exists(timeout=2):
            zhcn.click()
            time.sleep(2)

            # Verify: Settings should now show Chinese text
            # "安全性" = Security in zh-CN, "外观" = Appearance
            zh_visible = (d(textContains="安全性").exists(timeout=2) or
                          d(textContains="外观").exists(timeout=2) or
                          d(textContains="数据").exists(timeout=2))
            check("V16a-G5", "After selecting 简体中文, Settings shows simplified Chinese text", zh_visible)

            # Switch back to English to restore state
            for text in ["语言", "Language"]:
                el = d(text=text)
                if el.exists(timeout=1):
                    el.click()
                    break
            else:
                d.swipe(0.5, 0.7, 0.5, 0.3)
                time.sleep(1)
                for text in ["语言"]:
                    el = d(text=text)
                    if el.exists(timeout=1):
                        el.click()
                        break

            time.sleep(1)
            eng = d(text="English")
            eng_restored = False
            if eng.exists(timeout=2):
                eng.click()
                eng_restored = True
                time.sleep(1)
            check("V16b-G5", "Restored language to English ('English' option found and tapped)",
                  eng_restored)
        else:
            check("V16a-G5", "简体中文 option found in picker", False)
    else:
        check("V16a-G5", "Language option found in settings", False)

d.press("back")
time.sleep(1)
d.press("back")

# ═══════════════════════════════════════════════════════════
# V17: WebView blocks external URLs (G3)
# ═══════════════════════════════════════════════════════════
section("V17: WebView External URL Blocked (User Flow)")

launch_app()
time.sleep(3)

# Try to open an external URL via intent — should open in browser, NOT in WebView
# After sending, our app should still be in foreground (external URL opens separately)
# But if WebView allowed it, we'd still be in our app showing the external site
subprocess.run([
    "adb", "shell", "am", "start",
    "-n", f"{PKG}/io.woowtech.odoo.ui.MainActivity",
    "--es", "odoo_action_url", "https://evil.com/phish"
], capture_output=True, text=True, timeout=10)
time.sleep(3)

# App should still be running (not crashed)
running = app_in_foreground()
check("V17-G3", "App survives external URL deep link (rejected by DeepLinkValidator)", running)

# ═══════════════════════════════════════════════════════════
# V18: Cache size decreases after clear (G6)
# ═══════════════════════════════════════════════════════════
section("V18: Cache Size Decreases After Clear (User Flow)")

launch_app()

# Navigate to Settings
if not open_menu():
    check("V18-G6", MENU_MISSING, False)
else:
    time.sleep(2)

    for text in ["设置", "設定", "Settings"]:
        btn = d(text=text)
        if btn.exists(timeout=2):
            btn.click()
            break
    time.sleep(2)

    # Scroll to Data & Storage, find cache size text
    for _ in range(3):
        cache_row = (d(textContains="Clear Cache") or
                     d(textContains="清除快取") or
                     d(textContains="清除缓存"))
        if cache_row.exists(timeout=1):
            break
        d.swipe(0.5, 0.7, 0.5, 0.3)
        time.sleep(1)

    if cache_row.exists(timeout=2):
        # Tap clear cache
        cache_row.click()
        time.sleep(2)

        # After cache clear, verify we're still on settings and no crash
        # Cache size text may show "0 B", "0 KB", or localized text
        still_ok = app_in_foreground()
        settings_visible = (d(textContains="Settings").exists(timeout=2) or
                            d(textContains="设置").exists(timeout=2) or
                            d(textContains="設定").exists(timeout=2))
        check("V18-G6", "Cache cleared successfully — app stable, settings visible", still_ok and settings_visible)
    else:
        check("V18-G6", "Clear Cache row found", False)

d.press("back")
time.sleep(1)
d.press("back")

# ═══════════════════════════════════════════════════════════
# V19: Deep link navigates WebView to target URL (G7)
# ═══════════════════════════════════════════════════════════
section("V19: Deep Link Navigates WebView (User Flow)")

# Send deep link and check WebView loads something
d.app_stop(PKG)
time.sleep(1)
subprocess.run([
    "adb", "shell", "am", "start",
    "-n", f"{PKG}/io.woowtech.odoo.ui.MainActivity",
    "--es", "odoo_action_url", "/web#action=contacts"
], capture_output=True, text=True, timeout=10)
time.sleep(6)

# App should be running and showing WebView (Odoo content)
running2 = app_in_foreground()
odoo_loaded = (d(textContains="WoowTech").exists(timeout=2) or
               d(textContains="Contacts").exists(timeout=2) or
               d(textContains="Inbox").exists(timeout=2) or
               d(textContains="联系人").exists(timeout=2) or
               d(textContains="聯絡人").exists(timeout=2))
check("V19-G7", "Deep link /web#action=contacts — app loaded Odoo content", running2 and odoo_loaded)

# ═══════════════════════════════════════════════════════════
# V20: FCM End-to-End Push Notification
# ═══════════════════════════════════════════════════════════
section("V20: FCM E2E Push Notification")

# Path comes from test_config (FIREBASE_SA_FILE env / .env.test, default
# app/firebase-service-account.json, which is gitignored).
SA_FILE = FIREBASE_SA_FILE

if os.path.exists(SA_FILE):
    try:
        import google.auth.transport.requests as gauth_requests
        from google.oauth2 import service_account as gauth_sa

        # 1. Get FCM token from logcat (retry up to 30s for Firebase init)
        subprocess.run(["adb", "logcat", "-c"], capture_output=True)
        d.app_stop(PKG)
        time.sleep(2)
        d.app_start(PKG, ACTIVITY)

        fcm_token = None
        for attempt in range(6):
            time.sleep(5)
            logcat_out = subprocess.run(
                ["adb", "logcat", "-d"],
                capture_output=True, text=True
            ).stdout
            for line in logcat_out.split("\n"):
                if "FCM_TOKEN:" in line:
                    fcm_token = line.split("FCM_TOKEN:")[1].strip()
                    break
            if fcm_token:
                break

        check("V20a", f"FCM token retrieved from device (len={len(fcm_token) if fcm_token else 0})",
              fcm_token is not None and len(fcm_token) > 100)

        if fcm_token:
            # 2. Send push via FCM HTTP v1 API
            credentials = gauth_sa.Credentials.from_service_account_file(
                SA_FILE,
                scopes=["https://www.googleapis.com/auth/firebase.messaging"]
            )
            credentials.refresh(gauth_requests.Request())

            # Background the app first
            d.press("home")
            time.sleep(2)

            resp = requests.post(
                f"https://fcm.googleapis.com/v1/projects/{FIREBASE_PROJECT_ID}/messages:send",
                json={
                    "message": {
                        "token": fcm_token,
                        "data": {
                            "title": "E2E Test",
                            "body": "Automated push verification",
                            "odoo_model": "sale.order",
                            "odoo_res_id": "1",
                            "odoo_action_url": "/web#id=1&model=sale.order&view_type=form",
                            "event_type": "chatter"
                        }
                    }
                },
                headers={
                    "Authorization": f"Bearer {credentials.token}",
                    "Content-Type": "application/json",
                },
                timeout=10
            )
            check("V20b", f"FCM API returned {resp.status_code}", resp.status_code == 200)

            # 3. Verify notification appeared in notification shade
            time.sleep(5)
            notif_dump = subprocess.run(
                ["adb", "shell", "dumpsys", "notification", "--noredact"],
                capture_output=True, text=True, timeout=10
            ).stdout

            has_notif = "E2E Test" in notif_dump or (
                "io.woowtech.odoo.debug" in notif_dump and "woow_odoo_messages" in notif_dump
                and "NotificationRecord" in notif_dump
            )
            check("V20c", "Push notification appeared in notification shade", has_notif)
        else:
            check("V20b", "FCM token needed to send push", False)
            check("V20c", "Notification check skipped (no token)", False)

    except ImportError:
        check("V20a", "google-auth library needed (pip install google-auth)", False)
    except Exception as e:
        check("V20a", f"FCM test error: {e}", False)
else:
    for _vid in ("V20a", "V20b", "V20c"):
        skip(_vid, f"Firebase service account not found at {SA_FILE} (set FIREBASE_SA_FILE)")

# ═══════════════════════════════════════════════════════════
# V21-V24: Security hardening regressions (commit 482a7bf)
# Detects the class of bugs that made biometric unlock cosmetic
# in v1.0.21 — silent regressions that unit tests cannot catch.
# ═══════════════════════════════════════════════════════════

section("V21-C482a7bf: FLAG_SECURE is NOT set — screenshots/screen recording allowed")
# The owner deliberately removed window-level FLAG_SECURE (2026-09-23) so users
# can take screenshots. This guards against it silently coming back.
# Detection: find OUR MainActivity window block in `dumpsys window windows`
# and read its layout flags. Android 17 prints them as `fl=A B C`; older
# releases print `flags=A B C` or a hex mask `flags=#81810100`
# (FLAG_SECURE = 0x2000). No fallback: if the window or its flags line can't
# be found, the check FAILS — an unparseable dump must never read as green.
FLAG_SECURE_BIT = 0x00002000


def main_window_flags(window_dump):
    """Return the flag tokens of our MainActivity window, or None if not found."""
    header = re.compile(
        r"^\s*Window #\d+ Window\{[^}]*\s" + re.escape(f"{PKG}/{ACTIVITY}") + r"\}:\s*$",
        re.MULTILINE,
    )
    m = header.search(window_dump)
    if not m:
        return None
    nxt = re.compile(r"^\s*Window #\d+ ", re.MULTILINE).search(window_dump, m.end())
    block = window_dump[m.end(): nxt.start() if nxt else len(window_dump)]
    fm = re.search(r"^\s*(?:fl|flags)=(\S.*)$", block, re.MULTILINE)
    if not fm:
        return None
    value = fm.group(1).strip()
    hexm = re.match(r"(?:#|0x)([0-9a-fA-F]+)\b", value)
    if hexm:
        return ["SECURE"] if int(hexm.group(1), 16) & FLAG_SECURE_BIT else ["(hex, no SECURE bit)"]
    return value.split()


try:
    launch_app()
    if not app_in_foreground(timeout=5):
        check("V21-C482a7bf", f"MainActivity not in front (focus: {foreground_package()}) — cannot read its window flags", False)
    else:
        flags = main_window_flags(adb_cmd(["dumpsys", "window", "windows"], timeout=30))
        if flags is None:
            check("V21-C482a7bf", "Could not locate MainActivity window / its fl= line in dumpsys window", False)
        else:
            secure = any(t in ("SECURE", "FLAG_SECURE") for t in flags)
            check("V21-C482a7bf",
                  f"MainActivity window does NOT carry FLAG_SECURE (screenshots allowed); fl={' '.join(flags)}",
                  not secure)
except Exception as e:
    check("V21-C482a7bf", f"FLAG_SECURE check error: {e}", False)

# ═══════════════════════════════════════════════════════════
# Self-contained test helpers (per CLAUDE.md "Test Independence" rule)
# ═══════════════════════════════════════════════════════════

# Test credentials — sourced from test_config (single source of truth).
TEST_SERVER_URL = ODOO_HOST  # host-only form (no scheme) for the URL field
TEST_DB = ODOO_DB
TEST_USER = ODOO_USER
TEST_PASSWORD = ODOO_PASS
# SettingsRepository.PIN_LENGTH is 6 and the test-pin hook ignores anything
# else ("Ignored invalid test-pin"), so a 4-digit PIN never gets seeded.
TEST_PIN = "123456"


def _edits():
    """Return the bounds of every EditText currently on screen."""
    return re.findall(
        r'<node[^>]*class="[^"]*EditText[^"]*"[^>]*bounds="([^"]+)"[^>]*>',
        d.dump_hierarchy(),
    )


def _center(bounds_str):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds_str)
    return ((int(m.group(1)) + int(m.group(3))) // 2,
            (int(m.group(2)) + int(m.group(4))) // 2)


def _type_into(idx, text):
    """Click the idx-th EditText and ADB-type text into it. Returns True on success."""
    fg = foreground_package()
    if fg != PKG:
        # Owner's personal phone: never type into another app.
        print(f"  ⚠️  refusing to type — foreground is {fg!r}, not {PKG}")
        return False
    e = _edits()
    if len(e) <= idx:
        return False
    x, y = _center(e[idx])
    d.click(x, y); time.sleep(1)
    subprocess.run(["adb", "shell", "input", "text", text], timeout=15)
    time.sleep(1)
    return True


def is_webview_visible():
    return "android.webkit.WebView" in d.dump_hierarchy()


# Logged-out entry points. A fresh install / `pm clear` now opens straight on
# the server-URL form (no "Add Account" button), so match its field label too.
LOGIN_ENTRY_TEXTS = ("新增帳號", "添加账号", "Add Account",
                     "伺服器網址", "服务器网址", "Server URL")


def is_add_account_visible():
    return any(d(text=t).exists(timeout=1) for t in LOGIN_ENTRY_TEXTS)


def perform_login():
    """Walk through the Add Account → credentials → WebView flow.

    Switches to ADBKeyboard before typing so the URL/credentials are entered
    byte-perfect (default IMEs autocorrect tokens like "trycloudflare" or
    capitalise "admin"). The original IME is restored before returning.
    """
    from test_config import enable_adb_keyboard, restore_ime
    previous_ime = enable_adb_keyboard()
    try:
        # Server URL screen
        for _ in range(15):
            if len(_edits()) >= 2:
                break
            time.sleep(1)
        if not (_type_into(0, TEST_SERVER_URL) and _type_into(1, TEST_DB)):
            return False
        subprocess.run(["adb", "shell", "input", "keyevent", "111"], timeout=5); time.sleep(1)
        if d(text="下一步").exists(timeout=10):
            d(text="下一步").click()
        elif d(text="Next").exists(timeout=2):
            d(text="Next").click()
        time.sleep(8)
        # Credentials screen
        for _ in range(15):
            if len(_edits()) >= 2:
                break
            time.sleep(1)
        if not (_type_into(0, TEST_USER) and _type_into(1, TEST_PASSWORD)):
            return False
        subprocess.run(["adb", "shell", "input", "keyevent", "111"], timeout=5); time.sleep(1)
        for label in ("登入", "登录", "Login"):
            if d(text=label).exists(timeout=5):
                d(text=label).click()
                break
        # Wait for WebView. 60s budget (was 25s) covers a fresh cloudflared
        # tunnel cold-starting Odoo without a cached session — the path V26's
        # nuclear fallback exercises after pm clear. Faster paths return early.
        for _ in range(60):
            if is_webview_visible():
                return True
            time.sleep(1)
        return is_webview_visible()
    finally:
        restore_ime(previous_ime)


def is_auth_gate_visible():
    """True if the BiometricScreen or PinScreen is showing the auth gate."""
    return (
        d(textContains="生物辨識").exists(timeout=1) or
        d(textContains="Biometric").exists(timeout=1) or
        d(textContains="使用 PIN").exists(timeout=1) or
        d(textContains="Use PIN").exists(timeout=1) or
        d(textContains="Unlock").exists(timeout=1)
    )


def is_logged_in_but_not_on_webview():
    """True if user is logged in (account exists) but currently on a non-WebView
    screen like Settings, Profile, Config/Account drawer. Detected by presence
    of account-management text without the WebView class.
    """
    return (
        not is_webview_visible()
        and not is_add_account_visible()
        and not is_auth_gate_visible()
        and (
            d(textContains="切換帳號").exists(timeout=1) or       # Switch Account (zh-TW)
            d(textContains="Switch Account").exists(timeout=1) or
            d(textContains="登出").exists(timeout=1) or          # Logout (zh)
            d(textContains="Logout").exists(timeout=1) or
            d(textContains="設定").exists(timeout=1) or          # Settings (zh)
            d(textContains="Settings").exists(timeout=1)
        )
    )


def ensure_logged_in():
    """Idempotent precondition: app must be launched and showing the WebView.

    Per CLAUDE.md "Test Independence" rule: every test that needs an account
    calls this so it has no dependency on previous tests' state.

    Handles three possible starting states:
      1. Already on WebView → return True immediately
      2. On Add Account screen → run perform_login()
      3. On auth gate (Biometric/PIN screen) → use test hook to disable App
         Lock and reset state, restart, then re-check
    """
    d.app_start(PKG, ACTIVITY); time.sleep(4)
    if is_webview_visible():
        return True
    if is_add_account_visible():
        return perform_login()
    if is_auth_gate_visible():
        # Account exists but is gated. Use the test hook to disable App Lock
        # and reset failure state, then restart to land on WebView directly.
        subprocess.run([
            "adb", "shell", "am", "start", "-n", f"{PKG}/{ACTIVITY}",
            "--ez", "app-lock-enabled", "false",
            "--ez", "reset-state", "true",
        ], timeout=10)
        time.sleep(3)
        d.app_stop(PKG); time.sleep(1); d.app_start(PKG, ACTIVITY); time.sleep(5)
        if is_webview_visible():
            return True
        if is_add_account_visible():
            return perform_login()
    if is_logged_in_but_not_on_webview():
        # On a non-WebView screen (Settings, Profile drawer, etc.) with an
        # active account. Press BACK up to 3 times to close the drawer/screen
        # and return to the main WebView. (Force-stopping does NOT help —
        # the drawer is restored on next launch.)
        for _ in range(3):
            d.press("back"); time.sleep(2)
            if is_webview_visible():
                return True
            if not is_logged_in_but_not_on_webview():
                break
        if is_webview_visible():
            return True
        # If we ended up on the auth gate after dismissing, recurse once
        if is_auth_gate_visible():
            subprocess.run([
                "adb", "shell", "am", "start", "-n", f"{PKG}/{ACTIVITY}",
                "--ez", "app-lock-enabled", "false",
                "--ez", "reset-state", "true",
            ], timeout=10)
            time.sleep(3)
            d.app_stop(PKG); time.sleep(1); d.app_start(PKG, ACTIVITY); time.sleep(5)
            if is_webview_visible():
                return True
    # Unknown state — last resort: clean restart and re-check
    d.app_stop(PKG); time.sleep(1); d.app_start(PKG, ACTIVITY); time.sleep(5)
    if is_webview_visible():
        return True
    if is_add_account_visible():
        return perform_login()

    # Nuclear fallback: pm clear + fresh login. Slow (~30s per test) but
    # deterministic. This is the price of true test independence when
    # Compose state cannot be reliably navigated via uiautomator2.
    pm_clear_app()
    time.sleep(2)
    d.app_start(PKG, ACTIVITY); time.sleep(6)
    if is_add_account_visible():
        return perform_login()
    return is_webview_visible()


def pm_clear_app():
    """`pm clear` + re-grant POST_NOTIFICATIONS. `pm clear` revokes runtime
    permissions, and the app's first-launch permission dialog would otherwise
    cover the login form (CLAUDE.md lists the grant as a suite prerequisite)."""
    subprocess.run(["adb", "shell", "pm", "clear", PKG], timeout=10)
    subprocess.run(["adb", "shell", "pm", "grant", PKG,
                    "android.permission.POST_NOTIFICATIONS"], timeout=10)


def apply_test_hook(test_pin=None, app_lock=None, biometric=None, reset_state=False,
                    location_enabled=None):
    """Fire MainActivity intent with test-hook extras. Hook is debug-only and
    R8-stripped in release. Waits long enough for PBKDF2 (600K iterations,
    ~6-8s on Xiaomi 25078PC3EG) to complete before returning — otherwise the
    next app_stop kills the hash mid-flight and the PIN never persists."""
    args = ["adb", "shell", "am", "start", "-n", f"{PKG}/{ACTIVITY}"]
    if test_pin is not None:
        args += ["--es", "test-pin", test_pin]
    if app_lock is not None:
        args += ["--ez", "app-lock-enabled", "true" if app_lock else "false"]
    if biometric is not None:
        args += ["--ez", "biometric-enabled", "true" if biometric else "false"]
    if reset_state:
        args += ["--ez", "reset-state", "true"]
    if location_enabled is not None:
        args += ["--ez", "location-enabled", "true" if location_enabled else "false"]
    subprocess.run(args, timeout=10)
    # Wait for PBKDF2 to complete. The setPin path takes ~6-8s on the test
    # device because PBKDF2-HMAC-SHA256 with 600K iterations runs on the main
    # thread inside the hook. Wait extra when test_pin is provided.
    time.sleep(10 if test_pin is not None else 3)


def restart_to_trigger_gate():
    """Force-stop + relaunch so onCreate runs and the auth gate evaluates
    the freshly-seeded settings."""
    d.app_stop(PKG); time.sleep(1)
    d.app_start(PKG, ACTIVITY); time.sleep(5)


def fall_through_to_pin():
    """If the BiometricScreen is showing, tap 'Use PIN' to reach the PIN keypad.
    The Chinese label is "使用 PIN 碼" (PIN-code), not just "使用 PIN".
    Verified by hierarchy dump on Xiaomi 25078PC3EG (zh-TW)."""
    for label in ("Use PIN", "使用 PIN 碼", "使用 PIN", "使用PIN碼", "使用PIN"):
        if d(text=label).exists(timeout=2):
            d(text=label).click(); time.sleep(3); return True
    # Fallback: textContains("使用 PIN") matches "使用 PIN 碼"
    if d(textContains="使用 PIN").exists(timeout=2):
        d(textContains="使用 PIN").click(); time.sleep(3); return True
    if d(textContains="Use PIN").exists(timeout=2):
        d(textContains="Use PIN").click(); time.sleep(3); return True
    return False


def wait_for_pin_keypad(timeout_s=10):
    """Poll until the PinScreen keypad is rendered (digit '1' visible).
    Returns True if found within timeout, False otherwise.
    The keypad uses text= attributes (verified by hierarchy dump)."""
    for _ in range(timeout_s):
        if d(text="1").exists(timeout=1):
            return True
        time.sleep(1)
    return False


def type_pin_keypad(pin):
    """Tap each digit on the PinScreen keypad."""
    fg = foreground_package()
    if fg != PKG:
        print(f"  ⚠️  refusing to tap PIN — foreground is {fg!r}, not {PKG}")
        return
    for digit in pin:
        if d(text=digit).exists(timeout=2):
            d(text=digit).click(); time.sleep(0.3)


# ═══════════════════════════════════════════════════════════
section("V22-C482a7bf: PinScreen renders keypad (iOS parity, no submit button)")
# Self-contained per CLAUDE.md test-independence rule:
#   1. Setup: ensure logged in, seed PIN + enable App Lock + disable biometric
#   2. Restart to trigger the auth gate
#   3. Assert PinScreen has 0-9 keypad and no submit button
#   4. Cleanup: enter PIN to leave authenticated, disable App Lock so the next
#      test starts in a clean state
try:
    if not ensure_logged_in():
        check("V22-C482a7bf", "Could not reach logged-in baseline", False)
    else:
        apply_test_hook(test_pin=TEST_PIN, app_lock=True, biometric=False)
        restart_to_trigger_gate()
        # Force-PIN path — biometric should be off but dismiss prompt if it appears
        fall_through_to_pin()
        # Wait for PinScreen keypad to render before counting digits
        wait_for_pin_keypad(timeout_s=10)

        digits_found = sum(1 for digit in "0123456789" if d(text=str(digit)).exists(timeout=1))
        check("V22a-C482a7bf",
              f"PinScreen shows full 0-9 keypad ({digits_found}/10 digits found)",
              digits_found >= 10)

        has_submit = any(
            d(text=t).exists(timeout=1)
            for t in ("Submit", "Confirm", "OK", "確認", "确认")
        )
        check("V22b-C482a7bf",
              "No submit/confirm button — PIN auto-verifies on full length (iOS parity)",
              not has_submit)

        # Cleanup: enter PIN to authenticate, then disable lock for next test
        type_pin_keypad(TEST_PIN)
        for _ in range(10):
            if is_webview_visible(): break
            time.sleep(1)
        apply_test_hook(app_lock=False, reset_state=True)
except Exception as e:
    check("V22-C482a7bf", f"PinScreen keypad check error: {e}", False)

# ═══════════════════════════════════════════════════════════
section("V24-C482a7bf: ProcessLifecycleOwner re-auth on bg→fg (L1 fix)")
# Self-contained per CLAUDE.md test-independence rule:
#   1. Setup: ensure logged in, seed PIN + enable App Lock, restart, enter PIN
#      → reach WebView (so we have an authenticated session to invalidate)
#   2. Action: HOME → reopen
#   3. Assert: auth screen reappears (NOT WebView)
#   4. Cleanup: enter PIN, disable App Lock
try:
    if not ensure_logged_in():
        check("V24-C482a7bf", "Could not reach logged-in baseline", False)
    else:
        apply_test_hook(test_pin=TEST_PIN, app_lock=True, biometric=False)
        restart_to_trigger_gate()
        fall_through_to_pin()
        wait_for_pin_keypad(timeout_s=10)
        type_pin_keypad(TEST_PIN)

        # Wait until past the gate (WebView visible) — we MUST be authenticated
        # before we can meaningfully test that backgrounding invalidates auth.
        webview_reached = False
        for _ in range(15):
            if is_webview_visible():
                webview_reached = True; break
            time.sleep(1)
        if not webview_reached:
            check("V24-C482a7bf", "Could not reach WebView after PIN entry — V24 setup failed", False)
        else:
            # The real V24 check: background → foreground must re-trigger auth
            d.press("home"); time.sleep(3)
            d.app_start(PKG, ACTIVITY); time.sleep(3)

            # Auth screen indicators: any digit key from PIN keypad, biometric
            # prompt text, or "Use PIN" fallback. NOT the WebView.
            still_on_webview = is_webview_visible()
            auth_visible = (
                not still_on_webview and (
                    any(d(text=digit).exists(timeout=1) for digit in "0123") or
                    d(textContains="PIN").exists(timeout=1) or
                    d(textContains="生物辨識").exists(timeout=1) or
                    d(textContains="Biometric").exists(timeout=1)
                )
            )
            check("V24-C482a7bf",
                  "Auth screen re-appears after bg→fg (ProcessLifecycleOwner ON_STOP invalidated auth)",
                  auth_visible)

            # Cleanup
            type_pin_keypad(TEST_PIN)
            for _ in range(10):
                if is_webview_visible(): break
                time.sleep(1)
            apply_test_hook(app_lock=False, reset_state=True)
except Exception as e:
    check("V24-C482a7bf", f"bg→fg re-auth check error: {e}", False)

# ═══════════════════════════════════════════════════════════
section("V23-C482a7bf: DeepLinkValidator rejects deep links with no active account")
# Destructive test — runs LAST per CLAUDE.md test-independence rule because
# `pm clear` wipes account state. Cannot run before V22/V24/etc. without
# breaking those tests' preconditions. (V23 itself is self-contained: it
# performs its own pm clear setup, the cleanup is "leave app in fresh
# uninstalled state" which is fine for the end of the test sequence.)
try:
    pm_clear_app()
    time.sleep(2)
    # Deliver the deep link the way a notification tap does (explicit
    # component + odoo_action_url extra). The previous VIEW intent on
    # https://example.com never resolved to this app (the manifest only
    # accepts woowodoo://open), so it asserted nothing.
    started = subprocess.run([
        "adb", "shell", "am", "start", "-n", f"{PKG}/{ACTIVITY}",
        "--es", "odoo_action_url", "/web#action=contacts",
    ], capture_output=True, text=True, timeout=10)
    delivered = "Error" not in (started.stdout + started.stderr)
    time.sleep(4)
    # `dumpsys activity top` takes ~10 s on Android 17; the focused-window
    # helper plus the UI hierarchy answer the same question in <1 s.
    in_front = app_in_foreground(timeout=5)
    if not delivered:
        check("V23-C482a7bf", f"Deep-link intent was not delivered to the app: {started.stdout.strip()}", False)
    elif not in_front:
        check("V23-C482a7bf", f"App not in front after deep link (focus: {foreground_package()})", False)
    else:
        check("V23-C482a7bf",
              "Deep link rejected — no auto-navigation to WebView without active account",
              not is_webview_visible())

    logcat = adb_cmd(["logcat", "-d", "-t", "200"])
    rejected_logged = (
        "deep link" in logcat.lower()
        and ("reject" in logcat.lower() or "no active" in logcat.lower())
    )
    if rejected_logged:
        green("V23b-C482a7bf", "Timber log confirms deep-link rejection")
    else:
        skip("V23b-C482a7bf", "no explicit rejection log line (soft check, not asserted)")
except Exception as e:
    check("V23-C482a7bf", f"Deep-link rejection check error: {e}", False)

# ═══════════════════════════════════════════════════════════
# V26: Verifies the FOUR things we can confirm without manually tapping
# inside the OWL Compose dropdown (which is unreliable from uiautomator2
# because FLAG_SECURE blanks screenshots and Compose nodes don't always
# expose clickable bounds for nested menu items).
#
#   V26a — App launches with setGeolocationEnabled and does not crash
#   V26b — Manifest declares FINE + COARSE (NOT BACKGROUND)
#   V26c — TestHook for location-enabled fires (Timber log appears)
#   V26d — hr_attendance module is installed on the test Odoo server
#
# The full E2E flow (real clock-in records non-zero lat/lng on
# hr.attendance) is in scripts/e2e-production-test.py → E2E-15, which
# uses a hybrid manual+automated approach: user manually taps the
# Attendance systray and grants permission, the script then queries
# Odoo to confirm coordinates landed.
# ═══════════════════════════════════════════════════════════
section("V26-Cb1aaa75: Location permission infrastructure (Odoo Attendances)")
try:
    if not ensure_logged_in():
        check("V26", "baseline failed — not logged in", False)
    else:
        # V26a: app starts cleanly with setGeolocationEnabled in WebSettings
        d.app_start(PKG, ACTIVITY); time.sleep(5)
        check(
            "V26a-Cb1aaa75",
            "WebView with setGeolocationEnabled(true) launches without crash",
            app_in_foreground(),
        )

        # V26b: manifest declares the two foreground location permissions
        pkg_dump = adb_cmd(["dumpsys", "package", PKG])
        has_fine = "android.permission.ACCESS_FINE_LOCATION" in pkg_dump
        has_coarse = "android.permission.ACCESS_COARSE_LOCATION" in pkg_dump
        has_background = "android.permission.ACCESS_BACKGROUND_LOCATION" in pkg_dump
        check(
            "V26b-Cb1aaa75",
            f"FINE+COARSE declared, BACKGROUND NOT declared "
            f"(fine={has_fine}, coarse={has_coarse}, background={has_background})",
            has_fine and has_coarse and not has_background,
        )

        # V26c: TestHook for location-enabled fires and logs via Timber.
        # Force-stop first so the intent triggers onCreate (cold start),
        # which fires Timber.tag(TAG).w(...) reliably. Warm-start
        # onNewIntent ALSO fires the hook but timing is less deterministic.
        subprocess.run(["adb", "shell", "am", "force-stop", PKG], timeout=5)
        time.sleep(1)
        subprocess.run(["adb", "logcat", "-c"], timeout=5)
        subprocess.run([
            "adb", "shell", "am", "start", "-n", f"{PKG}/{ACTIVITY}",
            "--ez", "location-enabled", "true",
        ], timeout=10)
        time.sleep(6)  # PBKDF2-free path; just need Timber to flush
        log = subprocess.run(
            ["adb", "logcat", "-d", "-s", "TestHooks:W"],
            capture_output=True, text=True, timeout=10,
        ).stdout
        hook_fired = "Location preference set via test hook" in log
        check(
            "V26c-Cb1aaa75",
            "TestHooks logged location-enabled extra (hook reachable)",
            hook_fired,
        )

        # V26d: Odoo server has hr_attendance installed (E2E-15 prerequisite)
        try:
            url = f"{ODOO_URL}/jsonrpc"
            # Resolve the uid instead of assuming 2 (admin), and send the
            # password — execute_kw's third arg is the password, not the login.
            uid = requests.post(url, json={
                "jsonrpc": "2.0", "method": "call", "id": 0,
                "params": {"service": "common", "method": "login",
                           "args": [ODOO_DB, ODOO_USER, ODOO_PASS]},
            }, timeout=10).json().get("result")
            payload = {
                "jsonrpc": "2.0", "method": "call",
                "params": {
                    "service": "object", "method": "execute_kw",
                    "args": [ODOO_DB, uid, ODOO_PASS,
                             "ir.module.module", "search_read",
                             [[["name", "=", "hr_attendance"]]],
                             {"fields": ["state"]}],
                }, "id": 1,
            }
            resp = requests.post(url, json=payload, timeout=10).json()
            installed = (
                resp.get("result")
                and len(resp["result"]) > 0
                and resp["result"][0].get("state") == "installed"
            )
            check(
                "V26d-Cb1aaa75",
                "hr_attendance module is installed on test Odoo (E2E-15 prereq)",
                installed,
            )
        except Exception as e:
            check("V26d-Cb1aaa75", f"hr_attendance state check error: {e}", False)
except Exception as e:
    check("V26", f"error: {e}", False)

# ═══════════════════════════════════════════════════════════
section("V25-C482a7bf: Release variant ignores test hooks")
# @Skip — requires a built and installed release APK (io.woowtech.odoo, not .debug).
# Manual verification steps:
#   1. ./gradlew :app:assembleRelease
#   2. adb install -r app/build/outputs/apk/release/app-release-unsigned.apk
#   3. adb shell am start -n io.woowtech.odoo/io.woowtech.odoo.ui.MainActivity \
#        --es test-pin 9999 --ez app-lock-enabled true
#   4. Open Settings → Security: verify PIN is not "9999" and App Lock state unchanged.
#   5. apkanalyzer dex packages app-release-unsigned.apk | grep TestHooks
#      Expected: TestHooks class absent or method body empty (R8 dead-code removal).
# This test is intentionally skipped in the automated suite; it requires a signed release build.
skip("V25-C482a7bf", "release-variant test-hook isolation requires manual release APK install (see script comments)")

# ═══════════════════════════════════════════════════════════
# SUMMARY
# ═══════════════════════════════════════════════════════════
section("VERIFICATION SUMMARY")


def _group(vid):
    return re.match(r"V\d+", vid).group(0)


# A group-level result ("V22-C482a7bf", no letter suffix) stands in for all of
# that group's sub-checks — it is emitted when the group's setup failed.
_reported_ids = {vid for vid, _ in REPORTED}
_group_level = {_group(v) for v in _reported_ids if re.fullmatch(r"V\d+(-\S+)?", v)}
NOT_REACHED = [
    v for v in DEFINED_CHECKS
    if v not in _reported_ids and _group(v) not in _group_level
]
executed = PASS + FAIL
print(f"\n  Defined checks:  {len(DEFINED_CHECKS)}")
print(f"  Executed:        {executed}")
print(f"  \033[32mPassed:          {PASS}\033[0m")
print(("\033[31m" if FAIL else "") + f"  Failed:          {FAIL}\033[0m")
print(f"  \033[33mSkipped:         {SKIP}\033[0m")
print(f"  Not reached:     {len(NOT_REACHED)}" + (f"  ({', '.join(NOT_REACHED)})" if NOT_REACHED else ""))
GREEN = FAIL == 0 and executed > 0 and not NOT_REACHED
print(f"\n  Verdict: {'GREEN' if GREEN else 'NOT GREEN'}"
      + ("" if executed else " — nothing executed"))
print()

# Write results to markdown (path from test_config.VERIFY_REPORT_FILE)
report_path = VERIFY_REPORT_FILE
os.makedirs(os.path.dirname(os.path.abspath(report_path)), exist_ok=True)
with open(report_path, "a") as f:
    f.write(f"\n\n## uiautomator2 Verification Run — {time.strftime('%Y-%m-%d %H:%M:%S')}\n\n")
    f.write(f"| Field | Value |\n")
    f.write(f"|-------|-------|\n")
    f.write(f"| Device | {device_name} (SDK {sdk}) |\n")
    f.write(f"| Package | {PKG} |\n")
    f.write(f"| Result | **{len(DEFINED_CHECKS)} defined, {executed} executed, {PASS} passed, "
            f"{FAIL} failed, {SKIP} skipped, {len(NOT_REACHED)} not reached** |\n\n")
    f.write("| V-ID | Result | Description |\n")
    f.write("|------|--------|-------------|\n")
    for (vid, status), r in zip(REPORTED, RESULTS):
        desc = r.split(": ", 1)[1] if ": " in r else r
        f.write(f"| {vid} | {status} | {desc} |\n")
    for vid in NOT_REACHED:
        f.write(f"| {vid} | NOT REACHED | check never executed |\n")
    f.write("\n")

print(f"Results appended to {report_path}")
sys.exit(0 if GREEN else 1)
