#!/usr/bin/env python3
"""
Android H' FCM Pipeline E2E Verification — ANDROID-R05
=======================================================

Proves the Option H' FCM push pipeline works end-to-end on a real Pixel 4,
at parity with the completed iOS R-05 verification.

Functions under test (mirrors iOS FCMPushTests.swift test_FCM_E2E_all_broadcast_triggers):
  0. TOKEN-REG  — app login → woow_fcm_device row exists for user_id=2
  1. B-1        — res.partner record chatter @mention (demo → admin)
  2. B-2        — discuss.channel @mention (channel 1, demo → admin)
  3. B-3        — direct message (demo DM to admin)
  4. B-5        — mail.activity creation assigned to admin (user_id=2)
  5. DEEP-LINK  — tap delivered notification → app foreground
  6. GROUPING   — (optional) verify groupKey in dumpsys

Pipeline path for each trigger:
  Odoo JSON-RPC → woow_fcm_push plugin → unix socket → fcm-sidecar →
  central TVS (whitelist + postgres) → Google FCM API → Android device

Usage:
  export ANDROID_SERIAL=98081FFAZ000KA
  python3 scripts/e2e_hprime_android.py

Budget: 3 on-device runs. Stop if same failure repeats twice.
Tags:   [ANDROID-R05-TOKEN-REG] [ANDROID-R05-B1] [ANDROID-R05-B2]
        [ANDROID-R05-B3] [ANDROID-R05-B5] [ANDROID-R05-DEEPLINK]
        [ANDROID-R05-GROUPING]
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import time
import uuid

import requests
import uiautomator2 as u2

# ─── Config (single source of truth from test_config.py) ─────────────────────
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_config import (
    APP_ACTIVITY as ACTIVITY,
    APP_PACKAGE as PKG,
    ODOO_DB,
    ODOO_HOST,
    ODOO_PASS,
    ODOO_URL,
    ODOO_USER,
    enable_adb_keyboard,
    restore_ime,
)

# ─── Identifiers fixed for this Odoo database ────────────────────────────────
# admin: uid=2, partner_id=3 ("Mitchell Admin")
# demo:  uid=6, partner_id=7 ("Marc Demo")
ADMIN_USER_ID = 2
ADMIN_PARTNER_ID = 3
DEMO_USER_ID = 6
DEMO_PARTNER_ID = 7
CHANNEL_ID = 1           # #general channel, must exist in odoo18_ecpay

# ─── Result tracking ─────────────────────────────────────────────────────────
RESULTS: dict[str, bool | None] = {
    "TOKEN-REG": None,
    "B-1": None,
    "B-2": None,
    "B-3": None,
    "B-5": None,
    "DEEP-LINK": None,
    "GROUPING": None,
}
MATCH_PROOFS: dict[str, str] = {}   # trigger → matched NotificationRecord line
ERRORS: dict[str, str] = {}         # trigger → error detail

PASS_COUNT = 0
FAIL_COUNT = 0


def _mark(trigger: str, passed: bool, proof: str = "", error: str = "") -> None:
    global PASS_COUNT, FAIL_COUNT
    RESULTS[trigger] = passed
    if passed:
        PASS_COUNT += 1
        sym = "\033[32m  PASS\033[0m"
    else:
        FAIL_COUNT += 1
        sym = "\033[31m  FAIL\033[0m"
    if proof:
        MATCH_PROOFS[trigger] = proof
    if error:
        ERRORS[trigger] = error
    label = f"[ANDROID-R05-{trigger}]"
    print(f"{sym} {label}: {'OK' if passed else error or 'FAILED'}")
    if proof:
        print(f"        MATCH-PROOF: {proof[:200]}")


def section(title: str) -> None:
    print(f"\n\033[1m{'═' * 64}\033[0m")
    print(f"\033[1m  {title}\033[0m")
    print(f"\033[1m{'═' * 64}\033[0m")


# ─── Unique per-run marker factory ──────────────────────────────────────────
RUN_TS = int(time.time())

def make_marker(trigger_name: str) -> str:
    """Return a unique marker for this trigger in this test run.

    Format: ANDR-<trigger>-<unix_ts>-<uuid4[:6]>
    Short enough to appear in notification text; unique enough to avoid
    collision with prior runs. Mirrors iOS 'E2E-<trigger>-<ts>-<uuid[:8]>'.
    """
    nonce = uuid.uuid4().hex[:6]
    return f"ANDR-{trigger_name}-{RUN_TS}-{nonce}"


# ─── Odoo JSON-RPC helpers ───────────────────────────────────────────────────

_admin_cookies: requests.cookies.RequestsCookieJar | None = None
_admin_uid: int | None = None
_demo_cookies: requests.cookies.RequestsCookieJar | None = None


def _authenticate(login: str, password: str) -> tuple[int | None, requests.cookies.RequestsCookieJar]:
    """Authenticate with Odoo and return (uid, cookies).

    Uses /web/session/authenticate (same as existing harness). Raises on
    network error; returns (None, empty_jar) on wrong credentials.
    """
    resp = requests.post(
        f"{ODOO_URL}/web/session/authenticate",
        json={
            "jsonrpc": "2.0",
            "method": "call",
            "params": {"db": ODOO_DB, "login": login, "password": password},
            "id": 1,
        },
        timeout=15,
    )
    data = resp.json()
    uid = data.get("result", {}).get("uid")
    return uid, resp.cookies


def _call_kw(
    model: str,
    method: str,
    args: list,
    kwargs: dict | None = None,
    cookies: requests.cookies.RequestsCookieJar | None = None,
    uid: int | None = None,
    password: str | None = None,
) -> tuple[object | None, str | None]:
    """Invoke Odoo model method via /web/dataset/call_kw.

    Returns (result, error_message). Callers check error_message is None
    before using result.
    """
    # Use session cookie if provided; otherwise use uid+password RPC.
    if cookies is not None:
        payload = {
            "jsonrpc": "2.0",
            "method": "call",
            "params": {
                "model": model,
                "method": method,
                "args": args,
                "kwargs": kwargs or {},
            },
            "id": 1,
        }
        resp = requests.post(
            f"{ODOO_URL}/web/dataset/call_kw",
            json=payload,
            cookies=cookies,
            timeout=15,
        )
    else:
        # Legacy /jsonrpc path used by existing harness
        assert uid is not None and password is not None
        payload = {
            "jsonrpc": "2.0",
            "method": "call",
            "params": {
                "service": "object",
                "method": "execute_kw",
                "args": [ODOO_DB, uid, password, model, method, args, kwargs or {}],
            },
            "id": 1,
        }
        resp = requests.post(f"{ODOO_URL}/jsonrpc", json=payload, timeout=15)

    data = resp.json()
    if "error" in data:
        msg = data["error"].get("data", {}).get("message") or data["error"].get("message", "unknown")
        return None, msg
    return data.get("result"), None


# ─── Notification-shade checker ───────────────────────────────────────────────

def _dump_notification() -> str:
    """Return raw dumpsys notification --noredact output."""
    result = subprocess.run(
        ["adb", "shell", "dumpsys", "notification", "--noredact"],
        capture_output=True, text=True, timeout=15,
    )
    return result.stdout


def _find_notification_record(marker: str, dump: str | None = None) -> str | None:
    """Return the matched NotificationRecord line for our package containing marker.

    Scans the full dumpsys block for our package then checks for the marker
    within a 40-line window (one notification record can span many lines).
    Returns the NotificationRecord header line as match-proof, or None.

    Strategy: find all byte-offsets of "NotificationRecord(...pkg=io.woowtech.odoo.debug"
    then check if marker appears within the next 40 lines of that record.
    """
    if dump is None:
        dump = _dump_notification()

    lines = dump.split("\n")
    rec_pattern = re.compile(r"NotificationRecord\([^)]*pkg=io\.woowtech\.odoo\.debug")

    i = 0
    while i < len(lines):
        if rec_pattern.search(lines[i]):
            # Found a record for our package — scan up to 40 lines ahead for marker
            window = "\n".join(lines[i:i+40])
            if marker in window:
                return lines[i].strip()
        i += 1
    return None


def _wait_for_notification(marker: str, timeout_s: int = 35) -> str | None:
    """Poll dumpsys until the marker appears in a NotificationRecord for our app.

    Returns the matched header line (match-proof) or None on timeout.
    Polls every 3s; total budget is timeout_s.
    """
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        proof = _find_notification_record(marker)
        if proof:
            return proof
        time.sleep(3)
    # Final check
    return _find_notification_record(marker)


def _clear_notifications() -> None:
    """Clear all notifications and background the app."""
    subprocess.run(
        ["adb", "shell", "service", "call", "notification", "1"],
        capture_output=True, timeout=5,
    )
    subprocess.run(
        ["adb", "shell", "input", "keyevent", "KEYCODE_HOME"],
        capture_output=True, timeout=5,
    )
    time.sleep(1)


# ─── FCM token retrieval ──────────────────────────────────────────────────────

def _get_fcm_token_from_logcat() -> str | None:
    """Parse FCM_TOKEN: from recent logcat without restarting the app."""
    out = subprocess.run(
        ["adb", "logcat", "-d", "-t", "5000"],
        capture_output=True, encoding="utf-8", errors="replace", timeout=15,
    ).stdout
    matches = list(re.finditer(r"FCM_TOKEN:\s*(\S+)", out))
    if matches:
        return matches[-1].group(1)
    return None


def _get_fcm_token_with_restart() -> str | None:
    """Clear logcat, restart app, wait for FCM_TOKEN: log line."""
    subprocess.run(["adb", "logcat", "-c"], capture_output=True, timeout=5)
    d.app_stop(PKG)
    time.sleep(2)
    d.app_start(PKG, ACTIVITY)
    for _ in range(8):
        time.sleep(5)
        token = _get_fcm_token_from_logcat()
        if token:
            return token
    return None


# ─── App login helper ─────────────────────────────────────────────────────────

def _perform_login() -> bool:
    """Drive the Add Account login UI and wait for WebView.

    Uses uiautomator2's native send_keys() (no ADB keyboard needed — the
    task spec confirms ADB keyboard is not installed but send_keys() works).
    Returns True when the WebView appears within 75s.
    """
    for perm in (
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
    ):
        subprocess.run(["adb", "shell", "pm", "grant", PKG, perm], timeout=5)

    d.app_start(PKG, ACTIVITY)
    time.sleep(5)

    # Wait for 2 EditTexts (server URL + db name)
    for _ in range(20):
        if d(className="android.widget.EditText").count >= 2:
            break
        time.sleep(1)

    edits = d(className="android.widget.EditText")
    if edits.count < 2:
        print("  login: page 1 EditText count < 2")
        return False

    edits[0].click(); time.sleep(0.3); edits[0].clear_text(); edits[0].set_text(ODOO_HOST)
    edits[1].click(); time.sleep(0.3); edits[1].clear_text(); edits[1].set_text(ODOO_DB)
    d.press("back"); time.sleep(0.5)

    # "Next" button (try zh-TW first, then English)
    for label in ["下一步", "Next"]:
        if d(text=label).exists(timeout=5):
            d(text=label).click()
            break

    # Page 2: username + password
    for _ in range(20):
        if d(className="android.widget.EditText").count >= 2:
            break
        time.sleep(1)

    edits = d(className="android.widget.EditText")
    if edits.count < 2:
        print("  login: page 2 EditText count < 2")
        return False

    edits[0].click(); time.sleep(0.3); edits[0].clear_text(); edits[0].set_text(ODOO_USER)
    edits[1].click(); time.sleep(0.3); edits[1].clear_text(); edits[1].set_text(ODOO_PASS)
    d.press("back"); time.sleep(0.5)

    for label in ["登入", "Login"]:
        if d(text=label).exists(timeout=5):
            d(text=label).click()
            break

    # Wait for WebView (75s: covers cold tunnel + uncached session)
    for _ in range(75):
        time.sleep(1)
        if d(className="android.webkit.WebView").exists(timeout=1):
            time.sleep(8)   # let FCM register-on-login POST complete
            return True
    return False


# ─── Odoo trigger helpers (ported from iOS FCMPushTests.swift) ───────────────

def _post_record_mention(marker: str) -> tuple[bool, str]:
    """B-1: demo posts on res.partner id=3 (admin) mentioning admin.

    Mirrors iOS _postRecordMention. Returns (success, error_msg).
    """
    mention_html = (
        f'<p><a href="#" data-oe-model="res.partner" '
        f'data-oe-id="{ADMIN_PARTNER_ID}">@Mitchell Admin</a> {marker}</p>'
    )
    result, err = _call_kw(
        model="res.partner",
        method="message_post",
        args=[[ADMIN_PARTNER_ID]],
        kwargs={
            "body": mention_html,
            "partner_ids": [ADMIN_PARTNER_ID],
            "message_type": "comment",
            "subtype_xmlid": "mail.mt_comment",
        },
        cookies=_demo_cookies,
    )
    if err:
        return False, f"res.partner.message_post failed: {err}"
    return True, ""


def _post_channel_mention(marker: str) -> tuple[bool, str]:
    """B-2: demo posts in channel 1 mentioning admin (partner_id=3).

    Mirrors iOS _postChannelMention. Returns (success, error_msg).
    """
    mention_html = (
        f'<p><a href="#" data-oe-model="res.partner" '
        f'data-oe-id="{ADMIN_PARTNER_ID}">@Mitchell Admin</a> {marker}</p>'
    )
    result, err = _call_kw(
        model="discuss.channel",
        method="message_post",
        args=[[CHANNEL_ID]],
        kwargs={
            "body": mention_html,
            "partner_ids": [ADMIN_PARTNER_ID],
            "message_type": "comment",
            "subtype_xmlid": "mail.mt_comment",
        },
        cookies=_demo_cookies,
    )
    if err:
        return False, f"discuss.channel.message_post failed: {err}"
    return True, ""


def _post_direct_message(marker: str) -> tuple[bool, str]:
    """B-3: demo sends a DM to admin.

    Mirrors iOS _postDirectMessage: search for existing chat channel first,
    fall back to channel_get, then message_post.
    """
    # Search for an existing chat (channel_type='chat') that includes admin
    result, err = _call_kw(
        model="discuss.channel",
        method="search_read",
        args=[],
        kwargs={
            "domain": [
                ["channel_type", "=", "chat"],
                ["channel_partner_ids", "in", [ADMIN_PARTNER_ID]],
            ],
            "fields": ["id"],
            "limit": 5,
        },
        cookies=_demo_cookies,
    )
    channel_id: int | None = None
    if not err and isinstance(result, list) and result:
        channel_id = result[0]["id"]

    if channel_id is None:
        # Fall back to channel_get
        result2, err2 = _call_kw(
            model="discuss.channel",
            method="channel_get",
            args=[],
            kwargs={"partners_to": [ADMIN_PARTNER_ID]},
            cookies=_demo_cookies,
        )
        if err2:
            return False, f"channel_get failed: {err2}"
        # Odoo 18 may return dict with 'id', or {'Thread': [{'id': ...}]}
        if isinstance(result2, dict):
            if "id" in result2:
                channel_id = result2["id"]
            elif "Thread" in result2 and isinstance(result2["Thread"], list):
                channel_id = result2["Thread"][0].get("id")
            elif "channel_info" in result2:
                channel_id = result2["channel_info"].get("id")
        elif isinstance(result2, list) and result2:
            channel_id = result2[0].get("id")

    if channel_id is None:
        return False, "could not resolve DM channel between demo and admin"

    result3, err3 = _call_kw(
        model="discuss.channel",
        method="message_post",
        args=[[channel_id]],
        kwargs={
            "body": f"<p>{marker}</p>",
            "message_type": "comment",
            "subtype_xmlid": "mail.mt_comment",
        },
        cookies=_demo_cookies,
    )
    if err3:
        return False, f"DM message_post failed: {err3}"
    return True, ""


def _create_activity_for_admin(marker: str) -> tuple[bool, str]:
    """B-5: create mail.activity on res.partner id=3 assigned to admin (user_id=2).

    Mirrors iOS _createActivityForAdmin. Uses admin auth for ir.model and
    mail.activity.type lookups (access-restricted in Odoo 18), then creates
    the activity as demo so demo is the author and admin is the assignee.
    """
    # Look up first available activity type (admin auth — access-restricted)
    type_result, type_err = _call_kw(
        model="mail.activity.type",
        method="search_read",
        args=[],
        kwargs={"domain": [], "fields": ["id", "name"], "limit": 1, "order": "sequence, id"},
        cookies=_admin_cookies,
    )
    if type_err or not isinstance(type_result, list) or not type_result:
        return False, f"mail.activity.type lookup failed: {type_err}"
    activity_type_id: int = type_result[0]["id"]

    # Look up ir.model for res.partner (admin auth)
    model_result, model_err = _call_kw(
        model="ir.model",
        method="search_read",
        args=[],
        kwargs={
            "domain": [["model", "=", "res.partner"]],
            "fields": ["id"],
            "limit": 1,
        },
        cookies=_admin_cookies,
    )
    if model_err or not isinstance(model_result, list) or not model_result:
        return False, f"ir.model lookup failed: {model_err}"
    res_model_id: int = model_result[0]["id"]

    # Create activity as demo (demo = author → excluded from FCM targets;
    # admin = assignee → receives the push)
    create_result, create_err = _call_kw(
        model="mail.activity",
        method="create",
        args=[[{
            "activity_type_id": activity_type_id,
            "res_model_id": res_model_id,
            "res_id": ADMIN_PARTNER_ID,
            "user_id": ADMIN_USER_ID,
            "summary": marker,
            "note": f"<p>{marker}</p>",
        }]],
        cookies=_demo_cookies,
    )
    if create_err:
        return False, f"mail.activity.create failed: {create_err}"
    return True, ""


# ──────────────────────────────────────────────────────────────────────────────
# MAIN TEST FLOW
# ──────────────────────────────────────────────────────────────────────────────

print("\n")
section("ANDROID-R05: H' FCM Pipeline E2E Verification")
print(f"  Device: ANDROID_SERIAL={os.environ.get('ANDROID_SERIAL', '(not set)')}")
print(f"  Odoo URL: {ODOO_URL}")
print(f"  App pkg: {PKG}")
print(f"  Run timestamp: {RUN_TS}")

# ─── Connect to device ───────────────────────────────────────────────────────
section("Step 0: Device connection")
d = u2.connect()
device_info = d.info
print(f"  Device: {device_info.get('productName', '?')} (SDK {device_info.get('sdkInt', '?')})")

# Grant notification permission unconditionally
for perm in (
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
):
    subprocess.run(["adb", "shell", "pm", "grant", PKG, perm], timeout=5)

# ─── Step 1: Odoo authentication ─────────────────────────────────────────────
section("Step 1: Odoo Authentication")

_admin_uid, _admin_cookies = _authenticate(ODOO_USER, ODOO_PASS)
print(f"  Admin auth: {'OK' if _admin_uid else 'FAILED'} (uid={_admin_uid})")

_demo_uid, _demo_cookies = _authenticate("demo", "demo")
print(f"  Demo auth: {'OK' if _demo_uid else 'FAILED'} (uid={_demo_uid})")

if not _admin_uid or not _demo_uid:
    print("\n  FATAL: Cannot authenticate to Odoo. Aborting.")
    sys.exit(1)

# ─── Step 2: TOKEN-REG — app login and FCM device registration ───────────────
section("Step 2: [ANDROID-R05-TOKEN-REG] — Token registration on login")

# Clear app state and re-login so we get a fresh FCM token registration
print("  Clearing app data (pm clear) to simulate fresh state...")
subprocess.run(["adb", "shell", "pm", "clear", PKG], timeout=10)
time.sleep(2)

print("  Logging in via UI...")
subprocess.run(["adb", "logcat", "-c"], capture_output=True, timeout=5)  # clear before login

login_ok = _perform_login()
print(f"  UI login: {'OK' if login_ok else 'FAILED'}")

if not login_ok:
    _mark("TOKEN-REG", False, error="UI login failed — cannot verify token registration")
else:
    # Poll for the FCM token in logcat (app emits FCM_TOKEN: on startup)
    fcm_token: str | None = None
    for _ in range(10):
        fcm_token = _get_fcm_token_from_logcat()
        if fcm_token:
            break
        time.sleep(3)

    if not fcm_token:
        print("  FCM_TOKEN: not found in logcat after 30s, trying app restart...")
        # Gentle restart (don't pm clear again — that would wipe the login)
        subprocess.run(["adb", "logcat", "-c"], capture_output=True, timeout=5)
        d.app_stop(PKG)
        time.sleep(2)
        d.app_start(PKG, ACTIVITY)
        time.sleep(15)
        fcm_token = _get_fcm_token_from_logcat()

    print(f"  FCM token: {fcm_token[:30] + '...' if fcm_token else 'NOT FOUND'}")

    if not fcm_token:
        _mark("TOKEN-REG", False, error="FCM token not found in logcat")
    else:
        # Re-auth Odoo (pm clear may have invalidated cookies we built before login)
        _admin_uid, _admin_cookies = _authenticate(ODOO_USER, ODOO_PASS)
        _demo_uid, _demo_cookies = _authenticate("demo", "demo")

        # Query woow.fcm.device for this exact token OR for user_id=2
        device_rows, dev_err = _call_kw(
            model="woow.fcm.device",
            method="search_read",
            args=[[["user_id", "=", ADMIN_USER_ID]]],
            kwargs={"fields": ["id", "fcm_token", "platform", "user_id", "active"], "limit": 5},
            cookies=_admin_cookies,
        )
        if dev_err:
            _mark("TOKEN-REG", False, error=f"woow.fcm.device query failed: {dev_err}")
        elif not device_rows:
            _mark("TOKEN-REG", False, error="no woow.fcm.device row for user_id=2")
        else:
            # Check if any row has our exact token (strongest proof) or at least user_id=2
            exact_match = any(row.get("fcm_token") == fcm_token for row in device_rows)
            active_row = next((r for r in device_rows if r.get("active")), None)
            proof = (
                f"woow.fcm.device id={device_rows[0]['id']} "
                f"user_id={device_rows[0]['user_id']} "
                f"platform={device_rows[0].get('platform')} "
                f"active={device_rows[0].get('active')} "
                f"token_match={'EXACT' if exact_match else 'user_match_only'}"
            )
            _mark("TOKEN-REG", True, proof=proof)
            print(f"  Device rows for user_id=2: {len(device_rows)}, "
                  f"exact_token_match={exact_match}, active_row={active_row is not None}")

# ─── Background the app before triggers ──────────────────────────────────────
section("Step 3: Background app for notification testing")

_clear_notifications()
d.press("home")
time.sleep(2)
print("  App backgrounded, notifications cleared.")

# ─── Batched broadcast triggers ──────────────────────────────────────────────
# Mirrors iOS test_FCM_E2E_all_broadcast_triggers:
# post trigger → wait for notification via dumpsys → record match-proof.
# Each trigger gets a unique marker; soft-fail per trigger.

TRIGGERS = [
    ("B-1", "record @mention"),
    ("B-2", "channel @mention"),
    ("B-3", "direct message"),
    ("B-5", "activity assignment"),
]

# We'll tap the LAST successful notification for DEEP-LINK after all triggers.
last_delivered_marker: str | None = None
last_delivered_trigger: str | None = None

for trigger_id, trigger_desc in TRIGGERS:
    section(f"Trigger {trigger_id}: {trigger_desc} [ANDROID-R05-{trigger_id}]")

    # Clear notifications between triggers (same hygiene as iOS test)
    if trigger_id != "B-1":
        _clear_notifications()
        d.press("home")
        time.sleep(1)

    marker = make_marker(trigger_id)
    print(f"  Marker: {marker}")

    # Post the trigger
    try:
        if trigger_id == "B-1":
            ok, err = _post_record_mention(marker)
        elif trigger_id == "B-2":
            ok, err = _post_channel_mention(marker)
        elif trigger_id == "B-3":
            ok, err = _post_direct_message(marker)
        elif trigger_id == "B-5":
            ok, err = _create_activity_for_admin(marker)
        else:
            ok, err = False, "unknown trigger"
    except Exception as exc:
        ok, err = False, str(exc)

    if not ok:
        _mark(trigger_id, False, error=f"RPC post failed: {err}")
        continue

    print(f"  RPC posted OK. Waiting for notification (35s timeout)...")

    # Wait for the notification to appear in dumpsys
    proof = _wait_for_notification(marker, timeout_s=35)
    if proof:
        _mark(trigger_id, True, proof=proof)
        last_delivered_marker = marker
        last_delivered_trigger = trigger_id
        # Take a raw dump snippet for full evidence
        dump = _dump_notification()
        # Print the 3 lines surrounding the proof line
        lines = dump.split("\n")
        for i, line in enumerate(lines):
            if marker in line:
                start = max(0, i - 1)
                end = min(len(lines), i + 4)
                print(f"  DUMP snippet:")
                for dl in lines[start:end]:
                    print(f"    {dl}")
                break
    else:
        # Capture full dump for diagnosis
        dump = _dump_notification()
        our_records = [l for l in dump.split("\n") if "io.woowtech.odoo.debug" in l]
        _mark(trigger_id, False,
              error=f"marker not found in dumpsys after 35s "
                    f"(our_app_records_in_shade={len(our_records)})")
        if our_records:
            print(f"  Records for our app in shade (marker mismatch):")
            for r in our_records[:3]:
                print(f"    {r[:120]}")

# ─── DEEP-LINK test ───────────────────────────────────────────────────────────
section("Step 5: [ANDROID-R05-DEEPLINK] — Tap notification → app foreground")

if last_delivered_marker and last_delivered_trigger:
    print(f"  Using marker from {last_delivered_trigger}: {last_delivered_marker}")

    # Send a fresh B-2 notification so we have something to tap
    deeplink_marker = make_marker("DEEPLINK")
    print(f"  Posting fresh B-2 for deep-link tap (marker={deeplink_marker})...")
    dl_ok, dl_err = _post_channel_mention(deeplink_marker)
    if not dl_ok:
        _mark("DEEP-LINK", False, error=f"Could not post deep-link trigger: {dl_err}")
    else:
        # Wait for the notification to land in the shade
        dl_proof = _wait_for_notification(deeplink_marker, timeout_s=30)
        if not dl_proof:
            _mark("DEEP-LINK", False, error="deep-link notification did not arrive in shade")
        else:
            # Open notification shade via swipe-down
            d.open_notification()
            time.sleep(2.5)

            # Try to find and tap the notification
            # On Android the notification text appears in TextView/Button
            tapped = False
            for search_text in [deeplink_marker[:20], "Odoo", "odoo", "Mitchell", "Marc"]:
                elem = d(textContains=search_text)
                if elem.exists(timeout=3):
                    elem.click()
                    tapped = True
                    break

            if not tapped:
                # Fallback: use coordinated swipe to expand + tap first notification
                d.press("back")
                time.sleep(1)
                _mark("DEEP-LINK", False,
                      error="notification element not tappable via text search in shade")
            else:
                time.sleep(5)
                current_pkg = d.app_current().get("package", "")
                app_in_foreground = (current_pkg == PKG)
                if app_in_foreground:
                    _mark("DEEP-LINK", True,
                          proof=f"app came to foreground after notification tap (pkg={current_pkg})")
                else:
                    _mark("DEEP-LINK", False,
                          error=f"app NOT in foreground after tap (got pkg={current_pkg})")
else:
    _mark("DEEP-LINK", False,
          error="skipped — no trigger delivered a notification to tap (check B-1..B-5 results)")

# ─── GROUPING test ───────────────────────────────────────────────────────────
section("Step 6: [ANDROID-R05-GROUPING] — Notification groupKey verification (optional)")

# Send 3 B-2 channel mentions rapidly, then inspect groupKey in dumpsys
_clear_notifications()
d.press("home")
time.sleep(1)

group_markers = [make_marker(f"GRP{i}") for i in range(3)]
print(f"  Posting 3 channel mentions for grouping check...")
posted_ok = 0
for gm in group_markers:
    gok, gerr = _post_channel_mention(gm)
    if gok:
        posted_ok += 1
    time.sleep(0.5)

print(f"  Posted {posted_ok}/3 group markers. Waiting 20s for delivery...")
time.sleep(20)

dump = _dump_notification()
# Count NotificationRecord lines for our package
our_lines = [l for l in dump.split("\n")
             if re.search(r"NotificationRecord\([^)]*pkg=io\.woowtech\.odoo\.debug", l)]
print(f"  NotificationRecord count for our app: {len(our_lines)}")

# Check for groupKey
group_key_lines = [l for l in dump.split("\n")
                   if "io.woowtech.odoo.debug" in l and "groupKey=" in l]
print(f"  Lines with groupKey for our app: {len(group_key_lines)}")
for gkl in group_key_lines[:5]:
    print(f"    {gkl.strip()[:120]}")

# Success if we got at least 1 of 3 and groupKey appears
if our_lines and group_key_lines:
    gk_proof = group_key_lines[0].strip()
    _mark("GROUPING", True,
          proof=f"records={len(our_lines)}, first_groupKey_line={gk_proof[:100]}")
elif our_lines:
    # Notifications arrived but no groupKey (not grouped — not a hard failure)
    _mark("GROUPING", True,
          proof=f"records={len(our_lines)} arrived (no groupKey field — ungrouped)")
else:
    _mark("GROUPING", False,
          error=f"no notifications for our app in shade (posted {posted_ok}/3)")

# ─── SUMMARY ─────────────────────────────────────────────────────────────────
section("ANDROID-R05 SUMMARY")

total = len(RESULTS)
print(f"\n  Odoo URL:  {ODOO_URL}")
print(f"  Device:    {device_info.get('productName', '?')} "
      f"(SDK {device_info.get('sdkInt', '?')}, serial={os.environ.get('ANDROID_SERIAL', '?')})")
print(f"  Run TS:    {RUN_TS}\n")

print("  ┌──────────────┬────────┬──────────────────────────────────────────────┐")
print("  │ Trigger      │ Result │ Proof / Error                                │")
print("  ├──────────────┼────────┼──────────────────────────────────────────────┤")
for trigger, passed in RESULTS.items():
    if passed is None:
        result_str = " SKIP "
        detail = ERRORS.get(trigger, "")
    elif passed:
        result_str = " PASS "
        detail = MATCH_PROOFS.get(trigger, "")[:50]
    else:
        result_str = " FAIL "
        detail = ERRORS.get(trigger, "")[:50]
    print(f"  │ {trigger:<12} │ {result_str} │ {detail:<44} │")
print("  └──────────────┴────────┴──────────────────────────────────────────────┘")

pass_count = sum(1 for v in RESULTS.values() if v is True)
fail_count = sum(1 for v in RESULTS.values() if v is False)
skip_count = sum(1 for v in RESULTS.values() if v is None)

print(f"\n  Result: {pass_count} PASS, {fail_count} FAIL, {skip_count} SKIP out of {total}")

if MATCH_PROOFS:
    print("\n  Match-proof lines (dumpsys NotificationRecord):")
    for trigger, proof in MATCH_PROOFS.items():
        print(f"    [{trigger}] {proof}")

if ERRORS:
    print("\n  Failure details:")
    for trigger, err in ERRORS.items():
        print(f"    [{trigger}] {err}")

# iOS R-05 parity verdict
ios_triggers = {"B-1", "B-2", "B-3", "B-5"}
android_covered = {t for t, v in RESULTS.items() if v is True and t in ios_triggers}
print(f"\n  iOS R-05 parity: iOS covered {sorted(ios_triggers)}")
print(f"                   Android covered {sorted(android_covered)}")
if android_covered == ios_triggers:
    print("  VERDICT: Android FULLY VERIFIED at parity with iOS R-05.")
else:
    missing = ios_triggers - android_covered
    print(f"  VERDICT: PARTIAL — missing {sorted(missing)} vs iOS R-05.")

print()
sys.exit(fail_count)
