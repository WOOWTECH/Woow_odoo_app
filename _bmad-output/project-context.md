---
project_name: 'Woow Odoo Android App'
user_name: 'alanlin'
date: '2026-08-01'
sections_completed:
  ['technology_stack', 'language_rules', 'framework_rules', 'testing_rules', 'quality_rules', 'workflow_rules', 'anti_patterns']
status: 'complete'
optimized_for_llm: true
supersedes: 'untracked project-context.md generated 2026-05-04 (50 stale claims)'
verified_against: 'HEAD 0e57411 — every claim below was grepped in app/src, gradle/, .github/ on 2026-08-01'
---

# Project Context for AI Agents — Woow Odoo Android

_Critical rules and patterns for implementing code in this repo. Focused on unobvious details agents get wrong. Every factual claim here was verified against source at HEAD; where this file contradicts `CLAUDE.md` or `README.md`, **this file wins on facts** — but `CLAUDE.md`'s `CRITICAL RULE` blocks win on process._

---

## 0. Rule Precedence

1. **`CLAUDE.md` entries marked `CRITICAL RULE`** — process law, override any generic Kotlin/Android style advice. Summarized in §7.
2. **This file** — verified facts about the code as it actually is.
3. **`docs/architecture-overview.md`** (2026-07-29) — best narrative doc, but still repeats the stale "178 unit tests" figure.
4. `CLAUDE.md` / `README.md` factual claims — **known stale**, see §8.

---

## 1. Technology Stack & Versions

Source of truth: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `settings.gradle.kts`.

**Build/toolchain**
- Gradle **8.6**, AGP **8.3.0**, Kotlin **2.0.21**, KSP **2.0.21-1.0.28** (KSP only — **no kapt**)
- Java/Kotlin target **17**
- Single module: `include(":app")`. No multi-module, no `build-logic`, no convention plugins.
- `RepositoriesMode.FAIL_ON_PROJECT_REPOS` — declare repos in `settings.gradle.kts`, never in `app/build.gradle.kts`.

**App identity**
- `applicationId` `io.woowtech.odoo`; debug suffix `.debug` / `-debug`
- `compileSdk`/`targetSdk` **34**, `minSdk` **29**
- **`versionCode = 21`, `versionName = "1.4.1"`** (any doc saying 1.0.20/20 is stale)
- release: `isMinifyEnabled = true` + `isShrinkResources = true`. No `signingConfigs` block exists.

**Libraries**
- Compose BOM **2024.02.00**, Material3, Navigation Compose **2.7.7** (string routes — 2.8 type-safe API unavailable)
- Hilt **2.50** + hilt-navigation-compose **1.2.0**
- Room **2.6.1**; Lifecycle **2.7.0** incl. `lifecycle-process`
- OkHttp **4.12.0** + logging-interceptor; Gson **2.10.1**
- biometric **1.2.0-alpha05**; Firebase BOM **33.7.0** (messaging); google-services **4.4.2** applied **conditionally** via `if (file("google-services.json").exists())`
- Coroutines **1.8.0**, Timber **5.0.1**

**Encrypted prefs is NOT AndroidX** ⚠️
- `security-crypto = { group = "dev.spght", name = "encryptedprefs-core", version = "1.1.1" }` — a **community fork** replacing deprecated `androidx.security:security-crypto`.
- Imports are `dev.spght.encryptedprefs.EncryptedSharedPreferences` / `MasterKey` (`data/local/EncryptedPrefs.kt:5-6`).
- `androidx.security.crypto` has **0 occurrences** in `app/src/main`. Writing it will not compile.

**Declared but ZERO usages in `app/src/main`** (verified by grep) ⚠️
- `retrofit` + `converter-gson` — **0**. All HTTP is hand-rolled OkHttp + JSON-RPC 2.0 in `OdooJsonRpcClient`.
- `androidx.datastore:datastore-preferences` — **0**. All persistence is Room + `EncryptedPrefs` (theme color, theme mode, locale, reduce-motion, location-enabled all live in `EncryptedPrefs`).
- `coil-compose` — **0**.
- **Never say "follow the existing Retrofit/DataStore/Coil pattern" — there is no pattern to follow.** Adopting one is a new architectural decision requiring sign-off.

**Test stack**
- JUnit Jupiter **5.10.2** + `tasks.withType<Test> { useJUnitPlatform() }`
- MockK **1.13.10**, Turbine **1.1.0**, kotlinx-coroutines-test, okhttp mockwebserver
- **JUnit 4 (4.13.2) + Robolectric 4.11.1 + `junit-vintage-engine` are ALSO wired into the unit source set** (Robolectric is in use, not banned), plus `testOptions.unitTests.isIncludeAndroidResources = true`
- androidTest deps are declared but **`app/src/androidTest` does not exist**

---

## 2. Language Rules (Kotlin)

**Source-root asymmetry** ⚠️
- Production: `app/src/main/**java**/io/woowtech/odoo/`
- Tests: `app/src/test/**kotlin**/io/woowtech/odoo/`
- New files must land in the correct root. `java` for prod, `kotlin` for test — do not "unify" them.

**Logging**
- Timber only; `android.util.Log` appears nowhere in `app/src/main`.
- `Timber.plant(Timber.DebugTree())` is **debug-only**; there is **no release tree** (`WoowOdooApp.kt:30-32`). In release Timber is a silent no-op. No Crashlytics is wired.
- New code uses printf style: `Timber.d("FCM token registered for account %s", accountId)`. Older WebView code uses string templates — don't churn it.
- Secrets only behind `if (BuildConfig.DEBUG)`.

**Dispatchers — no qualifiers exist** ⚠️
- **There are ZERO Hilt dispatcher qualifiers in this repo.** No `@IoDispatcher`, no `@DefaultDispatcher`, no `@Qualifier` annotation of any kind. `AppModule` provides zero dispatchers.
- Dispatchers are written **directly at the call site**: `Dispatchers.IO` ×18, `Dispatchers.Default` ×7 in `app/src/main`. Match this — do not introduce a qualifier for one class.
- CPU/crypto → `Dispatchers.Default` (`SettingsRepository.setPin`/`verifyPin`, PBKDF2 600k — this exists because PBKDF2 on Main caused an ANR, commit `23c80bf`).
- Storage/network → `Dispatchers.IO`.
- Long-lived scopes constructed manually: `CoroutineScope(Dispatchers.IO + SupervisorJob())`, cancelled in `onDestroy`.
- `GlobalScope`: zero real usages.

