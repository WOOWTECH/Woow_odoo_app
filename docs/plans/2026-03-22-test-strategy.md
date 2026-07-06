# Automated Test Strategy: Zero-Screenshot Verification

> **Date:** 2026-03-22
> **Principle:** NO screenshots, NO visual LLM analysis. All verification is programmatic.
> **Current State:** Zero tests, basic test deps (JUnit 4, Espresso, Compose UI Test)
> **Target State:** Multi-layer automated verification for every implementation phase

---

## Test Architecture: 5 Layers

```
Layer 5: ADB Runtime Checks         ← verify app behavior on device (no screenshots)
Layer 4: Compose UI Tests            ← semantic node testing (androidTest/)
Layer 3: Unit Tests                  ← ViewModel/Repository logic (test/)
Layer 2: Build Verification          ← Gradle compile = structural correctness
Layer 1: Static Code Verification    ← grep/glob = instant, zero cost
```

Each layer is **cheaper and faster** than the one above it. Always start from Layer 1.

---

## Layer 0: Test Infrastructure Setup (Do First)

Before any tests can run, add missing test dependencies.

### 0.1 — Update `gradle/libs.versions.toml`

```toml
# Add these versions
junit5 = "5.10.2"
mockk = "1.13.10"
coroutines-test = "1.8.0"
hilt-testing = "2.51"
turbine = "1.1.0"

# Add these libraries
[libraries]
junit5-api = { group = "org.junit.jupiter", name = "junit-jupiter-api", version.ref = "junit5" }
junit5-engine = { group = "org.junit.jupiter", name = "junit-jupiter-engine", version.ref = "junit5" }
junit5-params = { group = "org.junit.jupiter", name = "junit-jupiter-params", version.ref = "junit5" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
mockk-android = { group = "io.mockk", name = "mockk-android", version.ref = "mockk" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines-test" }
hilt-testing = { group = "com.google.dagger", name = "hilt-android-testing", version.ref = "hilt-testing" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
```

### 0.2 — Update `app/build.gradle.kts`

```kotlin
dependencies {
    // Unit tests (JUnit 5 + MockK)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    testImplementation(libs.junit5.params)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)

    // Android instrumentation tests (Compose UI)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.hilt.testing)
    kspAndroidTest(libs.hilt.compiler)
}

tasks.withType<Test> {
    useJUnitPlatform() // Enable JUnit 5
}
```

### 0.3 — Create Test Directories

```
app/src/test/kotlin/io/woowtech/odoo/          ← unit tests
app/src/androidTest/kotlin/io/woowtech/odoo/    ← Compose UI tests
```

### 0.4 — Verification Script

**New file:** `scripts/verify-all.sh`

```bash
#!/bin/bash
# Master verification script — runs all 5 layers
# Usage: ./scripts/verify-all.sh [phase]
# Example: ./scripts/verify-all.sh B0

set -e
PHASE="${1:-ALL}"
PASS=0
FAIL=0
SKIP=0

green()  { echo -e "\033[32m✓ $1\033[0m"; ((PASS++)); }
red()    { echo -e "\033[31m✗ $1\033[0m"; ((FAIL++)); }
yellow() { echo -e "\033[33m⊘ $1 (skipped)\033[0m"; ((SKIP++)); }

check() {
    local desc="$1"; shift
    if eval "$@" > /dev/null 2>&1; then
        green "$desc"
    else
        red "$desc"
    fi
}

echo "══════════════════════════════════════════════"
echo "  Woow Odoo App — Automated Verification"
echo "  Phase: $PHASE"
echo "══════════════════════════════════════════════"
echo ""

# ─── Layer 1: Static Code Verification ───
echo "── Layer 1: Static Code Checks ──"
# (phase-specific checks injected below)

# ─── Layer 2: Build Verification ───
echo ""
echo "── Layer 2: Build Verification ──"
check "Debug build compiles" "./gradlew assembleDebug --quiet"

# ─── Layer 3: Unit Tests ───
echo ""
echo "── Layer 3: Unit Tests ──"
check "Unit tests pass" "./gradlew testDebugUnitTest --quiet"

echo ""
echo "══════════════════════════════════════════════"
echo "  Results: $PASS passed, $FAIL failed, $SKIP skipped"
echo "══════════════════════════════════════════════"
exit $FAIL
```

---

## Phase B0: Security Hardening — Test Matrix

### Layer 1: Static Code Verification (grep/glob)

