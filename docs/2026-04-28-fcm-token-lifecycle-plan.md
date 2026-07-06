# FCM Token Lifecycle — Comprehensive Audit & Fix Plan

> **Date:** 2026-04-28
> **Trigger:** Real bug discovered in production E2E run (E2E-12b).
> User logs in → Odoo's `woow.fcm.device` table never receives the device
> token → user receives no chatter / DM / @mention / activity push
> notifications. Single Android record in Odoo dates from 2026-03-29 with a
> stale token.
> **Scope:** Beyond the immediate fix, audit every code path that touches
> the FCM token to find latent issues. Mobile-security agent review pass
> performed; this plan combines those findings with my own audit.

---

## 1. The lifecycle, end-to-end

```
                   ┌──────────────────────────────┐
                   │ Firebase SDK                 │
                   │  - onNewToken(token)         │
                   │  - getToken() [on demand]    │
                   └──────────────┬───────────────┘
                                  │
                                  ▼
                   ┌──────────────────────────────┐
                   │ WoowFcmService               │
                   │  - onNewToken → register*    │
                   │  - onMessageReceived → notif │
                   └──────────────┬───────────────┘
                                  │
                                  ▼
                   ┌──────────────────────────────┐
                   │ FcmTokenRepositoryImpl       │
                   │  - registerTokenForAllAcc()  │
                   │  - registerToken(accId, t)   │
                   │  - unregisterToken(accId)    │
                   │  - getStoredToken()          │
                   └──────────────┬───────────────┘
                                  │
                  ┌───────────────┼───────────────┐
                  ▼               ▼               ▼
       ┌────────────────┐ ┌──────────────┐ ┌─────────────────────┐
       │ EncryptedPrefs │ │ AccountDao   │ │ HTTP POST →         │
       │  saveFcmToken  │ │  getAllAcc() │ │  /woow_fcm_push/    │
       │  getFcmToken   │ │              │ │  register|unregister│
       └────────────────┘ └──────────────┘ └─────────────────────┘

Triggers (forward direction):
  authenticate(success)  → registerSavedFcmToken(accId)
  switchAccount(success) → registerSavedFcmToken(accId)
  onNewToken(t)          → registerTokenForAllAccounts(t)

Triggers (reverse direction):
  logout(accId)          → unregisterToken(accId)   ← present
  removeAccount(accId)   → unregisterToken(accId)   ← MISSING (HIGH)
  switchAccount(prevId)  → unregisterToken(prevId)  ← MISSING (MED)
  uninstall              → (Firebase invalidates token; server eventually
                            bounces — no client-side action)
```

---

## 2. Catalogued issues

Numbered in priority order. Severity: HIGH (can cause silent feature
failure or data leak) / MED (degrades multi-account or race scenarios)
/ LOW (efficiency / hygiene).

### 2.1 — HIGH (FIXED in this PR)
**Forward asymmetry: `authenticate` did not call register**

- File: `AccountRepository.kt`
- Status: ✅ FIXED — `authenticate()` and `switchAccount()` now call
  `registerSavedFcmToken(accountId)` after successful login. Replays the
  token saved by `WoowFcmService.onNewToken` when no accounts existed.
- Test coverage: `LoginRegisterTest.kt` (5 tests), `FcmTokenEmptyAccountsTest.kt` (2 tests)

### 2.2 — HIGH (FIX IN THIS PR)
**Reverse asymmetry: `removeAccount` skips `unregisterToken`**

- File: `AccountRepository.kt:168-171`
- Symptom: After `removeAccount`, server still has an active
  `woow.fcm.device` record. If that account's Odoo user is later assigned
  to someone else, stale notifications can be delivered to this device.
- Fix: Mirror `logout()`'s `fcmTokenRepository?.unregisterToken(accountId)`
  call before `accountDao.deleteAccountById(accountId)`.
- Test: new `RemoveAccountUnregisterTest.kt` (2 tests).

### 2.3 — MED (FIX IN THIS PR)
**`switchAccount` does not unregister from previous account**

- File: `AccountRepository.kt:80-103`
- Symptom: User A active. User B switched to. Server-side, BOTH accounts
  still have this device's token registered. If User A's Odoo backend
  posts a push, this device receives User A's notifications even though
  User B is now logged in. Cross-account notification bleed.
