# AI Instructions — Woow Odoo Android App

## 2026-09-26 W1 程式修正（本機 commit，未 push）

- W1-3：engineering `b1b052f`（繁中「渥屋平台」）已合併；名稱只放 `app/src/woowtech/res/values-zh-rTW/strings.xml`，`main/` 與 apporo 三語名稱不變。
- W1-7：`authorize_live(..., scope, account)`；Apporo debug 只在 `scope="ui"`（`verify-on-device.py`、`e2e_15_clockin_full.py`）、`ALLOW_APPORO_LIVE_UI=<package>`、`ODOO_USER == APPORO_LIVE_ACCOUNT`、僅 demo111 時放行；push 範圍（其餘腳本、預設值）一律拒絕，V20 對 Apporo 記為 skip。實際開旗標跑 live 仍需擁有者 B14 批准。
- W1-10：「記住我」未勾 → `authenticate(..., rememberPassword=false)` 不存密碼並移除同帳號舊密碼（WOOW／Apporo 兩路徑；Apporo 提交失敗回滾舊密碼）；active 帳號無密碼仍可收深層連結（`canRouteDeepLink`）。「清除快取」= `WebView.clearCache(true)` + `WebStorage.deleteAllData()`，不碰 cookie／帳號／密碼。已知取捨：未記住的帳號 session 過期需重新登入；Apporo 推播對未記住帳號回報 SIGN_IN_REQUIRED（push transport 以存有密碼判定帳號仍有效）。

## 2026-09-25 階段 3 review 修正（待獨立驗收）

- 兩P1窄修：明確Apporo isolated manual/switch入口；shared WebView reauth成功發布有效SID。selection內ensureActive＋短local NonCancellable/rollback，push/network等待仍可取消。
- 核准sandbox封閉JVM：兩flavor各502總數、499 PASS/0 FAIL/3既有Keystore SKIP；37 offline只是source契約。使用synthetic Firebase與既有agent預載，未assemble/install，不可拿此生成資源安裝。
- 初次完整suite的舊API測試曾對真demo host嘗試authenticate並回可解析錯誤；原XML/log只讀保留。不宣稱無遠端影響。已撤回raw尾空白拒絕，兩品牌UI trim mock成功；測試均mock/owned loopback/NO_PROXY＋sandbox。
- 本輪結果、事件、命令、XML與剩餘review/實機門檻見 `docs/verification-report/apporo-phase3/validation.md`。以下前輪禁止Gradle/未編譯敘述僅為歷史；本輪獲明確授權的compile/JVM已執行，沒有授權live或發布。


## 2026-09-25 階段 3 推播候選（尚未編譯／獨立 review）

- Apporo 專用 account-ID/session transport：每個寫入前 capability、固定 brand、register echo/version；heal 最多一次且換 SID 重做 cap。WOOW 保留原 host/session 與 switch unregister。
- Apporo manual auth 只採當次 isolated response 的有效 SID；缺 SID 零帳號／credential／active／jar 寫入。有 SID 但 push 缺 cap 不改登入成功。push heal 不發布 UI jar；manual active/cookie commit 使用同一 selection-attempt fence。
- Settings 顯示目前 account-ID 的記憶體診斷（三語），ACK 不保證送達。captured unregister 仍可 best-effort 清理；移除帳號後不能重新認證或借別人 SID。
- 本輪只准 stdlib 離線檢查；新增 JVM 未跑，兩 flavor build/unit 由父代理序列驗證。37 offline 的兩個 stage2 freeze 測試已按核准的精確 5 個 session/push seams、10 個新增 strings keys 更新，其他安全檔／legacy string 語意保留。歷史 438 PASS/3 SKIP 不是本輪結果。
- live 拒絕閘門仍保留；不代表部署、送達、合併或發布就緒。詳見 `docs/plans/2026-09-25-apporo-push-client.md`、`docs/verification-report/apporo-phase3/validation.md`。

## 2026-09-24 階段 2 品牌候選補充（未完整驗證）