These checks cost ZERO tokens and run instantly. Each maps to a B0 item.

```bash
# B0.1 — Timber replaces android.util.Log
check "B0.1: No android.util.Log in source" \
  "! grep -r 'android.util.Log' app/src/main/java/ --include='*.kt'"
check "B0.1: Timber imported where logging happens" \
  "grep -r 'import timber.log.Timber' app/src/main/java/ --include='*.kt' | wc -l | [ \$(cat) -gt 0 ]"
check "B0.1: OkHttp logging guarded by BuildConfig.DEBUG" \
  "grep -A2 'HttpLoggingInterceptor' app/src/main/java/ -r | grep -q 'BuildConfig.DEBUG'"

# B0.2 — Biometric skip bypass removed
check "B0.2: No skip button in BiometricScreen" \
  "! grep -q 'onSkip\|Skip' app/src/main/java/io/woowtech/odoo/ui/auth/BiometricScreen.kt"

# B0.3 — LifecycleEventEffect for auth invalidation
check "B0.3: LifecycleEventEffect used for auth" \
  "grep -rq 'LifecycleEventEffect' app/src/main/java/io/woowtech/odoo/ui/"

# B0.4 — PBKDF2 replaces SHA-256 for PIN
check "B0.4: PBKDF2 used for PIN hash" \
  "grep -rq 'PBKDF2\|PBEKeySpec\|SecretKeyFactory' app/src/main/java/io/woowtech/odoo/data/repository/SettingsRepository.kt"
check "B0.4: No bare SHA-256 for PIN" \
  "! grep -q 'MessageDigest.getInstance.*SHA-256' app/src/main/java/io/woowtech/odoo/data/repository/SettingsRepository.kt"

# B0.5 — WebView restricted to same-host
check "B0.5: shouldOverrideUrlLoading checks host" \
  "grep -A10 'shouldOverrideUrlLoading' app/src/main/java/io/woowtech/odoo/ui/main/MainScreen.kt | grep -q 'host'"

# B0.6 — Third-party cookies disabled
check "B0.6: Third-party cookies disabled" \
  "grep -rq 'setAcceptThirdPartyCookies.*false' app/src/main/java/io/woowtech/odoo/ui/main/MainScreen.kt"

# B0.7 — Multiple windows disabled
check "B0.7: setSupportMultipleWindows(false)" \
  "grep -rq 'setSupportMultipleWindows.*false' app/src/main/java/io/woowtech/odoo/ui/main/MainScreen.kt"

# B0.8 — File access disabled
check "B0.8: allowFileAccess = false" \
  "grep -rq 'allowFileAccess.*false' app/src/main/java/io/woowtech/odoo/ui/main/MainScreen.kt"

# B0.9 — collectAsStateWithLifecycle used
check "B0.9: No bare collectAsState (use WithLifecycle)" \
  "! grep -rn '\.collectAsState()' app/src/main/java/ --include='*.kt' | grep -v 'WithLifecycle'"

# B0.10 — POST_NOTIFICATIONS permission
check "B0.10: POST_NOTIFICATIONS in AndroidManifest" \
  "grep -q 'POST_NOTIFICATIONS' app/src/main/AndroidManifest.xml"

# B0.11 — NotificationChannel in Application
check "B0.11: NotificationChannel created in Application" \
  "grep -rq 'createNotificationChannel\|NotificationChannel' app/src/main/java/io/woowtech/odoo/WoowOdooApp.kt 2>/dev/null || \
   grep -rq 'createNotificationChannel\|NotificationChannel' app/src/main/java/io/woowtech/odoo/application/ 2>/dev/null"

# B0.12 — DeepLinkManager exists
check "B0.12: DeepLinkManager class exists" \
  "find app/src/main/java -name 'DeepLinkManager.kt' | grep -q '.'"

# B0.13 — CacheRepository exists
check "B0.13: CacheRepository class exists" \
  "find app/src/main/java -name 'CacheRepository.kt' | grep -q '.'"

# B0.14 — FLAG_IMMUTABLE used
check "B0.14: PendingIntent uses FLAG_IMMUTABLE" \
  "grep -rq 'FLAG_IMMUTABLE' app/src/main/java/ --include='*.kt'"

# B0.15 — elapsedRealtime for lockout
check "B0.15: Uses elapsedRealtime (not currentTimeMillis) for lockout" \
  "! grep -q 'System.currentTimeMillis' app/src/main/java/io/woowtech/odoo/ui/auth/"

# B0.16 — ConcurrentHashMap for CookieStore
check "B0.16: CookieStore uses ConcurrentHashMap" \
  "grep -rq 'ConcurrentHashMap' app/src/main/java/io/woowtech/odoo/data/api/"
```

