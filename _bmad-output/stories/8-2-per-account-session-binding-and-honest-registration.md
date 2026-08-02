# Story 8-2 — Register under the right account, and tell the truth when the server says no

- **Status:** ready-for-dev (WI-1..WI-3 unblocked; WI-4..WI-6 gated — see the blocking question)
- **Repo:** android · branch `dev_spec_drift_refine` (base `d096570`)
- **Covers:** conformance findings **P0-2** and **P0-3**, plus two hazards found during planning
- **Decided in:** party-mode round 2026-08-03 (Winston / Amelia / Murat)
- **Sequencing:** land **after** story 8-1.

> ⛔ **BINDING CONSTRAINT — no live verification is possible.** Remote server SSD replacement.
> Unit tests and static verification ONLY. Never fake verification, never weaken an assertion.

> 🚧 **BLOCKING QUESTION, gating WI-4..WI-6.** Everything about per-account sessions assumes Odoo
> will hold **two concurrent valid sessions for one host from one device**. If the server's session
> store rotates or invalidates on a second authentication, this design changes shape entirely — the
> answer is not a footnote, it is a gate. 待伺服器恢復後驗證. WI-1..WI-3 (P0-2) do not depend on it
> and should be implemented and merged regardless.

---

## P0-2 — a rejected registration is reported as success

`FcmTokenRepositoryImpl.kt:354-365` checks only the **JSON-RPC envelope** `error`. The plugin returns
validation failures **inside `result`** with HTTP 200 (`controllers/fcm_controller.py:39,43,46`), so
`"Invalid fcm_token format"` is logged as *registered* and returned as `Result.success`. There is no
retry until the next cold start, and `Timber.plant` is DEBUG-only, so this is invisible in production.

**Two premises from the audit were wrong and must not be carried into the fix:**

1. **`{'success': false}` on unregister is NOT a failure.** `unregister_device` returns `False` when
   the caller had no row — which after logout is the **desired** state (`fcm_device.py:250-258`).
   Treating it as an error manufactures a false alarm on every correct logout.
2. **MockWebServer is not blocked.** `FcmTokenRepositoryImpl.kt:52-56`'s primary constructor already
   takes `httpClient: OkHttpClient`, and `FcmTokenRepositoryTest.kt:107-112` already uses it
   (`repoWith(fakeClient { ... })`). **P0-2 is fully testable today with zero production seams added.**

### WI-1 — an endpoint-aware outcome parser
Add a sibling to the existing pure-function `FcmRegistrationResponse.parseTenantId` pattern:
`parseOutcome(body, endpoint) -> Ok | Rejected(reason) | Unparseable`. Checks envelope `error` first,
then `result.error`, then treats a present `result` as Ok.

The endpoint discriminator is **required, not stylistic**: the two endpoints have disjoint
vocabularies — `register` never returns `success`, `unregister` never returns `device_id`. One
endpoint-agnostic parser has to invent a rule for `{'success': false}` and will either swallow real
register failures or fire on every logout. Use a **sealed type**, never a string (`CLAUDE.md`:
"Never use strings for logic").

### WI-2 — per-endpoint severity policy, applied by the callers
`postToOdoo` must stop deciding severity — it returns the body or throws; the callers apply policy:
- **register → fail closed.** Registration is idempotent server-side (the AC3 early return) and
  retries on next cold start, so a false failure costs one POST. A false success costs **push,
  silently, forever**.
- **unregister → tolerant of unparseable, failing on `error`.** Logout must never be blockable by a
  server. `unregisterToken` already wraps in `runCatching{}.onFailure{ log }`, so this changes nothing
  user-visible.

### WI-3 — a rejected registration must not write a tenant id
`FcmTokenRepositoryImpl.kt:241-245` writes `updateTenantId` from the response. A rejection must not
reach it.

---

## P0-3 — the root cause is NOT the FCM cookie jar

The audit located this at the FCM client's host-keyed `CookieJar`. That jar is a symptom.
`OdooJsonRpcClient.kt:29-41`:

```kotlin
private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()   // key = HOST
override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
    cookieStore.getOrPut(url.host) { mutableListOf() }.apply {
        clear()          // <-- destroys the sibling account's session
        addAll(cookies)
    }
}
```

