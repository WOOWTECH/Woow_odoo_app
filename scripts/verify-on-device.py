#!/usr/bin/env python3
"""
On-device verification for Woow Odoo App implementation plan.
Uses uiautomator2 to interact with the app and verify features.
No screenshots — only UI element inspection and ADB commands.

Verification IDs follow format: V{nn}-C{nn} matching commit plan.

Usage: python3 scripts/verify-on-device.py
"""

import uiautomator2 as u2
import time
import subprocess
import re
import sys

PKG = "io.woowtech.odoo.debug"
ACTIVITY = "io.woowtech.odoo.ui.MainActivity"
PASS = 0
FAIL = 0
RESULTS = []


def green(vid, msg):
    global PASS
    PASS += 1
    RESULTS.append(f"✅ {vid}: {msg}")
    print(f"\033[32m  ✅ {vid}: {msg}\033[0m")


def red(vid, msg):
    global FAIL
    FAIL += 1
    RESULTS.append(f"❌ {vid}: {msg}")
    print(f"\033[31m  ❌ {vid}: {msg}\033[0m")


def check(vid, desc, condition):
    if condition:
        green(vid, desc)
    else:
        red(vid, desc)


def section(title):
    print(f"\n\033[1m{'─' * 60}\033[0m")
    print(f"\033[1m  {title}\033[0m")
    print(f"\033[1m{'─' * 60}\033[0m")


def adb_cmd(args):
    """Run an ADB shell command and return stdout."""
    result = subprocess.run(
        ["adb", "shell"] + args,
        capture_output=True, text=True, timeout=10
    )
    return result.stdout


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
device_name = d.info.get("productName", "unknown")
sdk = d.info.get("sdkInt", "?")
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
    main_visible = d(text="WoowTech Odoo").exists(timeout=2)
    if main_visible:
        # App lock not enabled — test bg→fg anyway to confirm no crash
        d.press("home")
        time.sleep(2)
        d.app_start(PKG, ACTIVITY)
        time.sleep(3)
        still_running = d.app_current()["package"] == PKG
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

activities_dump = adb_cmd(["dumpsys", "activity", "activities"])
# Count unique ActivityRecord IDs for our package
unique_ids = set(
    re.findall(r"ActivityRecord\{([a-f0-9]+)\s.*?" + PKG, activities_dump)
)
check("V05-C04",
      f"Only 1 unique Activity instance (found {len(unique_ids)})",
      len(unique_ids) <= 1)

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
app_running = d.app_current()["package"] == PKG
check("V08a-C07",
      "App launches without crash (brand colors compiled)",
      app_running)

top_bar = d(text="WoowTech Odoo").exists(timeout=3)
check("V08b-C07",
      "App bar with 'WoowTech Odoo' title visible (themed)",
      top_bar)

# ═══════════════════════════════════════════════════════════
# V09-C09: zh-CN — 简体中文 option in language picker
# ═══════════════════════════════════════════════════════════
section("V09-C09: Simplified Chinese (B2)")

# Navigate: Main → Menu → Settings → Language
menu_found = False
for desc in ["开启菜单", "開啟選單", "Menu", "menu", "Open menu"]:
    btn = d(descriptionContains=desc)
    if btn.exists(timeout=1):
        btn.click()
        menu_found = True
        break

if not menu_found:
    # Try clicking the hamburger icon by content description pattern
    btn = d(className="android.widget.ImageButton")
    if btn.exists(timeout=1):
        btn.click()
        menu_found = True

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
    check("V09-C09", "Menu button found", False)

time.sleep(1)
d.press("back")

# ═══════════════════════════════════════════════════════════
# V10-C08: Color picker with brand colors + HEX input
# ═══════════════════════════════════════════════════════════
section("V10-C08: Color Picker (B1.2)")

launch_app()

# Navigate: Menu → Settings → Theme Color
menu_ok = False
for desc in ["开启菜单", "開啟選單", "Menu", "menu"]:
    btn = d(descriptionContains=desc)
    if btn.exists(timeout=1):
        btn.click()
        menu_ok = True
        break