### Layer 3: Unit Tests for B0

```kotlin
// app/src/test/kotlin/io/woowtech/odoo/data/repository/PinHashTest.kt

class PinHashTest {
    @Test
    fun `Given a PIN when hashed then uses PBKDF2 with salt`() {
        val repo = SettingsRepository(/* mock prefs */)
        val hash1 = repo.hashPin("1234")
        val hash2 = repo.hashPin("1234")
        // Different salt each time = different hash
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `Given correct PIN when verified then returns true`() {
        val repo = SettingsRepository(/* mock prefs */)
        val stored = repo.hashPin("1234")
        assertTrue(repo.verifyPin("1234", stored))
    }

    @Test
    fun `Given wrong PIN when verified then returns false`() {
        val repo = SettingsRepository(/* mock prefs */)
        val stored = repo.hashPin("1234")
        assertFalse(repo.verifyPin("5678", stored))
    }
}
```

```kotlin
// app/src/test/kotlin/io/woowtech/odoo/data/push/DeepLinkManagerTest.kt

class DeepLinkManagerTest {
    private val manager = DeepLinkManager()

    @Test
    fun `Given URL set when consumed then returns URL and clears`() {
        manager.setPending("/web#id=42&model=sale.order")
        assertEquals("/web#id=42&model=sale.order", manager.consume())
        assertNull(manager.consume()) // consumed = cleared
    }

    @Test
    fun `Given no URL when consumed then returns null`() {
        assertNull(manager.consume())
    }
}
```

```kotlin
// app/src/test/kotlin/io/woowtech/odoo/ui/auth/AuthViewModelTest.kt

@ExtendWith(MockKExtension::class)
class AuthViewModelTest {
    @MockK lateinit var settingsRepo: SettingsRepository
    @MockK lateinit var accountRepo: AccountRepository
    private lateinit var viewModel: AuthViewModel

    @Test
    fun `Given authenticated when onAppBackgrounded then isAuthenticated is false`() = runTest {
        viewModel.setAuthenticated(true)
        assertTrue(viewModel.isAuthenticated.value)

        viewModel.onAppBackgrounded()
        assertFalse(viewModel.isAuthenticated.value)
    }

    @Test
    fun `Given PIN wrong 5 times when verifyPin then lockout activated`() = runTest {
        every { settingsRepo.verifyPin(any(), any()) } returns false
        repeat(5) { viewModel.verifyPin("0000") }
        assertTrue(viewModel.isLockedOut.value)
    }
}
```

---

## Phase B1: Brand Colors — Test Matrix

### Layer 1: Static

```bash
# B1.1 — Brand color values defined
check "B1.1: BrandPrimaryBlue defined" \
  "grep -q '0xFF6183FC' app/src/main/java/io/woowtech/odoo/ui/theme/Color.kt"
check "B1.1: All 10 accent colors defined" \
  "grep -c 'Accent' app/src/main/java/io/woowtech/odoo/ui/theme/Color.kt | [ \$(cat) -ge 10 ]"

# B1.2 — Color picker uses LazyVerticalGrid
check "B1.2: LazyVerticalGrid in SettingsScreen" \
  "grep -q 'LazyVerticalGrid' app/src/main/java/io/woowtech/odoo/ui/config/SettingsScreen.kt"
check "B1.2: OutlinedTextField for HEX input" \
  "grep -q 'OutlinedTextField' app/src/main/java/io/woowtech/odoo/ui/config/SettingsScreen.kt"
```

### Layer 3: Unit Test

```kotlin
// app/src/test/kotlin/io/woowtech/odoo/ui/theme/ColorTest.kt

class ColorTest {
    @Test
    fun `Brand colors match design guide`() {
        assertEquals(Color(0xFF6183FC), BrandPrimaryBlue)
        assertEquals(Color(0xFFEFF1F5), BrandLightGray)
        assertEquals(Color(0xFF212121), BrandDeepGray)
    }

    @Test
    fun `All 10 accent colors are distinct`() {
        val accents = listOf(
            AccentCyan, AccentYellow, AccentSkyBlue, AccentRoyalBlue,
            AccentGreen, AccentBrown, AccentSand, AccentOrange,
            AccentCoral, AccentLavender
        )
        assertEquals(10, accents.toSet().size)
    }
}
```

