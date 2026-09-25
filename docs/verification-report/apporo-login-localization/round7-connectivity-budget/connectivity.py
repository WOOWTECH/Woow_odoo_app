"""One full connectivity observation; no retry, fallback or partial-output success."""
import re
import time

CONNECTIVITY_MAXIMUM = 20
# Full API36 dumps already saved in round4/round5 have these ordered sections
# and this terminal trailer. Unknown/truncated formats fail closed, not guessed.
SECTIONS = (
    'NetworkProviders for:',
    'Current network preferences:',
    'Current Networks:',
    'Status for known UIDs:',
    'Network Requests:',
    'Network Offers:',
    'mLegacyTypeTracker:',
    'Permission Monitor:',
    'Legacy network activity:',
)
TRAILER = re.compile(
    r'Multicast routing supported: (?:true|false)\n'
    r'Background firewall chain enabled: (?:true|false)\n'
    r'IngressToVpnAddressFiltering: (?:true|false)\Z'
)


def require_complete_no_default_network(result):
    if (result.get('exit') != 0 or result.get('timeout') is not False
            or result.get('truncated') or result.get('error') or result.get('stderr')):
        raise RuntimeError('Connectivity observation incomplete or failed')
    output = result.get('stdout')
    if not isinstance(output, str):
        raise ValueError('Missing connectivity output')
    output = output.strip()
    if (not output.startswith(SECTIONS[0]+'\n') or not TRAILER.search(output)
            or re.search(r'truncat|\btimed?\s*out\b|permission denial|exception|\ufffd|\x00', output, re.I)):
        raise ValueError('Unknown, diagnostic or truncated connectivity dump')
    lines = [line.rstrip() for line in output.splitlines()]
    positions = []
    for section in SECTIONS:
        if lines.count(section) != 1:
            raise ValueError('Missing or ambiguous connectivity section')
        positions.append(lines.index(section))
    if positions != sorted(positions):
        raise ValueError('Unexpected connectivity section order')
    states = [line for line in lines if 'active default network' in line.lower()]
    if states != ['Active default network: none']:
        raise ValueError('Connectivity has no unique trusted no-default-network state')
    if not positions[0] < lines.index(states[0]) < positions[1]:
        raise ValueError('Unexpected connectivity state location')


def verify_no_default_network(probe, clock=None):
    clock = time.monotonic if clock is None else clock
    deadline = probe.work_deadline
    remaining = deadline - clock()
    if remaining <= 0:
        raise TimeoutError('Connectivity observation deadline exhausted; not started')
    # Probe.run also clamps against its current boot/UI deadline and checks stop.
    result = probe.shell('dumpsys', 'connectivity', required=False,
                         maximum=min(CONNECTIVITY_MAXIMUM, remaining))
    if clock() >= deadline:
        raise TimeoutError('Connectivity observation completed after phase deadline')
    require_complete_no_default_network(result)
    return result
