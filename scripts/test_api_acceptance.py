import copy
import json
from pathlib import Path
import tempfile
import unittest
from generate_api_acceptance import ROOT, generate, operations
from run_api_acceptance import audit_newman


class AcceptanceAssetsTest(unittest.TestCase):
    def setUp(self):
        self.spec = {'paths': {'/api/private/{id}': {'post': {'operationId': 'private_write', 'security': [{'bearerAuth': []}],
            'parameters': [{'name': 'Idempotency-Key', 'in': 'header', 'required': True}],
            'requestBody': {'content': {'application/json': {'schema': {'type': 'object'}}}}}},
            '/api/public': {'get': {'operationId': 'public_read', 'security': []}}}}

    def test_every_contract_is_inventory_but_not_test_pass(self):
        assets = generate(self.spec)
        inventory = assets['acceptance_inventory.json']
        self.assertEqual(2, inventory['operations']); self.assertEqual(1, inventory['anonymous_probes'])
        self.assertFalse(inventory['full_api_acceptance_passed'])
        for item in inventory['items']:
            self.assertEqual('NOT_RUN', item['cases']['normal'])
            self.assertEqual('NOT_RUN', item['cases']['boundary'])
            self.assertEqual('NOT_RUN', item['cases']['error'])

    def test_public_alternative_and_security_override_are_not_probed(self):
        self.spec['security'] = [{'bearerAuth': []}]
        self.spec['paths']['/api/private/{id}']['post']['security'] = [{}, {'bearerAuth': []}]
        self.assertEqual(0, generate(self.spec)['acceptance_inventory.json']['anonymous_probes'])
        del self.spec['paths']['/api/private/{id}']['post']['security']
        self.assertEqual(1, generate(self.spec)['acceptance_inventory.json']['anonymous_probes'])

    def test_fallback_id_is_deterministic_and_does_not_modify_contract(self):
        del self.spec['paths']['/api/public']['get']['operationId']
        before = copy.deepcopy(self.spec)
        self.assertEqual(generate(self.spec), generate(self.spec))
        self.assertEqual(before, self.spec)

    def test_duplicate_operation_id_fails(self):
        self.spec['paths']['/api/public']['get']['operationId'] = 'private_write'
        with self.assertRaises(ValueError): operations(self.spec)

    def test_business_templates_require_explicit_enable_fixture_status_and_write_key(self):
        assets = generate(self.spec)
        item = assets['prepared.postman_collection.json']['item'][0]
        script = '\n'.join(item['event'][0]['script']['exec'])
        for text in ['enable_prepared_requests', 'skipRequest', 'status_private_write', 'body_private_write', 'access_token', 'private_write_Idempotency_Key']:
            self.assertIn(text, script)
        self.assertIn('{{private_write_id}}', item['request']['url'])

    def test_tracked_scope_and_assets_match_contract(self):
        spec = json.loads((ROOT / 'docs/api/openapi.json').read_text(encoding='utf-8'))
        assets = generate(spec)
        self.assertEqual(352, assets['acceptance_inventory.json']['operations'])
        self.assertEqual(338, assets['acceptance_inventory.json']['anonymous_probes'])
        for name, expected in assets.items():
            actual = json.loads((ROOT / 'docs/testing/postman' / name).read_text(encoding='utf-8'))
            self.assertEqual(expected, actual, name)


class NewmanEvidenceTest(unittest.TestCase):
    def setUp(self):
        cache = ROOT / '.cache'; cache.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='api-evidence-', dir=cache)
        self.addCleanup(self.cleanup)
        self.path = Path(self.temp.name) / 'newman.json'
        self.expected = {'private_read': ('GET', '/api/private/1')}
        self.report = {'run': {'failures': [], 'executions': [{'item': {'name': 'private_read'},
            'request': {'method': 'GET', 'url': {'path': ['api', 'private', '1']}},
            'response': {'code': 401}, 'assertions': [{'assertion': str(i)} for i in range(3)]}]}}

    def cleanup(self):
        path = Path(self.temp.name).resolve()
        assert path.is_relative_to((ROOT / '.cache').resolve()) and path.name.startswith('api-evidence-')
        self.temp.cleanup()

    def check(self):
        self.path.write_text(json.dumps(self.report), encoding='utf-8')
        return audit_newman(self.path, self.expected)

    def test_complete_successful_report(self):
        self.assertEqual(3, self.check()['assertions'])

    def test_missing_duplicate_failed_skipped_or_wrong_status_rejected(self):
        original = copy.deepcopy(self.report)
        for kind in ('missing', 'duplicate', 'failure', 'skipped', 'status'):
            with self.subTest(kind=kind):
                self.report = copy.deepcopy(original)
                run = self.report['run']
                if kind == 'missing': run['executions'] = []
                elif kind == 'duplicate': run['executions'] *= 2
                elif kind == 'failure': run['failures'] = [{'error': 'failure'}]
                elif kind == 'skipped': run['executions'][0]['assertions'][0]['skipped'] = True
                else: run['executions'][0]['response']['code'] = 200
                with self.assertRaises(ValueError): self.check()

    def test_named_item_cannot_probe_the_wrong_route_or_method(self):
        self.report['run']['executions'][0]['request']['method'] = 'POST'
        with self.assertRaises(ValueError): self.check()
        self.report['run']['executions'][0]['request']['method'] = 'GET'
        self.report['run']['executions'][0]['request']['url']['path'] = ['api', 'unrelated']
        with self.assertRaises(ValueError): self.check()


if __name__ == '__main__':
    unittest.main()
