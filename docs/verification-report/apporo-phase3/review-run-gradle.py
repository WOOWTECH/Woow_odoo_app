"""Local guarded compile/JVM runner. No assemble, install, secrets, or downloads."""
import datetime
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(__file__).resolve().parent
GIB = 1024 ** 3
free = shutil.disk_usage(ROOT).free
print(f"START {datetime.datetime.now(datetime.timezone.utc).isoformat()} freeGiB={free / GIB:.3f}", flush=True)
if free < 3 * GIB:
    sys.exit('BLOCKED: start requires 3 GiB free')
args = sys.argv[1:]
if not args or any('assemble' in arg.lower() or 'install' in arg.lower() for arg in args):
    sys.exit('BLOCKED: only explicit compile/JVM tasks allowed')
command = [
    '/usr/bin/sandbox-exec', '-f', str(EVIDENCE / 'review-sandbox.sb'), '/bin/bash', '-c',
    'source ~/.local/share/woow-android-toolchain/env.sh; '
    'unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy; '
    'export JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true; '
    'exec ./gradlew --offline --no-daemon --max-workers=1 -Dorg.gradle.parallel=false '
    '-Porg.gradle.java.installations.auto-download=false '
    '-I docs/verification-report/apporo-phase3/review-fixtures.init.gradle "$@"',
    '--', *args,
]
print(shlex.join(command), flush=True)
job = subprocess.Popen(command, cwd=ROOT, start_new_session=True)
minimum = free
while job.poll() is None:
    free = shutil.disk_usage(ROOT).free
    minimum = min(minimum, free)
    if free < 2 * GIB:
        print('STOP: less than 2 GiB free; terminating this job only', flush=True)
        os.killpg(job.pid, signal.SIGTERM)
        job.wait(timeout=30)
        sys.exit(2)
    time.sleep(2)
print(f"END {datetime.datetime.now(datetime.timezone.utc).isoformat()} minimumFreeGiB={minimum / GIB:.3f} exit={job.returncode}", flush=True)
sys.exit(job.returncode)
