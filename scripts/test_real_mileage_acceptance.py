import copy
import contextlib
import io
from pathlib import Path
import unittest
from unittest.mock import patch
import run_real_mileage_acceptance as runner
from run_real_mileage_acceptance import ROOT, EXPECTED, CHECKS, NATIVE_CHECKS, gateway_audit, native_audit, local_model_root

class RealMileageProofTest(unittest.TestCase):
    def setUp(self):
        self.proof = {key: True for key in CHECKS}
        rows = []
        for name, status in EXPECTED.items():
            path = '/api/file/upload' if name == 'upload' else '/api/vehicle/mileage-recognition'
            if name == 'query_rejected': path += '?owner_id=991'
            if name == 'signed_access': path = '/api/file/17/access'
            rows.append({'name': name, 'status': status, 'path': path, 'method': 'GET' if name == 'signed_access' else 'POST', 'passed': True})
        self.proof['cases'] = rows

    def test_complete_report_requires_twelve_actual_routes(self):
        self.assertEqual(12, gateway_audit(self.proof)['requests'])

    def test_each_database_storage_and_cleanup_check_required(self):
        for name in CHECKS:
            with self.subTest(name=name):
                report = copy.deepcopy(self.proof); report[name] = False
                with self.assertRaises(ValueError): gateway_audit(report)

    def test_missing_duplicate_or_extra_case_rejected(self):
        for change in ('missing', 'duplicate', 'extra'):
            report = copy.deepcopy(self.proof)
            if change == 'missing': report['cases'].pop()
            elif change == 'duplicate': report['cases'][-1] = report['cases'][0]
            else: report['cases'].append({**report['cases'][0], 'name': 'unexpected'})
            with self.assertRaises(ValueError): gateway_audit(report)

    def test_wrong_method_path_status_or_success_rejected(self):
        for key, value in [('method', 'GET'), ('path', '/api/other'), ('status', 503), ('passed', False)]:
            report = copy.deepcopy(self.proof); report['cases'][0][key] = value
            with self.assertRaises(ValueError): gateway_audit(report)

    def test_signed_access_must_have_real_positive_identifier(self):
        for path in ['/api/file/0/access', '/api/file/17/access?token=private', '/api/input-materials/17/access']:
            report = copy.deepcopy(self.proof)
            next(row for row in report['cases'] if row['name'] == 'signed_access')['path'] = path
            with self.assertRaises(ValueError): gateway_audit(report)

    def test_workspace_model_path_keeps_exact_files_without_unicode_prefix(self):
        self.assertEqual(Path('.cache/ocr-prepared'), local_model_root(ROOT / '.cache/ocr-prepared'))
        outside = ROOT.parent / 'external-models'
        self.assertEqual(outside.resolve(), local_model_root(outside))

    def test_missing_explicit_flag_stops_before_creating_run(self):
        with patch('sys.argv', ['runner']), patch.object(runner.uuid, 'uuid4') as create, contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as error: runner.main()
            self.assertEqual(2, error.exception.code); create.assert_not_called()

    def test_native_or_reused_identity_stops_before_creating_run(self):
        for key in ['AUTOCARE_NATIVE_MATERIAL_RUN_ID', 'AUTOCARE_REAL_MILEAGE_RUN_ID']:
            with patch('sys.argv', ['runner', '--allow-isolated-real-ocr']), patch.object(runner.platform, 'system', return_value='Windows'), \
                 patch.dict(runner.os.environ, {key: 'a' * 32}), patch.object(runner.uuid, 'uuid4') as create, contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit): runner.main()
                create.assert_not_called()

    def test_native_requires_every_ui_database_and_cleanup_fact(self):
        ui={k:True for k in ['passed','session_cleared','system_api_restored','proxy_closed','local_reference_released']}
        ui.update(checks=[{'name':k,'passed':True} for k in NATIVE_CHECKS],counts={'upload':1,'recognition':2,'archive':1})
        database={k:True for k in ['passed','zero_archives_before_save','one_archive_saved','correct_mileage_and_file','vehicle_mileage_unchanged','signed_download_matches','no_input_material_created']}
        database['checkpoints']=['candidate','cancelled','applied']
        self.assertEqual(ui,native_audit(ui,database)['ui'])
        for kind in ['missing_check','failed_check','duplicate_checkpoint','bad_count','cleanup','database']:
            a,b=copy.deepcopy(ui),copy.deepcopy(database)
            if kind=='missing_check':a['checks'].pop()
            elif kind=='failed_check':a['checks'][0]['passed']=False
            elif kind=='duplicate_checkpoint':b['checkpoints']=['candidate']*3
            elif kind=='bad_count':a['counts']['archive']=2
            elif kind=='cleanup':a['session_cleared']=False
            else:b['one_archive_saved']=False
            with self.subTest(kind=kind),self.assertRaises(ValueError):native_audit(a,b)

if __name__ == '__main__': unittest.main()