**`runBlocking` — one sanctioned exception** ⚠️
- `SessionReauthenticator.kt:109` and `:205` use `runBlocking`, with a KDoc carve-out at `:56-58`: *"the sanctioned exception to the no-`runBlocking` rule"* — the OkHttp interceptor already runs on OkHttp's own dispatcher thread pool, never on Main.
- **Do not "fix" it** — a suspend rewrite breaks the synchronous `Interceptor`/`Authenticator` contract.
- **Do not copy it** anywhere outside an OkHttp `Interceptor`/`Authenticator`.

**Cancellation**
- `CancellationException` is re-thrown, never swallowed as failure: `catch (c: CancellationException) { throw c }` (`FcmTokenRepositoryImpl`).

**Concurrency primitives in use**
- `kotlinx.coroutines.sync.Mutex` + `withLock` for register/unregister serialization.
- `ConcurrentHashMap` for per-host state (cookie store, single-flight locks, circuit breakers).
- `AtomicBoolean` inside `remember` for the WebView self-heal guard.
- Helpers requiring a held lock are suffixed `…Locked` with KDoc saying "**Caller MUST hold [x]**".

**Time**
- Monotonic (PIN lockout) → `SystemClock.elapsedRealtime()`. Never `System.currentTimeMillis()` for durations.
- Wall clock is a **parameter with a default** so tests can drive it: `fun setPending(..., nowMillis: Long = System.currentTimeMillis())` (`DeepLinkManager`). There is **no injected `Clock`**.

**Types / API shape**
- **Ids are `String` UUIDs**: `OdooAccount.id: String = UUID.randomUUID().toString()`. Never write `accountId: Long`. No `value class` wrappers exist anywhere.
- `Result<T>` for fallible I/O; plain `Boolean` for allow/deny; `sealed class` for `AuthResult`.
- New closed hierarchies: `sealed interface` + `data object` (`AuthAction`). Older ones are `sealed class`. Match the file you're editing.
- Domain models are immutable `data class` + `copy()`.
- `fun interface` for single-method seams (`PermissionChecker`) so tests avoid Robolectric.

**JVM-testability idiom (non-obvious)** ⚠️
- Pure-logic classes deliberately use `java.net.URI`, **not** `android.net.Uri`, so they unit-test on plain JVM: `DeepLinkValidator`, `LocationPermissionGate`, and `MainScreen`'s host comparison. **Do not "modernize" these** — it breaks the test suite.

**KDoc / comments**
- Comments carry work-item ids (`L1`, `L2`, `L6/M2`, `C1`, `C3`, `WI-1`, `WI-3`, `MA-1`, `S2`, `B0.5`–`B0.8`) and sometimes commit SHAs (`482a7bf`, `23c80bf`, `caea05e`). **Preserve these markers** — they are the traceability mechanism. There is no `// WHY:` / `// ODOO:` prefix convention.

---

## 3. Framework / Architecture Rules

### Hilt / DI
- **Dominant pattern is `@Singleton class X @Inject constructor(...)` with NO AppModule entry** — `CacheRepository`, `EncryptionHelper`, `DeepLinkManager`, `NotificationHelper`, `ReloginSignal`, `LocationPermissionGate`, `ContextPermissionChecker`.
- `di/AppModule.kt` is the **only** `@Module` (a single `object`, 11 `@Provides`). No `@Binds`, no second module, no qualifier.
- ⚠️ **AppModule redundantly `@Provides` four classes that ALREADY have `@Inject constructor`:** `AccountRepository`, `EncryptedPrefs`, `OdooJsonRpcClient`, `SessionReauthenticator`. **The `@Provides` wins.** Adding or changing a constructor parameter on any of those four **requires editing `AppModule` in the same change or the build breaks.**
- **DI cycle broken with `dagger.Lazy` + post-construction assignment** (`AppModule.kt:65-79`): `AccountRepository(...).also { it.fcmTokenRepository = fcmTokenRepository.get() }`, with `AccountRepository.fcmTokenRepository` a nullable `var` called via `?.`. Do not "clean this up" into constructor injection.
- Seam interfaces are anonymous objects inside `@Provides` (`SessionCookieProvider`, `AppModule.kt:94-101`).
- `FcmTokenRepositoryImpl` has a **two-constructor pattern**: primary takes a built `OkHttpClient` (for MockWebServer tests), secondary `@Inject constructor` builds the hardened client. Mirror this for new HTTP-backed repositories.
- `Application` can't field-inject at `onCreate`: uses nested `@EntryPoint` + `EntryPointAccessors.fromApplication(...)`.
- `WoowFcmService` must stay `@AndroidEntryPoint` — Firebase constructs it outside Hilt otherwise.

### Activity / Navigation
- ⚠️ **`MainActivity` extends `androidx.fragment.app.FragmentActivity`, not `ComponentActivity`** — `androidx.biometric.BiometricPrompt` requires a FragmentActivity host. Changing the base class breaks biometrics.
- `window.addFlags(FLAG_SECURE)` set once at window level in `onCreate` (closes the rotation gap).
- Auth-on-background uses **`ProcessLifecycleOwner`**, not Activity lifecycle, and the observer is **explicitly removed in `onDestroy`**.
- ⚠️ Manifest declares aggressive `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode|locale|layoutDirection|fontScale|density"` — **the Activity is NOT recreated** on rotation/locale/font-scale. Code assuming recreation is wrong here.
- Navigation is **string routes** via `sealed class Screen(val route: String)`. Navigation Compose is 2.7.7 — **do not introduce `composable<Route>` / `toRoute<>()`**.
- Screens receive **lambdas, never the `NavController`**.
- ⚠️ **The auth gate needs BOTH guards**: the computed `startDestination` *and* the imperative `LaunchedEffect(isAuthenticated, requiresAuth, authAction)` with `popUpTo(navController.graph.id) { inclusive = true }`. `startDestination` alone cannot pop a back stack restored after process death. Removing either reopens the bypass.
- Which lock screen opens is decided by the pure, unit-tested `resolveAuthOptions(...)` → `AuthAction`, mapped by `internal fun authStartDestination(...)`. `resolveAuthOptions` is **total** — `AuthAction.RecoveryNeeded` is the floor so the lock screen can never render zero controls (the P0 brick it was written to prevent).

