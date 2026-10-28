"""Host-only acceptance controls. No production switch or Docker socket mount."""
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection
from inspect import getsource
import json
import threading
import time

from run_linux_ocr_acceptance import owned, worker_command


def inflight_audit(proof):
    names = {'cancel', 'hide', 'leave', 'actor', 'context'}
    if proof.get('passed') is not True or proof.get('context_event_assisted') is not True or proof.get('hide_via_navigation') is not True:
        raise ValueError('Missing in-flight scope evidence')
    for key in ('cases', 'transport'):
        rows = proof.get(key, [])
        if len(rows) != 5 or {r.get('name') for r in rows} != names: raise ValueError('In-flight cases changed')
        for row in rows:
            if key == 'cases':
                if any(row.get(k) is not True for k in ('passed','no_late_candidate','no_form_write','no_archive_request')): raise ValueError('In-flight safety failed')
            elif row.get('status') != 200 or any(row.get(k) is not True for k in ('real_response_held','response_bytes_unchanged')):
                raise ValueError('Real delayed bytes missing')
    return proof


def gateway_fault_audit(proof):
    if any(proof.get(k) is not True for k in ('passed','no_archive_created','vehicle_mileage_unchanged','owned_image_unchanged','no_input_material_created')):
        raise ValueError('Gateway fault SQL proof missing')
    if type(proof.get('sql_checkpoints')) is not int or proof['sql_checkpoints'] != 8: raise ValueError('SQL checkpoints changed')
    expected = {'timeout': 503, 'timeout_recovery': 200, 'busy': 503, 'exit': 503, 'exit_recovery': 200,
                'transport_timeout': 503, 'transport_recovery': 200}
    if set(proof.get('cases', {})) != set(expected): raise ValueError('Gateway fault cases changed')
    for name, status in expected.items():
        row = proof['cases'][name]; elapsed = row.get('elapsed_seconds')
        if row.get('passed') is not True or type(row.get('status')) is not int or row['status'] != status:
            raise ValueError('Gateway fault result changed')
        if type(elapsed) not in (int, float) or not 0 <= elapsed < 25: raise ValueError('Gateway response is not bounded')
    if not 7.5 <= proof['cases']['timeout']['elapsed_seconds'] < 13 or proof['cases']['busy']['elapsed_seconds'] >= 3:
        raise ValueError('Gateway inference/busy deadline changed')
    if not 14.5 <= proof['cases']['transport_timeout']['elapsed_seconds'] < 22: raise ValueError('Transport deadline changed')
    return proof


def signal_worker(expected, action):
    # Executed as the unprivileged container user. pidfd binds the signal to the
    # process whose start time and command are checked, including on PID reuse.
    import os
    from pathlib import Path
    import signal
    if action not in ('STOP', 'KILL'): raise ValueError('Unknown fault')
    pid = expected['pid']
    if type(pid) is not int or pid <= 1: raise ValueError('Invalid worker')
    fd = os.pidfd_open(pid)
    try:
        p = Path('/proc') / str(pid)
        if (p/'stat').read_text().split()[21] != expected['start']: raise ValueError('Worker start changed')
        if not worker_command((p/'cmdline').read_bytes().split(bytes([0]))): raise ValueError('Wrong process')
        status = dict(line.split(':', 1) for line in (p/'status').read_text().splitlines() if ':' in line)
        if status['Uid'].split()[0] != '10001': raise ValueError('Worker uid changed')
        signal.pidfd_send_signal(fd, getattr(signal, 'SIG'+action))
        if action == 'STOP':
            end = time.monotonic()+2
            while True:
                state = (p/'stat').read_text().split()[2]
                if state in ('T', 't'): break
                if time.monotonic() >= end: raise ValueError('Stop not observed')
                time.sleep(.01)
        return {'action': action, 'identity_checked': True, 'pidfd_bound': True,
                'stopped_observed': action == 'STOP'}
    finally: os.close(fd)


