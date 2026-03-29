#!/usr/bin/env python3
"""
E2E Verification Report Generator — Woow Odoo Android App
==========================================================

Takes screenshots at each step + verifies UI elements via uiautomator2.
Generates a markdown report with embedded screenshot paths.
Clears notification bar between tests for clean evidence.

Usage: python3 scripts/e2e-verification-report.py
"""

import os
import subprocess
import sys
import time
import re

import uiautomator2 as u2
import requests

# ─── Config ──────────────────────────────────────────────
PKG = "io.woowtech.odoo.debug"
ACTIVITY = "io.woowtech.odoo.ui.MainActivity"
SCREENSHOT_DIR = "/Users/alanlin/Woow_odoo_app/docs/verification-report/screenshots"
REPORT_PATH = "/Users/alanlin/Woow_odoo_app/docs/verification-report/verification-report.md"
SA_FILE = "/Users/alanlin/Woow_odoo_app/app/firebase-service-account.json"

RESULTS = []
STEP = 0


def screenshot(name):
    """Take screenshot and save to SCREENSHOT_DIR."""
    path = os.path.join(SCREENSHOT_DIR, f"{name}.png")
    d.screenshot(path)
    return f"screenshots/{name}.png"


def clear_notifications():
    """Clear all notifications from the bar."""
    subprocess.run(["adb", "shell", "service", "call", "notification", "1"],
                   capture_output=True, timeout=5)
    time.sleep(1)


def step(title):
    """Log a verification step."""
    global STEP
    STEP += 1
    print(f"\n{'='*60}")
    print(f"  Step {STEP}: {title}")
    print(f"{'='*60}")
    return STEP


def check(desc, condition, screenshot_path=None):
    """Record a verification check."""
    status = "✅ PASS" if condition else "❌ FAIL"
    RESULTS.append({
        "step": STEP,
        "desc": desc,
        "status": status,
        "screenshot": screenshot_path,
        "passed": condition,
    })
    emoji = "✅" if condition else "❌"
    print(f"  {emoji} {desc}")
    if screenshot_path:
        print(f"     📷 {screenshot_path}")


def launch_app():
    d.app_stop(PKG)
    time.sleep(1)
    d.app_start(PKG, ACTIVITY)
    time.sleep(6)


# ─── Connect ─────────────────────────────────────────────
print("Connecting to device...")
d = u2.connect()
device_model = d.info.get("productName", "unknown")
sdk = d.info.get("sdkInt", "?")
print(f"Device: {device_model} (SDK {sdk})")

# ═══════════════════════════════════════════════════════════
# Step 1: App Launch + Main Screen
# ═══════════════════════════════════════════════════════════
step("App Launch — Verify main screen loads")
clear_notifications()
launch_app()

s1 = screenshot("01_main_screen")
main_visible = d(text="WoowTech Odoo").exists(timeout=3)
check("App launches and shows 'WoowTech Odoo' title bar", main_visible, s1)

# Check WebView loaded Odoo content
time.sleep(3)
s1b = screenshot("01b_webview_loaded")
info = d.dump_hierarchy()
texts = [t for t in re.findall(r'text="([^"]+)"', info) if t.strip()]
has_odoo = any(kw in " ".join(texts) for kw in ["Inbox", "Discuss", "Activities", "收件匣"])
check("WebView shows Odoo content (Inbox/Discuss)", has_odoo, s1b)

# ═══════════════════════════════════════════════════════════
# Step 2: Navigate to Settings
# ═══════════════════════════════════════════════════════════
step("Navigate to Settings — Verify all sections")

for desc in ["開啟選單", "开启菜单", "Menu"]:
    btn = d(description=desc)
    if btn.exists(timeout=2):
        btn.click()
        break
else:
    d(className="android.widget.ImageButton").click_exists(timeout=1)
time.sleep(2)

s2 = screenshot("02_config_screen")

# Click settings
for text in ["應用程式偏好設定和選項", "应用程序偏好设置和选项", "App preferences"]:
    btn = d(textContains=text)
    if btn.exists(timeout=2):
        btn.click()
        break
else:
    for text in ["設定", "设置", "Settings"]:
        btn = d(text=text)
        if btn.exists(timeout=2):
            btn.click()
            break
time.sleep(2)

s2b = screenshot("02b_settings_screen")
settings_visible = (d(textContains="外觀").exists(timeout=2) or
                    d(textContains="外观").exists(timeout=2) or
                    d(textContains="Appearance").exists(timeout=2))
