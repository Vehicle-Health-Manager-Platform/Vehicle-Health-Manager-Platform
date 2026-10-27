import os
from pathlib import Path
import sys
import time
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent/'src'))
from document_worker import ProcessDocument,environment
from document_protocol import DocumentError
from test_document_protocol import body


class ProcessTest(unittest.TestCase):
    def command(self,mode):return [sys.executable,str(Path(__file__).with_name('test_document_child.py').resolve()),mode]
    def test_production_remains_disabled_without_explicit_configuration(self):
        with patch.dict(os.environ,{'DOCUMENT_CORE_ENABLED':'false'}):
            with self.assertRaises(DocumentError):ProcessDocument()

    def test_actual_child_is_reaped_and_output_must_bind_to_source(self):
        for mode in ('ok','wrong_hash','crash'):
            worker=ProcessDocument(_command=self.command(mode))
            try:
                if mode=='ok':self.assertEqual('EXTRACTED',worker.extract_request(body())['status'])
                else:
                    with self.assertRaises(DocumentError):worker.extract_request(body())
                self.assertIsNone(worker.pid);self.assertFalse(worker.lock.locked())
            finally:worker.close()

    def test_timeout_reaps_child_and_releases_single_job_slot(self):
        worker=ProcessDocument(timeout=.1,_command=self.command('hang'));start=time.monotonic()
        try:
            with self.assertRaises(DocumentError):worker.extract_request(body())
            self.assertLess(time.monotonic()-start,5);self.assertIsNone(worker.pid);self.assertFalse(worker.lock.locked())
        finally:worker.close()

    def test_credentials_and_java_option_injection_are_not_inherited(self):
        with patch.dict(os.environ,{'DOCUMENT_SERVICE_TOKEN':'synthetic-secret','HF_TOKEN':'synthetic-secret','JAVA_TOOL_OPTIONS':'-invalid-private','_JAVA_OPTIONS':'-invalid','AWS_ACCESS_KEY_ID':'synthetic-key','UNRELATED_CREDENTIAL':'synthetic'}):
            worker=ProcessDocument(_command=self.command('environment'))
            try:self.assertEqual('EXTRACTED',worker.extract_request(body())['status'])
            finally:worker.close()


if __name__=='__main__':unittest.main()
