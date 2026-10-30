"""A fresh audit process must verify JWTs without seed() globals."""
import json
import unittest
from unittest.mock import patch
import auth_sessions_api_cases as module
import test_auth_sessions_api_cases as fixtures
SECRET = fixtures.SECRET


class AuthSessionReauditTest(unittest.TestCase):
    def fixture(self):
        case = fixtures.AuthSessionsEvidenceTest()
        case.setUp()
        self.addCleanup(case.doCleanups)
        case.path.write_text(json.dumps(case.report), encoding='utf-8')
        return case

    def test_fresh_process_uses_private_context_and_does_not_export_it(self):
        case = self.fixture()
        case.tokens['_audit_jwt_secret'] = SECRET
        with patch.object(module, 'SECRET', ''):
            result = module.audit(case.path, case.collection, case.manifest, case.tokens)
        self.assertTrue(result['auth_request_bindings_verified'])
        self.assertNotIn(SECRET, json.dumps(result))

    def test_missing_or_wrong_context_is_rejected_even_with_seed_global(self):
        for value in (None, '', 'x' * 40):
            case = self.fixture()
            case.tokens.pop('_audit_jwt_secret', None)
            if value is not None:
                case.tokens['_audit_jwt_secret'] = value
            with self.subTest(value=value), patch.object(module, 'SECRET', SECRET), self.assertRaises(ValueError):
                module.audit(case.path, case.collection, case.manifest, case.tokens)
