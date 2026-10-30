"""Measure core read/write latency at 500 concurrency on a disposable, owned stack.

Every run builds a new stack: an owned MySQL container with the repository migrations,
a fixed synthetic dataset, and the current backend jar served by a second owned container.
The load client runs in this process, separate from the server, so client and server do not
share one JVM and the reported latency is not distorted by that.
"""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import hmac
import http.client
import json
import math
import multiprocessing
import os
import platform
import queue
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / 'docs/sql/migrations'
JAR = ROOT / 'backend/target/platform-backend-0.1.0-SNAPSHOT.jar'
LABEL = 'autocare.performance.run'
MYSQL_IMAGE = 'mysql:8.0'
RUNTIME_IMAGE = 'maven:3.9-eclipse-temurin-17'
LOAD_IMAGE = 'python:3.11-slim-bookworm@sha256:f06d01d4ba3f8ad5af45a3ec87df19c1d317d8e8961edc0fabda61811af6f7cf'
MAVEN_CACHE = 'autocare-maven-cache'
DATABASE = 'perf_baseline'
ISSUER = 'vehicle-health-manager'
TABLES = 134

CONCURRENCY = 500
READ_ROUNDS = 3
WRITE_ROUNDS = 2
WARMUP_ROUNDS = 1
POOL_SIZE = 40
CLIENT_PROCESSES = 10
ERROR_RATE = 0.001

HOME_OWNERS = 50
HOME_VEHICLES = 2
ARCHIVES_PER_VEHICLE = 40
WRITE_OWNERS = 1000
MERCHANTS = 300
PREFLIGHT_REQUESTS = 20
# Measured writes use slots 1..1500; the last block belongs to the sequential preflight.
SLOTS = MERCHANTS * 5 + PREFLIGHT_REQUESTS

THRESHOLDS = {
    'owner_home': {'p95_ms': 300.0, 'p99_ms': 800.0},
    'merchant_list': {'p95_ms': 300.0, 'p99_ms': 800.0},
    'order_create': {'p95_ms': 500.0, 'p99_ms': None},
}
TITLES = {
    'owner_home': '车主首页（车辆列表 + 最新档案）',
    'merchant_list': '商家列表（项目报价分页）',
    'order_create': '创建预约订单',
}
SCOPES = {'owner_home': '读', 'merchant_list': '读', 'order_create': '写'}
MODES = {'owner_home': 'home', 'merchant_list': 'merchant', 'order_create': 'order'}


class PerformanceError(ValueError):
    """Invalid input or unusable evidence; ValueError keeps validators easy to assert."""


def require(condition, message):
    if not condition:
        raise PerformanceError(message)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def percentile(ordered, percent):
    """Nearest-rank percentile; `ordered` must already be sorted."""
    rank = max(1, math.ceil(percent / 100.0 * len(ordered)))
    return round(ordered[rank - 1], 3)


def summarize(values):
    ordered = sorted(values)
    return {
        'samples': len(ordered),
        'min_ms': round(ordered[0], 3),
        'mean_ms': round(sum(ordered) / len(ordered), 3),
        'p50_ms': percentile(ordered, 50),
        'p90_ms': percentile(ordered, 90),
        'p95_ms': percentile(ordered, 95),
        'p99_ms': percentile(ordered, 99),
        'max_ms': round(ordered[-1], 3),
    }


def free_port():
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def migration_files():
    files = sorted(MIGRATIONS.glob('*.sql'))
    require(len(files) == 65, 'Expected 65 ordered migrations for the performance baseline')
    return files


def token(secret, owner, session, seconds=7200):
    def encode(raw):
        return base64.urlsafe_b64encode(raw).rstrip(b'=').decode()
    now = int(time.time())
    header = encode(b'{"alg":"HS256","typ":"JWT"}')
    claims = json.dumps({'iss': ISSUER, 'sub': str(owner), 'iat': now, 'exp': now + seconds,
                         'jti': session, 'subject_type': 'user', 'role': 'OWNER'},
                        separators=(',', ':'))
    payload = encode(claims.encode())
    signature = encode(hmac.new(secret.encode(), (header + '.' + payload).encode(), hashlib.sha256).digest())
    return header + '.' + payload + '.' + signature


