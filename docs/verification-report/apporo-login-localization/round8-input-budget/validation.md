# Round8 input command budget — offline candidate only

## Scope and unchanged historical result

No ADB, AVD, build, resource sample, network connection, real signal, install, branch change, staging, commit or push was performed. `launcher.main()` was **not executed**. Runtime requires independent review and subsequent parent authorization. This is one class-specific observation-budget adjustment, **not evidence of a root-cause fix**.

Read-only historical research root: `/Users/elmolin/WOOW-mobile-app-analysis/research/apporo-odoo/simulator-validation/android` (the task's space-separated shorthand resolves to this existing directory).

Inspected `round7-connectivity-budget` receipt/results and `round7-runtime-independent/critical-stop-and-matrix.json`. The historical outcome remains **1/21 checkpoints PASS**, **0/18 completed input readbacks**. The single SET_TEXT broadcast timed out at 4.016s with partial stdout; application to the guest is **unknown**, not “no input.” Four guest mutations each timed out at approximately2s; the final original IME readback matched. Host cleanup was independently verified. No historical result, tool, matrix or artifact was rewritten.

## Incremental implementation

New copy only: `docs/verification-report/apporo-login-localization/round8-input-budget/`. `incremental.patch` shows every Python delta relative to round7. All other copied Python files are unchanged.

- `ui_driver.py`: only `ADB_KEYBOARD_SET_TEXT` gets a dedicated15s command maximum, clamped to the same input screen120s and UI work deadline. The input's setup, focus, command, raw readback and screenshot share that screen deadline. Other commands retain4s defaults; connectivity20s, installed artifact pull120s, boot260s, UI720s and cleanup40s remain unchanged.
- Each planned fixture sends at most once. Failed, timeout, late or mismatched input never resends. After failure, the UI failure latch and Probe stop event are set **before** diagnostics; no hide/tap/input or later checkpoint is authorized. Partial output means the mutation may already have applied.
- Normal PASS still requires exact raw XML field equality (including leading/trailing whitespace), successful command completion and an in-deadline screenshot. Raw fixtures are unchanged. Password input is prohibited; the existing incomplete-credentials-only submission guard remains.
- On failure, at most one XML/PNG observation sequence is attempted: one dump, one XML pull, one screencap and one PNG pull; stop that sequence at its first failed transfer. Every step is bounded by the existing screen/work budget. No cleanup reserve is spent on UI diagnostics. The observation is never parsed to upgrade input/checkpoint results. Exhaustion skips observation; failure still reaches the launcher's existing unconditional cleanup.
- `launcher.py`: each guest cleanup operation has maximum5s, clamped to a **single** cleanup deadline `min(existing total deadline, cleanup start +40s)`; no per-operation reset or retry. Four mutation receipts distinguish `acknowledged`, `unknown-timeout`, `failed`, `not-attempted`, `not-required`. ACK is only command completion, not independently verified force-stop/disable state. Only the existing final IME query supplies actual readback/match state; no new guest state queries were added.
- Parent-approved compatibility: `cleanupResolved` is explicitly a **legacy host-only alias** equal to `hostCleanupResolved`. New summaries do not use it as overall success. When guest cleanup is required, final success additionally requires every necessary guest command to complete without timeout and original IME readback to match (or an explicitly not-required IME step when it was never touched). Host success cannot hide guest unknown, skipped operations or IME mismatch.
- Existing phone, root, UID, two-artifact full pull/hash/EOF/metadata, full connectivity and exact-once System UI Wait gates remain. No generic driver framework or product code changes.

## Offline validation

All suites are guarded against real subprocess launch, sockets, signals and thread starts. Test-specific mocks replace guards only with fakes.

| Run | PASS | FAIL/errors | Log |
|---|---:|---:|---|
| Historical round7 baseline |87|0|`tests-round7-baseline.log`|
| Initial round8, before four extra boundary tests |104|0|`tests-initial.log`|
| Final round8: original87 + new22 |109|0|`tests.log`|
| Final shuffled, seed8 |109|0|`tests-shuffled.log`|
| New test file in isolation |22|0|`tests-input-alone.log`|

No skips. Intermediate108/21-test green logs are retained as `*-before-receipt-exception-check.log`; the last additional test covers receipt-write failure after a possible mutation. A first isolated-test shell heredoc had an extra closing parenthesis and did not execute Python; retained as `tests-input-command-error.log`, then corrected and rerun. This was a command transcription error, not a failing product/tool test.

New `test_input_budget.py` covers12s completion with exact raw readback, partial timeout possibly applied/no resend/no hide/no PASS, nonzero and exit0+timeout rejection, mismatched raw readback, screen/UI clamping, pre-send exhaustion, late completion, one bounded forensic attempt and failures, actual execute/finally cleanup after failed forensic capture, post-failure Probe mutation rejection, password rejection,5s/shared40s cleanup limits, unknown/missing guest steps, final IME mismatch, host-success separation, and receipt-writing exceptions after possible guest mutations (failed/unknown, not falsely unattempted).

The87 historical test cases are retained. In the new copy only, the obsolete full-cleanup-byte-equality assertion is narrowed to the unchanged host signal/reap/server tail because guest cleanup/reporting is the approved change. Its other frozen-file checks remain. The connectivity integration helper accepts a failure injection to exercise real cleanup after diagnostic failure; its original gate tests remain unchanged in meaning.

### Reproduction (repository root)

```bash
python3 -B - <<'PY' > docs/verification-report/apporo-login-localization/round8-input-budget/tests.log 2>&1
from contextlib import ExitStack
from pathlib import Path
import sys, unittest
from unittest.mock import patch
path=Path('docs/verification-report/apporo-login-localization/round8-input-budget').resolve()
sys.path.insert(0,str(path))
with ExitStack() as stack:
    for target in ('subprocess.run','subprocess.Popen','socket.socket','os.kill','os.killpg','threading.Thread.start'):
        stack.enter_context(patch(target,side_effect=AssertionError('Offline guard: '+target)))
    result=unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.discover(str(path)))
raise SystemExit(not result.wasSuccessful())
PY
```

Baseline used identical guards/discovery with round7 path. Isolated new tests used `discover(str(path), pattern='test_input_budget.py')`. Shuffled run recursively flattened discovery then `random.Random(8).shuffle(tests)` and ran `unittest.TestSuite(tests)` under the same guards. Other commands were read-only git inspection and Python stdlib inspection, copying, hashing, diff generation and preservation verification. No runtime entry was invoked.

## Preservation and review evidence

`preservation-before.json` records pre-edit hashes of all existing app files and localization tooling/evidence, historical round7 runtime and independent matrix artifacts, HEAD, branch, tracked diff and empty staged list. `preservation-check.json` verifies them unchanged. `evidence-sha256.json` fingerprints the new directory excluding itself. Pre-existing worktree changes remain untouched; only this new round8 directory and the required external handoff report are written.

## Residual risks / next gate

- 15s may still timeout; the underlying runtime stall is unresolved. No runtime improvement or new checkpoint/input PASS is claimed.
- Guest ACK does not establish final app/helper process state or helper disable state; those have no independent readback in this scope. Final IME readback is the only guest state independently observed.
- Failure XML/PNG may be unavailable or partial; observation never upgrades results. Cleanup reserve is not borrowed for extra UI work.
- Offline tests use pre-existing local absolute-path XML/connectivity fixtures and guard/configuration files; those are needed for independent reproduction.
- Independent review is required. Only after review may the parent separately authorize any runtime attempt. Not a build/device/release sign-off.
