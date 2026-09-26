"""Single source of truth for E2E test configuration.

Resolution order for each value:  env var  ->  .env.test file  ->  default.

The Odoo test server has NO committed default: supply ODOO_URL, ODOO_DB,
ODOO_USER and ODOO_PASS via ONE of:
  - env vars (CI, one-off runs)
  - `.env.test` at repo root (gitignored — never commit credentials), e.g.
        ODOO_URL=https://your-odoo.example.com
        ODOO_DB=yourdb
        ODOO_USER=tester@example.com
        ODOO_PASS=...
Scripts that need the server should refuse to run while ODOO_URL is empty.

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


# ─── Odoo server ────────────────────────────────────────────────────────────
# No default on purpose: the old trycloudflare tunnel default went dead and
# produced cascade failures that looked like regressions. Supply ODOO_URL /
# ODOO_DB / ODOO_USER / ODOO_PASS via env or .env.test (see module docstring).
ODOO_URL = _get("ODOO_URL", "")

# Host-only form (no scheme), for scripts that type into the app's URL field.
ODOO_HOST = ODOO_URL.replace("https://", "").replace("http://", "").rstrip("/")

ODOO_DB = _get("ODOO_DB", "")
ODOO_USER = _get("ODOO_USER", "")
ODOO_PASS = _get("ODOO_PASS", "")

# ─── Android app under test ─────────────────────────────────────────────────
from brand_test_target import authorize_live, target_for

APP_VARIANT = _get("APP_VARIANT", "woowtechDebug")
APP_TARGET = target_for(APP_VARIANT)
APP_PACKAGE = _get("APP_PACKAGE", APP_TARGET.package)
if APP_PACKAGE != APP_TARGET.package:
    raise ValueError("APP_PACKAGE must match APP_VARIANT; cross-brand overrides are forbidden")
# The Activity class belongs to the unchanged namespace, not the applicationId.
APP_ACTIVITY = "io.woowtech.odoo.ui.MainActivity"
APP_TITLES = (APP_TARGET.title,)
APP_APK_PATH = str(_REPO_ROOT / APP_TARGET.apk_path)

# Only WOOW retains the existing SA path. Apporo must explicitly select its own path.
FIREBASE_SA_FILE = _get(
    "FIREBASE_SA_FILE",
    str(_REPO_ROOT / "app" / "firebase-service-account.json") if APP_TARGET.brand == "woowtech" else "",
)
FIREBASE_PROJECT_ID = _get("FIREBASE_PROJECT_ID", APP_TARGET.firebase_project)
if FIREBASE_PROJECT_ID != APP_TARGET.firebase_project:
    raise ValueError("FIREBASE_PROJECT_ID must match APP_VARIANT")

# Separate every artifact directory by variant; never merge cross-brand evidence.
VERIFICATION_REPORT_DIR = str(Path(_get(
    "VERIFICATION_REPORT_DIR", str(_REPO_ROOT / "docs" / "verification-report")
)) / APP_VARIANT)
VERIFY_REPORT_FILE = str(Path(VERIFICATION_REPORT_DIR) / "device-verification-log.md")
E2E_REPORT_FILE = str(Path(VERIFICATION_REPORT_DIR) / "e2e-test-results.md")


def require_live_test_authorization(scope="push"):
    """scope="ui" only for scripts that never send or assert push; default stays "push"."""
    authorize_live(APP_TARGET, ODOO_URL, os.environ, scope=scope, account=ODOO_USER)
    print(f"Explicit live target: {APP_VARIANT} / {APP_PACKAGE}")


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
    # Never dump resolved config: it includes ODOO_PASS and potentially secret paths.
    print(f"Target: {APP_VARIANT} / {APP_PACKAGE}; credentials are not displayed")
