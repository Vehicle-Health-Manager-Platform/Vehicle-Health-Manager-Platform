"""One warm subprocess, bounded frames, no queue, watchdog covers all pipe I/O."""
import argparse
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import threading
import time
from recognition import MAX_BODY, InvalidImage, decode_image, recognize_image, PaddleEngine
from process_limits import bind_worker, kill_worker, guard_parent, worker_alive

MAX_RESPONSE = 16384
class WorkerBusy(RuntimeError): pass
class WorkerUnavailable(RuntimeError): pass

def read_frame(stream, maximum):
    header = stream.read(4)
    if len(header) != 4: raise WorkerUnavailable()
    size = struct.unpack('!I', header)[0]
    if not 0 < size <= maximum: raise WorkerUnavailable()
    chunks = bytearray()
    while len(chunks) < size:
        chunk = stream.read(size - len(chunks))
        if not chunk: raise WorkerUnavailable()
        chunks.extend(chunk)
    return bytes(chunks)

def write_frame(stream, value):
    data = struct.pack('!I', len(value)) + value
    view = memoryview(data)
    while view:
        count = stream.write(view)
        if not count: raise WorkerUnavailable()
        view = view[count:]
    stream.flush()

def child_loop(factory):
    # Keep the binary control channel separate from Python/native library logs.
    control = os.fdopen(os.dup(sys.stdout.fileno()), 'wb', buffering=0)
    with open(os.devnull, 'wb') as quiet:
        os.dup2(quiet.fileno(), 1); os.dup2(quiet.fileno(), 2)
    def answer(value):
        raw = json.dumps(value, allow_nan=False).encode('utf-8')
        if len(raw) > MAX_RESPONSE: raise WorkerUnavailable()
        write_frame(control, raw)
    try:
        guard_parent()
        if read_frame(sys.stdin.buffer,4) != b'BOOT': return
        try: engine = factory()
        except Exception: answer({'kind': 'startup_failed'}); return
        answer({'kind': 'ready'})
        while True:
            raw = read_frame(sys.stdin.buffer, MAX_BODY)
            try: image = decode_image(json.loads(raw))
            except Exception: answer({'kind': 'invalid'}); continue
            try:
                with image: result = recognize_image(image, engine)
                answer({'kind': 'ok', 'result': result})
            except Exception: answer({'kind': 'error'})
    except (BrokenPipeError, OSError, WorkerUnavailable): pass
    finally: control.close()

