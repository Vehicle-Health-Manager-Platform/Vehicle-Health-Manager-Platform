import copy
import subprocess
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import run_linux_mileage_acceptance as runner


class LinuxMileageTest(unittest.TestCase):
    def test_no_opt_in_creates_nothing(self):
        with patch.object(sys, 'argv', ['runner']), patch.object(runner.Path, 'mkdir') as create:
            with self.assertRaises(SystemExit): runner.main()
            create.assert_not_called()

    def test_reused_identity_creates_nothing(self):
        with patch.object(sys, 'argv', ['runner', '--allow-isolated-linux-mileage']), \
                patch.object(runner.platform, 'system', return_value='Windows'), \
                patch.dict(runner.os.environ, {'AUTOCARE_REAL_MILEAGE_RUN_ID': 'a'*32}), \
                patch.object(runner.Path, 'mkdir') as create:
            with self.assertRaises(SystemExit): runner.main()
            create.assert_not_called()

    def test_linux_connection_counts_are_distinct_from_model_calls(self):
        for native, count in [(False, 1), (True, 3)]:
            proof = {'provider_transport': 'linux-private-network-via-loopback', 'provider_tcp_connections': count}
            self.assertEqual(count, runner.transport_audit(proof, native)['successful_provider_tcp_connections'])
            self.assertFalse(runner.transport_audit(proof, native)['model_internal_calls_measured'])
            for invalid in (None, True, '3', 0, 2, 4):
                wrong = copy.deepcopy(proof); wrong['provider_tcp_connections'] = invalid
                with self.subTest(native=native, value=invalid), self.assertRaises(ValueError): runner.transport_audit(wrong, native)

    def test_wrong_transport_cannot_supply_linux_evidence(self):
        for value in (None, 'host-isolated-worker', 'linux-private-network'):
            with self.assertRaises(ValueError): runner.transport_audit({'provider_transport': value, 'provider_tcp_connections': 1}, False)

    def test_stop_does_not_touch_finished_or_unstarted_process(self):
        from unittest.mock import Mock
        with patch.object(runner.subprocess, 'run') as command:
            runner.stop_process(None); runner.stop_process(Mock(poll=lambda: 0))
            command.assert_not_called()

    def test_inspection_failures_are_not_successful_cleanup(self):
        result = subprocess.CompletedProcess([], 1, '', 'permission denied')
        with self.assertRaises(RuntimeError): runner.inspected_object(result, 'owned')

    def ingress(self):
        return {'HostConfig': {'Privileged': False, 'ReadonlyRootfs': True, 'Memory': 128*1024*1024,
            'MemorySwap': 128*1024*1024, 'NanoCpus': 500000000, 'PidsLimit': 32,
            'CapDrop': ['ALL'], 'CapAdd': None, 'SecurityOpt': ['no-new-privileges']},
            'Config': {'User': '10001:10001'}, 'Mounts': [{'Destination': '/ingress.py', 'RW': False}],
            'NetworkSettings': {'Ports': {'8091/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '12345'}]},
                'Networks': {'private': {}, 'transit': {}}}}

    def test_ingress_rejects_expanded_privilege_mounts_networks_and_public_ports(self):
        self.assertEqual(12345, runner.ingress_audit(self.ingress(), 'private', 'transit'))
        patches = [lambda d: d['HostConfig'].update(Privileged=True),
            lambda d: d['HostConfig'].update(ReadonlyRootfs=False),
            lambda d: d['HostConfig'].update(CapAdd=['NET_ADMIN']),
            lambda d: d['HostConfig'].update(Memory=0),
            lambda d: d['HostConfig'].update(SecurityOpt=[]),
            lambda d: d['Config'].update(User='root'),
            lambda d: d['Mounts'].append({'Destination': '/var/run/docker.sock', 'RW': True}),
            lambda d: d['Mounts'][0].update(RW=True),
            lambda d: d['NetworkSettings']['Networks'].update(extra={}),
            lambda d: d['NetworkSettings']['Ports']['8091/tcp'][0].update(HostIp='0.0.0.0'),
            lambda d: d['NetworkSettings']['Ports']['8091/tcp'][0].update(HostPort='0')]
        for change in patches:
            item = self.ingress(); change(item)
            with self.assertRaises(ValueError): runner.ingress_audit(item, 'private', 'transit')

    def test_upload_cleanup_verifies_labels_and_continues_after_failure(self):
        state = {(False, 'foreign'): {'Config': {'Labels': {'autocare.upload.run': 'other'}}},
            (False, 'broken'): {'Config': {'Labels': {'autocare.upload.run': 'run'}}},
            (True, 'owned'): {'Labels': {'autocare.upload.run': 'run'}}}
        removed = []
        def docker(args): return SimpleNamespace(stdout=' '.join(name for (network, name) in state if network == (args[0] == 'network')))
        def inspect(name, network): return state.get((network, name))
        def remove(name, network):
            if name == 'broken': raise RuntimeError('daemon unavailable')
            removed.append((network, name)); state.pop((network, name))
        self.assertFalse(runner.cleanup_uploads('run', docker, inspect, remove))
        self.assertEqual([(True, 'owned')], removed)
        self.assertEqual(2, len(state))

    def test_empty_upload_listing_is_valid_but_daemon_failure_is_not(self):
        from unittest.mock import Mock
        self.assertTrue(runner.cleanup_uploads('run', Mock(return_value=SimpleNamespace(stdout='')), Mock(), Mock()))
        self.assertFalse(runner.cleanup_uploads('run', Mock(side_effect=RuntimeError('daemon unavailable')), Mock(), Mock()))


if __name__ == '__main__': unittest.main()