def memory_mb():
    try:
        if os.name == 'nt':
            import ctypes

            class Status(ctypes.Structure):
                _fields_ = [('dwLength', ctypes.c_ulong), ('dwMemoryLoad', ctypes.c_ulong),
                            ('ullTotalPhys', ctypes.c_ulonglong), ('ullAvailPhys', ctypes.c_ulonglong),
                            ('ullTotalPageFile', ctypes.c_ulonglong), ('ullAvailPageFile', ctypes.c_ulonglong),
                            ('ullTotalVirtual', ctypes.c_ulonglong), ('ullAvailVirtual', ctypes.c_ulonglong),
                            ('ullAvailExtendedVirtual', ctypes.c_ulonglong)]
            status = Status()
            status.dwLength = ctypes.sizeof(Status)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status))
            return int(status.ullTotalPhys // (1024 * 1024))
        return int(os.sysconf('SC_PAGE_SIZE') * os.sysconf('SC_PHYS_PAGES') // (1024 * 1024))
    except Exception:
        return None


def planned_slot_base(options):
    # Keep sequential preflight slots outside all warmup/measured rounds and align
    # their shop mapping with perform(). Supports explicit longer JVM warmup.
    timed=(options.warmup_rounds+options.write_rounds)*options.concurrency
    return math.ceil(timed/MERCHANTS)*MERCHANTS


def build_dataset(sessions, refresh_hash, slot_count=SLOTS):
    """Deterministic synthetic dataset: fixed ids keep worker-to-resource mapping reproducible."""
    chunks, users, vehicles, archives, files, links, slots = [], [], [], [], [], [], []
    for owner in range(1, HOME_OWNERS + 1):
        users.append(f"({owner},'perf-owner-{owner}',1)")
        for offset in range(HOME_VEHICLES):
            vehicles.append(f"({(owner - 1) * HOME_VEHICLES + offset + 1},{owner},1,"
                            f"'粤A{owner:05d}{offset}','LPERF{owner:06d}{offset}',12000)")
    archive_id = 0
    for vehicle in range(1, HOME_OWNERS * HOME_VEHICLES + 1):
        for index in range(ARCHIVES_PER_VEHICLE):
            archive_id += 1
            content = json.dumps({'title': f'合成保养记录{archive_id}', 'notes': '性能基线夹具',
                                  'mileage': 12000 + index}, ensure_ascii=False).replace("'", "''")
            archives.append(f"({archive_id},{vehicle},1,'{content}',3,"
                            f"DATE_SUB(UTC_TIMESTAMP(),INTERVAL {index} DAY))")
            files.append(f"({archive_id},'vehicle',{vehicle},'perf/archive/{archive_id}','image/png',1024,'CLEAN')")
            links.append(f"({archive_id},{archive_id},0)")
    for offset in range(WRITE_OWNERS):
        owner, vehicle = HOME_OWNERS + offset + 1, HOME_OWNERS * HOME_VEHICLES + offset + 1
        users.append(f"({owner},'perf-owner-{owner}',1)")
        vehicles.append(f"({vehicle},{owner},1,'粤B{offset:05d}','LPERF{owner:06d}',30000)")
    for owner in range(1, HOME_OWNERS + WRITE_OWNERS + 1):
        chunks.append('INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,refresh_hash,expires_at) '
                      f"VALUES('{sessions[owner]}','user',{owner},'OWNER','perf-baseline',"
                      f"'{refresh_hash(owner)}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 HOUR));")
    for index in range(1, MERCHANTS + 1):
        chunks.append('INSERT INTO merchant(id,merchant_type,name,address,region_code,status) '
                      f"VALUES({index},2,'合成性能门店{index}','合成性能地址{index}号','440106',1);")
        chunks.append('INSERT INTO merchant_project(id,merchant_id,project_id,price,on_shelf) '
                      f'VALUES({index},{index},1,{100 + index}.00,1);')
        chunks.append('INSERT INTO merchant_project_version(id,merchant_project_id,version,price,on_shelf) '
                      f'VALUES({index},{index},1,{100 + index}.00,1);')
    for index in range(1, slot_count + 1):
        slots.append(f"({index},{(index - 1) % MERCHANTS + 1},1,"
                     f"DATE_ADD(UTC_TIMESTAMP(),INTERVAL {2880 + index * 7} MINUTE),"
                     f"DATE_ADD(UTC_TIMESTAMP(),INTERVAL {2940 + index * 7} MINUTE),1,0)")
    chunks.append("INSERT INTO brand(id,name,status) VALUES(1,'合成性能品牌',1);")
    chunks.append("INSERT INTO series(id,brand_id,name,status) VALUES(1,1,'合成性能车系',1);")
    chunks.append("INSERT INTO model(id,series_id,year,status) VALUES(1,1,'2026',1);")
    chunks.append('INSERT INTO standard_project(id,project_name,category,service_content,base_price_low,base_price_high) '
                  "VALUES(1,'合成性能保养',1,'检查清洁',1.00,900.00);")
    statements = {
        'user': ("INSERT INTO `user`(id,openid,status) VALUES ", users, 400),
        'vehicle': ("INSERT INTO vehicle(id,user_id,model_id,plate_no,vin,current_mileage) VALUES ", vehicles, 400),
        'vehicle_archive': ("INSERT INTO vehicle_archive(id,vehicle_id,archive_type,content,input_type,recorded_at) VALUES ",
                            archives, 200),
        'file_object': ("INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) "
                        "VALUES ", files, 400),
        'vehicle_archive_file': ("INSERT INTO vehicle_archive_file(archive_id,file_id,position) VALUES ", links, 400),
        'appointment_slot': ("INSERT INTO appointment_slot(id,merchant_id,project_id,starts_at,ends_at,capacity,"
                             "reserved_count) VALUES ", slots, 300),
    }
    for prefix, values, size in statements.values():
        for start in range(0, len(values), size):
            chunks.append(prefix + ','.join(values[start:start + size]) + ';')
    counts = {'owners': len(users), 'vehicles': len(vehicles), 'archives': len(archives),
              'archive_files': len(links), 'merchants': MERCHANTS, 'quotes': MERCHANTS,
              'slots': len(slots), 'auth_sessions': len(sessions)}
    counts['rows_total'] = sum(counts.values())
    return '\n'.join(chunks) + '\n', counts


class Docker:
    @staticmethod
    def run(args, data=None, timeout=900, allow_failure=False):
        try:
            result = subprocess.run(['docker', *args], input=data, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, timeout=timeout, shell=False)
        except (OSError, subprocess.TimeoutExpired) as exc:
            # Never echo a command: it can carry ephemeral credentials.
            raise PerformanceError('Docker tool unavailable or timed out') from exc
        if not allow_failure and result.returncode:
            raise PerformanceError(f'Docker operation {args[0]} failed with exit {result.returncode}')
        return result


class Owned:
    """Re-checks this run's label, full id and name before any use or cleanup."""

    def __init__(self, run, role, name, labels, kind='container'):
        self.run, self.role, self.name, self.labels, self.kind = run, role, name, labels, kind
        self.id = None

    def guard(self):
        require(bool(self.id and re.fullmatch('[0-9a-f]{64}', self.id)), 'No owned object')
        if self.kind == 'network':
            info = json.loads(Docker.run(['network', 'inspect', self.id]).stdout)[0]
            labels = info.get('Labels') or {}
            require(info.get('Id') == self.id and info.get('Name') == self.name
                    and all(labels.get(k) == v for k, v in self.labels.items()),
                    'Network ownership mismatch; refusing operation')
            return
        info = json.loads(Docker.run(['inspect', '--format', '{{json .}}', self.id]).stdout)
        self.check_container(info)

    def check_container(self, info):
        labels = info.get('Config', {}).get('Labels') or {}
        require(info.get('Id') == self.id and info.get('Name') == '/' + self.name
                and all(labels.get(k) == v for k, v in self.labels.items()),
                'Container ownership mismatch; refusing operation')

    def close(self, timeout=45):
        if self.id is None:
            return
        self.guard()
        if self.kind == 'network':
            Docker.run(['network', 'rm', self.id], timeout=timeout)
            gone = Docker.run(['network', 'inspect', self.id], allow_failure=True)
            require(gone.returncode != 0, 'Owned network still exists after cleanup')
        else:
            Docker.run(['stop', '--time', '10', self.id], timeout=timeout)
            # --rm is asynchronous: stop can return before the removal finishes.
            deadline = time.monotonic() + 10
            while True:
                gone = Docker.run(['inspect', '--format', '{{json .}}', self.id], allow_failure=True)
                if gone.returncode != 0: break
                # Validate the same snapshot: removal between two inspect calls is harmless.
                self.check_container(json.loads(gone.stdout))
                require(time.monotonic() < deadline, 'Owned container still exists after cleanup deadline')
                time.sleep(0.1)
        self.id = None


class MySQL(Owned):
    def __init__(self, run, network):
        super().__init__(run, 'mysql', f'autocare-perf-{run[:12]}-mysql',
                         {LABEL: run, 'autocare.performance.role': 'mysql'})
        self.network, self.password = network, secrets.token_hex(24)
        self.app_password = secrets.token_hex(24)

    def start(self):
        raw = Docker.run(['run', '--detach', '--rm', '--name', self.name, '--network', self.network.name,
                          '--network-alias', 'perf-mysql', '--label', f'{LABEL}={self.run}',
                          '--label', 'autocare.performance.role=mysql',
                          '--env', f'MYSQL_ROOT_PASSWORD={self.password}',
                          '--env', f'MYSQL_DATABASE={DATABASE}', '--env', 'MYSQL_USER=perf',
                          '--env', f'MYSQL_PASSWORD={self.app_password}',
                          MYSQL_IMAGE, '--log-bin-trust-function-creators=1',
                          '--max-connections=400']).stdout.decode().strip()
        require(bool(re.fullmatch('[0-9a-f]{64}', raw)), 'Docker did not return a full owned container ID')
        self.id = raw
        self.guard()
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if self.sql(b'SELECT 1;', allow_failure=True).returncode == 0:
                return
            time.sleep(1)
        raise PerformanceError('New isolated MySQL did not become ready')

    def sql(self, query, allow_failure=False):
        self.guard()
        args = ['exec', '--interactive', '--env', f'MYSQL_PWD={self.password}', self.id,
                'mysql', '--default-character-set=utf8mb4', '--batch', '--skip-column-names',
                '--protocol=tcp', '--host=127.0.0.1', "--init-command=SET time_zone='+00:00'",
                '--user=root', DATABASE]
        return Docker.run(args, query if isinstance(query, bytes) else query.encode(), allow_failure=allow_failure)

    def text(self, query):
        return self.sql(query).stdout.decode('utf-8').strip()

    def migrate(self, files):
        self.sql(b'\n'.join(p.read_bytes() for p in files))

    def seed(self, sql):
        self.sql(sql.encode('utf-8'))


class Backend(Owned):
    def __init__(self, run, network, port, secret, pool_size):
        super().__init__(run, 'backend', f'autocare-perf-{run[:12]}-backend',
                         {LABEL: run, 'autocare.performance.role': 'backend'})
        self.network, self.port, self.secret, self.pool_size = network, port, secret, pool_size

    def start(self, mysql, extra_env=()):
        require(JAR.is_file(), 'Backend jar missing; build it before starting the container')
        raw = Docker.run(['run', '--detach', '--rm', '--name', self.name, '--network', self.network.name,
                          '--label', f'{LABEL}={self.run}', '--label', 'autocare.performance.role=backend',
                          '--publish', f'127.0.0.1:{self.port}:8080',
                          '--mount', f'type=bind,source={ROOT},target=/workspace,readonly',
                          '--env', f'JWT_SECRET={self.secret}', '--env', 'MYSQL_HOST=perf-mysql',
                          '--env', 'MYSQL_PORT=3306', '--env', f'MYSQL_DATABASE={DATABASE}',
                          '--env', 'MYSQL_USER=perf', '--env', f'MYSQL_PASSWORD={mysql.app_password}',
                          '--env', f'MYSQL_POOL_SIZE={self.pool_size}',
                          '--env', 'SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true',
                          '--env', 'JAVA_TOOL_OPTIONS=-Xms512m -Xmx512m',
                          *[arg for key, value in extra_env for arg in ('--env', key+'='+value)],
                          RUNTIME_IMAGE, 'java', '-jar',
                          '/workspace/backend/target/' + JAR.name]).stdout.decode().strip()
        require(bool(re.fullmatch('[0-9a-f]{64}', raw)), 'Docker did not return a full owned backend ID')
        self.id = raw
        self.guard()

    def wait_healthy(self, deadline_seconds=240):
        deadline = time.monotonic() + deadline_seconds
        while time.monotonic() < deadline:
            try:
                connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=3)
                connection.request('GET', '/actuator/health')
                response = connection.getresponse()
                body = response.read().decode('utf-8', 'replace')
                connection.close()
                if response.status == 200 and '"UP"' in body:
                    return
            except OSError:
                pass
            time.sleep(2)
        logs = Docker.run(['logs', '--tail', '20', self.id], allow_failure=True).stdout.decode('utf-8', 'replace')
        raise PerformanceError('Backend did not become healthy; last log lines: '
                               + ' '.join(line[:120] for line in logs.splitlines()[-5:]))


def build_jar(native=False, repository=None):
    if native:
        maven=shutil.which('mvn.cmd' if os.name=='nt' else 'mvn')
        require(maven is not None,'Native performance build requires Maven on PATH')
        command=[maven,'-B','clean','package','-DskipTests']
        if repository is not None:command.append('-Dmaven.repo.local='+str(repository.resolve()))
        log=ROOT/'.cache/performance-native-build.private.log';log.parent.mkdir(exist_ok=True)
        with log.open('wb') as stream:
            result=subprocess.run(command,cwd=ROOT/'backend',stdout=stream,stderr=subprocess.STDOUT,timeout=1200)
        require(result.returncode==0 and JAR.is_file(),'Native backend build failed; see private build log')
        return
    Docker.run(['run', '--rm', '--mount', f'type=bind,source={ROOT},target=/workspace',
                '--mount', f'type=volume,source={MAVEN_CACHE},target=/root/.m2',
                '-e', 'JAVA_TOOL_OPTIONS=-Dapi.version=1.44', '-w', '/workspace/backend',
                RUNTIME_IMAGE, 'mvn', '-B', '-q', '-DskipTests', 'package'], timeout=1200)
    require(JAR.is_file(), 'Backend jar was not produced')


def code_of(problems):
    """Compact per-sample code: 0 = ok, HTTP status for HTTP failures, 1000+kind otherwise."""
    if not problems:
        return 0
    head = problems[0]
    if head.startswith('http_'):
        return int(head.split('_')[1])
    return {'transport': 1001, 'unexpected_payload': 1002, 'business_error': 1003,
            'invalid_json': 1004}.get(head, 1001)


def code_name(code):
    if code < 1000:
        return 'http_' + str(code)
    return {1001: 'transport', 1002: 'unexpected_payload', 1003: 'business_error',
            1004: 'invalid_json'}.get(code, 'unknown')


_DIAGNOSTICS = []
_HTTP_DETAILS = []


def perform(connection, plan, index, round_index):
    mode = plan['mode']
    if mode == 'home':
        owner = index % HOME_OWNERS + 1
        vehicle = (owner - 1) * HOME_VEHICLES + 1 + (index // HOME_OWNERS) % HOME_VEHICLES
        token_value = plan['tokens'][owner]
        problems, data = call(connection, 'GET', '/api/vehicle/list', token_value)
        if problems:
            return problems
        if not isinstance(data, dict) or not isinstance(data.get('list'), list) or data['list'] == []:
            return ['unexpected_payload']
        problems, data = call(connection, 'GET', f'/api/archive/list?vehicle_id={vehicle}&page=1&page_size=20',
                              token_value)
        if problems:
            return problems
        if not isinstance(data, dict) or data.get('total') != ARCHIVES_PER_VEHICLE:
            return ['unexpected_payload']
        return []
    if mode == 'merchant':
        page = (1, 2, 3, 15)[index % 4]
        problems, data = call(connection, 'GET',
                              f'/api/service/project/1/merchants?sort=price_asc&page={page}&page_size=20',
                              plan['tokens'][index % HOME_OWNERS + 1])
        if problems:
            return problems
        if not isinstance(data, dict) or len(data.get('items') or []) != 20:
            return ['unexpected_payload']
        return []
    share = index % WRITE_OWNERS
    quote = (index + round_index * plan['concurrency']) % MERCHANTS + 1
    body = {'merchant_project_id': quote, 'quote_version_id': quote,
            'vehicle_id': HOME_OWNERS * HOME_VEHICLES + share + 1,
            'slot_id': plan.get('slot_base', 0) + round_index * plan['concurrency'] + index + 1}
    problems, data = call(connection, 'POST', '/api/order/create', plan['write_tokens'][share], body,
                          str(uuid.uuid4()))
    if problems:
        return problems
    if not isinstance(data, dict) or not isinstance(data.get('order_id'), int) or data['order_id'] <= 0:
        return ['unexpected_payload']
    return []


def client_thread(offset, local, plan, latency, codes, state, start, done):
    index = offset + local
    connection = http.client.HTTPConnection(plan['host'], plan['port'], timeout=60)
    while True:
        start.wait()
        if state[1]:
            connection.close()
            return
        round_index = state[0]
        started = time.perf_counter()
        try:
            code = code_of(perform(connection, plan, index, round_index))
        except Exception:
            # One trace per client process: transport failures must not be invisible, but the
            # report only keeps the classified code so tokens never reach the evidence.
            if not _DIAGNOSTICS:
                _DIAGNOSTICS.append(1)
                import traceback
                traceback.print_exc()
            code = 1001
            try:
                connection.close()
            except OSError:
                pass
            connection = http.client.HTTPConnection(plan['host'], plan['port'], timeout=60)
        latency[index] = (time.perf_counter() - started) * 1000
        codes[index] = code
        done.wait()


def client_group(offset, plan, latency, codes, state, start, done):
    threads = [threading.Thread(target=client_thread, daemon=True,
                                args=(offset, local, plan, latency, codes, state, start, done))
               for local in range(plan['workers'])]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()


class LoadClients:
    """500 simultaneous requests split across processes and threads.

    One Python process cannot drive 500 concurrent requests: the GIL makes the client, not
    the server, the bottleneck. Workers are therefore spread over several processes, each
    holding a few dozen threads with one keep-alive connection, and released by a shared
    barrier so every round starts with the declared concurrency.
    """

    def __init__(self, host, port, concurrency, processes, tokens, mode):
        require(concurrency % processes == 0, 'Concurrency must divide evenly across client processes')
        require(processes <= concurrency, 'More client processes than workers')
        context = multiprocessing.get_context('spawn')
        self.concurrency, self.processes = concurrency, processes
        self.latency = context.RawArray('d', concurrency)
        self.codes = context.RawArray('i', concurrency)
        self.state = context.RawArray('i', 2)
        self.start = context.Barrier(concurrency + 1)
        self.done = context.Barrier(concurrency + 1)
        per_process = concurrency // processes
        self.handles = []
        for number in range(processes):
            plan = {'host': host, 'port': port, 'mode': mode, 'workers': per_process,
                    'concurrency': concurrency, 'tokens': tokens if mode != 'order' else None,
                    'write_tokens': tokens if mode == 'order' else None}
            handle = context.Process(target=client_group, daemon=True,
                                     args=(number * per_process, plan, self.latency, self.codes,
                                           self.state, self.start, self.done))
            handle.start()
            self.handles.append(handle)

    def round(self, round_index):
        self.state[0] = round_index
        self.start.wait(timeout=120)
        started = time.perf_counter()
        self.done.wait(timeout=120)
        elapsed = time.perf_counter() - started
        results = [(round(self.latency[index], 3), self.codes[index]) for index in range(self.concurrency)]
        return results, elapsed

    def stop(self):
        self.state[1] = 1
        self.start.wait(timeout=120)
        for handle in self.handles:
            handle.join(timeout=30)
        require(not any(handle.is_alive() for handle in self.handles), 'Load client process did not exit')


class LinuxLoadRuntime(Owned):
    """Direct Linux-network control; uses exactly the same worker and payload functions."""
    def __init__(self, run, network, backend, options, work):
        super().__init__(run,'linux_load_runtime',f'autocare-perf-{run[:12]}-load',{LABEL:run,'autocare.performance.role':'linux_load_runtime'})
        self.network,self.backend,self.options,self.work=network,backend,options,work
    def start(self):
        args=['run','--detach','--rm','--name',self.name,'--network',self.network.name,
              '--pids-limit','2048','--ulimit','nofile=8192:8192',
              '--label',f'{LABEL}={self.run}','--label','autocare.performance.role=linux_load_runtime',
              '--mount',f'type=bind,source={ROOT / "scripts"},target=/scripts,readonly',LOAD_IMAGE,'sleep','infinity']
        self.id=Docker.run(args).stdout.decode().strip();self.guard()
    def config(self,tokens,mode):
        return {'host':self.backend.name,'port':8080,'concurrency':self.options.concurrency,
                'processes':self.options.client_processes,'tokens':tokens,'mode':mode,'slot_base':planned_slot_base(self.options)}
    def preflight(self,tokens,mode):
        self.guard()
        raw=Docker.run(['exec','-i',self.id,'python','/scripts/performance_linux_client.py','--preflight'],
                       (json.dumps(self.config(tokens,mode))+'\n').encode(),timeout=180)
        return json.loads(raw.stdout)
    def clients(self,tokens,mode):return LinuxLoadClients(self,tokens,mode)


class LinuxLoadClients:
    def __init__(self,runtime,tokens,mode):
        runtime.guard();self.stopped=False;self.concurrency=runtime.options.concurrency
        self.log=(runtime.work/f'linux-client-{mode}.private.log').open('wb')
        self.process=subprocess.Popen(['docker','exec','-i',runtime.id,'python','/scripts/performance_linux_client.py'],
                                      stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=self.log,text=True,encoding='utf-8',bufsize=1)
        self.output=queue.Queue()
        def read():
            try:
                for line in self.process.stdout:self.output.put(line)
            finally:self.output.put(None)
        threading.Thread(target=read,daemon=True).start()
        self.send(runtime.config(tokens,mode));require(self.receive()=={'ready':True},'Linux load workers did not become ready')
    def send(self,value):self.process.stdin.write(json.dumps(value)+'\n');self.process.stdin.flush()
    def receive(self):
        try:raw=self.output.get(timeout=180)
        except queue.Empty:raise PerformanceError('Linux load observation timeout')
        require(raw is not None,'Linux load process exited without an observation')
        return json.loads(raw)
    def round(self,index):
        self.send({'round':index});result=self.receive();samples=result.get('samples')
        require(isinstance(samples,list) and len(samples)==self.concurrency,'Incomplete Linux load round')
        require(all(isinstance(s,list) and len(s)==2 and type(s[0]) in (int,float) and math.isfinite(s[0]) and s[0]>0
                    and type(s[1]) is int and s[1]>=0 for s in samples),'Invalid Linux load observations')
        return samples,result['elapsed']
    def stop(self):
        if self.stopped:return
        try:
            self.send({'stop':True});require(self.receive()=={'stopped':True},'Linux load workers did not stop')
            self.process.stdin.close();require(self.process.wait(timeout=30)==0,'Linux load process failed')
        finally:
            self.stopped=True
            if self.process.poll() is None:self.process.kill();self.process.wait(timeout=10)
            self.log.close()


def call(connection, method, path, token_value, body=None, key=None):
    """Returns (problems, data). Any non-200 or non-zero business code counts as an error."""
    headers = {'Accept': 'application/json'}
    if token_value:
        headers['Authorization'] = 'Bearer ' + token_value
    payload = None
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode()
        headers['Content-Type'] = 'application/json'
    if key:
        headers['Idempotency-Key'] = key
    connection.request(method, path, body=payload, headers=headers)
    response = connection.getresponse()
    raw = response.read()
    if response.status != 200:
        # Bounded console diagnostic: the platform's own error text, never a token or row value.
        if len(_HTTP_DETAILS) < 3:
            try:
                message = json.loads(raw).get('message')
            except ValueError:
                message = None
            _HTTP_DETAILS.append(str(message)[:120] if message else '<no message>')
            print('Performance: HTTP ' + str(response.status) + ' ' + _HTTP_DETAILS[-1], flush=True)
        return ['http_' + str(response.status)], None
    try:
        parsed = json.loads(raw)
    except ValueError:
        return ['invalid_json'], None
    if not isinstance(parsed, dict) or parsed.get('code') != 0:
        return ['business_error'], None
    return [], parsed.get('data')


def preflight(port, mode, tokens, requests=PREFLIGHT_REQUESTS, slot_base=0, host='127.0.0.1'):
    """Sequential probe before any concurrency: makes cold-start and per-request cost visible."""
    plan = {'host': host, 'port': port, 'mode': mode, 'concurrency': CONCURRENCY,
            'slot_base': slot_base,
            'tokens': tokens if mode != 'order' else None,
            'write_tokens': tokens if mode == 'order' else None}
    connection = http.client.HTTPConnection(host, port, timeout=60)
    samples, kinds = [], {}
    try:
        for index in range(requests):
            started = time.perf_counter()
            code = code_of(perform(connection, plan, index, 0))
            samples.append((time.perf_counter() - started) * 1000)
            if code:
                kinds[code_name(code)] = kinds.get(code_name(code), 0) + 1
    finally:
        connection.close()
    return {'requests': requests, 'errors': sum(kinds.values()), 'error_kinds': kinds,
            'latency_ms': summarize(samples)}


class MetricsSampler(threading.Thread):
    """Samples the server's own gauges and container CPU so a slow run can be attributed."""

    NAMES = ('hikaricp_connections_active', 'hikaricp_connections_pending',
             'hikaricp_connections_max', 'tomcat_threads_busy_threads', 'tomcat_threads_current_threads')

    def __init__(self, port, containers=None):
        super().__init__(daemon=True)
        self.port, self.stop_flag = port, False
        self.containers = containers or {}
        self.samples, self.error = 0, None
        self.peaks = {name: 0.0 for name in self.NAMES}
        self.cpu = {role: 0.0 for role in self.containers}

    def run(self):
        connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=5)
        while not self.stop_flag:
            try:
                connection.request('GET', '/actuator/prometheus')
                body = connection.getresponse().read().decode('utf-8', 'replace')
            except OSError:
                try:
                    connection.close()
                except OSError:
                    pass
                connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=5)
                time.sleep(0.5)
                continue
            self.samples += 1
            totals = {name: 0.0 for name in self.NAMES}
            for line in body.splitlines():
                if line.startswith('#'):
                    continue
                name, _, value = line.partition(' ')
                base = name.split('{')[0]
                if base in totals:
                    try:
                        totals[base] += float(value)
                    except ValueError:
                        pass
            for name, value in totals.items():
                self.peaks[name] = max(self.peaks[name], value)
            if self.containers:
                result = Docker.run(['stats', '--no-stream', '--format', '{{.Name}} {{.CPUPerc}}',
                                     *self.containers.values()], timeout=15, allow_failure=True)
                for line in result.stdout.decode('utf-8', 'replace').splitlines():
                    name, _, percent = line.partition(' ')
                    for role, container in self.containers.items():
                        if name == container:
                            try:
                                self.cpu[role] = max(self.cpu[role], float(percent.rstrip('%')))
                            except ValueError:
                                pass
            time.sleep(0.5)
        connection.close()

    def summary(self):
        return {'samples': self.samples,
                'peaks': {name: round(value, 2) for name, value in self.peaks.items()},
                'container_cpu_percent_peak': {role: round(value, 1) for role, value in self.cpu.items()}}