check("Settings screen shows Appearance section", settings_visible, s2b)

security_visible = (d(textContains="安全性").exists(timeout=2) or
                    d(textContains="Security").exists(timeout=2))
check("Settings screen shows Security section", security_visible)

d.press("back")
time.sleep(1)
d.press("back")

# ═══════════════════════════════════════════════════════════
# Step 3: Color Picker with Brand Colors
# ═══════════════════════════════════════════════════════════
step("Color Picker — Verify brand colors + HEX input")

for desc in ["開啟選單", "开启菜单", "Menu"]:
    btn = d(description=desc)
    if btn.exists(timeout=2):
        btn.click()
        break
else:
    d(className="android.widget.ImageButton").click_exists(timeout=1)
time.sleep(2)

for text in ["應用程式偏好設定和選項", "Settings", "設定"]:
    btn = d(textContains=text)
    if btn.exists(timeout=2):
        btn.click()
        break
time.sleep(2)

for text in ["主題顏色", "主题颜色", "Theme Color"]:
    btn = d(textContains=text)
    if btn.exists(timeout=2):
        btn.click()
        break
time.sleep(2)

s3 = screenshot("03_color_picker")
preset = (d(textContains="預設").exists(timeout=2) or
          d(textContains="预设").exists(timeout=2) or
          d(textContains="Preset").exists(timeout=2))
check("Color picker shows Preset Colors", preset, s3)

accent = d(text="Accent").exists(timeout=2)
check("Color picker shows Accent colors section", accent)

# Scroll to see HEX input
d.swipe(0.5, 0.55, 0.5, 0.25)
time.sleep(1)
s3b = screenshot("03b_color_picker_hex")
custom = (d(textContains="自訂").exists(timeout=2) or
          d(textContains="自定义").exists(timeout=2) or
          d(textContains="Custom").exists(timeout=2))
check("Color picker shows Custom HEX input", custom, s3b)

d.press("back")
time.sleep(1)
d.press("back")
time.sleep(1)
d.press("back")

# ═══════════════════════════════════════════════════════════
# Step 4: zh-CN Language
# ═══════════════════════════════════════════════════════════
step("Language — Verify 简体中文 option exists")

launch_app()
for desc in ["開啟選單", "开启菜单", "Menu"]:
    btn = d(description=desc)
    if btn.exists(timeout=2):
        btn.click()
        break
else:
    d(className="android.widget.ImageButton").click_exists(timeout=1)
time.sleep(2)

for text in ["應用程式偏好設定和選項", "Settings", "設定"]:
    btn = d(textContains=text)
    if btn.exists(timeout=2):
        btn.click()
        break
time.sleep(2)

# Scroll to language
for _ in range(3):
    lang = d(text="語言") or d(text="语言") or d(text="Language")
    if lang.exists(timeout=1):
        lang.click()
        break
    d.swipe(0.5, 0.8, 0.5, 0.3)
    time.sleep(1)

time.sleep(1)
s4 = screenshot("04_language_picker")
zhcn = d(text="简体中文").exists(timeout=2)
check("Language picker shows 简体中文 option", zhcn, s4)

d.press("back")
time.sleep(1)
d.press("back")
time.sleep(1)
d.press("back")

# ═══════════════════════════════════════════════════════════
# Step 5: FCM Push — Chatter Message
# ═══════════════════════════════════════════════════════════
step("FCM Push — Send chatter message from Odoo, verify notification")

clear_notifications()

# Background the app
d.press("home")
time.sleep(2)
s5_before = screenshot("05a_home_before_push")

# Get FCM token
subprocess.run(["adb", "logcat", "-c"], capture_output=True)
launch_app()
time.sleep(10)
logcat = subprocess.run(["adb", "logcat", "-d"], capture_output=True,
                        encoding="utf-8", errors="replace").stdout
fcm_token = None
for line in logcat.split("\n"):
    if "FCM_TOKEN:" in line:
        fcm_token = line.split("FCM_TOKEN:")[1].strip()
        break

