"""Current-scope station inbox; no formal SMS/WeChat provider."""

def add_r7c1(d, operation):
    s = d['components']['schemas']
    ref = lambda name: {'$ref': '#/components/schemas/' + name}
    strict = lambda props: {'type': 'object', 'additionalProperties': False, 'required': list(props), 'properties': props}
    identifier = {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740991}
    count = {'type': 'integer', 'minimum': 0}
    stamp = {'type': 'string', 'format': 'date-time'}
    kinds = ['ORDER_PAID','ORDER_CHECK_IN','ORDER_ASSIGN','ORDER_TECH_ACCEPT','ORDER_SERVICE_FINISH','ORDER_COMPLETE','ORDER_CLOSED','REFUND_REQUESTED','REFUND_WITHDRAWN','REFUND_REJECTED','REFUND_ACCEPTED','REFUND_SUCCEEDED','REFUND_FAILED','DISPUTE_OPENED','DISPUTE_HANDLED','DISPUTE_REJECTED','DISPUTE_RESOLVED']
    s['ServiceNotification'] = strict({'notification_id': identifier, 'kind': {'enum': kinds}, 'order_id': identifier, 'order_no': {'type': 'string', 'minLength': 1, 'maxLength': 64}, 'order_status': {'enum': ['PENDING_PAYMENT','PAID','RECEIVED','IN_SERVICE','PENDING_VERIFY','COMPLETED','CLOSED','DISPUTED']}, 'occurred_at': stamp, 'read_at': {'anyOf': [stamp, {'type': 'null'}]}})
    s['ServiceInbox'] = strict({'unread_count': count, 'notifications': strict({'items': {'type': 'array', 'maxItems': 20, 'items': ref('ServiceNotification')}, 'total': count, 'page': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}, 'page_size': {'const': 20}})})
    s['ServiceNotificationRead'] = strict({'notification_id': identifier, 'read_at': stamp})
    for role, label in [('owner','OWNER'),('merchant','MERCHANT,STAFF'),('tech','TECHNICIAN')]:
        for method, suffix, result in [('get','','ServiceInbox'),('put','/{id}/read','ServiceNotificationRead')]:
            path = f'/api/{role}/notifications' + suffix
            item = operation(method, path, '当前身份订单事件收件箱/单条已读', 'F08,F15,F18')
            item.update({'x-roles': label, 'x-read-only': method == 'get', 'x-implementation-status': 'r7c3-order-events-implemented'})
            item['description'] = 'no-store。十七类订单事实同业务事务落outbox（退款受理与渠道成功分开），独立去重投递；无历史回填。每次读取/已读重验会话及当前订单车主/门店/派工归属；失权不计入列表或未读数，不给后来接手者回填旧事件。仅固定提示和最小订单事实，无正文/照片/联系方式/金额。退款仅车主和门店；其他事件含当时技师。多轮退款按版本、异议按追加记录区分，渠道结果重复不重发。门店账号共享本店已读。拒绝未知/重复query，读不制造事件。详见SERVICE_NOTIFICATIONS.md。'
            if method == 'get':
                item['parameters'] = [{'name': 'page', 'in': 'query', 'schema': {'type': 'integer', 'minimum': 1, 'maximum': 1000000, 'default': 1}}, {'name': 'page_size', 'in': 'query', 'schema': {'const': 20}}]
            else:
                item['parameters'] = [{'name': 'id', 'in': 'path', 'required': True, 'schema': identifier}, {'name': 'Idempotency-Key', 'in': 'header', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}}]
                item['requestBody'] = {'required': True, 'content': {'application/json': {'schema': strict({})}}}
            item['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': ref(result)}}]}
            item['responses'].update({'404': {'description': '消息不属于当前范围或来源不可用'}, '503': {'description': '通知数据库/写入服务不可用'}})
            d['paths'].setdefault(path, {})[method] = item
