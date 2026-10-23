import unittest
from generate_openapi import build_document


class LocalFinanceContractTest(unittest.TestCase):
    def test_read_contract_keeps_unknown_statement_and_exact_selection(self):
        op=build_document()['paths']['/api/admin/reconciliation']['get']
        self.assertEqual('local-finance-read-implemented',op['x-implementation-status'])
        self.assertEqual(['date','channel'],[p['name'] for p in op['parameters']])
        self.assertTrue(all(p['required'] for p in op['parameters']))
        self.assertNotIn('requestBody',op)
        schema=op['responses']['200']['content']['application/json']['schema']['allOf'][1]['properties']['data']
        statement=schema['properties']['channel_statement']['properties']
        self.assertEqual({'const':'UNAVAILABLE'},statement['state'])
        self.assertEqual({'type':'null'},statement['difference'])
        self.assertEqual({'type':'null'},statement['net_amount'])
        self.assertFalse(schema['additionalProperties'])
        self.assertEqual({'const':'no-store'},op['responses']['200']['headers']['Cache-Control']['schema'])
        self.assertTrue({'400','401','403','409','503'}.issubset(op['responses']))

    def test_missing_historical_evidence_is_not_hidden_by_local_implementation(self):
        import json
        from pathlib import Path
        data=json.loads((Path(__file__).resolve().parents[1]/'docs/api/CONTRACT_VERDICTS.json').read_text(encoding='utf-8'))
        rows={row['operation']:row for row in data['verdicts']}
        self.assertEqual('implemented-local',rows['GET /api/admin/reconciliation']['verdict'])
        self.assertEqual('blocked-external',rows['POST /api/auth/cloud-login']['verdict'])


if __name__=='__main__':
    unittest.main()