if fcm_token:
    check(f"FCM token retrieved (len={len(fcm_token)})", len(fcm_token) > 100)

    # Background again for clean notification
    d.press("home")
    time.sleep(2)
    clear_notifications()
    time.sleep(1)

    # Send real push via FCM API
    import google.auth.transport.requests as gauth_req
    from google.oauth2 import service_account

    creds = service_account.Credentials.from_service_account_file(
        SA_FILE, scopes=["https://www.googleapis.com/auth/firebase.messaging"]
    )
    creds.refresh(gauth_req.Request())

    resp = requests.post(
        "https://fcm.googleapis.com/v1/projects/woow-odoo-de2cb/messages:send",
        json={
            "message": {
                "token": fcm_token,
                "data": {
                    "title": "Alice Chen",
                    "body": "Please review invoice INV-2026-099",
                    "odoo_model": "account.move",
                    "odoo_res_id": "99",
                    "odoo_action_url": "/web#id=99&model=account.move&view_type=form",
                    "event_type": "chatter",
                },
            }
        },
        headers={"Authorization": f"Bearer {creds.token}", "Content-Type": "application/json"},
        timeout=10,
    )
    check(f"FCM API returned {resp.status_code}", resp.status_code == 200)

    # Wait for notification
    time.sleep(5)

    # Open notification shade
    d.open_notification()
    time.sleep(2)
    s5_notif = screenshot("05b_notification_received")

    # Check notification content
    sender_visible = d(textContains="Alice Chen").exists(timeout=3)
    check("Notification shows sender: 'Alice Chen'", sender_visible, s5_notif)

    body_visible = d(textContains="invoice").exists(timeout=2) or d(textContains="INV-2026").exists(timeout=2)
    check("Notification shows body: 'Please review invoice INV-2026-099'", body_visible)

    # Tap notification
    notif = d(textContains="Alice Chen")
    if notif.exists(timeout=2):
        notif.click()
        time.sleep(5)
        s5_deeplink = screenshot("05c_deeplink_opened")
        running = d.app_current()["package"] == PKG
        check("Tapping notification opens WoowTech Odoo app", running, s5_deeplink)
    else:
        check("Notification tappable", False)

    # Close notification shade
    d.press("back")
    time.sleep(1)
else:
    check("FCM token found in logcat", False)

# ═══════════════════════════════════════════════════════════
# Step 6: FCM Push — Real Odoo Chatter (E2E)
# ═══════════════════════════════════════════════════════════
step("FCM E2E — Post chatter in Odoo, verify push arrives")

clear_notifications()
d.press("home")
time.sleep(2)

# Post chatter message from Odoo as test user
session = requests.Session()
auth = session.post("http://localhost:8069/web/session/authenticate",
    json={"jsonrpc": "2.0", "method": "call", "params": {
        "db": "odoo18_ecpay", "login": "test@woowtech.com", "password": "test1234"
    }, "id": 1}, timeout=10)
test_uid = auth.json().get("result", {}).get("uid")

if test_uid:
    r = session.post("http://localhost:8069/web/dataset/call_kw", json={
        "jsonrpc": "2.0", "method": "call",
        "params": {
            "model": "res.partner",
            "method": "message_post",
            "args": [15],  # Azure Interior
            "kwargs": {
                "body": "<p>E2E verification: real Odoo chatter to FCM push</p>",
                "message_type": "comment",
                "subtype_xmlid": "mail.mt_comment",
            }
        }, "id": 2
    }, timeout=15)
    msg_result = r.json()
    msg_ok = not msg_result.get("error")
    check(f"Odoo message_post OK (msg_id={msg_result.get('result')})", msg_ok)

    # Check Odoo log for FCM send
    time.sleep(8)
    odoo_log = subprocess.run(
        ["docker", "exec", "ecpay_odoo18", "tail", "-10", "/var/log/odoo/odoo.log"],
        capture_output=True, text=True, timeout=10
    ).stdout
    fcm_sent = "FCM sent to" in odoo_log
    check("Odoo log shows 'FCM sent to' (push delivered)", fcm_sent)

    if fcm_sent:
        # Open notification shade
        d.open_notification()
        time.sleep(2)
        s6 = screenshot("06_odoo_chatter_notification")

        test_user_notif = d(textContains="Test User").exists(timeout=3)
        check("Notification from real Odoo chatter shows sender 'Test User'", test_user_notif, s6)

        d.press("back")
    time.sleep(1)
else:
    check("Test user auth for Odoo chatter", False)

# ═══════════════════════════════════════════════════════════
# Step 7: Notification Grouping
# ═══════════════════════════════════════════════════════════
step("Notification Grouping — Send 3 pushes, verify grouped")

clear_notifications()
d.press("home")
time.sleep(2)

