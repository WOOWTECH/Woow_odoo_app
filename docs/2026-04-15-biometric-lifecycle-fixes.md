# Biometric Auth — Android Lifecycle & Process-State Security Fixes
Date: 2026-04-15
Branch: feature/biometric-restoration

---

## Risk Matrix

| ID  | Severity | Finding | MASVS Control |
|-----|----------|---------|---------------|
| L1  | HIGH     | ProcessLifecycleOwner observer leaks on every Activity recreation | MASVS-RESILIENCE-2 |
| L2  | HIGH     | `startDestination` race: nav graph re-evaluates before `isAuthenticated` resets, potentially skipping auth gate on process restoration | MASVS-AUTH-1 |
| L3  | MEDIUM   | `LaunchedEffect(Unit)` in `BiometricScreen` re-fires on every composition entry including rotation, causing double-prompt | MASVS-PLATFORM-2 |
| L4  | MEDIUM   | Stale `BiometricPromptHelper` after rotation — callback from old prompt can land on new composition | MASVS-RESILIENCE-2 |
| L5  | MEDIUM   | `configChanges` not declared — every rotation/locale/font-scale change destroys and recreates `MainActivity`, multiplying L1 | MASVS-RESILIENCE-2 |
| L6  | MEDIUM   | `FLAG_SECURE` cleared on navigation-driven dispose, but on rotation the `DisposableEffect` teardown fires before the new composition adds it back — brief window where screenshot is possible | MASVS-STORAGE-2 |
| L7  | LOW      | `isLockedOut` local state in `PinScreen` initialised once from `viewModel.isLockedOut()` — after process death and restore, the ViewModel re-reads persisted state correctly, but `isLockedOut` local var is always re-evaluated on composition entry (safe, but could race with lockout countdown coroutine resuming) | MASVS-AUTH-1 |
| L8  | LOW      | `NavGraph` uses string-based routes (`sealed class Screen`) instead of type-safe `@Serializable` data classes — not a direct security risk but makes saved-state restoration of the back-stack harder to audit | MASVS-PLATFORM-1 |

---

## Detailed Findings

### L1 — HIGH: ProcessLifecycleOwner observer leak

**File:** `app/src/main/java/io/woowtech/odoo/ui/MainActivity.kt`, line 39–45

**Exploitation:**
Every time Android recreates `MainActivity` (rotation, locale change, font-scale change, theme switch — all of which happen because `android:configChanges` is not declared), a new `LifecycleEventObserver` is added to the **process-level** `ProcessLifecycleOwner`. Because `ProcessLifecycleOwner` lives for the entire process lifetime, the observer is never removed when the Activity is destroyed. After N recreations, N observers call `authViewModel.onAppBackgrounded()` on every `ON_STOP`. The ViewModel is the same instance (survives config change via Hilt), so the call is idempotent in effect — but N leaked observers accumulate for the process lifetime and can cause spurious `ON_STOP` firings if the system re-delivers events.

More critically: because the observer is added in `onCreate` with no corresponding `removeObserver` in `onDestroy`, any code path that is sensitive to the number of callbacks (e.g. logging, analytics, rate-limiting) will be called multiple times.

**Current code:**
```kotlin
// MainActivity.onCreate — observer added but never removed
ProcessLifecycleOwner.get().lifecycle.addObserver(
    LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) {
            authViewModel.onAppBackgrounded()
        }
    }
)
```

**Fix — store reference and remove in onDestroy:**
```kotlin
private lateinit var processLifecycleObserver: LifecycleEventObserver

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    processLifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) {
            authViewModel.onAppBackgrounded()
        }
    }
    ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)
    // ...
}

override fun onDestroy() {
    super.onDestroy()
    ProcessLifecycleOwner.get().lifecycle.removeObserver(processLifecycleObserver)
}
```

**Alternative (preferred) — use `addObserver` bound to this activity's lifecycle:**
```kotlin
// Automatically removed when the activity's lifecycle reaches DESTROYED
lifecycle.addObserver(LifecycleEventObserver { _, event ->
    // This fires on the ACTIVITY lifecycle, not the process lifecycle.
    // For bg detection use ProcessLifecycleOwner but scope removal to this activity:
})
// For process-level bg, attach once at Application level instead of per-Activity.
```

