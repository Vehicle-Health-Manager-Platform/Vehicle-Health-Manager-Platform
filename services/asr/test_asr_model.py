"""Offline file/installed-version verification with explicit library doubles; no real weights."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent/'src'))
from asr_model import REQUIRED,PACKAGES,verify,FasterWhisper

VERSIONS={key:'test-version' for key in PACKAGES};VERSIONS['faster-whisper']='1.2.1'

class ModelTest(unittest.TestCase):
    def setUp(self):
        self.directory=tempfile.TemporaryDirectory();self.addCleanup(self.directory.cleanup);self.root=Path(self.directory.name);self.manifest=self.root/'manifest.json'
        for name in REQUIRED:(self.root/name).write_bytes(b'fake-local-model-file')
        self.data={'version':1,'repository':'Systran/faster-whisper-small','revision':'a'*40,'license_reference':'https://huggingface.co/Systran/faster-whisper-small','license_accepted':True,'packages':VERSIONS,'files':{name:hashlib.sha256((self.root/name).read_bytes()).hexdigest() for name in REQUIRED}}
        self.write();self.version=patch('importlib.metadata.version',side_effect=lambda name:VERSIONS[name]);self.version.start();self.addCleanup(self.version.stop)
    def write(self):self.manifest.write_text(json.dumps(self.data),encoding='utf-8')
    def test_exact_official_inventory_and_streamed_weights(self):
        self.assertIn('vocabulary.txt',REQUIRED);self.assertNotIn('preprocessor_config.json',REQUIRED)
        original=Path.read_bytes
        def bounded(path):
            if path.name=='model.bin':raise AssertionError('whole weight read forbidden')
            return original(path)
        with patch.object(Path,'read_bytes',bounded):self.assertEqual(self.root.resolve(),verify(self.root,self.manifest))
    def test_missing_extra_tampered_and_manifest_declared_escape_refused(self):
        (self.root/'model.bin').write_bytes(b'changed')
        with self.assertRaises(ValueError):verify(self.root,self.manifest)
        (self.root/'model.bin').write_bytes(b'fake-local-model-file');(self.root/'extra.bin').write_bytes(b'x')
        with self.assertRaises(ValueError):verify(self.root,self.manifest)
        (self.root/'extra.bin').unlink();self.data['files']['../outside']='a'*64;self.write()
        with self.assertRaises(ValueError):verify(self.root,self.manifest)
    def test_revision_license_and_runtime_types_are_strict(self):
        for key,value in [('version',True),('revision','main'),('license_accepted','false'),('license_reference',' '),('packages',{})]:
            original=self.data[key];self.data[key]=value;self.write()
            with self.assertRaises(ValueError):verify(self.root,self.manifest)
            self.data[key]=original
        self.write()
        with patch('importlib.metadata.version',return_value='wrong'):
            with self.assertRaises(ValueError):verify(self.root,self.manifest)
    def test_prepare_records_local_revision_and_versions_without_download(self):
        path=Path(__file__).with_name('prepare_manifest.py');spec=importlib.util.spec_from_file_location('asr_prepare_test',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
        module.main(['--root',str(self.root),'--revision','b'*40,'--license-reference','operator-reviewed-MIT','--accept-license','--output',str(self.manifest)])
        self.assertEqual('b'*40,json.loads(self.manifest.read_text())['revision']);verify(self.root,self.manifest)
    def test_local_only_cpu_adapter_and_unknown_confidence(self):
        captured={}
        class Model:
            def __init__(self,*args,**kwargs):captured.update(kwargs);captured['path']=args[0]
            def transcribe(self,samples,**kwargs):return iter([SimpleNamespace(start=0,end=.8,text=' 合成转写 ',avg_logprob=-.1)]),SimpleNamespace(language='zh')
        modules={'faster_whisper':SimpleNamespace(WhisperModel=Model),'faster_whisper.audio':SimpleNamespace(decode_audio=lambda *args,**kwargs:[0]*16000)}
        with patch.dict(sys.modules,modules):
            engine=FasterWhisper(self.root,self.manifest);output=engine.transcribe(b'test',1008)
        self.assertEqual(str(self.root.resolve()),captured['path']);self.assertTrue(captured['local_files_only']);self.assertEqual('cpu',captured['device']);self.assertEqual('int8',captured['compute_type']);self.assertIsNone(output['segments'][0]['confidence']);self.assertEqual('合成转写',output['segments'][0]['text'])

if __name__=='__main__':unittest.main()