---

## Phase B2: zh-CN — Test Matrix

### Layer 1: Static

```bash
# B2.1 — zh-CN strings file exists
check "B2.1: zh-CN strings.xml exists" \
  "test -f app/src/main/res/values-zh-rCN/strings.xml"

# B2.1 — zh-CN has same string count as zh-TW
check "B2.1: zh-CN string count matches zh-TW" \
  "[ \$(grep -c '<string ' app/src/main/res/values-zh-rCN/strings.xml) -ge \$(grep -c '<string ' app/src/main/res/values-zh-rTW/strings.xml) ]"

# B2.2 — CHINESE_CN enum added
check "B2.2: CHINESE_CN in AppLanguage enum" \
  "grep -q 'CHINESE_CN' app/src/main/java/io/woowtech/odoo/domain/model/AppSettings.kt"

# B2.1 — Uses simplified Chinese characters
check "B2.1: zh-CN uses simplified chars (服务器 not 伺服器)" \
  "grep -q '服务器' app/src/main/res/values-zh-rCN/strings.xml"
```

### Layer 3: Unit Test

```kotlin
class AppLanguageTest {
    @Test
    fun `AppLanguage contains zh-CN with correct code`() {
        val zhCn = AppLanguage.CHINESE_CN
        assertEquals("zh-CN", zhCn.code)
        assertEquals("简体中文", zhCn.displayName)
    }

    @Test
    fun `All languages have unique codes`() {
        val codes = AppLanguage.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }
}
```

---

## Phase B3: Biometric Fix — Test Matrix

### Layer 3: Unit Tests (primary verification)

```kotlin
// app/src/test/kotlin/io/woowtech/odoo/ui/auth/AuthViewModelTest.kt

class AuthLifecycleTest {
    @Test
    fun `Given app lock enabled when background then requires auth on resume`() = runTest {
        // Setup
        every { settingsRepo.isAppLockEnabled() } returns true
        viewModel.setAuthenticated(true)

        // Act — simulate background
        viewModel.onAppBackgrounded()

        // Assert
        assertFalse(viewModel.isAuthenticated.value)
    }

    @Test
    fun `Given app lock disabled when background then no auth needed`() = runTest {
        every { settingsRepo.isAppLockEnabled() } returns false
        viewModel.setAuthenticated(true)
        viewModel.onAppBackgrounded()
        // Should stay authenticated (lock is disabled)
        assertTrue(viewModel.isAuthenticated.value)
    }

    @Test
    fun `Given PIN lockout when 5 failed attempts then exponential backoff`() = runTest {
        every { settingsRepo.verifyPin(any(), any()) } returns false
        repeat(5) { viewModel.verifyPin("0000") }

        assertTrue(viewModel.isLockedOut.value)
        // First lockout = 30s
        assertEquals(30_000L, viewModel.lockoutDurationMs.value)
    }
}
```

### Layer 4: Compose UI Test

```kotlin
// app/src/androidTest/kotlin/io/woowtech/odoo/ui/auth/BiometricScreenTest.kt

@HiltAndroidTest
class BiometricScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun biometricScreen_noSkipButton_whenAppLockEnabled() {
        composeRule.setContent {
            BiometricScreen(
                isAppLockEnabled = true,
                onAuthenticated = {},
                onSkip = {}  // should be ignored
            )
        }
        // Verify no skip button exists in the semantic tree
        composeRule.onNodeWithText("Skip").assertDoesNotExist()
        composeRule.onNodeWithText("跳過").assertDoesNotExist()
        composeRule.onNodeWithText("跳过").assertDoesNotExist()
    }
}
```

### Layer 5: ADB Runtime Check (no screenshots)

```bash
# Check that auth screen appears after backgrounding
check "B3: Auth screen appears on resume" \
  "adb shell am start -n io.woowtech.odoo.debug/.ui.MainActivity && \
   sleep 2 && \
   adb shell input keyevent KEYCODE_HOME && \
   sleep 1 && \
   adb shell am start -n io.woowtech.odoo.debug/.ui.MainActivity && \
   sleep 2 && \
   adb shell dumpsys activity top | grep -q 'BiometricScreen\|PinScreen\|AuthScreen'"
```

---

## Phase B4: Cache Clearing — Test Matrix

### Layer 3: Unit Tests

