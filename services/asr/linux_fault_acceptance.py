"""Fault injection into exactly owned real model children, inside an isolated container."""
import ctypes
import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import threading
import time

sys.path.insert(0, '/app')
from asr_process import ProcessAsr, Unavailable


def identity(pid):
    root = Path('/proc') / str(pid)
    raw = (root / 'stat').read_text().rsplit(')', 1)[1].split()
    return {'pid': pid, 'start': raw[19],
        'arguments': [p.decode() for p in (root / 'cmdline').read_bytes().split(b'\0') if p]}


def real_child(pid):
    item = identity(pid)
    if item['arguments'][1:] != ['-u', '/app/asr_process.py', '--child', '/models', '/tmp/asr-manifest.json']:
        raise RuntimeError('Refusing unverified process target')
    return item


def kill_verified(item):
    # A pidfd keeps the target bound to this process even if a numeric PID is reused.
    descriptor = os.pidfd_open(item['pid'])
    try:
        if identity(item['pid']) != item:
            raise RuntimeError('Process identity changed before fault')
        signal.pidfd_send_signal(descriptor, signal.SIGKILL)
    finally:
        os.close(descriptor)


def filter_count(pid):
    rows = dict(line.split(':', 1) for line in (Path('/proc')/str(pid)/'status').read_text().splitlines() if ':' in line)
    return int(rows.get('Seccomp_filters', '0').strip())


def guard_ready(pid):
    # docker exec may inherit more than one filter. Wait for the worker's added
    # filter instead of treating a fixed count as proof its parent guard ran.
    return filter_count(pid) > filter_count(os.getpid())


def started(engine, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        pid = engine.pid
        if pid:
            try:
                item = real_child(pid)
                if guard_ready(pid):
                    return item
            except FileNotFoundError:
                pass
        time.sleep(.01)
    raise RuntimeError('Real isolated worker did not start')


def active(engine, raw):
    outcome = []
    def run():
        try:
            engine.transcribe_request(raw)
            outcome.append('success')
        except Unavailable:
            outcome.append('unavailable')
        except Exception:
            outcome.append('unexpected')
    thread = threading.Thread(target=run)
    thread.start()
    return thread, outcome


def supervisor(raw):
    engine = ProcessAsr('/models', '/tmp/asr-manifest.json')
    try:
        thread, result = active(engine, raw)
        print(json.dumps(started(engine)), flush=True)
        thread.join(95)
    finally:
        engine.close()


def main():
    raw = Path(os.environ.get('ASR_FAULT_FIXTURE','/fixtures/asr-request.json')).read_bytes()
    if len(sys.argv) == 2 and sys.argv[1] == '--supervisor':
        supervisor(raw)
        return 0
    proof = {'passed': False, 'real_user_accuracy_accepted': False, 'checks': {}}
    checks = proof['checks']
    engine = None
    parent = None
    adopted = None
    def check(name, condition):
        checks[name] = bool(condition)
        if not condition:
            raise RuntimeError(name)
    try:
        # Reap the orphaned worker ourselves instead of leaving a PID1 zombie.
        libc = ctypes.CDLL(None, use_errno=True)
        check('subreaper_enabled', libc.prctl(36, 1, 0, 0, 0) == 0)
        engine = ProcessAsr('/models', '/tmp/asr-manifest.json')
        thread, result = active(engine, raw)
        worker = started(engine)
        check('owned_worker_identity', real_child(worker['pid']) == worker)
        kill_verified(worker)
        thread.join(10)
        check('killed_worker_safe_failure', not thread.is_alive() and result == ['unavailable'])
        check('killed_worker_reaped', engine.pid is None and not Path('/proc', str(worker['pid'])).exists())
        recovered = engine.transcribe_request(raw)
        check('real_model_recovers_after_kill', recovered['status'] == 'TRANSCRIBED' and engine.pid is None)
        engine.close()
        parent = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--supervisor'],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        parent_identity = identity(parent.pid)
        check('owned_supervisor_identity', parent_identity['arguments'][1:] == [str(Path(__file__).resolve()), '--supervisor'])
        ready, _, _ = select.select([parent.stdout], [], [], 20)
        check('supervisor_reports_real_child', bool(ready))
        line = parent.stdout.readline(4097)
        check('bounded_supervisor_receipt', 0 < len(line) <= 4096)
        adopted = json.loads(line)
        check('child_guard_installed', real_child(adopted['pid']) == adopted and guard_ready(adopted['pid']))
        check('supervisor_not_replaced', identity(parent.pid) == parent_identity)
        parent.kill()
        parent.wait(timeout=5)
        deadline = time.monotonic()+5
        reaped = False
        while time.monotonic() < deadline:
            pid, status = os.waitpid(adopted['pid'], os.WNOHANG)
            if pid:
                check('kernel_parent_death_sigkill', os.WIFSIGNALED(status) and os.WTERMSIG(status) == signal.SIGKILL)
                reaped = True
                break
            time.sleep(.02)
        check('orphan_reaped_without_residual_worker', reaped and not Path('/proc', str(adopted['pid'])).exists())
        proof['passed'] = True
    except Exception as error:
        proof['failure'] = type(error).__name__+': '+str(error)
    finally:
        if engine:
            engine.close()
        if parent:
            if parent.poll() is None:
                parent.kill()
            parent.wait(timeout=5)
            parent.stdout.close()
        if adopted and Path('/proc', str(adopted['pid'])).exists():
            try:
                current = identity(adopted['pid'])
                if current['start'] == adopted['start']:
                    kill_verified(current)
                    os.waitpid(adopted['pid'], 0)
            except (ProcessLookupError, ChildProcessError, FileNotFoundError):
                pass
        checks['cleanup_complete'] = not adopted or not Path('/proc', str(adopted['pid'])).exists()
        proof['passed'] = proof['passed'] and all(checks.values())
    print(json.dumps(proof))
    return 0 if proof['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
