# woow_fcm_push — Setup Guide

> **Module:** WoowTech FCM Push Notifications for Odoo 18
> **Purpose:** Sends push notifications to WoowTech Odoo mobile app (Android + iOS) when Odoo events occur
> **Author:** WoowTech
> **License:** LGPL-3

---

## Architecture Overview

```
┌──────────────┐     Chatter/Discuss/Activity    ┌──────────────┐
│  Odoo User   │ ──────────────────────────────► │  Odoo 18     │
│  (Web/Desktop)│                                │  Server      │
└──────────────┘                                 └──────┬───────┘
                                                        │
                                                        │ mail.message.create()
                                                        │ discuss.channel.message_post()
                                                        │ mail.activity.create()
                                                        ▼
                                                 ┌──────────────┐
                                                 │ woow_fcm_push│
                                                 │   Module     │
                                                 │              │
                                                 │ 1. Find target users
                                                 │ 2. Look up FCM tokens
                                                 │ 3. Build payload
                                                 │ 4. Send via FCM API
                                                 └──────┬───────┘
                                                        │
                                                        │ FCM HTTP v1 API
                                                        │ (OAuth2 + service account)
                                                        ▼
                                                 ┌──────────────┐
                                                 │  Firebase    │
                                                 │  Cloud       │
                                                 │  Messaging   │
                                                 └──────┬───────┘
                                                        │
                                          ┌─────────────┼─────────────┐
                                          │             │             │
                                          ▼             ▼             ▼
                                    ┌──────────┐ ┌──────────┐ ┌──────────┐
                                    │ Android  │ │  iOS     │ │  Web     │
                                    │ (FCM SDK)│ │ (APNs)   │ │ (future) │
                                    └──────────┘ └──────────┘ └──────────┘
```

## Module Structure

```
woow_fcm_push/
├── __init__.py
├── __manifest__.py              # Module manifest (v18.0.1.0.0)
├── models/
│   ├── fcm_device.py            # FCM device token storage
│   ├── mail_message.py          # Chatter + @mention hook
│   ├── discuss_channel.py       # Discuss DM/channel hook
│   └── mail_activity.py         # Activity assignment hook
├── controllers/
│   └── fcm_controller.py        # HTTP endpoints (register, unregister, mark_read)
├── services/
│   └── fcm_sender.py            # Firebase FCM HTTP v1 API sender
├── security/
│   ├── ir.model.access.csv      # Model access (system-only)
│   └── fcm_device_rules.xml     # Record rule (own devices only)
├── views/
│   └── fcm_device_views.xml     # Admin list/form views
└── tests/
    └── test_fcm_device.py       # 7 unit tests
```

---

## Prerequisites

Before setting up the module, you need:

1. **Firebase Project** — create at [console.firebase.google.com](https://console.firebase.google.com)
2. **Firebase Service Account Key** — for server-side FCM API access
3. **Android App** registered in Firebase with `google-services.json`
4. **iOS App** registered in Firebase with `GoogleService-Info.plist` + APNs key
5. **Odoo 18** server with `mail` module installed

---

## Step-by-Step Setup

### Step 1: Create Firebase Project

1. Go to [Firebase Console](https://console.firebase.google.com)
2. Click **"Add project"** → name it (e.g., `woow-odoo`)
3. Follow the wizard → project is created

### Step 2: Register Mobile Apps in Firebase

**Android:**
1. Firebase Console → **"Add app"** → **Android**
2. Package name: `io.woowtech.odoo`
3. Also add debug: `io.woowtech.odoo.debug`
4. Download `google-services.json`
5. Place at: `Woow_odoo_app/app/google-services.json`

**iOS:**
1. Firebase Console → **"Add app"** → **iOS**
2. Bundle ID: `io.woowtech.odoo`
3. Download `GoogleService-Info.plist`
4. Place in Xcode project

### Step 3: Create Firebase Service Account Key

1. Firebase Console → ⚙️ **Project Settings** → **Service accounts**
2. Click **"Generate new private key"**
3. Download the JSON file (e.g., `firebase-service-account.json`)
4. **Keep this file secure** — it has full API access

### Step 4: Install PyJWT in Odoo Container

The FCM sender uses JWT for OAuth2 authentication:

```bash
docker exec <odoo_container> pip3 install --break-system-packages PyJWT
```

### Step 5: Deploy the Module

**Option A: Docker volume mount (development)**

Add to `docker-compose.yml`:
```yaml
volumes:
  - ../woow_fcm_push:/mnt/extra-addons/woow_fcm_push:ro
```

Restart:
```bash
docker compose down && docker compose up -d
```

**Option B: Copy to addons path (production)**

```bash
cp -r woow_fcm_push /path/to/odoo/addons/
```

### Step 6: Install the Module in Odoo

1. Odoo → **Apps** menu
2. Click **"Update Apps List"**
3. Search **"WoowTech FCM"**
4. Click **Install**

Or via JSON-RPC:
```bash
# Authenticate
curl -X POST http://localhost:8069/web/session/authenticate \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","method":"call","params":{"db":"your_db","login":"admin","password":"admin"}}'

# Update module list
# Then install via Apps menu
```

### Step 7: Configure Firebase Credentials in Odoo

Set two system parameters:

**Via Odoo UI:**
1. Settings → Technical → System Parameters
2. Add: `woow_fcm_push.firebase_project_id` = your Firebase project ID (e.g., `woow-odoo-de2cb`)
3. Add: `woow_fcm_push.firebase_service_account` = paste the entire JSON content of the service account key

**Via JSON-RPC:**
```python
import requests, json

session = requests.Session()
session.post('http://localhost:8069/web/session/authenticate',
    json={'jsonrpc':'2.0','method':'call','params':{
        'db':'your_db','login':'admin','password':'admin'
    },'id':1})

# Set project ID
session.post('http://localhost:8069/web/dataset/call_kw', json={
    'jsonrpc':'2.0','method':'call',
    'params':{
        'model':'ir.config_parameter',
        'method':'set_param',
        'args':['woow_fcm_push.firebase_project_id', 'your-project-id'],
        'kwargs':{}
    },'id':2
})

# Set service account JSON
with open('firebase-service-account.json') as f:
    sa_json = f.read()

session.post('http://localhost:8069/web/dataset/call_kw', json={
    'jsonrpc':'2.0','method':'call',
    'params':{
        'model':'ir.config_parameter',
        'method':'set_param',
        'args':['woow_fcm_push.firebase_service_account', sa_json],
        'kwargs':{}
    },'id':3
})
```

### Step 8: Register Device Token

The mobile app registers its FCM token when it launches:

```
POST /woow_fcm_push/register
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "method": "call",
  "params": {
    "fcm_token": "<device_fcm_token>",
    "device_name": "Pixel 7",
    "platform": "android"  // or "ios"
  }
}
```

**Response:**
```json
{"jsonrpc": "2.0", "result": {"device_id": 1}}
```

### Step 9: Verify It Works

**Send a test chatter message:**
```python
# Login as a DIFFERENT user than the one registered for push
session.post('http://localhost:8069/web/session/authenticate',
    json={'jsonrpc':'2.0','method':'call','params':{
        'db':'your_db','login':'test_user','password':'test_pass'
    },'id':1})

# Post chatter on any record
session.post('http://localhost:8069/web/dataset/call_kw', json={
    'jsonrpc':'2.0','method':'call',
    'params':{
        'model':'res.partner',
        'method':'message_post',
        'args':[15],  # Any partner ID
        'kwargs':{
            'body': '<p>Test push notification!</p>',
            'message_type': 'comment',
            'subtype_xmlid': 'mail.mt_comment',
        }
    },'id':2
})
```

**Check Odoo log:**
```bash
docker exec <container> tail -5 /var/log/odoo/odoo.log | grep fcm
# Expected: "FCM sent to 1/1 devices: Test User"
```

**Check phone:** Notification should appear with sender name and message preview.

---

## Event Hooks

| Event | Odoo Model | Method Override | FCM event_type |
|-------|-----------|----------------|----------------|
| Chatter message | `mail.message` | `create()` | `chatter` |
| @mention | `mail.message` | `create()` (checks `partner_ids`) | `mention` |
| Discuss DM | `discuss.channel` | `message_post()` | `discuss` |
| Channel message | `discuss.channel` | `message_post()` | `discuss` |
| Activity assigned | `mail.activity` | `create()` | `activity` |

**Logic for each hook:**
1. Find target users (followers + mentioned, minus sender)
2. Look up FCM tokens from `woow.fcm.device`
3. Build rich payload: title (sender name), body (message preview), deep link URL
4. Send via FCM HTTP v1 API with OAuth2

---

## Security

| Feature | Implementation |
|---------|---------------|
| Auth on endpoints | All controllers use `auth='user'` |
| CSRF protection | All controllers use `csrf=True` |
| IDOR on mark_read | Verifies user is message recipient |
| Token validation | Length check 100-300 chars |
| Model access | System-admin only (no user read) |
| Record rules | Users see own devices only |
| Thread-safe cache | `threading.Lock` on OAuth2 token |
| Credential storage | Service account in `ir.config_parameter` (admin-only) |

---

## API Endpoints

### POST /woow_fcm_push/register
Register a device token for the current user.

**Auth:** `user` (must be logged in)

**Params:**
- `fcm_token` (string, required, 100-300 chars)
- `device_name` (string, optional)
- `platform` (string, "android" or "ios")

**Response:** `{"device_id": 1}`

### POST /woow_fcm_push/unregister
Deactivate a device token.

**Auth:** `user`

**Params:**
- `fcm_token` (string, required)

**Response:** `{"success": true}`

### POST /woow_fcm_push/mark_read
Mark a message as read (with IDOR protection).

**Auth:** `user`

**Params:**
- `message_id` (integer, required)

**Response:** `{"success": true}` or `{"error": "Not authorized"}`

---

## Admin View

Registered devices can be viewed at:
**Settings → Technical → FCM Devices**

Fields: User, Device Name, Platform (android/ios), Active, Last Seen

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| No push received | Check `woow_fcm_push.firebase_project_id` and `woow_fcm_push.firebase_service_account` in System Parameters |
| "PyJWT not installed" in log | Run `pip3 install --break-system-packages PyJWT` in Odoo container |
| "FCM sent to 0/0 devices" | No FCM token registered for target user — check FCM Devices admin view |
| Push sent but not received | Check POST_NOTIFICATIONS permission on device (Android 13+) |
| "Firebase not configured" | Both system parameters must be set |
| Self-notification | By design, sender is excluded from targets — use a different user to test |

---

## Running Tests

```bash
cd /path/to/deployment
docker compose run --rm -T odoo python3 -m odoo \
  --config /etc/odoo/odoo.conf \
  -d your_db \
  --test-enable \
  -u woow_fcm_push \
  --stop-after-init \
  --no-http

# Expected: 0 failed, 0 error(s) of 7 tests
```
