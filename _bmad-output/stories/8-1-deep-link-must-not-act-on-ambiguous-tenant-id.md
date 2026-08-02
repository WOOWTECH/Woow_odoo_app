# Story 8-1 — A push deep link must never switch accounts on an ambiguous identity

- **Status:** review-fixes-applied — WI-1..WI-4 implemented, then seven defects from an adversarial
  review of `36458a3` were fixed, including one where WI-2 as first written **defeated WI-1**.
  Full `:app:testDebugUnitTest` suite green.
- **Repo:** android · branch `dev_spec_drift_refine` (base `d096570`)
- **Covers:** conformance finding **P2-9** (Android half; iOS half is a separate story)
- **Spec of record:** `docs/spec-hprime/2026-05-10-Option-H-Prime-Implementation-Plan.md` §13.3
- **Decided in:** party-mode round 2026-08-03 (Winston / Amelia / Murat)
- **Sequencing:** land **before** story 8-2. This story is 100% verifiable offline; 8-2 is gated on a
  server question nobody can answer right now. Do not let 8-2 hold this one in a branch.

> ⛔ **BINDING CONSTRAINT — no live verification is possible.** Remote server SSD replacement.
> Unit tests and static verification ONLY. Anything needing a server is marked 待伺服器恢復後驗證.

---

## The finding, corrected

The audit said "an unauthenticated FCM data payload drives account switching plus an authenticated
WebView navigation". **The mechanism as stated is wrong and the finding gets dismissed on that
technicality if we repeat it.** `NotificationHelper.kt:54-59` builds a `PendingIntent`; it fires only
on a **user tap**, and `handleDeepLinkIntent` runs from `onCreate`/`onNewIntent`
(`MainActivity.kt:98, 113`). A push on its own switches nothing.

**The consequence, however, was understated — and it needs no attacker at all.**

`DeepLinkRouter.kt:79`:

```kotlin
val target = accounts.firstOrNull { it.tenantId == tenantId }
```

`odoo_tenant_id` defaults to the Odoo **database name**, and §4.3 ships every box with the same
`POSTGRES_DB` unless an operator overrides it. Two customer servers therefore produce two local
accounts with an **identical `tenantId`**, and `firstOrNull` makes routing depend on DAO row order.
A perfectly legitimate push from server Y routes to server X's account. That is not an exploit; it is
the default deployment behaving as written.

And the side effect is worse than mis-navigation. `applyResolvedDeepLink` (`MainActivity.kt:180-190`)
calls `switchAccount`, which **unregisters the previously-active account's FCM token server-side**
(`AccountRepository.kt:127`). So one tap on a mis-routed notification **kills push for account A**.
A peer server gains a denial-of-notifications primitive against an account it has nothing to do with.

### The unregister-on-switch is now actively wrong, not merely a liability

`AccountRepository.kt:102-124` justifies it because "both accounts remain active in
`woow.fcm.device`" — true under the old one-row-per-token schema. The plugin now has
**`UNIQUE(fcm_token, user_id)`** and the send path **DISTINCT-dedupes by token**, so one token
legitimately maps to many users and the device still receives exactly one push. The premise the
unregister rests on no longer holds.

---

## Work items

### WI-1 — `DeepLinkRouter.route` must refuse an ambiguous tenant id
Replace `firstOrNull` with a uniqueness check: more than one account matching → `Drop`. This is a
one-line semantic change in a pure function and it converts every collision from *mis-route* to
*no-route*. That is the correct safety trade: on colliding deployments deep links stop navigating
rather than navigating to the wrong tenant.

### WI-2 — refuse to persist a colliding tenant id
`FcmTokenRepositoryImpl.kt:241-245` writes the registration response's tenant id onto the account
row. Do not write a `tenantId` already owned by a **different** account row; log it instead. Keeps
the ambiguity out of the database, and makes it visible rather than latent.

### WI-3 — remove the unregister side effect from the account-switch path
Delete the `unregisterToken(previousActiveAccountId)` call from `switchAccount`
(`AccountRepository.kt:127`), and **rewrite the comment block at `:102-124`** — leaving a stale
rationale in place is how this comes back. Explicit user-initiated logout keeps its unregister
(`:224`, `:279`): that one is honest logout and is not in scope here.

### WI-4 — correct the finding's wording where it is recorded
The TODO doc and any PR text must say "a user tap on a push notification", not "a push payload
drives". Overstating the mechanism is how a real finding gets closed as invalid.

---