### TestHooks — production-source debug backdoor ⚠️
- `app/src/main/java/io/woowtech/odoo/ui/TestHooks.kt` (`internal object TestHooks`) seeds PIN / app-lock / biometric / reset / location state from adb intent extras, gated **only** by `if (!BuildConfig.DEBUG) return` (line 54); stripped by R8 in release.
- Called from **`MainActivity.onCreate:95` — deliberately BEFORE `setContent`** so the auth gate sees seeded state — and again from `onNewIntent:115`. It is passed `lifecycleScope`.
- **Any refactor of `MainActivity` intent handling, of `setContent` ordering, or of the `suspend SettingsRepository.setPin` signature silently breaks the entire Python E2E suite.** Re-run device tests after touching those.
- It is a security-sensitive surface: **never widen it**, never relax the `BuildConfig.DEBUG` gate, never add a hook that mutates account credentials.
- Every branch is wrapped in `try/catch (t: Throwable)` — FLAG_SECURE makes crash screens un-debuggable, so TestHooks must never crash. Keep that.
- Recognized extras: `--es test-pin <exactly 6 digits>`, `--ez app-lock-enabled`, `--ez biometric-enabled`, `--ez reset-state`, `--ez location-enabled`. (The rejection log string still says "must be 4-6 digits" — stale message, the check is `pin.length == 6`.)

### Compose
- ⚠️ **The codebase uses BOTH `collectAsState()` and `collectAsStateWithLifecycle()`.** Bare `collectAsState()` survives at `NavGraph.kt:61-64`, `PinScreen.kt:75`, `BiometricScreen.kt:75`. `CLAUDE.md` says "never bare `collectAsState()`" — that is the **target**, not the current state. Use `collectAsStateWithLifecycle` for new code; do not assume the file you're in already does.
- ViewModel Flow idiom: `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initial)`.
- Backing-field idiom: `private val _x = MutableStateFlow(...); val x: StateFlow<...> = _x.asStateFlow()`.
- ⚠️ **`rememberUpdatedState` in `MainScreen.OdooWebView` is load-bearing.** The `WebViewClient`/`WebChromeClient` are built once in `AndroidView(factory=…)` and would capture stale values otherwise (real bug `caea05e`). **Eight** values are wrapped (`MainScreen.kt:317-331`): `activeHostSnapshot`, `serverUrl`, `database`, `deepLinkUrl`, `onDeepLinkConsumed`, `onSelfHeal`, `getFreshSessionId`, `onReloginRequired`. Adding a ninth callback means adding a ninth wrap.
- `@Preview` composables are essentially absent — there is no preview convention to follow.

### Theming
- ⚠️ **`ThemeManager` is a top-level Kotlin `object` holding mutable `MutableStateFlow`** (`ui/theme/Theme.kt:21-45`), written from `SettingsRepository`. It is **not** Hilt-managed. A blanket "no mutable global objects" rule does not hold here — this is the actual theme wiring: `SettingsRepository → ThemeManager → WoowTechOdooTheme`.
- The theme composable is named **`WoowTechOdooTheme`** (not `WoowOdooTheme`), `Theme.kt:90` — the only `MaterialTheme(...)` invocation.
- **Dynamic color / Material You is not implemented at all** — no `dynamicLightColorScheme` call, no `dynamicColor` parameter. Schemes are hand-built in `createLightColorScheme`/`createDarkColorScheme`.
- Reduce-motion is a plain `AppSettings.reduceMotion: Boolean` passed as an ordinary parameter. There is **no `LocalReduceMotion` CompositionLocal** and no system animation-scale reads.

### Room — the three-edit migration ritual ⚠️
- `AppDatabase`: `@Database(entities = [OdooAccount::class], version = 2, exportSchema = false)`; `DATABASE_NAME = "woowtech_odoo_db"`; table name `accounts`.
- ⚠️ **The `@Entity` lives in `domain/model/OdooAccount.kt`** and imports `androidx.room`. There is **no "domain is Android-free" invariant** here, and no `data/local/entities/` folder.
- **Adding or changing any field on `OdooAccount` requires THREE coordinated edits in the same commit:**
  1. Bump `version = N` in `AppDatabase`'s `@Database` annotation
  2. Add a new `val MIGRATION_N_M = object : Migration(N, M) { … }` in `AppDatabase.Companion`
  3. **Register it in `AppModule.provideAppDatabase`'s `.addMigrations(...)` chain** (currently `.addMigrations(AppDatabase.MIGRATION_1_2)`)
- Miss step 3 and nothing fails at compile time — it surfaces at runtime as `IllegalStateException` on a real user's device. `exportSchema = false` + no `app/schemas/` means Room can't catch it either.
- `fallbackToDestructiveMigration` is **not** used and must not be added.
- DAO methods are all `suspend` or `Flow`-returning, in paired shapes: `getActiveAccount(): Flow<OdooAccount?>` **and** `suspend fun getActiveAccountOnce()`.

### Encrypted storage
- One wrapper: **`data/local/EncryptedPrefs.kt`** (note `data/local/`, not `data/security/`), file name **`encrypted_prefs`**, `MasterKey.KeyScheme.AES256_GCM` / `AES256_SIV` keys / `AES256_GCM` values.
- Keystore-corruption recovery is a broad `catch (e: Exception)` around `create(...)` that clears prefs, deletes `shared_prefs/encrypted_prefs.xml`, and recreates — deliberate.
- Settings/FCM/permission keys are `private const val KEY_…` in the companion. ⚠️ **Per-account password keys are built inline with no constant**: `prefs.edit().putString("pwd_$accountId", …)` (`EncryptedPrefs.kt:46,50,54`). Match whichever style the neighbouring code uses.
- All writes use `.apply()`; `commit()` appears nowhere.
- Backup exclusion is already wired: `android:allowBackup="true"` **with** both `data_extraction_rules.xml` and `backup_rules.xml` excluding `sharedpref` path `encrypted_prefs.xml`. **Renaming the prefs file requires updating both XMLs.**
- `data/security/EncryptionHelper.kt` is a separate AndroidKeystore AES-256-GCM utility (alias `WoowTechOdooKey`, 12-byte IV prefix, Base64 `NO_WRAP`).

