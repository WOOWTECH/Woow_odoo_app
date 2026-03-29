# Verification Report: Woow Odoo Android App

> **Date:** 2026-03-29 23:21:12
> **Device:** dew_p_global (SDK 35)
> **Result:** **16 passed, 3 failed** out of 19

---

## How to Read

📱 = Phone side | 🖥️ = Server side

---

## Step 1 [📱 Phone]: Launch app — fresh install, no account

- ✅ Login screen shown with server URL field

![Step 1](screenshots/01_fresh_launch.png)

---

## Step 2 [📱 Phone]: Enter server URL

- ✅ Server URL entered

![Step 2](screenshots/02_server_url.png)

---

## Step 3 [📱 Phone]: Enter database name: odoo18_ecpay

- ✅ Database name entered

![Step 3](screenshots/03_database.png)

---

## Step 4 [📱 Phone]: Tap Next → credentials screen

- ✅ Credentials screen shown

![Step 4](screenshots/04_credentials.png)

---

## Step 5 [📱 Phone]: Enter username: admin

- ✅ Username entered

![Step 5](screenshots/05_username.png)

---

## Step 6 [📱 Phone]: Enter password and tap Login

- ✅ Login successful — WoowTech Odoo title visible

![Step 6](screenshots/06_logged_in.png)

---

## Step 7 [📱 Phone]: Odoo WebView fully loaded

- ✅ Odoo web dashboard visible inside app

![Step 7](screenshots/07_odoo_loaded.png)

---

## Step 8 [📱 Phone]: Open menu → Config screen

- ✅ Config screen with account info

![Step 8](screenshots/08_config.png)

---

## Step 9 [📱 Phone]: Open Settings

- ✅ Settings screen visible

![Step 9](screenshots/09_settings.png)

---

## Step 10 [📱 Phone]: Open color picker

- ✅ Brand preset colors visible
- ✅ HEX input (#RRGGBB) visible after scroll

![Step 10](screenshots/10_color_picker.png)

![Step 10](screenshots/11_color_hex.png)

---

## Step 11 [📱 Phone]: Check language picker

- ✅ 简体中文 option available

![Step 11](screenshots/12_language.png)

---

## Step 12 [🖥️ Server]: Login to Odoo as test user

- ✅ Logged in as test@woowtech.com (uid=636)

---

## Step 13 [🖥️ Server]: Find Azure Interior contact

- ✅ Found Azure Interior (id=15)

---

## Step 14 [🖥️ Server]: Post chatter comment on Azure Interior

- ✅ Comment posted — message_id=6875

---

## Step 15 [🖥️ Server]: Odoo module sends FCM push

- ✅ Log:  INFO odoo18_ecpay odoo.addons.woow_fcm_push.services.fcm_sender: FCM sent to 0/1 devices: Test User

---

## Step 16 [📱 Phone]: Notification appears on phone

- ❌ Sender 'Test User' visible in notification
- ❌ Message preview visible

![Step 16](screenshots/13_notification.png)

---

## Step 17 [📱 Phone]: Tap notification → app opens

- ❌ App opens after tapping notification

![Step 17](screenshots/14_opened_from_tap.png)

---

## Step 18 [📱 Phone]: 3 grouped notifications


---

## Summary

| Checks | 19 |
|---|---|
| Passed | 16 |
| Failed | 3 |
| Screenshots | 14 |
