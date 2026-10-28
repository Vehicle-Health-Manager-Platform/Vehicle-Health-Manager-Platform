"""Real owned processes and loopback HTTP, explicit engine doubles only."""
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection
import json
import os
from pathlib import Path
import sys
import threading
import time
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent/'src'))
from asr_process import ProcessAsr,Unavailable,Busy
from asr_server import Server,configured_server
from asr_protocol import InvalidRequest
from test_asr_protocol import request,answer,audio

CHILD=Path(__file__).with_name('test_asr_child.py')
TOKEN='synthetic-asr-server-token-000000000001'

class ProcessTest(unittest.TestCase):
    def test_flat_runtime_import_does_not_assume_repository_directory_depth(self):
        import asr_process
        source=Path(asr_process.__file__).read_text(encoding='utf-8')
        flat=Path(Path.cwd().anchor)/'app'/'asr_process.py'
        namespace={'__file__':str(flat),'__name__':'asr_flat_import_probe'}
        with patch('pathlib.Path.resolve',return_value=flat):exec(compile(source,str(flat),'exec'),namespace)
        self.assertIn('ProcessAsr',namespace)
    def test_worker_inherits_no_service_credentials_or_hub_tokens(self):
        with patch.dict(os.environ,{'ASR_SERVICE_TOKEN':'synthetic-private-token','HF_TOKEN':'synthetic-hub','SYNTHETIC_API_KEY':'synthetic-key','ASR_ENV_TEST_VALUE':'retained'}):
            worker=self.worker('environment')
            self.assertEqual('TRANSCRIBED',worker.transcribe_request(request())['status'])
    def worker(self,mode='good',timeout=2):
        value=ProcessAsr(timeout=timeout,_command=[sys.executable,'-u',str(CHILD),mode]);self.addCleanup(value.close);return value
    def test_request_process_is_reaped_and_each_retry_uses_a_new_process(self):
        value=self.worker()
        self.assertEqual('TEST_ONLY',value.status)
        for _ in range(2):
            self.assertEqual('TRANSCRIBED',value.transcribe_request(request())['status']);self.assertIsNone(value.pid);self.assertFalse(value.lock.locked())
    def test_timeout_crash_error_and_malformed_frames_are_safe(self):
        for mode in ['hang','crash','error','bad_result','duplicate','no_read']:
            worker=self.worker(mode,.3);start=time.monotonic()
            with self.assertRaises(Unavailable) as error:worker.transcribe_request(request())
            self.assertLess(time.monotonic()-start,4);self.assertNotIn('private',str(error.exception));self.assertIsNone(worker.pid);self.assertFalse(worker.lock.locked())
    def test_no_queue_and_close_aborts_inflight_and_prevents_reuse(self):
        worker=self.worker('hang',3)
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(worker.transcribe_request,request());deadline=time.monotonic()+2
            while worker.pid is None and time.monotonic()<deadline:time.sleep(.01)
            self.assertIsNotNone(worker.pid)
            with self.assertRaises(Busy):worker.transcribe_request(request())
            worker.close()
            with self.assertRaises(Unavailable):pending.result(timeout=4)
        self.assertEqual('CLOSED',worker.status);self.assertIsNone(worker.pid)
        with self.assertRaises(Unavailable):worker.transcribe_request(request())
    def test_invalid_audio_never_spawns_and_cleanup_failure_releases_lock(self):
        worker=self.worker()
        with patch('asr_process.subprocess.Popen') as spawn:
            with self.assertRaises(InvalidRequest):worker.transcribe_request(b'{}')
            spawn.assert_not_called()
        with patch('asr_process.kill_worker',side_effect=OSError('test cleanup failure')):
            with self.assertRaises(Unavailable):worker.transcribe_request(request())
        self.assertFalse(worker.lock.locked());self.assertEqual('CLOSED',worker.status)
    def test_production_never_bypasses_linux_or_manifest_admission(self):
        with patch('asr_process.production_limits',side_effect=Unavailable('required')):
            with self.assertRaises(Unavailable):ProcessAsr('missing','missing')
        with patch('asr_process.production_limits',return_value=2147483648),patch('asr_model.verify',side_effect=ValueError('missing model')) as verify:
            with self.assertRaises(ValueError):ProcessAsr('missing','missing')
            verify.assert_called_once()
        with patch('asr_process.production_limits',return_value=2147483648),patch('asr_model.verify',return_value=Path.cwd()),patch('asr_process.subprocess.Popen') as spawn:
            engine=ProcessAsr('placeholder','placeholder')
            try:self.assertEqual('CONFIG_VERIFIED',engine.status);spawn.assert_not_called()
            finally:engine.close()

