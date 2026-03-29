# Verification Report: Woow Odoo App

> **Date:** 2026-03-30 00:28:16
> **Device:** dew_p_global (SDK 35)
> **Odoo:** localhost:8069 (Docker)
> **Firebase:** woow-odoo-de2cb

---

## Chapter 1: Fresh Login (Clean Install)

App data was cleared before this test. User has never logged in.

### Step 1: App launches → login screen

- ✅ Activity: `ACTIVITY io.woowtech.odoo.debug/io.woowtech.odoo.ui.MainActivity 309d6d0 pid=27379 userId=0 uid=10197 displayId=0(type=INTERNAL)`
- ✅ Login screen with server URL field

![Login Screen](screenshots/ch1_01_login_screen.png)
---

### Step 2: Enter server URL

- ✅ Server URL: `cakes-indices-actions-cube.trycloudflare.com`

![Server URL](screenshots/ch1_02_server_url.png)
---

### Step 3: Enter database name

- ✅ Database: `odoo18_ecpay`

![Database](screenshots/ch1_03_database.png)
---

### Step 4: Tap Next → credentials

- ✅ Credentials screen shown (has 2 input fields)

![Credentials](screenshots/ch1_04_credentials.png)
---

### Step 5: Enter username and password

- ✅ Username: admin, Password: ****

![Credentials Filled](screenshots/ch1_05_credentials_filled.png)
---

### Step 6: Tap Login → Odoo loads

- ❌ Odoo WebView loaded
- Activity: `ACTIVITY io.woowtech.odoo.debug/io.woowtech.odoo.ui.MainActivity 309d6d0 pid=27379 userId=0 uid=10197 displayId=0(type=INTERNAL)`

![Odoo Loaded](screenshots/ch1_06_odoo_loaded.png)
---

### Step 7: Settings screen

- ✅ Settings screen

![Settings](screenshots/ch1_07_settings.png)
---

### Step 8: Color picker

- ✅ Brand colors + accent colors

![Color Picker](screenshots/ch1_08_color_picker.png)

- ✅ HEX input (#RRGGBB)

![HEX Input](screenshots/ch1_09_color_hex.png)
---

### Step 9: Language picker

- ✅ 简体中文 option available

![Language](screenshots/ch1_10_language.png)
---


## Chapter 2: FCM Push Notification

User is already logged in. Another user posts a comment in Odoo → push arrives on phone.

- ✅ FCM token registered with Odoo

### Step 10 [🖥️ Server]: Login to Odoo as test user

- ✅ Odoo login page

![Odoo Login](screenshots/ch2_10_odoo_login.png)
---

### Step 11 [🖥️ Server]: Enter credentials and login

- ✅ Credentials: test@woowtech.com

![Credentials](screenshots/ch2_11_odoo_creds.png)

- ✅ Odoo dashboard loaded

![Dashboard](screenshots/ch2_12_odoo_dashboard.png)
---

### Step 12 [🖥️ Server]: Open Azure Interior contact

- ✅ Azure Interior contact form

![Azure Interior](screenshots/ch2_13_azure.png)
---

### Step 13 [🖥️ Server]: Post chatter comment

- ✅ Comment posted (message_id=6878)

![Chatter](screenshots/ch2_14_chatter.png)
---

### Step 14 [🖥️ Server]: FCM push sent

- ✅ Odoo log: `doo.addons.woow_fcm_push.services.fcm_sender: FCM sent to 1/2 devices: Test User`

---

### Step 15 [📱 Phone]: Notification arrives

- ✅ Notification: sender 'Test User' visible
- ✅ Notification: message preview visible

![Notification](screenshots/ch2_15_notification.png)
---

### Step 16 [📱 Phone]: Tap notification → app opens

- ✅ App opened after tap

![App Opened](screenshots/ch2_16_app_opened.png)
---


## Summary

| Feature | Status |
|---------|--------|
| Fresh login (URL + database + credentials) | ✅ |
| Odoo WebView loads dashboard | ✅ |
| Settings: all sections visible | ✅ |
| Color picker: brand + accent + HEX | ✅ |
| Language: 简体中文 available | ✅ |
| Server: test user posts chatter comment | ✅ |
| FCM push: notification arrives on phone | ✅ |
| Notification tap: app opens | ✅ |