- 新工作分支由 `73ad528` 起；flavors 為 `woowtech` / `apporo`，namespace 仍為 `io.woowtech.odoo`。下方歷史的單一 variant/CI 敘述不適用本候選。
- WOOW 1.4.2/23 不變；Apporo 1.0/1、`com.apporo.odoo` / `.debug`；scheme `apporoodoo` / `apporoodoo-dev`。名稱三語均 `Apporo platform`。
- 真正任務改為 `assembleWoowtechDebug` / `testWoowtechDebugUnitTest` 與 `assembleApporoDebug` / `testApporoDebugUnitTest`；正式任務同理替換 Debug→Release。本輪由**主代理因磁碟僅約 5.4GiB 暫緩 Gradle 重型建置**（不是擁有者明文禁止所有 build），仍不得執行 Gradle；裝置/網路操作另屬禁止範圍。完整 gate 仍 BLOCKED，不代表免驗。
- Firebase plugin 無條件接線；每 variant `app/src/<variant>/google-services.json`。只有 WOOW 可保留既有 `app/google-services.json`。Apporo 無配置不能出包；正式缺 `APPORO_RELEASE_*` 簽章亦拒絕。client 檔不得印 API key。
- Apporo signing 只讀 `apporo-keystore.properties` 或同名 Gradle/env properties，禁止 WOOW key 路徑。真 key 尚未建立；正式 signer 指紋仍待另批驗證。
- 離線腳本需 Python 3（stdlib），`python3 -B -m unittest discover -s scripts/tests -p 'test_brand*.py' -v`；CI 僅兩 flavor debug build/unit/internal artifact，不自動 publish。
- live scripts 必須顯式 `APP_VARIANT`，package 與 Firebase project 不得跨品牌覆寫；device/Odoo 寫入各須授權旗標。Apporo live tests 仍拒絕：階段 3 品牌推播契約尚未實作。Activity 類別保持 WOOW namespace。
- 詳見 `docs/plans/2026-09-24-apporo-Implementation_Plan.md` / `2026-09-24-apporo-Test_Plan.md`。只交未 staged 候選 diff，不 commit 產品碼、不宣稱可發布。

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

### Repository-Event Symmetry (CRITICAL RULE)

When a repository wires a side-effect to event X (e.g., `logout → unregisterToken`),
you MUST verify the symmetric side-effect for the **inverse** event Y is
reachable. Common inverse pairs:

| Forward event | Inverse event |
|---|---|
| `login` → register | `logout` → unregister |
| `addAccount` → register-with-server | `removeAccount` → unregister-with-server |
| `acquireResource` → setup | `releaseResource` → cleanup |
| `subscribe` → wire callback | `unsubscribe` → tear down |

**The bug this rule prevents** (commit `482a7bf`, 2026-04-16): a 32-file
mega-commit added `logout → unregisterToken` (C3) and `WoowFcmService.onNewToken
→ register` (C1) but **never wired `login → register`**. After `pm clear` +
fresh login, the FCM token never reached Odoo because `onNewToken` had fired
before login (zero accounts → silent no-op) and login had no register hook.
Detected on 2026-04-28 by E2E-12b. Reference:
`docs/2026-04-27-e2e-suite-results.md`.

**How to apply** before declaring a feature done:
1. List the events the repository handles. Pair them up by inverse.
2. For each pair, write a unit test asserting BOTH directions.
3. If the forward event has no test asserting the side-effect, the work
   is not done — even if the inverse is implemented.
4. **Empty-collection paranoia**: if the side-effect iterates a collection
   (e.g., `accounts.forEach { register }`), add a test for the empty case
   that asserts the operation does NOT silently consume a not-yet-replayed
   value. Either log a warning, set a "pending" flag for replay later, or
   raise — never `Result.success(Unit)` with empty input.

---

### Verification Checklists Become Automated Tests (MANDATORY)

Every line in a commit's "Requires on-device verification" list MUST
correspond to a V-ID in `scripts/verify-on-device.py` or an E2E-ID in
`scripts/e2e-production-test.py`/`e2e_15_clockin_full.py`. The traceability
matrix in `docs/plans/2026-03-23-test-plan.md` is the source of truth —
every checklist line maps to a test ID.

**Rationale**: Manual checklists rot. Commit `482a7bf` listed "FCM token
registers after login (Firebase Console installations)" as item #7 but as
a manual Firebase Console check, not an automated test. The check was
either skipped or run in a state that masked the bug. The bug shipped.
A `verify-on-device.py` test that queries Odoo's `woow.fcm.device` count
after login would have caught it at PR time.

**How to apply**:
- Before merging a feature commit, every item in the "verification" list
  has a corresponding test ID in §3 / §3b of the test plan
- If an item can't be automated (genuinely device-only, like real
  Keystore tests), tag it `@OnDevice` and document why in the test plan

---

