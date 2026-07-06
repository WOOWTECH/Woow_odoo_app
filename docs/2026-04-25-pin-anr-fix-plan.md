# PIN-Entry ANR Fix Plan

**Date:** 2026-04-25
**Severity:** P1 (UI freezes 3–6s on every PIN tap; Android shows ANR after 5s)
**Branch target:** `dev_missing_features_security`

---

## 1. The Bug

`AuthViewModel.enterPinDigit` is called synchronously from `PinScreen`'s `NumberKey.onClick`. It calls `SettingsRepository.verifyPin` which calls `pbkdf2Hash(pin, salt)` — **600,000 iterations of HMAC-SHA256 on the main thread**.

Measured on Xiaomi 25078PC3EG (Android 15, mid-range CPU): **3–6 seconds** per `verifyPin` call. MIUI logs `APP_SCOUT_HANG` at 6s; Android shows the ANR dialog at 5s.

The same path runs on `setPin` (PIN setup) and on legacy SHA-256→PBKDF2 migration during `verifyPin`.

### Affected user flows
- Every unlock via PIN (4-digit → ~3s freeze; 6-digit → ~3s freeze; verifyPin runs once per attempt)
- First-time PIN setup
- App-Lock toggle in Settings (calls setPin)
- Test hooks `--es test-pin <pin>` (also calls setPin)

### Why my earlier "fix" was not a fix
During V22 testing I added `time.sleep(10)` after `apply_test_hook(test_pin=...)` so the test wouldn't kill the app mid-hash. **That hid the symptom in tests; the production app still freezes for every real user.** Acknowledged.

---

## 2. Iteration count is correct — DO NOT lower

Per independent mobile-security review:

| Source | Recommendation |
|--------|---------------|
| OWASP Password Storage Cheat Sheet (2024) | PBKDF2-HMAC-SHA256: 600,000 iterations minimum |
| OWASP MASVS v2 / MASTG | Defers to OWASP cheat sheet → same 600K |
| NIST SP 800-63B | Minimum 10K, "should increase over time" |
| iOS PinHasher (peer app) | Also 600K (cross-platform parity) |

For a 4-digit PIN, the iteration count is the **only** offline-attack defence. Lowering to 100K would make a leaked EncryptedSharedPreferences crackable in ~1.7 minutes for a 6-digit PIN on a high-end GPU. Do not lower.

**The fix is threading, not crypto.**

---

## 3. Design

### Convert to suspend functions and dispatch to `Dispatchers.Default`

```kotlin
// SettingsRepository.kt
suspend fun setPin(pin: String): Boolean = withContext(Dispatchers.Default) {
    if (pin.length !in MIN_PIN_LENGTH..MAX_PIN_LENGTH) return@withContext false
    val hash = hashPin(pin)  // PBKDF2 — runs on Default dispatcher
    encryptedPrefs.updatePinHash(hash)
    _settings.value = _settings.value.copy(pinEnabled = true)
    true
}

suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.Default) {
    // existing logic; pbkdf2Hash and legacy SHA-256 both off main thread
    ...
}
```

### Propagate suspend up through AuthViewModel

```kotlin
suspend fun enterPinDigit(digit: String, currentPin: String): Pair<String, PinEntryResult> {
    val nextPin = currentPin + digit
    if (nextPin.length < MIN_PIN_LENGTH) return nextPin to PinEntryResult.NeedMoreDigits
    if (settingsRepository.verifyPin(nextPin)) {  // now suspend
        _isAuthenticated.value = true
        return nextPin to PinEntryResult.Success
    }
    // ... rest unchanged
}
```

### Wire PinScreen via `rememberCoroutineScope`

```kotlin
val scope = rememberCoroutineScope()
val onNumberClick: (String) -> Unit = { num ->
    if (!isLockedOut && !isVerifying) {
        isVerifying = true
        scope.launch {
            val (nextPin, result) = viewModel.enterPinDigit(num, pin)
            pin = nextPin
            handleResult(result)
            isVerifying = false
        }
    }
}
```

### UX: show progress during the 1–3s wait

Add `var isVerifying by remember { mutableStateOf(false) }`. When true, dim the keypad or show a tiny `CircularProgressIndicator` next to the dot row. Even though we're now off the main thread, the **inherent verify cost is still 1–3 seconds** — users need a hint that something is happening.

### TestHooks: same treatment

```kotlin
// TestHooks.kt
fun applyIfPresent(intent: Intent?, settings: SettingsRepository) {
    if (!BuildConfig.DEBUG) return
    if (intent == null || intent.extras == null) return
    // Launch on a coroutine since setPin is now suspend
    GlobalScope.launch {  // or pass in a CoroutineScope
        try {
            val pin = intent.getStringExtra(EXTRA_TEST_PIN)
            if (pin != null && pin.length in 4..6 && pin.all { it.isDigit() }) {
                settings.setPin(pin)
                Timber.tag(TAG).w("Seeded PIN via test hook (DEBUG only)")
            }
            // ... rest
        } catch (t: Throwable) {
            Timber.tag(TAG).e(t, "Test hook threw — ignored")
        }
    }
}
```

Or simpler — pass a `coroutineScope` explicitly so we don't use `GlobalScope`. MainActivity has `lifecycleScope`; call `TestHooks.applyIfPresent(intent, settingsRepository, lifecycleScope)`.

---

## 4. Files to change

