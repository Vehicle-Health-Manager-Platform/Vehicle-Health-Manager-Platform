"""Ownership, restore boundaries and failure evidence for the isolated recovery runner."""
import copy
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import run_database_recovery as recovery


class RecoveryGuardTest(unittest.TestCase):
    def setUp(self):
        cache = recovery.ROOT / '.cache'
        cache.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='test-recovery-', dir=cache)
        self.addCleanup(self.cleanup)
        self.work = Path(self.temp.name)
        self.server = recovery.OwnedMySQL('a' * 32, 'source')
        self.server.container_id = 'b' * 64
        self.info = {'Id': 'b' * 64, 'Name': '/' + self.server.name,
                     'Config': {'Labels': {recovery.LABEL: 'a' * 32, 'autocare.recovery.role': 'source'}}}

    def cleanup(self):
        path = Path(self.temp.name).resolve()
        assert path.is_relative_to((recovery.ROOT / '.cache').resolve()) and path.name.startswith('test-recovery-')
        self.temp.cleanup()

    def response(self, info):
        return subprocess.CompletedProcess([], 0, json.dumps(info).encode(), b'')

    def test_guard_accepts_exact_new_container(self):
        with patch.object(self.server, 'docker', return_value=self.response(self.info)) as docker:
            self.server.guard()
            self.assertEqual(self.server.container_id, docker.call_args.args[0][-1])

    def test_guard_rejects_foreign_id_name_run_role_and_missing_labels(self):
        bad = []
        for field in ('Id', 'Name'):
            info = copy.deepcopy(self.info); info[field] = 'foreign'; bad.append(info)
        for field in (recovery.LABEL, 'autocare.recovery.role'):
            info = copy.deepcopy(self.info); info['Config']['Labels'][field] = 'foreign'; bad.append(info)
        bad.append({'Id': self.server.container_id, 'Name': '/' + self.server.name})
        for info in bad:
            with self.subTest(info=info), patch.object(self.server, 'docker', return_value=self.response(info)):
                with self.assertRaises(recovery.RecoveryError): self.server.guard()

    def test_cleanup_never_stops_on_mismatch(self):
        info = copy.deepcopy(self.info); info['Config']['Labels'][recovery.LABEL] = 'foreign'
        with patch.object(self.server, 'docker', return_value=self.response(info)) as docker:
            with self.assertRaises(recovery.RecoveryError): self.server.close()
            self.assertEqual(1, docker.call_count)
            self.assertEqual('inspect', docker.call_args.args[0][0])

    def test_cleanup_only_stops_verified_full_id_and_checks_removal(self):
        with patch.object(self.server, 'docker', side_effect=[self.response(self.info),
                subprocess.CompletedProcess([], 0, b'', b''), subprocess.CompletedProcess([], 1, b'', b'Error: No such object: ' + b'b' * 64)]) as docker:
            self.server.close()
            self.assertIsNone(self.server.container_id)
            self.assertEqual(['stop', '--time', '10', 'b' * 64], docker.call_args_list[1].args[0])

    def test_cleanup_waits_for_auto_removal_and_stops_only_once(self):
        gone = subprocess.CompletedProcess([], 1, b'', b'error: no such object: ' + b'b' * 64)
        with patch.object(self.server, 'docker', side_effect=[self.response(self.info),
                subprocess.CompletedProcess([], 0, b'', b''), self.response(self.info), self.response(self.info), gone]) as docker, \
                patch.object(recovery.time, 'sleep'):
            self.server.close()
        self.assertIsNone(self.server.container_id)
        self.assertEqual(1, sum(c.args[0][0] == 'stop' for c in docker.call_args_list))

    def test_cleanup_retains_id_on_timeout_changed_ownership_or_daemon_error(self):
        cases = [self.response(self.info), self.response({'Id': 'foreign'}),
                 subprocess.CompletedProcess([], 1, b'', b'Cannot connect to Docker daemon'),
                 subprocess.CompletedProcess([], 1, b'', b'error: no such object: ' + b'c' * 64),
                 subprocess.CompletedProcess([], 2, b'', b'error: no such object: ' + b'b' * 64)]
        for last in cases:
            with self.subTest(last=last), patch.object(self.server, 'docker', side_effect=[self.response(self.info),
                    subprocess.CompletedProcess([], 0, b'', b''), last]), \
                    patch.object(recovery.time, 'monotonic', side_effect=[0, 11]):
                with self.assertRaises(recovery.RecoveryError): self.server.close()
                self.assertEqual('b' * 64, self.server.container_id)

    def test_no_container_means_no_cleanup_command(self):
        self.server.container_id = None
        with patch.object(self.server, 'docker') as docker:
            self.server.close(); docker.assert_not_called()

    def test_cleanup_failure_does_not_replace_original_execution_failure(self):
        report = {}
        with patch.object(recovery.OwnedMySQL, 'start', side_effect=recovery.RecoveryError('synthetic original failure')), \
                patch.object(recovery.OwnedMySQL, 'close', side_effect=recovery.RecoveryError('synthetic cleanup failure')), \
                contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaisesRegex(recovery.RecoveryError, '^synthetic original failure$'):
                recovery.run_recovery(report, self.work)
        self.assertEqual('start_owned_source', report['active_step'])
        self.assertTrue(report['cleanup'])
        self.assertTrue(all(x['passed'] is False for x in report['cleanup']))

    def test_database_and_identifier_boundaries(self):
        with patch.object(self.server, 'guard'), patch.object(self.server, 'docker') as docker:
            for name in ('production', 'recovery_source; DROP DATABASE mysql', '../mysql'):
                with self.assertRaises(recovery.RecoveryError): self.server.sql('SELECT 1', name)
                with self.assertRaises(recovery.RecoveryError): self.server.create_database(name)
            docker.assert_not_called()
        for name in ('a`b', 'x.y', 'a;DROP', ''):
            with self.assertRaises(recovery.RecoveryError): recovery.identifier(name)
        self.assertEqual('`order`', recovery.identifier('order'))

    def test_dump_rejects_database_redirection_or_empty_backup(self):
        valid = b'CREATE TABLE x (id int);\n' + b'-- synthetic padding\n' * 60
        recovery.validate_dump(valid)
        for prefix in (b'USE production;\n', b'create database production;\n', b'/*!50003 USE production */;\n'):
            with self.assertRaises(recovery.RecoveryError): recovery.validate_dump(prefix + valid)
        with self.assertRaises(recovery.RecoveryError): recovery.validate_dump(b'')

    def test_tampered_backup_never_creates_or_restores_database(self):
        path = self.work / 'backup.sql'; path.write_bytes(b'tampered')
        with patch.object(self.server, 'create_database') as create, patch.object(self.server, 'sql') as sql:
            with self.assertRaises(recovery.RecoveryError): self.server.restore('recovery_restore', path, '0' * 64)
            create.assert_not_called(); sql.assert_not_called()

    def test_restore_checks_hash_and_database_redirection(self):
        raw = b'USE production;\nCREATE TABLE x (id int);\n' + b'-- padding\n' * 120
        path = self.work / 'backup.sql'; path.write_bytes(raw)
        with patch.object(self.server, 'create_database') as create, patch.object(self.server, 'sql') as sql:
            with self.assertRaises(recovery.RecoveryError): self.server.restore('recovery_restore', path, recovery.digest(raw))
            create.assert_not_called(); sql.assert_not_called()

    def test_migration_gaps_and_duplicates_fail_closed(self):
        for index in range(1, 65): (self.work / f'V{index:03}__fixture.sql').write_text('SELECT 1;', encoding='utf-8')
        with patch.object(recovery, 'MIGRATIONS', self.work):
            self.assertEqual(64, len(recovery.migration_files()))
            (self.work / 'V064__fixture.sql').unlink()
            with self.assertRaises(recovery.RecoveryError): recovery.migration_files()
            (self.work / 'V061__duplicate.sql').write_text('SELECT 1;', encoding='utf-8')
            with self.assertRaises(recovery.RecoveryError): recovery.migration_files()

    def test_row_and_schema_changes_cannot_pass_snapshot_comparison(self):
        original = {'tables': {'vehicle': {'rows': 1, 'data_sha256': 'a', 'schema_sha256': 'b'}}, 'objects': {}, 'sha256': 'x'}
        recovery.equal_snapshot(original, copy.deepcopy(original), 'restore')
        for field in ('rows', 'data_sha256', 'schema_sha256'):
            altered = copy.deepcopy(original); altered['tables']['vehicle'][field] = 'changed'
            with self.assertRaises(recovery.RecoveryError): recovery.equal_snapshot(original, altered, 'restore')
        altered = copy.deepcopy(original); altered['objects']['TRIGGER:guard'] = 'changed'
        with self.assertRaises(recovery.RecoveryError): recovery.equal_snapshot(original, altered, 'restore')

    def test_tool_error_never_leaks_command_password_or_sql(self):
        result = subprocess.CompletedProcess([], 1, b'', b'sensitive SQL or password')
        with patch.object(recovery.subprocess, 'run', return_value=result):
            with self.assertRaises(recovery.RecoveryError) as error:
                self.server.docker(['exec', 'ephemeral-password'])
            self.assertNotIn('ephemeral-password', str(error.exception))
            self.assertNotIn('sensitive', str(error.exception))

    def test_failed_execution_writes_a_new_failed_report(self):
        scripts = self.work / 'scripts'
        (scripts / 'fixtures').mkdir(parents=True)
        (scripts / 'run_database_recovery.py').write_text('# synthetic runner', encoding='utf-8')
        (scripts / 'fixtures/database_recovery.sql').write_text('-- synthetic fixture', encoding='utf-8')
        old = self.work / '.cache/database-recovery-old/report.json'
        old.parent.mkdir(parents=True)
        old.write_text('{"passed":true}', encoding='utf-8')
        with patch.object(recovery, 'ROOT', self.work), patch.object(recovery.subprocess, 'check_output', return_value='head\n'), \
                patch.object(recovery, 'run_recovery', side_effect=recovery.RecoveryError('synthetic failure')), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, recovery.main())
        reports = [p for p in (self.work / '.cache').glob('database-recovery-*/report.json') if p != old]
        self.assertEqual(1, len(reports))
        result = json.loads(reports[0].read_text(encoding='utf-8'))
        self.assertFalse(result['passed'])
        self.assertEqual('synthetic failure', result['error'])


if __name__ == '__main__':
    unittest.main()
