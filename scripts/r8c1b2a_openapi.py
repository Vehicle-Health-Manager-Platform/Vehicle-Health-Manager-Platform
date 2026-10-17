"""Private community questions; no diagnosis eligibility or public disclosure."""

def add_r8c1b2a(d, operation):
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
    s['CommunityQuestionServiceInput'] = strict(terms)
    s['CommunityQuestionServiceUpdate'] = strict({**terms, 'expected_revision': revision})
    s['CommunityQuestionServicePolicy'] = strict({**terms, 'id': ident, 'revision': ident, 'status': {'enum': ['DRAFT','PUBLISHED','STOPPED']}, 'created_at': stamp, 'published_at': null(stamp)})
    s['CommunityQuestionServiceAvailability'] = strict({'state': {'enum': ['UNKNOWN','NOT_STARTED','EXPIRED','SOURCE_UNAVAILABLE',*modes]}, 'accepting': {'type': 'boolean'}, 'policy': null(strict({'id': ident, 'revision': ident, 'mode': {'enum': modes}, 'valid_from': stamp, 'valid_to': stamp}))})
    s['CommunityQuestionServicePage'] = paged(ref('CommunityQuestionServicePolicy'))
    s['CommunityQuestionHandlerPage'] = paged(strict({'id': ident, 'label': text}))
    for bit in ['can_community_answer','can_community_manage']:
        s['OperatorAuthenticatedUser']['properties'][bit] = {'type': 'boolean'}
        s['OperatorAuthenticatedUser']['required'].append(bit)
    s['CommunityQuestionAdmissionContext'] = ref('CommunityQuestionServiceAvailability')
    states = ['WAITING','PROCESSING','REPLIED','CLOSED']
    header = {'case_id': ident, 'revision': ident, 'status': {'enum': states}, 'created_at': stamp, 'updated_at': stamp}
    s['CommunityQuestionCase'] = strict(header)
    s['CommunityQuestionQueueCase'] = strict({**header, 'assigned_operator_id': null(ident)})
    s['CommunityQuestionCasePage'] = paged(ref('CommunityQuestionCase'))
    s['CommunityQuestionQueuePage'] = paged(ref('CommunityQuestionQueueCase'))
    message = {'type': 'string', 'minLength': 1, 'maxLength': 2000, 'description': 'Unicode字符计数；允许内部换行，拒绝首尾空白及其他控制字符。'}
    s['CommunityQuestionCaseDetail'] = strict({'case': ref('CommunityQuestionCase'), 'category': {'enum': ['MAINTENANCE','REPAIR','FAULT']}, 'vehicle': strict({'model_id': ident, **{k: null(text) for k in ['brand','series','year','config_name']}}), 'messages': paged(strict({'message_id': ident, 'sender': {'enum': ['OWNER','OPERATOR']}, 'body': message, 'created_at': stamp}))})
    consent = {'policy_id': ident, 'policy_revision': ident, 'message': message, 'consent': {'const': True}}
    s['CommunityQuestionCreateInput'] = strict({'vehicle_id': ident, 'category': {'enum': ['MAINTENANCE','REPAIR','FAULT']}, **consent})
    s['CommunityQuestionMessageInput'] = strict({'expected_revision': revision, **consent})
    s['CommunityQuestionReplyInput'] = strict({'expected_revision': revision, 'message': message})
    s['CommunityQuestionAssignInput'] = strict({'expected_revision': revision, 'operator_id': ident})
    s['CommunityQuestionCloseInput'] = strict({'expected_revision': revision})
    s['CommunityQuestionReceipt'] = strict({'case_id': ident, 'revision': ident, 'status': {'enum': states}})
    routes = [
        ('get','/api/owner/community/questions/context','OWNER','CommunityQuestionAdmissionContext',None),
        ('get','/api/owner/community/questions/cases','OWNER','CommunityQuestionCasePage',None),
        ('post','/api/owner/community/questions/cases','OWNER','CommunityQuestionReceipt','CommunityQuestionCreateInput'),
        ('get','/api/owner/community/questions/cases/{id}','OWNER','CommunityQuestionCaseDetail',None),
        ('post','/api/owner/community/questions/cases/{id}/message','OWNER','CommunityQuestionReceipt','CommunityQuestionMessageInput'),
        ('post','/api/owner/community/questions/cases/{id}/close','OWNER','CommunityQuestionReceipt','CommunityQuestionCloseInput'),
        ('get','/api/admin/community/questions/policies','OPERATOR(can_community_manage)','CommunityQuestionServicePage',None),
        ('post','/api/admin/community/questions/policies','OPERATOR(can_community_manage)','CommunityQuestionServicePolicy','CommunityQuestionServiceInput'),
        ('put','/api/admin/community/questions/policies/{id}','OPERATOR(can_community_manage)','CommunityQuestionServicePolicy','CommunityQuestionServiceUpdate'),
        ('put','/api/admin/community/questions/policies/{id}/status','OPERATOR(can_community_manage)','CommunityQuestionServicePolicy','InvitePolicyStatusInput'),
        ('get','/api/admin/community/questions/handlers','OPERATOR(can_community_manage)','CommunityQuestionHandlerPage',None),
        ('get','/api/admin/community/questions/cases','OPERATOR(can_community_answer or can_community_manage)','CommunityQuestionCasePage',None),
        ('get','/api/admin/community/questions/cases/{id}','OPERATOR(can_community_answer + assigned)','CommunityQuestionCaseDetail',None),
        ('put','/api/admin/community/questions/cases/{id}/assignment','OPERATOR(can_community_manage)','CommunityQuestionReceipt','CommunityQuestionAssignInput'),
        ('post','/api/admin/community/questions/cases/{id}/reply','OPERATOR(can_community_answer + assigned)','CommunityQuestionReceipt','CommunityQuestionReplyInput'),
        ('post','/api/admin/community/questions/cases/{id}/close','OPERATOR(can_community_answer + assigned)','CommunityQuestionReceipt','CommunityQuestionCloseInput')]
    for method, path, role, result, body in routes:
        item = operation(method, path, '私有结构化问答受理/留言/分配与答复', 'F12,F20')
        item.update({'x-roles': role, 'x-read-only': method == 'get', 'x-implementation-status': 'r8c1b2-private-questions-implemented'})
        for p in item['parameters']:
            if p['in'] == 'path': p['schema'] = ident
        if method == 'get' and not path.endswith('/context'):
            item['parameters'] += [{'name': 'page', 'in': 'query', 'schema': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}}, {'name': 'page_size', 'in': 'query', 'schema': {'const': 20}}]
        if method == 'get' and path == '/api/admin/community/questions/cases':
            item['parameters'].append({'name': 'scope', 'in': 'query', 'schema': {'enum': ['mine','queue'], 'default': 'mine'}})
        if body:
            item['parameters'].append({'name': 'Idempotency-Key', 'in': 'header', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}})
            item['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref(body)}}}
        item['description'] = 'no-store。V058/122表，两项独立默认关闭权限/16字段登录。独立私有问答后端及b2b四页面/确认和本机原请求恢复已接通。默认无政策UNKNOWN，来源配置与有效处理人员/本人车辆有效车型在提交和补充事务内重验，非实时答复，无邀请资格或积分关联。队列仅分配信息无正文/车型；管理权限不授予正文；处理者须当前获派，重分配或撤权立即拒绝旧者新读取/原回执。车辆转移不转移原提问人历史；仅主动文字和车型快照，不复用R7档案/诊断/图片。CAS和消息/事件/审计原子，永久原键创建回执，其他原键24小时内当前权限恢复、过期CAS拒绝重复。公开须后续b3另行授权审核。本步不实现公开问答、通知投递或正式渠道。见PRIVATE_COMMUNITY_QUESTIONS.md。'
        schema = {'anyOf': [ref('CommunityQuestionCasePage'),ref('CommunityQuestionQueuePage')]} if path == '/api/admin/community/questions/cases' and method == 'get' else ref(result)
        item['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': schema}}]}
        item['responses'].update({'404': {'description': '非本人车辆/会话/当前分配或处理人员不可用'}, '409': {'description': '当前配置/车型/问答版本或状态变化'}, '503': {'description': '数据库/审计暂不可用，原键重试'}})
        d['paths'].setdefault(path, {})[method] = item