| File | Change |
|------|--------|
| `SettingsRepository.kt` | `setPin`, `verifyPin`, `recordFailedAttempt`, anything that calls `pbkdf2Hash` → `suspend fun` with `withContext(Dispatchers.Default)` |
| `AuthViewModel.kt` | `enterPinDigit`, `verifyPin`, `setupPin` → `suspend` |
| `PinScreen.kt` | wrap `enterPinDigit` call in `scope.launch`; add `isVerifying` state and small UI cue |
| `SettingsViewModel.kt` (PIN setup path) | wrap `setPin` calls in `viewModelScope.launch` |
| `TestHooks.kt` | take `CoroutineScope` parameter; launch the suspend `setPin` call inside it |
| `MainActivity.kt` | pass `lifecycleScope` into `TestHooks.applyIfPresent` |
| `SettingsRepositoryPinTest.kt` | tests need `runTest { ... }` for suspend functions |
| `AuthViewModelPinEntryTest.kt` | same |
| `TestHooksTest.kt` | same |

---

## 5. Tests

### Existing tests
- All existing PIN-related unit tests must be migrated to `runTest { ... }` because the functions are now suspend. **Behaviour must not change.**

### New regression test — main-thread non-blocking

```kotlin
@Test
fun `verifyPin does not block the calling thread for more than 100ms`() = runTest {
    val repo = SettingsRepository(fakePrefs)
    repo.setPin("1234")
    val start = SystemClock.elapsedRealtime()
    // Run on a single-threaded test dispatcher that simulates the main thread
    val mainDispatcher = StandardTestDispatcher()
    withContext(mainDispatcher) {
        val deferred = async(Dispatchers.Default) { repo.verifyPin("1234") }
        // Simulate other main-thread work happening in parallel
        var counter = 0
        while (!deferred.isCompleted) {
            counter++
            yield()
        }
        deferred.await()
    }
    val elapsed = SystemClock.elapsedRealtime() - start
    // Real PBKDF2 ~3s but it must NOT have blocked the main dispatcher
    // The test asserts main-thread-side work could run interleaved
    assertTrue(counter > 0, "Main-thread coroutine never got a chance to yield")
}
```

(For unit tests we'll use a fake clock + assert the call site doesn't block. Specifics during implementation.)

### Regression: V22 keypad on real device
Currently V22 takes ~10s per PIN tap because the test waits for PBKDF2. After fix, V22 should still pass and per-tap latency should drop to a small fraction of a second (only the 6th digit triggers verify).

### E2E sanity
After the fix, V22 + V24 should still pass on real device. Re-run them.

---

## 6. UX considerations

- The **first time** the PIN-fix lands, all users with existing PINs will see the same 1–3s wait, just no longer blocking the UI.
- Add `isVerifying` state and a small hint (CircularProgressIndicator next to dots, or fade keypad).
- Existing exponential lockout logic stays unchanged.

---

## 7. Phases

| # | Step | Effort |
|---|------|--------|
| 1 | Convert `setPin`, `verifyPin` (+ helpers) to `suspend` with `withContext(Dispatchers.Default)` | 30 min |
| 2 | Propagate `suspend` into `AuthViewModel.enterPinDigit` and any other callers | 30 min |
| 3 | Wrap `PinScreen` onClick + SettingsViewModel calls in `scope.launch` | 30 min |
| 4 | Add `isVerifying` state and a UI cue | 30 min |
| 5 | Update `TestHooks` to take a `CoroutineScope` parameter; thread through MainActivity | 20 min |
| 6 | Migrate existing tests to `runTest { ... }`; add regression test | 1 hr |
| 7 | Build + unit tests pass | 15 min |
| 8 | Real-device V22/V24 re-run; manual PIN entry confirms no ANR | 30 min |

**Total: ~4 hours**

---

## 8. Acceptance Criteria

1. `./gradlew :app:testDebugUnitTest` — all tests pass (incl. migrated suspend tests)
2. `./gradlew :app:assembleDebug` — green
3. Real-device V22 PIN entry — no MIUI `APP_SCOUT_HANG` warning in logcat
4. Real-device PIN tap latency: digits 1–5 instant; digit 6 verification ≤ 4s with UI hint visible
5. Per OWASP/NIST: `PBKDF2_ITERATIONS = 600_000` UNCHANGED (no security regression)
6. New regression test asserts main-thread non-blocking
7. TestHooks `--es test-pin 1234` still works (now async); test script's 10s wait can be reduced

---

## 9. Risk

| Risk | Mitigation |
|------|------------|
| `enterPinDigit` becomes suspend — breaking change for any other caller | Search codebase for `enterPinDigit` callers (only PinScreen exists per grep) |
| PinScreen rapid taps could fire overlapping coroutines | Guard with `isVerifying` state — ignore taps while a verify is in flight |
| Test hook becomes async — script may snapshot state before persist | TestHooksTest covers this; existing 10s wait in test script is more than enough |
| `GlobalScope` anti-pattern in TestHooks | Use injected `CoroutineScope` (lifecycleScope from MainActivity) |
| UI flicker if "isVerifying" hint flashes on instant rejects | Only show hint after 200ms (debounce); for 4-digit PIN the verify is the same cost regardless |

---

## 10. What this fix does NOT change

- `PBKDF2_ITERATIONS = 600_000` — UNCHANGED (security-mandated)
- Salt size, hash output size — unchanged
- Lockout schedule (30s → 5m → 30m → 1h) — unchanged
- Storage format (`pbkdf2:iterations:salt:hash`) — unchanged
- Legacy SHA-256 → PBKDF2 migration logic — unchanged (just runs off main thread now)
