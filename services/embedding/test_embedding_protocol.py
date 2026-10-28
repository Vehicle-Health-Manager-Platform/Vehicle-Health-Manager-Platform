import copy
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).parent/'src'))
from embedding_protocol import EmbeddingError,request,result,MAX_BODY
HASH='a'*64
def body(texts=None):return json.dumps({'version':1,'profile_hash':HASH,'texts':['合成测试'] if texts is None else texts},ensure_ascii=True).encode()
def answer(count=1):return {'version':1,'profile_hash':HASH,'vectors':[[1.0]+[0.0]*511 for _ in range(count)]}
class ProtocolTest(unittest.TestCase):
    def test_exact_input_and_hash(self):
        self.assertEqual(request(body(),HASH),['合成测试'])
        for raw in [b'{}',body()+b'{}',b'{"version":1,"version":1}',b'{"v":NaN}']:
            with self.assertRaises(EmbeddingError):request(raw,HASH)
        for change in [{'version':True},{'url':'https://private'},{'profile_hash':'b'*64},{'texts':'bad'}]:
            with self.assertRaises(EmbeddingError):request(json.dumps({**json.loads(body()),**change}).encode(),HASH)
    def test_utf16_control_surrogate_and_batch_budgets(self):
        for texts in [[],['x']*5,[''],[' '],['x\0'],['x\t'],['x\u0085'],['x\u200e'],['\ud800'],['😀'*513],['x'*1025],[None],[True]]:
            with self.subTest(texts=str(texts)[:25]),self.assertRaises(EmbeddingError):request(body(texts),HASH)
        self.assertEqual(request(body(['😀'*512]),HASH),['😀'*512])
        with self.assertRaises(EmbeddingError):request(b'x'*(MAX_BODY+1),HASH)
    def test_result_requires_finite_unit_fixed_dimension_and_count(self):
        result(answer(4),HASH,4)
        for kind in ['hash','count','dimension','zero','nan','infinity','boolean','huge_integer','norm','extra','version']:
            value=answer()
            if kind=='hash':value['profile_hash']='b'*64
            elif kind=='count':value['vectors']=[]
            elif kind=='dimension':value['vectors'][0].pop()
            elif kind=='zero':value['vectors'][0][0]=0
            elif kind=='nan':value['vectors'][0][0]=float('nan')
            elif kind=='infinity':value['vectors'][0][0]=float('inf')
            elif kind=='boolean':value['vectors'][0][0]=True
            elif kind=='huge_integer':value['vectors'][0][0]=10**400
            elif kind=='norm':value['vectors'][0][0]=.5
            elif kind=='extra':value['text']='private'
            else:value['version']=True
            with self.subTest(kind=kind),self.assertRaises(EmbeddingError):result(value,HASH,1)
    def test_unknown_errors_are_not_provider_diagnostics(self):self.assertEqual(str(EmbeddingError('private path')),'UNAVAILABLE')
if __name__=='__main__':unittest.main()