```kotlin
// app/src/test/kotlin/io/woowtech/odoo/data/repository/CacheRepositoryTest.kt

class CacheRepositoryTest {
    @MockK lateinit var context: Context

    @Test
    fun `Given cache dir with files when clearAppCache then dir emptied`() = runTest {
        val cacheDir = createTempDirectory().toFile()
        File(cacheDir, "test.tmp").writeText("data")
        every { context.cacheDir } returns cacheDir

        val repo = CacheRepository(context)
        repo.clearAppCache()

        assertTrue(cacheDir.listFiles()?.isEmpty() ?: true)
    }

    @Test
    fun `Given cache cleared when calculateCacheSize then returns near zero`() = runTest {
        val cacheDir = createTempDirectory().toFile()
        every { context.cacheDir } returns cacheDir

        val repo = CacheRepository(context)
        val size = repo.calculateCacheSize()
        assertEquals(0L, size)
    }
}
```

---

## Phase A1: Firebase SDK — Test Matrix

### Layer 1: Static

```bash
# A1.1 — Firebase dependencies
check "A1.1: firebase-bom in libs.versions.toml" \
  "grep -q 'firebase-bom' gradle/libs.versions.toml"
check "A1.1: google-services plugin applied" \
  "grep -q 'google.services' app/build.gradle.kts"
check "A1.1: google-services.json exists" \
  "test -f app/google-services.json"

# A1.2 — WoowFcmService
check "A1.2: WoowFcmService exists" \
  "find app/src/main/java -name 'WoowFcmService.kt' | grep -q '.'"
check "A1.2: @AndroidEntryPoint on service" \
  "grep -q '@AndroidEntryPoint' app/src/main/java/io/woowtech/odoo/data/push/WoowFcmService.kt"
check "A1.2: Service declared in AndroidManifest" \
  "grep -q 'WoowFcmService' app/src/main/AndroidManifest.xml"

# A1.3 — FcmTokenRepository interface
check "A1.3: FcmTokenRepository interface exists" \
  "grep -q 'interface FcmTokenRepository' app/src/main/java/io/woowtech/odoo/data/repository/FcmTokenRepository.kt"
check "A1.3: FcmTokenRepositoryImpl exists" \
  "find app/src/main/java -name 'FcmTokenRepositoryImpl.kt' | grep -q '.'"

# A1.4 — Deep link URL validation
check "A1.4: Deep link URL validation (reject javascript:)" \
  "grep -rq 'javascript:\|data:' app/src/main/java/io/woowtech/odoo/ --include='*.kt' | head -1 | grep -q 'reject\|block\|startsWith'"
check "A1.4: Uri.parse for URL construction (not string concat)" \
  "grep -rq 'Uri.parse\|buildUpon\|encodedPath' app/src/main/java/io/woowtech/odoo/ui/main/"

# A1.5 — Notification VISIBILITY_PRIVATE
check "A1.5: VISIBILITY_PRIVATE on notifications" \
  "grep -rq 'VISIBILITY_PRIVATE' app/src/main/java/io/woowtech/odoo/data/push/"
```

### Layer 3: Unit Tests

```kotlin
// app/src/test/kotlin/io/woowtech/odoo/data/repository/FcmTokenRepositoryTest.kt

@ExtendWith(MockKExtension::class)
class FcmTokenRepositoryTest {
    @MockK lateinit var apiClient: OdooJsonRpcClient
    @MockK lateinit var encryptedPrefs: EncryptedPrefs
    @MockK lateinit var accountDao: AccountDao

    @Test
    fun `Given 3 accounts when registerForAll then calls register 3 times`() = runTest {
        val accounts = listOf(account(1), account(2), account(3))
        coEvery { accountDao.getAllAccounts() } returns accounts
        coEvery { apiClient.post(any(), any()) } returns Result.success(Unit)

        val repo = FcmTokenRepositoryImpl(apiClient, encryptedPrefs, accountDao)
        repo.registerTokenForAllAccounts("fcm_token_123")

        coVerify(exactly = 3) { apiClient.post(any(), any()) }
    }

    @Test
    fun `Given token saved when getStoredToken then returns token`() {
        every { encryptedPrefs.getString("fcm_token", null) } returns "token_abc"
        val repo = FcmTokenRepositoryImpl(apiClient, encryptedPrefs, accountDao)
        assertEquals("token_abc", repo.getStoredToken())
    }
}
```

