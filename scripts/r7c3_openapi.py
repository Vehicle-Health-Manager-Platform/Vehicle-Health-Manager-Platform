"""Diagnosis notifications are independently scoped from order notifications."""
from copy import deepcopy

def add_r7c3(d, operation):
    s = d['components']['schemas']
    ref = lambda name: {'$ref': '#/components/schemas/' + name}
    strict = lambda props: {'type': 'object', 'additionalProperties': False, 'required': list(props), 'properties': props}
    identifier = {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740991}
    count = {'type': 'integer', 'minimum': 0}
    stamp = {'type': 'string', 'format': 'date-time'}
    s['DiagnosisNotification'] = strict({'notification_id': identifier, 'kind': {'enum': ['DIAGNOSIS_CREATED','DIAGNOSIS_MESSAGE','DIAGNOSIS_ASSIGNED','DIAGNOSIS_REPLIED','DIAGNOSIS_CLOSED']}, 'case_id': identifier, 'case_status': {'enum': ['WAITING','PROCESSING','REPLIED','CLOSED']}, 'target': {'enum': ['CASE','QUEUE']}, 'occurred_at': stamp, 'read_at': {'anyOf': [stamp, {'type': 'null'}]}})
    s['DiagnosisInbox'] = strict({'unread_count': count, 'notifications': strict({'items': {'type': 'array', 'maxItems': 20, 'items': ref('DiagnosisNotification')}, 'total': count, 'page': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}, 'page_size': {'const': 20}})})
    s['DiagnosisOwnerNotification'] = deepcopy(s['DiagnosisNotification'])
    s['DiagnosisOwnerNotification']['properties']['target'] = {'const': 'CASE'}
    s['DiagnosisOwnerInbox'] = deepcopy(s['DiagnosisInbox'])
    s['DiagnosisOwnerInbox']['properties']['notifications']['properties']['items']['items'] = ref('DiagnosisOwnerNotification')
    for role, label in [('owner','OWNER'),('admin','OPERATOR with current diagnose or diagnose-manage privilege')]:
        for method, suffix, result in [('get','','DiagnosisOwnerInbox' if role == 'owner' else 'DiagnosisInbox'),('put','/{id}/read','ServiceNotificationRead')]:
            path = f'/api/{role}/diagnosis/notifications' + suffix
            item = operation(method, path, '本人/独立运营诊断消息与原键已读', 'F08,F15,F18')
            item.update({'x-roles': label, 'x-read-only': method == 'get', 'x-implementation-status': 'r7c3-diagnosis-notifications-implemented'})
            item['description'] = 'no-store。五类诊断事实同业务事务捕获来源与用途，独立去重投递，无历史回填。列表/计数/原键已读重验当前身份与业务用途：本人CASE；当时获派且当前仍获派并有处理权限的人员CASE；当时管理接收人且当前仍有管理权限者仅QUEUE。双权限账号同事件一条；旧管理用途不会因后来获派提升为CASE。不含正文/车型/身份/档案/私有地址。已读不重复审计或改变时点。详见DIAGNOSIS_NOTIFICATIONS.md。'
            if method == 'get':
                item['parameters'] = [{'name': 'page', 'in': 'query', 'schema': {'type': 'integer', 'minimum': 1, 'maximum': 1000000, 'default': 1}}, {'name': 'page_size', 'in': 'query', 'schema': {'const': 20}}]
            else:
                item['parameters'] = [{'name': 'id', 'in': 'path', 'required': True, 'schema': identifier}, {'name': 'Idempotency-Key', 'in': 'header', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}}]
                item['requestBody'] = {'required': True, 'content': {'application/json': {'schema': strict({})}}}
            item['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': ref(result)}}]}
            item['responses'].update({'403': {'description': '无当前诊断权限/身份错误'}, '404': {'description': '不在当时与当前用途交集'}, '503': {'description': '数据库或原子写入暂不可用'}})
            d['paths'].setdefault(path, {})[method] = item
