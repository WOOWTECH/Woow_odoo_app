# Round7 connectivity observation budget — offline candidate only

## Scope / status

No ADB, AVD, build, network, actual process signals, install, branch change, staging, commit or push was performed. Runtime execution remains **NOT AUTHORIZED / NOT VERIFIED**. Independent review and subsequent parent authorization are required before any runtime attempt. This is a single bounded observation budget adjustment, **not a demonstrated root-cause fix**.

New tool copy: `docs/verification-report/apporo-login-localization/round7-connectivity-budget/`. Historical round6 and product sources were not edited. The launcher's future evidence destination names round7; that runtime destination was not created or used.

## Historical evidence inspected (read-only)

Research root: `/Users/elmolin/WOOW-mobile-app-analysis/research/apporo-odoo/simulator-validation/android`.

- `round6-system-ui-wait/commands.jsonl`, line 58: full `shell dumpsys connectivity`, `exit=null`, `timeout=true`, `seconds=4.01`; captured partial stdout contains `Active default network: none` but stops during network offers. This is **not successful connectivity evidence**.
- The corresponding receipt records `failed-or-blocked`, no app launch/IME change/new dump/install, and historical cleanup resolved in 73.764s. No historical runtime was repeated.
- Prior complete exit0/non-timeout dumps in round4 and round5 have ordered connectivity sections and end with `Multicast routing supported`, `Background firewall chain enabled`, `IngressToVpnAddressFiltering`. Round5's first complete dump is used as a read-only parser fixture. This is existing evidence, not a new runtime observation.

## Minimal behavioral increment

`incremental.patch` is the complete Python delta against round6; other Python files are exact copies.

1. New `connectivity.py`: exactly one full `dumpsys connectivity` invocation, dedicated maximum **20s**, clamped to the existing boot/UI `work_deadline`; no invocation once exhausted, and no acceptance at/after the phase deadline. No retries, alternate commands, fallback or partial-output acceptance.
2. PASS requires explicit exit0 and `timeout=False`, no stderr/error/truncation flag, the observed full-dump section/trailer structure, and exactly one top-level `Active default network: none` in the expected location. Unknown, conflicting, duplicated, diagnostic or truncated output fails closed. Version/format changes may conservatively block and must be reviewed, not bypassed.
3. Both boot connectivity gates and the pre-IME/UI gate use that same helper. Launcher's default command/ADB maximum stays **4s**. Only its future evidence-directory name also changes to round7.
4. Existing boot260/UI720/cleanup40 and max1020 budgets remain unchanged. Cleanup implementation is byte-identical. Installed two-artifact full pull/hash/EOF/metadata gate, root, phone, readiness and exact-once System UI Wait helper are byte-identical. UI driver changes only its connectivity import/call. No product sources changed.

## Offline tests

| Run | Passed | Failed / errors | Evidence |
|---|---:|---:|---|
| Original round6 baseline | 69 | 0 / 0 | `tests-round6-baseline.log` |
| Round7 final (69 retained + 18 new) | 87 | 0 / 0 | `tests.log` |
| Round7 shuffled, seed7 | 87 | 0 / 0 | `tests-shuffled.log` |

No skipped tests in these runs. New `test_connectivity.py` covers full success after 12s (>4 and <20), timeout with even full `none` stdout, actual historical partial receipt (including falsely relabelled exit0), nonzero, truncated/missing/unknown/duplicate/conflicting states, deadline clamping/no-start/late result, monitor stop, untouched4s defaults, actual cleanup code under mocked effects including40s/no-extension, and integration across all three gates with failure injected at each. Existing launcher success mock was updated to provide a complete synthetic dump and explicit timeout flag; no original tests were removed.

Initial run: 87 tests, 3 failures + 2 errors (`tests-initial-parser-errors.log` retained). The initial diagnostic regex incorrectly matched normal `mDefault*InactivityTimeout` fields in the complete historical dump. Added word boundaries around the timeout diagnostic term; final and shuffled suites are fully passing. This was an offline parser issue, not a runtime finding.

### Reproducible guarded test command

Executed from repository root. Host Python/unittest only; all process-launch, network socket, signal and thread-start primitives below default to fail-closed mocks. Individual test cases replace these guards only with their own fake implementations.

```bash
python3 -B - <<'PY' > docs/verification-report/apporo-login-localization/round7-connectivity-budget/tests.log 2>&1
from contextlib import ExitStack
from pathlib import Path
import sys, unittest
from unittest.mock import patch
path=Path('docs/verification-report/apporo-login-localization/round7-connectivity-budget').resolve()
sys.path.insert(0,str(path))
with ExitStack() as stack:
    for target in ('subprocess.run','subprocess.Popen','socket.socket','os.kill','os.killpg','threading.Thread.start'):
        stack.enter_context(patch(target,side_effect=AssertionError('Offline guard: '+target)))
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.discover(str(path)))
raise SystemExit(not result.wasSuccessful())
PY
```

Baseline command used identical code with discovery path `round6-system-ui-wait` and output `tests-round6-baseline.log` inside round7. Shuffled command flattened the discovered suite, then used `random.Random(7).shuffle(tests)` and ran `unittest.TestSuite(tests)` under identical guards; output `tests-shuffled.log`.

Additional offline commands: `git status --short`, `git diff --cached --name-only`, `git diff --binary`, `git rev-parse HEAD`, `git branch --show-current`; Python stdlib file/JSON inspection, copying, hashing and unified-diff generation. No `launcher.py` main was executed.

## Preservation / review evidence

`preservation-before.json` contains initial hashes. `preservation-check.json` verifies all **229 protected files** (product `app/src` and historical round6 files) unchanged; full pre-existing tracked diff, HEAD and branch unchanged; staged files empty. Existing worktree modifications belong to earlier work and were left intact. `evidence-sha256.json` fingerprints round7 evidence/code files (excluding itself).

## Residual risks / next gate

- 20s may still timeout in a future authorized attempt; no root cause or runtime success is established.
- Parser is deliberately pinned to the observed API36 full-dump shape; unknown shapes fail closed. Completeness checks detect transport/timeout failure, explicit diagnostics and missing/truncated structural endpoints, not cryptographic authenticity of guest text.
- Offline suites retain historical absolute-path fixtures and local configuration dependencies; independent review needs those existing read-only artifacts.
- Runtime, build, device suites and release readiness are not claimed. Review this minimal patch and offline evidence first; only the parent may subsequently authorize a bounded runtime attempt.