The cleanest fix is to move the `ProcessLifecycleOwner` observer to `WoowOdooApp.onCreate()` where it is registered exactly once for the process lifetime. The `AuthViewModel` is a `@HiltViewModel` — obtain it via a `ViewModelStoreOwner` at Application scope or use a `@Singleton` service.

---

### L2 — HIGH: `startDestination` race on process restoration

**File:** `app/src/main/java/io/woowtech/odoo/ui/navigation/NavGraph.kt`, lines 39–44

**Exploitation:**
`_isAuthenticated` starts as `false` in `AuthViewModel` (in-memory only, not persisted, not in `SavedStateHandle`). On process death and restoration, Android can restore the nav back-stack via `rememberNavController`'s saved-state, potentially restoring the user directly to `Screen.Main`. Because `isAuthenticated` is `false` after process restoration and `requiresAuth` reads from `SettingsRepository` (persisted), the `startDestination` computation on the very first frame correctly sends to `Screen.Auth`. However, there is a one-frame window where:

1. `hasActiveAccount` emits `null` (initial value) → `startDestination = Screen.Splash`
2. The nav back-stack restored from saved state may be `[Splash, Main]`
3. `LaunchedEffect(hasActiveAccount, requiresAuth, isAuthenticated)` in Splash fires asynchronously
4. If the system is slow delivering the `StateFlow` update, the user could briefly see `MainScreen`

The stronger exploitation path: `Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`. When the app is re-launched from the Recents screen after process death, the Activity is recreated with the saved instance state, `isAuthenticated` is `false`, but the nav controller may restore its saved state to the last-visited destination (`Main`). The `startDestination` only controls the *initial* destination — it does not pop the restored back-stack.

**Fix:**
`isAuthenticated` must not be held solely in a `MutableStateFlow`. On process death it resets to `false` which is secure, but the nav controller's saved state can bypass the `startDestination` guard. The fix is to observe `isAuthenticated` in a top-level `LaunchedEffect` in `WoowOdooNavHost` and imperatively navigate to `Screen.Auth` whenever it transitions from `true` to `false` (i.e. backgrounded):

```kotlin
// In WoowOdooNavHost, alongside existing state collection:
LaunchedEffect(isAuthenticated, requiresAuth) {
    if (requiresAuth && !isAuthenticated) {
        // Clear the entire back-stack and land on Auth — covers process restoration
        // and bg→fg re-auth regardless of which destination was on top.
        navController.navigate(Screen.Auth.route) {
            popUpTo(navController.graph.id) { inclusive = true }
        }
    }
}
```

This is the same pattern used by the iOS counterpart and ensures the nav controller saved-state cannot bypass the auth gate.

---

### L3 — MEDIUM: `LaunchedEffect(Unit)` causes double biometric prompt on rotation

**File:** `app/src/main/java/io/woowtech/odoo/ui/auth/BiometricScreen.kt`, line 148

**Exploitation:**
`LaunchedEffect(Unit)` uses `Unit` as its key, which means it re-fires every time `BiometricScreen` re-enters the composition. On rotation (without `android:configChanges`), the entire composable tree is destroyed and re-composed, so `LaunchedEffect(Unit)` fires again and calls `showBiometricPrompt()` a second time. The first `BiometricPrompt` instance was tied to the old (destroyed) `FragmentActivity` — its callbacks will land on a dead fragment manager. The second prompt is correct. The result is a flash of the system dialog, dismiss, then re-show — jarring UX and a sign that callback state is briefly undefined.

The actual security impact is limited because the callback from the dead prompt cannot call `onAuthSuccess` through the new composition's closure, but it creates a callback-state gap where neither prompt owns the result.

**Fix — key the effect on a stable identity, not `Unit`:**
```kotlin
// Use the activity instance as the key so the effect only re-fires if
// the activity itself changes (true re-creation), not on recomposition.
val activity = LocalContext.current as? FragmentActivity
LaunchedEffect(activity) {
    if (settings.biometricEnabled && canUseBiometric) {
        showBiometricPrompt()
    }
}
```