### Mega-Commit Cap (CRITICAL RULE)

Commits touching **>15 files** OR **>1000 LOC of behavior change** must be
split into focused commits along feature/responsibility lines.

**Rationale**: Commit `482a7bf` was 32 files, +2,251 / −182 LOC, and
bundled biometric, lifecycle, FCM, deep-link, color picker, language,
reduce motion. Reviewers can't spot a missing one-line wiring in that
volume. The missing FCM register-on-login wiring was invisible in the
diff noise.

**How to apply**:
- One commit per "responsibility area" (e.g., FCM lifecycle is one commit;
  biometric crypto is another)
- Tests + production code stay in the same commit (so the test asserts
  the new behavior in the same atomic change)
- Refactor commits stay separate from feature commits
- If you find yourself writing more than 3 paragraphs in a single commit
  body, split it

---

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

### Inspect Before Asserting (uiautomator2 rule)

**When developing or fixing a uiautomator2 check, ALWAYS dump the live UI
hierarchy first to see what selector matches the element.** Never guess what
attribute (`text=` vs `content-desc=` vs `resource-id=`) a Compose element
exposes — Compose semantics are not consistent with classic Android Views.

Why: Compose nodes often expose only `content-desc` (from
`Modifier.semantics { contentDescription = ... }`), or expose `text` only on
the inner `BasicText` and not on the surrounding `Button`. Guessing wastes a
30+ second setup cycle (PBKDF2, login form, navigation) per attempt.

Required workflow:

```python
# 1. Drive the phone into the target state ONCE
ensure_logged_in()
apply_test_hook(test_pin="1234", app_lock=True, biometric=False)
restart_to_trigger_gate()
fall_through_to_pin()

# 2. Dump the hierarchy and PRINT IT
import re
hier = d.dump_hierarchy()
print("=== Visible interactive elements ===")
for m in re.finditer(r'<node[^>]*>', hier):
    el = m.group()
    text = re.search(r'text="([^"]+)"', el)
    desc = re.search(r'content-desc="([^"]+)"', el)
    rid  = re.search(r'resource-id="([^"]+)"', el)
    bnds = re.search(r'bounds="([^"]+)"', el)
    if (text and text.group(1).strip()) or (desc and desc.group(1).strip()):
        print(f"  text='{text.group(1) if text else ''}' "
              f"desc='{desc.group(1) if desc else ''}' "
              f"id='{rid.group(1) if rid else ''}' "
              f"{bnds.group(1) if bnds else ''}")

# 3. Now you know the right selector — write the assertion
```

Save the dump to `/tmp/hierarchy_<screen>.xml` for later reference. Reuse the
dump across iterations if you can't reach the same state quickly.

### Verification Rules

**Every commit must pass:**
1. `./gradlew assembleDebug` — build succeeds
2. `./gradlew testDebugUnitTest` — 0 failures
3. `python3 scripts/verify-on-device.py` — all checks pass (if device connected)

### Source Code Change → ALL Tests Must Pass (CRITICAL RULE)

**Whenever production source code (`app/src/main/...`) changes, every suite
listed in §"Test Script Catalog" must be re-run and ALL must report 0
failures BEFORE declaring the work done.** This is non-negotiable. The
rule applies even when:

- The change is "obviously safe" (one-liner, comment-only, formatting).
- An unrelated test was already known to fail before the change.
- Time pressure makes a full re-run feel costly.

**Why this rule exists**: in this project we have already shipped a
silent feature regression (commit `482a7bf`, FCM register-on-login
missing) because verification was checklist-based, not full-suite-based.
On 2026-04-28 we shipped a fix for that bug and were tempted to declare
"done" with `e2e-production-test.py` still red, on the reasoning that
the failing tests were "script bugs not code regressions." A red test
is a red test — until the suite is green, you have not proven the fix.

**How to apply**:

