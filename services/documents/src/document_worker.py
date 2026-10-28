"""One owned document child per request; no public endpoint or implicit enablement."""
import json
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading

HERE=Path(__file__).resolve().parent
for candidate in [Path('/app'),*[p/'services/ocr/src' for p in HERE.parents]]:
    if (candidate/'process_limits.py').is_file():sys.path.append(str(candidate));break
from process_limits import guard_parent,bind_worker,kill_worker
from worker import read_frame,write_frame
from document_protocol import DocumentError,MAX_BODY,MAX_RESPONSE,loads,request,result


def environment():
    allowed={'PATH','PYTHONPATH','HOME','LANG','LC_ALL','LC_CTYPE','TEMP','TMP','TMPDIR',
        'SYSTEMROOT','WINDIR','PATHEXT','PADDLE_HOME','PADDLE_PDX_CACHE_HOME','PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK',
        'HF_HOME','XDG_CACHE_HOME','MODELSCOPE_CACHE'}
    value={key:item for key,item in os.environ.items() if key.upper() in allowed}
    value.update(OCR_SUPERVISOR_PID=str(os.getpid()),HF_HUB_OFFLINE='1',TRANSFORMERS_OFFLINE='1',
        OMP_NUM_THREADS='2',OPENBLAS_NUM_THREADS='2',MKL_NUM_THREADS='2',
        PYTHONPYCACHEPREFIX='/tmp/document-pycache',PYTHONDONTWRITEBYTECODE='1')
    return value


def limits():
    if sys.platform!='linux':raise DocumentError('UNAVAILABLE')
    value=Path('/sys/fs/cgroup/memory.max').read_text().strip()
    if not value.isdigit() or not 0<int(value)<=2*1024**3:raise DocumentError('UNAVAILABLE')


def engine():
    sys.path.append('/app')
    from model_assets import verify_models
    from recognition import PaddleEngine
    paths=verify_models(Path('/models'))
    return PaddleEngine(paths['PP-OCRv5_mobile_det'],paths['PP-OCRv5_mobile_rec'])


def child_loop():
    control=os.fdopen(os.dup(sys.stdout.fileno()),'wb',buffering=0)
    with open(os.devnull,'wb') as quiet:os.dup2(quiet.fileno(),1);os.dup2(quiet.fileno(),2)
    try:
        guard_parent()
        if read_frame(sys.stdin.buffer,4)!=b'BOOT':return
        raw=read_frame(sys.stdin.buffer,MAX_BODY)
        try:
            limits()
            import resource
            resource.setrlimit(resource.RLIMIT_CPU,(60,60));resource.setrlimit(resource.RLIMIT_FSIZE,(4*1024**2,4*1024**2))
            from network_guard import enforce
            enforce()
            from document_core import extract
            value={'kind':'ok','result':extract(raw,Path.cwd(),engine)}
        except DocumentError as error:value={'kind':'error','code':error.code}
        except Exception as error:
            from recognition import InvalidImage
            value={'kind':'error','code':'INVALID_DOCUMENT' if isinstance(error,InvalidImage) else 'UNAVAILABLE'}
        encoded=json.dumps(value,ensure_ascii=False,allow_nan=False).encode()
        if len(encoded)>MAX_RESPONSE:encoded=b'{"kind":"error","code":"LIMIT_EXCEEDED"}'
        write_frame(control,encoded)
    except Exception:pass
    finally:control.close()


class ProcessDocument:
    def __init__(self,timeout=60,_command=None):
        if not .05<=timeout<=60:raise ValueError('Invalid document deadline')
        if _command is None:
            if os.environ.get('DOCUMENT_CORE_ENABLED')!='true':raise DocumentError('UNAVAILABLE')
            limits()
        self.command=_command or [sys.executable,'-u',str(Path(__file__).resolve()),'--child']
        self.timeout=timeout;self.lock=threading.Lock();self.state=threading.Lock();self.cleanup=threading.Lock()
        self.closed=False;self.process=None;self.pid=None
    def extract_request(self,raw):
        content_type,body=request(raw)
        expected_hash=hashlib.sha256(body).hexdigest()
        if not self.lock.acquire(False):raise DocumentError('UNAVAILABLE')
        process=None;timer=None;expired=threading.Event()
        try:
            if self.closed:raise DocumentError('UNAVAILABLE')
            with tempfile.TemporaryDirectory(prefix='document-job-') as temporary:
                try:
                    process=subprocess.Popen(self.command,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,
                        cwd=temporary,env=environment(),bufsize=0,
                        **({'creationflags':subprocess.CREATE_NO_WINDOW} if os.name=='nt' else {'start_new_session':True}))
                    with self.state:
                        if self.closed:raise DocumentError('UNAVAILABLE')
                        self.process=process;self.pid=process.pid
                    bind_worker(process)
                    def abort():
                        expired.set()
                        with self.cleanup:kill_worker(process)
                    timer=threading.Timer(self.timeout,abort);timer.daemon=True;timer.start()
                    write_frame(process.stdin,b'BOOT');write_frame(process.stdin,raw)
                    value=loads(read_frame(process.stdout,MAX_RESPONSE))
                    if expired.is_set():raise DocumentError('UNAVAILABLE')
                    if isinstance(value,dict) and set(value)=={'kind','code'} and value['kind']=='error':raise DocumentError(value['code'])
                    if not isinstance(value,dict) or set(value)!={'kind','result'} or value['kind']!='ok':raise DocumentError('UNAVAILABLE')
                    output=result(value['result'])
                    if output['content_type']!=content_type or output['content_sha256']!=expected_hash:raise DocumentError('INVALID_RESULT')
                    with self.state:
                        if self.closed:raise DocumentError('UNAVAILABLE')
                    return output
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
        except DocumentError:raise
        except Exception:raise DocumentError('UNAVAILABLE') from None
        finally:
            with self.state:self.process=None;self.pid=None
            self.lock.release()
    def close(self):
        with self.state:self.closed=True;process=self.process
        if process is not None:
            with self.cleanup:kill_worker(process)


if __name__=='__main__':
    if sys.argv[1:]!=['--child']:raise SystemExit(2)
    child_loop()