class Engine:
    def __init__(self):self.calls=0;self.error=False;self.closed=False;self.entered=threading.Event();self.release=None
    def transcribe_request(self,raw):
        self.calls+=1;self.entered.set()
        if self.release and not self.release.wait(3):raise RuntimeError('test wait')
        if self.error:raise RuntimeError('private transcript diagnostic')
        return answer()
    def close(self):self.closed=True

class HttpTest(unittest.TestCase):
    def setUp(self):
        self.engine=Engine();self.server=Server(('127.0.0.1',0),self.engine,TOKEN);self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start();self.addCleanup(self.close)
    def close(self):self.server.shutdown();self.server.server_close();self.thread.join();self.assertTrue(self.engine.closed)
    def call(self,raw=None,token=TOKEN,path='/transcribe',headers=None):
        conn=HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
        conn.request('POST',path,request() if raw is None else raw,{'Authorization':'Bearer '+token,'Content-Type':'application/json',**(headers or {})})
        response=conn.getresponse();data=json.loads(response.read());status=response.status
        self.assertEqual('no-store',response.getheader('Cache-Control'));conn.close();return status,data
    def test_loopback_success_and_early_errors_drain_bounded_body(self):
        self.assertEqual('TRANSCRIBED',self.call()[1]['status'])
        for code,kwargs in [(401,{'token':'wrong'}),(404,{'path':'/transcribe?x=1'}),(415,{'headers':{'Content-Type':'text/plain'}}),(422,{'raw':b'{}'})]:self.assertEqual(code,self.call(**kwargs)[0])
        self.assertEqual(1,self.engine.calls)
    def test_framing_and_encoding_ambiguity_refused_before_engine(self):
        self.assertEqual(411,self.call(raw=b'',headers={'Transfer-Encoding':'chunked'})[0])
        self.assertEqual(413,self.call(raw=b'',headers={'Content-Length':'5000000'})[0])
        conn=HTTPConnection('127.0.0.1',self.server.server_port,timeout=5);raw=request()
        conn.putrequest('POST','/transcribe');conn.putheader('Authorization','Bearer '+TOKEN);conn.putheader('Content-Type','application/json');conn.putheader('Content-Length',str(len(raw)));conn.putheader('Content-Encoding','identity');conn.putheader('Content-Encoding','gzip');conn.endheaders(raw)
        response=conn.getresponse();self.assertEqual(415,response.status);response.read();conn.close();self.assertEqual(0,self.engine.calls)
    def test_source_errors_are_not_empty_success_or_private_text(self):
        self.engine.error=True;code,data=self.call();self.assertEqual(503,code);self.assertNotIn('private',json.dumps(data))
    def test_single_job_no_queue_and_recovers_after_completion(self):
        self.engine.release=threading.Event()
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(self.call);self.assertTrue(self.engine.entered.wait(2))
            try:self.assertEqual(429,self.call()[0])
            finally:self.engine.release.set()
            self.assertEqual(200,pending.result(timeout=4)[0])
        self.assertEqual(200,self.call()[0])
    def test_disabled_service_and_ready_flag_are_truthful(self):
        disabled=configured_server(('127.0.0.1',0),{})
        try:self.assertIsNone(disabled.engine)
        finally:disabled.server_close()
        self.server.engine=None
        self.assertEqual(503,self.call()[0]);self.server.engine=self.engine
        for env in [{'ASR_SERVICE_ENABLED':'typo'},{'ASR_SERVICE_ENABLED':'true','ASR_SERVICE_TOKEN':'short'}]:
            with self.assertRaises(ValueError):configured_server(('127.0.0.1',0),env)
        with patch('asr_server.ProcessAsr',side_effect=ValueError('bad manifest')) as process:
            with self.assertRaises(ValueError):configured_server(('127.0.0.1',0),{'ASR_SERVICE_ENABLED':'true','ASR_SERVICE_TOKEN':TOKEN,'ASR_MODEL_ROOT':'x','ASR_MODEL_MANIFEST':'y'})
            process.assert_called_once()

if __name__=='__main__':unittest.main()
