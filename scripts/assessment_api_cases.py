"""Assessment policy, monthly settlement and business dashboard reads through real isolated HTTP."""
import base64
import hashlib
import hmac
import json
import re
import time
import uuid
from coupon_api_cases import Cases
from fulfillment_api_cases import ROOT, assemble
from fulfillment_api_cases import seed as identity_seed

OUTPUT = ROOT/'docs/testing/postman/assessment-cases.postman_collection.json'
MANIFEST = ROOT/'docs/testing/postman/assessment-case-manifest.json'
OPERATION_COUNT = 20

# 固定月份夹具：不随执行日期漂移，断言才可以写死。
CLOSED = '2024-01'        # 已结束，本店有已发布政策
FUTURE = '2999-01'        # 未开始
ABSENT = '3000-01'        # 未开始、无政策、无快照
CLOSED_FROM = '2023-12-31T16:00:00Z'
CLOSED_TO = '2024-01-31T16:00:00Z'
FUTURE_FROM = '2998-12-31T16:00:00Z'
FUTURE_TO = '2999-01-31T16:00:00Z'
ISO = r'/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/'
GRADES = [{'code': 'A', 'label': '优秀', 'min_score': '90.00'}, {'code': 'B', 'label': '合格', 'min_score': '0.00'}]
POLICY = {'merchant_id': 2, 'acquisition_target': 10, 'coupon_target': 10,
          'process_requirements': ['CHECK_IN_CONFIRMED', 'REPORT_SUBMITTED'], 'grade_thresholds': GRADES,
          'source_note': '合成考核政策', 'valid_from': FUTURE_FROM, 'valid_to': FUTURE_TO}


def replay_key(name):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'autocare-coupon:replay:'+name))


