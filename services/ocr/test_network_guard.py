import json
import os
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).parent / 'src'))
import network_guard


class NetworkGuardTest(unittest.TestCase):
    def test_default_and_false_do_not_load_linux_library(self):
        for value in ['', 'false']:
            with patch.dict(os.environ, {'OCR_WORKER_NETWORK_DISABLED': value}), patch.object(network_guard, 'enforce') as enforce:
                network_guard.configure(); enforce.assert_not_called()

    def test_invalid_policy_is_never_silently_disabled(self):
        with patch.dict(os.environ, {'OCR_WORKER_NETWORK_DISABLED': 'TRUE'}):
            with self.assertRaises(RuntimeError): network_guard.configure()

    def test_requested_policy_failure_propagates(self):
        with patch.dict(os.environ, {'OCR_WORKER_NETWORK_DISABLED': 'true'}), \
             patch.object(network_guard, 'enforce', side_effect=OSError('explicit missing library fixture')):
            with self.assertRaises(OSError): network_guard.configure()

    def test_unsupported_platform_rejects_requested_policy(self):
        with patch.object(network_guard.sys, 'platform', 'win32'):
            with self.assertRaises(RuntimeError): network_guard.enforce()

    def test_invalid_policy_prevents_real_worker_startup(self):
        from worker import ProcessRecognizer, WorkerUnavailable
        child = Path(__file__).with_name('test_worker_child.py')
        with patch.dict(os.environ, {'OCR_WORKER_NETWORK_DISABLED': 'unsupported'}):
            with self.assertRaises(WorkerUnavailable):
                ProcessRecognizer(startup_timeout=5, _command=[sys.executable, '-u', str(child), 'good'])

    @unittest.skipUnless(sys.platform == 'linux', 'Actual kernel filter requires Linux')
    def test_kernel_denies_sockets_and_existing_threads_while_files_and_pipes_work(self):
        source = r'''
import errno,json,os,socket,sys,threading
sys.path.insert(0,sys.argv[1])
from network_guard import enforce
families=[(socket.AF_INET,socket.SOCK_STREAM),(socket.AF_INET,socket.SOCK_DGRAM),(socket.AF_UNIX,socket.SOCK_STREAM)]
for family,kind in families:
    with socket.socket(family,kind): pass
started=threading.Event();release=threading.Event();answers=[]
def thread_probe():
    started.set();release.wait()
    try:socket.socket()
    except OSError as error:answers.append(error.errno)
thread=threading.Thread(target=thread_probe);thread.start();started.wait()
enforce();release.set();thread.join(3)
assert answers==[errno.EPERM] and not thread.is_alive()
for family,kind in families:
    try:socket.socket(family,kind)
    except OSError as error:assert error.errno==errno.EPERM
    else:raise AssertionError('Socket allowed')
try:socket.socketpair()
except OSError as error:assert error.errno==errno.EPERM
else:raise AssertionError('Socketpair allowed')
read,write=os.pipe();os.write(write,b'control-pipe');assert os.read(read,12)==b'control-pipe';os.close(read);os.close(write)
with open('/proc/self/status') as status:assert 'Seccomp:\t2' in status.read()
print(json.dumps({'positive_sockets':3,'denied_sockets':4,'existing_thread_denied':True,'pipe_and_file_ok':True}))
'''
        result = subprocess.run([sys.executable, '-c', source, str(Path(__file__).parent / 'src')], capture_output=True, text=True, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        proof = json.loads(result.stdout)
        self.assertEqual(4, proof['denied_sockets']); self.assertTrue(proof['existing_thread_denied'])
        self.assertTrue(proof['pipe_and_file_ok'])


if __name__ == '__main__': unittest.main()