Because `biometricHelper` is already `remember(activity)`, using `activity` as the `LaunchedEffect` key is consistent: both the helper and the auto-show trigger are tied to the same activity instance.

---

### L4 — MEDIUM: Stale `BiometricPromptHelper` after rotation — callback delivered to dead composition

**File:** `app/src/main/java/io/woowtech/odoo/ui/auth/BiometricScreen.kt`, lines 87–88

**Exploitation:**
`biometricHelper` is `remember(activity)`. On rotation, `activity` changes, so `biometricHelper` is correctly re-created. However, the *old* `BiometricPrompt` instance (created inside the previous `prompt()` call) still holds a reference to the old `FragmentActivity`'s fragment manager. If the user rotates while the system dialog is showing, the old prompt's callback closures (`onSuccess`, `onFailed`, etc.) capture local Compose state from the old composition (`errorMessage`, `failureCount`, `onAuthSuccess`). After rotation those closures reference dead state and stale lambdas.

In practice `BiometricPrompt` is internally dismissed when its host `FragmentActivity` is destroyed, so `onSuccess` will not fire after rotation — but `onAuthenticationError(ERROR_CANCELED)` or `onAuthenticationError(ERROR_USER_CANCELED)` may fire, calling `onFallbackToPin()` from the old composition's lambda, which calls `navController.navigate(Screen.Pin.route)` through a now-invalid controller reference.

**Fix — cancel the prompt on composition disposal:**
```kotlin
DisposableEffect(activity) {
    onDispose {
        // BiometricPrompt does not expose a public cancel(), but navigating away
        // naturally dismisses it. For explicit cancellation during rotation,
        // retain the BiometricPrompt reference and call cancelAuthentication():
        biometricHelper?.cancelPendingAuthentication()
    }
}
```

Expose `cancelPendingAuthentication()` on `BiometricPromptHelper` by holding the `BiometricPrompt` reference:
```kotlin
// BiometricPromptHelper — store the last active prompt
private var activePrompt: BiometricPrompt? = null

fun cancelPendingAuthentication() {
    activePrompt?.cancelAuthentication()
    activePrompt = null
}

fun prompt(...) {
    val biometricPrompt = promptFactory(activity, executor, callback)
    activePrompt = biometricPrompt
    // ...
}
```

---

### L5 — MEDIUM: No `android:configChanges` on MainActivity

**File:** `app/src/main/AndroidManifest.xml`, line 38

**Exploitation:**
Without `android:configChanges`, Android destroys and recreates `MainActivity` on: rotation, locale change, font scale change, dark mode toggle, and display density change. Each recreation compounds L1 (leaked observer) and L3 (double prompt). This is architectural root cause for several of the above issues.

**Fix:**
```xml
<activity
    android:name=".ui.MainActivity"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|uiMode|locale|layoutDirection|fontScale|density"
    android:windowSoftInputMode="adjustResize">
```

This is the standard set for a Compose single-activity app. Compose handles recomposition on configuration change natively; the activity does not need to be destroyed. This eliminates L3 double-prompt, reduces L1 leak frequency, and removes the L4 stale-callback window during rotation.

Note: adding `configChanges` means `onConfigurationChanged` fires instead. Since the UI is fully Compose with no manual configuration-dependent logic in the Activity, this is safe.

---

### L6 — MEDIUM: FLAG_SECURE gap during rotation (DisposableEffect teardown before re-add)

**Files:** `BiometricScreen.kt` line 78–81, `PinScreen.kt` lines 74–77

**Exploitation:**
Both screens use `DisposableEffect(Unit)` to add `FLAG_SECURE` and clear it on disposal. On rotation (without `configChanges`), the composable is disposed and the flag is cleared, then the new composition adds it back. Between those two events there is a window (typically 1–3 frames, ~16–50ms) where `FLAG_SECURE` is not set. If the system takes a screenshot during an Activity transition in that window (Recents thumbnail capture is the primary risk), the auth UI can appear in the Recents thumbnail.