1. Before declaring done, run **all four** in order (per §"Test Script
   Catalog"):
   ```
   ./gradlew testDebugUnitTest        # unit
   python3 scripts/verify-on-device.py        # device V-tests
   python3 scripts/e2e_15_clockin_full.py     # GPS clock-in
   python3 scripts/e2e-production-test.py     # 8 boss requirements
   ```
2. Show a status table with PASS / FAIL counts for each suite.
3. If ANY suite has failures, you have two valid responses:
    - Fix the failure (in production code OR in the test script — both
      are valid; document which).
    - Get explicit user agreement to merge with the failure(s)
      documented as a follow-up ticket.
4. Do NOT report "ready to merge" while any suite is red, even if the
   failing tests are categorized as script-level. Categorize after
   fixing, not instead of fixing.

**A "test script bug" is still a real bug.** If a test is supposed to
verify a behavior and it's giving a false negative, it has the same
practical impact as a missing test: the behavior is unverified. Fix the
test or remove it; do not leave it red.

### Test Script Catalog (MANDATORY — list before declaring "no regression")

When a user asks Claude to verify a branch, run E2E, or claim "no regression",
Claude MUST surface this catalog and prompt the user to run all relevant
scripts. Do NOT declare a branch green based on a partial run or unit tests
alone — feature regressions live in device behavior.

| # | Script | Coverage | Approx. time | Prereqs |
|---|---|---|---|---|
| 1 | `scripts/verify-on-device.py` | 41 defined checks (V01–V26): logging, biometric, FCM, deep-link, color picker, zh-CN, cache clearing, security hardening (V21: FLAG_SECURE must be **absent** — screenshots are allowed by design / PIN keypad / ProcessLifecycle), location-permission infra. Summary reports defined / executed / passed / failed / skipped / not reached; exit 0 only if nothing failed, something executed and nothing was left unreached | ~10 min | device + USB/wireless debugging (`ANDROID_SERIAL` if several), app installed, `ODOO_URL`/`ODOO_DB`/`ODOO_USER`/`ODOO_PASS` via env or gitignored `.env.test`, `pm grant POST_NOTIFICATIONS`, `FIREBASE_SA_FILE` for V20 (counted as skipped if absent), Odoo reachable |
| 2 | `scripts/e2e_15_clockin_full.py` | E2E-15: WebView clock-in via OWL with GPS verification end-to-end (JSON-RPC snapshot → JS-injected clock action → JSON-RPC verify) | ~2 min | location pre-granted (`pm grant ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`), `hr_attendance` installed on Odoo |
| 3 | `scripts/e2e-production-test.py` | 8 boss requirements: FCM push (chatter / DM / activity), biometric on bg→fg, color picker theme change, zh-CN switch, cache clear preserves login, deep link from notification | ~15 min | `firebase-service-account.json`, Odoo with users + chatter |
| 4 | `scripts/e2e-verification-report.py` | Reporter — consumes results from above and generates `docs/verification-report/verification-report.md` with screenshots | — | run after the others |

**Required run order** for full sign-off:

```bash
./gradlew assembleDebug && ./gradlew testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Confirm app is logged in to the LIVE tunnel URL (see Test Config below).
python3 scripts/verify-on-device.py        # ~10 min
python3 scripts/e2e_15_clockin_full.py     # ~2 min
python3 scripts/e2e-production-test.py     # ~15 min
python3 scripts/e2e-verification-report.py # generates report
```

**Hard rule on text input — always use ADBKeyboard before typing into login/PIN fields:**

The default IME (Gboard, SwiftKey, MIUI keyboard) silently autocorrects test
input — `"trycloudflare"` becomes `"try cloudflare"`, `"admin"` becomes `"Admin"`,
PIN digits get auto-completed. This causes "tunnel unreachable" or "wrong
password" failures that look like infrastructure problems but are IME bugs.

Before any `send_keys()` / `set_text()` into an `EditText`, switch to
ADBKeyboard (bundled with uiautomator2 — no separate install needed):

```python
from test_config import enable_adb_keyboard, restore_ime

prev_ime = enable_adb_keyboard()
try:
    edits[0].send_keys(ODOO_HOST)   # byte-perfect
    edits[1].send_keys(ODOO_PASS)
finally:
    restore_ime(prev_ime)            # leave the user's IME as-found
```

`scripts/verify-on-device.py:perform_login` already does this — copy that
pattern for any new login/PIN entry helper.

**Hard rule on test config (single source of truth):** these scripts hardcode
the dev tunnel URL in module-level constants (e.g.
`TEST_SERVER_URL`, `e2e_15_clockin_full.py` JSON-RPC URLs). When the
cloudflared tunnel rotates (every ~24h or after a Mac restart), update those
constants in lockstep — a stale URL causes cascade failures that look like
regressions but are not. **Long-term fix tracked separately:** read tunnel
URL from a single `scripts/test_config.py` (mirroring the iOS
`SharedTestConfig` pattern) and have all scripts import it. Until that lands,
update the constants together when the tunnel changes.

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