class Faults:
    def __init__(self, *, run, server, inspect, execute, identity, port, token, raw):
        self.run, self.server, self.inspect, self.execute = run, server, inspect, execute
        self.identity, self.port, self.token, self.raw = identity, port, token, raw
        self.signals = []

    def signal(self, worker, action):
        container = self.inspect(self.server)
        if not container or not owned(container, self.run) or not container.get('State', {}).get('Running'):
            raise ValueError('Target is not this running acceptance container')
        result = self.execute(getsource(worker_command)+'\n'+getsource(signal_worker)+
            '\nimport json,time\nprint(json.dumps(signal_worker('+repr(worker)+','+repr(action)+')))')
        if result != {'action': action, 'identity_checked': True, 'pidfd_bound': True, 'stopped_observed': action == 'STOP'}:
            raise ValueError('Fault signal proof missing')
        self.signals.append(result)
        return result

    def call(self, raw=None):
        c = HTTPConnection('127.0.0.1', self.port, timeout=20)
        start = time.monotonic()
        try:
            c.request('GET' if raw is None else 'POST', '/health' if raw is None else '/recognize', raw,
                      {'Authorization': 'Bearer '+self.token, 'Content-Type': 'application/json'})
            r = c.getresponse()
            if r.getheader('Cache-Control') != 'no-store': raise ValueError('Private response policy missing')
            return r.status, json.loads(r.read()), round(time.monotonic()-start, 3)
        finally: c.close()

    def wait(self, state, seconds=35):
        end = time.monotonic()+seconds
        while time.monotonic() < end:
            if self.call()[1].get('recognition') == state: return
            time.sleep(.05)
        raise TimeoutError('OCR did not reach '+state)

    def worker(self):
        value = self.identity()
        if len(value['workers']) != 1: raise ValueError('Expected one worker')
        w = value['workers'][0]
        if w['uid'] != '10001' or w['filters'] <= value['parent_filters']: raise ValueError('Worker isolation changed')
        return w

    def success(self):
        status, data, elapsed = self.call(self.raw)
        if status != 200 or data.get('status') != 'CANDIDATE' or data.get('requires_confirmation') is not True:
            raise ValueError('Recovery inference failed')
        rows = data.get('candidates', [])
        if len(rows) != 1 or rows[0].get('mileage_km') != 123456 or rows[0].get('confidence', 0) < .9:
            raise ValueError('Recovery did not use real expected model result')
        return {'status': status, 'elapsed_seconds': elapsed, 'real_mileage': 123456, 'requires_confirmation': True}

    def run_direct(self):
        proof = {'positive_control': self.success(), 'cases': [], 'production_deadlines': {'inference': 8, 'startup': 30}}
        for mode in ('timeout', 'exit'):
            old = self.worker()
            self.signal(old, 'STOP')
            with ThreadPoolExecutor(max_workers=1) as pool:
                pending = pool.submit(self.call, self.raw)
                self.wait('BUSY', 3)
                status, data, busy_seconds = self.call(self.raw)
                if status != 429 or data != {'error': 'recognition_busy'} or busy_seconds >= 3:
                    raise ValueError('Busy request was queued or succeeded')
                if mode == 'exit': self.signal(old, 'KILL')
                status, data, elapsed = pending.result(timeout=12)
            if status != 503 or data != {'error': 'recognition_failed'}: raise ValueError('Fault became success')
            if not (7.5 <= elapsed <= 12 if mode == 'timeout' else elapsed < 6): raise ValueError('Fault not bounded')
            start = time.monotonic(); self.wait('READY'); recovery_seconds = round(time.monotonic()-start, 3)
            new = self.worker()
            if (new['pid'], new['start']) == (old['pid'], old['start']): raise ValueError('Worker was not replaced')
            recovery = self.success()
            time.sleep(.3)
            if self.worker() != new: raise ValueError('Unbounded worker rebuild')
            proof['cases'].append({'name': mode, 'status': status, 'elapsed_seconds': elapsed,
                'busy_status': 429, 'busy_elapsed_seconds': busy_seconds, 'worker_replaced': True,
                'recovery_seconds': recovery_seconds, 'recovery': recovery, 'no_extra_rebuild_observed': True})
        status, data, _ = self.call(b'{')
        if status != 422 or data != {'error': 'invalid_image_request'}: raise ValueError('Invalid input handling changed')
        proof.update(invalid_status=status, final_success=self.success(), signals=list(self.signals), passed=True)
        return proof


class GatewayControls:
    """File handshakes from test-only JVM code; fixed sequence, no arbitrary commands."""
    COMMANDS = ('stop-timeout', 'ready-timeout', 'stop-exit', 'busy-exit', 'kill-exit', 'ready-exit',
                'pause-transport', 'resume-transport')

    def __init__(self, faults, work, transport=None):
        self.faults, self.work = faults, work
        self.stop = threading.Event()
        self.completed, self.error, self.final_identity = [], None, None
        self.transport = transport
        self.thread = threading.Thread(target=self.run, name='owned-ocr-faults', daemon=True)

    def run(self):
        try:
            worker = None
            for name in self.COMMANDS:
                request = self.work/('fault-'+name+'.request')
                while not request.exists():
                    if self.stop.wait(.05): return
                if request.read_text() != name: raise ValueError('Invalid fault command')
                if name in ('pause-transport', 'resume-transport'):
                    if not self.transport: raise ValueError('Transport control missing')
                    self.transport(name == 'pause-transport')
                elif name.startswith('stop-'):
                    worker = self.faults.worker(); self.faults.signal(worker, 'STOP')
                elif name == 'busy-exit': self.faults.wait('BUSY', 3)
                elif name == 'kill-exit': self.faults.signal(worker, 'KILL')
                else:
                    self.faults.wait('READY')
                    current = self.faults.worker()
                    if (current['pid'], current['start']) == (worker['pid'], worker['start']):
                        raise ValueError('Gateway fault did not replace worker')
                    self.final_identity = self.faults.identity()
                self.completed.append(name)
                temporary = self.work/('fault-'+name+'.json.tmp')
                temporary.write_text(json.dumps({'passed': True}))
                temporary.replace(self.work/('fault-'+name+'.json'))
        except Exception as e: self.error = type(e).__name__

    def close(self):
        self.stop.set(); self.thread.join(40)
        if self.thread.is_alive(): raise RuntimeError('Fault control did not stop')

    def proof(self):
        if self.error or self.completed != list(self.COMMANDS): raise ValueError('Incomplete gateway fault controls')
        return {'commands': self.completed, 'passed': True, 'signals': self.faults.signals[3:]}
