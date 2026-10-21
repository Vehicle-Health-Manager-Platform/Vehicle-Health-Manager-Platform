"""Operator catalog governance and price-monitor policy evidence through real isolated HTTP."""
import base64
import hashlib
import hmac
import json
import time
import uuid
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble
from fulfillment_api_cases import seed as identity_seed

OUTPUT = ROOT/'docs/testing/postman/governance-cases.postman_collection.json'
MANIFEST = ROOT/'docs/testing/postman/governance-case-manifest.json'
OPERATION_COUNT = 9

FUTURE_FROM = '2998-12-31T16:00:00Z'
FUTURE_TO = '2999-01-31T16:00:00Z'
ISO = r'/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/'
HEX64 = r'/^[0-9a-f]{64}$/'
SOURCE_NOTE = '合成目录来源说明'
BRAND = {'name': 'synthetic governed brand', 'sort_order': 1, 'source_note': '合成目录新增来源'}
SERIES = {'brand_id': 1, 'name': 'synthetic series', 'sort_order': 2, 'source_note': '合成目录重复来源'}
PROJECT = {'project_name': 'synthetic service', 'category': 1, 'service_content': 'synthetic inspection',
           'quality_standard': '', 'base_price_low': '1.00', 'base_price_high': '100.00',
           'sort_order': 0, 'source_note': SOURCE_NOTE, 'status': 1, 'revision': 1}
POLICY = {'quote_id': 1, 'source_hash': '__QUOTE_HASH__', 'lower_price': '12.00', 'upper_price': '13.00',
          'price_unit': '元/次', 'applicability_note': '合成适用说明', 'source_note': '合成价格依据说明',
          'scope_confirmed': True, 'valid_from': FUTURE_FROM, 'valid_to': FUTURE_TO}


def policy_body(body):
    """The source hash is only obtainable from the quote read, so the body carries a variable."""
    return json.dumps(body, ensure_ascii=False).replace('"__QUOTE_HASH__"', '"{{quote_hash}}"')


def replay_key(name):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'autocare-coupon:replay:'+name))


