"""Synthetic OCR lines/images and explicit engine doubles, never document accuracy evidence."""
import io
import base64
import json
import sys
import threading
import unittest
from pathlib import Path
from http.client import HTTPConnection
sys.path.insert(0, str(Path(__file__).parent / 'src'))
from PIL import Image
from license_fields import parse_license
from recognition import decode_image, load_request, InvalidImage
from server import create_server
from worker import ProcessRecognizer

VIN = 'LTEST223456789012'
TOKEN = 'synthetic-license-test-token-000000000001'
def line(text, confidence=.95): return {'text': text, 'confidence': confidence}
def body(scene='license'):
    stream=io.BytesIO();Image.new('RGB',(30,20),'white').save(stream,format='PNG')
    return json.dumps({'scene':scene,'image_base64':base64.b64encode(stream.getvalue()).decode()}).encode()
def candidates(out,key): return next(field for field in out['fields'] if field['key']==key)['candidates']

class LicenseParserTest(unittest.TestCase):
    def test_complete_same_line_labels_only_and_no_private_text(self):
        out=parse_license([line('号牌号码：粤B12345'),line('车辆识别代号 '+VIN),line('所有人：合成姓名'),line('住址：合成私有地址')])
        self.assertEqual('CANDIDATE',out['status']);self.assertEqual('粤B12345',candidates(out,'plate')[0]['value']);self.assertEqual(VIN,candidates(out,'vin')[0]['value'])
        self.assertFalse(out['image_recognition_performed']);self.assertTrue(out['requires_confirmation']);self.assertNotIn('合成',json.dumps(out,ensure_ascii=False))
    def test_split_labels_use_lower_confidence_and_only_immediate_next_line(self):
        out=parse_license([line('VIN',.5),line(VIN,.99),line('号牌号码'),line('粤B12345')]);self.assertEqual('LOW_CONFIDENCE',out['status']);self.assertEqual(.5,candidates(out,'vin')[0]['confidence'])
        out=parse_license([line('VIN'),line('所有人：其他'),line(VIN)]);self.assertEqual('NO_CANDIDATE',out['status'])
    def test_unlabelled_or_embedded_private_text_is_never_used(self):
        self.assertEqual('NO_CANDIDATE',parse_license([line(VIN),line('粤B12345'),line('姓名 VIN '+VIN),line('VIN '+VIN+' 住址 合成地址')])['status'])
    def test_no_guess_repairs_and_no_unicode_expansion(self):
        for value in ['LTEST22345678901O','LTEST22345678901I','LTEST22345678901Q','LTEST 23456789012','A'*15+'ß',VIN+'1',VIN[:-1]]:
            self.assertEqual('NO_CANDIDATE',parse_license([line('VIN '+value)])['status'])
    def test_lowercase_is_canonical_after_ascii_check(self):
        self.assertEqual(VIN,candidates(parse_license([line('vin '+VIN.lower())]),'vin')[0]['value'])
    def test_partial_plate_and_vin_and_threshold(self):
        self.assertEqual('PARTIAL',parse_license([line('PLATE 粤B12345')])['status'])
        self.assertEqual('PARTIAL',parse_license([line('VIN '+VIN,.9)])['status'])
        self.assertEqual('LOW_CONFIDENCE',parse_license([line('VIN '+VIN,.8999)])['status'])
    def test_ordinary_and_new_energy_plates_and_invalid_plate(self):
        for value in ['粤B12345','沪AD12345']:
            self.assertEqual(value,candidates(parse_license([line('号牌号码 '+value)]),'plate')[0]['value'])
        for value in ['粤 B12345','京A1234','粤B1234567','ABC12345']:
            self.assertEqual('NO_CANDIDATE',parse_license([line('车牌号 '+value)])['status'])
    def test_high_confidence_traditional_province_misread_is_not_repaired(self):
        # Observed real Paddle on a synthetic 粤 image: it read 粵 at high confidence.
        out=parse_license([line('号牌号码：粵B12345',.982698),line('车辆识别代号：'+VIN,.998677)])
        self.assertEqual('PARTIAL',out['status']);self.assertEqual([],candidates(out,'plate'))
        self.assertEqual(VIN,candidates(out,'vin')[0]['value'])
    def test_multiple_values_remain_ambiguous_and_duplicates_never_promote_confidence(self):
        out=parse_license([line('VIN '+VIN),line('VIN '+'LTEST323456789012'),line('车牌号 粤B12345')]);self.assertEqual('AMBIGUOUS',out['status']);self.assertEqual(2,len(candidates(out,'vin')))
        out=parse_license([line('VIN '+VIN,.4),line('VIN '+VIN,.99)]);self.assertEqual('LOW_CONFIDENCE',out['status']);self.assertEqual(.4,candidates(out,'vin')[0]['confidence'])
    def test_empty_has_both_empty_fields(self):
        out=parse_license([]);self.assertEqual('NO_CANDIDATE',out['status']);self.assertEqual(['plate','vin'],[f['key'] for f in out['fields']]);self.assertTrue(all(not f['candidates'] for f in out['fields']))
    def test_line_score_type_text_and_total_budgets_fail_closed(self):
        for data in [[line('VIN '+VIN,True)],[line('x',float('nan'))],[line('x',1.1)],[line('x','0.9')],[line('x\0')],[line('x\ud800')],[line('x'*501)],[{'text':'x','confidence':.9,'private':'x'}],[line('x')]*101]:
            with self.assertRaises(ValueError): parse_license(data)
        with self.assertRaises(ValueError): parse_license([line('车牌号 粤B'+str(10000+i)) for i in range(11)])
    def test_duplicate_trailing_nonfinite_and_unsupported_scene_rejected(self):
        for raw in [b'{"scene":"license","scene":"mileage"}',b'{}{}',b'{"x":NaN}']:
            with self.assertRaises(InvalidImage): load_request(raw)
        with decode_image(load_request(body())) as image: self.assertEqual((30,20),image.size)
        with self.assertRaises(InvalidImage): decode_image({'scene':'general','image_base64':'AA=='})

class LicenseHttpProcessTest(unittest.TestCase):
    def test_real_http_and_restricted_child_dispatch_both_scenes_without_raw_text(self):
        child=Path(__file__).with_name('test_license_worker_child.py')
        worker=ProcessRecognizer(timeout=2,startup_timeout=5,_command=[sys.executable,'-u',str(child)])
        server=create_server(('127.0.0.1',0),worker,TOKEN);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            original_pid=worker.pid
            for scene in ['license','mileage','license']:
                connection=HTTPConnection('127.0.0.1',server.server_port,timeout=5)
                connection.request('POST','/recognize',body(scene),{'Content-Type':'application/json','Authorization':'Bearer '+TOKEN})
                response=connection.getresponse();data=json.loads(response.read());connection.close()
                self.assertEqual(200,response.status);self.assertEqual(scene,data['scene']);self.assertEqual('CANDIDATE',data['status']);self.assertTrue(data['image_recognition_performed']);self.assertNotIn('私有',json.dumps(data,ensure_ascii=False))
            self.assertEqual(original_pid,worker.pid)
            with self.assertRaises(InvalidImage):worker.recognize_request(b'{"scene":"license","scene":"mileage"}')
            self.assertEqual(original_pid,worker.pid)
        finally: server.shutdown();server.server_close();thread.join()

if __name__=='__main__':unittest.main()