def cases():
    c = Cases(); add = c.add; write = c.write; read = c.read
    admin = '/api/admin/assessment'; biz = '/api/admin/business'; mine = '/api/merchant/assessment'
    snapshots = admin+'/snapshots'; closed = '?period_month='+CLOSED; future = '?period_month='+FUTURE
    # 政策清单：种子里只有门店 1 的历史政策。
    add('policies_seeded', 'GET', admin+'/policies', 200, 'normal', role='assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(1);pm.expect(d.items.length).to.eql(1);',
        'const d=pm.response.json().data;const p=d.items[0];pm.expect(p.id).to.eql(1);pm.expect(p.merchant_id).to.eql(1);pm.expect(p.status).to.eql("PUBLISHED");pm.expect(p.revision).to.eql(1);pm.expect(p.rule_version).to.eql("R8B_POLICY_V1");',
        'const p=pm.response.json().data.items[0];pm.expect(p.scope).to.eql("MERCHANT");pm.expect(p.period).to.eql("BEIJING_MONTH");pm.expect(p.timezone).to.eql("Asia/Shanghai");pm.expect(p.weights).to.eql({acquisition:40,coupon:40,process:20});pm.expect(p.metric_source_version).to.eql("R8B_FACTS_V1");',
        'const p=pm.response.json().data.items[0];pm.expect(p.merchant).to.eql({id:1,name:"synthetic shop",address:"synthetic address"});pm.expect(p.process_requirements.length).to.eql(4);pm.expect(p.grade_thresholds.length).to.eql(2);pm.expect(p.grade_thresholds[1].min_score).to.eql("0.00");pm.expect(p.grade_thresholds[0].code).to.eql("A");',
        'const p=pm.response.json().data.items[0];pm.expect(p.valid_from).to.match('+ISO+');pm.expect(p.valid_to).to.match('+ISO+');pm.expect(p.published_at).to.match('+ISO+');'])
    add('policies_last_page', 'GET', admin+'/policies?page=2', 200, 'boundary', role='assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.items).to.eql([]);pm.expect(d.total).to.eql(1);pm.expect(d.page).to.eql(2);pm.expect(d.page_size).to.eql(20);'])
    add('policies_page_size', 'GET', admin+'/policies?page_size=10', 400, 'error', role='assessor')
    add('policies_anonymous', 'GET', admin+'/policies', 401, 'unauthorized', role='anonymous')
    add('policies_analyst_denied', 'GET', admin+'/policies', 403, 'unauthorized', role='analyst')
    add('policies_merchant_denied', 'GET', admin+'/policies', 403, 'unauthorized', role='merchant')
    # 门店清单：只列启用门店。
    add('merchants_list', 'GET', admin+'/merchants', 200, 'normal', role='assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.map(i=>i.id)).to.eql([2,1]);pm.expect(Object.keys(d.items[0]).sort()).to.eql(["address","id","name"]);'])
    add('merchants_last_page', 'GET', admin+'/merchants?page=1000000', 200, 'boundary', role='assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.items).to.eql([]);pm.expect(d.total).to.eql(2);'])
    add('merchants_page_zero', 'GET', admin+'/merchants?page=0', 400, 'error', role='assessor')
    add('merchants_anonymous', 'GET', admin+'/merchants', 401, 'unauthorized', role='anonymous')
    add('merchants_analyst_denied', 'GET', admin+'/merchants', 403, 'unauthorized', role='analyst')
    # 发布前：门店 2 没有已发布政策，不得伪造状态。
    add('merchant_policy_absent', 'GET', mine+'/policy', 200, 'boundary', role='other_merchant', extra=[
        'const d=pm.response.json().data;pm.expect(d.state).to.eql("UNKNOWN");pm.expect(d.policy).to.eql(null);pm.expect(d.scoring_available).to.eql(true);pm.expect(d.timezone).to.eql("Asia/Shanghai");'])
    # 政策写入链：创造、修订、发布（窗口必须未开始，因此压在门站 2 的未来月份）。
    write('policy_create', admin+'/policies', POLICY, 'assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.id).to.eql(2);pm.expect(d.merchant_id).to.eql(2);pm.expect(d.status).to.eql("DRAFT");pm.expect(d.revision).to.eql(1);pm.expect(d.published_at).to.eql(null);',
        'const d=pm.response.json().data;pm.expect(d.merchant).to.eql({id:2,name:"synthetic other shop",address:"synthetic address"});pm.expect(d.source_note).to.eql("合成考核政策");pm.expect(d.process_requirements).to.eql(["CHECK_IN_CONFIRMED","REPORT_SUBMITTED"]);pm.expect(d.grade_thresholds[0].code).to.eql("A");'])
    add('policy_create_changed_body', 'POST', admin+'/policies', 400, 'error', dict(POLICY, source_note='同键改正文'), 'assessor', key=replay_key('policy_create'))
    add('policy_create_past_window', 'POST', admin+'/policies', 409, 'boundary',
        dict(POLICY, valid_from=CLOSED_FROM, valid_to=CLOSED_TO), 'assessor')
    add('policy_create_zero_target', 'POST', admin+'/policies', 400, 'error', dict(POLICY, acquisition_target=0), 'assessor')
    add('policy_create_duplicate_requirement', 'POST', admin+'/policies', 400, 'error',
        dict(POLICY, process_requirements=['REPORT_SUBMITTED', 'REPORT_SUBMITTED']), 'assessor')
    add('policy_create_grade_not_zero', 'POST', admin+'/policies', 400, 'error',
        dict(POLICY, grade_thresholds=[{'code': 'A', 'label': '优秀', 'min_score': '90.00'},
                                       {'code': 'B', 'label': '合格', 'min_score': '10.00'}]), 'assessor')
    add('policy_create_grade_not_declining', 'POST', admin+'/policies', 400, 'error',
        dict(POLICY, grade_thresholds=[{'code': 'A', 'label': '优秀', 'min_score': '10.00'},
                                       {'code': 'B', 'label': '合格', 'min_score': '20.00'}]), 'assessor')
    add('policy_create_mid_month', 'POST', admin+'/policies', 400, 'error',
        dict(POLICY, valid_from='2999-01-15T16:00:00Z', valid_to='2999-03-31T16:00:00Z'), 'assessor')
    add('policy_create_unknown_field', 'POST', admin+'/policies', 400, 'error', dict(POLICY, merchant_name='x'), 'assessor')
    add('policy_create_query', 'POST', admin+'/policies?merchant_id=2', 400, 'error', POLICY, 'assessor')
    add('policy_create_unknown_merchant', 'POST', admin+'/policies', 404, 'error', dict(POLICY, merchant_id=999), 'assessor')
    add('policy_create_analyst_denied', 'POST', admin+'/policies', 403, 'unauthorized', POLICY, 'analyst')
    write('policy_update', admin+'/policies/2', dict(POLICY, source_note='合成考核政策（修订）', expected_revision=1), 'assessor',
          method='PUT', extra=['const d=pm.response.json().data;pm.expect(d.revision).to.eql(2);pm.expect(d.status).to.eql("DRAFT");pm.expect(d.source_note).to.eql("合成考核政策（修订）");'])
    add('policy_update_stale', 'PUT', admin+'/policies/2', 409, 'boundary',
        dict(POLICY, source_note='合成考核政策（旧修订）', expected_revision=1), 'assessor')
    add('policy_update_unknown', 'PUT', admin+'/policies/999', 404, 'boundary', dict(POLICY, expected_revision=1), 'assessor')
    add('policy_update_zero_id', 'PUT', admin+'/policies/0', 400, 'error', dict(POLICY, expected_revision=1), 'assessor')
    write('policy_publish', admin+'/policies/2/status', {'expected_revision': 2, 'status': 'PUBLISHED'}, 'assessor',
          method='PUT', extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("PUBLISHED");pm.expect(d.revision).to.eql(3);pm.expect(d.published_at).to.match('+ISO+');'])
    add('policy_publish_again', 'PUT', admin+'/policies/2/status', 409, 'boundary', {'expected_revision': 3, 'status': 'PUBLISHED'}, 'assessor')
    add('policy_status_draft', 'PUT', admin+'/policies/2/status', 400, 'error', {'expected_revision': 3, 'status': 'DRAFT'}, 'assessor')
    add('policy_publish_unknown', 'PUT', admin+'/policies/999/status', 404, 'boundary', {'expected_revision': 1, 'status': 'PUBLISHED'}, 'assessor')
    # 发布后的门店侧读：窗口未开始。
    add('merchant_policy_not_started', 'GET', mine+'/policy', 200, 'normal', role='other_merchant', extra=[
        'const d=pm.response.json().data;pm.expect(d.state).to.eql("NOT_STARTED");pm.expect(d.policy.id).to.eql(2);pm.expect(d.policy.status).to.eql("PUBLISHED");pm.expect(d.policy.acquisition_target).to.eql(10);'])
    read('merchant_policy', mine+'/policy', role='merchant', extra=[
        'const d=pm.response.json().data;pm.expect(d.state).to.eql("CONFIGURED");pm.expect(d.policy.id).to.eql(1);pm.expect(d.policy.merchant.id).to.eql(1);pm.expect(d.scoring_available).to.eql(true);pm.expect(d.policy.source_note).to.eql("合成历史考核政策");'],
        error=(mine+'/policy?merchant_id=1', 400))
    add('merchant_policy_staff_denied', 'GET', mine+'/policy', 403, 'unauthorized', role='staff')
    # 运营侧月份上下文：结算前 revision=0。
    add('admin_month_ready', 'GET', admin+'/month'+closed+'&merchant_id=1', 200, 'normal', role='assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.state).to.eql("READY");pm.expect(d.can_settle).to.eql(true);pm.expect(d.revision).to.eql(0);pm.expect(d.latest).to.eql(null);pm.expect(d.policy.id).to.eql(1);pm.expect(d.period_month).to.eql("2024-01");pm.expect(d.timezone).to.eql("Asia/Shanghai");',
        'const d=pm.response.json().data;pm.expect(d.month_end).to.eql("2024-01-31T16:00:00Z");pm.expect(d.merchant).to.eql({id:1,name:"synthetic shop",address:"synthetic address"});pm.expect(d.server_now).to.match('+ISO+');'])
    add('admin_month_open', 'GET', admin+'/month'+future+'&merchant_id=2', 200, 'boundary', role='assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.state).to.eql("MONTH_OPEN");pm.expect(d.can_settle).to.eql(false);pm.expect(d.revision).to.eql(0);pm.expect(d.policy.id).to.eql(2);pm.expect(d.month_end).to.eql("2999-01-31T16:00:00Z");'])
    add('admin_month_missing_month', 'GET', admin+'/month?merchant_id=1', 400, 'error', role='assessor')
    add('admin_month_missing_merchant', 'GET', admin+'/month'+closed, 400, 'error', role='assessor')
    add('admin_month_bad_month', 'GET', admin+'/month?period_month=2024-13&merchant_id=1', 400, 'error', role='assessor')
    add('admin_month_unknown_merchant', 'GET', admin+'/month'+closed+'&merchant_id=999', 404, 'error', role='assessor')
    add('admin_month_anonymous', 'GET', admin+'/month'+closed+'&merchant_id=1', 401, 'unauthorized', role='anonymous')
    add('admin_month_analyst_denied', 'GET', admin+'/month'+closed+'&merchant_id=1', 403, 'unauthorized', role='analyst')
    # 月结：证据不全时结论必须是 UNKNOWN，不能给出看起来正常的分数。
    settle = {'merchant_id': 1, 'period_month': CLOSED, 'expected_revision': 0}
    write('settle', snapshots, settle, 'assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.id).to.eql(1);pm.expect(d.revision).to.eql(1);pm.expect(d.merchant_id).to.eql(1);pm.expect(d.period_month).to.eql("2024-01");pm.expect(d.calculation_version).to.eql("R8B_CALC_V1");pm.expect(d.timezone).to.eql("Asia/Shanghai");pm.expect(d.facts_hash).to.match(/^[a-f0-9]{64}$/);',
        'const s=pm.response.json().data.summary;pm.expect(s.state).to.eql("UNKNOWN");pm.expect(s.total_score).to.eql(null);pm.expect(s.grade).to.eql(null);pm.expect(s.fact_count).to.eql(2);',
        'const m=pm.response.json().data.summary.metrics;pm.expect(m.acquisition).to.eql({numerator:0,denominator:10,state:"KNOWN",score:"0.00",reason:"VERIFIED"});pm.expect(m.coupon.state).to.eql("KNOWN");pm.expect(m.coupon.score).to.eql("0.00");pm.expect(m.coupon.denominator).to.eql(10);',
        'const m=pm.response.json().data.summary.metrics;pm.expect(m.process).to.eql({numerator:0,denominator:1,state:"UNKNOWN",score:null,reason:"SOURCE_UNKNOWN"});',
        'const d=pm.response.json().data;pm.expect(d.policy.id).to.eql(1);pm.expect(d.policy.acquisition_target).to.eql(10);pm.expect(d.captured_from).to.match('+ISO+');pm.expect(d.captured_to).to.match('+ISO+');'])
    add('settle_stale_revision', 'POST', snapshots, 409, 'boundary', settle, 'assessor')
    add('settle_changed_body', 'POST', snapshots, 400, 'error',
        {'merchant_id': 1, 'period_month': CLOSED, 'expected_revision': 2}, 'assessor', key=replay_key('settle'))
    add('settle_future_month', 'POST', snapshots, 409, 'boundary',
        {'merchant_id': 1, 'period_month': FUTURE, 'expected_revision': 0}, 'assessor')
    add('settle_unknown_merchant', 'POST', snapshots, 404, 'error',
        {'merchant_id': 999, 'period_month': CLOSED, 'expected_revision': 0}, 'assessor')
    write('settle_again', snapshots, {'merchant_id': 1, 'period_month': CLOSED, 'expected_revision': 1}, 'assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.id).to.eql(2);pm.expect(d.revision).to.eql(2);pm.expect(d.summary.state).to.eql("UNKNOWN");pm.expect(d.summary.fact_count).to.eql(2);'])
    write('settle_other_shop', snapshots, {'merchant_id': 2, 'period_month': CLOSED, 'expected_revision': 0}, 'assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.id).to.eql(3);pm.expect(d.merchant_id).to.eql(2);pm.expect(d.revision).to.eql(1);pm.expect(d.policy).to.eql(null);',
        'const s=pm.response.json().data.summary;pm.expect(s.state).to.eql("UNKNOWN");pm.expect(s.fact_count).to.eql(0);pm.expect(s.total_score).to.eql(null);pm.expect(s.metrics.acquisition).to.eql({numerator:0,denominator:0,state:"UNKNOWN",score:null,reason:"POLICY_UNKNOWN"});'])
    read('admin_snapshots', snapshots, role='assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(3);pm.expect(d.items.map(i=>i.id)).to.eql([3,2,1]);pm.expect(d.items[1].revision).to.eql(2);pm.expect(d.items[2].revision).to.eql(1);'],
        boundary=(snapshots+'?page=2', 200), error=(snapshots+'?page=0', 400))
    read('admin_snapshot', snapshots+'/2', role='assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.revision).to.eql(2);pm.expect(d.period_month).to.eql("2024-01");pm.expect(d.summary.state).to.eql("UNKNOWN");pm.expect(d.policy.id).to.eql(1);pm.expect(d.timezone).to.eql("Asia/Shanghai");'],
        boundary=(snapshots+'/999999', 404), error=(snapshots+'/0', 400))
    read('admin_snapshot_facts', snapshots+'/2/facts', role='assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.length).to.eql(2);',
        'const i=pm.response.json().data.items[0];pm.expect(i.dimension).to.eql("PROCESS");pm.expect(i.source_type).to.eql("ORDER");pm.expect(i.source_id).to.eql("100");pm.expect(i.state).to.eql("EXCLUDED");pm.expect(i.reason).to.eql("TEST_SOURCE");pm.expect(i.evidence).to.eql({});pm.expect(i.proof_hash).to.match(/^[a-f0-9]{64}$/);pm.expect(Object.keys(i).sort()).to.eql(["dimension","evidence","occurred_at","proof_hash","reason","source_id","source_type","staff_id","state"]);',
        'const i=pm.response.json().data.items[1];pm.expect(i.source_type).to.eql("UNPROVEN_ORDER");pm.expect(i.source_id).to.eql("101");pm.expect(i.state).to.eql("UNKNOWN");pm.expect(i.reason).to.eql("COMPLETION_RECEIPT_MISSING");pm.expect(i.occurred_at).to.eql(null);pm.expect(i.staff_id).to.eql(null);',
        'const t=pm.response.text();pm.expect(t).not.to.include("SYNTH-ASSESS-000100");pm.expect(t).not.to.include("SYN-ASSESS");pm.expect(t).not.to.include("1380000");'],
        boundary=(snapshots+'/2/facts?page=2', 200), error=(snapshots+'/2/facts?page=0', 400))
    read('admin_history', admin+'/history'+closed+'&merchant_id=1', role='assessor', extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.map(i=>i.revision)).to.eql([2,1]);'],
        boundary=(admin+'/history'+closed+'&merchant_id=1&page=2', 200),
        error=(admin+'/history'+closed+'&merchant_id=1&page_size=10', 400))
    # 门店侧读：只能看本店，结算动作永远不可用。
    read('merchant_month', mine+'/month'+closed, role='merchant', extra=[
        'const d=pm.response.json().data;pm.expect(d.state).to.eql("READY");pm.expect(d.can_settle).to.eql(false);pm.expect(d.revision).to.eql(2);pm.expect(d.latest.revision).to.eql(2);pm.expect(d.policy.id).to.eql(1);pm.expect(d.month_end).to.eql("2024-01-31T16:00:00Z");'],
        boundary=(mine+'/month'+future, 200), error=(mine+'/month', 400))
    add('merchant_month_staff_denied', 'GET', mine+'/month'+closed, 403, 'unauthorized', role='staff')
    read('merchant_history', mine+'/history'+closed, role='merchant', extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.map(i=>i.revision)).to.eql([2,1]);'],
        boundary=(mine+'/history'+closed+'&page=2', 200), error=(mine+'/history'+closed+'&page=0', 400))
    add('merchant_history_staff_denied', 'GET', mine+'/history'+closed, 403, 'unauthorized', role='staff')
    read('merchant_snapshot', mine+'/snapshots/2', role='merchant', extra=[
        'const d=pm.response.json().data;pm.expect(d.id).to.eql(2);pm.expect(d.revision).to.eql(2);pm.expect(d.merchant_id).to.eql(1);pm.expect(d.summary.state).to.eql("UNKNOWN");'],
        boundary=(mine+'/snapshots/3', 404), error=(mine+'/snapshots/0', 400))
    add('merchant_snapshot_staff_denied', 'GET', mine+'/snapshots/2', 403, 'unauthorized', role='staff')
    read('merchant_snapshot_facts', mine+'/snapshots/2/facts', role='merchant', extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.map(i=>i.source_type)).to.eql(["ORDER","UNPROVEN_ORDER"]);'],
        boundary=(mine+'/snapshots/2/facts?page=2', 200), error=(mine+'/snapshots/2/facts?page=0', 400))
    add('merchant_facts_staff_denied', 'GET', mine+'/snapshots/2/facts', 403, 'unauthorized', role='staff')
    # 员工考核：读的是冻结快照，不是实时重算。
    add('merchant_staff_frozen', 'GET', mine+'/staff'+closed, 200, 'normal', role='merchant', extra=[
        'const d=pm.response.json().data;pm.expect(d.state).to.eql("FROZEN");pm.expect(d.role).to.eql("MERCHANT");pm.expect(d.staff_id).to.eql(1);pm.expect(d.merchant_id).to.eql(1);pm.expect(d.period_month).to.eql("2024-01");pm.expect(d.revision).to.eql(2);',
        'const d=pm.response.json().data;pm.expect(d.attribution).to.eql({included:0,excluded:0,unknown:0});pm.expect(d.captured_from).to.match('+ISO+');pm.expect(d.captured_to).to.match('+ISO+');'])
    add('merchant_staff_self', 'GET', mine+'/staff'+closed, 200, 'normal', role='staff', extra=[
        'const d=pm.response.json().data;pm.expect(d.role).to.eql("STAFF");pm.expect(d.staff_id).to.eql(4);pm.expect(d.state).to.eql("FROZEN");pm.expect(d.attribution.included).to.eql(0);'])
    add('merchant_staff_no_snapshot', 'GET', mine+'/staff?period_month='+ABSENT, 200, 'boundary', role='merchant', extra=[
        'const d=pm.response.json().data;pm.expect(d.state).to.eql("NO_SNAPSHOT");pm.expect(d.revision).to.eql(0);pm.expect(d.captured_from).to.eql(null);pm.expect(d.captured_to).to.eql(null);pm.expect(d.attribution).to.eql({included:0,excluded:0,unknown:0});'])
    add('merchant_staff_missing_month', 'GET', mine+'/staff', 400, 'error', role='merchant')
    add('merchant_staff_merchant_param', 'GET', mine+'/staff'+closed+'&merchant_id=1', 400, 'error', role='merchant')
    add('merchant_staff_owner_denied', 'GET', mine+'/staff'+closed, 403, 'unauthorized', role='owner')
    add('merchant_staff_anonymous', 'GET', mine+'/staff'+closed, 401, 'unauthorized', role='anonymous')
    # 经营看板：分桶不计猜测，金额与未分类同时给出。
    read('business_merchants', biz+'/merchants', role='analyst', extra=[
        'const d=pm.response.json().data;pm.expect(d.total).to.eql(2);pm.expect(d.items.map(i=>i.id)).to.eql([2,1]);pm.expect(Object.keys(d.items[0]).sort()).to.eql(["address","id","name"]);'],
        boundary=(biz+'/merchants?page=2', 200), error=(biz+'/merchants?page=0', 400))
    add('business_merchants_assessor_denied', 'GET', biz+'/merchants', 403, 'unauthorized', role='assessor')
    dashboard = biz+'/dashboard'+closed+'&merchant_id=1'
    add('admin_dashboard', 'GET', dashboard, 200, 'normal', role='analyst', extra=[
        'const d=pm.response.json().data;pm.expect(d.period_month).to.eql("2024-01");pm.expect(d.currency).to.eql("CNY");pm.expect(d.timezone).to.eql("Asia/Shanghai");pm.expect(d.source_version).to.eql("R8B_BUSINESS_V1");pm.expect(d.window_from).to.eql("2023-12-31T16:00:00Z");pm.expect(d.window_to).to.eql("2024-01-31T16:00:00Z");pm.expect(d.merchant).to.eql({id:1,name:"synthetic shop",address:"synthetic address"});',
        'const d=pm.response.json().data;pm.expect(Object.keys(d.metrics).sort()).to.eql(["cash_coupon_uses","commission_accrual","new_customers","orders_completed","orders_created","payments","refunds","service_coupon_uses"]);',
        'const m=pm.response.json().data.metrics;pm.expect(m.payments.test).to.eql({state:"KNOWN",count:1,unknown_count:0,excluded_count:0,amount:"10.00"});pm.expect(m.payments.formal).to.eql({state:"KNOWN",count:0,unknown_count:0,excluded_count:0,amount:"0.00"});pm.expect(m.payments.unclassified_count).to.eql(0);',
        'const m=pm.response.json().data.metrics;pm.expect(m.orders_created.unclassified_count).to.eql(2);pm.expect(m.orders_created.formal.state).to.eql("UNKNOWN");pm.expect(m.orders_created.test.state).to.eql("UNKNOWN");',
        'const m=pm.response.json().data.metrics;pm.expect(m.orders_completed.test).to.eql({state:"UNKNOWN",count:1,unknown_count:0,excluded_count:0,amount:null});pm.expect(m.orders_completed.unclassified_count).to.eql(1);pm.expect(m.commission_accrual.test.unknown_count).to.eql(1);',
        'const d=pm.response.json().data;pm.expect(d.metrics.cash_coupon_uses.test).to.eql({state:"KNOWN",count:0,unknown_count:0,excluded_count:0,amount:null});pm.expect(d.metrics.service_coupon_uses.formal.count).to.eql(0);pm.expect(d.metrics.new_customers.formal.count).to.eql(0);',
        'const d=pm.response.json().data;pm.expect(d.cash_difference.test.amount).to.eql("10.00");pm.expect(d.cash_difference.formal.amount).to.eql("0.00");pm.expect(d.cash_difference.test.state).to.eql("KNOWN");',
        'const a=pm.response.json().data.assessment;pm.expect(a.snapshot_id).to.eql(2);pm.expect(a.revision).to.eql(2);pm.expect(a.state).to.eql("UNKNOWN");pm.expect(a.total_score).to.eql(null);pm.expect(a.grade).to.eql(null);'])
    add('admin_dashboard_no_sources', 'GET', biz+'/dashboard'+closed+'&merchant_id=2', 200, 'boundary', role='analyst', extra=[
        'const d=pm.response.json().data;pm.expect(d.metrics.payments.test).to.eql({state:"KNOWN",count:0,unknown_count:0,excluded_count:0,amount:"0.00"});pm.expect(d.metrics.orders_created.unclassified_count).to.eql(0);pm.expect(d.cash_difference.test.amount).to.eql("0.00");',
        'const a=pm.response.json().data.assessment;pm.expect(a.snapshot_id).to.eql(3);pm.expect(a.revision).to.eql(1);pm.expect(a.state).to.eql("UNKNOWN");pm.expect(a.total_score).to.eql(null);pm.expect(a.grade).to.eql(null);'])
    add('admin_dashboard_future', 'GET', biz+'/dashboard'+future+'&merchant_id=1', 409, 'error', role='analyst')
    add('admin_dashboard_missing_merchant', 'GET', biz+'/dashboard'+closed, 400, 'error', role='analyst')
    add('admin_dashboard_assessor_denied', 'GET', dashboard, 403, 'unauthorized', role='assessor')
    add('admin_dashboard_anonymous', 'GET', dashboard, 401, 'unauthorized', role='anonymous')
    merchant_dashboard = '/api/merchant/business/dashboard'+closed
    add('merchant_dashboard', 'GET', merchant_dashboard, 200, 'normal', role='merchant', extra=[
        'const d=pm.response.json().data;pm.expect(d.merchant.id).to.eql(1);pm.expect(d.window_from).to.eql("2023-12-31T16:00:00Z");pm.expect(d.metrics.payments.test.amount).to.eql("10.00");pm.expect(d.cash_difference.test.amount).to.eql("10.00");pm.expect(d.assessment.revision).to.eql(2);'])
    add('merchant_dashboard_no_sources', 'GET', merchant_dashboard, 200, 'boundary', role='other_merchant', extra=[
        'const d=pm.response.json().data;pm.expect(d.merchant.id).to.eql(2);pm.expect(d.metrics.orders_completed.formal.count).to.eql(0);pm.expect(d.metrics.new_customers.test.count).to.eql(0);',
        'const a=pm.response.json().data.assessment;pm.expect(a.snapshot_id).to.eql(3);pm.expect(a.state).to.eql("UNKNOWN");pm.expect(a.total_score).to.eql(null);'])
    add('merchant_dashboard_merchant_param', 'GET', merchant_dashboard+'&merchant_id=1', 400, 'error', role='merchant')
    add('merchant_dashboard_staff_denied', 'GET', merchant_dashboard, 403, 'unauthorized', role='staff')
    add('merchant_dashboard_owner_denied', 'GET', merchant_dashboard, 403, 'unauthorized', role='owner')
    add('merchant_dashboard_anonymous', 'GET', merchant_dashboard, 401, 'unauthorized', role='anonymous')
    # 停止发布后，门店侧不再看到政策。
    write('policy_stop', admin+'/policies/2/status', {'expected_revision': 3, 'status': 'STOPPED'}, 'assessor',
          method='PUT', extra=['const d=pm.response.json().data;pm.expect(d.status).to.eql("STOPPED");pm.expect(d.revision).to.eql(4);'])
    add('policy_stop_again', 'PUT', admin+'/policies/2/status', 409, 'boundary', {'expected_revision': 4, 'status': 'STOPPED'}, 'assessor')
    add('merchant_policy_stopped', 'GET', mine+'/policy', 200, 'boundary', role='other_merchant', extra=[
        'const d=pm.response.json().data;pm.expect(d.state).to.eql("UNKNOWN");pm.expect(d.policy).to.eql(null);'])
    return c.items