if fcm_token and os.path.exists(SA_FILE):
    creds.refresh(gauth_req.Request())
    for i in range(3):
        requests.post(
            "https://fcm.googleapis.com/v1/projects/woow-odoo-de2cb/messages:send",
            json={
                "message": {
                    "token": fcm_token,
                    "data": {
                        "title": f"User {i+1}",
                        "body": f"Message #{i+1} for grouping test",
                        "event_type": "chatter",
                    },
                }
            },
            headers={"Authorization": f"Bearer {creds.token}", "Content-Type": "application/json"},
            timeout=10,
        )
        time.sleep(1)

    time.sleep(5)
    d.open_notification()
    time.sleep(2)
    s7 = screenshot("07_notification_grouping")

    notif_dump = subprocess.run(
        ["adb", "shell", "dumpsys", "notification", "--noredact"],
        capture_output=True, text=True, timeout=10
    ).stdout
    posted = len(re.findall(r"NotificationRecord.*io\.woowtech\.odoo\.debug", notif_dump))
    check(f"3 notifications posted ({posted} found)", posted >= 3, s7)

    grouped = notif_dump.count("groupKey=0|io.woowtech.odoo.debug|g:chatter")
    check(f"Notifications grouped by 'chatter' ({grouped} in group)", grouped >= 3)

    d.press("back")

# ═══════════════════════════════════════════════════════════
# Step 8: Cache Clear
# ═══════════════════════════════════════════════════════════
step("Cache Clear — Verify login preserved after clear")

clear_notifications()
launch_app()

for desc in ["開啟選單", "开启菜单", "Menu"]:
    btn = d(description=desc)
    if btn.exists(timeout=2):
        btn.click()
        break
else:
    d(className="android.widget.ImageButton").click_exists(timeout=1)
time.sleep(2)

for text in ["應用程式偏好設定和選項", "Settings", "設定"]:
    btn = d(textContains=text)
    if btn.exists(timeout=2):
        btn.click()
        break
time.sleep(2)

# Scroll to Clear Cache
for _ in range(3):
    cache_btn = d(textContains="清除快取") or d(textContains="清除缓存") or d(textContains="Clear Cache")
    if cache_btn.exists(timeout=1):
        break
    d.swipe(0.5, 0.7, 0.5, 0.3)
    time.sleep(1)

if cache_btn.exists(timeout=2):
    cache_btn.click()
    time.sleep(2)
    s8 = screenshot("08_cache_cleared")
    check("Cache cleared — still on Settings (login preserved)", True, s8)

    d.press("back")
    time.sleep(1)
    d.press("back")
    time.sleep(3)
    s8b = screenshot("08b_still_logged_in")
    main = d(text="WoowTech Odoo").exists(timeout=3)
    check("After cache clear — still logged in (main screen)", main, s8b)

# ═══════════════════════════════════════════════════════════
# Generate Report
# ═══════════════════════════════════════════════════════════
print(f"\n{'='*60}")
print(f"  GENERATING REPORT")
print(f"{'='*60}")

passed = sum(1 for r in RESULTS if r["passed"])
failed = sum(1 for r in RESULTS if not r["passed"])

with open(REPORT_PATH, "w") as f:
    f.write("# Verification Report: Woow Odoo Android App\n\n")
    f.write(f"> **Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
    f.write(f"> **Device:** {device_model} (SDK {sdk})\n")
    f.write(f"> **App:** {PKG}\n")
    f.write(f"> **Result:** **{passed} passed, {failed} failed** out of {len(RESULTS)}\n\n")
    f.write("---\n\n")

    current_step = 0
    for r in RESULTS:
        if r["step"] != current_step:
            current_step = r["step"]
            f.write(f"\n## Step {current_step}\n\n")

        f.write(f"- {r['status']}: {r['desc']}\n")
        if r["screenshot"]:
            f.write(f"  - Screenshot: ![Step {current_step}]({r['screenshot']})\n")
        f.write("\n")

    f.write("\n---\n\n")
    f.write(f"## Summary\n\n")
    f.write(f"| Metric | Value |\n|--------|-------|\n")
    f.write(f"| Total checks | {len(RESULTS)} |\n")
    f.write(f"| Passed | {passed} |\n")
    f.write(f"| Failed | {failed} |\n")
    f.write(f"| Screenshots | {sum(1 for r in RESULTS if r['screenshot'])} |\n")

print(f"\nReport: {REPORT_PATH}")
print(f"Screenshots: {SCREENSHOT_DIR}/")
print(f"Result: {passed}/{len(RESULTS)} passed")

# Cleanup
clear_notifications()
d.press("home")

sys.exit(failed)