def run_scenario(clients, rounds, warmup, port, containers):
    sampler = MetricsSampler(port, containers)
    sampler.start()
    try:
        for round_index in range(warmup):
            clients.round(round_index)
        samples, problems, kinds = [], 0, {}
        started = time.perf_counter()
        for round_index in range(warmup, warmup + rounds):
            results, _ = clients.round(round_index)
            for latency, code in results:
                samples.append(latency)
                if code:
                    problems += 1
                    kinds[code_name(code)] = kinds.get(code_name(code), 0) + 1
        elapsed = time.perf_counter() - started
    finally:
        sampler.stop_flag = True
        sampler.join(timeout=5)
    return samples, problems, kinds, elapsed, sampler.summary()


def scenario_record(name, samples, problems, kinds, elapsed, metrics, options, http_requests):
    threshold, total = THRESHOLDS[name], len(samples)
    latency, error_rate = summarize(samples), problems / total
    checks = [
        {'name': 'requests_completed', 'value': total, 'expected': options.concurrency * name_rounds(name, options),
         'passed': total == options.concurrency * name_rounds(name, options)},
        {'name': 'error_rate_below_0.1%', 'value': round(error_rate, 6), 'expected': ERROR_RATE,
         'passed': error_rate < ERROR_RATE},
        {'name': 'p95_target', 'value': latency['p95_ms'], 'expected': threshold['p95_ms'],
         'passed': latency['p95_ms'] < threshold['p95_ms']},
    ]
    if threshold['p99_ms'] is not None:
        checks.append({'name': 'p99_target', 'value': latency['p99_ms'], 'expected': threshold['p99_ms'],
                       'passed': latency['p99_ms'] < threshold['p99_ms']})
    return {
        'id': name, 'title': TITLES[name], 'scope': SCOPES[name],
        'concurrency': options.concurrency, 'rounds': name_rounds(name, options),
        'warmup_requests': options.concurrency * options.warmup_rounds,
        'http_requests_per_sample': http_requests, 'http_requests': total * http_requests,
        'requests': total, 'errors': problems, 'error_kinds': kinds,
        'error_rate': round(error_rate, 6), 'duration_seconds': round(elapsed, 3),
        'throughput_samples_per_second': round(total / elapsed, 2) if elapsed else None,
        'latency_ms': latency, 'thresholds': threshold, 'checks': checks,
        'server_metrics': metrics,
        'latency_target_met': all(c['passed'] for c in checks if c['name'].endswith('_target')),
        'passed': all(c['passed'] for c in checks),
    }