- Fix: At start of `switchAccount`, capture the previously-active account
  id (`accountDao.getActiveAccountOnce()?.id`). After successful re-auth
  to the new account, call `unregisterToken(previousId)` for the old one
  — symmetric pair of the new register call.
- Test: extend `LoginRegisterTest.kt` with `switchAccount` previous-acc
  unregister assertion.

### 2.4 — MED (FIX IN THIS PR)
**No `Mutex` protecting the register/save critical section**

- File: `FcmTokenRepositoryImpl.kt:58, 94`
- Race: `WoowFcmService.onNewToken` and `AccountRepository.authenticate`
  both call into FcmTokenRepository on `Dispatchers.IO`. IO is a thread
  pool — they CAN run concurrently. Two POSTs in flight, last-write-wins
  on Odoo's `woow.fcm.device` upsert. If onNewToken arrives slightly
  later with a fresher token, but its POST resolves earlier, the older
  token can become the active server-side record.
- Fix: `private val registrationMutex = Mutex()` in FcmTokenRepositoryImpl.
  Wrap `saveFcmToken + accountDao read + per-account register POSTs` and
  `register-for-single-account` and `unregister` in `registrationMutex.withLock {}`.
- Test: `FcmTokenRaceTest.kt` exercising two concurrent calls and
  asserting both complete without exception (positive path); a future
  test could assert ordering once `Odoo` API contract is observable.

### 2.5 — MED (FIX IN THIS PR)
**Token + account binding for unregister is wrong**

- File: `FcmTokenRepositoryImpl.kt:107` — `unregisterToken(accountId)`
  reads `encryptedPrefs.getFcmToken()` (i.e., the CURRENT device token)
  to send in the unregister POST.
- Race: between the original `register(account=X, token=T1)` and a later
  `unregister(account=X)`, the device token may have rotated to `T2`.
  The unregister POST then sends `T2`, but the server record under `X`
  still references `T1`. Server-side delete-by-token has no effect.
- Fix: Server-side, the Odoo module should treat unregister as
  "deactivate all `woow.fcm.device` rows for `request.env.user.id`" —
  ignore the client-supplied token. The fix is documented as a
  follow-up ticket because it requires backend coordination.
  Client-side, switch unregister to send the account id (server already
  knows the active account from the session cookie) — backwards-compat
  with current Odoo module if it accepts both.
- Test: `RemoveAccountUnregisterTest.kt` includes the rotated-token case.

### 2.6 — LOW (FOLLOW-UP TICKET)
**`registerSavedFcmToken` failure has no retry hook**

- File: `AccountRepository.kt:120-132`
- Symptom: If the register POST fails with a transient network error
  during login, the token is never registered. The user must wait for
  Firebase to issue a new `onNewToken` (could be days) before push
  notifications start working.
- Fix (deferred): On app foregrounding (e.g., `Application.onCreate` or
  `ProcessLifecycleOwner.ON_START`), if `getStoredToken()` returns
  non-null AND there's an active account, call
  `registerSavedFcmToken(active.id)` once. Idempotent server-side.

### 2.7 — LOW (FOLLOW-UP TICKET)
**No "pending unregister" state for offline logout**

- File: `AccountRepository.kt:149-155`
- Symptom: User logs out while offline. `unregisterToken` POST fails.
  Server-side, the `woow.fcm.device` record stays active. If the user
  hands the device to someone else, the old account keeps receiving
  notifications until Firebase invalidates the token.
- Fix (deferred): Maintain a small "pending_unregister" set in
  EncryptedPrefs of `(serverUrl, accountId, token)` triples. On next
  successful auth (any account), drain the pending set and POST each
  unregister. Coordinate with the password-removal path so we keep
  enough state to construct the unregister POST.

### 2.8 — LOW (FOLLOW-UP TICKET)
**`fcmTokenRepository` is a mutable `var` set post-construction**

- File: `AccountRepository.kt:27`
- Symptom: Fragile — if the field is null when `registerSavedFcmToken`
  is called, the registration silently no-ops. Comment explains the
  circular DI dependency (AccountRepository ↔ FcmTokenRepository ↔
  AccountDao) but the workaround is brittle.