### Networking (hand-rolled JSON-RPC)
- `OdooJsonRpcClient` — `@Singleton`, one `OkHttpClient`, 30s timeouts, custom `CookieJar` over `ConcurrentHashMap<String, MutableList<Cookie>>` **keyed by host, not by account** (documented consequence: multi-account on the same host overwrites session cookies — see the ordering comment in `AccountRepository.switchAccount`).
- `HttpLoggingInterceptor` level is `if (BuildConfig.DEBUG) BODY else NONE` — the interceptor itself is a plain `implementation` dependency, not `debugImplementation`.
- ⚠️ **Odoo returns an expired session as HTTP 200 with a JSON-RPC error envelope, not 401.** That is why session recovery is an OkHttp **`Interceptor`** (`SessionReauthInterceptor`) that peeks `response.peekBody(64KB)` and matches `error.data.name == "odoo.http.SessionExpiredException"` or `error.code == 100` + "session expired". **An `okhttp3.Authenticator` here silently never fires.**
- Retry is fenced by `SessionReauthenticator.RETRY_MARKER_HEADER` — one re-auth, one retry, hard cap.
- `SessionReauthenticator` guardrails (KDoc `:36-60`): https-only + exact stored host, invalid-credential STOP (opens circuit + raises `ReloginSignal`), circuit breaker after N consecutive failures, no credential/cookie logging, per-host single-flight `Mutex`.
- Gson DTOs sit at the bottom of `OdooJsonRpcClient.kt`. ⚠️ There is exactly **one** `@SerializedName` in the whole app (`@SerializedName("debug") val debug: String?`, line 186) and it is **redundant** — the JSON name equals the field name. **No DTO field has a differing name; there is no "annotate only when names differ" rule to follow.** R8 safety comes from `proguard-rules.pro` keeping `data.api.**` and `domain.model.**` wholesale.
- **HTTPS is enforced in three independent places** new network code must respect: manifest `android:usesCleartextTraffic="false"`, `android:networkSecurityConfig="@xml/network_security_config"` (base-config `cleartextTrafficPermitted="false"`, system trust anchors only), and `OdooJsonRpcClient.authenticate` hard-rejecting non-https with `AuthResult.ErrorType.HTTPS_REQUIRED`. **There is no certificate pinning.**

### WebView (`ui/main/MainScreen.kt`, ~930 lines — largest file)
- Created in `AndroidView(factory = …)`; destroyed via `DisposableEffect(Unit) { onDispose { webView?.destroy() } }`.
- ⚠️ **The hardening set is narrower than you'd assume.** Present: `allowFileAccess = false`, `MIXED_CONTENT_NEVER_ALLOW`, `setAcceptThirdPartyCookies(false)`, `setSupportMultipleWindows(false)`, zero `addJavascriptInterface`. **Absent**: `setWebContentsDebuggingEnabled`, `setSafeBrowsingEnabled`, `setAllowFileAccessFromFileURLs`, `setAllowUniversalAccessFromFileURLs`, `onRenderProcessGone`. Also `allowContentAccess = true`.
- ⚠️ `loadWithOverviewMode = false`, `useWideViewPort = false`, `javaScriptCanOpenWindowsAutomatically = true`, and the hardcoded Chrome-120 desktop UA are **intentional** — flipping them breaks Odoo OWL layout. Do not "fix" them.
- JS runs via `evaluateJavascript(...)` (a large OWL layout-fix script in `onPageFinished`), never `addJavascriptInterface`.
- Cookie isolation: `isolateCookiesForAccount(serverUrl, sessionId)` → `setAcceptCookie(true)` → `removeAllCookies(null)` → `setCookie(serverUrl, "session_id=…; Path=/; Secure")` → `flush()`. Called before first load and on every account switch.
- ⚠️ **Session self-heal one-shot guard**: `AtomicBoolean selfHealAttempted` is set on the first `/web/login` interception and cleared in `onPageFinished` **only when the landed URL does NOT contain `/web/login`**. Clearing it on any page-finish reintroduces the Main⇄login bounce loop.
- Account switch is detected in `AndroidView(update = …)` by `serverUrl != lastLoadedServerUrl`.
- Deep links are applied through the pure, unit-tested `DeepLinkWebPlanner.plan(...)` → `NavPlan.FullLoad` (or `null` = safe no-op).

### Push / deep links
- Single channel id **`woow_odoo_messages`** (`WoowOdooApp.CHANNEL_ID_MESSAGES`), `IMPORTANCE_HIGH`. Channels are immutable after creation — changing attributes requires a new id.
- `NotificationHelper`: `PendingIntent.getActivity(..., FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)`, `VISIBILITY_PRIVATE`, `setAutoCancel(true)`, `setGroup(eventType ?: "odoo_messages")`, catches `SecurityException` for missing POST_NOTIFICATIONS. `notificationId = System.currentTimeMillis().toInt()` doubles as the request code — there is no `requestCodeFor(...)` helper.
- FCM payload is **data-only** with fixed keys: `title`, `body`, `odoo_action_url`, `event_type`, `odoo_tenant_id`. Missing `title` or `body` → early return.
- Multi-account routing: `MainActivity.handleDeepLinkIntent` → `DeepLinkRouter.route(...)` → `DeepLinkRoute.SwitchAndApply | ApplyToActive | Drop` → `DeepLinkManager.setPending(url, accountId)`.
- ⚠️ **Deep links are dropped, never re-targeted.** A present-but-unresolvable `odoo_tenant_id`, or a resolved account with no stored password, yields `Drop`. Only a *missing* tenant id falls back to the active account (legacy path).
- `DeepLinkManager` holds at most one pending link, account-bound, single-consume via `consumeFor(accountId)`, 5-minute TTL, plus `dropIfNotTarget(activeAccountId)` as defence in depth.
- `DeepLinkValidator.isValid(url, serverHost)`: rejects blank, `javascript:`, `data:`, any `..` anywhere in the string; accepts relative paths matching the anchored regex `^/web([/?#].*)?$` (so `/website/`, `/webhook`, `/web@evil.com` are rejected); otherwise requires `URI.host == serverHost` case-insensitively.
- Manifest deep-link scheme: `woowodoo://open`.
- POST_NOTIFICATIONS is requested from `MainScreen`'s `LaunchedEffect(Unit)` via `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())`, at most once per install (guarded by `EncryptedPrefs.wasPostNotificationPermissionRequested()`), plus an in-app denial banner. **Accompanist is not a dependency.**