def name_rounds(name, options):
    return options.write_rounds if name == 'order_create' else options.read_rounds


def evaluate(report, gates):
    """gates=all enforces latency targets; gates=errors records latency but only enforces the error budget."""
    for scenario in report['scenarios']:
        enforced = [c for c in scenario['checks'] if gates == 'all' or not c['name'].endswith('_target')]
        scenario['enforced_checks'] = [c['name'] for c in enforced]
        scenario['passed'] = all(c['passed'] for c in enforced)
    report['gates'] = gates
    report['passed'] = all(s['passed'] for s in report['scenarios'])
    return report


def check_matches(check):
    if check['name'].endswith('_target') or 'error_rate' in check['name']:
        return check['passed'] == (check['value'] < check['expected'])
    return check['passed'] == (check['value'] == check['expected'])


def validate(path):
    report = json.loads(Path(path).read_text(encoding='utf-8'))
    require(report.get('version') == 1 and report.get('kind') == 'performance-baseline', 'Unexpected report kind')
    require(report.get('gates') in ('all', 'errors'), 'Unknown gate policy')
    require(isinstance(report.get('scenarios'), list) and len(report['scenarios']) == 3, 'Expected three scenarios')
    require({s.get('id') for s in report['scenarios']} == set(THRESHOLDS), 'Scenario ids changed')
    require((report.get('dataset') or {}).get('rows_total', 0) > 0, 'Missing dataset size')
    require((report.get('method') or {}).get('concurrency') == CONCURRENCY, 'Baseline must run at 500 concurrency')
    require(report.get('tables') == TABLES, 'Migration table count missing')
    require(set((report.get('preflight') or {}).keys()) == set(THRESHOLDS), 'Missing sequential preflight evidence')
    require(all(item['errors'] == 0 for item in report['preflight'].values()),
            'Preflight reported failures; the measured scenarios are not trustworthy')
    for scenario in report['scenarios']:
        name = scenario['id']
        require(scenario['thresholds'] == THRESHOLDS[name], 'Threshold drift for ' + name)
        latency = scenario['latency_ms']
        require(latency['samples'] == scenario['requests'] == scenario['concurrency'] * scenario['rounds'],
                'Sample count does not match concurrency x rounds for ' + name)
        ordered = [latency[key] for key in ('min_ms', 'p50_ms', 'p90_ms', 'p95_ms', 'p99_ms', 'max_ms')]
        require(ordered == sorted(ordered), 'Unsorted percentiles for ' + name)
        expected = {'requests_completed', 'error_rate_below_0.1%', 'p95_target'}
        if THRESHOLDS[name]['p99_ms'] is not None:
            expected.add('p99_target')
        require({c['name'] for c in scenario['checks']} == expected, 'Check set changed for ' + name)
        require(all(check_matches(c) for c in scenario['checks']), 'Inconsistent check for ' + name)
        enforced = [c for c in scenario['checks']
                    if report['gates'] == 'all' or not c['name'].endswith('_target')]
        require(scenario.get('enforced_checks') == [c['name'] for c in enforced],
                'Enforced check list inconsistent for ' + name)
        require(scenario['passed'] == all(c['passed'] for c in enforced), 'Scenario verdict inconsistent for ' + name)
    require(report['passed'] == all(s['passed'] for s in report['scenarios']), 'Global verdict inconsistent')
    require(bool(report.get('boundaries')), 'Missing boundaries')
    require(report.get('cleanup') and all(item['passed'] for item in report['cleanup']),
            'Owned resource cleanup evidence missing')
    return report


