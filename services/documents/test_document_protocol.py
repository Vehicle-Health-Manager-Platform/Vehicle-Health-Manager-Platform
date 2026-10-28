import base64
import copy
import hashlib
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).parent/'src'))
from document_protocol import DocumentError,request,result,native_lines,polygon


def body(raw=b'synthetic PDF',content_type='application/pdf'):
    return json.dumps({'version':1,'content_type':content_type,'document_base64':base64.b64encode(raw).decode()}).encode()


def answer(raw=b'synthetic PDF',content='Service\nODO 123456 km'):
    return {'version':1,'task':'DOCUMENT','source':'PDFBOX_PADDLE','content_type':'application/pdf',
        'content_sha256':hashlib.sha256(raw).hexdigest(),'page_count':1,'pages':[
        {'page':1,'method':'NATIVE_TEXT','text':content,'raster_size':None,'blocks':[
        {'text':row['text'],'confidence':None,'source':{'kind':'TEXT_OFFSET','start':row['start'],'end':row['end']}}
        for row in native_lines(content)]}], 'status':'EXTRACTED','requires_confirmation':True,
        'image_recognition_performed':False,'all_pages_processed':True}


class ProtocolTest(unittest.TestCase):
    def test_strict_input_has_no_url_path_or_duplicate_json_surface(self):
        self.assertEqual(request(body()),('application/pdf',b'synthetic PDF'))
        for raw in (b'{}',b'{"version":1,"version":1}',b'{"x":NaN}',body()+b'{}'):
            with self.subTest(raw=raw),self.assertRaises(DocumentError):request(raw)
        value=json.loads(body())
        for change in ({'version':True},{'url':'https://private'},{'content_type':'image/webp'},{'document_base64':'invalid?'}):
            with self.assertRaises(DocumentError):request(json.dumps({**value,**change}).encode())

    def test_native_offsets_use_utf16_and_cover_every_nonempty_line(self):
        value=answer(content='合成😀\nODO 123456 km')
        self.assertEqual(value['pages'][0]['blocks'][1]['source']['start'],5)
        result(value)
        changed=copy.deepcopy(value);changed['pages'][0]['blocks'].pop()
        with self.assertRaises(DocumentError):result(changed)
        changed=copy.deepcopy(value);changed['pages'][0]['blocks'][1]['source']['start']=4
        with self.assertRaises(DocumentError):result(changed)

    def test_unknown_native_confidence_and_no_fake_ocr_flag(self):
        value=answer()
        for change in ('confidence','ocr','partial','page','extra'):
            current=copy.deepcopy(value)
            if change=='confidence':current['pages'][0]['blocks'][0]['confidence']=.99
            elif change=='ocr':current['image_recognition_performed']=True
            elif change=='partial':current['all_pages_processed']=False
            elif change=='page':current['pages'][0]['page']=True
            else:current['diagnosis']='invented'
            with self.subTest(change=change),self.assertRaises(DocumentError):result(current)

    def test_ocr_polygon_must_be_actual_finite_convex_and_inside_raster(self):
        polygon([[1,1],[20,1],[20,10],[1,10]],30,20)
        for points in ([[0,0],[20,20],[0,20],[20,0]],[[0,0],[0,0],[1,1],[2,2]],[[0,0],[31,0],[31,10],[0,10]],[[False,0],[20,0],[20,10],[0,10]],[[0,0],[float('nan'),0],[20,10],[0,10]]):
            with self.subTest(points=points),self.assertRaises(DocumentError):polygon(points,30,20)

    def test_text_and_total_budget_never_silently_truncate(self):
        for content in ('x'*501,'a\0b','a\ud800'):
            with self.subTest(content=repr(content)),self.assertRaises(DocumentError):result(answer(content=content))
        value=answer(content=('x'*100+'\n')*40)
        value['page_count']=2;value['pages'].append(copy.deepcopy(value['pages'][0]));value['pages'][1]['page']=2
        with self.assertRaises(DocumentError) as caught:result(value)
        self.assertEqual('LIMIT_EXCEEDED',caught.exception.code)


if __name__=='__main__':unittest.main()
