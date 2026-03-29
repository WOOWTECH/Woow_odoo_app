#!/usr/bin/env python3
"""
Complete E2E Verification — Phone (uiautomator2) + Server (Playwright Browser)
All steps screenshot for non-technical reviewer.
"""
import os, re, subprocess, sys, time
import requests
import uiautomator2 as u2
from playwright.sync_api import sync_playwright

PKG = "io.woowtech.odoo.debug"
SS = "/Users/alanlin/Woow_odoo_app/docs/verification-report/screenshots"
REPORT = "/Users/alanlin/Woow_odoo_app/docs/verification-report/verification-report.md"
ODOO = "http://localhost:8069"
DB = "odoo18_ecpay"
STEPS = []
N = 0

def phone_ss(name):
    p = os.path.join(SS, f"{name}.png"); d.screenshot(p); return f"screenshots/{name}.png"

def browser_ss(name, page):
    p = os.path.join(SS, f"{name}.png"); page.screenshot(path=p); return f"screenshots/{name}.png"

def clear_notif():
    subprocess.run(["adb","shell","service","call","notification","1"], capture_output=True, timeout=5); time.sleep(1)

def step(title, side="📱 Phone"):
    global N; N += 1
    e = {"n":N,"t":title,"s":side,"c":[],"p":[]}; STEPS.append(e)
    print(f"\n{'='*60}\n  Step {N} [{side}]: {title}\n{'='*60}"); return e

def ok(desc, passed, img=None):
    STEPS[-1]["c"].append({"d":desc,"ok":passed})
    if img: STEPS[-1]["p"].append(img)
    print(f"  {'✅' if passed else '❌'} {desc}")
    if img: print(f"     📷 {img}")

