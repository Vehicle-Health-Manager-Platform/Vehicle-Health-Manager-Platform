"""Read-only source-verified business dashboard; no financial channel actions."""

def add_r8b3a(d, operation):
    s = d['components']['schemas']
    ref = lambda n: {'$ref': '#/components/schemas/' + n}
    strict = lambda p: {'type': 'object', 'additionalProperties': False, 'required': list(p), 'properties': p}
    nullable = lambda v: {'anyOf': [v, {'type': 'null'}]}
    count = {'type': 'integer', 'minimum': 0, 'maximum': 1000000}
    ident = {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740990}
    stamp = {'type': 'string', 'format': 'date-time'}
    month = {'type': 'string', 'pattern': '^[1-9][0-9]{3}-(?:0[1-9]|1[0-2])$'}
    money = {'type': 'string', 'pattern': '^-?(?:0|[1-9][0-9]{0,23})\\.[0-9]{2}$', 'description': '精确两位小数字符串；不允许负零。收款/退款非负，应计冲销和收退款差额可负。'}
    s['OperatorAuthenticatedUser']['properties']['can_analytics'] = {'type': 'boolean'}
    s['OperatorAuthenticatedUser']['required'].append('can_analytics')
    s['BusinessBucket'] = strict({'state': {'enum': ['KNOWN', 'UNKNOWN']}, 'count': count, 'unknown_count': count, 'excluded_count': count, 'amount': nullable(money)})
    s['BusinessMetric'] = strict({'formal': ref('BusinessBucket'), 'test': ref('BusinessBucket'), 'unclassified_count': count})
    s['BusinessDifference'] = strict({'state': {'enum': ['KNOWN', 'UNKNOWN']}, 'amount': nullable(money)})
    s['BusinessFrozenAssessment'] = strict({'snapshot_id': ident, 'revision': ident, 'state': {'enum': ['CALCULATED', 'UNKNOWN']}, 'total_score': nullable({'type': 'string', 'pattern': '^(?:100\\.00|(?:0|[1-9][0-9]?)\\.[0-9]{2})$', 'description': 'CALCULATED 时为 0.00–100.00；UNKNOWN 为 null。'}), 'grade': nullable(strict({'code': {'type': 'string', 'pattern': '^[A-Z][A-Z0-9_]{0,15}$'}, 'label': {'type': 'string', 'minLength': 1, 'maxLength': 32}})), 'captured_from': stamp, 'captured_to': stamp})
    s['BusinessDashboard'] = strict({'merchant': ref('AssessmentMerchant'), 'period_month': month, 'timezone': {'const': 'Asia/Shanghai'}, 'window_from': stamp, 'window_to': stamp, 'observed_at': stamp, 'currency': {'const': 'CNY'}, 'source_version': {'const': 'R8B_BUSINESS_V1'}, 'metrics': strict({k: ref('BusinessMetric') for k in ['orders_created', 'payments', 'refunds', 'orders_completed', 'cash_coupon_uses', 'service_coupon_uses', 'new_customers', 'commission_accrual']}), 'cash_difference': strict({k: ref('BusinessDifference') for k in ['formal', 'test']}), 'assessment': nullable(ref('BusinessFrozenAssessment'))})
    routes = [('/api/admin/business/merchants', 'AssessmentMerchantPage', True, True), ('/api/admin/business/dashboard', 'BusinessDashboard', True, False), ('/api/merchant/business/dashboard', 'BusinessDashboard', False, False)]
    for path, result, admin, paged in routes:
        item = operation('get', path, '按门店与北京时间自然月读取核验经营记录', 'F19,F20')
        item['parameters'] = []
        item['x-roles'] = 'OPERATOR(can_analytics)' if admin else 'MERCHANT(manager, own store only)'
        item['x-read-only'] = True
        item['x-implementation-status'] = 'r8b3a-business-dashboard-implemented'
        item['description'] = 'no-store。独立can_analytics默认关闭，普通员工拒绝全店金额。单REPEATABLE_READ快照，区间[月初,min(次月初,observed_at))；收款/退款按成功时间分别归月，可负差额不是已结资金。正式/测试分栏，有未知或未分类来源时该完整金额为null；计数项amount恒null。count仅已核验候选，unknown_count及unclassified_count不能当0，excluded_count为剔除。佣金仅应计。月结单独返回最新已冻结最小版本，不计算/改写；候选流超过10000返回503。不输出客户/订单/凭据明细，不调用渠道。详见BUSINESS_DASHBOARD.md。'
        if paged:
            item['parameters'] = [{'name': 'page', 'in': 'query', 'schema': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}}, {'name': 'page_size', 'in': 'query', 'schema': {'const': 20}}]
        else:
            item['parameters'].append({'name': 'period_month', 'in': 'query', 'required': True, 'schema': month})
            if admin:
                item['parameters'].append({'name': 'merchant_id', 'in': 'query', 'required': True, 'schema': ident})
        item['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': ref(result)}}]}
        item['responses'].update({'404': {'description': '有效门店不存在或不在当前范围'}, '409': {'description': '未来月份尚未开始'}, '503': {'description': '数据库未配置、来源不可确认或候选数超过上限'}})
        d['paths'].setdefault(path, {})['get'] = item
