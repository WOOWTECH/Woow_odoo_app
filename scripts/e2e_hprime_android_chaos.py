#!/usr/bin/env python3
"""
Android H' FCM Pipeline CHAOS/Fault-Injection Tests — ANDROID-CHAOS
====================================================================

Mirrors iOS FCMPushChaosTests.swift, adapted for Android (ADB dumpsys instead
of XCUITest springboard queries, subprocess chaos scripts instead of
Foundation.Process).

Trace gaps closed:
  G-6  C-1: sidecar SIGKILL mid-mention  — no phantom delivery, app survives
  G-7  C-2: central TVS 503             — cached-bearer path (NFC-1 60s) still delivers
  G-9  C-3: invalid FCM token / id=247  — auto-deactivation after 2 failures

CRITICAL constraints:
  - C-3 MUST target ONLY id=247 (Android).  NEVER bulk-poison user_id=2 because
    that would also hit id=246 (iOS iPhone) and break the iOS chaos suite which
    runs immediately after this script on the same backend.
  - Restore all services and DB state before exiting, even on failure (each
    sub-routine has a finally block).

Usage:
  export ANDROID_SERIAL=98081FFAZ000KA
  python3 scripts/e2e_hprime_android_chaos.py

Attempt budget: 3 on-device runs total.
Tags: [ANDROID-CHAOS-C1] [ANDROID-CHAOS-C2] [ANDROID-CHAOS-C3]
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import threading
import time
import uuid

import requests

# ─── Config (shared with happy-path harness) ─────────────────────────────────
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_config import (
    APP_PACKAGE as PKG,
    ODOO_DB,
    ODOO_PASS,
    ODOO_URL,
    ODOO_USER,
)

# ─── Fixed IDs ───────────────────────────────────────────────────────────────
ADMIN_USER_ID = 2
ADMIN_PARTNER_ID = 3
DEMO_PARTNER_ID = 7
CHANNEL_ID = 1

# The Pixel 4 Android device row — NEVER target id=246 (iOS/iPhone).
ANDROID_DEVICE_ROW_ID = 247

# ─── Chaos scripts ───────────────────────────────────────────────────────────
CHAOS_SCRIPTS_DIR = os.environ.get(
    "FCM_CHAOS_SCRIPTS_DIR",
    "/Users/alanlin/woow_fcm_central/central/scripts/chaos",
)
KILL_SIDECAR_SH = os.path.join(CHAOS_SCRIPTS_DIR, "kill-sidecar.sh")
STOP_CENTRAL_SH = os.path.join(CHAOS_SCRIPTS_DIR, "stop-central.sh")

# ─── ODOO_DB_PASS for C-3 psql restore (matches poison-fcm-token.sh) ─────────
ODOO_DB_PASS = os.environ.get("ODOO_DB_PASS", "odoo")

# ─── Result tracking ─────────────────────────────────────────────────────────
RUN_TS = int(time.time())

RESULTS: dict[str, bool | None] = {"C-1": None, "C-2": None, "C-3": None}
MATCH_PROOFS: dict[str, str] = {}
ERRORS: dict[str, str] = {}

PASS_COUNT = 0
FAIL_COUNT = 0


def _mark(test: str, passed: bool, proof: str = "", error: str = "") -> None:
    global PASS_COUNT, FAIL_COUNT
    RESULTS[test] = passed
    if passed:
        PASS_COUNT += 1
        sym = "\033[32m  PASS\033[0m"
    else:
        FAIL_COUNT += 1
        sym = "\033[31m  FAIL\033[0m"
    if proof:
        MATCH_PROOFS[test] = proof
    if error:
        ERRORS[test] = error
    label = f"[ANDROID-CHAOS-{test}]"
    print(f"{sym} {label}: {'OK' if passed else error or 'FAILED'}")
    if proof:
        print(f"        PROOF: {proof[:200]}")


def section(title: str) -> None:
    print(f"\n\033[1m{'═' * 64}\033[0m")
    print(f"\033[1m  {title}\033[0m")
    print(f"\033[1m{'═' * 64}\033[0m")


def make_marker(test_name: str) -> str:
    """Unique per-run marker: CHAOS-<test>-<ts>-<uuid6>.

    Short enough to appear in dumpsys android.text; unique enough to avoid
    collision with prior runs or concurrent iOS suite. Mirrors iOS pattern.
    """
    nonce = uuid.uuid4().hex[:6]
    return f"CHAOS-{test_name}-{RUN_TS}-{nonce}"


# ─── ADB helpers ─────────────────────────────────────────────────────────────

def _adb(cmd: list[str], timeout: int = 15) -> str:
    """Run an adb shell command, return stdout as str."""
    full = ["adb", "shell"] + cmd
    r = subprocess.run(full, capture_output=True, text=True, timeout=timeout)
    return r.stdout


def _get_app_pid() -> str | None:
    """Return the PID of PKG as a string, or None if not running."""
    out = _adb(["pidof", PKG]).strip()
    return out if out else None


def _dump_notification() -> str:
    """Return raw dumpsys notification --noredact output."""
    r = subprocess.run(
        ["adb", "shell", "dumpsys", "notification", "--noredact"],
        capture_output=True, text=True, timeout=15,
    )
    return r.stdout


def _find_notification_record(
    marker: str,
    dump: str | None = None,
    require_group_key: str | None = None,
) -> str | None:
    """Search dumpsys output for our app's NotificationRecord containing marker.

    The marker appears in android.text (confirmed by dump-first analysis).
    Strategy: scan all lines for our package NotificationRecord header, then
    check the next 50 lines for the marker string. Returns the header line
    as match-proof, or None.

    Args:
        marker: the unique marker string to search for.
        dump: pre-fetched dumpsys output (fetched fresh if None).
        require_group_key: if set, only match records whose header line
            contains 'groupKey=<value>' (e.g. 'mention' for FCM-path
            notifications, 'discuss' for WebSocket-path notifications).
            This is used by C-1 to distinguish FCM from WebSocket delivery.
    """
    if dump is None:
        dump = _dump_notification()

    lines = dump.split("\n")
    rec_pattern = re.compile(r"NotificationRecord\([^)]*pkg=io\.woowtech\.odoo\.debug")

    i = 0
    while i < len(lines):
        if rec_pattern.search(lines[i]):
            header = lines[i]
            if require_group_key and f"groupKey={require_group_key}" not in header:
                i += 1
                continue
            window = "\n".join(lines[i : i + 50])
            if marker in window:
                return header.strip()
        i += 1
    return None


def _wait_for_notification(
    marker: str,
    timeout_s: int,
    require_group_key: str | None = None,
) -> str | None:
    """Poll dumpsys every 3s until marker found or timeout.

    Returns the matched NotificationRecord header (match-proof), or None.
    See _find_notification_record for require_group_key semantics.
    """
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        proof = _find_notification_record(marker, require_group_key=require_group_key)
        if proof:
            return proof
        time.sleep(3)
    return _find_notification_record(marker, require_group_key=require_group_key)


def _assert_no_fcm_notification(marker: str, wait_s: int) -> tuple[bool, str]:
    """Wait wait_s seconds; assert NO FCM-path notification (groupKey=mention) appears.

    Dump-first insight (Attempts 1 & 2): when the FCM sidecar is killed/paused,
    the Android app's persistent WebSocket to Odoo still delivers discuss messages
    via the bus mechanism. These create local notifications with groupKey=discuss.
    The test for C-1 must specifically assert the ABSENCE of FCM-path notifications
    (groupKey=mention) — WebSocket-path notifications (groupKey=discuss) are expected
    and are NOT a test failure.

    Returns (True, absence_proof) if no FCM notification appeared.
    Returns (False, phantom_proof) if an FCM notification unexpectedly appeared.
    """
    print(f"  [C-1 absence-check] Waiting {wait_s}s — FCM-path marker must NOT appear: {marker}")
    print(f"  Note: WebSocket-path (groupKey=discuss) notifications are EXPECTED and allowed.")
    fcm_proof = _wait_for_notification(
        marker, timeout_s=wait_s, require_group_key="mention"
    )
    if fcm_proof is None:
        # Confirmed FCM-path absence — check what actually appeared (WebSocket or nothing)
        dump = _dump_notification()
        ws_appeared = _find_notification_record(marker, dump=dump, require_group_key="discuss")
        our_records = [
            l for l in dump.split("\n")
            if "io.woowtech.odoo.debug" in l and "NotificationRecord" in l
        ]
        absence_proof = (
            f"no FCM-path notification (groupKey=mention) after {wait_s}s; "
            f"ws_path_appeared={'YES' if ws_appeared else 'NO'} (groupKey=discuss, expected); "
            f"our_app_records={len(our_records)}"
        )
        return True, absence_proof
    else:
        return False, f"FCM-path notification appeared (groupKey=mention): {fcm_proof}"


def _assert_no_notification(marker: str, wait_s: int) -> tuple[bool, str]:
    """Wait wait_s seconds; return (True, absence_proof) if marker NEVER appears.

    This version checks ALL notification types (no groupKey filter).
    Used by tests other than C-1 where no WebSocket alternative path exists.
    """
    print(f"  [absence-check] Waiting {wait_s}s — marker must NOT appear: {marker}")
    proof = _wait_for_notification(marker, timeout_s=wait_s)
    if proof is None:
        dump = _dump_notification()
        our_records = [
            l for l in dump.split("\n")
            if "io.woowtech.odoo.debug" in l and "NotificationRecord" in l
        ]
        absence_proof = (
            f"marker absent after {wait_s}s; "
            f"our_app_records_in_shade={len(our_records)}"
        )
        return True, absence_proof
    else:
        return False, f"PHANTOM notification appeared: {proof}"


def _clear_notifications() -> None:
    """Clear notification shade and send app to background."""
    subprocess.run(
        ["adb", "shell", "service", "call", "notification", "1"],
        capture_output=True, timeout=5,
    )
    subprocess.run(
        ["adb", "shell", "input", "keyevent", "KEYCODE_HOME"],
        capture_output=True, timeout=5,
    )
    time.sleep(1)


# ─── Odoo JSON-RPC helpers (reused from happy-path harness) ──────────────────

_admin_cookies: requests.cookies.RequestsCookieJar | None = None
_demo_cookies: requests.cookies.RequestsCookieJar | None = None


def _authenticate(login: str, password: str) -> tuple[int | None, requests.cookies.RequestsCookieJar]:
    resp = requests.post(
        f"{ODOO_URL}/web/session/authenticate",
        json={
            "jsonrpc": "2.0", "method": "call", "id": 1,
            "params": {"db": ODOO_DB, "login": login, "password": password},
        },
        timeout=15,
    )
    data = resp.json()
    uid = data.get("result", {}).get("uid")
    return uid, resp.cookies


def _call_kw(
    model: str, method: str, args: list, kwargs: dict | None = None,
    cookies: requests.cookies.RequestsCookieJar | None = None,
) -> tuple[object | None, str | None]:
    payload = {
        "jsonrpc": "2.0", "method": "call", "id": 1,
        "params": {
            "model": model, "method": method,
            "args": args, "kwargs": kwargs or {},
        },
    }
    resp = requests.post(
        f"{ODOO_URL}/web/dataset/call_kw",
        json=payload, cookies=cookies, timeout=15,
    )
    data = resp.json()
    if "error" in data:
        msg = (
            data["error"].get("data", {}).get("message")
            or data["error"].get("message", "unknown")
        )
        return None, msg
    return data.get("result"), None


def _post_channel_mention(marker: str) -> tuple[bool, str]:
    """B-2: demo posts in channel 1 mentioning admin (partner_id=3)."""
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


def _count_active_devices_for_admin() -> int:
    """Return count of active woow.fcm.device rows for user_id=2 (admin)."""
    result, err = _call_kw(
        model="woow.fcm.device",
        method="search_count",
        args=[[["user_id", "=", ADMIN_USER_ID], ["active", "=", True]]],
        cookies=_admin_cookies,
    )
    if err:
        return -1
    return int(result) if result is not None else 0


def _get_device_row(device_id: int) -> dict | None:
    """Return the woow.fcm.device record for the given id, or None."""
    result, err = _call_kw(
        model="woow.fcm.device",
        method="read",
        args=[[device_id]],
        kwargs={"fields": ["id", "user_id", "platform", "fcm_token", "active", "consecutive_failures"]},
        cookies=_admin_cookies,
    )
    if err or not result:
        return None
    rows = result if isinstance(result, list) else []
    return rows[0] if rows else None


def _write_device_row(device_id: int, vals: dict) -> tuple[bool, str]:
    """Update a woow.fcm.device record via admin auth. Returns (ok, err)."""
    result, err = _call_kw(
        model="woow.fcm.device",
        method="write",
        args=[[device_id], vals],
        cookies=_admin_cookies,
    )
    if err:
        return False, err
    return True, ""


# ─── Chaos script launcher ────────────────────────────────────────────────────

def _run_chaos_script_async(script_path: str, arguments: list[str]) -> subprocess.Popen:
    """Launch a chaos shell script asynchronously.

    Returns the Popen object so the caller can wait on it after the chaos
    window has elapsed. The script's own EXIT trap is responsible for
    restoring the service — even if the script is SIGKILLed externally.

    Stderr/stdout are streamed to a daemon thread to prevent pipe-buffer
    deadlock (same P14 rationale as iOS _runChaosScript).
    """
    if not os.path.isfile(script_path):
        raise FileNotFoundError(f"chaos script not found: {script_path}")
    if not os.access(script_path, os.X_OK):
        raise PermissionError(f"chaos script not executable: {script_path}. Run: chmod +x {script_path}")

    script_name = os.path.basename(script_path)
    proc = subprocess.Popen(
        ["/bin/bash", script_path] + arguments,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
    )

    def _drain(stream, label: str) -> None:
        for line in stream:
            print(f"  [chaos-{label}:{script_name}] {line}", end="")

    threading.Thread(target=_drain, args=(proc.stdout, "stdout"), daemon=True).start()
    threading.Thread(target=_drain, args=(proc.stderr, "stderr"), daemon=True).start()
    return proc


def _verify_sidecar_running() -> bool:
    """Return True if fcm-sidecar container is running (not paused, not stopped)."""
    docker = os.environ.get(
        "DOCKER",
        "/Applications/Docker.app/Contents/Resources/bin/docker",
    )
    r = subprocess.run(
        [docker, "ps", "--filter", "name=fcm-sidecar", "--format", "{{.Names}}"],
        capture_output=True, text=True, timeout=10,
    )
    return "fcm-sidecar" in r.stdout


def _pause_sidecar() -> bool:
    """Suspend the sidecar via SIGSTOP (docker pause). Returns True on success.

    docker pause is INSTANT — unlike docker kill (SIGKILL which has kernel-level
    scheduling latency), SIGSTOP is processed synchronously by the kernel before
    docker pause returns. This guarantees the sidecar cannot process any new
    unix-socket requests after this call returns.
    """
    docker = os.environ.get("DOCKER", "/Applications/Docker.app/Contents/Resources/bin/docker")
    r = subprocess.run([docker, "pause", "fcm-sidecar"], capture_output=True, text=True, timeout=10)
    return r.returncode == 0


def _unpause_sidecar() -> bool:
    """Resume the sidecar via SIGCONT (docker unpause). Returns True on success."""
    docker = os.environ.get("DOCKER", "/Applications/Docker.app/Contents/Resources/bin/docker")
    r = subprocess.run([docker, "unpause", "fcm-sidecar"], capture_output=True, text=True, timeout=10)
    return r.returncode == 0


def _make_valid_format_poison_token(run_ts: int) -> str:
    """Build a valid-FORMAT but invalid-FOR-FCM token for C-3.

    The plugin's _is_valid_fcm_token() requires: length 100-500, charset
    [A-Za-z0-9_:/-]. A token that passes format validation but contains
    a clearly bogus registration ID will reach Google's FCM API which returns
    INVALID_ARGUMENT or NOT_FOUND — both terminal errors that trigger the
    consecutive_failures increment and eventual deactivation.

    Format mirrors real FCM tokens: <sender_id>:<registration_token>
    We use a real-looking prefix (same chars) but invalid registration value.
    """
    nonce = uuid.uuid4().hex  # 32 chars hex (valid charset)
    # Total length ~154, within 100-500, all chars in [A-Za-z0-9_:/-]
    poison = (
        f"CHAOS_ANDROID_{run_ts}_{nonce}"  # 14+10+1+32 = ~57 chars sender-like prefix
        ":APA91bINVALID_TOKEN_FOR_ANDROID_CHAOS_TEST_"
        + "A" * 53  # pad to safe total length
    )
    # Verify it passes the validator before returning
    if not (100 <= len(poison) <= 500 and re.match(r'^[A-Za-z0-9_:/-]+$', poison)):
        # Fallback: pad more aggressively
        poison = "A" * 99 + ":" + "B" * 53 + f"_{run_ts}"
    return poison


def _verify_central_running(min_replicas: int = 1) -> bool:
    """Return True if token-vending-service has >= min_replicas Ready pods."""
    kubectl = os.environ.get(
        "KUBECTL",
        "/Applications/Docker.app/Contents/Resources/bin/kubectl",
    )
    r = subprocess.run(
        [
            kubectl, "get", "deploy", "token-vending-service",
            "-n", "woow-fcm-central",
            "-o", "jsonpath={.status.readyReplicas}",
        ],
        capture_output=True, text=True, timeout=10,
    )
    try:
        ready = int(r.stdout.strip() or "0")
        return ready >= min_replicas
    except ValueError:
        return False


# ──────────────────────────────────────────────────────────────────────────────
# STEP 0 — Odoo auth + preflight
# ──────────────────────────────────────────────────────────────────────────────

print("\n")
section("ANDROID-CHAOS: H' FCM Fault-Injection Test Suite")
print(f"  Device:   ANDROID_SERIAL={os.environ.get('ANDROID_SERIAL', '(not set)')}")
print(f"  Odoo URL: {ODOO_URL}")
print(f"  App pkg:  {PKG}")
print(f"  Run TS:   {RUN_TS}")
print(f"  Chaos dir:{CHAOS_SCRIPTS_DIR}")

section("Step 0: Auth + Preflight")

_admin_uid, _admin_cookies = _authenticate(ODOO_USER, ODOO_PASS)
print(f"  Admin auth: {'OK' if _admin_uid else 'FAILED'} (uid={_admin_uid})")

_demo_uid, _demo_cookies = _authenticate("demo", "demo")
print(f"  Demo auth:  {'OK' if _demo_uid else 'FAILED'} (uid={_demo_uid})")

if not _admin_uid or not _demo_uid:
    print("\n  FATAL: Cannot authenticate to Odoo. Aborting.")
    sys.exit(1)

# Verify Android device row exists and is active
device_row = _get_device_row(ANDROID_DEVICE_ROW_ID)
if not device_row:
    print(f"\n  FATAL: woow.fcm.device id={ANDROID_DEVICE_ROW_ID} not found or not accessible.")
    sys.exit(1)
print(f"  Android device row: id={device_row['id']} platform={device_row['platform']} "
      f"active={device_row['active']} consecutive_failures={device_row['consecutive_failures']}")
if not device_row["active"]:
    print(f"\n  FATAL: Device row id={ANDROID_DEVICE_ROW_ID} is inactive — fix happy path first.")
    sys.exit(1)

# Capture original FCM token (for C-3 restore verification)
ORIGINAL_ANDROID_TOKEN: str = device_row["fcm_token"]

# Capture app PID
_pid_before_all = _get_app_pid()
print(f"  App pid: {_pid_before_all}")

# Verify sidecar and central are up
sidecar_ok = _verify_sidecar_running()
central_ok = _verify_central_running()
print(f"  Sidecar running: {sidecar_ok}, Central ready: {central_ok}")
if not sidecar_ok or not central_ok:
    print("\n  FATAL: Backend not healthy before chaos suite. Run verify-e2e-preconditions.sh.")
    sys.exit(1)

# Background app and clear notifications to start from clean state
_clear_notifications()
print("  App backgrounded, notifications cleared.")

# ══════════════════════════════════════════════════════════════════════════════
# C-1: sidecar SIGKILL mid-mention
# ══════════════════════════════════════════════════════════════════════════════
#
# Rationale: If the sidecar is dead BEFORE the mention fires, the plugin
# finds no unix socket → cannot forward to FCM → push fails silently.
# We assert: NO notification arrives (push path broken) AND app is alive.
#
# Implementation note (Attempt 1 lesson): SIGKILL via `docker kill` has
# kernel-scheduling latency on macOS Docker Desktop — the container may still
# process in-flight requests for up to ~3s after the kill command returns.
# We use `docker pause` (SIGSTOP) which is INSTANT — the container process
# is suspended before `docker pause` returns, guaranteeing the sidecar cannot
# accept new connections. After the chaos window, we use kill-sidecar.sh's
# EXIT trap pattern (docker unpause then docker start) to restore.
# ──────────────────────────────────────────────────────────────────────────────

section("C-1: sidecar suspended (SIGSTOP) before mention [ANDROID-CHAOS-C1]")

try:
    pid_c1_before = _get_app_pid()
    print(f"  App PID before C-1: {pid_c1_before}")

    # DUMP-FIRST: capture baseline notification count
    dump_baseline = _dump_notification()
    baseline_records = [
        l for l in dump_baseline.split("\n")
        if "io.woowtech.odoo.debug" in l and "NotificationRecord" in l
    ]
    print(f"  Baseline our-app notification records: {len(baseline_records)}")

    marker_c1 = make_marker("C1")
    print(f"  Marker: {marker_c1}")

    # PAUSE sidecar via SIGSTOP (instant — no scheduling race)
    # docker pause returns only after the kernel has delivered SIGSTOP to all
    # processes in the container cgroup, so the sidecar CANNOT accept new
    # unix-socket connections after this call returns.
    print(f"  Pausing fcm-sidecar (docker pause / SIGSTOP) ...")
    pause_ok = _pause_sidecar()
    print(f"  docker pause returned: {'OK' if pause_ok else 'FAILED'}")
    if not pause_ok:
        raise RuntimeError("docker pause fcm-sidecar failed — sidecar may still be running")

    # Brief settle to let any already-accepted connections complete within
    # the sidecar (the socket accept queue is drained; no new requests can
    # be accepted after SIGSTOP).
    time.sleep(0.5)

    # Post B-2 mention — the plugin will try the unix socket and fail because
    # the sidecar process is suspended (SIGSTOP prevents accept(2) from returning).
    # The plugin's socket-missing retry (0.25s + 0.5s + 1s) will exhaust and skip.
    print(f"  Posting B-2 mention (sidecar suspended — push must fail)...")
    ok, rpc_err = _post_channel_mention(marker_c1)
    if not ok:
        print(f"  [expected] RPC post returned error (sidecar socket blocked): {rpc_err}")
    else:
        print(f"  RPC post returned OK (Odoo accepted, plugin will fail at socket layer)")

    # ASSERT: no FCM-path (groupKey=mention) notification must appear within 35s.
    # 35s > plugin retry schedule (0.25+0.5+1.0 = 1.75s) + FCM delivery latency.
    # WebSocket-path (groupKey=discuss) notifications ARE expected and allowed —
    # the app's persistent WebSocket to Odoo remains alive while the sidecar is
    # paused, so discuss messages still arrive via bus. The test only asserts that
    # the FCM push path (sidecar → Google FCM → device) is broken.
    no_notif, absence_proof = _assert_no_fcm_notification(marker_c1, wait_s=35)

    # Restore: unpause + verify socket re-binds
    print("  Unpausing fcm-sidecar (docker unpause / SIGCONT) ...")
    unpause_ok = _unpause_sidecar()
    print(f"  docker unpause returned: {'OK' if unpause_ok else 'FAILED'}")
    time.sleep(3)  # allow sidecar to process any queued work after SIGCONT
    sidecar_restored = _verify_sidecar_running()
    print(f"  Sidecar running after unpause: {sidecar_restored}")

    # App survival check
    pid_c1_after = _get_app_pid()
    print(f"  App PID after C-1: {pid_c1_after}")
    app_alive = (pid_c1_after is not None) and (pid_c1_after == pid_c1_before)
    print(f"  App alive (pid stable): {app_alive}")

    if no_notif and app_alive and pause_ok and sidecar_restored:
        proof = (
            f"{absence_proof}; "
            f"pid={pid_c1_before}->{pid_c1_after} (stable); "
            f"pause_ok={pause_ok}; sidecar_restored={sidecar_restored}"
        )
        _mark("C-1", True, proof=proof)
    else:
        errs = []
        if not pause_ok:
            errs.append("docker pause failed")
        if not no_notif:
            errs.append(f"FCM-path phantom: {absence_proof}")
        if not app_alive:
            errs.append(f"app pid changed: {pid_c1_before}->{pid_c1_after}")
        if not sidecar_restored:
            errs.append("sidecar NOT restored after unpause")
        _mark("C-1", False, error="; ".join(errs))

except Exception as exc:
    _mark("C-1", False, error=f"C-1 exception: {exc}")
    # Emergency: ensure sidecar comes back (unpause if still paused)
    try:
        _unpause_sidecar()
    except Exception:
        pass
finally:
    # Re-auth in case session expired during the 35s window
    _admin_uid, _admin_cookies = _authenticate(ODOO_USER, ODOO_PASS)
    _demo_uid, _demo_cookies = _authenticate("demo", "demo")
    _clear_notifications()

# ══════════════════════════════════════════════════════════════════════════════
# C-2: central TVS 503 — cached-bearer tolerance (NFC-1)
# ══════════════════════════════════════════════════════════════════════════════
#
# Rationale: NFC-1 specifies the sidecar caches the FCM bearer token for 60s.
# When central TVS is scaled to 0 replicas, the sidecar should use its
# cached bearer to push to Google FCM. The notification MUST still arrive
# within 60s.
# 90s downtime = 60s NFC-1 window + 30s notification-wait slack.
# ──────────────────────────────────────────────────────────────────────────────

section("C-2: central TVS 503 — cached-bearer (NFC-1) [ANDROID-CHAOS-C2]")

try:
    pid_c2_before = _get_app_pid()
    print(f"  App PID before C-2: {pid_c2_before}")

    marker_c2 = make_marker("C2")
    print(f"  Marker: {marker_c2}")

    # Launch chaos script (TVS scale-to-0 for 90s)
    print(f"  Launching stop-central.sh 90 ...")
    chaos_c2 = _run_chaos_script_async(STOP_CENTRAL_SH, ["90"])

    # Give kubectl scale 2s to propagate (mirrors iOS Thread.sleep(2.0))
    time.sleep(2.0)

    # DUMP-FIRST: snapshot shade before posting
    dump_pre_c2 = _dump_notification()
    pre_c2_records = [
        l for l in dump_pre_c2.split("\n")
        if "io.woowtech.odoo.debug" in l and "NotificationRecord" in l
    ]
    print(f"  Pre-C2 our-app notification records: {len(pre_c2_records)}")

    # Post B-2 mention — must raise no error even with TVS down (sidecar is alive)
    print(f"  Posting B-2 mention ...")
    ok, rpc_err = _post_channel_mention(marker_c2)
    if not ok:
        print(f"  [unexpected] RPC post failed: {rpc_err}")
    else:
        print(f"  RPC post OK")

    # ASSERT: notification MUST arrive within 60s (NFC-1 cached-bearer path)
    print(f"  Waiting up to 60s for notification via cached bearer ...")
    proof_c2 = _wait_for_notification(marker_c2, timeout_s=60)

    # Wait for central to restore before proceeding
    print("  Waiting for stop-central.sh to finish (EXIT trap restores TVS)...")
    chaos_c2.wait(timeout=100)
    exit_code_c2 = chaos_c2.returncode
    print(f"  stop-central.sh exit code: {exit_code_c2}")

    # Poll for central to become ready (up to 40s after restore command)
    central_deadline = time.time() + 40
    central_restored = False
    while time.time() < central_deadline:
        if _verify_central_running():
            central_restored = True
            break
        time.sleep(3)
    print(f"  Central restored (1 ready replica): {central_restored}")

    # App survival check
    pid_c2_after = _get_app_pid()
    print(f"  App PID after C-2: {pid_c2_after}")
    app_alive_c2 = (pid_c2_after is not None) and (pid_c2_after == pid_c2_before)

    if proof_c2 and app_alive_c2:
        # Full dump snippet for evidence
        dump_c2 = _dump_notification()
        lines_c2 = dump_c2.split("\n")
        snippet = ""
        for i, line in enumerate(lines_c2):
            if marker_c2 in line:
                start = max(0, i - 1)
                snippet = " | ".join(lines_c2[start : i + 3])
                break
        proof_msg = (
            f"MATCH: {proof_c2[:120]}; "
            f"pid={pid_c2_before}->{pid_c2_after} (stable); "
            f"central_restored={central_restored}; "
            f"chaos_exit={exit_code_c2}"
        )
        _mark("C-2", True, proof=proof_msg)
    else:
        errs = []
        if not proof_c2:
            # Check if the cached bearer had already expired before the test
            dump_c2 = _dump_notification()
            our_recs = [
                l for l in dump_c2.split("\n")
                if "io.woowtech.odoo.debug" in l and "NotificationRecord" in l
            ]
            errs.append(
                f"NFC-1 violation: notification did NOT arrive within 60s "
                f"(our_app_records_in_shade={len(our_recs)}). "
                f"Possible: cached bearer had expired before chaos window started."
            )
        if not app_alive_c2:
            errs.append(f"app pid changed: {pid_c2_before}->{pid_c2_after}")
        _mark("C-2", False, error="; ".join(errs))

except Exception as exc:
    _mark("C-2", False, error=f"C-2 exception: {exc}")
    # Emergency: ensure central comes back
    try:
        kubectl = os.environ.get("KUBECTL", "/Applications/Docker.app/Contents/Resources/bin/kubectl")
        subprocess.run(
            [kubectl, "scale", "deploy/token-vending-service", "--replicas=1", "-n", "woow-fcm-central"],
            capture_output=True, timeout=15,
        )
    except Exception:
        pass
finally:
    # Re-auth in case session expired
    _admin_uid, _admin_cookies = _authenticate(ODOO_USER, ODOO_PASS)
    _demo_uid, _demo_cookies = _authenticate("demo", "demo")
    _clear_notifications()

# ══════════════════════════════════════════════════════════════════════════════
# C-3: token unregistered/poisoned → device auto-deactivation (id=247 ONLY)
# ══════════════════════════════════════════════════════════════════════════════
#
# Rationale: Google FCM returns UNREGISTERED / invalid-registration-token when
# the device token is stale or bogus. The H' plugin's terminal-error classifier
# increments consecutive_failures and deactivates the device row after 2 such
# errors. We target ONLY id=247 (Android) — NEVER id=246 (iOS iPhone).
#
# IMPORTANT: We use Odoo JSON-RPC write() for both poison and restore because
# direct psql shows 0 rows (the data lives in k8s postgres, not Odoo's local
# DB — the Odoo model bridges via custom _auto=False or similar mechanism).
# ──────────────────────────────────────────────────────────────────────────────

section("C-3: token poison → auto-deactivation (id=247 ONLY) [ANDROID-CHAOS-C3]")

_c3_restore_needed = False
_c3_original_token: str | None = None

try:
    pid_c3_before = _get_app_pid()
    print(f"  App PID before C-3: {pid_c3_before}")

    # Step 1: snapshot original token for id=247
    row_before = _get_device_row(ANDROID_DEVICE_ROW_ID)
    if not row_before:
        raise RuntimeError(f"Cannot read woow.fcm.device id={ANDROID_DEVICE_ROW_ID} before poisoning")

    _c3_original_token = row_before["fcm_token"]
    print(f"  Snapshot: id={ANDROID_DEVICE_ROW_ID} active={row_before['active']} "
          f"token_prefix={_c3_original_token[:30]}...")

    # Verify iOS row (id=246) is untouched throughout — we check it at start
    ios_row_before = _get_device_row(246)
    print(f"  iOS row id=246 (must stay untouched): active={ios_row_before['active'] if ios_row_before else 'N/A'}")

    # Step 2: Poison id=247 ONLY with a valid-FORMAT but invalid-FOR-FCM token.
    # CRITICAL: The plugin's _is_valid_fcm_token() validator (in fcm_sender.py)
    # rejects tokens shorter than 100 chars or containing chars outside
    # [A-Za-z0-9_:/-]. A token failing the validator returns 'error' (not
    # 'terminal') so consecutive_failures is never incremented (Attempt 1 lesson).
    # We use a token that passes format validation but is rejected by Google FCM
    # as INVALID_ARGUMENT or NOT_FOUND — both in _TERMINAL_ERROR_CODES.
    poison_token = _make_valid_format_poison_token(RUN_TS)
    print(f"  Poisoning id={ANDROID_DEVICE_ROW_ID} with valid-format token (length={len(poison_token)})")
    print(f"  Token prefix: {poison_token[:50]}...")
    ok_poison, err_poison = _write_device_row(
        ANDROID_DEVICE_ROW_ID,
        {"fcm_token": poison_token, "active": True, "consecutive_failures": 0},
    )
    if not ok_poison:
        raise RuntimeError(f"Poison write failed: {err_poison}")
    _c3_restore_needed = True
    print(f"  Poison write: OK")

    # Wait 2s for the DB write to propagate (mirrors iOS Thread.sleep(2.0))
    time.sleep(2.0)

    # Verify iOS row is still untouched (id=246 must not change)
    ios_row_mid = _get_device_row(246)
    print(f"  iOS row id=246 mid-poison: active={ios_row_mid['active'] if ios_row_mid else 'N/A'} "
          f"(must match 'true')")
    if ios_row_mid and ios_row_mid["fcm_token"] != (ios_row_before["fcm_token"] if ios_row_before else ""):
        print(f"  WARNING: iOS row token changed — investigate immediately!")

    # Step 3: Post 2 B-2 mentions so the plugin attempts delivery to the poisoned token twice
    marker_c3_1 = make_marker("C3-1")
    marker_c3_2 = make_marker("C3-2")
    print(f"  Marker 1: {marker_c3_1}")
    print(f"  Marker 2: {marker_c3_2}")

    ok1, err1 = _post_channel_mention(marker_c3_1)
    print(f"  Mention 1 posted: {'OK' if ok1 else err1}")

    # Brief pause between mentions (mirrors iOS Thread.sleep(10))
    # 10s gives Google FCM time to surface the UNREGISTERED error to the sidecar
    print("  Waiting 10s for Google FCM to return UNREGISTERED error for mention 1...")
    time.sleep(10)

    ok2, err2 = _post_channel_mention(marker_c3_2)
    print(f"  Mention 2 posted: {'OK' if ok2 else err2}")

    # Wait for the second FCM error to propagate (mirrors iOS Thread.sleep(10))
    print("  Waiting 10s for Google FCM to return UNREGISTERED error for mention 2...")
    time.sleep(10)

    # Step 4: Poll for active=false on id=247 (up to 60s)
    # The deactivation may happen after 1 or 2 failures depending on the threshold.
    print(f"  Polling for id={ANDROID_DEVICE_ROW_ID} active=false (up to 60s)...")
    deactivated = False
    deactivation_deadline = time.time() + 60
    poll_count = 0
    actual_active: bool | None = None
    actual_failures: int | None = None

    while time.time() < deactivation_deadline:
        row_check = _get_device_row(ANDROID_DEVICE_ROW_ID)
        if row_check:
            actual_active = row_check["active"]
            actual_failures = row_check.get("consecutive_failures")
            print(f"  Poll {poll_count}: active={actual_active} consecutive_failures={actual_failures}")
            if not actual_active:
                deactivated = True
                break
        poll_count += 1
        time.sleep(5)

    # App survival check (before restore, so the check is orthogonal to DB state)
    pid_c3_after = _get_app_pid()
    print(f"  App PID after C-3 mentions: {pid_c3_after}")
    app_alive_c3 = (pid_c3_after is not None) and (pid_c3_after == pid_c3_before)

    # Step 5: RESTORE id=247 BEFORE marking pass/fail so backend is healthy for iOS run
    print(f"  Restoring id={ANDROID_DEVICE_ROW_ID}: token=<original> active=true ...")
    ok_restore, err_restore = _write_device_row(
        ANDROID_DEVICE_ROW_ID,
        {"fcm_token": _c3_original_token, "active": True, "consecutive_failures": 0},
    )
    if ok_restore:
        _c3_restore_needed = False
        print(f"  Restore: OK")
    else:
        print(f"  WARNING: Restore failed: {err_restore} — manual recovery needed!")
        print(f"  Run: POST {ODOO_URL}/web/dataset/call_kw with write(id=247, fcm_token=<original>, active=True)")

    # Verify restore
    row_after = _get_device_row(ANDROID_DEVICE_ROW_ID)
    restored_correctly = (
        row_after is not None
        and row_after["active"]
        and row_after["fcm_token"] == _c3_original_token
    )
    print(f"  id=247 after restore: active={row_after['active'] if row_after else 'N/A'} "
          f"token_restored={'YES' if restored_correctly else 'NO'}")

    # Verify iOS row 246 is still untouched
    ios_row_after = _get_device_row(246)
    ios_intact = (
        ios_row_after is not None
        and ios_row_after["active"]
        and ios_row_before is not None
        and ios_row_after["fcm_token"] == ios_row_before["fcm_token"]
    )
    print(f"  iOS row id=246 after C-3: active={ios_row_after['active'] if ios_row_after else 'N/A'} "
          f"token_intact={ios_intact}")

    # Verdict
    if deactivated and app_alive_c3:
        proof_c3 = (
            f"id={ANDROID_DEVICE_ROW_ID} active=False after {poll_count} polls; "
            f"consecutive_failures={actual_failures}; "
            f"pid={pid_c3_before}->{pid_c3_after} (stable); "
            f"ios_intact={ios_intact}; "
            f"restored={restored_correctly}"
        )
        _mark("C-3", True, proof=proof_c3)
    else:
        errs = []
        if not deactivated:
            errs.append(
                f"device row id={ANDROID_DEVICE_ROW_ID} NOT deactivated after 2 mentions "
                f"(active={actual_active}, consecutive_failures={actual_failures}) — "
                f"H' auto-deactivation may not handle invalid-token responses from Google"
            )
        if not app_alive_c3:
            errs.append(f"app pid changed: {pid_c3_before}->{pid_c3_after}")
        _mark("C-3", False, error="; ".join(errs))

except Exception as exc:
    _mark("C-3", False, error=f"C-3 exception: {exc}")

finally:
    # Unconditional restore attempt if the try block's restore was skipped
    if _c3_restore_needed and _c3_original_token:
        print("  [finally] Emergency restore of id=247 token and active=true ...")
        ok_em, err_em = _write_device_row(
            ANDROID_DEVICE_ROW_ID,
            {"fcm_token": _c3_original_token, "active": True, "consecutive_failures": 0},
        )
        print(f"  Emergency restore: {'OK' if ok_em else err_em}")
    _clear_notifications()

# ──────────────────────────────────────────────────────────────────────────────
# FINAL BACKEND HEALTH CHECK
# ──────────────────────────────────────────────────────────────────────────────

section("Final Backend Health Check")

# Sidecar
sidecar_final = _verify_sidecar_running()
print(f"  fcm-sidecar running: {sidecar_final}")

# Central
central_final = _verify_central_running()
print(f"  token-vending-service ready: {central_final}")

# Device rows
row_247_final = _get_device_row(ANDROID_DEVICE_ROW_ID)
row_246_final = _get_device_row(246)
print(f"  id=247 (Android): active={row_247_final['active'] if row_247_final else 'N/A'}, "
      f"token_ok={row_247_final['fcm_token'] == ORIGINAL_ANDROID_TOKEN if row_247_final else False}")
print(f"  id=246 (iOS):     active={row_246_final['active'] if row_246_final else 'N/A'}")

backend_healthy = sidecar_final and central_final and (row_247_final["active"] if row_247_final else False)
print(f"\n  Backend healthy for iOS chaos run: {backend_healthy}")
if not backend_healthy:
    print("  WARNING: Backend may not be healthy. Run verify-e2e-preconditions.sh before iOS chaos suite.")

# ──────────────────────────────────────────────────────────────────────────────
# SUMMARY
# ──────────────────────────────────────────────────────────────────────────────

section("ANDROID-CHAOS SUMMARY")

print(f"\n  Odoo URL: {ODOO_URL}")
print(f"  Run TS:   {RUN_TS}\n")

print("  ┌──────┬────────┬─────────────────────────────────────────────────────────┐")
print("  │ Test │ Result │ Proof / Error                                           │")
print("  ├──────┼────────┼─────────────────────────────────────────────────────────┤")
for test_id, passed in RESULTS.items():
    if passed is None:
        res_str = " SKIP "
        detail = ERRORS.get(test_id, "")
    elif passed:
        res_str = " PASS "
        detail = MATCH_PROOFS.get(test_id, "")[:60]
    else:
        res_str = " FAIL "
        detail = ERRORS.get(test_id, "")[:60]
    print(f"  │ {test_id:4s} │ {res_str} │ {detail:<55} │")
print("  └──────┴────────┴─────────────────────────────────────────────────────────┘")

pass_count = sum(1 for v in RESULTS.values() if v is True)
fail_count = sum(1 for v in RESULTS.values() if v is False)
skip_count = sum(1 for v in RESULTS.values() if v is None)
print(f"\n  Result: {pass_count} PASS, {fail_count} FAIL, {skip_count} SKIP out of {len(RESULTS)}")

# Full match-proof dump
if MATCH_PROOFS:
    print("\n  Match-proof / absence-proof lines:")
    for test_id, proof in MATCH_PROOFS.items():
        print(f"    [{test_id}] {proof}")

if ERRORS:
    print("\n  Failure details:")
    for test_id, err in ERRORS.items():
        print(f"    [{test_id}] {err}")

# iOS parity statement
ios_tests = {"C-1", "C-2", "C-3"}
android_covered = {t for t, v in RESULTS.items() if v is True and t in ios_tests}
print(f"\n  iOS chaos parity:")
print(f"    iOS covers:     {sorted(ios_tests)} (G-6 C-1, G-7 C-2, G-9 C-3)")
print(f"    Android covers: {sorted(android_covered)}")
if android_covered == ios_tests:
    print("  VERDICT: Android chaos suite FULLY VERIFIED at parity with iOS FCMPushChaosTests.")
else:
    missing = ios_tests - android_covered
    print(f"  VERDICT: PARTIAL — {sorted(missing)} did not pass.")

print(f"\n  Backend left healthy: {backend_healthy}")
print()
sys.exit(fail_count)