class ProcessRecognizer:
    def __init__(self, detection_dir='', recognition_dir='', *, timeout=8, startup_timeout=30, _command=None):
        if not 0.05 <= timeout <= 12 or not 0.05 <= startup_timeout <= 60: raise ValueError('Invalid worker deadline')
        self.timeout, self.startup_timeout = timeout, startup_timeout
        def local(value):
            try: return os.path.relpath(Path(value).resolve(), Path.cwd()) if value else ''
            except ValueError: return str(value)
        self.command = _command or [sys.executable, '-u', str(Path(__file__).resolve()), '--child',
            '--detection-dir', local(detection_dir), '--recognition-dir', local(recognition_dir)]
        self._lock = threading.RLock(); self._busy = threading.Lock(); self._cleanup = threading.Lock()
        self._process = self._ticket = None
        self._closed = False; self._state = 'STARTING'
        self._changed = threading.Condition(self._lock)
        self._initial_done = threading.Event(); self._boot_requested = True
        # Linux PDEATHSIG follows the creating thread, not just its process.
        # Keep one owner alive across initial boot, idle periods and recovery.
        self._supervisor = threading.Thread(target=self._supervise, name='ocr-worker-supervisor', daemon=True)
        self._supervisor.start(); self._initial_done.wait()
        if self.status != 'READY':
            self.close(); raise WorkerUnavailable('OCR worker startup failed') from None

    @property
    def status(self):
        with self._lock:
            if self._closed: return 'CLOSED'
            if self._state == 'READY':
                if self._process is None or not worker_alive(self._process): return 'UNAVAILABLE'
                return 'BUSY' if self._busy.locked() else 'READY'
            return self._state

    @property
    def pid(self):
        with self._lock: return self._process.pid if self._process else None

    def _dispose(self, process):
        if process is None: return
        with self._cleanup:
            if getattr(process,'_ocr_disposed',False): return
            kill_worker(process)
            process.wait(timeout=2)
            process._ocr_disposed=True
            for stream in (process.stdin, process.stdout):
                if stream: stream.close()
            job=getattr(process,'_ocr_job',None)
            if job: job.close()

    def _exchange(self, process, raw, seconds):
        ticket = object(); expired = threading.Event(); deadline = time.monotonic() + seconds
        with self._lock: self._ticket = ticket
        def abort():
            with self._lock:
                if self._ticket is ticket and self._process is process:
                    expired.set()
                    with self._cleanup: kill_worker(process)
        timer = threading.Timer(seconds, abort); timer.daemon = True; timer.start()
        try:
            if raw is not None: write_frame(process.stdin, raw)
            result = json.loads(read_frame(process.stdout, MAX_RESPONSE))
            if expired.is_set() or time.monotonic() > deadline: raise WorkerUnavailable()
            return result
        finally:
            with self._lock:
                if self._ticket is ticket: self._ticket = None
                timer.cancel()

    def _boot(self):
        with self._lock:
            if self._closed: raise WorkerUnavailable()
        env = {**os.environ, 'OMP_NUM_THREADS': '2', 'MKL_NUM_THREADS': '2', 'OPENBLAS_NUM_THREADS': '2',
            'NUMEXPR_NUM_THREADS': '2', 'PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK': 'True', 'OCR_SUPERVISOR_PID':str(os.getpid())}
        process = subprocess.Popen(self.command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL, bufsize=0, env=env, **({'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {'start_new_session':True}))
        try:
            self.resource_profile=bind_worker(process)
            with self._lock:
                if self._closed: raise WorkerUnavailable()
                self._process = process
            if self._exchange(process, b'BOOT', self.startup_timeout) != {'kind': 'ready'}: raise WorkerUnavailable()
            with self._lock:
                if self._closed: raise WorkerUnavailable()
                self._state = 'READY'
        except Exception:
            self._dispose(process)
            with self._lock:
                if self._process is process: self._process = None
            raise

    def _supervise(self):
        while True:
            with self._changed:
                self._changed.wait_for(lambda: self._closed or self._boot_requested)
                if self._closed: return
                self._boot_requested = False
            try: self._boot()
            except Exception:
                with self._lock:
                    if not self._closed: self._state = 'UNAVAILABLE'
            finally: self._initial_done.set()

    def _retire(self, process):
        with self._lock:
            if self._process is not process: return
            if not self._closed: self._state = 'RECOVERING'
        try: self._dispose(process)
        except Exception:
            with self._lock:
                if not self._closed: self._state = 'UNAVAILABLE'
            return  # Do not start a second process if the first could not be reaped.
        with self._changed:
            if self._process is process: self._process = None
            if not self._closed:
                self._boot_requested = True
                self._changed.notify()

    def recognize_request(self, raw):
        if not isinstance(raw, bytes) or not 0 < len(raw) <= MAX_BODY: raise InvalidImage('Invalid request')
        if not self._busy.acquire(blocking=False): raise WorkerBusy()
        process = None
        try:
            with self._lock:
                if self._closed or self._state != 'READY' or self._process is None: raise WorkerUnavailable()
                process = self._process
            result = self._exchange(process, raw, self.timeout)
            if result == {'kind': 'invalid'}: raise InvalidImage('Invalid image request')
            if not isinstance(result, dict) or set(result) != {'kind', 'result'} or result['kind'] != 'ok' or not isinstance(result['result'], dict): raise WorkerUnavailable()
            return result['result']
        except InvalidImage: raise
        except Exception:
            if process is not None: self._retire(process)
            raise WorkerUnavailable('OCR worker unavailable') from None
        finally: self._busy.release()

    def close(self):
        with self._changed:
            self._closed = True; self._state = 'CLOSED'
            process = self._process
            self._changed.notify()
        self._dispose(process)
        if self._supervisor is not threading.current_thread(): self._supervisor.join(timeout=3)
        with self._lock: self._process = None

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--child', action='store_true', required=True)
    parser.add_argument('--detection-dir', required=True)
    parser.add_argument('--recognition-dir', required=True)
    args = parser.parse_args()
    child_loop(lambda: PaddleEngine(args.detection_dir, args.recognition_dir))