There is **one cookie slot per host, and authenticating account B calls `clear()` on account A's
session**. The FCM jar faithfully reports the only truth this app has. **A fix confined to
`FcmTokenRepositoryImpl` cannot work**, and — the part that matters for review — a test that fakes
`SessionCookieProvider` to hand back a distinct cookie per account is testing **a world that does not
exist** and would be green against a fix that ships broken.

### Hazard A — the re-auth cross-binds accounts (found during planning, not in the audit)
`SessionReauthenticator.kt:202-217` resolves the account by **host** with `firstOrNull`; its own KDoc
admits "the most-recently-used one is chosen". With accounts A (MRU) and B on one host:
B's POST returns the session-expired envelope → the resolver picks **A** → `authenticate(A)` clears
and writes A's cookie → the interceptor replays **B's** request carrying **A's** cookie → the server
creates a row for **A** → the client returns `Result.success` for **B** → and then writes the
response's tenant id onto **B's** row (`FcmTokenRepositoryImpl.kt:241-245`). The routing key used by
story 8-1 is corrupted silently.

### Hazard B — `registrationMutex` does not protect what its KDoc claims
The comment at `FcmTokenRepositoryImpl.kt:78-93` calls the sequence "a single critical section". It
serialises only this class's own callers. `AccountRepository.switchAccount` re-authenticates at
`AccountRepository.kt:145-150` **without touching that mutex**, so a user tapping "switch account"
while an `onNewToken` fan-out runs on `WoowFcmService`'s background scope makes every remaining
account register under the newly-active one. **The comment must be corrected regardless of which fix
ships** — it actively misleads the next reader.

### WI-4 — key the session store by `accountId`, not host  *(gated)*
`ConcurrentHashMap<String /*accountId*/, List<Cookie>>`, with `authenticate()` taking the account id.
Blast radius: `getSessionId/getSessionCookies` (`OdooJsonRpcClient.kt:58-68`),
`AccountRepository.getSessionId/getSessionCookies` (`:293-301`), `clearCookies`
(`AccountRepository.kt:232-233`, `SessionReauthenticator.kt:191-195`).

### WI-5 — select the session per request  *(gated)*
`cookieJar(CookieJar.NO_COOKIES)` on the FCM client, and `postToOdoo` — which **already has the
`account` parameter** and currently uses it only for error strings — sets an explicit `Cookie` header.

**Two traps this WI must handle, both verified:**
- **A request tag cannot work.** `CookieJar.loadForRequest(url: HttpUrl)` receives only the URL; it
  never sees the `Request`. A `ThreadLocal` would work under `execute()` and break silently the day
  anyone switches to `enqueue()`. Rejected.
- **The explicit header is clobbered while the jar is non-empty.** OkHttp's `BridgeInterceptor` runs
  *after* application interceptors and calls `header("Cookie", …)`, which **replaces**. Removing the
  jar from that client is not optional cleanup — it is what makes the fix work at all.
- **The re-auth replay reuses the original headers** (`SessionReauthInterceptor.kt:67-70`), so it
  would carry the stale cookie. The interceptor must re-resolve and overwrite `Cookie` on the replay.

### WI-6 — resolve the re-auth account by account, not host  *(gated)*
Closes Hazard A. Stamp `account.id` as a request tag so the interceptor can resolve it — the tag is
readable from an `Interceptor` (which sees the `Request`), unlike from a `CookieJar`.

---

## Acceptance criteria

- **AC1** A `result`-level rejection on register → `Result.failure`.
- **AC2** `unregister` returning `{"success": false}` → `Result.success` (post-logout desired state).
- **AC3** A rejected register does not write `tenantId`.
- **AC4** Envelope-level `error` still → failure (no regression on `:354-365`).
- **AC5** The parser ignores the `id` field.
- **AC6** *(gated)* Two accounts on one host each carry their **own** session cookie **on the wire**.
- **AC7** *(gated)* A re-auth triggered by account B's request re-authenticates **B**, not the MRU
  account, and the replay carries B's cookie.
- **AC8** The `registrationMutex` KDoc no longer claims protection it does not provide.

## Test plan — every row states HOW it was verified

