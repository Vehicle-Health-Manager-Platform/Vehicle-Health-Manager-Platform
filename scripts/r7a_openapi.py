"""Owner-only construction evidence reads; no write or new schema."""

def add_r7a(d, operation):
    s = d['components']['schemas']
    ref = lambda n: {'$ref': '#/components/schemas/' + n}
    strict = lambda p: {'type': 'object', 'additionalProperties': False, 'required': list(p), 'properties': p}
    nullable = lambda p: {'anyOf': [p, {'type': 'null'}]}
    identifier = {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740991}
    stamp = {'type': 'string', 'format': 'date-time'}
    kinds = ['PICKUP', 'PROTECTION', 'PROCESS', 'FAULT', 'FINISH', 'SIGNATURE']
    s['OwnerEvidencePhoto'] = strict({'file_id': identifier, 'slot': {'enum': ['', 'FRONT', 'REAR', 'LEFT', 'RIGHT', 'ROOF', 'DASHBOARD', 'INTERIOR', 'FUEL']}, 'available': {'type': 'boolean'}})
    s['OwnerEvidenceGroup'] = strict({'kind': {'enum': kinds}, 'state': {'enum': ['MISSING', 'UNKNOWN', 'RECORDED']}, 'photos': {'type': 'array', 'maxItems': 9, 'items': ref('OwnerEvidencePhoto')}})
    s['OwnerEvidenceReport'] = strict({
        'repair_plan': {'type': 'string', 'minLength': 1, 'maxLength': 2000}, 'fault_analysis': {'type': 'string', 'minLength': 1, 'maxLength': 2000},
        'parts_used': {'type': 'array', 'maxItems': 20, 'items': strict({**{k: {'type': 'string', 'minLength': 1, 'maxLength': 100} for k in ['name', 'model', 'brand']}, 'quantity': {'type': 'integer', 'minimum': 1, 'maximum': 999}})},
        'no_parts': {'type': 'boolean'}, 'no_fault_parts': {'type': 'boolean'}, 'work_minutes': {'type': 'integer', 'minimum': 1, 'maximum': 1440}, 'submitted_at': stamp, 'signed_at': nullable(stamp)})
    s['OwnerServiceEvidence'] = strict({
        'order_id': identifier, 'order_status': {'enum': ['PENDING_PAYMENT', 'PAID', 'RECEIVED', 'IN_SERVICE', 'PENDING_VERIFY', 'COMPLETED', 'CLOSED', 'DISPUTED']},
        'timeline': {'type': 'array', 'maxItems': 10, 'items': strict({'event': {'enum': ['CREATED', 'CHECKED_IN', 'OWNER_CONFIRMED', 'ASSIGNED', 'PROTECTED', 'REPORTED', 'QUALITY_SIGNED', 'REDEEMED', 'CLOSED']}, 'occurred_at': stamp})},
        'groups': {'type': 'array', 'minItems': 6, 'maxItems': 6, 'items': ref('OwnerEvidenceGroup')}, 'report_state': {'enum': ['MISSING', 'UNKNOWN', 'SUBMITTED', 'SIGNED']}, 'report': nullable(ref('OwnerEvidenceReport'))})
    s['OwnerEvidenceAccess'] = strict({'url': {'type': 'string', 'format': 'uri', 'pattern': '^https://'}, 'expires_at': stamp})
    for path, result in [('/api/order/{id}/work', 'OwnerServiceEvidence'), ('/api/order/{id}/work/files/{file}/access', 'OwnerEvidenceAccess')]:
        item = operation('get', path, '本人订单施工证据/受控图片读取', 'F08,F15,F18')
        item['x-roles'] = 'OWNER'; item['x-read-only'] = True; item['x-implementation-status'] = 'r7a-owner-evidence-implemented'
        item['parameters'] = [{'name': k, 'in': 'path', 'required': True, 'schema': identifier} for k in (['id', 'file'] if '{file}' in path else ['id'])]
        item['description'] = 'no-store，拒绝全部query。当前车主会话与订单归属复核；固定六组、缺失MISSING/来源不完整UNKNOWN，安全文件单独available。不输出员工身份/对象key。签名存储IO后再次复核当前来源，已发URL仍可能在既有最长300秒有效期内访问。GET不改变施工或订单状态，无新增表，详见OWNER_SERVICE_EVIDENCE.md。'
        item['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': ref(result)}}]}
        item['responses'].update({'404': {'description': '非当前本人订单或文件来源/安全状态不可用'}, '503': {'description': '数据库/私有对象存储不可用'}})
        d['paths'][path] = {'get': item}