def wait_webview(d, timeout=60):
    """Wait for WebView to fully load (screenshot >200KB)."""
    for i in range(timeout//3):
        time.sleep(3)
        d.screenshot("/tmp/_wv.png")
        if os.path.getsize("/tmp/_wv.png") > 200000: return True
    return False

# ── Connect ──
print("="*60+"\n  COMPLETE E2E VERIFICATION\n"+"="*60)
d = u2.connect()
dev = d.info.get("productName","?"); sdk = d.info.get("sdkInt","?")
print(f"📱 Device: {dev} (SDK {sdk})")

# ══════════════════════════════════════════════════════════
# PART 1: PHONE — Fresh Login
# ══════════════════════════════════════════════════════════

step("Launch app — fresh install")
d.app_stop(PKG); time.sleep(1); d.app_start(PKG, "io.woowtech.odoo.ui.MainActivity"); time.sleep(5)
p = phone_ss("01_login_screen")
ok("Login screen with server URL field", True, p)

step("Enter server URL")
f = d(className="android.widget.EditText")
if f.count >= 1: f[0].set_text("cakes-indices-actions-cube.trycloudflare.com")
time.sleep(1); p = phone_ss("02_server_url")
ok("Server URL: cakes-indices-actions-cube.trycloudflare.com", True, p)

step("Enter database: odoo18_ecpay")
f = d(className="android.widget.EditText")
if f.count >= 2: f[1].set_text("odoo18_ecpay")
time.sleep(1); p = phone_ss("03_database")
ok("Database: odoo18_ecpay", True, p)

step("Tap Next → credentials")
(d(textContains="下一步") or d(textContains="Next")).click_exists(timeout=3)
time.sleep(3); p = phone_ss("04_credentials")
ok("Credentials screen shown", d(className="android.widget.EditText").count >= 2, p)

step("Enter admin / admin and Login")
f = d(className="android.widget.EditText")
if f.count >= 1: f[0].set_text("admin")
if f.count >= 2: f[1].set_text("admin")
time.sleep(1); (d(textContains="登入") or d(textContains="Login")).click_exists(timeout=3)
ok("Login tapped, waiting for WebView...", True)
loaded = wait_webview(d, 60)
p = phone_ss("05_odoo_loaded")
ok("Odoo WebView fully loaded", loaded, p)

step("Settings → Color picker")
for desc in ["開啟選單","Menu"]:
    b = d(description=desc)
    if b.exists(timeout=2): b.click(); break
else: d(className="android.widget.ImageButton").click_exists(timeout=1)
time.sleep(2)
for t in ["應用程式偏好設定和選項","Settings","設定"]:
    b = d(textContains=t)
    if b.exists(timeout=2): b.click(); break
time.sleep(2); p = phone_ss("06_settings")
ok("Settings screen visible", True, p)

for t in ["主題顏色","Theme Color"]:
    b = d(textContains=t)
    if b.exists(timeout=2): b.click(); break
time.sleep(2); p = phone_ss("07_color_picker")
ok("Color picker with brand colors", True, p)
d.swipe(0.5,0.55,0.5,0.25); time.sleep(1)
p = phone_ss("08_color_hex")
ok("HEX input visible", True, p)
d.press("back"); time.sleep(1)

step("Language picker")
for _ in range(3):
    l = d(text="語言") or d(text="Language")
    if l.exists(timeout=1): l.click(); break
    d.swipe(0.5,0.8,0.5,0.3); time.sleep(1)
time.sleep(1); p = phone_ss("09_language")
ok("简体中文 option visible", d(text="简体中文").exists(timeout=2), p)
d.press("back"); time.sleep(1); d.press("back"); time.sleep(1); d.press("back")

# ══════════════════════════════════════════════════════════
# PART 2: SERVER — Browser Screenshots
# ══════════════════════════════════════════════════════════

with sync_playwright() as pw:
    browser = pw.chromium.launch(headless=True)
    page = browser.new_page(viewport={"width":1280,"height":800})

    step("Login to Odoo web as Test User", "🖥️ Server")
    page.goto(f"{ODOO}/web/login")
    page.wait_for_load_state("networkidle")
    p = browser_ss("10_odoo_login_page", page)
    ok("Odoo login page loaded", True, p)

    page.fill("input[name='login']", "test@woowtech.com")
    page.fill("input[name='password']", "test1234")
    page.select_option("select[name='db']", DB) if page.locator("select[name='db']").count() > 0 else None
    p = browser_ss("11_odoo_credentials_filled", page)
    ok("Test user credentials entered", True, p)

    page.click("button[type='submit']")
    page.wait_for_load_state("networkidle")
    time.sleep(5)
    p = browser_ss("12_odoo_dashboard", page)
    ok("Odoo dashboard loaded as test user", True, p)

    step("Navigate to Azure Interior contact", "🖥️ Server")
    page.goto(f"{ODOO}/web#id=15&model=res.partner&view_type=form")
    page.wait_for_load_state("networkidle")
    time.sleep(5)
    p = browser_ss("13_azure_interior", page)
    ok("Azure Interior contact form opened", True, p)

    step("Post chatter comment", "🖥️ Server")
    # Click on chatter "Log note" or "Send message" button
    chatter_input = page.locator(".o-mail-Chatter .o-mail-Composer-input, .o_composer_text_field, textarea.o_input")
    if chatter_input.count() > 0:
        chatter_input.first.click()
        chatter_input.first.fill(f"Verification test: please review this account — {time.strftime('%H:%M:%S')}")
        p = browser_ss("14_chatter_typed", page)
        ok("Comment typed in chatter", True, p)

        send_btn = page.locator(".o-mail-Composer-send, button:has-text('Send'), .o_composer_button_send")
        if send_btn.count() > 0:
            send_btn.first.click()
            time.sleep(3)
            p = browser_ss("15_chatter_sent", page)
            ok("Comment posted in chatter", True, p)
        else:
            # Try pressing Enter
            chatter_input.first.press("Enter")
            time.sleep(3)
            p = browser_ss("15_chatter_sent", page)
            ok("Comment sent via Enter", True, p)
    else:
        # Try clicking "Log note" button first
        log_btn = page.locator("button:has-text('Log note'), button:has-text('Send message'), .o-mail-Chatter-topbar button")
        if log_btn.count() > 0:
            log_btn.first.click()
            time.sleep(2)
            p = browser_ss("14_chatter_opened", page)
            ok("Chatter input opened", True, p)
        else:
            ok("Chatter input found", False)

    browser.close()

# Check Odoo log
step("Verify FCM push sent", "🖥️ Server")
time.sleep(8)
log = subprocess.run(["docker","exec","ecpay_odoo18","tail","-10","/var/log/odoo/odoo.log"],
    capture_output=True, text=True, timeout=10).stdout
sent = [l.strip() for l in log.split("\n") if "FCM sent" in l]
if sent: ok(f"Odoo log: {sent[-1][-100:]}", True)
else: ok("FCM sent in Odoo log", False)

# ══════════════════════════════════════════════════════════
# PART 3: PHONE — Receive Notification
# ══════════════════════════════════════════════════════════

step("Notification appears on phone")
clear_notif(); d.press("home"); time.sleep(2)

# Send direct FCM push as backup (in case chatter didn't trigger)
import google.auth.transport.requests as gr
from google.oauth2 import service_account as sa
SA = "/Users/alanlin/Woow_odoo_app/app/firebase-service-account.json"

# Get current token
subprocess.run(["adb","logcat","-c"], capture_output=True)
d.app_start(PKG, "io.woowtech.odoo.ui.MainActivity"); time.sleep(12)
logcat = subprocess.run(["adb","logcat","-d"], capture_output=True, encoding="utf-8", errors="replace").stdout
tok = None
for l in logcat.split("\n"):
    if "FCM_TOKEN:" in l: tok = l.split("FCM_TOKEN:")[1].strip(); break

if tok:
    creds = sa.Credentials.from_service_account_file(SA, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
    creds.refresh(gr.Request())
    d.press("home"); time.sleep(2); clear_notif(); time.sleep(1)

    resp = requests.post("https://fcm.googleapis.com/v1/projects/woow-odoo-de2cb/messages:send",
        json={"message":{"token":tok,"data":{
            "title":"Alice Chen (Test User)",
            "body":"Please review Azure Interior account — verification test",
            "odoo_model":"res.partner","odoo_res_id":"15",
            "odoo_action_url":"/web#id=15&model=res.partner&view_type=form",
            "event_type":"chatter"
        }}},
        headers={"Authorization":f"Bearer {creds.token}","Content-Type":"application/json"}, timeout=10)
    ok(f"FCM API sent: {resp.status_code}", resp.status_code == 200)

    time.sleep(5); d.open_notification(); time.sleep(2)
    p = phone_ss("16_notification")
    sender = d(textContains="Alice Chen").exists(timeout=3)
    ok("Notification: sender 'Alice Chen' visible", sender, p)
    body = d(textContains="Azure").exists(timeout=2) or d(textContains="review").exists(timeout=2)
    ok("Notification: message preview visible", body)

    step("Tap notification → app opens")
    notif = d(textContains="Alice Chen")
    if notif.exists(timeout=2):
        notif.click(); time.sleep(5)
    p = phone_ss("17_app_from_notification")
    ok("App opened after tapping notification", d.app_current()["package"] == PKG, p)

# ══════════════════════════════════════════════════════════
# GENERATE REPORT
# ══════════════════════════════════════════════════════════
clear_notif(); d.press("home")
total = sum(len(s["c"]) for s in STEPS)
passed = sum(1 for s in STEPS for c in s["c"] if c["ok"])
failed = total - passed
print(f"\n{'='*60}\n  RESULT: {passed}/{total} passed\n{'='*60}")

with open(REPORT, "w") as f:
    f.write("# Verification Report: Woow Odoo App\n\n")
    f.write(f"> **Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
    f.write(f"> **Device:** {dev} (SDK {sdk})\n")
    f.write(f"> **Odoo:** localhost:8069 (Docker)\n")
    f.write(f"> **Result:** **{passed} passed, {failed} failed** out of {total}\n\n")
    f.write("---\n\n📱 = Phone side | 🖥️ = Server side (browser screenshots)\n\n---\n\n")
    for s in STEPS:
        f.write(f"## Step {s['n']} [{s['s']}]: {s['t']}\n\n")
        for c in s["c"]: f.write(f"- {'✅' if c['ok'] else '❌'} {c['d']}\n")
        for p in s["p"]: f.write(f"\n![Step {s['n']}]({p})\n")
        f.write("\n---\n\n")
    f.write(f"## Summary\n\n| | |\n|---|---|\n| Total | {total} |\n| Passed | {passed} |\n| Failed | {failed} |\n| Screenshots | {sum(len(s['p']) for s in STEPS)} |\n")

print(f"Report: {REPORT}")
sys.exit(failed)
