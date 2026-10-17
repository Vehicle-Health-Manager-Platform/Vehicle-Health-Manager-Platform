"""Implemented issuance contracts; coupon trading stays in R5b."""
def add_r5a(document, operation):
    schemas = document['components']['schemas']
    strict = lambda p: {'type': 'object', 'additionalProperties': False, 'required': list(p), 'properties': p}
    ref = lambda n: {'$ref': '#/components/schemas/' + n}
    nullable = lambda n: {'anyOf': [n, {'type': 'null'}]}
    integer = lambda a, b: {'type': 'integer', 'minimum': a, 'maximum': b}
    identifier = integer(1, 9007199254740991)
    revision = integer(0, 9007199254740990)
    money = {'type': 'string', 'pattern': r'^(0|[1-9][0-9]{0,5})\.[0-9]{2}$'}
    utc = {'type': 'string', 'format': 'date-time', 'pattern': r'^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$'}
    schemas['CouponTerms'] = strict({'name': {'type': 'string', 'minLength': 1, 'maxLength': 128}, 'type': {'enum': ['DISCOUNT', 'SERVICE']},
        'project_id': identifier, 'amount': money, 'min_spend': money, 'total_quantity': integer(1, 2147483647), 'valid_from': utc, 'valid_to': utc})
    schemas['CouponTerms']['description'] = '发行门店指定在售标准项目；DISCOUNT金额>0且门槛>=金额，SERVICE金额/门槛0.00；截止晚于生效且创建/发布截止须未来。发布冻结条款、总库存；终身每模板每人一次。'
    schemas['CouponInventory'] = strict({'coupon_id': identifier, 'merchant_id': identifier, 'terms': ref('CouponTerms'), 'revision': identifier,
        'status': {'enum': ['DRAFT', 'PUBLISHED', 'STOPPED']}, 'stock_left': integer(0, 2147483647), 'publication_revision': nullable(identifier),
        'pool_selected': {'type': 'boolean'}, 'pool_revision': revision})
    schemas['MyCoupon'] = strict({'user_coupon_id': identifier, 'coupon_id': identifier, 'merchant_id': nullable(identifier), 'source_known': {'type': 'boolean'},
        'terms': nullable(ref('CouponTerms')), 'status': {'type': 'string'}, 'availability': {'enum': ['AVAILABLE', 'NOT_STARTED', 'EXPIRED', 'ISSUER_UNAVAILABLE', 'UNKNOWN']}, 'publication_revision': nullable(identifier)})
    for name, row in [('CouponInventoryPage', 'CouponInventory'), ('MyCouponPage', 'MyCoupon')]:
        schemas[name] = strict({'items': {'type': 'array', 'maxItems': 100, 'items': ref(row)}, 'total': integer(0, 9007199254740991), 'page': integer(1, 1000000), 'page_size': integer(1, 100)})
    schemas['CouponDraftInput'] = strict({'terms': ref('CouponTerms')})
    schemas['CouponUpdateInput'] = strict({'expected_revision': revision, 'terms': ref('CouponTerms')})
    schemas['CouponStatusInput'] = strict({'expected_revision': revision, 'status': {'enum': ['PUBLISHED', 'STOPPED']}})
    schemas['CouponPoolInput'] = strict({'expected_pool_revision': revision, 'selected': {'type': 'boolean'}})
    schemas['CouponClaimInput'] = strict({'coupon_id': identifier, 'publication_revision': identifier})
    schemas['CouponClaimReceipt'] = strict({'user_coupon_id': identifier, 'coupon_id': identifier, 'publication_revision': identifier})
    for method, path, summary, role, body, result in (
        ('get', '/api/merchant/coupons', '本店来源券库存列表', 'MERCHANT(manager)', None, 'CouponInventoryPage'),
        ('post', '/api/merchant/coupons', '本店创建券草稿', 'MERCHANT(manager)', 'CouponDraftInput', 'CouponInventory'),
        ('put', '/api/merchant/coupons/{id}', '版本化修改未发布草稿', 'MERCHANT(manager)', 'CouponUpdateInput', 'CouponInventory'),
        ('put', '/api/merchant/coupons/{id}/status', '发布冻结或停止领取', 'MERCHANT(manager)', 'CouponStatusInput', 'CouponInventory'),
        ('get', '/api/admin/coupons', '受限运营已发布券列表', 'OPERATOR(can_coupon)', None, 'CouponInventoryPage'),
        ('put', '/api/admin/coupons/{id}/pool', '版本化选择精选券池', 'OPERATOR(can_coupon)', 'CouponPoolInput', 'CouponInventory'),
        ('get', '/api/coupon/offers', '当前车主可领门店券或精选券', 'OWNER', None, 'CouponInventoryPage'),
        ('post', '/api/coupon/receive', '原子扣库存并发放本人券', 'OWNER', 'CouponClaimInput', 'CouponClaimReceipt'),
        ('get', '/api/coupon/mine', '本人券冻结权益与实际到期状态', 'OWNER', None, 'MyCouponPage')):
        item = operation(method, path, summary, 'F09')
        item['x-roles'] = role
        item['x-implementation-status'] = 'r5a-inventory-claim-implemented'
        item['description'] = 'no-store；严格正文/query，当前身份权限/来源复核。V038不提升历史券，不预置生产权益。写入UUID、锁、版本/限领/审计/库存/回执原子；成功重放后GET。池选择仅曝光，已领权益不因停止/移池改变。UTC传输，北京时间界面；交易抵扣/核销属R5b，见COUPON_INVENTORY.md。'
        for p in item['parameters']:
            if p['in'] == 'path': p['schema'] = identifier
        if method == 'get':
            item['parameters'] += [{'name': k, 'in': 'query', 'required': False, 'schema': s} for k, s in [('page', integer(1, 1000000)), ('page_size', integer(1, 100))]]
            if path.endswith('/offers'): item['parameters'].append({'name': 'merchant_id', 'in': 'query', 'required': False, 'schema': identifier, 'description': '省略时精选池，指定时本店当前可领取券'})
        else:
            item['parameters'] = [p for p in item['parameters'] if p['in'] != 'header']
            item['parameters'].append({'name': 'Idempotency-Key', 'in': 'header', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}})
            item['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref(body)}}}
        item['responses'].update({'404': {'description': '券或发行门店/项目不可用'}, '409': {'description': '状态/版本/库存/限领冲突'}, '503': {'description': '暂不可用，保留同body/key重试'}})
        item['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': ref(result)}}]}
        document['paths'].setdefault(path, {})[method] = item
