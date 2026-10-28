import os
from pathlib import Path
import sys
import time
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent/'src'))
from embedding_process import ProcessEmbedding,environment
from embedding_protocol import EmbeddingError
from test_embedding_protocol import body,HASH


class ProcessTest(unittest.TestCase):
    def command(self,mode):return [sys.executable,str(Path(__file__).with_name('test_embedding_child.py').resolve()),mode]
    def test_production_remains_disabled_without_explicit_configuration(self):
        with patch.dict(os.environ,{'EMBEDDING_MODEL_ENABLED':'false'}):
            with self.assertRaises(EmbeddingError):ProcessEmbedding()

    def test_actual_child_is_reaped_and_output_must_bind_to_source(self):
        for mode in ('ok','wrong_hash','crash'):
            worker=ProcessEmbedding(_command=self.command(mode),_profile_hash=HASH)
            try:
                if mode=='ok':self.assertEqual(HASH,worker.embed_request(body())['profile_hash'])
                else:
                    with self.assertRaises(EmbeddingError):worker.embed_request(body())
                self.assertIsNone(worker.pid);self.assertFalse(worker.lock.locked())
            finally:worker.close()

    def test_timeout_reaps_child_and_releases_single_job_slot(self):
        worker=ProcessEmbedding(timeout=.1,_command=self.command('hang'),_profile_hash=HASH);start=time.monotonic()
        try:
            with self.assertRaises(EmbeddingError):worker.embed_request(body())
            self.assertLess(time.monotonic()-start,5);self.assertIsNone(worker.pid);self.assertFalse(worker.lock.locked())
        finally:worker.close()

    def test_credentials_and_java_option_injection_are_not_inherited(self):
        with patch.dict(os.environ,{'EMBEDDING_SERVICE_TOKEN':'synthetic-secret','HF_TOKEN':'synthetic-secret','JAVA_TOOL_OPTIONS':'-invalid-private','_JAVA_OPTIONS':'-invalid','AWS_ACCESS_KEY_ID':'synthetic-key','UNRELATED_CREDENTIAL':'synthetic'}):
            worker=ProcessEmbedding(_command=self.command('environment'),_profile_hash=HASH)
            try:self.assertEqual(HASH,worker.embed_request(body())['profile_hash'])
            finally:worker.close()


if __name__=='__main__':unittest.main()
