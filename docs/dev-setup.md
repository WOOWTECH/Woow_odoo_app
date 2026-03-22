# Development Environment Setup

## Local Odoo 18 Server

### Docker Containers

Located at: `/Users/alanlin/Documents/odoo_migration_ecpay/deployment/`

```bash
# Start
cd /Users/alanlin/Documents/odoo_migration_ecpay/deployment && docker compose up -d

# Stop
cd /Users/alanlin/Documents/odoo_migration_ecpay/deployment && docker compose down

# Check status
docker ps --filter "name=ecpay"
```

| Container | Port | Status Check |
|-----------|------|-------------|
| `ecpay_odoo18` (Odoo 18) | `localhost:8069` | `curl -s http://localhost:8069/web/health` → `{"status": "pass"}` |
| `ecpay_odoo18_db` (PostgreSQL 15) | `localhost:5433` | `docker exec ecpay_odoo18_db pg_isready` |

### HTTPS Tunnel (Cloudflare)

The app enforces HTTPS at 3 levels, so a tunnel is required for local testing.

```bash
# Start tunnel (generates a random HTTPS URL each time)
cloudflared tunnel --url http://localhost:8069

# Example output URL:
# https://directions-joe-itunes-feel.trycloudflare.com
```

> **Note:** The URL changes every restart. Update the app's server URL accordingly.

---

## Test Accounts

### Database: `odoo18_ecpay`

| Role | Username | Password |
|------|----------|----------|
| Admin | `admin` | `admin` |
| Test User | `test@woowtech.com` | `test1234` |

### Creating Additional Test Users

```bash
curl -s https://<TUNNEL_URL>/jsonrpc \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "method": "call",
    "params": {
      "service": "object",
      "method": "execute_kw",
      "args": ["odoo18_ecpay", 2, "admin", "res.users", "create", [{
        "name": "New User",
        "login": "newuser@woowtech.com",
        "password": "password123",
        "email": "newuser@woowtech.com"
      }]]
    },
    "id": 1
  }'
```

---

## App Login Steps

1. **Server URL:** Enter the Cloudflare tunnel URL (without `https://`)
2. **Database:** `odoo18_ecpay`
3. **Username:** `test@woowtech.com`
4. **Password:** `test1234`

---

## Quick Start (All-in-One)

```bash
# 1. Start Docker containers
cd /Users/alanlin/Documents/odoo_migration_ecpay/deployment && docker compose up -d

# 2. Wait for healthy
until curl -sf http://localhost:8069/web/health > /dev/null 2>&1; do sleep 2; done && echo "Odoo ready"

# 3. Start HTTPS tunnel
cloudflared tunnel --url http://localhost:8069
# Copy the https://xxx.trycloudflare.com URL and use it in the app
```

---

## ADB Automated Login

```bash
# Fill login fields via uiautomator2 (requires: pip3 install uiautomator2)
python3 << 'EOF'
import uiautomator2 as u2, time

TUNNEL_URL = "YOUR_TUNNEL_URL_HERE"  # e.g. directions-joe-itunes-feel.trycloudflare.com
DB_NAME = "odoo18_ecpay"
USERNAME = "test@woowtech.com"
PASSWORD = "test1234"

d = u2.connect()

# Screen 1: Server URL + Database
edits = d(className="android.widget.EditText")
edits[0].set_text(TUNNEL_URL)
time.sleep(0.3)
edits[1].set_text(DB_NAME)
d(text="下一步").click()
time.sleep(3)

# Screen 2: Username + Password
edits = d(className="android.widget.EditText")
edits[0].set_text(USERNAME)
time.sleep(0.3)
edits[1].set_text(PASSWORD)
d(text="登入").click()
EOF
```

---

## Odoo Server Config

Config file: `/Users/alanlin/Documents/odoo_migration_ecpay/deployment/config/odoo.conf`

Key settings:
- `proxy_mode = True` (required for Cloudflare tunnel)
- `dbfilter = ^odoo18_ecpay$`
- `db_password = odoo_ecpay_dev_2025`
- `admin_passwd = admin_ecpay_dev` (database manager password)