### Location
- `LocationPermissionGate.resolve(origin, activeAccountHost)` → `Decision.Grant | Reject(reason) | NeedsRuntimePrompt`, checking in this fixed **security-first order**: origin is https → origin has host → active account exists → origin host == account host → user preference `locationEnabled` → live OS permission. Do not reorder.
- `activeHostSnapshot` is pre-computed in a `LaunchedEffect(account)` so the `WebChromeClient` callback thread never suspends.

---

## 4. Testing Rules

**Layout & scale**
- Tests: `app/src/test/kotlin/…`. **41 files, 370 test methods** (364 `@Test` + 6 `@ParameterizedTest`). Any doc citing "178 unit tests" is stale.
- **`app/src/androidTest` does not exist.** The declared androidTest deps (espresso, uiautomator, ui-test-junit4) sit against an empty source set. `./gradlew connectedDebugAndroidTest` (documented in README:902) is a no-op.

**Frameworks — mixed on purpose**
- Default is **JUnit 5 (Jupiter)**: `org.junit.jupiter.api.Test`, `@BeforeEach`, MockK, Turbine, coroutines-test, MockWebServer. 40 of 41 files.
- ⚠️ **Robolectric is IN USE, not banned.** `ui/auth/PinDotsRowShakeTest.kt` is JUnit 4 + Robolectric + Compose: `@RunWith(RobolectricTestRunner::class)`, `@GraphicsMode(LEGACY)`, `@Config(sdk = [34], application = Application::class)`, `@get:Rule val composeRule = createComposeRule()`, `org.junit.Test`. It runs under `useJUnitPlatform()` only because `junit-vintage-engine` is on the test runtime classpath. So "never import `org.junit.Test`" is **false** — it is correct for Robolectric/Compose render tests only.
- ⚠️ Any new Robolectric test **must** use `@Config(application = Application::class)` — a plain stub, not `WoowOdooApp`, because `WoowOdooApp.onCreate` initializes Firebase and crashes render tests.
- `testOptions.unitTests.isIncludeAndroidResources = true` must stay — removing it breaks the Compose render tests.
- **No Hilt test graph at all** — zero `@HiltAndroidTest`, zero `TestInstallIn`. Every test constructs the SUT directly with MockK fakes.

**Conventions**
- Backticked GIVEN-WHEN-THEN names: ``fun `Given zero accounts when registerTokenForAllAccounts then token saved but no POST attempted`()``
- **One test class per behaviour/regression, not per production class**, named after the *scenario*: `FcmTokenEmptyAccountsTest`, `LoginRegisterTest`, `LogoutUnregisterTest`, `SwitchAccountUnregisterTest`, `MainViewModelSelfHealTest`, `PinDotsRowShakeTest`, `AuthRoutingTest`.
- Each regression test file **opens with a KDoc block naming the bug it guards, with the commit SHA**. This is the project's traceability mechanism — follow it.
- ViewModel/repo tests: `StandardTestDispatcher()` + `Dispatchers.setMain/resetMain`, `runTest { }`, `mockk(relaxed = true)` + `coEvery`/`coVerify(exactly = n)`. `UnconfinedTestDispatcher` is not used.
- Real objects preferred over mocks where cheap.
- MockWebServer goes through `FcmTokenRepositoryImpl`'s client-injecting primary constructor.

**Commands that actually work**
```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest        # or ./gradlew test
./gradlew :app:lint                # AGP defaults only, no config
./gradlew :app:testDebugUnitTest --tests "io.woowtech.odoo.data.repository.AccountRepositoryTest"
```

**Python device/E2E suites** (`scripts/`, run manually — **CI runs none of them**)

| Script | Coverage |
|---|---|
| `scripts/verify-on-device.py` | V-tests over uiautomator2; contains `perform_login`, the ADBKeyboard reference pattern |
| `scripts/e2e_15_clockin_full.py` | GPS clock-in through WebView + JSON-RPC verify |
| `scripts/e2e-production-test.py` | FCM push / biometric / theme / locale / cache / deep link |
| `scripts/e2e_hprime_android.py`, `…_chaos.py` | H′ pipeline + fault injection |
| `scripts/e2e-verification-report.py` | Consumes the above → `docs/verification-report/` |

- ✅ **`scripts/test_config.py` EXISTS and is the single source of truth** for tunnel URL / DB / creds / package / Firebase project. Resolution order: env var → `.env.test` (gitignored) → committed default. **Do not hardcode the tunnel in individual scripts** — that ticket is closed.
- Always switch to ADBKeyboard before typing into login/PIN fields (`enable_adb_keyboard()` / `restore_ime()`); the default IME autocorrects test input into false failures.
- Always dump the live UI hierarchy before writing a uiautomator2 selector — Compose exposes `content-desc` where you'd expect `text`.

---

## 5. Code Quality & Style

**Enforcement reality — the single most important correction** ⚠️
- There is **no `.editorconfig`**, **no ktlint plugin**, **no detekt plugin**, **no `config/detekt/`**, **no `app/lint.xml`**, **no lint baseline**, **no `.githooks/`**, **no Konsist**, **no `app/schemas/`**.
- `app/build.gradle.kts` `plugins { }` contains exactly: android-application, kotlin-android, kotlin-compose, hilt, ksp (+ conditional google-services).
- ⚠️ **`./gradlew ktlintFormat` does not exist and will fail.** It is printed in both `CLAUDE.md` and `README.md:1120`. Do not run it and do not tell the user to.
- Consequence: **every style rule below is convention only, enforced by review.** Never claim a rule is "enforced by tooling" in this repo.