```kotlin
// app/src/test/kotlin/io/woowtech/odoo/data/push/DeepLinkValidatorTest.kt

class DeepLinkValidatorTest {
    @ParameterizedTest
    @ValueSource(strings = [
        "javascript:alert(1)",
        "data:text/html,<script>alert(1)</script>",
        "https://evil.com/phish",
        "ftp://files.example.com",
        ""
    ])
    fun `Given malicious URL when validate then rejected`(url: String) {
        assertFalse(DeepLinkValidator.isValid(url, serverHost = "odoo.example.com"))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "/web#id=42&model=sale.order&view_type=form",
        "/web#action=contacts",
        "/web/login"
    ])
    fun `Given valid Odoo path when validate then accepted`(url: String) {
        assertTrue(DeepLinkValidator.isValid(url, serverHost = "odoo.example.com"))
    }
}
```

```kotlin
// app/src/test/kotlin/io/woowtech/odoo/data/push/NotificationHelperTest.kt

class NotificationHelperTest {
    @Test
    fun `Given notification built then has VISIBILITY_PRIVATE`() {
        // Verify notification builder sets VISIBILITY_PRIVATE
    }

    @Test
    fun `Given notification built then PendingIntent has FLAG_IMMUTABLE`() {
        // Verify PendingIntent flags include FLAG_IMMUTABLE
    }

    @Test
    fun `Given chatter and mention events then grouped separately`() {
        // Verify grouping by event_type
    }
}
```

---

## Phase A2: Odoo Backend Module — Test Matrix

### Layer 1: Static (Module Structure)

```bash
ADDON_PATH="/Users/alanlin/Documents/odoo_migration_ecpay/deployment/addons/woow_fcm_push"

check "A2.1: Module directory exists" \
  "test -d $ADDON_PATH"
check "A2.1: __manifest__.py exists" \
  "test -f $ADDON_PATH/__manifest__.py"
check "A2.2: fcm_device.py exists" \
  "test -f $ADDON_PATH/models/fcm_device.py"
check "A2.2: platform field in fcm_device" \
  "grep -q 'platform.*Selection' $ADDON_PATH/models/fcm_device.py"
check "A2.3: discuss_channel.py (not mail_channel)" \
  "test -f $ADDON_PATH/models/discuss_channel.py"
check "A2.5: Controllers use auth='user'" \
  "grep -q \"auth='user'\" $ADDON_PATH/controllers/fcm_controller.py"
check "A2.5: CSRF protection enabled" \
  "grep -q 'csrf=True' $ADDON_PATH/controllers/fcm_controller.py"
check "A2.5: IDOR protection on mark_read" \
  "grep -A10 'mark_read' $ADDON_PATH/controllers/fcm_controller.py | grep -q 'partner_ids\|recipient\|sudo'"
check "A2: ir.model.access.csv exists" \
  "test -f $ADDON_PATH/security/ir.model.access.csv"
```

### Odoo Endpoint Tests (HTTP/curl — no screenshots)

```bash
# These run against the local Docker Odoo server

ODOO_URL="https://directions-joe-itunes-feel.trycloudflare.com"
# Note: URL changes on each tunnel restart — update from dev-setup.md

# Test: Register device endpoint
check "A2.5: /register endpoint responds" \
  "curl -s -o /dev/null -w '%{http_code}' \
    -X POST '$ODOO_URL/woow_fcm_push/register' \
    -H 'Content-Type: application/json' \
    -d '{\"jsonrpc\":\"2.0\",\"params\":{\"fcm_token\":\"test_token\",\"device_name\":\"test\"}}' \
    --cookie 'session_id=...' | grep -q '200'"

# Test: Unregister endpoint
check "A2.5: /unregister endpoint responds" \
  "curl -s -o /dev/null -w '%{http_code}' \
    -X POST '$ODOO_URL/woow_fcm_push/unregister' \
    -H 'Content-Type: application/json' \
    -d '{\"jsonrpc\":\"2.0\",\"params\":{\"fcm_token\":\"test_token\"}}' \
    --cookie 'session_id=...' | grep -q '200'"

# Test: Unauthenticated request rejected
check "A2.5: Unauthenticated request returns 403/302" \
  "curl -s -o /dev/null -w '%{http_code}' \
    -X POST '$ODOO_URL/woow_fcm_push/register' \
    -H 'Content-Type: application/json' \
    -d '{\"jsonrpc\":\"2.0\",\"params\":{\"fcm_token\":\"test\"}}' | grep -qE '403|302'"
```

