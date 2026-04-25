# MainScreen.kt Refactor Backlog

**Date:** 2026-04-25
**Source:** Independent code-architect review during the location-bug investigation
**Status:** Documented — NOT implemented (per user direction "fix location first, refactor later")

---

## Context

The location-permission feature shipped with a bug: every geolocation request was rejected with `no-active-account` because the `WebChromeClient` closure captured a stale `activeHostSnapshot = null`. The fix was 3 lines (`rememberUpdatedState`).

While diagnosing, the architect found **10 additional anti-patterns** of the same class or related architectural smell in `MainScreen.kt`. Most are latent (only manifest on account switch or rare paths). They should be addressed in a focused refactor PR before more features land in this file.

---

## Findings — Same bug class as the location bug

These are stale-closure captures inside `AndroidView { factory }`. The factory runs ONCE; closures created inside it capture parameters at first composition and never see updates.

### F1 — `sessionId` + `serverUrl` in cookie write (HIGH on account switch)
**File:** `MainScreen.kt:317`
**Captured:** `sessionId`, `serverUrl` for `cookieManager.setCookie(...)` inside the `factory` block
**Manifestation:** Cookie set ONCE for the original account. After account switch, the new account's session never reaches the WebView's cookie store → user appears unauthenticated to Odoo.
**Fix:** Move cookie write to a `LaunchedEffect(sessionId, serverUrl)` outside the factory.

### F2 — `serverUrl` in `WebViewClient.shouldOverrideUrlLoading` (HIGH on account switch)
**File:** `MainScreen.kt:392`
**Captured:** `serverUrl` for `URI(serverUrl).host` same-host check
**Manifestation:** After account switch, the same-host check still uses the original server's host → new account's URLs treated as external, opened in system browser, breaks navigation.
**Fix:** Wrap with `rememberUpdatedState(serverUrl)`.

### F3 — `onSessionExpired` callback (MEDIUM)
**File:** `MainScreen.kt:386`
**Captured:** `onSessionExpired` lambda (changes identity each recomposition)
**Manifestation:** Caller's lambda closes over fresh state per recomposition; the captured one is from first composition. State referenced in the lambda body may be stale.
**Fix:** `rememberUpdatedState(onSessionExpired)`.

### F4 — `onLoadingChanged` callback (LOW)
**File:** `MainScreen.kt:324, 373`
**Captured:** `onLoadingChanged` lambda (changes identity each recomposition)
**Manifestation:** Setter writes to an old composition's state holder. Compose tolerates this but it is semantically stale and can mask issues.
**Fix:** Same — `rememberUpdatedState`.

### F5 — Initial `loadUrl(initialUrl)` for `deepLinkUrl` / account URL (HIGH on account switch)
**File:** `MainScreen.kt:605-617`
**Captured:** `deepLinkUrl`, `serverUrl`, `database` for the FIRST `loadUrl` call inside the factory
**Manifestation:** When user switches accounts, the WebView keeps the same instance (see F6 below) and never loads the new account's URL.
**Fix:** Combined with F6 — destroy + recreate WebView on account switch.

---

## Findings — WebView lifecycle / structural issues

### F6 — WebView is reused across account switches (HIGH refactor candidate)
**File:** `MainScreen.kt:127`
**Issue:** `account?.let { ... OdooWebView(...) }` does not key the composable on `account.id`. The same WebView instance survives account switches, carrying the old account's cookies, factory-time state, and stale closures.
**Fix:** `key(acc.id) { OdooWebView(...) }` so the new account forces a new factory invocation.

### F7 — Cookie write inside `factory` (Architectural risk)
**File:** `MainScreen.kt:317`
**Issue:** Side effects in the `factory` block of `AndroidView`. `factory` should construct the View only; mutations belong in `update` or a side-effect.
**Fix:** Move cookie write to a `LaunchedEffect(sessionId, serverUrl)` (also fixes F1).