## Acceptance criteria

- **AC1** Two accounts sharing a `tenantId` → `route` returns `Drop`, naming ambiguity as the reason.
- **AC2** Exactly one account matching a `tenantId` → unchanged behaviour (`SwitchAndApply`).
- **AC3** No account matching → unchanged (`Drop("unresolved tenant id")`).
- **AC4** Missing/blank tenant id (old plugin) → unchanged (`ApplyToActive`). Legacy must not regress.
- **AC5** `switchAccount` does **not** call `unregisterToken` for the previously-active account.
- **AC6** Explicit logout still **does** call `unregisterToken` — honest logout is not weakened.
- **AC7** A registration response whose tenant id is already owned by another account does not
  overwrite it, and the collision is logged.

## Test plan — every row states HOW it was verified

`Verified how` may only be `Unit (hermetic)` / `Static reading` / `待伺服器恢復後驗證`. No blanks.

| Claim | Verified how |
|---|---|
| AC1 ambiguous → Drop | Unit (hermetic) — `DeepLinkRouterTest.kt`, pure function |
| AC2/AC3/AC4 unchanged paths | Unit (hermetic) — regression rows in the same suite |
| AC5 switch does not unregister | Unit (hermetic) — `SwitchAccountUnregisterTest.kt`, fake `FcmTokenRepository` |
| AC6 logout still unregisters | Unit (hermetic) — same suite |
| AC7 colliding tenant id not persisted | Unit (hermetic) — fake `AccountDao` |
| Two deployed boxes really do emit the same `odoo_tenant_id` | 待伺服器恢復後驗證 |
| E2E: server Y push → tap → no cross-account switch | **Absent** — see below |

**Every new test must be demonstrated RED before the fix, and the red output recorded here.** A P0/P2
regression test never observed failing is not a regression test.

## Coverage that does NOT exist (do not describe it as pending)

`app/src/androidTest/` is **empty**. Instrumentation dependencies (espresso, uiautomator) and a
`testInstrumentationRunner` are configured in `app/build.gradle.kts:25,151-155`, but there is no
source. Verified absent from the working tree, the index, all branches, and git history. Earlier
notes in this project referred to `LogoutE2ETest.kt` / `DeepLinkE2ETest.kt` (~710 lines) as
"untracked" — **they do not exist anywhere**, so E2E logout and deep-link coverage is **absent, not
pending**. If those files resurface from another machine, treat them as a new story with its own
review, never as pre-existing coverage.

## Explicitly NOT proven by this story

WI-1 is correct **whether or not** two boxes actually collide — it converts an ambiguous identity
into a refusal, which is right in either case. But the claim "customers are affected today" rests on
§4.3's shared `POSTGRES_DB` and has not been observed on live boxes: 待伺服器恢復後驗證.

## Follow-ups

- **🔴 The routing key cannot identify an account, and this story does not fix that.** `odoo_tenant_id`
  is the database name, so two users on one database share it and their deep links are now dropped —
  a real functional loss for a supported configuration, accepted because the alternative is opening
  the wrong user's session. The server must stamp something account-scoped on the payload (the
  registration response already returns `device_id`; the send path knows the recipient's `user_id`).
  **This is the actual fix for P2-9 and it lives in the plugin, not here.**
- Narrowing the blast radius honestly: §4.3's shared `POSTGRES_DB=odoo18_ecpay` is the **STB** path.
  `deploy/deploy-tenant.sh:41` derives a per-tenant database, so the k8s path does not collide
  box-to-box. The two-users-one-database case above collides on every deployment path.

- A confirmation dialog when a deep link would switch to a non-active account. Reasonable, not
  minimal, and not a blocker.
- Make `odoo_tenant_id` unguessable server-side (a random per-install id rather than the db name).
  That is the real fix for §13.3's residual risk and it belongs in the plugin/central repos.

## Dev Agent Record

### Implementation notes

**WI-1 — `DeepLinkRouter.route`.** `firstOrNull` replaced with a `filter` + size check; more than one
match returns `Drop("ambiguous tenant id (N accounts share it)")`. Pure function, no Android
dependency, so the test is exact rather than approximate.

**WI-2 — colliding tenant ids are not persisted.** New `AccountDao.countAccountsWithTenantId(tenantId,
excludingId)`. This is a `@Query` addition only — no schema change, so no Room migration. The existing
`getAccountByTenantId` has the same `LIMIT 1` non-determinism this story removed from the router; it
has **zero callers**, so it is not a second live hole, but its KDoc now warns against using it to
resolve a routing target.

