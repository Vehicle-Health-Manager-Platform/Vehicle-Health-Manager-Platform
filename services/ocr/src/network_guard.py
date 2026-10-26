"""Opt-in Linux worker-only socket denial. Failure never downgrades to network access."""
import ctypes
import errno
import os
from pathlib import Path
import socket
import sys

SYSCALLS = ('socket', 'socketpair', 'connect', 'bind', 'listen', 'accept', 'accept4',
    'sendto', 'sendmsg', 'sendmmsg', 'recvfrom', 'recvmsg', 'recvmmsg', 'shutdown',
    'io_uring_setup', 'io_uring_register', 'io_uring_enter')


def enforce():
    if sys.platform != 'linux': raise RuntimeError('Worker network guard requires Linux')
    # The production worker inherits only control pipes, never a listening socket.
    for descriptor in Path('/proc/self/fd').iterdir():
        try: target = os.readlink(descriptor)
        except FileNotFoundError: continue
        if target.startswith('socket:'): raise RuntimeError('Worker inherited a socket')
    lib = ctypes.CDLL('libseccomp.so.2', use_errno=True)
    definitions = {
        'seccomp_init': ([ctypes.c_uint32], ctypes.c_void_p),
        'seccomp_release': ([ctypes.c_void_p], None),
        'seccomp_attr_set': ([ctypes.c_void_p, ctypes.c_int, ctypes.c_uint32], ctypes.c_int),
        'seccomp_syscall_resolve_name': ([ctypes.c_char_p], ctypes.c_int),
        'seccomp_rule_add_array': ([ctypes.c_void_p, ctypes.c_uint32, ctypes.c_int, ctypes.c_uint, ctypes.c_void_p], ctypes.c_int),
        'seccomp_load': ([ctypes.c_void_p], ctypes.c_int),
    }
    for name, (args, result) in definitions.items():
        function = getattr(lib, name); function.argtypes = args; function.restype = result
    context = lib.seccomp_init(0x7fff0000)  # SCMP_ACT_ALLOW
    if not context: raise RuntimeError('Cannot initialize worker network guard')
    try:
        # SCMP_FLTATR_CTL_TSYNC: cover all current threads, and future children inherit.
        if lib.seccomp_attr_set(context, 4, 1) != 0: raise RuntimeError('Cannot synchronize worker network guard')
        for name in SYSCALLS:
            number = lib.seccomp_syscall_resolve_name(name.encode('ascii'))
            if number < 0 or lib.seccomp_rule_add_array(context, 0x00050000 | errno.EPERM, number, 0, None) != 0:
                raise RuntimeError('Cannot configure worker network guard')
        if lib.seccomp_load(context) != 0: raise RuntimeError('Cannot load worker network guard')
    finally: lib.seccomp_release(context)
    # Verify actual kernel enforcement, rather than trusting configuration alone.
    try:
        connection = socket.socket()
    except OSError as error:
        if error.errno != errno.EPERM: raise RuntimeError('Worker network guard self-check failed') from None
    else:
        connection.close(); raise RuntimeError('Worker network guard did not deny sockets')


def configure():
    requested = os.environ.get('OCR_WORKER_NETWORK_DISABLED', '')
    if requested not in ('', 'false', 'true'): raise RuntimeError('Invalid worker network policy')
    if requested == 'true': enforce()