With L5 fix (adding `configChanges`), this window is eliminated because the Activity is not recreated. If L5 cannot be applied immediately, the mitigation is to set `FLAG_SECURE` unconditionally in `MainActivity.onCreate()` (for the entire app) and clear it only when leaving the auth screens via explicit navigation.

**Fix (independent of L5):**
```kotlin
// MainActivity.onCreate — set FLAG_SECURE globally; auth screens inherit it.
// Remove only after navigating to MainScreen where sensitive content is not shown.
window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
```

Then in `MainScreen` (or via a `NavBackStackEntry` observer in `WoowOdooNavHost`) clear the flag after successful auth navigation. This is a defence-in-depth approach that removes the gap entirely.

---

### L7 — LOW: `isLockedOut` local state initialisation race in PinScreen

**File:** `app/src/main/java/io/woowtech/odoo/ui/auth/PinScreen.kt`, line 67

**Exploitation:**
`var isLockedOut by remember { mutableStateOf(viewModel.isLockedOut()) }` reads the persisted lockout state once at composition time. This is correct for initial state. However, the lockout countdown `LaunchedEffect` is keyed on `(isLockedOut, lifecycleOwner)` — if the user returns to `PinScreen` after backgrounding mid-lockout, `isLockedOut` is re-evaluated from `viewModel.isLockedOut()` at composition time (correct), and the countdown fires again. The race: `getLockoutRemainingMs()` could return 0 between the `isLockedOut()` check and the first `LaunchedEffect` tick (lockout expired in the few milliseconds between composition and effect launch), leaving `isLockedOut = true` locally even though lockout cleared.

The impact is low (user sees "try again later" for up to 500ms extra) but could be confusing in automated tests.

**Fix — unify the lockout check inside the effect:**
```kotlin
var isLockedOut by remember { mutableStateOf(false) } // start false, let effect determine

LaunchedEffect(lifecycleOwner) {
    // Re-evaluate every time lifecycle restarts — covers bg/fg transition
    val remaining = viewModel.getLockoutRemainingMs()
    isLockedOut = remaining > 0
    while (isLockedOut && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        val remainingMs = viewModel.getLockoutRemainingMs()
        if (remainingMs <= 0) { isLockedOut = false; break }
        delay(500)
    }
}
```

---

## What Is Handled Correctly

(See Section 2 in the companion audit report)

---

## Test Approach

### Unit Tests (ViewModel state survival)

```kotlin
// AuthViewModelTest.kt

@Test
fun `Given app backgrounded when requiresAuth is true then isAuthenticated resets to false`() {
    authViewModel.setAuthenticated(true)
    authViewModel.onAppBackgrounded()  // simulate ON_STOP
    assertFalse(authViewModel.isAuthenticated.value)
}

@Test
fun `Given process death simulation when ViewModel recreated then isAuthenticated starts false`() {
    // HiltViewModel does NOT survive process death — isAuthenticated must start false
    val freshVm = AuthViewModel(accountRepository, settingsRepository)
    assertFalse(freshVm.isAuthenticated.value)
    // Security property: process death cannot bypass auth gate
}

@Test
fun `Given requiresAuth false when app backgrounded then isAuthenticated unchanged`() {
    // When lock is disabled, backgrounding should not clear auth (no lock = no gate)
    every { settingsRepository.settings } returns flowOf(AppSettings(appLockEnabled = false))
    authViewModel.setAuthenticated(true)
    authViewModel.onAppBackgrounded()
    assertTrue(authViewModel.isAuthenticated.value)
}
```

### Instrumented Tests (Activity recreation)