def cases():
    c = Cases(); add = c.add
    catalog = '/api/admin/catalog'; monitor = '/api/admin/price-monitor'
    projects = catalog+'/projects'; brand = catalog+'/brands'; series = catalog+'/series'
    policies = monitor+'/policies'; quotes = monitor+'/quotes'
    draft = 'pm.expect(pm.response.json().data.data).to.eql(undefined);'
    # 报价证据：目录来源说明为空时，报价只能是 UNKNOWN —— 先证明"不可确认"。
    add('quotes_unknown_source', 'GET', quotes, 200, 'boundary', role='monitor', extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(1);pm.expect(d.page).to.eql(1);pm.expect(d.page_size).to.eql(20);',
        'const q=pm.response.json().data.items[0];pm.expect(q.quote_id).to.eql(1);pm.expect(q.state).to.eql("UNKNOWN");pm.expect(q.source_hash).to.eql(null);',
        'const q=pm.response.json().data.items[0];pm.expect(q.source.price).to.eql("12.34");pm.expect(q.source.quote_version).to.eql(1);pm.expect(q.source.project.source_note).to.eql("");'])
    # 目录清单：一页一条已发布项目，来源说明尚未补。
    add('projects_list', 'GET', projects+'?page=1&page_size=20', 200, 'normal', role='catalog', extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(1);pm.expect(d.page).to.eql(1);pm.expect(d.page_size).to.eql(20);',
        'const p=pm.response.json().data.items[0];pm.expect(p.id).to.eql(1);pm.expect(p.project_name).to.eql("synthetic service");pm.expect(p.category).to.eql(1);',
        'const p=pm.response.json().data.items[0];pm.expect(p.status).to.eql(1);pm.expect(p.revision).to.eql(1);pm.expect(p.sort_order).to.eql(0);pm.expect(p.source_note).to.eql("");',
        'const p=pm.response.json().data.items[0];pm.expect(p.base_price_low).to.eql("1.00");pm.expect(p.base_price_high).to.eql("100.00");pm.expect(p.quality_standard).to.eql("");'])
    add('projects_list_last_page', 'GET', projects+'?page=999999', 200, 'boundary', role='catalog', extra=[
        'const d=pm.response.json().data;pm.expect(d.items).to.eql([]);pm.expect(d.total).to.eql(1);pm.expect(d.page).to.eql(999999);'])
    add('projects_list_page_size', 'GET', projects+'?page_size=51', 400, 'error', role='catalog')
    add('projects_list_anonymous', 'GET', projects, 401, 'unauthorized', role='anonymous')
    add('projects_list_monitor_denied', 'GET', projects, 403, 'unauthorized', role='monitor')
    # 新增目录只能落草稿，不接受客户端自带状态。
    add('brand_create', 'POST', brand, 200, 'normal', BRAND, 'catalog', key=replay_key('brand_create'), extra=[
        'const d=pm.response.json().data;pm.expect(d.id).to.eql(2);pm.expect(d.name).to.eql("synthetic governed brand");pm.expect(d.status).to.eql(0);pm.expect(d.revision).to.eql(1);',
        'const d=pm.response.json().data;pm.expect(d.source_note).to.eql("合成目录新增来源");pm.expect(d.sort_order).to.eql(1);', draft,
        'pm.collectionVariables.set("receipt_brand",JSON.stringify(pm.response.json()));'])
    add('brand_create_replay', 'POST', brand, 200, 'boundary', BRAND, 'catalog', key=replay_key('brand_create'), extra=[
        'pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_brand")));'])
    add('brand_create_changed_body', 'POST', brand, 400, 'error', dict(BRAND, name='synthetic changed brand'), 'catalog',
        key=replay_key('brand_create'))
    add('series_create_duplicate', 'POST', series, 409, 'error', SERIES, 'catalog')
    add('brand_create_anonymous', 'POST', brand, 401, 'unauthorized', BRAND, 'anonymous')
    add('brand_create_plain_denied', 'POST', brand, 403, 'unauthorized', BRAND, 'plain')
    # 目录修订：补来源说明后同一报价才可确认；缺来源说明的报价仍不可用。
    add('project_update', 'PUT', projects+'/1', 200, 'normal', PROJECT, 'catalog', key=replay_key('project_update'), extra=[
        'const d=pm.response.json().data;pm.expect(d.id).to.eql(1);pm.expect(d.revision).to.eql(2);pm.expect(d.status).to.eql(1);',
        'const d=pm.response.json().data;pm.expect(d.source_note).to.eql("合成目录来源说明");pm.expect(d.project_name).to.eql("synthetic service");pm.expect(d.base_price_low).to.eql("1.00");', draft,
        'pm.collectionVariables.set("receipt_project",JSON.stringify(pm.response.json()));'])
    add('project_update_replay', 'PUT', projects+'/1', 200, 'boundary', PROJECT, 'catalog', key=replay_key('project_update'), extra=[
        'pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_project")));'])
    add('project_update_stale_revision', 'PUT', projects+'/1', 409, 'error', PROJECT, 'catalog')
    add('project_update_bad_money', 'PUT', projects+'/1', 400, 'error', dict(PROJECT, revision=2, base_price_low='1'), 'catalog')
    add('project_update_anonymous', 'PUT', projects+'/1', 401, 'unauthorized', dict(PROJECT, revision=2), 'anonymous')
    add('project_update_merchant_denied', 'PUT', projects+'/1', 403, 'unauthorized', dict(PROJECT, revision=2), 'merchant')
    # 补齐来源说明之后：同一报价变成 READY，并由读取结果给出可引用的来源哈希。
    add('quotes_ready_source', 'GET', quotes, 200, 'normal', role='monitor', extra=[
        'const q=pm.response.json().data.items[0];pm.expect(q.state).to.eql("READY");pm.expect(q.source_hash).to.match('+HEX64+');',
        'const q=pm.response.json().data.items[0];pm.expect(q.source.price).to.eql("12.34");pm.expect(q.source.quote_version_id).to.eql(1);pm.expect(q.source.quote_version).to.eql(1);',
        'const q=pm.response.json().data.items[0];pm.expect(q.source.project.source_note).to.eql("合成目录来源说明");pm.expect(q.source.project.quality_standard).to.eql("");pm.expect(q.source.merchant.id).to.eql(1);',
        'pm.collectionVariables.set("quote_hash",pm.response.json().data.items[0].source_hash);'])
    add('quotes_last_page', 'GET', quotes+'?page=2', 200, 'boundary', role='monitor', extra=[
        'const d=pm.response.json().data;pm.expect(d.items).to.eql([]);pm.expect(d.total).to.eql(1);'])
    add('quotes_page_size', 'GET', quotes+'?page_size=10', 400, 'error', role='monitor')
    add('quotes_anonymous', 'GET', quotes, 401, 'unauthorized', role='anonymous')
    add('quotes_catalog_denied', 'GET', quotes, 403, 'unauthorized', role='catalog')
    # 政策只能引用当前报价证据；哈希不同即视为证据已变化。
    add('policy_create', 'POST', policies, 200, 'normal', policy_body(POLICY), 'monitor', key=replay_key('policy_create'), extra=[
        'const d=pm.response.json().data;pm.expect(d.id).to.eql(1);pm.expect(d.quote_id).to.eql(1);pm.expect(d.status).to.eql("DRAFT");pm.expect(d.revision).to.eql(1);',
        'const d=pm.response.json().data;pm.expect(d.lower_price).to.eql("12.00");pm.expect(d.upper_price).to.eql("13.00");pm.expect(d.price_unit).to.eql("元/次");pm.expect(d.scope_confirmed).to.eql(true);',
        'const d=pm.response.json().data;pm.expect(d.rule_version).to.eql("R8B_PRICE_SOURCE_V1");pm.expect(d.comparison_available).to.eql(true);pm.expect(d.published_at).to.eql(null);pm.expect(d.stopped_at).to.eql(null);',
        'const d=pm.response.json().data;pm.expect(d.source_hash).to.eql(pm.collectionVariables.get("quote_hash"));pm.expect(d.source_snapshot.quote_version).to.eql(1);pm.expect(d.source_snapshot.price).to.eql("12.34");',
        'const d=pm.response.json().data;pm.expect(d.valid_from).to.match('+ISO+');pm.expect(d.valid_to).to.match('+ISO+');pm.expect(d.created_at).to.match('+ISO+');', draft,
        'pm.collectionVariables.set("receipt_policy",JSON.stringify(pm.response.json()));'])
    add('policy_create_replay', 'POST', policies, 200, 'boundary', policy_body(POLICY), 'monitor',
        key=replay_key('policy_create'), extra=[
            'pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_policy")));'])
    add('policy_create_changed_body', 'POST', policies, 400, 'error',
        policy_body(dict(POLICY, applicability_note='同键改正文')), 'monitor', key=replay_key('policy_create'))
    add('policy_create_stale_hash', 'POST', policies, 409, 'error',
        policy_body(dict(POLICY, source_hash='0'*64)), 'monitor')
    add('policy_create_past_window', 'POST', policies, 409, 'error',
        policy_body(dict(POLICY, valid_from='2023-12-31T16:00:00Z', valid_to='2024-01-31T16:00:00Z')), 'monitor')
    add('policy_create_bad_range', 'POST', policies, 400, 'error',
        policy_body(dict(POLICY, lower_price='13.00', upper_price='12.00')), 'monitor')
    add('policy_create_anonymous', 'POST', policies, 401, 'unauthorized', policy_body(POLICY), 'anonymous')
    add('policy_create_catalog_denied', 'POST', policies, 403, 'unauthorized', policy_body(POLICY), 'catalog')
    add('policies_list', 'GET', policies, 200, 'normal', role='monitor', extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(1);pm.expect(d.items.length).to.eql(1);pm.expect(d.page).to.eql(1);pm.expect(d.page_size).to.eql(20);',
        'const p=pm.response.json().data.items[0];pm.expect(p.id).to.eql(1);pm.expect(p.quote_id).to.eql(1);pm.expect(p.status).to.eql("DRAFT");pm.expect(p.revision).to.eql(1);'])
    add('policies_list_last_page', 'GET', policies+'?page=2', 200, 'boundary', role='monitor', extra=[
        'const d=pm.response.json().data;pm.expect(d.items).to.eql([]);pm.expect(d.total).to.eql(1);'])
    add('policies_list_page_size', 'GET', policies+'?page_size=10', 400, 'error', role='monitor')
    add('policies_list_anonymous', 'GET', policies, 401, 'unauthorized', role='anonymous')
    add('policies_list_plain_denied', 'GET', policies, 403, 'unauthorized', role='plain')
    add('policy_detail', 'GET', policies+'/1', 200, 'normal', role='monitor', extra=[
        'const d=pm.response.json().data;pm.expect(d.id).to.eql(1);pm.expect(d.quote_id).to.eql(1);pm.expect(d.status).to.eql("DRAFT");pm.expect(d.source_note).to.eql("合成价格依据说明");',
        'const d=pm.response.json().data;pm.expect(d.source_snapshot.project.source_note).to.eql("合成目录来源说明");pm.expect(d.source_snapshot.merchant.name).to.eql("synthetic shop");'])
    add('policy_detail_missing', 'GET', policies+'/2', 404, 'boundary', role='monitor')
    add('policy_detail_bad_id', 'GET', policies+'/0', 400, 'error', role='monitor')
    add('policy_detail_anonymous', 'GET', policies+'/1', 401, 'unauthorized', role='anonymous')
    add('policy_detail_catalog_denied', 'GET', policies+'/1', 403, 'unauthorized', role='catalog')
    # 草稿修订要带乐观锁版本号，版本落后即拒绝。
    add('policy_update', 'PUT', policies+'/1', 200, 'normal',
        policy_body(dict(POLICY, expected_revision=1, applicability_note='合成修订适用说明')), 'monitor', key=replay_key('policy_update'), extra=[
            'const d=pm.response.json().data;pm.expect(d.revision).to.eql(2);pm.expect(d.status).to.eql("DRAFT");pm.expect(d.applicability_note).to.eql("合成修订适用说明");', draft,
            'pm.collectionVariables.set("receipt_policy_update",JSON.stringify(pm.response.json()));'])
    add('policy_update_replay', 'PUT', policies+'/1', 200, 'boundary',
        policy_body(dict(POLICY, expected_revision=1, applicability_note='合成修订适用说明')), 'monitor',
        key=replay_key('policy_update'), extra=[
            'pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_policy_update")));'])
    add('policy_update_stale_revision', 'PUT', policies+'/1', 409, 'error',
        policy_body(dict(POLICY, expected_revision=1, applicability_note='合成旧版修订')), 'monitor')
    add('policy_update_missing_revision', 'PUT', policies+'/1', 400, 'error', policy_body(POLICY), 'monitor')
    add('policy_update_anonymous', 'PUT', policies+'/1', 401, 'unauthorized',
        policy_body(dict(POLICY, expected_revision=2)), 'anonymous')
    add('policy_update_catalog_denied', 'PUT', policies+'/1', 403, 'unauthorized',
        policy_body(dict(POLICY, expected_revision=2)), 'catalog')
    # 状态机：草稿→已发布→停止，且每次都要带当前版本号。
    add('policy_publish', 'PUT', policies+'/1/status', 200, 'normal',
        {'expected_revision': 2, 'status': 'PUBLISHED'}, 'monitor', key=replay_key('policy_publish'), extra=[
            'const d=pm.response.json().data;pm.expect(d.status).to.eql("PUBLISHED");pm.expect(d.revision).to.eql(3);pm.expect(d.stopped_at).to.eql(null);',
            'const d=pm.response.json().data;pm.expect(d.published_at).to.match('+ISO+');', draft,
            'pm.collectionVariables.set("receipt_policy_publish",JSON.stringify(pm.response.json()));'])
    add('policy_publish_replay', 'PUT', policies+'/1/status', 200, 'boundary',
        {'expected_revision': 2, 'status': 'PUBLISHED'}, 'monitor', key=replay_key('policy_publish'), extra=[
            'pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_policy_publish")));'])
    add('policy_publish_again', 'PUT', policies+'/1/status', 409, 'boundary',
        {'expected_revision': 3, 'status': 'PUBLISHED'}, 'monitor')
    add('policy_stop', 'PUT', policies+'/1/status', 200, 'normal',
        {'expected_revision': 3, 'status': 'STOPPED'}, 'monitor', key=replay_key('policy_stop'), extra=[
            'const d=pm.response.json().data;pm.expect(d.status).to.eql("STOPPED");pm.expect(d.revision).to.eql(4);',
            'const d=pm.response.json().data;pm.expect(d.stopped_at).to.match('+ISO+');pm.expect(d.published_at).to.match('+ISO+');', draft,
            'pm.collectionVariables.set("receipt_policy_stop",JSON.stringify(pm.response.json()));'])
    add('policy_stop_replay', 'PUT', policies+'/1/status', 200, 'boundary',
        {'expected_revision': 3, 'status': 'STOPPED'}, 'monitor', key=replay_key('policy_stop'), extra=[
            'pm.expect(pm.response.json()).to.eql(JSON.parse(pm.collectionVariables.get("receipt_policy_stop")));'])
    add('policy_stop_again', 'PUT', policies+'/1/status', 409, 'boundary',
        {'expected_revision': 4, 'status': 'STOPPED'}, 'monitor')
    add('policy_status_bad_value', 'PUT', policies+'/1/status', 400, 'error',
        {'expected_revision': 4, 'status': 'PAUSED'}, 'monitor')
    add('policy_status_anonymous', 'PUT', policies+'/1/status', 401, 'unauthorized',
        {'expected_revision': 4, 'status': 'STOPPED'}, 'anonymous')
    add('policy_status_catalog_denied', 'PUT', policies+'/1/status', 403, 'unauthorized',
        {'expected_revision': 4, 'status': 'STOPPED'}, 'catalog')
    return c.items


