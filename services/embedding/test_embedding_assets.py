"""Resource policy fixtures, not downloaded model acceptance."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent/'src'))
from embedding_assets import records,profile,fingerprint,verify
from prepare_assets import prepare,download,main

class AssetsTest(unittest.TestCase):
    def test_pinned_model_and_cpu_dependency_closure(self):
        model=records('model');wheels=records('wheels')
        self.assertEqual(11,len(model['files']))
        self.assertEqual(512,model['dimension'])
        self.assertEqual('2.8.0+cpu',next(r['version'] for r in wheels['files'] if r['name']=='torch'))
        self.assertEqual(64,len(fingerprint(profile())))
    def test_default_cli_never_downloads(self):
        with tempfile.TemporaryDirectory() as folder,patch('prepare_assets.download') as fetch:
            with self.assertRaises(ValueError):main(['--kind','model','--output',str(Path(folder)/'absent')])
            fetch.assert_not_called()
    def test_atomic_preparation_never_publishes_invalid_or_overwrites_existing(self):
        with tempfile.TemporaryDirectory() as folder:
            locks=Path(folder)/'locks';locks.mkdir()
            data=records('model')
            for row in data['files']:row.update(bytes=1,sha256=hashlib.sha256(b'x').hexdigest())
            (locks/'models.lock.json').write_text(json.dumps(data),encoding='utf-8')
            root=Path(folder)/'model'
            def bad(row,target):target.write_bytes(b'y')
            with self.assertRaises(ValueError):prepare(root,'model',locks,bad)
            self.assertFalse(root.exists())
            prepare(root,'model',locks,lambda row,target:target.write_bytes(b'x'))
            with patch('prepare_assets.download') as fetch:prepare(root,'model',locks);fetch.assert_not_called()
            (root/'extra').write_bytes(b'x')
            with self.assertRaises(ValueError):verify(root,'model',locks)
            self.assertTrue((root/'extra').exists())
    def test_resource_source_and_hash_policies_are_strict(self):
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder)/'models.lock.json'
            for key,value in [('revision','main'),('dimension',3),('license','UNKNOWN')]:
                data=records('model');data[key]=value;target.write_text(json.dumps(data),encoding='utf-8')
                with self.assertRaises(ValueError):records('model',folder)
            data=records('model');data['files'][0]['url']='https://other.invalid/model'
            target.write_text(json.dumps(data),encoding='utf-8')
            with self.assertRaises(ValueError):records('model',folder)
    def test_download_will_not_overwrite_existing_destination(self):
        with tempfile.TemporaryDirectory() as folder:
            target=Path(folder)/'file';target.write_bytes(b'owned')
            with patch('prepare_assets.urlopen') as fetch:
                with self.assertRaises(ValueError):download({},target,fetch)
                fetch.assert_not_called()
            self.assertEqual(b'owned',target.read_bytes())

if __name__=='__main__':unittest.main()
