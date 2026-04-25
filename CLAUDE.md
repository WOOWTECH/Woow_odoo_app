# AI Instructions — Woow Odoo Android App

## Project Overview

Android companion app for Odoo ERP. Wraps Odoo web UI in a WebView with native authentication, FCM push notifications, multi-account support, biometric/PIN lock, brand theming, and multilingual support (EN, zh-TW, zh-CN).

Built entirely in Kotlin + Jetpack Compose.

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Run all unit tests
./gradlew testDebugUnitTest

# Code formatting
./gradlew ktlintFormat

# Device verification (phone connected via USB)
python3 scripts/verify-on-device.py

# E2E production tests
python3 scripts/e2e-production-test.py

# E2E verification report with screenshots
python3 scripts/e2e-verification-report.py
```

## Architecture

- **Language:** Kotlin only
- **UI:** Jetpack Compose (no XML)
- **DI:** Hilt
- **Networking:** OkHttp + JSON-RPC 2.0 (no Retrofit for JSON-RPC)
- **Database:** Room
- **Storage:** EncryptedSharedPreferences (AES-256-GCM)
- **Push:** Firebase Cloud Messaging (FCM)
- **Navigation:** Navigation Compose
- **Logging:** Timber (never android.util.Log)
- **Concurrency:** Kotlin Coroutines + Flow
- **Testing:** JUnit 5 + MockK + Turbine + uiautomator2

## Directory Structure

```
app/src/main/java/io/woowtech/odoo/
├── WoowOdooApp.kt              # Application class (Timber, NotificationChannel, FCM token)
├── ui/
│   ├── MainActivity.kt         # Single activity, deep link handler
│   ├── navigation/NavGraph.kt  # Navigation + LifecycleEventEffect auth
│   ├── auth/                   # BiometricScreen, PinScreen, AuthViewModel
│   ├── login/                  # LoginScreen, LoginViewModel
│   ├── main/                   # MainScreen (WebView), MainViewModel
│   ├── config/                 # ConfigScreen, ConfigViewModel
│   └── theme/                  # Color.kt, Theme.kt, Type.kt
├── data/
│   ├── api/OdooJsonRpcClient.kt    # HTTP client (OkHttp + JSON-RPC)
│   ├── push/                       # WoowFcmService, NotificationHelper, DeepLinkManager, DeepLinkValidator
│   ├── repository/                 # AccountRepository, SettingsRepository, FcmTokenRepository, CacheRepository
│   ├── local/                      # Room DB, EncryptedPrefs, AccountDao
│   └── security/                   # EncryptionHelper
├── domain/model/                   # OdooAccount, AuthResult, AppSettings
└── di/AppModule.kt                 # Hilt bindings
```

## Key Conventions

### Code Style
- **Kotlin only** — no Java
- **Timber for logging** — never `android.util.Log` (leaks in production)
- **OkHttp logging guarded:** `if (BuildConfig.DEBUG) Level.BODY else Level.NONE`
- **collectAsStateWithLifecycle()** — never bare `collectAsState()`
- **Immutable models** — use `copy()` for mutations
- **No `Any` types** in public API where possible

### Security
- PIN hash: **PBKDF2 + random salt** (600K iterations) — never SHA-256
- PIN lockout: **exponential** (30s → 5min → 30min → 1hr)
- Lockout timing: `SystemClock.elapsedRealtime()` — never `System.currentTimeMillis()`
- WebView: **same-host only**, no third-party cookies, `allowFileAccess = false`
- Deep links: **allowlist validation** — reject javascript:, data:, external hosts
- Biometric: **no skip button** — removed for security
- Auth on background: `LifecycleEventEffect(ON_STOP)` resets `isAuthenticated`
- PendingIntent: **FLAG_IMMUTABLE** (required API 31+)
- Notifications: **VISIBILITY_PRIVATE** (hide content on lock screen)
- CookieStore: **ConcurrentHashMap** (thread-safe)

### Testing
- **178 unit tests** (JUnit 5 + MockK + Turbine)
- **30 device checks** (uiautomator2, scripts/verify-on-device.py)
- **22 E2E production tests** (scripts/e2e-production-test.py)
- **7 Odoo module tests** (TransactionCase)
- Test naming: `Given X when Y then Z` (Kotlin), `test_{method}_given{X}_returns{Y}` (general)

### Test Independence (CRITICAL RULE)

**Every test must be independently runnable.** A test must pass when run alone,
in any order with other tests, or in parallel — same outcome every time. No test
may depend on side effects from another test.

**Why:** chained tests cascade failures (one breakage fails N tests), make CI
flaky, prevent parallel execution, and create order-sensitive bugs. The same
trap was hit in the iOS port — iOS now uses `ensureAccountThenRelaunch` to make
every E2E self-contained (`docs/2026-04-14-E2E-Test-Progress.md:159`).

**Required pattern — every test owns its own setup, action, cleanup:**

```python
# ✅ GOOD — self-contained
def test_V22_pin_keypad():
    # Setup (idempotent — does nothing if already in target state)
    ensure_logged_in()
    apply_test_hook(test_pin="1234", app_lock=True, biometric=False)
    restart_app_to_trigger_gate()
    # Action + assertion
    assert digit_keys_visible() == 10
    # Cleanup so next test isn't affected
    type_pin("1234"); wait_for_webview()
    apply_test_hook(app_lock=False)