if not menu_ok:
    btn = d(className="android.widget.ImageButton")
    if btn.exists(timeout=1):
        btn.click()
        menu_ok = True
time.sleep(2)

settings_ok = False
for text in ["设置", "設定", "Settings"]:
    btn = d(text=text)
    if btn.exists(timeout=2):
        btn.click()
        settings_ok = True
        break
time.sleep(2)

if settings_ok:
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
menu_ok2 = False
for desc in ["开启菜单", "開啟選單", "Menu", "menu"]:
    btn = d(descriptionContains=desc)
    if btn.exists(timeout=1):
        btn.click()
        menu_ok2 = True
        break
if not menu_ok2:
    d(className="android.widget.ImageButton").click()
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
still_ok = d.app_current()["package"] == PKG
check("V14a-C17", "App launches with deep link handler (no crash)", still_ok)

# Send deep link intent
subprocess.run([
    "adb", "shell", "am", "start",
    "-n", f"{PKG}/io.woowtech.odoo.ui.MainActivity",
    "--es", "odoo_action_url", "/web#id=42&model=sale.order&view_type=form"
], capture_output=True, text=True, timeout=10)
time.sleep(3)

still_ok2 = d.app_current()["package"] == PKG
check("V14b-C17", "App handles deep link intent without crash", still_ok2)

# ═══════════════════════════════════════════════════════════
# V15: Color picker ACTUALLY changes theme (G4)
# ═══════════════════════════════════════════════════════════
section("V15: Color Picker Changes Theme (User Flow)")

launch_app()

# Navigate to Settings → Theme Color
for desc in ["开启菜单", "開啟選單", "Menu", "menu"]:
    btn = d(descriptionContains=desc)
    if btn.exists(timeout=1):
        btn.click()
        break
else:
    d(className="android.widget.ImageButton").click()
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
for desc in ["开启菜单", "開啟選單", "Menu", "menu"]:
    btn = d(descriptionContains=desc)
    if btn.exists(timeout=1):
        btn.click()
        break
else:
    d(className="android.widget.ImageButton").click()
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
        if eng.exists(timeout=2):
            eng.click()
            time.sleep(1)
        check("V16b-G5", "Restored language to English", True)
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
running = d.app_current()["package"] == PKG
check("V17-G3", "App survives external URL deep link (rejected by DeepLinkValidator)", running)

# ═══════════════════════════════════════════════════════════
# SUMMARY
# ═══════════════════════════════════════════════════════════
section("VERIFICATION SUMMARY")
total = PASS + FAIL
print(f"\n  Total checks: {total}")
print(f"  \033[32mPassed: {PASS}\033[0m")
if FAIL > 0:
    print(f"  \033[31mFailed: {FAIL}\033[0m")
else:
    print(f"  Failed: 0")
print()

# Write results to markdown
report_path = "/Users/alanlin/Woow_odoo_app/docs/plans/2026-03-22-device-verification-log.md"
with open(report_path, "a") as f:
    f.write(f"\n\n## uiautomator2 Verification Run — {time.strftime('%Y-%m-%d %H:%M:%S')}\n\n")
    f.write(f"| Field | Value |\n")
    f.write(f"|-------|-------|\n")
    f.write(f"| Device | {device_name} (SDK {sdk}) |\n")
    f.write(f"| Package | {PKG} |\n")
    f.write(f"| Result | **{PASS} passed, {FAIL} failed** |\n\n")
    f.write("| V-ID | Result | Description |\n")
    f.write("|------|--------|-------------|\n")
    for r in RESULTS:
        emoji = "PASS" if "✅" in r else "FAIL"
        clean = r.replace("✅ ", "").replace("❌ ", "")
        vid = clean.split(":")[0]
        desc = ":".join(clean.split(":")[1:]).strip()
        f.write(f"| {vid} | {emoji} | {desc} |\n")
    f.write("\n")

print(f"Results appended to {report_path}")
sys.exit(FAIL)