**Package layout** (`app/src/main/java/io/woowtech/odoo/`) — package by layer, then feature
```
WoowOdooApp.kt                 @HiltAndroidApp
ui/     MainActivity, TestHooks, navigation/, auth/, login/, main/, config/, theme/
data/   api/, local/, location/, push/, repository/, security/
domain/ model/   (OdooAccount [Room @Entity], AppSettings, AuthResult)
di/     AppModule.kt
```
- Naming: `*ViewModel`, `*Repository`, `*Screen`, `*Helper`, `*Manager`, `*Validator`, `*Router`, `*Planner`, `*Gate`.
- ⚠️ **Interface + `Impl` is used exactly once** (`FcmTokenRepository` / `FcmTokenRepositoryImpl`). `AccountRepository`, `SettingsRepository`, `CacheRepository` are concrete with no interface. **Do not add interfaces reflexively.**
- Stateless `object`s of pure functions (`DeepLinkValidator`, `DeepLinkRouter`, `DeepLinkWebPlanner`) are the preferred shape for unit-testable logic pulled out of Compose/Activity code.

**Constants**
- ⚠️ **There are no `*Constants.kt` files** — `AuthConstants.kt`, `CryptoConstants.kt`, `PushConstants.kt` do not exist. Put constants in the owning class's `companion object`.
- Existing homes: `SettingsRepository.Companion` (`PIN_LENGTH = 6`, `MAX_PIN_ATTEMPTS = 5`, `SALT_LENGTH_BYTES = 16`, `PBKDF2_ITERATIONS = 600_000`, `LOCKOUT_DURATIONS_MS`), `WoowOdooApp.Companion` (`CHANNEL_ID_MESSAGES`), `NotificationHelper.Companion` (`EXTRA_ACTION_URL`, `EXTRA_TENANT_ID`), `DeepLinkManager.Companion` (`TTL_MILLIS`), `SessionReauthInterceptor.Companion` (Odoo error strings).

**Visibility**
- Almost everything is `public`. `internal` is used sparingly and intentionally: `internal object TestHooks`, `internal fun authStartDestination(...)` (exposed for its unit test), `internal class ContextPermissionChecker`. There is **no "internal by default" rule**.

**Error handling**
- `runCatching { }` + `.onSuccess/.onFailure` is the dominant idiom for best-effort side effects.
- Broad `catch (e: Exception)` / `catch (t: Throwable)` is used **deliberately and documented** at exactly three boundaries: EncryptedPrefs keystore recovery, `TestHooks` (never crash the app), `ThemeManager.setPrimaryColorFromHex` (keep default color). Everywhere else catch specific types.
- ⚠️ **Failure classification must not be simplified to `is IOException`.** `FcmTokenRepositoryImpl.Throwable.isUnreachable()` = `UnknownHostException || ConnectException || SocketTimeoutException` → best-effort/retryable. **Everything else — including HTTP 401 and Odoo error envelopes, which `postToOdoo` also maps to `IOException` — is a hard failure the caller must see.** Getting this wrong makes one dead tenant poison every reconcile (bug `115cd4c`).
- Fan-out operations aggregate all per-item failures into one summary (`registerTokenForAllAccounts` partitions `failures` and returns an `IllegalStateException` naming `hard.size/accounts.size`), rather than surfacing the last one.

**Security conventions in force**
- PIN: PBKDF2WithHmacSHA256, **600 000 iterations**, 16-byte `SecureRandom` salt, stored `"saltHex:hashHex"`, compared with `ByteArray.contentEquals`.
- ⚠️ **"Never SHA-256" carries one exception**: `SettingsRepository.verifyPbkdf2` retains a **legacy unsalted SHA-256 migration path** — a stored hash with no `:` separator is SHA-256-compared and transparently re-hashed to PBKDF2 on successful verify (`SettingsRepository.kt:245-250`). Do not delete this path; do not add new SHA-256 call sites.
- **PIN length is exactly 6, not "4–6".** `SettingsRepository.PIN_LENGTH = 6` is the single source of truth; `setPin` returns false for any other length; `AuthViewModel.enterPinDigit` verifies **only** at 6 digits (verifying at 4 and 5 burned spurious failures and caused false lockouts — fixed in `e22f02e`).
- Lockout after 5 failures, escalating 30s → 5min → 30min → 1h, timed with `SystemClock.elapsedRealtime()`.
- Data-layer invariant: **`appLockEnabled ⇒ pinEnabled`**. `updateAppLock(true)` returns `false` + warns if no PIN is set; `removePin()` co-disables App Lock in a **single** `saveAppSettings` write so a crash can never leave a PIN-less armed lock.
- `updateBiometric(enabled, canEnable)` force-writes `false` when `BiometricManager` reports no strong biometric.
- Biometric prompt has **no skip button** — removed for security.
- Secrets are never logged; account ids and reason categories only.

**Strings / localization**
- Three locales: `values/`, `values-zh-rTW/`, `values-zh-rCN/`. ⚠️ **Currently out of parity**: `values` 155, `zh-rTW` 153 (missing `notification_channel_messages`, `notification_channel_messages_desc`), `zh-rCN` 156 (extra `language_chinese_cn`). **Any new string must be added to all three in the same commit.**
- There is **no `res/xml/locales-config.xml`** and no `android:localeConfig`. AppCompat is not a dependency. Locale is an in-app `AppLanguage` enum (`system`/`en`/`zh-TW`/`zh-CN`) persisted in `EncryptedPrefs`.

---

## 6. Development Workflow Rules

**Branching**
- Remote `origin` = `github.com/WOOWTECH/Woow_odoo_app.git`; default branch `main`.
- Live patterns: `feature/<topic>`, `fix/<topic>`, `security/<topic>`.
- **Merge commits into `main` are used, not squash-only.**

**Commit messages** — `type(scope): description`
- Types in use: `feat`, `fix`, `test`, `docs`, `chore`.
- ⚠️ **Scope is a feature/work-item id, NOT the old `B0`–`B4`/`A1`/`A2` phase letters.** No commit since 2026-05-04 uses phase letters. Real scopes: `session`, `applock`, `fcm`, `fcm-s4`, `deeplink`, `deeplink+push`, `android`, `arch`, `release`.
- The version bump is its own commit with nothing else in it (`2b39124`) — that convention is real.