```

```python
# ❌ BAD — depends on prior test
def test_V24_bg_fg():
    # Assumes V22 just ran and left us authenticated with lock on
    press_home()
    launch_app()
    assert auth_screen_visible()
```

**Test hooks** (`io.woowtech.odoo.ui.TestHooks`, debug-only):
- `--es test-pin <4-6 digits>` — seed PIN
- `--ez app-lock-enabled <bool>` — toggle App Lock
- `--ez biometric-enabled <bool>` — toggle biometric
- `--ez reset-state <bool>` — clear failed PIN attempts / auth state

Use these in `scripts/verify-on-device.py` to set preconditions without
multi-step Compose UI navigation. Hooks are stripped by R8 in release builds.

**`pm clear` is destructive** — only use it inside the ONE test that explicitly
exercises the empty-state behaviour (currently V23 deep-link rejection). Other
tests should re-create state via hooks instead of cleaning data.

### Verification Rules

**Every commit must pass:**
1. `./gradlew assembleDebug` — build succeeds
2. `./gradlew testDebugUnitTest` — 0 failures
3. `python3 scripts/verify-on-device.py` — all checks pass (if device connected)

**Screenshot verification (CRITICAL RULE):**

Never take a screenshot without first verifying expected content is on screen.

```python
# ❌ BAD — blindly capture after sleep (may get blank/loading screen)
time.sleep(15)
d.screenshot("step.png")

# ✅ GOOD — verify element exists, retry if not ready
for attempt in range(15):
    time.sleep(2)
    if d(text="WoowTech Odoo").exists(timeout=1):
        d.screenshot("step.png")
        break
else:
    print("FAIL: expected content not found after 30s")
```

Always:
- Check the current activity is correct before capturing
- Clear notification bar (`adb shell service call notification 1`) before notification tests
- Wait for WebView content (poll screenshot size > threshold)
- Verify notification sender/body text exists before capturing notification shade

### Commit Messages

Format: `type(phase): description`

Types: `security`, `feat`, `fix`, `refactor`, `test`, `chore`, `docs`

Phases: `B0` (security), `B1` (colors), `B2` (zh-CN), `B3` (biometric), `B4` (cache), `A1` (Firebase), `A2` (Odoo module)

Example: `security(B0): replace android.util.Log with Timber, guard OkHttp logging`

## Firebase Configuration

- **Project:** `woow-odoo-de2cb`
- **google-services.json:** `app/google-services.json` (in .gitignore)
- **Service account:** `app/firebase-service-account.json` (in .gitignore, NEVER commit)
- **App IDs:** `io.woowtech.odoo` (release) + `io.woowtech.odoo.debug` (debug)
- **google-services plugin:** conditionally applied with `if (file("google-services.json").exists())`

## Odoo Backend Module

The `woow_fcm_push` Odoo module is in a separate repo: `github.com/WOOWTECH/woow_odoo_fcm_push`

It hooks into `mail.message.create()`, `discuss.channel.message_post()`, and `mail.activity.create()` to send FCM push notifications.

## Reference Documents

| Document | Location |
|----------|----------|
| Implementation plan | `docs/plans/2026-03-22-implementation-plan.md` |
| Test strategy | `docs/plans/2026-03-22-test-strategy.md` |
| Test plan (178 tests) | `docs/plans/2026-03-23-test-plan.md` |
| Final verification | `docs/plans/2026-03-24-final-verification-report.md` |
| Verification report | `docs/verification-report/verification-report.md` |
| FCM setup guide | `docs/verification-report/woow-fcm-push-setup-guide.md` |
| iOS porting plan | `docs/plans/2026-03-25-ios-porting-plan.md` |
| Device verification | `scripts/verify-on-device.py` |
| E2E production tests | `scripts/e2e-production-test.py` |
| E2E verification report | `scripts/e2e-verification-report.py` |