def run_stack(report, options):
    run_id = uuid.uuid4().hex
    work = ROOT / '.cache' / ('performance-baseline-' + run_id[:12])
    work.mkdir(parents=True)
    network = Owned(run_id, 'network', f'autocare-perf-{run_id[:12]}', {LABEL: run_id}, kind='network')
    mysql, backend, clients, linux_runtime = MySQL(run_id, network), None, None, None
    started = time.monotonic()
    files = migration_files()
    report['migrations'] = {p.name: digest(p.read_bytes()) for p in files}
    report['migration_last'] = files[-1].name
    try:
        print('Performance: create owned network', flush=True)
        raw = Docker.run(['network', 'create', '--label', f'{LABEL}={run_id}', network.name]).stdout.decode().strip()
        require(bool(re.fullmatch('[0-9a-f]{64}', raw)), 'Docker did not return a full owned network ID')
        network.id = raw
        network.guard()
        print('Performance: start owned MySQL', flush=True)
        mysql.start()
        print('Performance: apply migrations', flush=True)
        mysql.migrate(files)
        count = int(mysql.text('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();'))
        require(count == TABLES, f'Expected {TABLES} tables after migrations, found {count}')
        report['tables'] = count
        secret = secrets.token_hex(24)
        sessions = {owner: str(uuid.uuid4()) for owner in range(1, HOME_OWNERS + WRITE_OWNERS + 1)}
        slot_base=planned_slot_base(options);slot_count=slot_base+PREFLIGHT_REQUESTS
        sql, counts = build_dataset(sessions, lambda owner: digest(sessions[owner].encode()),slot_count)
        print('Performance: seed ' + str(counts['rows_total']) + ' rows', flush=True)
        mysql.seed(sql)
        require(int(mysql.text(f'SELECT COUNT(*) FROM vehicle WHERE user_id=1;')) == HOME_VEHICLES,
                'Seed verification failed for the home owner')
        require(int(mysql.text('SELECT COUNT(*) FROM appointment_slot;')) == slot_count, 'Seed verification failed for slots')
        require(slot_base >= (options.warmup_rounds + options.write_rounds) * options.concurrency,
                'Not enough slots for the requested write rounds')
        report['dataset'] = counts
        print('Performance: build backend jar', flush=True)
        build_jar(options.native_build,options.maven_repository)
        port = free_port()
        backend = Backend(run_id, network, port, secret, options.pool_size)
        print('Performance: start backend on 127.0.0.1:' + str(port), flush=True)
        backend.start(mysql)
        backend.wait_healthy()
        if options.linux_client:
            linux_runtime=LinuxLoadRuntime(run_id,network,backend,options,work);linux_runtime.start()
        report['environment'] = {
            'host_platform': platform.platform(),
            'host_cpu_model': platform.processor() or platform.machine(),
            'host_cpu_logical': os.cpu_count(),
            'host_memory_mb': memory_mb(),
            'python': platform.python_version(),
            'docker_server': Docker.run(['version', '--format', '{{.Server.Version}}']).stdout.decode().strip(),
            'client_position': 'owned Linux container on direct Docker network, separate from backend, same host' if options.linux_client else 'load client in this process, separate from the server container, same host',
            'load_client_image': LOAD_IMAGE if options.linux_client else None,
            'backend_publish': f'127.0.0.1:{port} -> 8080',
            'runtime_image': RUNTIME_IMAGE, 'jvm_options': '-Xms512m -Xmx512m',
            'backend_build': 'host-native-clean' if options.native_build else 'docker-maven',
            'mysql_image': MYSQL_IMAGE, 'mysql_version': mysql.text('SELECT VERSION();'),
            'mysql_max_connections': mysql.text('SELECT @@max_connections;'),
            'mysql_pool_size': options.pool_size,
            'tomcat_mbean_registry': True,
        }
        report['method'] = {'concurrency': options.concurrency, 'read_rounds': options.read_rounds,
                            'write_rounds': options.write_rounds, 'warmup_rounds': options.warmup_rounds,
                            'percentile': 'nearest-rank', 'client_keep_alive': True,
                            'client_processes': options.client_processes,
                            'measurement': 'perf_counter around one round trip per sample; per-round barrier release',
                            'order_distribution': f'{options.concurrency} concurrent orders spread over '
                                                  f'{MERCHANTS} synthetic shops'}
        # Session 1..HOME_OWNERS belong to the home/list readers; the rest belong to order writers.
        reader_tokens = {owner: token(secret, owner, sessions[owner]) for owner in range(1, HOME_OWNERS + 1)}
        writer_tokens = [token(secret, HOME_OWNERS + share + 1, sessions[HOME_OWNERS + share + 1])
                         for share in range(WRITE_OWNERS)]
        report['preflight'] = {}
        for name in ('owner_home', 'merchant_list', 'order_create'):
            report['preflight'][name] = linux_runtime.preflight(writer_tokens if name=='order_create' else reader_tokens,MODES[name]) if linux_runtime else preflight(
                port, MODES[name], writer_tokens if name == 'order_create' else reader_tokens,
                slot_base=slot_base if name == 'order_create' else 0)
            require(report['preflight'][name]['errors'] == 0,
                    'Preflight request failed for ' + name + '; fix the stack before measuring')
        print('Performance: preflight ' + json.dumps(
            {name: round(value['latency_ms']['p50_ms'], 2) for name, value in report['preflight'].items()}),
            flush=True)
        clients = None
        for name, tokens in (('owner_home', reader_tokens), ('merchant_list', reader_tokens),
                             ('order_create', writer_tokens)):
            print('Performance: scenario ' + name, flush=True)
            if clients is not None:
                clients.stop()
            clients = linux_runtime.clients(tokens,MODES[name]) if linux_runtime else LoadClients('127.0.0.1', port, options.concurrency, options.client_processes,
                                  tokens, MODES[name])
            record = scenario_record(name, *run_scenario(clients, name_rounds(name, options),
                                                         options.warmup_rounds, port,
                                                         {'backend': backend.name, 'mysql': mysql.name}),
                                     options, 2 if name == 'owner_home' else 1)
            report['scenarios'].append(record)
        if any(s['errors'] for s in report['scenarios']):
            status = mysql.text('SHOW ENGINE INNODB STATUS\\G')
            marker = status.find('LATEST DETECTED DEADLOCK')
            report['deadlock_evidence'] = status[marker:marker + 2500] if marker >= 0 else 'no deadlock reported'
            report['innodb_lock_wait_timeout'] = mysql.text('SELECT @@innodb_lock_wait_timeout;')
        report['database_after_load'] = {
            'orders': int(mysql.text('SELECT COUNT(*) FROM `order`;')),
            'reserved_slots': int(mysql.text('SELECT COUNT(*) FROM appointment_slot WHERE reserved_count=1;')),
            'idempotency_records': int(mysql.text('SELECT COUNT(*) FROM idempotency_record;')),
        }
        expected_orders = (options.warmup_rounds + options.write_rounds) * options.concurrency + PREFLIGHT_REQUESTS
        require(report['database_after_load']['orders'] == expected_orders,
                'Committed order count does not match the requested write rounds')
        require(report['database_after_load']['reserved_slots'] == expected_orders,
                'Reserved slot count does not match the committed orders')
        require(report['migrations'] == {p.name: digest(p.read_bytes()) for p in files},
                'Migrations changed during execution')
    finally:
        report['cleanup'] = []
        if backend is not None and backend.id is not None:
            try:
                (work / 'server.log').write_bytes(
                    Docker.run(['logs', '--tail', '500', backend.id], allow_failure=True).stdout)
            except Exception:
                pass
        if clients is not None:
            try:
                clients.stop()
            except Exception:
                report['cleanup'].append({'role': 'load_client', 'passed': False})
        for owned in (linux_runtime, backend, mysql, network):
            if owned is None:
                continue
            try:
                owned.close()
                report['cleanup'].append({'role': owned.role, 'passed': True})
            except Exception:
                report['cleanup'].append({'role': owned.role, 'passed': False})
        require(all(item['passed'] for item in report['cleanup']) if report['cleanup'] else True,
                'Owned resource cleanup failed; inspect this run before retrying')
    report['duration_seconds'] = round(time.monotonic() - started, 3)
    return work


