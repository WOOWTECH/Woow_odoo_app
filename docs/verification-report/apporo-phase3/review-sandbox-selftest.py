import errno
import socket

# Own and close both endpoints; no external/LAN service is probed.
for family, host in [(socket.AF_INET, '127.0.0.1'), (socket.AF_INET6, '::1')]:
    with socket.socket(family) as listener:
        listener.bind((host, 0))
        listener.listen()
        with socket.socket(family) as client:
            client.settimeout(1)
            client.connect(listener.getsockname())
            accepted, _ = listener.accept()
            accepted.close()
        print('PASS owned loopback:', host)

# This macOS cannot bind 127.0.0.2. Require sandbox refusal, not timeout/refusal,
# before it can connect to the non-allowlisted loopback destination.
with socket.socket() as client:
    client.settimeout(1)
    try:
        client.connect(('127.0.0.2', 9))
    except OSError as error:
        assert error.errno in (errno.EPERM, errno.EACCES), repr(error)
        print('PASS non-allowlisted loopback blocked, errno:', error.errno)
    else:
        raise AssertionError('Sandbox allowed a non-allowlisted destination')
