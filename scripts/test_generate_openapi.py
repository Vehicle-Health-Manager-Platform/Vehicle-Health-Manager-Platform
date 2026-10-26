"""The generator must reproduce the delivered contract, without using that file as input."""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import generate_openapi as generator


class OpenApiGenerationTest(unittest.TestCase):
    def setUp(self):
        (generator.ROOT/'.cache').mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(prefix='openapi-generation-',dir=generator.ROOT/'.cache')
        self.addCleanup(self.temp.cleanup)
        self.output=Path(self.temp.name)/'openapi.json'

    def test_full_generation_reproduces_delivered_contract(self):
        delivered=generator.OUTPUT.read_text(encoding='utf-8')
        with patch.object(generator,'OUTPUT',self.output),patch('sys.argv',['generate_openapi.py']),contextlib.redirect_stdout(io.StringIO()):
            generator.main()
        self.assertEqual(delivered,self.output.read_text(encoding='utf-8'))

    def invoke(self,*args):
        with patch.object(generator,'OUTPUT',self.output),contextlib.redirect_stdout(io.StringIO()),contextlib.redirect_stderr(io.StringIO()):
            return generator.main(list(args))

    def test_check_accepts_current_file_without_writing(self):
        self.output.write_bytes(generator.OUTPUT.read_bytes())
        before=self.output.read_bytes(),self.output.stat().st_mtime_ns
        with patch.object(Path,'write_text',side_effect=AssertionError('check must not write')):
            self.invoke('--check')
        self.assertEqual(before,(self.output.read_bytes(),self.output.stat().st_mtime_ns))

    def test_check_rejects_semantic_drift_invalid_and_empty_files_without_repair(self):
        drift=generator.build_document()
        drift['paths']['/api/check/delivery/compare']['post']['parameters']=[{'name':'Idempotency-Key','in':'header','required':True}]
        for content in (json.dumps(drift,ensure_ascii=False,indent=2)+'\n','{',''):
            with self.subTest(content=content[:30]):
                self.output.write_text(content,encoding='utf-8');before=self.output.read_bytes()
                with patch.object(Path,'write_text',side_effect=AssertionError('check must not repair')),self.assertRaises(SystemExit) as error:
                    self.invoke('--check')
                self.assertEqual(1,error.exception.code)
                self.assertEqual(before,self.output.read_bytes())

    def test_missing_contract_is_not_created_by_check(self):
        with patch.object(Path,'write_text',side_effect=AssertionError('check must not create')),self.assertRaises(SystemExit) as error:
            self.invoke('--check')
        self.assertEqual(1,error.exception.code)
        self.assertFalse(self.output.exists())

    def test_two_default_generations_are_byte_stable(self):
        self.invoke();first=self.output.read_bytes()
        self.invoke();self.assertEqual(first,self.output.read_bytes())

    def test_build_does_not_read_or_require_the_output(self):
        self.output.write_text('invalid output must not become generator input',encoding='utf-8')
        with patch.object(generator,'OUTPUT',self.output):document=generator.build_document()
        self.assertEqual(json.loads(generator.OUTPUT.read_text(encoding='utf-8')),document)
        self.assertEqual('invalid output must not become generator input',self.output.read_text(encoding='utf-8'))

    def test_mileage_gateway_requires_consent_and_has_no_write_key(self):
        item=generator.build_document()['paths']['/api/vehicle/mileage-recognition']['post']
        self.assertEqual([{'bearerAuth':[]}],item['security'])
        self.assertFalse(item.get('parameters'))
        schema=item['requestBody']['content']['application/json']['schema']
        self.assertFalse(schema['additionalProperties'])
        self.assertTrue(schema['properties']['consent']['const'])
        self.assertEqual('MILEAGE_OCR_V1',schema['properties']['consent_version']['const'])
        self.assertTrue({'400','401','403','404','409','429','503'}.issubset(item['responses']))

    def test_pickup_mileage_has_independent_purpose_and_shop_scope(self):
        item=generator.build_document()['paths']['/api/check/pickup/mileage-recognition']['post']
        schema=item['requestBody']['content']['application/json']['schema']
        self.assertEqual([{'bearerAuth':[]}],item['security'])
        self.assertEqual('PICKUP_MILEAGE_OCR_V1',schema['properties']['consent_version']['const'])
        self.assertIn('order_id',schema['required'])
        self.assertNotIn('vehicle_id',schema['properties'])
        self.assertFalse(schema['additionalProperties'])
        self.assertFalse(item.get('parameters'))
        self.assertNotEqual(item['operationId'],generator.build_document()['paths']['/api/vehicle/mileage-recognition']['post']['operationId'])

    def test_license_gateway_has_independent_new_vehicle_consent_and_bounded_candidates(self):
        paths=generator.build_document()['paths'];item=paths['/api/vehicle/license-recognition']['post']
        self.assertEqual('license-owner-candidate-implemented-requires-document-acceptance',item['x-implementation-status'])
        self.assertEqual([{'bearerAuth':[]}],item['security'])
        self.assertFalse(item.get('parameters'))
        schema=item['requestBody']['content']['application/json']['schema']
        self.assertFalse(schema['additionalProperties'])
        self.assertEqual(['file_id','purpose','consent','consent_version'],schema['required'])
        self.assertEqual('NEW_VEHICLE',schema['properties']['purpose']['const'])
        self.assertEqual('NEW_VEHICLE_LICENSE_OCR_V1',schema['properties']['consent_version']['const'])
        self.assertNotIn('vehicle_id',schema['properties'])
        self.assertEqual({'200','400','401','403','404','409','429','503'},set(item['responses']))
        projection=item['responses']['200']['content']['application/json']['schema']['allOf'][1]['properties']['data']
        self.assertFalse(projection['additionalProperties'])
        self.assertEqual(True,projection['properties']['requires_confirmation']['const'])
        fields=projection['properties']['fields'];self.assertEqual((2,2),(fields['minItems'],fields['maxItems']))
        self.assertEqual(10,fields['items']['properties']['candidates']['maxItems'])
        self.assertTrue(paths['/api/vehicle/ocr-license']['get']['deprecated'])

    def test_delivery_compare_remains_read_only_strict_and_authorized(self):
        document=generator.build_document();item=document['paths']['/api/check/delivery/compare']['post']
        self.assertEqual(356,sum(len(v) for v in document['paths'].values()))
        self.assertEqual('delivery-compare-implemented',item['x-implementation-status'])
        self.assertEqual('本单车主或本店 MERCHANT/STAFF',item['x-roles'])
        self.assertEqual([{'bearerAuth':[]}],item['security'])
        self.assertFalse(any(p.get('name')=='Idempotency-Key' for p in item.get('parameters',[])))
        schema=item['requestBody']['content']['application/json']['schema']
        self.assertEqual(False,schema['additionalProperties'])
        self.assertEqual(['order_id'],schema['required'])
        self.assertEqual({'order_id':{'type':'integer','minimum':1,'maximum':9007199254740991}},schema['properties'])
        self.assertEqual({'200','400','401','403','404','409','503'},set(item['responses']))
        self.assertIn('43001',item['responses']['409']['description'])

    def test_r9_implementation_markers_survive_regeneration(self):
        paths=generator.build_document()['paths']
        self.assertEqual('ai-chat-implemented-requires-provider',paths['/api/ai/chat']['post']['x-implementation-status'])
        self.assertEqual('controlled-input-material-upload-implemented',paths['/api/input-materials/{purpose}/{vehicleId}/upload']['post']['x-implementation-status'])
        self.assertEqual('controlled-input-material-access-implemented',paths['/api/input-materials/{purpose}/{vehicleId}/{id}/access']['get']['x-implementation-status'])

    def test_builds_are_independent(self):
        first=generator.build_document();second=generator.build_document()
        first['paths']['/api/check/delivery/compare']['post']['responses']['404']['content'].clear()
        self.assertEqual(generator.build_document(),second)
        self.assertTrue(first['paths']['/api/check/delivery/compare']['post']['responses']['200']['content'])


if __name__=='__main__':unittest.main()
