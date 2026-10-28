import base64
import copy
import hashlib
import io
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).parent/'src'))
sys.path.insert(0,str(Path(__file__).parents[1]/'ocr/src'))
from PIL import Image
from document_core import extract
from document_protocol import DocumentError
from test_document_protocol import body


def image():
    output=io.BytesIO();Image.new('RGB',(100,60)).save(output,format='PNG');return output.getvalue()


class Engine:
    def recognize_document(self,image):
        return [{'text':'合成里程 123456 km','confidence':.91,'points':[[1,1],[90,1],[90,30],[1,30]]}]


class CoreTest(unittest.TestCase):
    def test_native_pdf_uses_actual_plan_offsets_and_does_not_load_ocr(self):
        plan={'version':1,'task':'DOCUMENT_PLAN','source':'PDFBOX_3_0_5','page_count':1,'pages':[
            {'page':1,'kind':'NATIVE','text':'Service','blocks':[{'text':'Service','start':0,'end':7}],'raster':None}]}
        def unused():raise AssertionError('Native-only document must not load OCR')
        with tempfile.TemporaryDirectory() as folder:
            result=extract(body(),Path(folder),unused,lambda raw,work:plan)
        self.assertFalse(result['image_recognition_performed']);self.assertEqual('Service',result['pages'][0]['text'])

    def test_real_image_decode_and_fixture_ocr_preserves_polygon_and_unknown_mime_is_rejected(self):
        raw=image()
        with tempfile.TemporaryDirectory() as folder:
            result=extract(body(raw,'image/png'),Path(folder),Engine)
            self.assertEqual([100,60],result['pages'][0]['raster_size']);self.assertEqual(hashlib.sha256(raw).hexdigest(),result['content_sha256'])
            self.assertEqual([90,30],result['pages'][0]['blocks'][0]['source']['points'][2])
            with self.assertRaises(DocumentError):extract(body(raw,'image/jpeg'),Path(folder),Engine)

    def test_mixed_page_ocr_hash_and_dimensions_are_rechecked(self):
        raw=image()
        with tempfile.TemporaryDirectory() as folder:
            work=Path(folder);(work/'page-1.png').write_bytes(raw)
            page={'page':1,'kind':'OCR','text':'','blocks':[],'raster':{'filename':'page-1.png','width':100,'height':60,'sha256':hashlib.sha256(raw).hexdigest()}}
            plan={'version':1,'task':'DOCUMENT_PLAN','source':'PDFBOX_3_0_5','page_count':1,'pages':[page]}
            result=extract(body(),work,Engine,lambda *args:plan);self.assertEqual('OCR',result['pages'][0]['method'])
            for change in ('filename','width','sha256'):
                changed=copy.deepcopy(plan);changed['pages'][0]['raster'][change]={'filename':'../source.png','width':101,'sha256':'0'*64}[change]
                with self.subTest(change=change),self.assertRaises(DocumentError):extract(body(),work,Engine,lambda *args:changed)

    def test_missing_model_polygons_and_failed_partial_page_are_not_success(self):
        class Bad:
            def recognize_document(self,image):return [{'text':'synthetic','confidence':.9}]
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(DocumentError):extract(body(image(),'image/png'),Path(folder),Bad)
    def test_model_line_budget_is_reported_without_partial_results(self):
        from recognition import DocumentLinesLimit
        class Many:
            def recognize_document(self,image):raise DocumentLinesLimit()
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(DocumentError) as caught:extract(body(image(),'image/png'),Path(folder),Many)
            self.assertEqual(caught.exception.code,'LIMIT_EXCEEDED')


if __name__=='__main__':unittest.main()