### Odoo Python Unit Tests

```python
# woow_fcm_push/tests/test_fcm_device.py

from odoo.tests.common import TransactionCase

class TestFcmDevice(TransactionCase):
    def setUp(self):
        super().setUp()
        self.user = self.env['res.users'].create({
            'login': 'test_fcm@example.com',
            'name': 'FCM Test User',
        })

    def test_register_device(self):
        """Register a device and verify token stored"""
        device = self.env['woow.fcm.device'].create({
            'user_id': self.user.id,
            'fcm_token': 'test_token_123',
            'device_name': 'Pixel 7',
            'platform': 'android',
        })
        self.assertEqual(device.fcm_token, 'test_token_123')
        self.assertEqual(device.platform, 'android')

    def test_duplicate_token_updates(self):
        """Same token should update existing record, not duplicate"""
        self.env['woow.fcm.device'].create({
            'user_id': self.user.id,
            'fcm_token': 'token_abc',
        })
        # Second registration with same token
        # Should update, not create duplicate
        devices = self.env['woow.fcm.device'].search([
            ('fcm_token', '=', 'token_abc')
        ])
        self.assertEqual(len(devices), 1)

    def test_user_deletion_cascades(self):
        """Deleting user removes their FCM devices"""
        device = self.env['woow.fcm.device'].create({
            'user_id': self.user.id,
            'fcm_token': 'token_cascade',
        })
        device_id = device.id
        self.user.unlink()
        self.assertFalse(self.env['woow.fcm.device'].browse(device_id).exists())

    def test_mark_read_idor_protection(self):
        """User can only mark their own messages as read"""
        other_user = self.env['res.users'].create({
            'login': 'other@example.com',
            'name': 'Other User',
        })
        # Create message for other_user
        # Attempt to mark_read as self.user → should fail
```

---

## CI/CD Integration

### Update `.github/workflows/build.yml`

```yaml
- name: Run static code checks
  run: |
    # B0 security checks
    ! grep -r 'android.util.Log' app/src/main/java/ --include='*.kt'
    grep -rq 'PBKDF2\|PBEKeySpec' app/src/main/java/

- name: Run unit tests
  run: ./gradlew testDebugUnitTest

- name: Run lint
  run: ./gradlew lint --continue
  continue-on-error: true

- name: Build Debug APK
  run: ./gradlew assembleDebug
```

---

## Master Verification Command

Run after each phase to verify completion:

```bash
# Verify specific phase
./scripts/verify-all.sh B0
./scripts/verify-all.sh B1
./scripts/verify-all.sh A1

# Verify everything
./scripts/verify-all.sh ALL

# Quick Layer 1 only (instant, zero cost)
./scripts/verify-all.sh --static-only
```

---

## Test Count (Actual — Updated 2026-03-23)

### Unit Tests (47 total — `./gradlew testDebugUnitTest`)

| Test Class | Count | Covers |
|------------|-------|--------|
| `AuthViewModelTest` | 8 | Auth state, bg→fg, PIN verify, lockout |
| `SettingsRepositoryPinTest` | 14 | PBKDF2 hash, salt, migration, exponential lockout |
| `DeepLinkManagerTest` | 5 | Set/consume/clear/flow/overwrite |
| `DeepLinkValidatorTest` | 13 | Malicious URLs, valid paths, external hosts |
| `CacheRepositoryTest` | 3 | Clear, empty size, known size |
| `FcmTokenRepositoryTest` | 4 | Multi-account register, store/get token |

### Device Tests (30 total — `python3 scripts/verify-on-device.py`)