**CI — `.github/workflows/build.yml` is the ONLY workflow** ⚠️
- Triggers: push to `main`, PR to `main`, `workflow_dispatch`. Steps: checkout → temurin 17 → setup-gradle → **`./gradlew assembleDebug --stacktrace`** → upload APK artifact → `softprops/action-gh-release@v1`.
- **CI runs no tests, no lint, no static analysis, no device/E2E suites.**
- Known defect (flagged in `docs/architecture-overview.md` §16): the release step is **unconditional — it fires on pull requests too** — and uses hardcoded tag `v1.0.0-build${{ github.run_number }}` that does not track `versionName`.
- `pr.yml`, `device-nightly.yml`, `release.yml` do **not** exist.
- **Because CI is this thin, local verification is the only gate. Run it.**

**Versioning / release**
- `versionCode`/`versionName` in `app/build.gradle.kts` `defaultConfig`.
- Release APKs are built **locally** (`./gradlew assembleRelease`), not in CI. There is **no `signingConfigs` block** in the checked-in build file (signing is documented in README only).
- `releases/` holds stale v1.0.0–v1.0.2 **debug** APKs.

**Secrets / gitignore**
- Ignored: `google-services.json`, `app/firebase-service-account.json`, `local.properties`, `*.env`, `.env.test`, `secrets/`, `*.apk`/`*.aab`, `__pycache__/`.
- ⚠️ **`*.jks` / `*.keystore` are present but COMMENTED OUT** in `.gitignore:50-51`. Keystores are **not ignored today** — never `git add` one, and there is no `check-no-secrets-in-diff` CI step to save you.
- `app/firebase-service-account.json` must NEVER be committed.

**Docs**
- Plans: `docs/plans/<YYYY-MM-DD>-<topic>.md`. Scratch: `docs/<YYYY-MM-DD>-<topic>.md`. Verification output: `docs/verification-report/`.
- `docs/architecture-overview.md` (2026-07-29) is the newest narrative doc and explicitly audits older claims — read it before `CLAUDE.md` when they disagree on CI/build facts.
- Odoo-side push module `woow_fcm_push` lives in a separate repo: `github.com/WOOWTECH/woow_odoo_fcm_push`.

---

## 7. `CLAUDE.md` CRITICAL RULEs — highest precedence

These override any generic Kotlin/Android style advice, including anything in this file.

1. **Repository-Event Symmetry.** When a repository wires a side-effect to event X (`logout → unregisterToken`), you MUST verify the symmetric inverse is reachable and **unit-tested in both directions** (`login → register`, `addAccount ↔ removeAccount`, `acquire ↔ release`, `subscribe ↔ unsubscribe`). If the forward event has no test asserting the side-effect, the work is **not done**. Origin: commit `482a7bf` shipped `logout → unregister` without `login → register`; FCM tokens never reached Odoo after a fresh login.
   - **Empty-collection paranoia**: if the side-effect iterates a collection, add a test for the empty case asserting the operation does not silently consume a not-yet-replayed value. The sanctioned shape in this repo is **save + loud warn + replay hook + covering test** (see §9 item 3).
2. **Verification Checklists Become Automated Tests.** Every line in a commit's "Requires on-device verification" list MUST map to a V-ID in `scripts/verify-on-device.py` or an E2E-ID in the E2E scripts. If it genuinely cannot be automated, tag it `@OnDevice` and document why in the test plan. Manual checklists rot — that is how `482a7bf` shipped.
3. **Mega-Commit Cap.** Commits touching **>15 files** OR **>1000 LOC of behavior change** must be split along feature/responsibility lines. Tests + production code stay in the same commit. Refactors stay separate from features. More than 3 paragraphs of commit body ⇒ split.
4. **Test Independence.** Every test must pass when run alone, in any order, in parallel. Each test owns its setup, action, and cleanup. Use the debug `TestHooks` intent extras to set preconditions instead of multi-step Compose navigation. **`pm clear` is destructive** — only inside the one test that explicitly exercises empty-state behaviour.
5. **Inspect Before Asserting (uiautomator2).** Always dump and print the live UI hierarchy before writing a selector. Never guess `text=` vs `content-desc=` vs `resource-id=` — Compose semantics differ from classic Views.
6. **Screenshot verification.** Never screenshot after a bare `sleep`. Poll for the expected element/activity first, then capture; fail loudly if it never appears.
7. **Source Code Change → ALL Tests Must Pass.** ⚠️ **Whenever `app/src/main/…` changes, re-run every suite in §4 and report a PASS/FAIL table before declaring done** — even for one-liners, comment-only changes, or when a test was already red. A red test is a red test. Valid responses to a failure: fix it (production **or** script — document which), or get explicit user agreement to merge with a documented follow-up ticket. **Never report "ready to merge" while any suite is red.** A "test script bug" is still a real bug: the behavior is unverified either way.

**Per-commit minimum gate:** `./gradlew assembleDebug` → `./gradlew testDebugUnitTest` (0 failures) → `python3 scripts/verify-on-device.py` if a device is attached.

---

## 8. Known-stale claims in `CLAUDE.md` / `README.md`

Do not act on these; do not propagate them.

| Stale claim | Reality |
|---|---|
| `./gradlew ktlintFormat` (CLAUDE.md, README:1120) | Task does not exist — no ktlint/detekt/`.editorconfig` |
| "178 unit tests" (CLAUDE.md, architecture-overview §16-17) | 41 files, **370** test methods |
| `--es test-pin <4-6 digits>` | `TestHooks` requires **exactly 6**; the log string is also stale |
| "PIN hash never SHA-256" | True for new PINs; a **legacy unsalted SHA-256 migration path** still exists |
| "never bare `collectAsState()`" | Target state; 6 bare call sites remain |
| `./gradlew connectedDebugAndroidTest` (README:902) | No `androidTest` source set — no-op |
| Phase scopes `B0`–`B4`/`A1`/`A2` in commit messages | Replaced by feature/work-item scopes |
| `security-crypto 1.1.0-alpha06` (AndroidX) | `dev.spght:encryptedprefs-core:1.1.1` (community fork) |
| Retrofit/DataStore/Coil "part of the stack" | Declared, **zero usages** |
| Dispatcher qualifiers in `AppModule` | None exist anywhere |
| "No `runBlocking` in `app/src/main`" | One KDoc-sanctioned exception in `SessionReauthenticator` |
| `versionName 1.0.20` / `versionCode 20` | **1.4.1 / 21** |
| Robolectric forbidden in the unit tier | Robolectric **is wired and in use** (`PinDotsRowShakeTest`) |

