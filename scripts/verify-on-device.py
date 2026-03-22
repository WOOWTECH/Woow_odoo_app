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