- Fix (deferred): Refactor to inject `Provider<FcmTokenRepository>`
  (Dagger lazy injection). Both can resolve when needed without
  construction-time coupling.

### 2.9 — LOW (FOLLOW-UP TICKET)
**Cross-server multi-account: inactive account gets 401**

- File: `FcmTokenRepositoryImpl.kt:64-70`
- Symptom: User A (server X) and User B (server Y) both saved as
  accounts (one active, one inactive). `registerTokenForAllAccounts`
  POSTs to BOTH servers. Only the active account has a session cookie;
  the inactive account's POST returns 401, gets logged as failure,
  `lastError` set. Result is `Result.failure` even though the active
  account was registered correctly.
- Fix (deferred): Either filter to only the active account in
  `registerTokenForAllAccounts`, or accept partial success and only
  surface failure when ALL accounts failed.

### 2.10 — LOW (FOLLOW-UP TICKET)
**`getStoredToken()` is non-suspending but does disk I/O**

- File: `FcmTokenRepositoryImpl.kt:122`
- Symptom: SharedPreferences read on calling thread; if a UI handler
  ever calls this directly, that's a Strict Mode violation.
- Fix (deferred): Make `suspend`, dispatch to IO. All current callers
  are already suspend-context.

### 2.11 — LOW (FOLLOW-UP TICKET)
**No telemetry for silent FCM failures**

- Symptom: All FCM-related failures are `Timber.w` only. In production,
  silent feature failures (like the bug that triggered this audit) are
  invisible until users complain.
- Fix (deferred): Wire to a lightweight server endpoint that records
  "register-failed" / "unregister-failed" events with anonymized
  account id and error class, no token or PII.

### 2.12 — LOW (FOLLOW-UP TICKET)
**No handling for FirebaseMessaging.getInstance().getToken() on cold start**

- Symptom: After `pm clear`, EncryptedPrefs is empty. `onNewToken` may
  not fire on cold start (FCM only fires it on rotation or first
  install). The app would then have no token until a rotation occurs.
- Fix (deferred): On first foreground entry (`ProcessLifecycleOwner.ON_START`)
  if `encryptedPrefs.getFcmToken() == null`, call
  `FirebaseMessaging.getInstance().getToken().addOnSuccessListener { … }`
  and feed it through `registerTokenForAllAccounts`.

### 2.13 — LOW (FOLLOW-UP TICKET)
**No protection against POST_NOTIFICATIONS revocation**

- Symptom: User revokes POST_NOTIFICATIONS in OS Settings after login.
  `importance=NONE`. Pushes silently dropped. App has no UI affordance
  showing "notifications are disabled".
- Fix (deferred): Add `NotificationManagerCompat.areNotificationsEnabled()`
  check in Settings screen with a "Open OS Settings" CTA when disabled.
  This is a UX improvement, not a security bug.

---

## 3. Test plan additions

### Unit tests in this PR

| Test class | Cases | Asserts |
|---|---|---|
| `LoginRegisterTest.kt` (existing in this PR) | 5 | login/switch trigger register; failure paths don't |
| `FcmTokenEmptyAccountsTest.kt` (existing in this PR) | 2 | empty accounts: token saved, no POST |
| `RemoveAccountUnregisterTest.kt` (NEW) | 2 | removeAccount calls unregisterToken; failure non-fatal |
| `SwitchAccountUnregisterTest.kt` (NEW) | 2 | switchAccount unregisters previous active account |
| `FcmTokenRaceTest.kt` (NEW) | 2 | concurrent register calls don't crash; mutex blocks reentry |

### Device tests

Add to `scripts/verify-on-device.py`:
- `V27a` — after `pm clear` + login, query Odoo `woow.fcm.device`
  asserts at least one record exists with current device token within
  10 seconds of login.
- `V27b` — after switch to a second account, assert the previous
  account's record `active=False` AND the new account's record `active=True`.
- `V27c` — after `removeAccount`, assert the server-side record is
  deactivated.

### E2E

`scripts/e2e-production-test.py` E2E-12b/13a should now PASS without
modification — the fix addresses the root cause they were detecting.

