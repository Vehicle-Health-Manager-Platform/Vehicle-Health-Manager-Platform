import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock
from linux_mileage_faults import Faults, GatewayControls, gateway_fault_audit, inflight_audit


class FaultSafetyTest(unittest.TestCase):
    def test_signal_rejects_missing_foreign_or_stopped_container_before_exec(self):
        for item in (None, {'Config': {'Labels': {}}}, {'Config': {'Labels': {'autocare.ocr.acceptance': 'run'}}, 'State': {'Running': False}}):
            execute = Mock()
            faults = Faults(run='run', server='owned', inspect=Mock(return_value=item), execute=execute,
                identity=Mock(), port=1, token='private', raw=b'{}')
            with self.assertRaises(ValueError): faults.signal({'pid': 12, 'start': '1'}, 'STOP')
            execute.assert_not_called()

    def test_signal_cannot_pass_without_positive_injection_proof(self):
        item = {'Config': {'Labels': {'autocare.ocr.acceptance': 'run'}}, 'State': {'Running': True}}
        faults = Faults(run='run', server='owned', inspect=Mock(return_value=item), execute=Mock(return_value={}),
            identity=Mock(), port=1, token='private', raw=b'{}')
        with self.assertRaises(ValueError): faults.signal({'pid': 12, 'start': '1'}, 'STOP')
        self.assertEqual([], faults.signals)

    def test_handshake_rejects_wrong_command_and_does_not_signal(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary); (work/'fault-stop-timeout.request').write_text('kill arbitrary pid')
            faults = Mock(); controls = GatewayControls(faults, work); controls.run()
            self.assertEqual('ValueError', controls.error); faults.signal.assert_not_called()
            self.assertFalse((work/'fault-stop-timeout.json').exists())
            with self.assertRaises(ValueError): controls.proof()

    def gateway(self):
        return {**{k: True for k in ('passed','no_archive_created','vehicle_mileage_unchanged','owned_image_unchanged','no_input_material_created')},
            'sql_checkpoints': 8, 'cases': {n: {'status': s, 'elapsed_seconds': 8 if n == 'timeout' else 15 if n == 'transport_timeout' else .2, 'passed': True}
                for n, s in [('timeout',503),('timeout_recovery',200),('busy',503),('exit',503),('exit_recovery',200),('transport_timeout',503),('transport_recovery',200)]}}

    def test_gateway_requires_failures_recovery_sql_and_real_deadline(self):
        self.assertTrue(gateway_fault_audit(self.gateway())['passed'])
        changes = [lambda p: p.update(no_archive_created=False), lambda p: p.update(sql_checkpoints=True),
            lambda p: p['cases'].pop('exit_recovery'), lambda p: p['cases']['timeout'].update(status=200),
            lambda p: p['cases']['timeout'].update(elapsed_seconds=.1), lambda p: p['cases']['busy'].update(elapsed_seconds=4),
            lambda p: p['cases']['exit'].update(elapsed_seconds=float('nan'))]
        for change in changes:
            p = self.gateway(); change(p)
            with self.assertRaises(ValueError): gateway_fault_audit(p)

    def inflight(self):
        names = ('cancel','hide','leave','actor','context')
        return {'passed': True, 'context_event_assisted': True, 'hide_via_navigation': True,
            'cases': [{'name': n, 'passed': True, 'no_late_candidate': True, 'no_form_write': True, 'no_archive_request': True} for n in names],
            'transport': [{'name': n, 'status': 200, 'real_response_held': True, 'response_bytes_unchanged': True} for n in names]}

    def test_inflight_requires_all_five_contexts_and_actual_delayed_bytes(self):
        self.assertTrue(inflight_audit(self.inflight())['passed'])
        changes = [lambda p: p['cases'].pop(),lambda p: p['transport'][0].update(real_response_held=False),
            lambda p: p['transport'][1].update(response_bytes_unchanged=False),lambda p: p['cases'][2].update(no_form_write=False),
            lambda p: p['cases'][0].update(name='hide'),lambda p: p['transport'][0].update(status=503)]
        for change in changes:
            p = copy.deepcopy(self.inflight()); change(p)
            with self.assertRaises(ValueError): inflight_audit(p)


if __name__ == '__main__': unittest.main()