| Claim | Verified how |
|---|---|
| AC1 register `result.error` → failure | Unit (hermetic) — existing `fakeClient` seam |
| AC2 unregister `success:false` → success | Unit (hermetic) — **write this one first**; it is what a well-meaning implementer breaks |
| AC3 rejection does not write tenantId | Unit (hermetic) — fake `AccountDao` |
| AC4 envelope error → failure | Unit (hermetic) — regression row |
| AC5 parser ignores `id` | Unit (hermetic) |
| The store loses the sibling's cookie (`OdooJsonRpcClient.kt:34`) | Unit (hermetic) — **characterization test, write before any fix** |
| AC6 per-account cookie reaches the wire | Unit (hermetic) — **MockWebServer `takeRequest()` ONLY** |
| AC7 re-auth binds the right account | Unit (hermetic) |
| Hazard B: concurrent `switchAccount` mid-fan-out | Unit (hermetic) — `TestDispatcher` |
| Odoo accepts B's cookie as B | 待伺服器恢復後驗證 |
| Two device rows exist for two accounts on one server | 待伺服器恢復後驗證 |
| Fixtures match real wire bodies | 待伺服器恢復後驗證 |

### Four binding rules for this story

1. **No P0-3 cookie assertion may be made from an application interceptor.** Verified by probe:
   an app interceptor sees `SET_MANUALLY_BY_POSTTOODOO` while the wire carries `FROM_JAR`. Such an
   assertion is **structurally incapable of failing** and must be rejected in review. Only
   `MockWebServer.takeRequest()` or an `addNetworkInterceptor` tells the truth.
2. **Every new test must be demonstrated RED before the fix**, with the red output recorded.
3. **Fixture bodies are source-derived, not captured.** Each carries a `plugin file:line` citation.
   Note that `plugin/tests/test_fcm_controller.py:42` **discards the envelope**
   (`response.json().get('result', ...)`), so those tests are authoritative for the inner `result`
   object ONLY and carry zero information about the wrapper — copying a fixture from them would
   exercise a body shape Odoo never emits. They also assert `assertIn('error', result)`, i.e. **key
   presence, not message text**, so the Android taxonomy must key on the `error` key and never on its
   message string.
4. **Do not stub away the thing under test.** `FcmTokenRepositoryTest.kt:50` stubs
   `getCookiesForHost(any())` to `emptyList()` — that stub is precisely why P0-3 was never caught.
   New tests must not inherit it.

**MockWebServer needs one new artifact.** `OdooAccount.fullServerUrl` forces `https://`, so the
server must run TLS: `com.squareup.okhttp3:okhttp-tls` (`HandshakeCertificates`), which is **not in
the local Gradle cache** — it needs a download plus a lock update. **Do not** instead relax
`fullServerUrl` to honour `http://`: that would make production capable of plaintext-downgrading a
session cookie in order to widen a test seam.

## The finding underneath the finding

Three validators, three answers: the HTTP route checks 100–300 chars with no charset
(`fcm_controller.py:42`, the path **Android** uses); the model checks **nothing**
(`fcm_device.py:79`, the path **iOS** uses via `call_kw`); the sender checks 100–500 plus
`^[A-Za-z0-9_:/-]+$` at send time. A 301–500-char token is therefore accepted from iOS and rejected
from Android — a live cross-platform divergence. The model is the single writer of that table and the
layer with zero validation. Validation belongs there, once, with the sender's stricter rule as
canonical. **Raise it as a plugin story, or P0-2 returns wearing a different hat.** Tightening needs a
production row-length audit first: 待伺服器恢復後驗證.

## Follow-ups

- **Server-side: make the controller `raise UserError` instead of returning `{'error': ...}` inside
  `result`.** Odoo's `type='json'` dispatcher then returns the standard envelope error, which **both
  clients already parse correctly** — so it fixes iOS with zero iOS change. That is the fix at the
  right layer; the client work above is the compat shim that makes it safely deployable, since server
  versions cannot be pinned in the field. Every `raise` must sit before any write, as `UserError`
  rolls the transaction back. **Write it, do not merge it** until it can be verified.
- Do **not** normalise `register`'s unwrapped shape to match `unregister`'s wrapper. Both clients'
  tenant-id parsers read keys straight off `result`; wrapping it silently loses the tenant id and
  causes cross-tenant deep-link mis-routing. That is creating a P0 to fix a tidiness complaint.

## Dev Agent Record
_(to be filled during implementation)_

## Change Log
- 2026-08-03: Authored from the party-mode round. P0-3's root cause relocated from the FCM cookie jar
  to `OdooJsonRpcClient`'s host-keyed store (a fix in the original location cannot work). Two hazards
  added that the audit missed. P0-2's testability constraint was found to be wrong — the seam already
  exists — so P0-2 is unblocked and split from the gated P0-3 work.