**WI-3 — the unregister-on-switch is gone.** The removed block's own comment documented the
cookie-jar-by-host ordering workaround, which is why it was written; the reason it can now be deleted
outright is that its premise is void. Two independent confirmations, both verified in code rather than
assumed: the plugin has `UNIQUE(fcm_token, user_id)` with DISTINCT dedupe on send, and
`registerTokenForAllAccounts` (`FcmTokenRepositoryImpl.kt:96`) registers **every** account
unconditionally — so the switch was deleting a row the next token refresh or cold-start replay put
straight back. The replacement comment states both, so nobody restores it without re-checking the
server constraint.

**WI-4 — the finding's wording** was corrected in `hprime-conformance-todo.md` (monorepo `a0f8cf4`).

### Tests — and one deliberate reversal

All new tests were demonstrated RED first:

| Test | RED evidence |
|---|---|
| ambiguous → Drop | `AssertionFailedError at DeepLinkRouterTest.kt:184` |
| row order must not decide the target | `AssertionFailedError at DeepLinkRouterTest.kt:204` |
| colliding tenant id not persisted | `FcmTokenRepositoryTest > ...not persisted() FAILED` (1 of 23) |

The ambiguity test asserts the property **negatively** — for BOTH row orders, no `SwitchAndApply` may
be produced. A test asserting "it picks X" would have passed with the bug present, because the bug is
that the pick is arbitrary.

**`SwitchAccountUnregisterTest` previously asserted the behaviour WI-3 removes.** Those tests were
rewritten, not deleted, and the class KDoc now records the reversal and why the old rationale no
longer holds. The assertions are **not weakened**: `coVerify(exactly = 0) { unregisterToken(any()) }`
over any argument is strictly stronger than the previous `coVerifyOrder` pinning one call sequence. A
third test in that class became vacuous once the call disappeared (it stubbed a failure on a path that
no longer runs); it was replaced with an AC6 guard asserting that **explicit logout still
unregisters** — the property that genuinely needed protecting after this change.

### Verification

`./gradlew --offline :app:testDebugUnitTest` — BUILD SUCCESSFUL, whole module. No ktlint/detekt config
exists in this repo, so no style gate was run (nothing to run).

### NOT proven — 待伺服器恢復後驗證

- That two deployed boxes actually emit the same `odoo_tenant_id` today. WI-1 is correct either way —
  it converts an ambiguous identity into a refusal — but "customers are affected right now" rests on
  §4.3's shared `POSTGRES_DB` and has not been observed on live boxes.
- E2E: a push from server Y, tapped, must not switch to server X's account. **No instrumentation
  tests exist** (`app/src/androidTest/` is empty — verified against the working tree, index, all
  branches and history), so this is **absent, not pending**.
- That removing the unregister does not cause duplicate notifications in practice. The server-side
  DISTINCT dedupe is what prevents it and it is unit-tested in the plugin repo, but the end-to-end
  behaviour on a real device with two accounts on one server has not been observed.

### File List

- `app/src/main/java/io/woowtech/odoo/data/push/DeepLinkRouter.kt` — modified (WI-1)
- `app/src/main/java/io/woowtech/odoo/data/local/AccountDao.kt` — modified (WI-2 query + warning)
- `app/src/main/java/io/woowtech/odoo/data/repository/FcmTokenRepositoryImpl.kt` — modified (WI-2)
- `app/src/main/java/io/woowtech/odoo/data/repository/AccountRepository.kt` — modified (WI-3)
- `app/src/test/kotlin/io/woowtech/odoo/data/push/DeepLinkRouterTest.kt` — modified (3 new tests)
- `app/src/test/kotlin/io/woowtech/odoo/data/repository/FcmTokenRepositoryTest.kt` — modified (2 new tests)
- `app/src/test/kotlin/io/woowtech/odoo/data/repository/SwitchAccountUnregisterTest.kt` — modified (reversal)

### Code review round — defects found in `36458a3`

