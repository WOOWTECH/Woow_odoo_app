"""Offline harness; importing it does not execute tests or device operations."""
from pathlib import Path
from unittest.mock import patch
from contextlib import ExitStack
import json
import unittest


def main():
    root=Path(__file__).resolve().parent
    attempts=[]
    def forbidden(*args,**kwargs):
        attempts.append('unexpected real operation')
        raise AssertionError('Real operations forbidden in offline tests')
    with (root/'tests-final.log').open('w') as log, ExitStack() as stack:
        for name in ['subprocess.run','subprocess.Popen','socket.socket',
                     'socket.create_connection','os.kill','os.killpg','threading.Thread.start']:
            stack.enter_context(patch(name,side_effect=forbidden))
        # This OS-facing probe is unrelated to the assertions of the legacy wrong-
        # locale readiness test. Individual tests may override server availability.
        stack.enter_context(patch('launcher.port_open',return_value=False))
        suite=unittest.TestLoader().discover(str(root),pattern='test_*.py')
        result=unittest.TextTestRunner(stream=log,verbosity=2).run(suite)
    summary={'total':result.testsRun,'failures':len(result.failures),
             'errors':len(result.errors),'skipped':len(result.skipped),
             'forbiddenRealOperations':attempts,'runtimeExecuted':False}
    (root/'test-summary-final.json').write_text(json.dumps(summary,indent=2)+'\n')
    print(json.dumps(summary))
    return 0 if result.wasSuccessful() and not attempts else 1


if __name__=='__main__':
    raise SystemExit(main())