### F8 — `webView?.destroy()` in `DisposableEffect(Unit)` (HIGH leak)
**File:** `MainScreen.kt:89-93`
**Issue:** Keyed on `Unit`, runs only when `MainScreen` itself leaves composition. WebView is never destroyed on account switch (because the composable isn't keyed — see F6). Memory leak + lifecycle issue.
**Fix:** Tie destroy to the same key as F6.

---

## Findings — ViewModel / state-hoisting issues

### F9 — `MainViewModel.activeAccount` exposed as raw `Flow` (Architectural risk)
**File:** `MainViewModel.kt:33`
**Issue:** `val activeAccount: Flow<OdooAccount?> = accountRepository.activeAccount` requires every consumer to provide an initial value. The null-initial-value class of bugs (the location bug) follows directly.
**Fix:** Promote to `StateFlow`:
```kotlin
val activeAccount: StateFlow<OdooAccount?> =
    accountRepository.activeAccount.stateIn(
        viewModelScope, SharingStarted.Eagerly, null
    )
```
With `Eagerly` + the DAO query starting immediately on ViewModel init, the first emission lands before any UI subscriber arrives, eliminating the null-initial-value race.

### F10 — `MainViewModel.locationPermissionGate` is a public `val` (Architectural risk)
**File:** `MainViewModel.kt:30`
**Issue:** ViewModel exposes a mutable injected dependency to its consumers. Breaks ViewModel encapsulation; tests can mutate via the public field.
**Fix:** Make it private; expose a method `fun resolveLocationPermission(origin: String?, host: String?): Decision` that delegates internally.

### F11 — `consumePendingDeepLink()` called inside `remember { }` (HIGH same-bug-class)
**File:** `MainScreen.kt:125`
**Issue:** `remember` blocks must be pure. Calling a side-effecting consumer mutates external state during composition. On configuration change the deep link can be lost or consumed twice depending on recomposition timing.
**Fix:** Move into `LaunchedEffect(Unit)`.

---

## Findings — Cosmetic / Code smell

### F12 — `onCreateWindow` returns true while `setSupportMultipleWindows(false)` (Code smell + minor security)
**File:** `MainScreen.kt:302, 503-518`
**Issue:** Contradictory configuration — `setSupportMultipleWindows(false)` says "don't allow popups" but `onCreateWindow` is overridden to return `true`. Effective behavior depends on which one Chromium honors. For Odoo 18 hr_attendance the popup case is N/A (verified by source search) but the contradiction should be resolved.
**Fix:** Either set `setSupportMultipleWindows(true)` and properly handle `onCreateWindow`, OR remove the `onCreateWindow` override entirely.

### F13 — `Lifecycle.State` ordinal comparison (Code smell)
**File:** `MainScreen.kt:558`
**Issue:** `lifecycleOwner.lifecycle.currentState < Lifecycle.State.RESUMED` compares enum ordinals. Works, but the documented API is `!isAtLeast(Lifecycle.State.RESUMED)`.
**Fix:** Replace with the documented method.

### F14 — `LaunchedEffect` for synchronous Uri parse (Code smell)
**File:** `MainScreen.kt:83-87`
**Issue:** `LaunchedEffect(account) { activeHostSnapshot = account?.fullServerUrl?.let { ... } }` launches a coroutine for a synchronous, cheap parse. With the location-bug fix landing, this entire block is also now redundant — `rememberUpdatedState(activeHostSnapshot)` does the live read.
**Fix:** Replace with `val activeHostSnapshot = remember(account) { account?.fullServerUrl?.let { Uri.parse(it).host?.lowercase() } }`. Eliminates the LaunchedEffect entirely.

---

## Recommended refactor order (when scheduled)

1. **F9** (StateFlow promotion) — eliminates an entire bug class, low risk
2. **F1, F2, F3, F4, F5** (rememberUpdatedState pattern across all callbacks/state in WebChromeClient and WebViewClient) — same fix pattern, 5 places
3. **F6 + F8** (WebView keying + destroy) — together: forces clean WebView per account, fixes account-switch issues definitively
4. **F11** (deep link consume in LaunchedEffect) — small, isolated
5. **F10** (encapsulate gate access) — small, isolated
6. **F7, F14** (cookie write + Uri parse cleanup) — code cleanup
7. **F12, F13** (cosmetic) — last

Estimated effort: **~1 engineer-day** for items 1–5 (the user-impacting ones); 1 hour for items 6–7.

---

## Why this list now and refactor later

Per user direction during this session: *"I want fix the location problem first, refactor the code later."*

The location bug (P1) is fixed in commit `caea05e` with a 3-line change. The above 14 findings are tracked here so the next refactor sprint has a concrete backlog.

**None of F1–F14 currently break production for our specific test scenarios (Odoo 18 hr_attendance + single account).** They become important when:
- Users switch between accounts in the same session (F1, F2, F5, F6, F8 — HIGH)
- Future features add more closures inside the WebView factory (F1-F5 same-class)
- Maintenance cost as MainScreen.kt continues to grow (F9, F10 — refactor)

---

## Status update — 2026-04-25 (post-fix)

The original location bug (the "instance" that prompted this audit) has been
fixed in commit `caea05e` using `rememberUpdatedState(activeHostSnapshot)`,
the canonical Compose pattern recommended by the architect.

**Verified end-to-end on real device** (Xiaomi 25078PC3EG, Odoo 18 tunnel):

| field | before fix | after fix |
|-------|-----------|-----------|
| `hr.attendance.in_latitude` | 0.0 | 25.0539539 |
| `hr.attendance.in_longitude` | 0.0 | 121.6152575 |
| Gate decision | Reject (no-active-account) | Grant |

Full verification summary in
`docs/2026-04-25-location-permission-design-v2.md` §11.

Findings F1–F14 in this document remain **open** as a separate refactor sprint.
None block the location feature shipping.