```kotlin
// MainActivityRecreationTest.kt (Espresso + ActivityScenario)

@Test
fun `Given biometric screen visible when activity rotated then FLAG_SECURE is set after recreation`() {
    val scenario = ActivityScenario.launch(MainActivity::class.java)
    scenario.onActivity { activity ->
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
    scenario.recreate()  // simulate rotation
    scenario.onActivity { activity ->
        // FLAG_SECURE must be restored — gap window should be < 1 frame with L5 fix
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
}

@Test
fun `Given process death when app relaunched from history then auth screen shown`() {
    // Use ActivityScenario with FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
    val intent = Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
    }
    val scenario = ActivityScenario.launch<MainActivity>(intent)
    // Auth screen must be the visible destination — not MainScreen
    onView(withId(R.id.biometric_title)).check(matches(isDisplayed()))
}

@Test
fun `Given ProcessLifecycleOwner when activity recreated N times then only one observer is active`() {
    // Verify observer count via reflection or by checking onAppBackgrounded call count
    val callCount = AtomicInteger(0)
    // Instrument authViewModel.onAppBackgrounded to increment callCount
    // Recreate activity 3 times, trigger ON_STOP, assert callCount == 1
}
```

### Navigation Tests

```kotlin
// NavGraphTest.kt

@Test
fun `Given authenticated user when app backgrounded then navigate to Auth screen`() {
    // Set isAuthenticated = true, trigger onAppBackgrounded, verify navController current destination
    composeTestRule.setContent {
        // Provide fake AuthViewModel where requiresAuth=true
        WoowOdooNavHost(authViewModel = fakeAuthViewModel)
    }
    fakeAuthViewModel.setAuthenticated(true)
    fakeAuthViewModel.onAppBackgrounded()
    composeTestRule.onNodeWithTag("auth_screen").assertIsDisplayed()
}
```

---

## Verification Steps

### Manual

1. Enable App Lock (biometric + PIN).
2. Authenticate to `MainScreen`.
3. Rotate device — verify: (a) no double biometric prompt, (b) auth state preserved (still on Main), (c) FLAG_SECURE not dropped (Recents shows blank).
4. Background the app from `MainScreen`, wait 2 seconds, return — verify auth screen shown.
5. Background while biometric dialog is showing — verify prompt does not zombie-show on resume.
6. Force-stop the app from Settings → relaunch — verify auth gate is shown (process death resets `isAuthenticated`).
7. Change locale in system settings while app is on auth screen — verify no double prompt and auth state unchanged.
8. Change font scale to 200% — verify same as locale change.

### Automated

```bash
# Run unit tests
./gradlew :app:test --tests "*AuthViewModelTest*"

# Run instrumented tests (requires connected device/emulator)
./gradlew :app:connectedAndroidTest --tests "*MainActivityRecreationTest*"

# Check for observer leaks via LeakCanary (already in debug build)
# Launch app, rotate 5 times, check LeakCanary notification for ProcessLifecycleOwner leaks
```

---

## Implementation Phases

### Phase 1 — Critical fixes (before next release)

| Task | File | Effort |
|------|------|--------|
| Fix L1: Move ProcessLifecycleObserver to Application scope or store reference and removeObserver in onDestroy | `MainActivity.kt` | 30 min |
| Fix L2: Add `LaunchedEffect(isAuthenticated, requiresAuth)` in NavHost to imperatively re-navigate on auth loss | `NavGraph.kt` | 45 min |
| Fix L5: Add `android:configChanges` to MainActivity | `AndroidManifest.xml` | 10 min |

### Phase 2 — Medium risk (next sprint)

| Task | File | Effort |
|------|------|--------|
| Fix L3: Change `LaunchedEffect(Unit)` to `LaunchedEffect(activity)` | `BiometricScreen.kt` | 15 min |
| Fix L4: Expose `cancelPendingAuthentication()` on `BiometricPromptHelper` and call on disposal | `BiometricPromptHelper.kt`, `BiometricScreen.kt` | 30 min |
| Fix L6: Set FLAG_SECURE globally in MainActivity.onCreate | `MainActivity.kt`, `BiometricScreen.kt`, `PinScreen.kt` | 20 min |

### Phase 3 — Low risk / quality (backlog)

| Task | File | Effort |
|------|------|--------|
| Fix L7: Unify lockout initial state into LaunchedEffect | `PinScreen.kt` | 20 min |
| Fix L8: Migrate to type-safe `@Serializable` navigation routes | `NavGraph.kt` | 2 hours |
| Add unit tests for ViewModel state survival | New test file | 2 hours |
| Add instrumented tests for Activity recreation | New test file | 3 hours |