### Switch-account E2E (NEW — V28, Android counterpart of iOS UX-68)

**Source pattern**: iOS `Woow_odoo_ios/odooUITests/E2E_HighPriority_Tests.swift:968`
— `test_UX68_givenMultipleAccounts_whenAccountSwitched_thenWebViewReloads`
already exists. Android does NOT yet have an equivalent. This plan adds it.

**Why it matters now**: Issues 2.3 and 2.5 above are about FCM token
lifecycle DURING switch-account. Without an automated E2E covering the
switch path, the same "logout-half implemented, login-half missing"
class of bug can recur.

**V28 design** (to land in `scripts/verify-on-device.py` after V27):

```
V28-Cb1aaa75: Switch account — FCM token lifecycle
─────────────────────────────────────────────────
Pre: account A logged in, secondUser/secondPass in TestConfig.plist
     (skip with XCTSkip-equivalent if not configured)

Step 1: Snapshot Odoo woow.fcm.device records for account A.user_id —
        assert >=1 active record exists with current device token
        (already covered by V27a; included here for narrative)

Step 2: Open menu → Settings → Add Account → enter B's credentials.
        Wait for WebView to reload with B's content.

Step 3: Query Odoo woow.fcm.device records for BOTH user_ids:
        - account A's user_id: assert active=False (we unregistered)
        - account B's user_id: assert active=True (we registered)

Step 4: Open menu → Settings → tap account A's row to switch back.
        Wait for WebView to reload with A's content.

Step 5: Re-query woow.fcm.device records:
        - account A's user_id: assert active=True (re-registered)
        - account B's user_id: assert active=False (now unregistered)

Cleanup: logout B, removeAccount B (so the test is idempotent across runs).
```

**Sub-checks** (so failure points to the exact step that broke):
- V28a — Add Account UI flow opens login screen (parallels iOS UX-67)
- V28b — Account B credentials accepted and WebView reloads
- V28c — After switch to B, account A's woow.fcm.device.active = False
- V28d — After switch to B, account B's woow.fcm.device.active = True
- V28e — Switching back to A reverses the active flags
- V28f — Both accounts visible in Settings drawer (multi-account UI)

**Why splitting into V28a–f**: per the existing CLAUDE.md
"Inspect Before Asserting" rule, each sub-check is independently
diagnosable. V28a failure → UI selector issue. V28c failure → real
program bug in `switchAccount` unregister path. Etc.

**Prerequisites added to `scripts/test_config.py`**:
- `ODOO_SECOND_USER` / `TEST_SECOND_USER` — second Odoo login (default
  empty → V28 skips with diagnostic).
- `ODOO_SECOND_PASS` / `TEST_SECOND_PASS` — second password.
- (Optional) `ODOO_SECOND_URL` / `ODOO_SECOND_DB` for cross-server multi-
  account, defaulting to the same server.

**Implementation lands** in this PR's chained commit, since the V28
tests are the proof that the fix for 2.3 actually works on a real device.

---

## 4. Implementation order

1. ✅ DONE — register-on-login (HIGH 2.1)
2. → IN PROGRESS — `removeAccount` unregister (HIGH 2.2)
3. → IN PROGRESS — `switchAccount` previous-account unregister (MED 2.3)
4. → IN PROGRESS — Mutex protection (MED 2.4)
5. → AFTER FIXES — unit tests for 2.2 / 2.3 / 2.4
6. → THEN — full unit test suite + V05+V26+E2E re-run on device
7. → FOLLOW-UP COMMITS — 2.6 through 2.13 each as small focused PRs
   per the new "Mega-Commit Cap" rule in CLAUDE.md

---

## 5. Verification matrix update

After this PR lands, `docs/plans/2026-03-23-test-plan.md` § "Known
Incomplete" must be updated:

- E2E-12b → ✅ PASSING (fix lands)
- E2E-13a → ✅ PASSING (chain of E2E-12b)
- E2E-14a → still SCRIPT issue (Reduce Motion toggle exists; selector bug)

§ 6 "Coverage Summary" gains a row for "FCM lifecycle (account-event
symmetric)" — Grade A once 2.2/2.3/2.4 land with tests.
