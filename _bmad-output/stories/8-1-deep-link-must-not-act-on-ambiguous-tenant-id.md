# Story 8-1 — A push deep link must never switch accounts on an ambiguous identity

- **Status:** ready-for-dev
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

- A confirmation dialog when a deep link would switch to a non-active account. Reasonable, not
  minimal, and not a blocker.
- Make `odoo_tenant_id` unguessable server-side (a random per-install id rather than the db name).
  That is the real fix for §13.3's residual risk and it belongs in the plugin/central repos.

## Dev Agent Record
_(to be filled during implementation)_

## Change Log
- 2026-08-03: Authored from the party-mode round. Mechanism corrected (user tap, not silent push);
  consequence escalated (no attacker needed, and the switch is destructive). The unregister-on-switch
  was found to be invalidated by the server's `UNIQUE(fcm_token, user_id)` + DISTINCT dedupe, so it
  moved from "security liability" to "actively wrong".
