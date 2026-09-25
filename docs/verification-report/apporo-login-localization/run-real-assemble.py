"""Real-config APK build, under the same outbound sandbox and disk limits as JVM."""
import datetime
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time

ROOT = Path(__file__).resolve().parents[3]
GIB = 1024 ** 3
free = shutil.disk_usage(ROOT).free
print(f'START {datetime.datetime.now(datetime.timezone.utc).isoformat()} freeGiB={free / GIB:.3f}', flush=True)
if free < 3 * GIB:
    raise SystemExit('BLOCKED: start requires 3 GiB')
command = ['/usr/bin/sandbox-exec', '-f', str(ROOT / 'docs/verification-report/apporo-phase3/review-sandbox.sb'),
           '/bin/bash', '-c', 'source ~/.local/share/woow-android-toolchain/env.sh; '
           'unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy; '
           'export JAVA_TOOL_OPTIONS=-Djava.net.preferIPv4Stack=true; '
           'exec ./gradlew --offline --no-daemon --max-workers=1 --console=plain '
           '-Dorg.gradle.parallel=false -Porg.gradle.java.installations.auto-download=false :app:assembleApporoDebug']
print(command, flush=True)
job = subprocess.Popen(command, cwd=ROOT, start_new_session=True)
minimum = free
while job.poll() is None:
    free = shutil.disk_usage(ROOT).free
    minimum = min(minimum, free)
    if free < 2 * GIB:
        os.killpg(job.pid, signal.SIGTERM)
        job.wait(timeout=30)
        raise SystemExit('STOP: less than 2 GiB; stopped own build only')
    time.sleep(2)
print(f'END minimumFreeGiB={minimum / GIB:.3f} exit={job.returncode}', flush=True)
raise SystemExit(job.returncode)
