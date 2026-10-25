import base64
from concurrent.futures import ThreadPoolExecutor
from http.client import HTTPConnection, HTTPException
import io
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import sys
import threading
import tempfile
import time
import unittest
from unittest.mock import patch
import uuid
sys.path.insert(0,str(Path(__file__).parent/'src'))
from PIL import Image
from recognition import InvalidImage, MAX_BODY
from worker import ProcessRecognizer, WorkerUnavailable, WorkerBusy
from server import create_server

CHILD=Path(__file__).with_name('test_worker_child.py').resolve()
TOKEN='synthetic-worker-credential-00000000000001'
def body(color=(0,0,0)):
    with Image.new('RGB',(3,3),color) as image:
        data=io.BytesIO();image.save(data,format='PNG')
    return json.dumps({'scene':'mileage','image_base64':base64.b64encode(data.getvalue()).decode()}).encode()
def wait_ready(worker):
    deadline=time.monotonic()+8
    while worker.status!='READY' and time.monotonic()<deadline: time.sleep(.025)
    if worker.status!='READY': raise AssertionError('Worker did not recover')

class WorkerTest(unittest.TestCase):
    def worker(self,mode='good',timeout=1):
        result=ProcessRecognizer(timeout=timeout,startup_timeout=5,_command=[sys.executable,'-u',str(CHILD),mode])
        self.addCleanup(result.close);return result
    def test_real_process_protocol_and_invalid_image_do_not_reload_model(self):
        worker=self.worker();pid=worker.pid
        self.assertEqual('CANDIDATE',worker.recognize_request(body())['status'])
        with self.assertRaises(InvalidImage):worker.recognize_request(b'{')
        self.assertEqual(pid,worker.pid);self.assertEqual('CANDIDATE',worker.recognize_request(body())['status'])
    def test_creator_thread_exit_and_repeated_recovery_preserve_owned_worker(self):
        with ThreadPoolExecutor(max_workers=1) as pool:
            worker=pool.submit(ProcessRecognizer,timeout=.35,startup_timeout=5,
                _command=[sys.executable,'-u',str(CHILD),'good']).result(timeout=8)
        self.addCleanup(worker.close)
        # The caller thread has exited; the process must still be owned by a live supervisor.
        time.sleep(.15)
        self.assertEqual('READY',worker.status)
        self.assertEqual('CANDIDATE',worker.recognize_request(body())['status'])
        supervisor=worker._supervisor
        for _ in range(2):
            pid=worker.pid
            with self.assertRaises(WorkerUnavailable):worker.recognize_request(body((255,0,0)))
            wait_ready(worker);time.sleep(.15)
            self.assertEqual('READY',worker.status);self.assertNotEqual(pid,worker.pid)
            self.assertEqual('CANDIDATE',worker.recognize_request(body())['status'])
            self.assertIs(supervisor,worker._supervisor);self.assertTrue(supervisor.is_alive())
        worker.close();self.assertFalse(supervisor.is_alive())
    def test_hung_inference_is_killed_and_next_request_uses_new_process(self):
        worker=self.worker(timeout=.35);pid=worker.pid;start=time.monotonic()
        with self.assertRaises(WorkerUnavailable):worker.recognize_request(body((255,0,0)))
        self.assertLess(time.monotonic()-start,3);wait_ready(worker);self.assertNotEqual(pid,worker.pid)
        self.assertEqual('CANDIDATE',worker.recognize_request(body())['status'])
    def test_crash_and_exception_are_not_empty_success_and_recover(self):
        for color in [(0,0,255),(0,255,0)]:
            with self.subTest(color=color):
                worker=self.worker();pid=worker.pid
                with self.assertRaises(WorkerUnavailable) as failure:worker.recognize_request(body(color))
                self.assertNotIn('private',str(failure.exception));wait_ready(worker);self.assertNotEqual(pid,worker.pid)
                self.assertEqual('CANDIDATE',worker.recognize_request(body())['status']);worker.close()
    def test_startup_hang_and_error_fail_closed(self):
        for mode in ['startup_hang','startup_error']:
            start=time.monotonic()
            with self.assertRaises(WorkerUnavailable):ProcessRecognizer(timeout=1,startup_timeout=.4,_command=[sys.executable,'-u',str(CHILD),mode])
            self.assertLess(time.monotonic()-start,3)
    def test_failed_recovery_does_not_replay_or_restart_in_loop(self):
        with tempfile.TemporaryDirectory() as temporary:
            marker=Path(temporary)/'boots'
            worker=ProcessRecognizer(timeout=.35,startup_timeout=5,_command=[sys.executable,'-u',str(CHILD),'recover_error',str(marker)])
            try:
                with self.assertRaises(WorkerUnavailable):worker.recognize_request(body((255,0,0)))
                deadline=time.monotonic()+6
                while worker.status!='UNAVAILABLE' and time.monotonic()<deadline:time.sleep(.025)
                self.assertEqual('UNAVAILABLE',worker.status)
                for _ in range(3):
                    with self.assertRaises(WorkerUnavailable):worker.recognize_request(body())
                self.assertEqual('2',marker.read_text());self.assertIsNone(worker.pid)
            finally:worker.close()
    def test_no_inference_queue(self):
        worker=self.worker(timeout=1)
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(worker.recognize_request,body((255,0,0)))
            deadline=time.monotonic()+3
            while worker.status!='BUSY' and time.monotonic()<deadline:time.sleep(.01)
            with self.assertRaises(WorkerBusy):worker.recognize_request(body())
            with self.assertRaises(WorkerUnavailable):pending.result(timeout=4)
    def test_oversized_input_and_response_are_rejected(self):
        worker=self.worker()
        with self.assertRaises(InvalidImage):worker.recognize_request(b'x'*(MAX_BODY+1))
        bad=self.worker('bad_frame')
        with self.assertRaises(WorkerUnavailable):bad.recognize_request(body())
    def test_dead_worker_with_descendant_holding_input_pipe_cannot_block_writer(self):
        worker=self.worker('leak_reader',timeout=.35);start=time.monotonic()
        with self.assertRaises(WorkerUnavailable):worker.recognize_request(b'x'*1048576)
        self.assertLess(time.monotonic()-start,3)
    def test_idle_exit_health_does_not_reap_linux_identity_and_disposal_is_idempotent(self):
        worker=self.worker('idle_exit');process=worker._process;deadline=time.monotonic()+3
        while worker.status!='UNAVAILABLE' and time.monotonic()<deadline:time.sleep(.01)
        self.assertEqual('UNAVAILABLE',worker.status)
        if sys.platform=='linux':self.assertIsNone(process.returncode)
        worker.close()
        with patch('worker.kill_worker') as kill:
            worker._dispose(process);kill.assert_not_called()
    def test_close_interrupts_active_job_and_rejects_future_requests(self):
        worker=self.worker(timeout=8)
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(worker.recognize_request,body((255,0,0)))
            deadline=time.monotonic()+3
            while worker.status!='BUSY' and time.monotonic()<deadline:time.sleep(.01)
            worker.close()
            with self.assertRaises(WorkerUnavailable):pending.result(timeout=3)
        self.assertEqual('CLOSED',worker.status)
        with self.assertRaises(WorkerUnavailable):worker.recognize_request(body())
    def test_windows_kernel_limits_or_linux_lifetime_profile(self):
        worker=self.worker();profile=worker.resource_profile
        self.assertTrue(profile['kill_on_parent_exit'])
        if os.name=='nt':
            self.assertEqual(1536*1024*1024,profile['memory_bytes']);self.assertEqual(8,profile['active_processes'])
    def test_parent_abrupt_exit_terminates_owned_worker(self):
        marker=uuid.uuid4().hex
        source="import sys,time;sys.path.insert(0,sys.argv[1]);from worker import ProcessRecognizer;w=ProcessRecognizer(startup_timeout=5,_command=[sys.executable,'-u',sys.argv[2],'good',sys.argv[3]]);print(w.pid,flush=True);time.sleep(60)"
        parent=subprocess.Popen([sys.executable,'-u','-c',source,str(CHILD.parent/'src'),str(CHILD),marker],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
        handle=None;pid=None
        try:
            pid=int(parent.stdout.readline())
            if os.name=='nt':
                import _winapi
                handle=_winapi.OpenProcess(_winapi.SYNCHRONIZE|1,False,pid)
            parent.kill();parent.wait(timeout=3)
            if os.name=='nt':self.assertEqual(_winapi.WAIT_OBJECT_0,_winapi.WaitForSingleObject(handle,5000))
            else:
                deadline=time.monotonic()+5
                while time.monotonic()<deadline:
                    path=Path('/proc')/str(pid)/'stat'
                    try:
                        if path.read_text().split()[2]=='Z':break
                    except FileNotFoundError:break
                    time.sleep(.05)
                else:self.fail('Owned worker survived parent exit')
        finally:
            if parent.poll() is None:parent.kill();parent.wait(timeout=3)
            parent.stdout.close()
            if handle:
                if _winapi.WaitForSingleObject(handle,0)==_winapi.WAIT_TIMEOUT:_winapi.TerminateProcess(handle,1)
                _winapi.CloseHandle(handle)
            elif pid:
                path=Path('/proc')/str(pid)/'cmdline'
                try:
                    if marker.encode() in path.read_bytes():os.kill(pid,signal.SIGKILL)
                except ProcessLookupError:pass
                except FileNotFoundError:pass

class WorkerHttpTest(unittest.TestCase):
    def setUp(self):
        self.worker=ProcessRecognizer(timeout=1,startup_timeout=5,_command=[sys.executable,'-u',str(CHILD),'good'])
        self.server=create_server(('127.0.0.1',0),self.worker,TOKEN)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.addCleanup(self.close)
    def close(self):self.server.shutdown();self.server.server_close();self.thread.join(3)
    def call(self,raw=None,path='/recognize',token=TOKEN):
        client=HTTPConnection('127.0.0.1',self.server.server_port,timeout=6)
        try:
            client.request('GET' if path=='/health' else 'POST',path,raw,{'Authorization':'Bearer '+token,'Content-Type':'application/json'})
            response=client.getresponse();self.assertEqual('no-store',response.getheader('Cache-Control'))
            return response.status,json.loads(response.read())
        finally:client.close()
    def test_real_http_timeout_health_busy_and_recovery(self):
        with ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(self.call,body((255,0,0)))
            deadline=time.monotonic()+3
            while self.worker.status!='BUSY' and time.monotonic()<deadline:time.sleep(.01)
            self.assertEqual(429,self.call(body())[0]);self.assertEqual('BUSY',self.call(path='/health')[1]['recognition'])
            self.assertEqual((503,{'error':'recognition_failed'}),pending.result(timeout=4))
        wait_ready(self.worker);self.assertEqual(200,self.call(body())[0])
    def test_auth_and_invalid_image_do_not_restart_process(self):
        pid=self.worker.pid
        self.assertEqual(401,self.call(body(),token='wrong')[0]);self.assertEqual(422,self.call(b'{')[0]);self.assertEqual(pid,self.worker.pid)
    def test_total_slow_header_deadline_and_connection_admission(self):
        connections=[]
        try:
            for _ in range(4):
                connection=socket.create_connection(('127.0.0.1',self.server.server_port),timeout=5)
                connections.append(connection);connection.sendall(b'POST /recognize HTTP/1.1\r\n')
            start=time.monotonic()
            with self.assertRaises((ConnectionError,HTTPException)):self.call(path='/health')
            self.assertLess(time.monotonic()-start,1);time.sleep(3.3)
            for connection in connections:
                try:self.assertEqual(b'',connection.recv(1))
                except ConnectionError:pass
            self.assertEqual(200,self.call(path='/health')[0])
        finally:
            for connection in connections:connection.close()

if __name__=='__main__':unittest.main()
