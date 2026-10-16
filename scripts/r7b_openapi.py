"""R7b current-task workbench and explicit record consent contracts."""

def add_r7b(d, operation):
    s = d['components']['schemas']
    ref = lambda n: {'$ref': '#/components/schemas/' + n}
    strict = lambda p: {'type': 'object', 'additionalProperties': False, 'required': list(p), 'properties': p}
    nullable = lambda p: {'anyOf': [p, {'type': 'null'}]}
    identifier = {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740991}
    count = {'type': 'integer', 'minimum': 0, 'maximum': 9007199254740991}
    text = {'type': 'string'}
    buckets = ['RECEIVE','CONFIRM','DISPUTE','ASSIGN','WAIT_ACCEPT','SERVICE','VERIFY','REVIEW_SOURCE']
    states = ['NONE','ACTIVE','REVOKED','SOURCE_CHANGED']
    paged = lambda item: strict({'items': {'type': 'array', 'maxItems': 20, 'items': item}, 'total': count, 'page': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}, 'page_size': {'const': 20}})
    version = {'type': 'string', 'pattern': '^[0-9a-f]{64}$'}
    fields = {}
    for typ, keys in [(1,['service_items','performed_mileage','service_date','oil_spec','parts_used','next_due_date','next_due_mileage']), (2,['repair_items','performed_mileage','repair_date','problem_description','parts_used','result_notes']), (10,['fault_code','fault_description','observed_on','fault_status','cleared_on','attention_level'])]:
        props = {}
        for k in keys:
            props[k] = ({'type': 'array', 'minItems': 1, 'maxItems': 20, 'uniqueItems': True, 'items': {'type': 'string', 'minLength': 1, 'maxLength': 80}} if k in ['service_items','repair_items','parts_used'] else count if k in ['performed_mileage','next_due_mileage'] else {'type': 'string', 'format': 'date'} if k in ['service_date','repair_date','next_due_date','observed_on','cleared_on'] else {'enum': ['ACTIVE','RESOLVED','UNKNOWN']} if k == 'fault_status' else {'enum': ['NORMAL','URGENT']} if k == 'attention_level' else {'type': 'string', 'maxLength': 200})
        fields[typ] = {'type': 'object', 'additionalProperties': False, 'properties': props}
    common = {'archive_id': identifier, 'recorded_date': nullable({'type': 'string', 'format': 'date'}), 'title': {'type': 'string', 'maxLength': 200}, 'notes': {'type': 'string', 'maxLength': 5000}, 'mileage': nullable(count), 'files': {'type': 'array', 'maxItems': 30, 'items': strict({'file_id': identifier, 'available': {'type': 'boolean'}})}}
    s['ServiceArchiveRecord'] = {'oneOf': [strict({**common, 'archive_type': {'const': typ}, 'fields': field}) for typ, field in fields.items()]}
    s['ArchiveGrantCandidates'] = strict({'order_id': identifier, 'service_active': {'type': 'boolean'}, 'records': paged(strict({'record': ref('ServiceArchiveRecord'), 'source_version': version, 'grant_revision': count, 'grant_state': {'enum': states}}))})
    s['ArchiveGrantInput'] = {'oneOf': [strict({'status': {'const': 'ACTIVE'}, 'expected_revision': count, 'source_version': version}), strict({'status': {'const': 'REVOKED'}, 'expected_revision': count})]}
    s['ArchiveGrantReceipt'] = strict({'order_id': identifier, 'archive_id': identifier, 'grant_revision': identifier, 'grant_state': {'enum': ['ACTIVE','REVOKED']}})
    s['ServiceTaskContext'] = strict({'order_id': identifier, 'order_status': {'enum': ['RECEIVED','IN_SERVICE','PENDING_VERIFY']}, 'vehicle': strict({k: nullable(text) for k in ['brand','series','year','config_name']}), 'project': nullable({'type': 'object', 'additionalProperties': False, 'properties': {'standard_project_id': identifier, 'project_name': text, 'service_content': text}}), 'arrival_mileage': nullable(count), 'archives': paged({'oneOf': [strict({'archive_id': identifier, 'state': {'const': 'ACTIVE'}, 'record': ref('ServiceArchiveRecord')}), strict({'archive_id': identifier, 'state': {'const': 'SOURCE_UNAVAILABLE'}, 'record': {'type': 'null'}})]})})
    s['ServiceWorkbench'] = strict({'counts': strict({k: count for k in buckets}), 'tasks': paged(strict({'order_id': identifier, 'order_status': {'enum': ['PAID','RECEIVED','DISPUTED','IN_SERVICE','PENDING_VERIFY']}, 'bucket': {'enum': buckets}, 'project_name': nullable(text), 'starts_at': nullable({'type': 'string', 'format': 'date-time'})}))})
    routes = [('get', '/api/order/{id}/archive-grants', 'OWNER', 'ArchiveGrantCandidates'), ('put', '/api/order/{id}/archive-grants/{archive}', 'OWNER', 'ArchiveGrantReceipt'), ('get', '/api/order/{id}/archive-grants/{archive}/files/{file}/preview', 'OWNER', 'OwnerEvidenceAccess')]
    for role, label in [('merchant','MERCHANT'),('tech','TECHNICIAN')]:
        routes += [('get', f'/api/{role}/workbench', label, 'ServiceWorkbench'), ('get', f'/api/{role}/orders/{{id}}/service-context', label, 'ServiceTaskContext'), ('get', f'/api/{role}/orders/{{id}}/service-context/archives/{{archive}}/files/{{file}}/access', label, 'OwnerEvidenceAccess')]
    import re
    for method, path, role, result in routes:
        item = operation(method, path, '当前任务工作台/本单最小资料/逐条档案授权', 'F08,F15,F18')
        item.update({'x-roles': role, 'x-read-only': method == 'get', 'x-implementation-status': 'r7b-service-workspace-implemented'})
        item['parameters'] = [{'name': name, 'in': 'path', 'required': True, 'schema': identifier} for name in re.findall(r'\{([^}]+)\}', path)]
        if result in ['ServiceWorkbench','ArchiveGrantCandidates','ServiceTaskContext']:
            item['parameters'] += [{'name': 'page', 'in': 'query', 'schema': {'type': 'integer', 'minimum': 1, 'maximum': 1000000, 'default': 1}}, {'name': 'page_size', 'in': 'query', 'schema': {'const': 20}}]
        if result == 'ServiceWorkbench':
            item['parameters'].append({'name': 'bucket', 'in': 'query', 'schema': {'enum': buckets}})
        if method == 'put':
            item['parameters'].append({'name': 'Idempotency-Key', 'in': 'header', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}})
            item['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref('ArchiveGrantInput')}}}
        item['description'] = 'no-store。拒绝未知/重复query；写入和文件访问无query。当前订单/本店/本人已接派工复核；仅1/2/10类历史档案由当前车主逐条授权，绑定源指纹/CAS版本。改动需重确认，撤销/失效/解绑拒绝新读取；无默认授权。签名IO前后复核，已发URL最长300秒残余有效。V044共96表，无回填。详见SERVICE_WORKSPACE.md。'
        item['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': ref(result)}}]}
        item['responses'].update({'404': {'description': '非当前有效本单/档案/文件或来源不可用'}, '409': {'description': '源记录或授权版本变化'}, '503': {'description': '数据库/私有存储不可用'}})
        d['paths'].setdefault(path, {})[method] = item