def main():
    parser = argparse.ArgumentParser(description='Run the isolated performance baseline.')
    parser.add_argument('--concurrency', type=int, default=CONCURRENCY)
    parser.add_argument('--read-rounds', type=int, default=READ_ROUNDS)
    parser.add_argument('--write-rounds', type=int, default=WRITE_ROUNDS)
    parser.add_argument('--warmup-rounds', type=int, default=WARMUP_ROUNDS)
    parser.add_argument('--pool-size', type=int, default=POOL_SIZE)
    parser.add_argument('--client-processes', type=int, default=CLIENT_PROCESSES)
    parser.add_argument('--gates', choices=['all', 'errors'], default='all',
                        help="'all' enforces latency targets; 'errors' only enforces the error budget")
    parser.add_argument('--report', type=Path, help='Write the evidence report to this path as well')
    parser.add_argument('--check-only', type=Path, help='Validate an existing report without running the stack')
    parser.add_argument('--native-build', action='store_true', help='Build from current sources with host Maven/Java, retaining a private build log.')
    parser.add_argument('--linux-client', action='store_true', help='Use the same workers in a separately owned Linux container on the test network.')
    parser.add_argument('--maven-repository', type=Path, help='Host dependency cache; only with --native-build.')
    options = parser.parse_args()
    if options.maven_repository and not options.native_build:parser.error('--maven-repository requires --native-build')
    if options.check_only:
        report = validate(options.check_only)
        print('Performance evidence: ' + ('PASS' if report['passed'] else 'FAIL')
              + '; scenarios=' + ','.join(s['id'] for s in report['scenarios'])
              + '; gates=' + report['gates'])
        return 0 if report['passed'] else 1
    report = {'version': 1, 'kind': 'performance-baseline', 'passed': False, 'image': MYSQL_IMAGE,
              'started_at': datetime.now(timezone.utc).isoformat(), 'scenarios': [], 'cleanup': [],
              'boundaries': [
                  'synthetic owners/vehicles/archives and one synthetic project; no production data',
                  'core HTTP endpoints only; no AI, WeChat, SMS or real payment provider in the timed path',
                  'single host with MySQL and backend containers; not a production capacity proof',
                  'no soak, spike decay or fault injection; no cold-start or real-network measurement',
                  'order creation is spread over synthetic shops; one shop still serialises on its row lock']}
    inputs = [ROOT / 'scripts/run_performance_baseline.py', ROOT / 'scripts/performance_linux_client.py', ROOT / 'backend/pom.xml'] + migration_files()
    inputs += sorted(p for p in (ROOT / 'backend/src/main').rglob('*') if p.is_file())
    report['implementation_inputs_sha256'] = {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in inputs}
    work = None
    try:
        report['started_from_commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
        work = run_stack(report, options)
        report['backend_jar_sha256'] = digest(JAR.read_bytes())
        report['implementation_sources_unchanged'] = report['implementation_inputs_sha256'] == {
            p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in inputs}
        require(report['implementation_sources_unchanged'], 'Performance implementation changed during execution')
        evaluate(report, options.gates)
    except Exception as exc:
        report['error'] = str(exc) if isinstance(exc, PerformanceError) else type(exc).__name__
        report['passed'] = False
    report['finished_at'] = datetime.now(timezone.utc).isoformat()
    output = (work or ROOT / '.cache') / 'report.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    output.write_text(text, encoding='utf-8')
    if options.report:
        options.report.parent.mkdir(parents=True, exist_ok=True)
        options.report.write_text(text, encoding='utf-8')
    if report.get('passed') and options.concurrency == CONCURRENCY:
        validate(output)
    print('Performance baseline: ' + ('PASS' if report.get('passed') else 'FAIL') + '; report: ' + str(output), flush=True)
    return 0 if report.get('passed') else 1


if __name__ == '__main__':
    sys.exit(main())