---

## 9. Critical Don't-Miss — the traps that actually bite

1. **`./gradlew ktlintFormat` does not exist.** Only `assembleDebug`, `testDebugUnitTest`/`test`, `:app:lint` work. Never claim tooling enforcement.
2. **Encrypted prefs are `dev.spght:encryptedprefs-core:1.1.1`, not AndroidX.** `androidx.security.crypto.*` will not compile.
3. **`registerTokenForAllAccounts` with zero accounts returns `Result.success(Unit)` — by design.** It saves the token locally, logs `Timber.w("… no accounts to register with — AccountRepository will replay on next login/switch")`, and `AccountRepository.authenticate`/`switchAccount` replay it via `reconcileOnAccountAvailable()`. `FcmTokenEmptyAccountsTest` asserts `isSuccess`. The empty-collection rule here is **save + warn + replay + covering test**, *not* "never return success".
4. **Changing a constructor param on `AccountRepository` / `EncryptedPrefs` / `OdooJsonRpcClient` / `SessionReauthenticator` requires editing `AppModule` too** — those four have both an `@Inject constructor` and a redundant `@Provides`, and `@Provides` wins. Otherwise the build breaks.
5. **Adding a field to `OdooAccount` requires three edits:** version bump + new `Migration` object + `.addMigrations(...)` registration in `AppModule.provideAppDatabase`. A missed registration only surfaces at runtime.
6. **`runBlocking` in `SessionReauthenticator` is sanctioned** (KDoc `:56-58`). Don't delete it; don't copy it outside an OkHttp interceptor.
7. **`MainActivity` must stay a `FragmentActivity`** — BiometricPrompt requires it.
8. **`TestHooks` runs at `MainActivity.onCreate:95`, before `setContent`.** Refactoring intent handling, `setContent` ordering, or `SettingsRepository.setPin`'s suspend signature silently breaks the whole E2E suite. Never widen the hook surface or relax its `BuildConfig.DEBUG` gate.
9. **Odoo signals session expiry as HTTP 200 + JSON-RPC error envelope.** Session recovery must stay an `Interceptor`; an `okhttp3.Authenticator` never fires.
10. **Account ids are `String` UUIDs.** Never `accountId: Long`. No `value class` wrappers exist.
11. **PIN length is exactly 6.** Verifying at 4/5 digits caused false lockouts (`e22f02e`).
12. **Don't simplify the FCM failure classifier to `is IOException`** — only `UnknownHost|Connect|SocketTimeout` are "unreachable"; everything else is a hard failure (`115cd4c`).
13. **`AccountRepository.fcmTokenRepository` is a nullable `var` set post-construction via `dagger.Lazy`** to break a real DI cycle. Don't "fix" it into constructor injection.
14. **`ThemeManager` is a global mutable `object`**, not a Hilt singleton. That's the real theme wiring.
15. **`rememberUpdatedState` around the 8 WebView callback params is load-bearing** (`caea05e`). Add a 9th param ⇒ add a 9th wrap.
16. **Navigation is string routes on 2.7.7.** No `composable<Route>` / `toRoute<>()`.
17. **The auth gate needs both `startDestination` and the imperative `LaunchedEffect`.** Removing either reopens the process-death bypass.
18. **The WebView self-heal `AtomicBoolean` clears only when the landed URL is not `/web/login`.** Clearing it unconditionally reintroduces the bounce loop.
19. **`loadWithOverviewMode = false` / `useWideViewPort = false` / `allowContentAccess = true` / the Chrome-120 UA are intentional** — they keep Odoo OWL layout working.
20. **`DeepLinkValidator`, `LocationPermissionGate` and the WebView host comparison use `java.net.URI`, not `android.net.Uri`,** so they unit-test on the JVM. Converting breaks the suite.
21. **The Activity does not recreate on rotation/locale/font-scale** (`android:configChanges`). Auth-on-background hangs off `ProcessLifecycleOwner`.
22. **Deep links with an unresolvable tenant id are dropped, never applied to the active account.**
23. **Notification channel id is `woow_odoo_messages`.** Channel attributes are immutable after creation.
24. **`app/src/androidTest` does not exist and CI runs only `assembleDebug`.** Device coverage is Python/uiautomator2, run manually.
25. **One test file is JUnit 4 + Robolectric** and needs `@Config(application = Application::class)` plus `isIncludeAndroidResources = true`.
26. **String resources are out of parity right now.** Add new strings to all three `values*` dirs.
27. **`scripts/test_config.py` is the single source of truth** for the E2E tunnel/creds. Don't hardcode the tunnel per script.
28. **`*.jks`/`*.keystore` are commented out in `.gitignore`** — keystores are not ignored. Never stage one.
29. **`src/main/java` vs `src/test/kotlin`** — put new files in the right root.
30. **There is no certificate pinning**, no StrictMode, no Kover/Pitest/Paparazzi, no Konsist arch tests, no `.githooks/`, no PR template. Don't reference them as existing.

---

## Usage Guidelines

**For AI agents**
- Read this file before implementing. Where it contradicts `CLAUDE.md`/`README.md` on **facts**, this file wins; where `CLAUDE.md` states a `CRITICAL RULE` **process**, that wins.
- Prefer the more restrictive option when in doubt.
- Verify before asserting a pattern exists — several "obvious" Android patterns (DataStore, Retrofit, dispatcher qualifiers, androidTest, ktlint) are **absent here**.
- After any `app/src/main` change, run the full suite table in §4 and report PASS/FAIL honestly.

**For humans**
- Keep this lean. Update when the stack, tooling, or an invariant changes.
- The §8 stale-claims table should shrink as `CLAUDE.md` and `README.md` are corrected — delete rows as they're fixed.

Last Updated: 2026-08-01 (verified against HEAD `0e57411`)
