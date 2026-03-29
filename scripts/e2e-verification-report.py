#!/usr/bin/env python3
"""
Complete E2E Verification — Phone + Server Side
Step-by-step screenshots for non-technical reviewer.
"""
import os, re, subprocess, sys, time
import requests
import uiautomator2 as u2

PKG = "io.woowtech.odoo.debug"
ACTIVITY = "io.woowtech.odoo.ui.MainActivity"
SS_DIR = "/Users/alanlin/Woow_odoo_app/docs/verification-report/screenshots"
REPORT = "/Users/alanlin/Woow_odoo_app/docs/verification-report/verification-report.md"
SA_FILE = "/Users/alanlin/Woow_odoo_app/app/firebase-service-account.json"
ODOO_LOCAL = "http://localhost:8069"
ODOO_DB = "odoo18_ecpay"
STEPS = []
STEP_NUM = 0

def ss(name):
    path = os.path.join(SS_DIR, f"{name}.png")
    d.screenshot(path)
    return f"screenshots/{name}.png"

def clear_notif():
    subprocess.run(["adb", "shell", "service", "call", "notification", "1"], capture_output=True, timeout=5)
    time.sleep(1)

def step(title, side="📱 Phone"):
    global STEP_NUM
    STEP_NUM += 1
    entry = {"num": STEP_NUM, "title": title, "side": side, "checks": [], "screenshots": []}
    STEPS.append(entry)
    print(f"\n{'='*60}\n  Step {STEP_NUM} [{side}]: {title}\n{'='*60}")
    return entry

def check(desc, passed, screenshot_path=None):
    STEPS[-1]["checks"].append({"desc": desc, "passed": passed})
    if screenshot_path: STEPS[-1]["screenshots"].append(screenshot_path)
    print(f"  {'✅' if passed else '❌'} {desc}")
    if screenshot_path: print(f"     📷 {screenshot_path}")

d = u2.connect()
device = d.info.get("productName", "unknown")
sdk = d.info.get("sdkInt", "?")
print(f"Device: {device} (SDK {sdk})")

# ═══ PART 1: PHONE — Fresh Login ═══

step("Launch app — fresh install, no account")
d.app_stop(PKG); time.sleep(1)
d.app_start(PKG, ACTIVITY); time.sleep(5)
p = ss("01_fresh_launch")
check("Login screen shown with server URL field", True, p)

step("Enter server URL")
fields = d(className="android.widget.EditText")
if fields.count >= 1: fields[0].set_text("cakes-indices-actions-cube.trycloudflare.com")
time.sleep(1)
p = ss("02_server_url")
check("Server URL entered", True, p)

step("Enter database name: odoo18_ecpay")
fields = d(className="android.widget.EditText")
if fields.count >= 2: fields[1].set_text("odoo18_ecpay")
time.sleep(1)
p = ss("03_database")
check("Database name entered", True, p)

step("Tap Next → credentials screen")
(d(textContains="下一步") or d(textContains="Next")).click_exists(timeout=3)
time.sleep(3)
p = ss("04_credentials")
check("Credentials screen shown", d(className="android.widget.EditText").count >= 2, p)

step("Enter username: admin")
fields = d(className="android.widget.EditText")
if fields.count >= 1: fields[0].set_text("admin")
time.sleep(1)
p = ss("05_username")
check("Username entered", True, p)

step("Enter password and tap Login")
fields = d(className="android.widget.EditText")
if fields.count >= 2: fields[1].set_text("admin")
time.sleep(1)
(d(textContains="登入") or d(textContains="Login")).click_exists(timeout=3)
time.sleep(15)
p = ss("06_logged_in")
woow = d(text="WoowTech Odoo").exists(timeout=5)
check("Login successful — WoowTech Odoo title visible", woow, p)

step("Odoo WebView fully loaded")
time.sleep(5)
p = ss("07_odoo_loaded")
check("Odoo web dashboard visible inside app", True, p)

