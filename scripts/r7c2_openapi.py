"""Asynchronous diagnosis with independently provisioned operator capabilities."""

def add_r7c2(d, operation):
    s = d['components']['schemas']
    ref = lambda name: {'$ref': '#/components/schemas/' + name}
    strict = lambda p: {'type': 'object', 'additionalProperties': False, 'required': list(p), 'properties': p}
    null = lambda p: {'anyOf': [p, {'type': 'null'}]}
    ident = {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740991}
    revision = {'type': 'integer', 'minimum': 0, 'maximum': 9007199254740990}
    stamp = {'type': 'string', 'format': 'date-time'}
    text = {'type': 'string'}
    paged = lambda row: strict({'items': {'type': 'array', 'maxItems': 20, 'items': row}, 'total': {'type': 'integer', 'minimum': 0}, 'page': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}, 'page_size': {'const': 20}})
    modes = ['AVAILABLE', 'LEAVE_MESSAGE', 'PAUSED']
    terms = {'mode': {'enum': modes}, 'source_note': {'type': 'string', 'minLength': 2, 'maxLength': 256}, 'valid_from': stamp, 'valid_to': stamp}
    s['DiagnosisServiceInput'] = strict(terms)
    s['DiagnosisServiceUpdate'] = strict({**terms, 'expected_revision': revision})
    s['DiagnosisServicePolicy'] = strict({**terms, 'id': ident, 'revision': ident, 'status': {'enum': ['DRAFT','PUBLISHED','STOPPED']}, 'created_at': stamp, 'published_at': null(stamp)})
    s['DiagnosisServiceAvailability'] = strict({'state': {'enum': ['UNKNOWN','NOT_STARTED','EXPIRED','SOURCE_UNAVAILABLE',*modes]}, 'accepting': {'type': 'boolean'}, 'policy': null(ref('DiagnosisServicePolicy'))})
    s['DiagnosisServicePage'] = paged(ref('DiagnosisServicePolicy'))
    s['DiagnosisHandlerPage'] = paged(strict({'id': ident, 'label': text}))
    s['InviteDiagnosisEligibility']['properties']['service_available'] = {'type': 'boolean', 'description': '资格ELIGIBLE且受理配置允许并有有效处理人员时true，不表示实时在线。提交仍重新核对。'}
    d['paths']['/api/invite/diagnosis-eligibility']['get']['description'] = d['paths']['/api/invite/diagnosis-eligibility']['get']['description'].replace('service_available固定false，留言服务R7另行接入，真正提交时重验', 'R7c2已接入受理配置；service_available仅在资格达标且配置允许时true，新提交/追加仍在事务中重验')
    s['DiagnosisAdmissionContext'] = strict({'service': ref('DiagnosisServiceAvailability'), 'eligibility': ref('InviteDiagnosisEligibility')})
    states = ['WAITING','PROCESSING','REPLIED','CLOSED']
    header = {'case_id': ident, 'revision': ident, 'status': {'enum': states}, 'created_at': stamp, 'updated_at': stamp}
    s['DiagnosisCase'] = strict(header)
    s['DiagnosisQueueCase'] = strict({**header, 'assigned_operator_id': null(ident)})
    s['DiagnosisCasePage'] = paged(ref('DiagnosisCase'))
    s['DiagnosisQueuePage'] = paged(ref('DiagnosisQueueCase'))
    message = {'type': 'string', 'minLength': 1, 'maxLength': 2000, 'description': 'Unicode字符计数；允许内部换行，拒绝首尾空白及其他控制字符。'}
    s['DiagnosisCaseDetail'] = strict({'case': ref('DiagnosisCase'), 'vehicle': strict({k: null(text) for k in ['brand','series','year','config_name']}), 'messages': paged(strict({'message_id': ident, 'sender': {'enum': ['OWNER','OPERATOR']}, 'body': message, 'created_at': stamp}))})
    consent = {'policy_id': ident, 'policy_revision': ident, 'message': message, 'consent': {'const': True}}
    s['DiagnosisCreateInput'] = strict({'vehicle_id': ident, **consent})
    s['DiagnosisMessageInput'] = strict({'expected_revision': revision, **consent})
    s['DiagnosisReplyInput'] = strict({'expected_revision': revision, 'message': message})
    s['DiagnosisAssignInput'] = strict({'expected_revision': revision, 'operator_id': ident})
    s['DiagnosisCloseInput'] = strict({'expected_revision': revision})
    s['DiagnosisReceipt'] = strict({'case_id': ident, 'revision': ident, 'status': {'enum': states}})
    routes = [
        ('get','/api/owner/diagnosis/context','OWNER','DiagnosisAdmissionContext',None),
        ('get','/api/owner/diagnosis/cases','OWNER','DiagnosisCasePage',None),
        ('post','/api/owner/diagnosis/cases','OWNER','DiagnosisReceipt','DiagnosisCreateInput'),
        ('get','/api/owner/diagnosis/cases/{id}','OWNER','DiagnosisCaseDetail',None),
        ('post','/api/owner/diagnosis/cases/{id}/message','OWNER','DiagnosisReceipt','DiagnosisMessageInput'),
        ('post','/api/owner/diagnosis/cases/{id}/close','OWNER','DiagnosisReceipt','DiagnosisCloseInput'),
        ('get','/api/admin/diagnosis/policies','OPERATOR(can_diagnose_manage)','DiagnosisServicePage',None),
        ('post','/api/admin/diagnosis/policies','OPERATOR(can_diagnose_manage)','DiagnosisServicePolicy','DiagnosisServiceInput'),
        ('put','/api/admin/diagnosis/policies/{id}','OPERATOR(can_diagnose_manage)','DiagnosisServicePolicy','DiagnosisServiceUpdate'),
        ('put','/api/admin/diagnosis/policies/{id}/status','OPERATOR(can_diagnose_manage)','DiagnosisServicePolicy','InvitePolicyStatusInput'),
        ('get','/api/admin/diagnosis/handlers','OPERATOR(can_diagnose_manage)','DiagnosisHandlerPage',None),
        ('get','/api/admin/diagnosis/cases','OPERATOR(can_diagnose or can_diagnose_manage)','DiagnosisCasePage',None),
        ('get','/api/admin/diagnosis/cases/{id}','OPERATOR(can_diagnose + assigned)','DiagnosisCaseDetail',None),
        ('put','/api/admin/diagnosis/cases/{id}/assignment','OPERATOR(can_diagnose_manage)','DiagnosisReceipt','DiagnosisAssignInput'),
        ('post','/api/admin/diagnosis/cases/{id}/reply','OPERATOR(can_diagnose + assigned)','DiagnosisReceipt','DiagnosisReplyInput'),
        ('post','/api/admin/diagnosis/cases/{id}/close','OPERATOR(manage or assigned handler)','DiagnosisReceipt','DiagnosisCloseInput')]
    for method, path, role, result, body in routes:
        item = operation(method, path, '非实时人工诊断受理/留言/分配与答复', 'F09,F10')
        item.update({'x-roles': role, 'x-read-only': method == 'get', 'x-implementation-status': 'r7c2-async-diagnosis-implemented'})
        for p in item['parameters']:
            if p['in'] == 'path': p['schema'] = ident
        if method == 'get' and not path.endswith('/context'):
            item['parameters'] += [{'name': 'page', 'in': 'query', 'schema': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}}, {'name': 'page_size', 'in': 'query', 'schema': {'const': 20}}]
        if method == 'get' and path == '/api/admin/diagnosis/cases':
            item['parameters'].append({'name': 'scope', 'in': 'query', 'schema': {'enum': ['mine','queue'], 'default': 'mine'}})
        if body:
            item['parameters'].append({'name': 'Idempotency-Key', 'in': 'header', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}})
            item['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref(body)}}}
        item['description'] = 'no-store。拒绝未知/重复query及额外正文。独立管理与处理权限默认关闭，管理队列不含正文，处理人员须当前获派。可受理/离线留言均非实时，暂停拒绝新留言；当前R6资格与配置在提交/追加事务锁等待后重验。仅主动文字与车型，无历史档案/图片默认共享。消息/状态事件/审计原子，CAS/幂等，创建有长期意图去重，切号/隐藏清理。V046共102表，见ASYNC_DIAGNOSIS.md。'
        schema = {'anyOf': [ref('DiagnosisCasePage'),ref('DiagnosisQueuePage')]} if path == '/api/admin/diagnosis/cases' and method == 'get' else ref(result)
        item['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': schema}}]}
        item['responses'].update({'404': {'description': '非本人车辆/会话/当前分配或处理人员不可用'}, '409': {'description': '当前配置/资格/会话版本或状态变化'}, '503': {'description': '数据库/审计暂不可用，原键重试'}})
        d['paths'].setdefault(path, {})[method] = item