| # | Defect | Fix |
|---|---|---|
| 1 | **WI-2 defeated WI-1.** WI-1 detects ambiguity with `matches.size > 1`, so it only fires while BOTH colliding accounts hold the id. WI-2's refusal guaranteed exactly ONE owner — so the router saw a unique match and confidently switched to it. Demonstrated: X registers first and keeps `odoo18_ecpay`, Y is refused and stays null, a push **from Y** yields `SwitchAndApply(acc-X)` and resolves Y's record id in X's database. Without WI-2 the same input returns `Drop`. My comment stated the harm as the benefit: "keeps the OTHER account routable" — routable *by pushes from either server*. | WI-2 now **persists** the colliding id and logs it. A visible collision is refused by WI-1; an invisible one is guessed. The test was inverted accordingly and states why. |
| 2 | **`Drop` breaks a supported configuration, and the routing key is wrong by construction.** `tenant_id_for` resolves to the DATABASE NAME ("one database == one tenant/box"), and accounts are keyed on `serverUrl + database + username` — so two users on ONE database are two accounts that NECESSARILY share the id. `tenantId` names a TENANT; the router selects an ACCOUNT. This class's own fixture (`SwitchAccountUnregisterTest.kt:54-71`) is exactly that shape. | Cannot be fixed client-side: the payload carries no account identity. `Drop` remains right (guessing opens the wrong user's session in the same database), but the loss is now stated in the code, this story and the TODO doc instead of being discovered later. Raised as a follow-up on the server. |
| 3 | WI-2 was write-path-only: the guard sat inside `if (tenantId != account.tenantId)`, so a device already holding a collision never re-evaluated. | Moot once #1 was fixed — persisting is now unconditional, and a device already in the collided state is exactly the state WI-1 detects. |
| 4 | **Three stale rationales**, including the one WI-3's own text demanded be rewritten. Worst: `registerSavedFcmToken`'s KDoc justified its single-account scope by "switch has just unregistered the previous account on purpose" — deleted in the same commit. `DeepLinkRouter`'s justification for `Drop` cited the same deleted call, so the stated reason for the check was void as of the commit introducing it. | All three rewritten. The router's justification now gives the real reasons (#1/#2). `registerSavedFcmToken`'s scope is re-justified on the host-keyed cookie jar, which is the actual constraint. |
| 5 | **The vacuity audit was incomplete** — three tests became tautologies, not one. "No previous active account" and "switch to the already-active account" discriminated only because the removed call was guarded by `previousActiveAccountId != null && != accountId`; with the call gone both assert something universally true and already covered. | Deleted, with a comment recording what they used to prove. Three tautologies in one class is worse than none. |
| 6 | The AC6 test claimed logout is "the only remaining caller" of `unregisterToken`. `removeAccount` also calls it — the story's own WI-3 text cites both. | Corrected, plus an honest note that the guarantee is call-made, not row-gone: the unregister is best-effort and nothing retries it. |
| 7 | Assertion failure messages used `\${...}` — escaped, so they print the template rather than the value at the only moment anyone reads them. | Unescaped. |

**One overclaim of mine corrected:** the commit said the rewritten `SwitchAccountUnregisterTest`
assertions are "strictly stronger". They are not comparable — `exactly = 0` and
`coVerifyOrder { unregister first }` are *contradictory* properties. "Broader over arguments" is
accurate; "strictly stronger" wrongly implies the old guarantee is subsumed. It is reversed, deliberately.

**Also actioned:** the dead `getAccountByTenantId` was **deleted** rather than documented — it was the
same `LIMIT 1` footgun WI-1 removed, left loaded. And the same-host `removeAccount` hazard (the cookie
jar is host-keyed and the server deletes by session `user_id`, so removing a non-active account on a
shared host deletes the ACTIVE account's row) is now recorded on `removeAccount`; the deleted
`switchAccount` block had been its only record in the repo.

## Change Log
- 2026-08-03 (review fixes): Seven defects fixed. The serious one was mine: WI-2 as first written
  guaranteed a single owner for a colliding tenant id, which is precisely the condition under which
  WI-1 cannot detect ambiguity — so the two work items cancelled out and the mis-route returned.
  The review also surfaced that the routing key is wrong by construction (a tenant id cannot name an
  account), which is recorded as a follow-up rather than papered over.
- 2026-08-03 (impl): WI-1..WI-4 implemented, each RED first. The notable decision was WI-3: three
  existing tests asserted the behaviour being removed, so they were rewritten with the reversal and
  its justification recorded in the class KDoc rather than quietly dropped.
- 2026-08-03: Authored from the party-mode round. Mechanism corrected (user tap, not silent push);
  consequence escalated (no attacker needed, and the switch is destructive). The unregister-on-switch
  was found to be invalidated by the server's `UNIQUE(fcm_token, user_id)` + DISTINCT dedupe, so it
  moved from "security liability" to "actively wrong".