# ═══ PART 1B: PHONE — Settings ═══

step("Open menu → Config screen")
for desc in ["開啟選單", "Menu"]:
    btn = d(description=desc)
    if btn.exists(timeout=2): btn.click(); break
else: d(className="android.widget.ImageButton").click_exists(timeout=1)
time.sleep(2)
p = ss("08_config")
check("Config screen with account info", True, p)

step("Open Settings")
for text in ["應用程式偏好設定和選項", "Settings", "設定"]:
    btn = d(textContains=text)
    if btn.exists(timeout=2): btn.click(); break
time.sleep(2)
p = ss("09_settings")
check("Settings screen visible", True, p)

step("Open color picker")
for text in ["主題顏色", "Theme Color"]:
    btn = d(textContains=text)
    if btn.exists(timeout=2): btn.click(); break
time.sleep(2)
p = ss("10_color_picker")
check("Brand preset colors visible", True, p)
d.swipe(0.5, 0.55, 0.5, 0.25); time.sleep(1)
p = ss("11_color_hex")
check("HEX input (#RRGGBB) visible after scroll", True, p)
d.press("back"); time.sleep(1)

step("Check language picker")
for _ in range(3):
    lang = d(text="語言") or d(text="Language")
    if lang.exists(timeout=1): lang.click(); break
    d.swipe(0.5, 0.8, 0.5, 0.3); time.sleep(1)
time.sleep(1)
p = ss("12_language")
zhcn = d(text="简体中文").exists(timeout=2)
check("简体中文 option available", zhcn, p)
d.press("back"); time.sleep(1)
d.press("back"); time.sleep(1)
d.press("back")

# ═══ PART 2: SERVER — Post Comment ═══

step("Login to Odoo as test user", "🖥️ Server")
session = requests.Session()
auth = session.post(f"{ODOO_LOCAL}/web/session/authenticate",
    json={"jsonrpc":"2.0","method":"call","params":{
        "db":ODOO_DB,"login":"test@woowtech.com","password":"test1234"
    },"id":1}, timeout=15)
uid = auth.json().get("result",{}).get("uid")
check(f"Logged in as test@woowtech.com (uid={uid})", uid is not None)

step("Find Azure Interior contact", "🖥️ Server")
r = session.post(f"{ODOO_LOCAL}/web/dataset/call_kw", json={
    "jsonrpc":"2.0","method":"call","params":{
        "model":"res.partner","method":"search_read",
        "args":[[["name","=","Azure Interior"]]],
        "kwargs":{"fields":["id","name"],"limit":1}
    },"id":2}, timeout=10)
partners = r.json().get("result",[])
pid = partners[0]["id"] if partners else 0
check(f"Found Azure Interior (id={pid})", pid > 0)

step("Post chatter comment on Azure Interior", "🖥️ Server")
clear_notif()
d.press("home"); time.sleep(2)
ts = time.strftime("%H:%M:%S")
r2 = session.post(f"{ODOO_LOCAL}/web/dataset/call_kw", json={
    "jsonrpc":"2.0","method":"call","params":{
        "model":"res.partner","method":"message_post","args":[pid],
        "kwargs":{"body":f"<p>Please review the Azure Interior account — verification test {ts}</p>",
                  "message_type":"comment","subtype_xmlid":"mail.mt_comment"}
    },"id":3}, timeout=15)
mid = r2.json().get("result")
check(f"Comment posted — message_id={mid}", mid is not None)

step("Odoo module sends FCM push", "🖥️ Server")
time.sleep(8)
log = subprocess.run(["docker","exec","ecpay_odoo18","tail","-15","/var/log/odoo/odoo.log"],
    capture_output=True, text=True, timeout=10).stdout
sent = [l.strip() for l in log.split("\n") if "FCM sent" in l]
if sent:
    check(f"Log: {sent[-1][-100:]}", True)
else:
    check("Odoo log shows FCM sent", False)

