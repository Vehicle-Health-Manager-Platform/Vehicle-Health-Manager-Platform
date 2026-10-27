"""Exercise logical backup/restore using only two newly created, ownership-checked MySQLs."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import secrets
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / 'docs/sql/migrations'
DATABASES = {'recovery_source', 'recovery_restore', 'recovery_rollback', 'recovery_repaired'}
LABEL = 'autocare.recovery.run'
IMAGE = 'mysql:8.0'


class RecoveryError(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise RecoveryError(message)


def identifier(value):
    require(bool(re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*', value)), 'Unsafe SQL identifier')
    return '`' + value + '`'


def digest(value):
    return hashlib.sha256(value).hexdigest()


def migration_files():
    files = sorted(MIGRATIONS.glob('*.sql'))
    require([p.name[:4] for p in files] == [f'V{i:03}' for i in range(1, 66)],
            'Expected exactly ordered V001-V065; review the recovery upgrade boundary when adding migrations')
    return files


def manifest(files):
    return {p.name: digest(p.read_bytes()) for p in files}


def validate_dump(raw):
    require(len(raw) > 1024 and b'CREATE TABLE' in raw, 'Missing or incomplete backup')
    require(not re.search(rb'(?mi)^\s*(?:/\*!\d+\s+)?(?:USE\s|CREATE\s+DATABASE\s)', raw),
            'Backup must not redirect the restore to another database')


class OwnedMySQL:
    def __init__(self, run, role):
        self.run, self.role = run, role
        self.password = secrets.token_hex(24)
        self.container_id = None
        self.name = f'autocare-recovery-{run[:12]}-{role}'

    @staticmethod
    def docker(args, data=None, timeout=180, allow_failure=False):
        try:
            result = subprocess.run(['docker', *args], input=data, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=timeout, shell=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            # Never include a command (it may contain an ephemeral password) or SQL/row values.
            raise RecoveryError('Docker tool unavailable or timed out') from exc
        if not allow_failure and result.returncode:
            detail = re.search(rb'ERROR ([0-9]+) \(([0-9A-Z]+)\)(?: at line ([0-9]+))?', result.stderr)
            safe_detail = '' if detail is None else ' (MySQL ' + detail.group(1).decode() + ', SQLSTATE ' + detail.group(2).decode() + ')'
            raise RecoveryError(f'Docker operation {args[0]} failed with exit {result.returncode}' + safe_detail)
        return result

    def start(self):
        raw = self.docker(['run', '--detach', '--rm', '--name', self.name,
                           '--label', f'{LABEL}={self.run}', '--label', f'autocare.recovery.role={self.role}',
                           '--env', f'MYSQL_ROOT_PASSWORD={self.password}', IMAGE,
                           '--log-bin-trust-function-creators=1', '--event-scheduler=OFF']).stdout.decode().strip()
        require(bool(re.fullmatch('[0-9a-f]{64}', raw)), 'Docker did not return a full owned container ID')
        self.container_id = raw
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            result = self.sql('SELECT 1;', allow_failure=True)
            if result.returncode == 0:
                return
            time.sleep(1)
        raise RecoveryError('New isolated MySQL did not become ready')

    def guard(self):
        require(bool(self.container_id and re.fullmatch('[0-9a-f]{64}', self.container_id)), 'No owned container')
        result = self.docker(['inspect', '--format', '{{json .}}', self.container_id])
        info = json.loads(result.stdout)
        labels = info.get('Config', {}).get('Labels') or {}
        require(info.get('Id') == self.container_id and labels.get(LABEL) == self.run
                and labels.get('autocare.recovery.role') == self.role
                and info.get('Name') == '/' + self.name,
                'Container ownership mismatch; refusing database/cleanup operation')

    def sql(self, query, database=None, allow_failure=False):
        self.guard()
        require(database is None or database in DATABASES, 'Unsupported recovery database')
        args = ['exec', '--interactive', '--env', f'MYSQL_PWD={self.password}', self.container_id,
                'mysql', '--default-character-set=utf8mb4', '--batch', '--skip-column-names',
                '--protocol=tcp', '--host=127.0.0.1',
                "--init-command=SET time_zone='+00:00'", '--user=root']
        if database is not None:
            args.append(database)
        return self.docker(args, query if isinstance(query, bytes) else query.encode(), allow_failure=allow_failure)

    def text(self, query, database=None):
        return self.sql(query, database).stdout.decode('utf-8').strip()

    def create_database(self, name):
        require(name in DATABASES, 'Unsupported recovery database')
        self.sql('CREATE DATABASE ' + identifier(name) + ' CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;')

    def backup(self, database, target):
        self.guard()
        require(database in DATABASES, 'Unsupported backup database')
        raw = self.docker(['exec', '--env', f'MYSQL_PWD={self.password}', self.container_id,
                           'mysqldump', '--user=root', '--single-transaction', '--routines', '--triggers', '--events',
                           '--hex-blob', '--set-gtid-purged=OFF', '--no-tablespaces', '--skip-comments',
                           '--default-character-set=utf8mb4', database]).stdout
        validate_dump(raw)
        target.write_bytes(raw)
        return {'file': target.name, 'bytes': len(raw), 'sha256': digest(raw)}

    def restore(self, database, source, expected_hash):
        raw = source.read_bytes()
        require(digest(raw) == expected_hash, 'Backup checksum mismatch; restore refused')
        validate_dump(raw)
        self.create_database(database)
        self.sql(raw, database)

    def close(self):
        if self.container_id is not None:
            self.guard()
            self.docker(['stop', '--time', '10', self.container_id], timeout=45)
            # Docker stop may return before asynchronous --rm finishes. Observe the
            # same full ID, checking ownership on each surviving snapshot.
            deadline = time.monotonic() + 10
            while True:
                result = self.docker(['inspect', '--format', '{{json .}}', self.container_id], allow_failure=True)
                if result.returncode:
                    require(result.returncode == 1 and
                            b'no such object: ' + self.container_id.encode() in result.stderr.lower(),
                            'Cannot verify owned container removal')
                    self.container_id = None
                    return
                info = json.loads(result.stdout)
                labels = info.get('Config', {}).get('Labels') or {}
                require(info.get('Id') == self.container_id and info.get('Name') == '/' + self.name
                        and labels.get(LABEL) == self.run and labels.get('autocare.recovery.role') == self.role,
                        'Container ownership changed during cleanup')
                require(time.monotonic() < deadline, 'Owned container removal deadline exceeded')
                time.sleep(0.2)


def normalize_schema(value):
    for database in sorted(DATABASES):
        value = value.replace(identifier(database) + '.', '`RECOVERY_DATABASE`.')
    return value


def snapshot(server, database):
    require(server.text("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type<>'BASE TABLE';", database) == '0',
            'Unexpected view: extend fingerprint support before accepting this schema')
    require(server.text("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND engine<>'InnoDB';", database) == '0',
            'Consistent logical snapshot requires all InnoDB tables')
    columns = {}
    for line in server.text('SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() ORDER BY table_name,ordinal_position;', database).splitlines():
        table, column = line.split('\t')
        identifier(table); identifier(column)
        columns.setdefault(table, []).append(column)
    require(bool(columns), 'Empty recovery schema')
    ddl_rows = server.text('\n'.join('SHOW CREATE TABLE ' + identifier(t) + ';' for t in columns), database).splitlines()
    require(len(ddl_rows) == len(columns), 'Missing table definition')
    ddl = {line.split('\t', 1)[0]: normalize_schema(line.split('\t', 1)[1]) for line in ddl_rows}
    row_queries = []
    for table, names in columns.items():
        cells = ','.join('IF(' + identifier(c) + ' IS NULL,NULL,HEX(' + identifier(c) + '))' for c in names)
        row_queries.append("SELECT JSON_ARRAY('" + table + "',JSON_ARRAY(" + cells + ')) FROM ' + identifier(table))
    data = {table: [] for table in columns}
    # Hex for every non-null column handles bytes, JSON, Unicode, multiline text and NULL distinctly.
    for line in server.text(' UNION ALL '.join(row_queries) + ';', database).splitlines():
        table, values = json.loads(line)
        data[table].append(json.dumps(values, separators=(',', ':')))
    objects = {}
    for kind, query in {
        'TRIGGER': 'SELECT trigger_name FROM information_schema.triggers WHERE trigger_schema=DATABASE() ORDER BY trigger_name;',
        'PROCEDURE': "SELECT routine_name FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_type='PROCEDURE' ORDER BY routine_name;",
        'FUNCTION': "SELECT routine_name FROM information_schema.routines WHERE routine_schema=DATABASE() AND routine_type='FUNCTION' ORDER BY routine_name;",
        'EVENT': 'SELECT event_name FROM information_schema.events WHERE event_schema=DATABASE() ORDER BY event_name;'
    }.items():
        for name in server.text(query, database).splitlines():
            row = server.text('SHOW CREATE ' + kind + ' ' + identifier(name) + ';', database)
            if kind == 'TRIGGER':
                row = '\t'.join(row.split('\t')[:-1])  # Created timestamp is restore-time metadata.
            objects[kind + ':' + name] = normalize_schema(row)
    tables = {t: {'rows': len(data[t]), 'schema_sha256': digest(ddl[t].encode()),
                  'data_sha256': digest('\n'.join(sorted(data[t])).encode())} for t in columns}
    return {'table_count': len(tables), 'row_count': sum(v['rows'] for v in tables.values()),
            'tables': tables, 'objects': {k: digest(v.encode()) for k, v in objects.items()},
            'sha256': digest(json.dumps({'tables': tables, 'objects': objects}, sort_keys=True).encode())}


def equal_snapshot(expected, actual, stage):
    if expected != actual:
        changed = sorted(t for t in expected['tables'].keys() | actual['tables'].keys()
                         if expected['tables'].get(t) != actual['tables'].get(t))
        raise RecoveryError(stage + ' mismatch; changed tables: ' + ','.join(changed)
                            + '; object definitions equal=' + str(expected['objects'] == actual['objects']))


def apply(server, database, files):
    # One guarded connection preserves migration order and fails on the first SQL error.
    server.sql(b'\n'.join(p.read_bytes() for p in files), database)


def expect_error(server, database, sql, number):
    result = server.sql(sql, database, allow_failure=True)
    require(result.returncode != 0 and f'ERROR {number} '.encode() in result.stderr,
            f'Restored constraint/trigger did not return expected MySQL error {number}')


def run_recovery(report, work):
    run = uuid.uuid4().hex
    source, restored = OwnedMySQL(run, 'source'), OwnedMySQL(run, 'restore')
    servers = [source, restored]
    files = migration_files()
    report['migrations'] = manifest(files)
    report['checks'] = []

    def step(name, action):
        print('Recovery: ' + name, flush=True)
        report['active_step'] = name
        start = time.monotonic()
        value = action()
        report['checks'].append({'name': name, 'passed': True, 'duration_seconds': round(time.monotonic()-start, 3)})
        return value

    try:
        step('start_owned_source', source.start)
        report['mysql_version'] = source.text('SELECT VERSION();')
        report['source_server_uuid'] = source.text('SELECT @@server_uuid;')
        source.create_database('recovery_source')
        step('migrate_V001_V064', lambda: apply(source, 'recovery_source', files[:-1]))
        source.sql((ROOT / 'scripts/fixtures/database_recovery.sql').read_bytes(), 'recovery_source')
        before = step('snapshot_before_upgrade', lambda: snapshot(source, 'recovery_source'))
        require(before['table_count'] == 132, 'Unexpected pre-V065 table count')
        report['pre_upgrade'] = before
        old_file = work / 'pre-upgrade.sql'
        report['pre_upgrade_backup'] = step('backup_before_upgrade', lambda: source.backup('recovery_source', old_file))
        expect_error(source, 'recovery_source', files[-1].read_text(encoding='utf-8') + "\nSIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic post-DDL failure';", 1644)
        require(source.text("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='knowledge_embedding_chunk';", 'recovery_source') == '1',
                'Expected committed DDL after injected failure')
        report['checks'].append({'name': 'injected_failure_leaves_committed_DDL', 'passed': True})
        step('restore_pre_upgrade_to_new_database', lambda: source.restore('recovery_rollback', old_file, report['pre_upgrade_backup']['sha256']))
        rollback = step('verify_backup_based_rollback', lambda: snapshot(source, 'recovery_rollback'))
        equal_snapshot(before, rollback, 'backup-based rollback')
        step('retry_V065', lambda: apply(source, 'recovery_source', files[-1:]))
        source.sql((ROOT / 'scripts/fixtures/knowledge_embedding_recovery.sql').read_bytes(), 'recovery_source')
        source.sql("INSERT INTO input_material(id,owner_id,vehicle_id,purpose,object_key,content_type,size_bytes,duration_ms,pages,scan_status) VALUES(1,1,1,'AI','synthetic-recovery/material','application/pdf',100,0,1,'CLEAN');", 'recovery_source')
        source.sql("INSERT INTO input_material(id,owner_id,vehicle_id,purpose,object_key,content_type,size_bytes,duration_ms,pages,scan_status) VALUES(2,1,1,'AI','synthetic-recovery/audio','audio/mpeg',6048,1008,0,'CLEAN'); "
                   "INSERT INTO recognition_job(id,owner_id,vehicle_id,material_id,purpose,origin_session_id,origin_expires_at,source_hash,state,error_code,expires_at) "
                   "VALUES(1,1,1,2,'AI','synthetic-recovery-session',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),SHA2('synthetic-recovery-source',256),'CANCELLED','USER_CANCELLED',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE));", 'recovery_source')
        current = step('snapshot_current', lambda: snapshot(source, 'recovery_source'))
        require(current['table_count'] == 134, 'Unexpected current table count')
        report['current'] = current
        full_file = work / 'current.sql'
        report['current_backup'] = step('backup_current', lambda: source.backup('recovery_source', full_file))
        step('start_independent_restore_server', restored.start)
        report['restore_server_uuid'] = restored.text('SELECT @@server_uuid;')
        require(report['restore_server_uuid'] != report['source_server_uuid'], 'Restore must use an independent server')
        step('restore_current_on_independent_server', lambda: restored.restore('recovery_restore', full_file, report['current_backup']['sha256']))
        actual = step('snapshot_independent_restore', lambda: snapshot(restored, 'recovery_restore'))
        equal_snapshot(current, actual, 'cross-instance restore')
        source.sql('UPDATE vehicle SET current_mileage=99999 WHERE id=1; DELETE FROM vehicle_archive WHERE id=1;', 'recovery_source')
        damaged = step('detect_synthetic_damage', lambda: snapshot(source, 'recovery_source'))
        require(damaged != current, 'Synthetic damage was not detected')
        report['damaged_tables'] = [t for t in current['tables'] if current['tables'][t] != damaged['tables'][t]]
        step('restore_damaged_data_to_new_database', lambda: source.restore('recovery_repaired', full_file, report['current_backup']['sha256']))
        repaired = step('verify_repair', lambda: snapshot(source, 'recovery_repaired'))
        equal_snapshot(current, repaired, 'damage recovery')
        step('reapply_all_migrations', lambda: apply(source, 'recovery_repaired', files))
        repeated = step('verify_migration_replay', lambda: snapshot(source, 'recovery_repaired'))
        equal_snapshot(current, repeated, 'migration replay')
        expect_error(restored, 'recovery_restore', "INSERT INTO user(id,openid) VALUES(1,'synthetic-duplicate');", 1062)
        expect_error(restored, 'recovery_restore', "INSERT INTO input_material(id,owner_id,vehicle_id,purpose,object_key,content_type,size_bytes,pages,scan_status) VALUES(999,1,1,'INVALID','synthetic-invalid','application/pdf',100,1,'CLEAN');", 3819)
        expect_error(restored, 'recovery_restore', 'UPDATE vehicle SET current_mileage=1 WHERE id=1;', 1644)
        expect_error(restored, 'recovery_restore', "INSERT INTO knowledge_embedding_chunk(job_id,ordinal,start_offset,end_offset,chunk_hash,content_text,dimension,vector_json) VALUES(99999999,0,0,9,SHA2('synthetic',256),'synthetic',3,JSON_ARRAY(1,0,0));", 1452)
        require(restored.text('CALL recovery_vehicle_count();', 'recovery_restore') == '1', 'Restored procedure unavailable')
        require(restored.text("SELECT status FROM information_schema.events WHERE event_schema=DATABASE() AND event_name='recovery_disabled_event';", 'recovery_restore') == 'DISABLED', 'Restored event must remain disabled')
        report['checks'].append({'name': 'restored_unique_check_trigger_procedure_event', 'passed': True})
        require(manifest(files) == report['migrations'], 'Migration files changed during execution')
        report['migration_sources_unchanged'] = True
    finally:
        failed_before_cleanup = sys.exc_info()[0] is not None
        report['cleanup'] = []
        for server in reversed(servers):
            try:
                server.close()
                report['cleanup'].append({'role': server.role, 'passed': True})
            except Exception:
                report['cleanup'].append({'role': server.role, 'passed': False})
        # Preserve the original migration/restore failure if cleanup also fails.
        if not failed_before_cleanup:
            require(all(x['passed'] for x in report['cleanup']), 'Owned container cleanup failed; inspect this run before retrying')


def main():
    work = ROOT / '.cache' / ('database-recovery-' + uuid.uuid4().hex[:12])
    work.mkdir(parents=True)
    report = {'version': 1, 'passed': False, 'image': IMAGE, 'checks': [],
              'started_at': datetime.now(timezone.utc).isoformat(),
              'boundaries': ['synthetic MySQL data only; LOCAL_TEST payment fixture',
                             'logical backup at a fixed point, no concurrent writes/DDL',
                             'rollback by backup into a new database, not transactional DDL/down migration',
                             'no zero-RPO/PITR, production failover, object-store backup or performance acceptance']}
    started = time.monotonic()
    inputs = [ROOT / 'scripts/run_database_recovery.py', ROOT / 'scripts/fixtures/database_recovery.sql', ROOT / 'scripts/fixtures/knowledge_embedding_recovery.sql']
    try:
        report['implementation_inputs_sha256'] = {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in inputs}
        report['started_from_commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        run_recovery(report, work)
        report['implementation_sources_unchanged'] = report['implementation_inputs_sha256'] == {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in inputs}
        require(report['implementation_sources_unchanged'], 'Recovery implementation changed during execution')
        report['passed'] = True
        report['active_step'] = None
    except Exception as exc:
        report['error'] = str(exc) if isinstance(exc, RecoveryError) else type(exc).__name__
    report['finished_at'] = datetime.now(timezone.utc).isoformat()
    report['duration_seconds'] = round(time.monotonic()-started, 3)
    output = work / 'report.json'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('Recovery: ' + ('PASS' if report['passed'] else 'FAIL') + '; report: ' + str(output), flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
