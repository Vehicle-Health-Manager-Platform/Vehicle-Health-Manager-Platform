"""One owned process per request, bounded frames, whole-process deadline, no inference queue."""
import json
import os
from pathlib import Path
import subprocess
import struct
import sys
import threading

# Flat runtime images carry these primitives beside this module; repository runs
# locate the shared OCR copy without assuming a minimum directory depth.
HERE=Path(__file__).resolve().parent
if not (HERE/'process_limits.py').is_file():
    for ancestor in HERE.parents:
        guards=ancestor/'services'/'ocr'/'src'
        if guards.is_dir():sys.path.append(str(guards));break
from process_limits import bind_worker,kill_worker,guard_parent
from asr_protocol import MAX_BODY,MAX_RESPONSE,audio_request,result,InvalidRequest

class Busy(RuntimeError):pass
class Unavailable(RuntimeError):pass

def child_environment():
    # The model/decoder needs runtime paths, never the private service credential or hub tokens.
    environment={key:value for key,value in os.environ.items() if not any(marker in key.upper() for marker in ('TOKEN','SECRET','PASSWORD','API_KEY'))}
    environment.update({'OCR_SUPERVISOR_PID':str(os.getpid()),'OMP_NUM_THREADS':'2','OPENBLAS_NUM_THREADS':'2','HF_HUB_OFFLINE':'1','TRANSFORMERS_OFFLINE':'1'})
    return environment

def read_frame(stream,maximum):
    header=stream.read(4)
    if len(header)!=4:raise Unavailable()
    size=struct.unpack('!I',header)[0]
    if not 0<size<=maximum:raise Unavailable()
    output=bytearray()
    while len(output)<size:
        chunk=stream.read(size-len(output))
        if not chunk:raise Unavailable()
        output.extend(chunk)
    return bytes(output)

def write_frame(stream,raw):
    value=memoryview(struct.pack('!I',len(raw))+raw)
    while value:
        count=stream.write(value)
        if not count:raise Unavailable()
        value=value[count:]
    stream.flush()

def production_limits():
    if sys.platform!='linux':raise Unavailable('Production ASR requires Linux isolation')
    cap=Path('/sys/fs/cgroup/memory.max').read_text().strip()
    if not cap.isdigit() or not 0<int(cap)<=2147483648:raise Unavailable('Container memory cap missing')
    return int(cap)

def child_loop(factory,secure=True):
    control=os.fdopen(os.dup(sys.stdout.fileno()),'wb',buffering=0)
    with open(os.devnull,'wb') as quiet:os.dup2(quiet.fileno(),1);os.dup2(quiet.fileno(),2)
    def answer(value):
        raw=json.dumps(value,allow_nan=False).encode('utf-8')
        if len(raw)>MAX_RESPONSE:raise Unavailable()
        write_frame(control,raw)
    try:
        guard_parent()
        if read_frame(sys.stdin.buffer,4)!=b'BOOT':return
        try:audio,duration=audio_request(read_frame(sys.stdin.buffer,MAX_BODY))
        except Exception:answer({'kind':'invalid'});return
        try:
            if secure:
                production_limits()
                import resource
                resource.setrlimit(resource.RLIMIT_CPU,(180,180));resource.setrlimit(resource.RLIMIT_FSIZE,(1048576,1048576))
                from network_guard import enforce
                enforce()
            output=result(factory().transcribe(audio,duration),duration)
            answer({'kind':'ok','result':output})
        except Exception:answer({'kind':'error'})
    except Exception:pass
    finally:control.close()

class ProcessAsr:
    def __init__(self,root='',manifest='',timeout=85,_command=None):
        if not .05<=timeout<=85:raise ValueError('Invalid ASR deadline')
        self.timeout=timeout;self.lock=threading.Lock();self.pid=None;self.profile=None
        self._state=threading.Lock();self._cleanup=threading.Lock();self._process=None;self._closed=False;self.configuration_verified=False;self.runtime_verified=False
        if _command is None:
            production_limits()
            from asr_model import verify
            verify(root,manifest)
            self.configuration_verified=True
        self.command=_command or [sys.executable,'-u',str(Path(__file__).resolve()),'--child',str(Path(root).resolve()),str(Path(manifest).resolve())]
    def transcribe_request(self,raw):
        # Cheap syntax validation is repeated inside the isolated decoder process.
        _,duration=audio_request(raw)
        if not self.lock.acquire(blocking=False):raise Busy()
        process=None;timer=None;expired=threading.Event()
        try:
            if self._closed:raise Unavailable()
            self.runtime_verified=False
            process=subprocess.Popen(self.command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,bufsize=0,
                env=child_environment(),
                **({'creationflags':subprocess.CREATE_NO_WINDOW} if os.name=='nt' else {'start_new_session':True}))
            with self._state:
                if self._closed:raise Unavailable()
                self._process=process;self.pid=process.pid
            self.profile=bind_worker(process)
            def abort():
                expired.set()
                with self._cleanup:kill_worker(process)
            timer=threading.Timer(self.timeout,abort);timer.daemon=True;timer.start()
            write_frame(process.stdin,b'BOOT');write_frame(process.stdin,raw)
            from asr_protocol import loads
            try:response=loads(read_frame(process.stdout,MAX_RESPONSE))
            except InvalidRequest:raise Unavailable() from None
            if expired.is_set():raise Unavailable()
            if response=={'kind':'invalid'}:raise InvalidRequest('Invalid audio')
            if not isinstance(response,dict) or set(response)!={'kind','result'} or response['kind']!='ok':raise Unavailable()
            try:
                output=result(response['result'],duration)
                self.runtime_verified=self.configuration_verified
                return output
            except InvalidRequest:raise Unavailable() from None
        except InvalidRequest:raise
        except Exception:raise Unavailable('ASR unavailable') from None
        finally:
            cleanup_failed=False
            try:
                if timer:timer.cancel()
                if process is not None:
                    with self._cleanup:
                        try:kill_worker(process)
                        except Exception:
                            self._closed=True;cleanup_failed=True
                            if process.poll() is None:process.kill()
                        try:process.wait(timeout=3)
                        except subprocess.TimeoutExpired:
                            self._closed=True;cleanup_failed=True;process.kill();process.wait(timeout=3)
                        process._ocr_disposed=True
                        for stream in (process.stdin,process.stdout):
                            if stream:stream.close()
                        job=getattr(process,'_ocr_job',None)
                        if job:job.close()
            finally:
                with self._state:self._process=None;self.pid=None
                self.lock.release()
            if cleanup_failed:raise Unavailable('ASR cleanup failed') from None

    @property
    def status(self):
        return 'CLOSED' if self._closed else 'BUSY' if self.lock.locked() else 'READY' if self.runtime_verified else 'CONFIG_VERIFIED' if self.configuration_verified else 'TEST_ONLY'

    def close(self):
        with self._state:
            self._closed=True;process=self._process
        if process is not None:
            with self._cleanup:kill_worker(process)

if __name__=='__main__':
    if len(sys.argv)!=4 or sys.argv[1]!='--child':raise SystemExit(2)
    from asr_model import FasterWhisper
    child_loop(lambda:FasterWhisper(sys.argv[2],sys.argv[3]))