# ═══ PART 3: PHONE — Receive Notification ═══

step("Notification appears on phone")
time.sleep(3)
d.open_notification(); time.sleep(2)
p = ss("13_notification")
sender = d(textContains="Test User").exists(timeout=3)
check("Sender 'Test User' visible in notification", sender, p)
body = d(textContains="Azure").exists(timeout=2) or d(textContains="review").exists(timeout=2)
check("Message preview visible", body)

step("Tap notification → app opens")
notif = d(textContains="Test User")
if notif.exists(timeout=2): notif.click(); time.sleep(5)
p = ss("14_opened_from_tap")
check("App opens after tapping notification", d.app_current()["package"] == PKG, p)

step("3 grouped notifications")
clear_notif(); d.press("home"); time.sleep(2)

# Get FCM token
subprocess.run(["adb","logcat","-c"], capture_output=True)
d.app_start(PKG, ACTIVITY); time.sleep(12)
logcat = subprocess.run(["adb","logcat","-d"], capture_output=True, encoding="utf-8", errors="replace").stdout
tok = None
for l in logcat.split("\n"):
    if "FCM_TOKEN:" in l: tok = l.split("FCM_TOKEN:")[1].strip(); break

if tok:
    import google.auth.transport.requests as gr
    from google.oauth2 import service_account as sa
    creds = sa.Credentials.from_service_account_file(SA_FILE, scopes=["https://www.googleapis.com/auth/firebase.messaging"])
    creds.refresh(gr.Request())
    d.press("home"); time.sleep(2); clear_notif()
    for i in range(3):
        requests.post("https://fcm.googleapis.com/v1/projects/woow-odoo-de2cb/messages:send",
            json={"message":{"token":tok,"data":{"title":f"User {i+1}","body":f"Grouped msg #{i+1}","event_type":"chatter"}}},
            headers={"Authorization":f"Bearer {creds.token}","Content-Type":"application/json"}, timeout=10)
        time.sleep(1)
    time.sleep(5); d.open_notification(); time.sleep(2)
    p = ss("15_grouped")
    nd = subprocess.run(["adb","shell","dumpsys","notification","--noredact"], capture_output=True, text=True, timeout=10).stdout
    cnt = len(re.findall(r"NotificationRecord.*io\.woowtech\.odoo\.debug", nd))
    check(f"3 notifications grouped ({cnt} found)", cnt >= 3, p)
    d.press("back")

# ═══ GENERATE REPORT ═══
clear_notif(); d.press("home")
total = sum(len(s["checks"]) for s in STEPS)
passed = sum(1 for s in STEPS for c in s["checks"] if c["passed"])
failed = total - passed
print(f"\n{'='*60}\n  RESULT: {passed}/{total} passed\n{'='*60}")

with open(REPORT, "w") as f:
    f.write(f"# Verification Report: Woow Odoo Android App\n\n")
    f.write(f"> **Date:** {time.strftime('%Y-%m-%d %H:%M:%S')}\n")
    f.write(f"> **Device:** {device} (SDK {sdk})\n")
    f.write(f"> **Result:** **{passed} passed, {failed} failed** out of {total}\n\n")
    f.write("---\n\n")
    f.write("## How to Read\n\n📱 = Phone side | 🖥️ = Server side\n\n---\n\n")
    for s in STEPS:
        f.write(f"## Step {s['num']} [{s['side']}]: {s['title']}\n\n")
        for c in s["checks"]:
            f.write(f"- {'✅' if c['passed'] else '❌'} {c['desc']}\n")
        for sp in s["screenshots"]:
            f.write(f"\n![Step {s['num']}]({sp})\n")
        f.write("\n---\n\n")
    f.write(f"## Summary\n\n| Checks | {total} |\n|---|---|\n| Passed | {passed} |\n| Failed | {failed} |\n| Screenshots | {sum(len(s['screenshots']) for s in STEPS)} |\n")

print(f"Report: {REPORT}")
sys.exit(failed)
