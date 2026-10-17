"""Independent consent and review of audited private question excerpts; UI follows in b3b."""

def add_r8c1b3a(d, operation):
    s = d['components']['schemas']
    ref = lambda n: {'$ref': '#/components/schemas/' + n}
    strict = lambda p: {'type': 'object', 'additionalProperties': False, 'required': list(p), 'properties': p}
    nullable = lambda p: {'anyOf': [p, {'type': 'null'}]}
    ident = {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740991}
    stamp = {'type': 'string', 'format': 'date-time'}
    uuid = {'type': 'string', 'format': 'uuid', 'pattern': '^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$'}
    digest = {'type': 'string', 'pattern': '^[a-f0-9]{64}$'}
    category = {'enum': ['MAINTENANCE', 'REPAIR', 'FAULT']}
    text = {'type': 'string', 'minLength': 1, 'maxLength': 300, 'description': '完整原文先NFKC及标识遮盖，再取前300 Unicode字符；人工仍须复核其他可识别信息。'}
    fields = {'model_id': ident, 'category': category, 'question_text': text, 'answer_text': text}
    s['PublicQaPreview'] = strict({**fields, 'redaction_version': {'const': 'QA_TEXT_REDACTION_V1'}})
    selection = {'question_message_id': ident, 'answer_message_id': ident}
    s['PublicQaSelection'] = strict(selection)
    s['PublicQaPreviewResult'] = strict({'preview': ref('PublicQaPreview'), 'preview_hash': digest})
    s['PublicQaSubmit'] = strict({**selection, 'preview_hash': digest, 'consent_version': {'const': 'R8C_PUBLIC_QA_V1'}, 'consent': {'const': True}})
    states = {'enum': ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN']}
    receipt = {'application_id': ident, 'revision': ident, 'status': states}
    s['PublicQaReceipt'] = strict(receipt)
    reason = nullable({'enum': ['PRIVACY', 'ABUSE', 'OFF_TOPIC', 'UNVERIFIABLE']})
    s['OwnerPublicQaApplication'] = strict({**receipt, 'preview': nullable(ref('PublicQaPreview')), 'public_id': nullable(uuid), 'reason_code': reason, 'visible': {'type': 'boolean'}, 'created_at': stamp})
    s['PublicQaPending'] = strict({**receipt, 'preview': nullable(ref('PublicQaPreview')), 'created_at': stamp, 'source_available': {'type': 'boolean'}})
    for name, row in [('OwnerPublicQaPage', 'OwnerPublicQaApplication'), ('PublicQaPendingPage', 'PublicQaPending')]:
        s[name] = strict({'items': {'type': 'array', 'maxItems': 20, 'items': ref(row)}, 'total': {'type': 'integer', 'minimum': 0}, 'page': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}, 'page_size': {'const': 20}})
    s['PublicQaWithdraw'] = strict({'expected_revision': ident})
    s['PublicQaModerate'] = strict({'expected_revision': ident, 'decision': {'enum': ['APPROVE', 'REJECT']}, 'reason_code': reason})
    s['PublishedQa'] = strict({**fields, 'public_id': uuid, 'published_at': stamp})
    s['PublishedQaList'] = strict({'vehicle_id': ident, 'items': {'type': 'array', 'maxItems': 20, 'items': ref('PublishedQa')}, 'next_cursor': nullable(ident)})
    s['PublishedQaDetail'] = strict({'vehicle_id': ident, 'question': ref('PublishedQa')})
    routes = [
        ('post', '/api/owner/community/questions/cases/{caseId}/publication-preview', 'PublicQaPreviewResult', 'PublicQaSelection', False),
        ('get', '/api/owner/community/questions/cases/{caseId}/publications', 'OwnerPublicQaPage', None, True),
        ('post', '/api/owner/community/questions/cases/{caseId}/publications', 'PublicQaReceipt', 'PublicQaSubmit', False),
        ('post', '/api/owner/community/question-publications/{id}/withdraw', 'PublicQaReceipt', 'PublicQaWithdraw', False),
        ('get', '/api/admin/community/question-publications', 'PublicQaPendingPage', None, True),
        ('post', '/api/admin/community/question-publications/{id}/moderate', 'PublicQaReceipt', 'PublicQaModerate', False),
        ('get', '/api/community/questions', 'PublishedQaList', None, False),
        ('get', '/api/community/questions/{publicId}', 'PublishedQaDetail', None, False)]
    for method, path, result, body, paged in routes:
        item = operation(method, path, '独立公开问答授权/审核/撤回与本人同款读取', 'F12,F20')
        item['x-roles'] = 'OPERATOR(can_review)' if '/admin/' in path else 'OWNER'
        item['x-implementation-status'] = 'r8c1b3a-public-qa-backend-implemented'
        readonly = method == 'get' or path.endswith('/publication-preview')
        item['x-read-only'] = readonly
        item['parameters'] = [{'name': p, 'in': 'path', 'required': True, 'schema': uuid if p == 'publicId' else ident} for p in ['caseId', 'id', 'publicId'] if '{' + p + '}' in path]
        if paged:
            item['parameters'] += [{'name': 'page', 'in': 'query', 'schema': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}}, {'name': 'page_size', 'in': 'query', 'schema': {'const': 20}}]
        if path.startswith('/api/community/'):
            item['parameters'].append({'name': 'vehicle_id', 'in': 'query', 'required': True, 'schema': ident})
            if path == '/api/community/questions':
                item['parameters'].append({'name': 'cursor', 'in': 'query', 'schema': ident})
        if body:
            item['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref(body)}}}
            if not readonly:
                item['parameters'].append({'name': 'Idempotency-Key', 'in': 'header', 'required': True, 'schema': uuid})
        item['description'] = ('no-store，V059/124表，COMMUNITY_PUBLICATION_ENABLED默认false。只有新消息的原文字/事件/审计及服务器VERIFIED_WECHAT/app来源均可核验才允许公开，不补旧/测试证据。'
                               '选择具体OWNER问题和该答复前最近问题对应的OPERATOR答复，完整先脱敏再取摘要；独立预览hash和公开consent，提交不接受任意原文。'
                               'can_review只读明确公开预览，无私有详情或来源标识；来源/投影篡改时preview=null、不可批准。当前授权/源/同款及最后事件/原审计重验，软删除或旧行回退拒绝。'
                               '申请永久原键创建data；当前身份恢复可在来源失效/开关关闭后完成，撤回不依赖当前来源。本人状态也可在关闭时读取；其余操作需开启。'
                               '公开列表最多20基础候选，失效逐条隐藏，按next_cursor继续，不保证满页。后端已实现，页面/本机恢复下一b3b；无公开图片、通知投递或正式身份接入声明。见PUBLIC_COMMUNITY_QUESTIONS.md。')
        item['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': ref(result)}}]}
        item['responses'].update({'404': {'description': '非本人问答/车辆/当前同款或公开UUID已撤回/来源失效'}, '409': {'description': '原来源/预览/授权/修订改变，或已有有效申请'}, '503': {'description': '数据库/公开开关暂不可用，原请求恢复'}})
        d['paths'].setdefault(path, {})[method] = item