def assets():
    return assemble(cases(), 'Autocare assessment policy monthly settlement and business dashboard',
                    'isolated_assessment_cases', OPERATION_COUNT)


def seed(mysql, secret):
    tokens = identity_seed(mysql, secret)
    mysql.sql("""INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(4,1,'STAFF','assessment-staff','ACTIVE');
INSERT INTO `order`(id,order_no,user_id,vehicle_id,merchant_id,project_id,amount,pay_amount,status,created_at) VALUES
(100,'SYNTH-ASSESS-000100',1,1,1,1,10.00,10.00,'COMPLETED','2024-01-05 02:00:00'),
(101,'SYNTH-ASSESS-000101',1,1,1,1,20.00,20.00,'COMPLETED','2024-01-10 02:00:00');
INSERT INTO payment(id,order_id,channel,payment_no,channel_payment_no,amount,currency,status,paid_at,created_at) VALUES
(100,100,'LOCAL_TEST','SYNTHASPAY100','SYNTHASLOCAL100',10.00,'CNY','SUCCEEDED','2024-01-05 03:00:00','2024-01-05 02:30:00');
INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES
('LOCAL_TEST','66666666-6666-4666-8666-666666666666',100,REPEAT('f',64),'PAID');
INSERT INTO order_redemption(order_id,merchant_id,staff_id,payment_id,test_mode,redeemed_at) VALUES
(100,1,1,100,1,'2024-01-05 04:00:00');
INSERT INTO order_status_transition(order_id,merchant_id,from_status,to_status,action,actor_type,actor_id,occurred_at) VALUES
(100,1,'PENDING_VERIFY','COMPLETED','ORDER_COMPLETE','staff_account',1,'2024-01-05 04:00:00');""")
    mysql.sql("""INSERT INTO assessment_policy(id,merchant_id,merchant_snapshot,acquisition_target,coupon_target,
 process_requirements,grade_thresholds,source_note,valid_from,valid_to,revision,status,rule_version,created_by,
 submission_key,submission_hash,created_at,published_at) VALUES
(1,1,'{"id":1,"name":"synthetic shop","address":"synthetic address"}',10,10,
 '["CHECK_IN_CONFIRMED","PROTECTION_COMPLETE","REPORT_SUBMITTED","QUALITY_SIGNED"]',
 '[{"code":"A","label":"优秀","min_score":"90.00"},{"code":"B","label":"合格","min_score":"0.00"}]',
 '合成历史考核政策','2023-12-31 16:00:00','2998-12-31 16:00:00',1,'PUBLISHED','R8B_POLICY_V1',1,
 '7a1d0f36-0000-4000-8000-000000000001',REPEAT('a',64),'2023-12-20 00:00:00','2023-12-20 00:00:00');""")
    mysql.sql("""INSERT INTO operator_account(id,account,password_hash,phone,status,can_assessment,can_analytics) VALUES
(1,'synthetic-assessment-assessor','not-a-login-password','13900000011','ACTIVE',1,0),
(2,'synthetic-assessment-analyst','not-a-login-password','13900000012','ACTIVE',0,1);""")
    def sign(subject, role, kind, app, shop=None):
        session = str(uuid.uuid4())
        binding = 'NULL' if shop is None else str(shop)
        mysql.sql(f"INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,binding_id,refresh_hash,expires_at) VALUES('{session}','{kind}',{subject},'{role}','{app}',{binding},NULL,'{hashlib.sha256(session.encode()).hexdigest()}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR));")
        claims = {'iss': 'vehicle-health-manager', 'sub': str(subject), 'iat': int(time.time()), 'exp': int(time.time())+3600,
                  'jti': session, 'subject_type': kind, 'role': role, 'app_id': app}
        if shop: claims['merchant_id'] = shop
        encode = lambda x: base64.urlsafe_b64encode(json.dumps(x, separators=(',', ':')).encode()).rstrip(b'=').decode()
        raw = encode({'alg': 'HS256', 'typ': 'JWT'})+'.'+encode(claims)
        return raw+'.'+base64.urlsafe_b64encode(hmac.new(secret.encode(), raw.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()
    for role, subject in [('assessor', 1), ('analyst', 2)]:
        tokens[role+'_token'] = sign(subject, 'OPERATOR', 'operator_account', 'operator-account')
    # 店员身份只用于证明"本人可读、店长专属接口一律 403"。
    tokens['staff_token'] = sign(4, 'STAFF', 'staff_account', 'merchant-account', 1)
    for key in ('isolated_fulfillment_cases', 'slot_start', 'slot_end'):
        tokens.pop(key, None)
    tokens['isolated_assessment_cases'] = 'true'
    return tokens


def environment(tokens):
    # 本套件不依赖任何生产开关；身份表与运营权限都在种子与令牌里。
    return [('WECHAT_APP_ID', 'fulfillment-cases')]


def audit(path, collection, manifest, tokens):
    from run_api_acceptance import audit_owner_cases
    result = audit_owner_cases(path, collection, manifest, tokens, expected_operations=OPERATION_COUNT)
    report = json.loads(path.read_text(encoding='utf-8'))
    definitions = {x['name']: x for x in manifest['items']}
    # 每条写用例必须带唯一幂等键；复用同一键的只能是"重放"与"同键异体"这两种声明过的用例。
    keys = {}
    for execution in report['run']['executions']:
        case = definitions[execution['item']['name']]
        if case['method'] not in ('POST', 'PUT') or case['role'] == 'anonymous':
            continue
        actual = [h['value'] for h in execution['request'].get('header', [])
                  if h['key'].lower() == 'idempotency-key' and not h.get('disabled')]
        if len(actual) != 1 or not re.fullmatch(r'[0-9a-f-]{36}', actual[0]):
            raise ValueError('Assessment write case sent no single idempotency key: '+case['name'])
        keys.setdefault(actual[0], []).append((case['name'], case['operation_id']))
    shared = {key: names for key, names in keys.items() if len(names) > 1}
    if len(shared) != 7:
        raise ValueError('Assessment writes must declare seven idempotent scopes')
    changed = 0
    for names in shared.values():
        operations = {operation for _, operation in names}
        main = [name for name, _ in names if not name.endswith(('_replay', '_changed_body'))]
        replay = [name for name, _ in names if name.endswith('_replay')]
        altered = [name for name, _ in names if name.endswith('_changed_body')]
        if len(operations) != 1 or len(main) != 1 or replay != [main[0]+'_replay'] or len(altered) > 1:
            raise ValueError('Assessment idempotency grouping differs from declarations: '+str(names))
        changed += len(altered)
    if changed != 2:
        raise ValueError('Assessment same-key different-body coverage is incomplete')
    result['idempotent_replay_scopes_verified'] = len(shared)
    result['idempotency_changed_body_verified'] = changed
    return result


def verify_database(mysql):
    tables = {'policies': 'assessment_policy', 'months': 'assessment_month', 'snapshots': 'assessment_snapshot',
              'facts': 'assessment_snapshot_fact', 'idempotency': 'idempotency_record', 'audits': 'audit_log'}
    actual = {key: int(mysql.text('SELECT COUNT(*) FROM `'+table+'`;')) for key, table in tables.items()}
    expected = dict(policies=2, months=2, snapshots=3, facts=4, idempotency=7, audits=7)
    if actual != expected:
        raise ValueError('Assessment policy/settlement rows are missing or duplicated')
    checks = {
        'policy_states': mysql.text("SELECT GROUP_CONCAT(CONCAT(id,':',status,':',revision) ORDER BY id) FROM assessment_policy;") == '1:PUBLISHED:1,2:STOPPED:4',
        'one_published_per_shop': mysql.text("SELECT COUNT(*) FROM (SELECT merchant_id FROM assessment_policy WHERE status='PUBLISHED' GROUP BY merchant_id HAVING COUNT(*)>1) x;") == '0',
        'seeded_policy_covers_closed_month': mysql.text("SELECT COUNT(*) FROM assessment_policy WHERE id=1 AND rule_version='R8B_POLICY_V1' AND status='PUBLISHED' AND stopped_at IS NULL AND valid_from<='2023-12-31 16:00:00' AND valid_to>='2024-01-31 16:00:00' AND published_at<='2023-12-31 16:00:00';") == '1',
        'month_heads': mysql.text("SELECT GROUP_CONCAT(CONCAT(merchant_id,':',period_month,':',revision) ORDER BY merchant_id,period_month) FROM assessment_month;") == '1:2024-01:2,2:2024-01:1',
        'only_closed_month_settled': mysql.text("SELECT COUNT(*) FROM assessment_snapshot WHERE period_month<>'2024-01';") == '0',
        'snapshot_revisions': mysql.text('SELECT GROUP_CONCAT(revision ORDER BY id) FROM assessment_snapshot;') == '1,2,1',
        'unknown_summary_never_scored': mysql.text("SELECT COUNT(*) FROM assessment_snapshot WHERE JSON_UNQUOTE(JSON_EXTRACT(summary,'$.state'))='UNKNOWN' AND JSON_TYPE(JSON_EXTRACT(summary,'$.total_score'))='NULL' AND JSON_TYPE(JSON_EXTRACT(summary,'$.grade'))='NULL';") == '3',
        'policy_less_settlement_kept_null_policy': mysql.text("SELECT COUNT(*) FROM assessment_snapshot WHERE id=3 AND JSON_TYPE(policy_snapshot)='NULL' AND JSON_UNQUOTE(JSON_EXTRACT(summary,'$.metrics.process.reason'))='POLICY_UNKNOWN';") == '1',
        'facts_are_minimal': mysql.text('SELECT COUNT(*) FROM assessment_snapshot_fact WHERE JSON_LENGTH(fact_json)<>9;') == '0',
        'facts_never_audited': mysql.text("SELECT COUNT(*) FROM audit_log WHERE after_state LIKE '%proof_hash%' OR before_state LIKE '%proof_hash%';") == '0',
        'audit_actions': mysql.text("SELECT GROUP_CONCAT(CONCAT(action,':',n) ORDER BY action) FROM (SELECT action,COUNT(*) n FROM audit_log GROUP BY action) counts;") == 'ASSESSMENT_MONTH_SETTLE:3,ASSESSMENT_POLICY_CREATE:1,ASSESSMENT_POLICY_STATUS:2,ASSESSMENT_POLICY_UPDATE:1',
        'no_foreign_settlement': mysql.text('SELECT COUNT(*) FROM assessment_snapshot WHERE merchant_id NOT IN (1,2);') == '0',
        'failed_settlements_left_no_head': mysql.text("SELECT COUNT(*) FROM assessment_month WHERE period_month<>'2024-01';") == '0',
    }
    if not all(checks.values()):
        raise ValueError('Assessment policy/settlement/scoring invariant failed')
    return {'row_counts': actual, 'checks': checks,
            'scope': 'synthetic identity, order, payment and historical policy fixtures; every policy and settlement write via HTTP'}


if __name__ == '__main__':
    collection, manifest = assets()
    for path, data in [(OUTPUT, collection), (MANIFEST, manifest)]:
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print('Assessment API cases:', manifest['cases'], 'assertions:', sum(x['assertions'] for x in manifest['items']))