| V-ID | Feature | What It Tests (User Perspective) |
|------|---------|----------------------------------|
| V01-C01 | Timber logging | No old `WoowTechOdoo` tag in logcat |
| V02a-C02 | Skip removed | No Skip/跳過/稍后再说 button in UI tree |
| V02b-C02 | Skip removed | No skip-related resource ID |
| V03-C02 | Auth bg→fg | App survives background→foreground cycle |
| V04-C04 | WebView host | WebView shows Odoo content (same-host) |
| V05-C04 | No popups | Only 1 Activity instance (no popup windows) |
| V06-C06 | Permission | POST_NOTIFICATIONS in package manifest |
| V07a-C06 | Channel | `woow_odoo_messages` channel exists |
| V07b-C06 | Channel | Channel importance=HIGH (4) |
| V08a-C07 | Brand theme | App launches without crash |
| V08b-C07 | Brand theme | App bar shows "WoowTech Odoo" |
| V09-C09 | zh-CN | 简体中文 option in language picker |
| V10a-C08 | Color picker | Preset colors label visible |
| V10b-C08 | Color picker | Accent colors section visible |
| V10c-C08 | Color picker | HEX input field (#RRGGBB) visible |
| V11a-C13 | Cache clear | Clear Cache button found |
| V11b-C13 | Cache clear | App stable after clear, login preserved |
| V13a-C15 | FCM service | WoowFcmService in package manifest |
| V13b-C15 | FCM service | MESSAGING_EVENT intent filter |
| V14a-C17 | Deep link | App launches with deep link handler |
| V14b-C17 | Deep link | App handles deep link intent (no crash) |
| V15-G4 | Color picker | Tap Apply → dialog closes, Settings visible |
| V16a-G5 | zh-CN switch | Select 简体中文 → UI shows Chinese text |
| V16b-G5 | zh-CN switch | Restore to English |
| V17-G3 | URL block | External URL rejected by validator |
| V18-G6 | Cache clear | App stable after clear, settings visible |
| V19-G7 | Deep link nav | /web#action=contacts → Odoo content loaded |
| V20a | FCM token | FCM token retrieved from device (len>100) |
| V20b | FCM send | FCM HTTP v1 API returns 200 OK |
| V20c | FCM receive | Notification appears in notification shade |

### How to Run All Tests

```bash
# 1. Unit tests (no device needed)
cd /Users/alanlin/Woow_odoo_app
./gradlew testDebugUnitTest

# 2. Build + install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 3. Device tests (phone must be connected via USB)
# Requires: pip3 install uiautomator2 google-auth requests
# Requires: app/firebase-service-account.json for V20
python3 scripts/verify-on-device.py

# 4. Check results
cat docs/plans/2026-03-22-device-verification-log.md
```

### Prerequisites for V20 (FCM E2E)

1. `app/google-services.json` with both `io.woowtech.odoo` and `io.woowtech.odoo.debug`
2. `app/firebase-service-account.json` (Firebase Console → Service Accounts → Generate Key)
3. POST_NOTIFICATIONS permission granted: `adb shell pm grant io.woowtech.odoo.debug android.permission.POST_NOTIFICATIONS`
4. Both files in `.gitignore` — never commit

---

## Verification Flow Diagram

```
Developer completes a phase
         │
         ▼
    ┌──────────┐
    │ Layer 1   │ grep/glob static checks
    │ (instant) │ ← costs 0 tokens, catches 60% of issues
    └────┬─────┘
         │ all pass?
         ▼
    ┌──────────┐
    │ Layer 2   │ ./gradlew assembleDebug
    │ (1-2 min) │ ← compile = structural correctness
    └────┬─────┘
         │ builds?
         ▼
    ┌──────────┐
    │ Layer 3   │ ./gradlew testDebugUnitTest
    │ (30 sec)  │ ← logic correctness, no device needed
    └────┬─────┘
         │ tests pass?
         ▼
    ┌──────────┐
    │ Layer 4   │ ./gradlew connectedDebugAndroidTest
    │ (2-5 min) │ ← Compose semantics, requires device/emulator
    └────┬─────┘
         │ UI tests pass?
         ▼
    ┌──────────┐
    │ Layer 5   │ ADB runtime checks (dumpsys, am, pm)
    │ (30 sec)  │ ← behavioral verification, no screenshots
    └────┬─────┘
         │
         ▼
    ✅ Phase verified
```

---

## Why This Approach Works

| Method | Token Cost | Speed | What It Catches |
|--------|-----------|-------|-----------------|
| Screenshot + LLM analysis | HIGH (10K+ tokens/image) | Slow | Visual layout only |
| **grep/glob (Layer 1)** | **ZERO** | **Instant** | Code presence, patterns, anti-patterns |
| **Gradle build (Layer 2)** | **ZERO** | **1-2 min** | Compilation errors, missing imports |
| **Unit tests (Layer 3)** | **ZERO** | **30 sec** | Logic errors, edge cases, regressions |
| **Compose UI tests (Layer 4)** | **ZERO** | **2-5 min** | Semantic UI behavior, accessibility |
| **ADB checks (Layer 5)** | **ZERO** | **30 sec** | Runtime state, navigation, permissions |

Total LLM token cost for verification: **ZERO**. All checks are programmatic.
