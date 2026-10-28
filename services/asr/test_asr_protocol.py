"""Synthetic MP3 frames and candidate protocol only; no speech recognition claim."""
import base64
import copy
import json
import sys
from pathlib import Path
import unittest
sys.path.insert(0,str(Path(__file__).parent/'src'))
from asr_protocol import audio_request,mp3_duration,result,loads,InvalidRequest,MAX_BODY

def audio(frames=28):return (b'\xff\xf2\x68\xc0'+bytes(212))*frames
def request():return json.dumps({'version':1,'task':'ASR','duration_ms':1008,'audio_base64':base64.b64encode(audio()).decode()}).encode()
def answer(duration=1008,text='合成声音文字'):
    return {'version':1,'task':'ASR','source':'FASTER_WHISPER','status':'TRANSCRIBED','language':'zh','duration_ms':duration,'segments':[{'start_ms':0,'end_ms':min(duration,900),'text':text,'confidence':None}],'requires_confirmation':True,'audio_recognition_performed':True}

class AudioProtocolTest(unittest.TestCase):
    def test_frame_duration_is_bound_to_request(self):
        data,duration=audio_request(request());self.assertEqual(audio(),data);self.assertEqual(1008,duration)
        self.assertEqual(59976,mp3_duration(audio(1666)))
    def test_id3_tag_and_padding_are_bounded(self):
        self.assertEqual(1008,mp3_duration(b'ID3\x04\0\0\0\0\0\x04test'+audio()))
        padded=(b'\xff\xf2\x6a\xc0'+bytes(213))*28
        self.assertEqual(1008,mp3_duration(padded))
        for value in [b'ID3\x04\0\1\0\0\0\0'+audio(),b'ID3\x04\0\0\xff\0\0\0'+audio(),b'ID3'+audio()]:
            with self.assertRaises(InvalidRequest):mp3_duration(value)
    def test_unsupported_rate_channels_and_partial_or_excess_frames_refused(self):
        for value in [b'',audio(27),audio(1667),audio()+b'x',audio()[:-1],audio().replace(b'\xff\xf2\x68\xc0',b'\xff\xfb\x68\xc0'),b'x'*3145729]:
            with self.assertRaises(InvalidRequest):mp3_duration(value)
    def test_strict_json_keys_bool_version_duration_and_nonfinite(self):
        for raw in [b'{"version":1,"version":1}',b'{}{}',b'{"x":NaN}',b'{',b'null']:
            with self.assertRaises(InvalidRequest):audio_request(raw)
        original=loads(request())
        for change in [{'version':True},{'task':'OCR'},{'duration_ms':True},{'duration_ms':1009},{'audio_base64':'http://private/image'},{'url':'remote'}]:
            with self.assertRaises(InvalidRequest):audio_request(json.dumps({**original,**change}).encode())
        with self.assertRaises(InvalidRequest):audio_request(b'x'*(MAX_BODY+1))

class CandidateProtocolTest(unittest.TestCase):
    def test_unknown_confidence_and_empty_are_distinct(self):
        self.assertIsNone(result(answer(),1008)['segments'][0]['confidence'])
        value=answer();value.update(status='EMPTY',language=None,segments=[]);self.assertEqual('EMPTY',result(value,1008)['status'])
    def test_flags_type_identity_and_duration_exact(self):
        for key,value in [('version',True),('task','OTHER'),('source','MOCK'),('duration_ms',1009),('requires_confirmation',False),('audio_recognition_performed',False),('language','ZH')]:
            data=answer();data[key]=value
            with self.assertRaises(InvalidRequest):result(data,1008)
        for duration in [True,999,60001]:
            with self.assertRaises(InvalidRequest):result(answer(),duration)
    def test_nonfinite_scores_controls_surrogates_and_unsupported_fields_refused(self):
        for text in ['', ' x', 'x\n', 'x\x7f', '\ud800', 'x'*501]:
            with self.assertRaises(InvalidRequest):result(answer(text=text),1008)
        for score in [True,float('nan'),float('inf'),-.1,1.1,'unknown']:
            data=answer();data['segments'][0]['confidence']=score
            with self.assertRaises(InvalidRequest):result(data,1008)
        data=answer();data['raw_audio']='private'
        with self.assertRaises(InvalidRequest):result(data,1008)
    def test_time_ranges_and_total_output_limit_are_not_truncated(self):
        for start,end in [(-1,1),(1,1),(1,1009),(True,900)]:
            data=answer();data['segments'][0].update(start_ms=start,end_ms=end)
            with self.assertRaises(InvalidRequest):result(data,1008)
        data=answer();data['segments']=[{'start_ms':0,'end_ms':400,'text':'a'*500,'confidence':None},{'start_ms':400,'end_ms':900,'text':'b'*500,'confidence':None}]
        with self.assertRaises(InvalidRequest):result(data,1008)
        data['segments'][1]['text']='b'*499;self.assertEqual(2,len(result(data,1008)['segments']))
        data['segments'][1]['start_ms']=399
        with self.assertRaises(InvalidRequest):result(data,1008)
    def test_utf16_budget_and_maximum_segments(self):
        result(answer(text='😀'*250),1008)
        with self.assertRaises(InvalidRequest):result(answer(text='😀'*251),1008)
        data=answer();data['segments']=[{'start_ms':i,'end_ms':i+1,'text':'x','confidence':None} for i in range(51)]
        with self.assertRaises(InvalidRequest):result(data,1008)

if __name__=='__main__':unittest.main()