def assets():
    return assemble(cases(), 'Autocare isolated catalog governance and price source evidence', 'isolated_governance_cases',
                    OPERATION_COUNT)


def seed(mysql, secret):
    tokens = identity_seed(mysql, secret)
    # 报价证据由商家侧写入产生，这里只补一条与写入同形的审计凭证，证明政策确认的是"当前报价"。
    mysql.sql("""INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,before_state,after_state,request_id)
VALUES('staff_account',1,'MERCHANT_PROJECT_SAVE','merchant_project',1,'{}','{"merchant_project_id":1,"version_id":1,"version":1,"price":"12.34","status":1}','synthetic-governance-quote');""")
    mysql.sql("""INSERT INTO operator_account(id,account,password_hash,phone,status,can_catalog,can_price_monitor) VALUES
(1,'synthetic-governance-catalog','not-a-login-password','13900000021','ACTIVE',1,0),
(2,'synthetic-governance-monitor','not-a-login-password','13900000022','ACTIVE',0,1),
(3,'synthetic-governance-plain','not-a-login-password','13900000023','ACTIVE',0,0);""")
    def sign(subject):
        session = str(uuid.uuid4())
        mysql.sql(f"INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES('{session}','operator_account',{subject},'OPERATOR','operator-account',NULL,NULL,'{hashlib.sha256(session.encode()).hexdigest()}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
        claims = {'iss': 'vehicle-health-manager', 'sub': str(subject), 'iat': int(time.time()), 'exp': int(time.time())+3600,
                  'jti': session, 'subject_type': 'operator_account', 'role': 'OPERATOR', 'app_id': 'operator-account'}
        encode = lambda x: base64.urlsafe_b64encode(json.dumps(x, separators=(',', ':')).encode()).rstrip(b'=').decode()
        raw = encode({'alg': 'HS256', 'typ': 'JWT'})+'.'+encode(claims)
        return raw+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(), raw.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()
    for role, subject in [('catalog', 1), ('monitor', 2), ('plain', 3)]:
        tokens[role+'_token'] = sign(subject)
    tokens['isolated_governance_cases'] = 'true'
    return tokens


def environment(tokens):
    # 目录维护在默认配置下是关闭的，本套件显式开启；价格监测无开关。
    return [('WECHAT_APP_ID', 'fulfillment-cases'), ('CATALOG_GOVERNANCE_ENABLED', 'true')]


def audit(path, collection, manifest, tokens):
    from run_api_acceptance import audit_owner_cases
    result = audit_owner_cases(path, collection, manifest, tokens, expected_operations=OPERATION_COUNT)
    report = json.loads(path.read_text(encoding='utf-8'))
    definitions = {x['name']: x for x in manifest['items']}
    scopes = {}
    for execution in report['run']['executions']:
        case = definitions[execution['item']['name']]
        if case['method'] not in ('POST', 'PUT') or case['role'] == 'anonymous':
            continue
        header = {h['key'].lower(): h['value'] for h in execution['request'].get('header', [])}
        key = header.get('idempotency-key')
        if not key:
            raise ValueError('Governance write case is missing an idempotency key')
        scopes.setdefault((case['method'], case['path'], key), []).append(case['name'])
    replay = sum(1 for names in scopes.values() for name in names if name.endswith('_replay'))
    changed = sum(1 for names in scopes.values() for name in names if name.endswith('_changed_body'))
    for names in scopes.values():
        mains = [n for n in names if not n.endswith('_replay') and not n.endswith('_changed_body')]
        repeats = [n for n in names if n.endswith('_replay') or n.endswith('_changed_body')]
        if len(mains) > 1 or (repeats and len(mains) != 1):
            raise ValueError('Governance idempotency key is shared by unrelated writes: '+str(names))
    if replay != 6 or changed != 2:
        raise ValueError('Governance idempotency coverage differs from declarations')
    result['idempotent_replay_scopes_verified'] = sum(1 for names in scopes.values() if len(names) > 1)
    result['idempotency_changed_body_verified'] = changed
    return result


def verify_database(mysql):
    tables = {'brands': 'brand', 'projects': 'standard_project', 'policies': 'price_monitor_policy',
              'idempotency': 'idempotency_record', 'audits': 'audit_log'}
    actual = {key: int(mysql.text('SELECT COUNT(*) FROM `'+table+'`;')) for key, table in tables.items()}
    expected = dict(brands=2, projects=1, policies=1, idempotency=6, audits=7)
    if actual != expected:
        raise ValueError('Governance catalog/policy rows are missing or duplicated')
    checks = {
        'created_catalog_item_stays_draft': mysql.text("SELECT COUNT(*) FROM brand WHERE id=2 AND status=0 AND revision=1 AND source_note='合成目录新增来源' AND sort_order=1;") == '1',
        'project_source_note_published': mysql.text("SELECT COUNT(*) FROM standard_project WHERE id=1 AND source_note='合成目录来源说明' AND revision=2 AND status=1;") == '1',
        'duplicate_series_never_created': mysql.text("SELECT COUNT(*) FROM series WHERE name='synthetic series';") == '1',
        'policy_final_state': mysql.text('SELECT CONCAT(id,\':\',status,\':\',revision) FROM price_monitor_policy WHERE id=1;') == '1:STOPPED:4',
        'policy_source_bound_to_current_quote': mysql.text("SELECT COUNT(*) FROM price_monitor_policy WHERE id=1 AND quote_id=1 AND JSON_UNQUOTE(JSON_EXTRACT(source_snapshot,'$.quote_id'))='1' AND JSON_UNQUOTE(JSON_EXTRACT(source_snapshot,'$.quote_version'))='1' AND JSON_UNQUOTE(JSON_EXTRACT(source_snapshot,'$.price'))='12.34';") == '1',
        'policy_hash_is_hex64': mysql.text("SELECT COUNT(*) FROM price_monitor_policy WHERE id=1 AND CHAR_LENGTH(source_hash)=64 AND source_hash REGEXP '^[0-9a-f]{64}$';") == '1',
        'policy_range_and_window': mysql.text('SELECT COUNT(*) FROM price_monitor_policy WHERE id=1 AND lower_price=12.00 AND upper_price=13.00 AND valid_to>valid_from AND valid_from>UTC_TIMESTAMP();') == '1',
        'policy_publish_and_stop_stamped': mysql.text('SELECT COUNT(*) FROM price_monitor_policy WHERE id=1 AND published_at IS NOT NULL AND stopped_at IS NOT NULL AND stopped_at>=published_at;') == '1',
        'one_published_policy_per_quote': mysql.text("SELECT COUNT(*) FROM (SELECT quote_id FROM price_monitor_policy WHERE status='PUBLISHED' GROUP BY quote_id HAVING COUNT(*)>1) x;") == '0',
        'idempotent_receipts_kept': mysql.text('SELECT COUNT(*) FROM idempotency_record WHERE response_body IS NULL;') == '0',
        'single_current_quote_evidence': mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='MERCHANT_PROJECT_SAVE' AND resource_type='merchant_project' AND resource_id=1;") == '1',
        'catalog_draft_never_audited_as_published': mysql.text("SELECT COUNT(*) FROM audit_log WHERE action='CATALOG_SAVE' AND JSON_EXTRACT(after_state,'$.status')<>1 AND resource_type<>'brand';") == '0',
        'audit_actions': mysql.text("SELECT GROUP_CONCAT(CONCAT(action,':',n) ORDER BY action) FROM (SELECT action,COUNT(*) n FROM audit_log GROUP BY action) counts;") == 'CATALOG_SAVE:2,MERCHANT_PROJECT_SAVE:1,PRICE_POLICY_CREATE:1,PRICE_POLICY_STATUS:2,PRICE_POLICY_UPDATE:1',
        'audit_request_ids_present': mysql.text("SELECT COUNT(*) FROM audit_log WHERE request_id='' OR request_id IS NULL;") == '0',
    }
    if not all(checks.values()):
        raise ValueError('Governance catalog/price-source invariant failed')
    return {'row_counts': actual, 'checks': checks,
            'scope': 'synthetic identity, catalog and quote-version fixtures; every catalog and policy write via HTTP'}


if __name__ == '__main__':
    collection, manifest = assets()
    for path, data in [(OUTPUT, collection), (MANIFEST, manifest)]:
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('Governance API cases:', manifest['cases'], 'assertions:', sum(x['assertions'] for x in manifest['items']))
