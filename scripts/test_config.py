"""Single source of truth for E2E test configuration.

Resolution order for each value:  env var  ->  .env.test file  ->  default.

To rotate the dev tunnel URL, update ONE of:
  - this file's `ODOO_URL` default (committed)
  - `.env.test` at repo root (gitignored, local dev)
  - `ODOO_URL` env var (CI)

This mirrors the iOS `SharedTestConfig` pattern documented in CLAUDE.md
("Test Script Catalog -> Hard rule on test config").
"""
from __future__ import annotations

import os
from pathlib import Path

# ─── .env.test loader (gitignored, project-local override) ──────────────────
_REPO_ROOT = Path(__file__).resolve().parent.parent
_ENV_FILE = _REPO_ROOT / ".env.test"
_env_file_vars: dict[str, str] = {}
if _ENV_FILE.exists():
    for raw in _ENV_FILE.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        _env_file_vars[k.strip()] = v.strip().strip('"').strip("'")


def _get(key: str, default: str) -> str:
    """env var > .env.test > built-in default."""
    return os.environ.get(key) or _env_file_vars.get(key) or default


# ─── Odoo server (live cloudflared tunnel) ──────────────────────────────────
# When the tunnel rotates, change THIS line (or set ODOO_URL env var).
ODOO_URL = _get("ODOO_URL", "https://tanks-urgent-randy-ate.trycloudflare.com")

# Host-only form (no scheme), for scripts that type into the app's URL field.
ODOO_HOST = ODOO_URL.replace("https://", "").replace("http://", "").rstrip("/")

ODOO_DB = _get("ODOO_DB", "odoo18_ecpay")
ODOO_USER = _get("ODOO_USER", "admin")
ODOO_PASS = _get("ODOO_PASS", "admin")

# ─── Android app under test ─────────────────────────────────────────────────
APP_PACKAGE = _get("APP_PACKAGE", "io.woowtech.odoo.debug")
APP_ACTIVITY = _get("APP_ACTIVITY", "io.woowtech.odoo.ui.MainActivity")

# ─── Firebase (FCM tests) ───────────────────────────────────────────────────
FIREBASE_SA_FILE = _get(
    "FIREBASE_SA_FILE",
    str(_REPO_ROOT / "app" / "firebase-service-account.json"),
)
FIREBASE_PROJECT_ID = _get("FIREBASE_PROJECT_ID", "woow-odoo-de2cb")

# ─── Verification report output (e2e-verification-report.py) ────────────────
VERIFICATION_REPORT_DIR = _get(
    "VERIFICATION_REPORT_DIR",
    str(_REPO_ROOT / "docs" / "verification-report"),
)


# ─── ADBKeyboard helpers (avoid IME autocorrect mangling URLs/passwords) ────
# Must be called BEFORE typing into any EditText (login URL, credentials, PIN).
# The default IME (Gboard, SwiftKey, MIUI keyboard) silently autocorrects
# tokens like "trycloudflare" → "try cloudflare" or capitalises the first
# letter of "admin" → "Admin", which breaks login. ADBKeyboard is byte-perfect.
#
# Bundled with uiautomator2 as `com.github.uiautomator/.AdbKeyboard` — no
# separate APK install needed. uiautomator2's `send_keys()` automatically
# uses the ADB_INPUT_TEXT broadcast when this IME is active.
import subprocess as _sp

ADB_KEYBOARD_IME = "com.github.uiautomator/.AdbKeyboard"


def enable_adb_keyboard() -> str | None:
    """Switch to ADBKeyboard. Returns the previous IME so caller can restore it.

    Idempotent: safe to call multiple times. No-op if already active.
    Returns None if no device is connected (caller may choose to skip).
    """
    try:
        prev = _sp.check_output(
            ["adb", "shell", "settings", "get", "secure", "default_input_method"],
            timeout=5, text=True,
        ).strip()
    except (_sp.CalledProcessError, _sp.TimeoutExpired, FileNotFoundError):
        return None
    if prev == ADB_KEYBOARD_IME:
        return prev  # already active
    _sp.run(["adb", "shell", "ime", "enable", ADB_KEYBOARD_IME], timeout=5)
    _sp.run(["adb", "shell", "ime", "set", ADB_KEYBOARD_IME], timeout=5)
    return prev


def restore_ime(previous_ime: str | None) -> None:
    """Restore the IME captured by `enable_adb_keyboard`. No-op if None."""
    if not previous_ime or previous_ime == ADB_KEYBOARD_IME:
        return
    _sp.run(["adb", "shell", "ime", "set", previous_ime], timeout=5)


if __name__ == "__main__":
    # Print resolved config — useful for debugging "which value did it pick up?"
    for k in sorted(globals()):
        if k.startswith("_") or k.islower():
            continue
        print(f"{k:25s} = {globals()[k]!r}")
