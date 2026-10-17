"""Read-only price comparisons with exact quotation and publication evidence."""


def add_r8b3c2(d, operation):
    schemas = d['components']['schemas']
    ref = lambda name: {'$ref': '#/components/schemas/' + name}
    strict = lambda fields: {'type': 'object', 'additionalProperties': False,
                             'required': list(fields), 'properties': fields}
    nullable = lambda schema: {'anyOf': [schema, {'type': 'null'}]}
    ident = {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740990}
    stamp = {'type': 'string', 'format': 'date-time',
             'description': 'UTC 整秒；一次读取使用相同观察时点。'}
    schemas['PricePolicy']['properties']['comparison_available'] = {
        'type': 'boolean',
        'description': '当前读取/新写回执为 true。c1 的永久原创建回执可保留 false；仅代表能力，不代表当前依据可用于比较。'}
    for path, methods in d['paths'].items():
        if path.startswith('/api/admin/price-monitor/'):
            for item in methods.values():
                item['description'] = item['description'].replace(
                    '配置不生成偏离判断，comparison_available=false，下一c2。',
                    '配置与比较分开；当前能力为true，c1永久原创建回执保持原false；有效比较须另GET同一时点重新核验。')
    states = ['UNKNOWN', 'WITHIN_RANGE', 'BELOW_RANGE', 'ABOVE_RANGE']
    reasons = ['VERIFIED', 'SOURCE_UNAVAILABLE', 'SOURCE_UNKNOWN', 'POLICY_MISSING',
               'POLICY_NOT_PUBLISHED', 'POLICY_STOPPED', 'POLICY_SOURCE_UNKNOWN',
               'NOT_STARTED', 'EXPIRED', 'SOURCE_CHANGED']
    schemas['PriceComparison'] = strict({
        'quote_id': ident,
        'state': {'enum': states},
        'reason': {'enum': reasons},
        'review_required': {'type': 'boolean', 'description': '仅完整有效依据下低于/高于区间为true，提示需核对。'},
        'current': nullable(ref('PriceQuoteSource')),
        'policy': nullable(ref('PricePolicy'))})
    schemas['PriceComparisonDetail'] = strict({'observed_at': stamp, 'comparison': ref('PriceComparison')})
    schemas['PriceComparisonPage'] = strict({'observed_at': stamp, 'comparisons': strict({
        'items': {'type': 'array', 'maxItems': 20, 'items': ref('PriceComparison')},
        'total': {'type': 'integer', 'minimum': 0},
        'page': {'type': 'integer', 'minimum': 1, 'maximum': 1000000},
        'page_size': {'const': 20}})})
    for path, result in [('/api/admin/price-monitor/comparisons', 'PriceComparisonPage'),
                         ('/api/admin/price-monitor/comparisons/{id}', 'PriceComparisonDetail')]:
        item = operation('get', path, '当前报价和已确认版本区间的只读比较', 'F20')
        item['x-roles'] = 'OPERATOR(can_price_monitor)'
        item['x-read-only'] = True
        item['x-implementation-status'] = 'r8b3c2-price-comparison-implemented'
        item['description'] = (
            'no-store，当前运营会话与独立can_price_monitor。单REPEATABLE_READ观察时间，候选为有效上架报价加仍PUBLISHED的失效报价；'
            '每报价优先发布依据，否则仅展示最新草稿/停止说明。复核最新报价及最后未软删除保存审计、冻结来源哈希/快照、最后未软删除发布状态凭证和[valid_from,valid_to)，后续软删除事件仍阻断回退旧证据。'
            '无源/缺证据/无依据/草稿/停止/未生效/到期/改版或适用来源变化均UNKNOWN，无默认区间或正常结果。'
            '仅VERIFIED按精确十进制比较，两端均WITHIN_RANGE；低于/高于仅BELOW_RANGE/ABOVE_RANGE且review_required=true。'
            '不自动改价、处罚或资金操作；只读不写审计/幂等/业务流水。source/policy不可核验为null，保留可核验旧快照供核对；详见PRICE_COMPARISONS.md。')
        item['parameters'] = ([{'name': 'id', 'in': 'path', 'required': True, 'schema': ident}]
                              if '{id}' in path else [
                                  {'name': 'page', 'in': 'query', 'schema': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}},
                                  {'name': 'page_size', 'in': 'query', 'schema': {'const': 20}}])
        item['responses']['200']['content']['application/json']['schema'] = {
            'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'],
                                         'properties': {'data': ref(result)}}]}
        item['responses'].update({'404': {'description': '不属于当前有效报价或仍发布依据候选'},
                                  '503': {'description': '数据库未配置或价格来源暂不可读取'}})
        d['paths'].setdefault(path, {})['get'] = item
