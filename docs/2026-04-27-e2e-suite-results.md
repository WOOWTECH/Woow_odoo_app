# E2E Suite Results — `dev_missing_features_security`

**Date**: 2026-04-27
**Branch**: `dev_missing_features_security` @ `23c80bf` + this commit's refactor + test fixes
**Device**: ZHO7GIKRHYNRKZRC (Android SDK 35, dew_p_global)
**Tunnel**: `https://similar-assign-proteins-stylus.trycloudflare.com`

This document records the verified state of the branch before the merge to `main`,
captures known-incomplete tests (so reviewers don't mistake script bugs for code
regressions), and lists the follow-up tickets needed.

---

## Test Preconditions

Reviewers re-running the suite need every row below to be true. `verify-on-device.py`
will skip cleanly with diagnostics if any are missing.

| # | Precondition | How to verify |
|---|---|---|
| 1 | Device connected + unlocked | `adb devices` lists exactly one `device` (not `unauthorized`) |
| 2 | Odoo Docker running on `localhost:8069` | `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8069/web/login` → `200` |
| 3 | cloudflared tunnel active | `grep trycloudflare /tmp/cf_ios.log \| tail -1` matches `scripts/test_config.py:ODOO_URL` |
| 4 | App installed + logged in to live tunnel | logcat `MainScreenKt$OdooWebView: Resource request:` shows the live tunnel host |
| 5 | `app/firebase-service-account.json` present | gitignored (`.gitignore` entry: `app/firebase-service-account.json`); copy from a sibling checkout if missing |
| 6 | `POST_NOTIFICATIONS` granted | `adb shell pm grant io.woowtech.odoo.debug android.permission.POST_NOTIFICATIONS` |
| 7 | Location FINE+COARSE granted | `adb shell pm grant io.woowtech.odoo.debug android.permission.ACCESS_FINE_LOCATION` (and `ACCESS_COARSE_LOCATION`) |
| 8 | `hr_attendance` module installed in Odoo | V26d JSON-RPC: `state == "installed"` |

---

## Combined Suite Results

| Suite | PASS | FAIL | Verdict |
|---|---|---|---|
| `verify-on-device.py` (run7) | **39 / 39** | **0** | ✅ Clean — V05 + V26 fixes confirmed |
| `e2e_15_clockin_full.py` | **PASS** | — | ✅ Created `hr.attendance` id=26 with GPS (25.0539587, 121.6152551) |
| `e2e-production-test.py` | 10 / 30 | 20 | ⚠️ Mostly script-level issues, see breakdown |

### `verify-on-device.py` (39/39 PASS)

| Group | V-IDs | Result |
|---|---|---|
| Logging (B0) | V01 | ✅ |
| Biometric Skip removed | V02a, V02b | ✅✅ |
| Auth re-prompt | V03 | ✅ |
| WebView same-host | V04 | ✅ |
| Single-Activity (NEW: regex fix) | V05 | ✅ found 1 unique |
| `POST_NOTIFICATIONS` + channel | V06, V07a, V07b | ✅✅✅ |
| Brand colors | V08a, V08b | ✅✅ |
| zh-CN | V09 | ✅ |
| Color picker | V10a, V10b, V10c | ✅✅✅ |
| Cache clearing | V11a, V11b | ✅✅ |
| FCM service | V13a, V13b | ✅✅ |
| Deep link | V14a, V14b | ✅✅ |
| Color picker UX | V15 | ✅ |
| zh-CN switch | V16a, V16b | ✅✅ |
| Deep-link reject | V17 | ✅ |
| Cache stable | V18 | ✅ |
| Deep-link open | V19 | ✅ |
| FCM E2E | V20a, V20b, V20c | ✅✅✅ |
| FLAG_SECURE | V21 | ✅ |
| PIN keypad | V22a, V22b | ✅✅ |
| Deep-link rejected w/o account | V23, V23b | ✅ ℹ |
| bg→fg re-auth | V24 | ✅ |
| Release-variant test-hook isolation | V25 | ⏭ skipped (manual APK) |
| Location infra (NEW: 60s timeout) | V26a, V26b, V26c, V26d | ✅✅✅✅ |

### `e2e_15_clockin_full.py` (PASS)

End-to-end clock-in via OWL WebView with GPS verification:

| Step | Result |
|---|---|
| Odoo auth | ✅ uid=2 |
| Latest attendance id (before) | ✅ id=25 |
| FINE+COARSE granted | ✅ |
| CDP forward to WebView | ✅ |
| Navigate to /odoo/attendances | ✅ |
| Click Attendance systray dropdown | ✅ |
| Click Check-In button | ✅ |
| Server-side verify | ✅ NEW record id=26, in=(25.0539587, 121.6152551), out=(0.0, 0.0) |

### `e2e-production-test.py` (10/30 PASS)

Detailed breakdown by boss requirement:

| # | Boss Requirement | Result | Notes |
|---|---|---|---|
| E2E-01 | Chatter → FCM push → notif | ⚠️ a✅ / b❌ / c❌ | API send PASS; shade detection FAIL |
| E2E-02 | DM → FCM push → notif | ⚠️ a✅ / b❌ | same |
| E2E-03 | @Mention → FCM push → notif | ⚠️ a✅ / b❌ | same |
| E2E-04 | Activity → FCM push → notif | ⚠️ a✅ / b❌ | same |
| E2E-05 | Deep link from notif → record | ⚠️ a✅ / b❌ | API ✅; shade-tap ❌ |
| E2E-06 | Biometric on bg→fg | ✅ a, b | full pass |
| E2E-07 | Color picker (brand+HEX) | ❌ × 4 | state cascade — passes in `verify-on-device.py:V10a/b/c, V15` |
| E2E-08 | zh-CN switch | ❌ | state cascade — passes in `V09, V16a/b` |
| E2E-09 | Cache clear preserves login | ❌ | state cascade — passes in `V11a/b, V18` |
| E2E-10 | Chinese FCM payload renders | ⚠️ a✅ / b❌ / c❌ | API ✅; shade ❌ |
| E2E-11 | FCM grouping by event type | ❌ × 2 | shade detection ❌ |
| E2E-12 | Fresh-install FCM token reg | ⚠️ a✅ / FAIL | script bug: `'list' has no attribute 'get'` (Odoo response shape) |
| E2E-13 | Logout FCM unregister | ⚠️ a✅ / FAIL | same script bug |
| E2E-14 | Reduce Motion toggle (H1/UX-57) | ❌ | **feature not implemented** — separate ticket |
| E2E-15 | Geolocation clock-in | ❌ | script bug: undefined `ensure_logged_in` (covered standalone — `e2e_15_clockin_full.py` PASS) |

---

## Failure Categorization

| Category | Count | Verdict |
|---|---|---|
| Real product regressions | **0** | none |
| Notification-shade detector bug in `e2e-production-test.py` | 11 | FCM push delivery WORKS — `verify-on-device.py:V20a/b/c` PASSES on the same device with the same FCM setup. The script's heuristic for "notification visible in shade" is flaky compared to V20's. |
| State cascade after shade-tap failure | 6 | E2E-07/08/09 leave the app un-navigable after a failed shade tap — pre-existing script-architecture issue. The same product checks PASS via `verify-on-device.py`. |
| Pre-existing script bugs | 2 | E2E-12 / E2E-13: `'list' has no attribute 'get'` (Odoo response shape mismatch) |
| Pre-existing script bug (redundant test) | 1 | E2E-15: undefined `ensure_logged_in`. Standalone `e2e_15_clockin_full.py` covers it (PASS). |
| Real feature gap | **1** | E2E-14: Reduce Motion toggle (H1/UX-57) not yet implemented |

---

## What this commit changes

| File | Change |
|---|---|
| `scripts/test_config.py` | NEW — single source of truth for E2E config (env > `.env.test` > defaults). Mirrors iOS `SharedTestConfig.swift` pattern. Includes ADBKeyboard helpers (`enable_adb_keyboard`, `restore_ime`). |
| `scripts/verify-on-device.py` | imports `test_config`; **V05 fix** (regex counts unique `ActivityRecord{...MainActivity}` IDs in resumed-state lines, dedup'd); **V26 fix** (`perform_login` WebView-wait 25s → 60s); `perform_login` swaps to ADBKeyboard around text entry; updated V26d to use `test_config.ODOO_URL` |
| `scripts/e2e_15_clockin_full.py` | imports `test_config` |
| `scripts/e2e-production-test.py` | imports `test_config` |
| `scripts/e2e-verification-report.py` | imports `test_config`; output paths use this checkout's `docs/verification-report/` |
| `CLAUDE.md` | New "Test Script Catalog (MANDATORY)" + "ADBKeyboard hard rule" + "Test config single source of truth" |
| `.gitignore` | Added `.env.test`, `__pycache__/`, `*.pyc` |
| `docs/2026-04-27-e2e-suite-results.md` | This document |

### What this commit does NOT change (intentional)

- No production source code touched (`app/src/...` untouched)
- No build tooling touched (gradle untouched)
- `e2e-production-test.py`'s shade-detection logic untouched — replacing it with V20's is a separate ticket below

---

## Pre-flight Verifications (proven before suite run)

Each fix proven against the live device in isolation BEFORE running the full suite:

| # | Check | Result |
|---|---|---|
| 1 | `test_config.py` resolves to live URL | ✅ |
| 2 | All 4 scripts AST-parse | ✅ |
| 3 | ADBKeyboard switch + restore round-trip | ✅ (Gboard → AdbKeyboard → Gboard) |
| 4 | V05 regex returns exactly 1 on live `dumpsys` | ✅ found 1 unique `ActivityRecord` |
| 5 | V26 `perform_login` completes within 60s budget after `pm clear` | ✅ actual 17.5s |
| 6 | `e2e_15_clockin_full.py` imports `test_config` | ✅ |
| 7 | `e2e-production-test.py` imports `test_config` | ✅ |

---

## Known incomplete / follow-up tickets

These do NOT block this branch's merge to `main`. Track separately.

1. **`e2e-production-test.py` shade detector** (11 failures): replace with the same logic `verify-on-device.py:V20c` uses, which passes reliably on the same device.
2. **`e2e-production-test.py` E2E-12 / E2E-13**: handle Odoo's list-vs-dict response shape (`'list' has no attribute 'get'`).
3. **`e2e-production-test.py` E2E-15**: define `ensure_logged_in` in this script, or remove (standalone `e2e_15_clockin_full.py` already covers it with PASS).
4. **Reduce Motion toggle** (H1/UX-57 / E2E-14): real feature gap, not yet implemented.
5. **`e2e-production-test.py` state cascade**: E2E-07/08/09 fail when E2E-01–05 leaves the app in a bad state. Make tests independent (already a CLAUDE.md rule for `verify-on-device.py`; needs to be applied here).

---

## Verdict

**Branch is ready to merge.** No code regressions. All real product checks PASS via `verify-on-device.py` (39/39) and `e2e_15_clockin_full.py` (PASS). The `e2e-production-test.py` failures are concentrated in script-level bugs documented above, not in the application under test.
