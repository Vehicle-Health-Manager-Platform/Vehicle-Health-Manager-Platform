import base64
import io
import json
import sys
import threading
import unittest
import tempfile
from types import SimpleNamespace
from pathlib import Path
from http.client import HTTPConnection
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).parent/'src'))
from PIL import Image
from recognition import decode_image, InvalidImage, PaddleEngine
from server import create_server

TOKEN = 'synthetic-ocr-test-credential-00000001'
def request_image(format='PNG',size=(30,20)):
    stream=io.BytesIO()
    Image.new('RGB',size).save(stream,format=format)
    return {'scene':'mileage','image_base64':base64.b64encode(stream.getvalue()).decode()}
class Engine:
    def __init__(self,lines=None): self.calls=0;self.lines=lines if lines is not None else [{'text':'123456 km','confidence':.95}]
    def recognize(self,image):
        self.calls+=1
        assert image.mode=='RGB'
        return self.lines

class ImageTest(unittest.TestCase):
    def test_real_png_jpeg_decode(self):
        for format in ['PNG','JPEG']:
            image=decode_image(request_image(format));self.assertEqual((30,20),image.size);image.close()
    def test_rejects_bad_formats_dimensions_and_remote_input(self):
        for data in [request_image('GIF'),request_image(size=(4097,1)),request_image(size=(3000,2100)),{}, {'scene':'license','image_base64':'AA=='},{'scene':'mileage','image_base64':'https://example.com/photo'}, {'scene':'mileage','image_base64':base64.b64encode(b'not an image').decode()}, {'scene':'mileage','image_base64':'x'*6000000}]:
            with self.assertRaises(InvalidImage): decode_image(data)
    def test_no_model_download_when_directories_missing(self):
        with self.assertRaises(ValueError): PaddleEngine(None,None)
    def test_paddle_v3_contract_with_explicit_library_stub(self):
        captured={}
        class Array:
            def __getitem__(self,key): return self
            def copy(self): return self
        class Model:
            def __init__(self,**kwargs): captured.update(kwargs)
            def predict(self,image): return iter([{'rec_texts':['123 km'],'rec_scores':[.95]}])
        with tempfile.TemporaryDirectory() as directory:
            Path(directory,'inference.yml').write_text('test fixture')
            with patch.dict(sys.modules,{'paddleocr':SimpleNamespace(PaddleOCR=Model),'numpy':SimpleNamespace(asarray=lambda image:Array())}):
                engine=PaddleEngine(directory,directory)
                self.assertEqual([{'text':'123 km','confidence':.95}],engine.recognize(None))
                for score in [True,'0.95']:
                    with patch.object(engine.model,'predict',return_value=iter([{'rec_texts':['123 km'],'rec_scores':[score]}])):
                        with self.assertRaises(ValueError): engine.recognize(None)
        self.assertFalse(captured['use_doc_unwarping'])
        self.assertFalse(captured['use_textline_orientation'])
        self.assertIs(False,captured.get('enable_mkldnn'))
        self.assertEqual('PP-OCRv5',captured['ocr_version'])
    def test_credential_required_for_enabled_engine(self):
        with self.assertRaises(ValueError): create_server(('127.0.0.1',0),Engine(),'short')

class HttpTest(unittest.TestCase):
    def setUp(self):
        self.engine=Engine();self.server=create_server(('127.0.0.1',0),self.engine,TOKEN)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.addCleanup(self.close)
    def close(self): self.server.shutdown();self.server.server_close();self.thread.join()
    def call(self,body=None,token=TOKEN,path='/recognize',extra=None):
        connection=HTTPConnection('127.0.0.1',self.server.server_port,timeout=5)
        headers={'Content-Type':'application/json','Authorization':'Bearer '+token};headers.update(extra or {})
        connection.request('POST',path,json.dumps(body if body is not None else request_image()).encode(),headers)
        response=connection.getresponse();status=response.status;cache=response.getheader('Cache-Control');data=json.loads(response.read());connection.close()
        self.assertEqual('no-store',cache)
        return status,data
    def test_real_http_with_explicit_test_engine(self):
        status,data=self.call();self.assertEqual(200,status);self.assertEqual('CANDIDATE',data['status']);self.assertTrue(data['requires_confirmation']);self.assertTrue(data['image_recognition_performed']);self.assertEqual(1,self.engine.calls)
    def test_unauthorized_and_query_refused_before_engine(self):
        self.assertEqual(401,self.call(token='wrong')[0]);self.assertEqual(404,self.call(path='/recognize?x=1')[0]);self.assertEqual(0,self.engine.calls)
    def test_bad_image_and_media_type_never_infer(self):
        self.assertEqual(422,self.call(body={'scene':'mileage','image_base64':'bad'})[0]);self.assertEqual(415,self.call(extra={'Content-Type':'text/plain'})[0]);self.assertEqual(0,self.engine.calls)
    def test_framing_and_request_limit(self):
        self.assertEqual(413,self.call(extra={'Content-Length':'7000000'})[0])
        self.assertEqual(411,self.call(extra={'Transfer-Encoding':'chunked'})[0])
        self.assertEqual(0,self.engine.calls)
    def test_inference_errors_are_not_success_or_leaked(self):
        with patch.object(self.engine,'recognize',side_effect=RuntimeError('private upstream detail')):
            status,data=self.call();self.assertEqual(503,status);self.assertNotIn('private',json.dumps(data))
    def test_malformed_model_output_refused(self):
        self.engine.lines=[{'text':'123 km','confidence':float('nan')}];self.assertEqual(503,self.call()[0])
    def test_disabled_keeps_501(self):
        self.server.engine=None;self.assertEqual(501,self.call()[0])

if __name__=='__main__': unittest.main()
