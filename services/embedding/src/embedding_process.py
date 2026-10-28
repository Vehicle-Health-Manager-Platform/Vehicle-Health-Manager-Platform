"""One owned process per fixed-profile batch; no inference queue or credential inheritance."""
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import threading
from embedding_protocol import EmbeddingError,MAX_BODY,MAX_RESPONSE,loads,request,result
from embedding_assets import verify,verify_installed,profile,fingerprint

HERE=Path(__file__).resolve().parent
if not (HERE/'process_limits.py').is_file():
    for parent in HERE.parents:
        guards=parent/'services/ocr/src'
        if guards.is_dir():sys.path.append(str(guards));break
from process_limits import bind_worker,kill_worker,guard_parent

def environment():
    allowed={'PATH','PYTHONPATH','HOME','LANG','LC_ALL','LC_CTYPE','TEMP','TMP','TMPDIR','SYSTEMROOT','WINDIR','PATHEXT'}
    out={k:v for k,v in os.environ.items() if k.upper() in allowed}
    out.update(OCR_SUPERVISOR_PID=str(os.getpid()),OMP_NUM_THREADS='2',OPENBLAS_NUM_THREADS='2',MKL_NUM_THREADS='2',TOKENIZERS_PARALLELISM='false',HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1',HF_HOME='/tmp/huggingface',TORCH_HOME='/tmp/torch',PYTHONDONTWRITEBYTECODE='1',PYTHONPYCACHEPREFIX='/tmp/embedding-pycache')
    return out

def limits():
    if sys.platform!='linux':raise EmbeddingError('UNAVAILABLE')
    value=Path('/sys/fs/cgroup/memory.max').read_text().strip()
    if not value.isdigit() or not 0<int(value)<=2*1024**3:raise EmbeddingError('UNAVAILABLE')

def read_frame(stream,maximum):
    header=stream.read(4)
    if len(header)!=4:raise EmbeddingError('UNAVAILABLE')
    size=struct.unpack('!I',header)[0]
    if not 0<size<=maximum:raise EmbeddingError('UNAVAILABLE')
    raw=bytearray()
    while len(raw)<size:
        block=stream.read(size-len(raw))
        if not block:raise EmbeddingError('UNAVAILABLE')
        raw.extend(block)
    return bytes(raw)

def write_frame(stream,raw):
    value=memoryview(struct.pack('!I',len(raw))+raw)
    while value:
        count=stream.write(value)
        if not count:raise EmbeddingError('UNAVAILABLE')
        value=value[count:]
    stream.flush()

def child(root):
    control=os.fdopen(os.dup(sys.stdout.fileno()),'wb',buffering=0)
    with open(os.devnull,'wb') as quiet:os.dup2(quiet.fileno(),1);os.dup2(quiet.fileno(),2)
    try:
        guard_parent()
        if read_frame(sys.stdin.buffer,4)!=b'BOOT':return
        raw=read_frame(sys.stdin.buffer,MAX_BODY)
        try:
            limits()
            import resource
            resource.setrlimit(resource.RLIMIT_CPU,(30,30));resource.setrlimit(resource.RLIMIT_FSIZE,(4*1024**2,4*1024**2))
            from network_guard import enforce
            enforce()
            from embedding_model import BgeEngine
            engine=BgeEngine(root);texts=request(raw,engine.profile_hash);output=engine.embed(texts)
            value={'kind':'ok','result':output}
        except EmbeddingError as e:value={'kind':'error','code':e.code}
        except Exception:value={'kind':'error','code':'UNAVAILABLE'}
        encoded=json.dumps(value,allow_nan=False,ensure_ascii=False).encode()
        if len(encoded)>MAX_RESPONSE:encoded=b'{"kind":"error","code":"INVALID_RESULT"}'
        write_frame(control,encoded)
    except Exception:pass
    finally:control.close()

class ProcessEmbedding:
    def __init__(self,root='',timeout=20,_command=None,_profile_hash=None):
        if not .05<=timeout<=20:raise ValueError('Invalid embedding deadline')
        if _command is None:
            if os.environ.get('EMBEDDING_MODEL_ENABLED')!='true':raise EmbeddingError('UNAVAILABLE')
            limits();verify(root);verify_installed();self.profile_hash=fingerprint(profile())
        else:
            if not isinstance(_profile_hash,str) or not re.fullmatch('[a-f0-9]{64}',_profile_hash):raise ValueError('Explicit test identity required')
            self.profile_hash=_profile_hash
        self.command=_command or [sys.executable,'-u',str(Path(__file__).resolve()),'--child',str(Path(root).resolve())]
        self.timeout=timeout;self.lock=threading.Lock();self.state=threading.Lock();self.cleanup=threading.Lock();self.process=None;self.pid=None;self.closed=False
    def embed_request(self,raw):
        texts=request(raw,self.profile_hash)
        if not self.lock.acquire(False):raise EmbeddingError('UNAVAILABLE')
        process=None;timer=None;expired=threading.Event()
        try:
            if self.closed:raise EmbeddingError('UNAVAILABLE')
            with tempfile.TemporaryDirectory(prefix='embedding-job-') as folder:
                try:
                    process=subprocess.Popen(self.command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,cwd=folder,env=environment(),bufsize=0,**({'creationflags':subprocess.CREATE_NO_WINDOW} if os.name=='nt' else {'start_new_session':True}))
                    with self.state:
                        if self.closed:raise EmbeddingError('UNAVAILABLE')
                        self.process=process;self.pid=process.pid
                    bind_worker(process)
                    def abort():
                        expired.set()
                        with self.cleanup:kill_worker(process)
                    timer=threading.Timer(self.timeout,abort);timer.daemon=True;timer.start()
                    write_frame(process.stdin,b'BOOT');write_frame(process.stdin,raw)
                    value=loads(read_frame(process.stdout,MAX_RESPONSE))
                    if expired.is_set():raise EmbeddingError('UNAVAILABLE')
                    if isinstance(value,dict) and set(value)=={'kind','code'} and value['kind']=='error':raise EmbeddingError(value['code'])
                    if not isinstance(value,dict) or set(value)!={'kind','result'} or value['kind']!='ok':raise EmbeddingError('INVALID_RESULT')
                    out=result(value['result'],self.profile_hash,len(texts))
                    with self.state:
                        if self.closed:raise EmbeddingError('UNAVAILABLE')
                    return out
                finally:
                    if timer:timer.cancel()
                    if process is not None:
                        with self.cleanup:
                            try:kill_worker(process)
                            except Exception:self.closed=True;raise
                            try:process.wait(timeout=3)
                            except subprocess.TimeoutExpired:self.closed=True;process.kill();process.wait(timeout=3)
                            process._ocr_disposed=True
                            for stream in (process.stdin,process.stdout):
                                if stream:stream.close()
                            job=getattr(process,'_ocr_job',None)
                            if job:job.close()
        except EmbeddingError:raise
        except Exception:raise EmbeddingError('UNAVAILABLE') from None
        finally:
            with self.state:self.process=None;self.pid=None
            self.lock.release()
    def close(self):
        with self.state:self.closed=True;process=self.process
        if process is not None:
            with self.cleanup:kill_worker(process)

if __name__=='__main__':
    if len(sys.argv)!=3 or sys.argv[1]!='--child':raise SystemExit(2)
    child(sys.argv[2])
