"""User-approved R5b cash-coupon and noncash service-coupon contracts."""
def add_r5b(document, operation):
    s = document['components']['schemas']
    strict = lambda p: {'type': 'object', 'additionalProperties': False, 'required': list(p), 'properties': p}
    ref = lambda n: {'$ref': '#/components/schemas/' + n}
    nullable = lambda n: {'anyOf': [n, {'type': 'null'}]}
    integer = lambda a, b: {'type': 'integer', 'minimum': a, 'maximum': b}
    identifier = integer(1, 9007199254740991)
    revision = integer(0, 9007199254740990)
    money = {'type': 'string', 'pattern': r'^(0|[1-9][0-9]{0,7})\.[0-9]{2}$'}
    utc = {'type': 'string', 'format': 'date-time'}
    reasons = ['ELIGIBLE', 'SOURCE_UNKNOWN', 'SERVICE_ONLY', 'DISCOUNT_ONLY', 'NOT_AVAILABLE', 'NOT_STARTED', 'EXPIRED', 'WRONG_SCOPE', 'ISSUER_UNAVAILABLE', 'PROJECT_UNAVAILABLE', 'BELOW_MINIMUM', 'ZERO_CASH']
    booking = {k: identifier for k in ['merchant_project_id', 'quote_version_id', 'vehicle_id', 'slot_id']}
    s['CouponBookingInput'] = strict({**booking, 'user_coupon_id': identifier, 'publication_revision': identifier, 'rule_version': {'const': 'R5B_CASH_V1'}})
    s['CouponPreviewInput'] = strict({**booking, 'selected_user_coupon_id': nullable(identifier), 'page': integer(1, 1000000)})
    s['CouponCashCandidate'] = strict({'user_coupon_id': identifier, 'coupon_id': identifier, 'source_known': {'type': 'boolean'}, 'merchant_id': nullable(identifier),
        'publication_revision': nullable(identifier), 'terms': nullable(ref('CouponTerms')), 'eligibility': {'enum': reasons}, 'discount_amount': nullable(money), 'cash_amount': nullable(money)})
    page = lambda row: strict({'items': {'type': 'array', 'maxItems': 20, 'items': ref(row)}, 'total': integer(0, 9007199254740991), 'page': integer(1, 1000000), 'page_size': {'const': 20}})
    s['CouponCashCandidatePage'] = page('CouponCashCandidate')
    s['CouponTransactionPreview'] = strict({'quote': ref('ReservationQuote'), 'vehicle_id': identifier, 'slot': ref('ReservationSlot'), 'coupons': ref('CouponCashCandidatePage'),
        'selected': nullable(ref('CouponCashCandidate')), 'can_create': {'type': 'boolean'}, 'rule_version': {'const': 'R5B_CASH_V1'}})
    s['CouponBookingReceipt'] = strict({'order_id': identifier, 'order_no': {'type': 'string'}, 'amount_due': money, 'status': {'const': 'PENDING_PAYMENT'}, 'expires_at': utc})
    s['CouponOrderSummary'] = {'oneOf': [strict({'state': {'const': 'UNKNOWN'}, 'source_known': {'const': False}}), strict({
        'state': {'enum': ['RESERVED', 'CONSUMED', 'RELEASED', 'RETURNED']}, 'source_known': {'const': True}, 'user_coupon_id': identifier, 'coupon_id': identifier,
        'name': {'type': 'string'}, 'original_amount': money, 'discount_amount': money, 'cash_amount': money, 'rule_version': {'const': 'R5B_CASH_V1'}, 'valid_to': utc})]}
    for name in ['OwnerOrder', 'OwnerOrderDetail', 'MerchantOrder', 'MerchantOrderDetail', 'MerchantOrderAction']:
        if name in s: s[name].setdefault('properties', {})['coupon_summary'] = ref('CouponOrderSummary')
    s['ServiceCouponReceipt'] = strict({'redemption_id': identifier, 'user_coupon_id': identifier, 'coupon_id': identifier, 'merchant_id': identifier, 'project_id': identifier,
        'terms': ref('CouponTerms'), 'redeemed_at': utc, 'commission_amount': {'const': '0.00'}, 'rule_version': {'const': 'R5B_SERVICE_V1'}})
    s['ServiceCouponView'] = strict({'user_coupon_id': identifier, 'merchant_id': nullable(identifier), 'terms': nullable(ref('CouponTerms')), 'source_known': {'type': 'boolean'},
        'eligibility': {'enum': reasons}, 'authorization_revision': revision, 'authorization_expires_at': nullable(utc), 'redemption': nullable(ref('ServiceCouponReceipt'))})
    s['ServiceCouponAuthorizationInput'] = strict({'expected_revision': revision})
    s['ServiceCouponAuthorization'] = strict({'user_coupon_id': identifier, 'authorization_revision': identifier, 'expires_at': utc,
        'code': {'type': 'string', 'pattern': '^[0-9a-f]{32}$', 'writeOnly': True}, 'rule_version': {'const': 'R5B_SERVICE_V1'}})
    # This is a private response, not a writable data field. Avoid misleading writeOnly metadata.
    s['ServiceCouponAuthorization']['properties']['code'].pop('writeOnly')
    s['ServiceCouponRedeemInput'] = strict({'user_coupon_id': identifier, 'authorization_revision': identifier, 'code': {'type': 'string', 'pattern': '^[0-9a-f]{32}$'}})
    s['ServiceCouponMerchantPreview'] = strict({'user_coupon_id': identifier, 'merchant_id': identifier, 'project_id': identifier, 'project_name': {'type': 'string'},
        'terms': ref('CouponTerms'), 'authorization_revision': identifier, 'authorization_expires_at': utc, 'redemption': nullable(ref('ServiceCouponReceipt')), 'can_redeem': {'type': 'boolean'}})
    s['ServiceCouponReceiptPage'] = {**page('ServiceCouponReceipt')}
    s['ServiceCouponReceiptPage']['properties']['page_size'] = integer(1, 100)
    s['ServiceCouponReceiptPage']['properties']['items']['maxItems'] = 100
    for method, path, title, role, body, result, write in (
        ('post', '/api/order/coupon-preview', '当前报价/本人车/时段用券资格预览', 'OWNER', 'CouponPreviewInput', 'CouponTransactionPreview', False),
        ('post', '/api/order/coupon-create', '订单/时段/单券原子冻结创建', 'OWNER', 'CouponBookingInput', 'CouponBookingReceipt', True),
        ('get', '/api/coupon/{id}/service-redemption', '本人服务券授权版本和核销记录', 'OWNER', None, 'ServiceCouponView', False),
        ('post', '/api/coupon/{id}/service-authorization', '本人生成短期服务券授权码', 'OWNER', 'ServiceCouponAuthorizationInput', 'ServiceCouponAuthorization', True),
        ('post', '/api/merchant/coupons/service-preview', '本店核对服务券项目和车主授权', 'MERCHANT,STAFF', 'ServiceCouponRedeemInput', 'ServiceCouponMerchantPreview', False),
        ('post', '/api/merchant/coupons/service-redeem', '本店独立消费服务券并记录免佣事实', 'MERCHANT,STAFF', 'ServiceCouponRedeemInput', 'ServiceCouponReceipt', True),
        ('get', '/api/merchant/coupons/service-redemptions', '本店服务券核销记录分页', 'MERCHANT,STAFF', None, 'ServiceCouponReceiptPage', False)):
        item = operation(method, path, title, 'F07,F09,F18')
        item['x-roles'] = role
        item['x-implementation-status'] = 'r5b-coupon-transactions-implemented'
        item['parameters'] = [p for p in item['parameters'] if p['in'] != 'header']
        for p in item['parameters']:
            if p['in'] == 'path': p['schema'] = identifier
        if write: item['parameters'].append({'name': 'Idempotency-Key', 'in': 'header', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}})
        if body: item['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref(body)}}}
        if path.endswith('service-redemptions'): item['parameters'] += [{'name': k, 'in': 'query', 'schema': v} for k, v in [('page', integer(1, 1000000)), ('page_size', integer(1, 100))]]
        item['description'] = 'no-store，严格正文/查询及当前角色。用户确认单券不叠加、发行店项目限定，DISCOUNT实付>=0.01按实际现金冻结既有佣金政策；SERVICE独立非现金核销免佣。支付PAID消费、未付关闭释放、全额退款SUCCEEDED返原券不续期；V039不提升旧券/WAIT_COUPON。生成码只存摘要，码仅本人私有回执；核销先核对本店/项目/授权，错误码持久限流，交易/审计/回执原子，恢复依据真实唯一付款/退款事实。预览不消费券，服务码预览错误尝试可能更新安全计数。见COUPON_TRANSACTIONS.md。'
        item['responses'].update({'404': {'description': '本人/本店资源不可用'}, '409': {'description': '报价/券/名额/冻结事实改变'}, '422': {'description': '服务码错误或版本已更新'}, '429': {'description': '服务码尝试受限，Retry-After秒'}, '503': {'description': '暂不可用，写入保留原body/key重试'}})
        item['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': ref(result)}}]}
        document['paths'].setdefault(path, {})[method] = item
