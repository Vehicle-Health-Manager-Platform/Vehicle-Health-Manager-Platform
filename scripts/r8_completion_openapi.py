"""R8 independent image copies and licensed keyword retrieval; no vector effect claim."""


def add_r8_completion(d, operation):
    s = d['components']['schemas']
    ref = lambda n: {'$ref': '#/components/schemas/' + n}
    strict = lambda p: {'type': 'object', 'additionalProperties': False, 'required': list(p), 'properties': p}
    nullable = lambda p: {'anyOf': [p, {'type': 'null'}]}
    ident = {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740991}
    uuid = {'type': 'string', 'format': 'uuid'}
    stamp = {'type': 'string', 'format': 'date-time'}
    digest = {'type': 'string', 'pattern': '^[a-f0-9]{64}$'}
    text = lambda n: {'type': 'string', 'minLength': 1, 'maxLength': n}
    boolean = {'type': 'boolean'}
    paged = lambda row: strict({'items': {'type': 'array', 'maxItems': 20, 'items': ref(row)}, 'total': {'type': 'integer', 'minimum': 0}, 'page': ident, 'page_size': {'const': 20}})
    dims = {'width': {'type': 'integer', 'minimum': 1, 'maximum': 4096}, 'height': {'type': 'integer', 'minimum': 1, 'maximum': 4096}}
    b64 = {'type': 'string', 'contentEncoding': 'base64', 'maxLength': 2796204}
    receipt = {'application_id': ident, 'revision': ident, 'status': {'enum': ['PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN']}}
    reason = nullable({'enum': ['PRIVACY', 'ABUSE', 'OFF_TOPIC', 'UNVERIFIABLE']})
    selected = {'experience_id': uuid, 'archive_id': ident, 'source_file_id': ident, 'copy_file_id': ident}
    s['PublicImageSelection'] = strict(selected)
    s['PublicImageSource'] = strict({'archive_id': ident, 'source_file_id': ident})
    s['PublicImageSourcePage'] = paged('PublicImageSource')
    s['PublicImagePreview'] = strict({'preview_hash': digest, 'png_base64': b64, **dims, 'model_id': ident})
    s['PublicImageSubmit'] = strict({**selected, 'preview_hash': digest, 'consent_version': {'const': 'R8C_IMAGE_V1'}, 'consent': {'const': True}})
    s['PublicImageReceipt'] = strict(receipt)
    image_row = {**receipt, 'preview_hash': digest, 'experience_id': uuid, 'model_id': ident, **dims, 'created_at': stamp, 'reason_code': reason, 'source_available': boolean}
    s['PublicImageOwner'] = strict({**image_row, 'public_id': nullable(uuid), 'visible': boolean})
    s['PublicImagePending'] = strict({**image_row, 'status': {'const': 'PENDING'}})
    s['PublicImageOwnerPage'] = paged('PublicImageOwner')
    s['PublicImagePendingPage'] = paged('PublicImagePending')
    s['PublicImageBytes'] = strict({'png_base64': b64})
    s['PublicImageList'] = strict({'vehicle_id': ident, 'experience_id': uuid, 'items': {'type': 'array', 'maxItems': 20, 'items': strict({'public_id': uuid, **dims, 'published_at': stamp})}})
    payload = {'title': text(120), 'publisher': text(120), 'document_version': text(80), 'source_reference': text(500), 'license_reference': text(500), 'license_confirmed': boolean, 'model_id': ident, 'valid_from': stamp, 'valid_to': stamp, 'search_allowed': boolean, 'excerpt_allowed': boolean, 'ai_allowed': boolean, 'body': text(10000)}
    s['KnowledgePayload'] = {'oneOf':[strict(payload),strict({**payload,'embedding_allowed':boolean,'embedding_policy_version':{'const':'KNOWLEDGE_EMBEDDING_V1'}})],'description':'旧13字段payload原样保存/哈希，嵌入默认未授权；新授权必须同时提交embedding_allowed布尔和策略版本。仅本地私有嵌入，不推导外部模型许可；默认公开检索KEYWORD，显式开启及完整性通过后可用VECTOR。'}
    s['KnowledgeUpdate'] = {'oneOf':[strict({**payload,'expected_revision':ident}),strict({**payload,'embedding_allowed':boolean,'embedding_policy_version':{'const':'KNOWLEDGE_EMBEDDING_V1'},'expected_revision':ident})]}
    s['KnowledgeStatus'] = strict({'expected_revision': ident, 'status': {'enum': ['PUBLISHED', 'STOPPED']}})
    s['KnowledgeDocument'] = strict({'id': ident, 'revision': ident, 'status': {'enum': ['DRAFT', 'PUBLISHED', 'STOPPED']}, 'source_hash': digest, 'public_id': nullable(uuid), 'payload': ref('KnowledgePayload'), 'created_at': stamp, 'published_at': nullable(stamp)})
    status_fields = {'state': {'enum': ['DISABLED','NOT_ALLOWED','NOT_QUEUED','QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED']}, 'error_code': nullable({'enum': ['WORKER_LOST','UNAVAILABLE','INVALID_VECTOR','LIMIT_EXCEEDED','SOURCE_CHANGED','FEATURE_DISABLED']}), 'profile_hash': nullable(digest), 'updated_at': nullable(stamp)}
    s['KnowledgeEmbeddingState'] = strict(status_fields)
    s['KnowledgeEmbeddingState']['description'] = '仅运营可见的当前配置索引状态，非公开VECTOR或领域效果证明；FAILED不自动重试。DISABLED其余字段为空；NOT_ALLOWED/NOT_QUEUED保留profile且时间/错误为空；任务状态须有更新时间，成功/排队/运行无错误。'
    s['KnowledgeAdminRow'] = {'oneOf': [ref('KnowledgeDocument'), strict({**s['KnowledgeDocument']['properties'], 'embedding': ref('KnowledgeEmbeddingState')})]}
    s['KnowledgeAdminPage'] = paged('KnowledgeAdminRow')
    s['KnowledgeModel'] = strict({'model_id': ident, **{k: nullable(text(256)) for k in ['brand', 'series', 'year', 'config_name']}})
    s['KnowledgeModelPage'] = paged('KnowledgeModel')
    public = {'reference': uuid, 'revision': ident, 'model_id': ident, 'title': text(120), 'publisher': text(120), 'document_version': text(80), 'source_reference': text(500), 'valid_from': stamp, 'valid_to': stamp, 'excerpt': text(800), 'ai_allowed': boolean}
    s['KnowledgeReference'] = strict(public)
    chunk = {'chunk': {'type':'integer','minimum':0,'maximum':63}, 'start_offset':{'type':'integer','minimum':0,'maximum':19999}, 'end_offset':{'type':'integer','minimum':1,'maximum':20000}}
    vector_public = {**public, 'excerpt':text(256), **chunk}
    s['KnowledgeVectorReference'] = strict(vector_public)
    fallback = {'enum':['INDEX_NOT_READY','VECTOR_UNAVAILABLE','QUERY_LIMIT','CURSOR_PAGING']}
    keyword_search = strict({'vehicle_id':ident,'state':{'enum':['AVAILABLE','UNKNOWN','NO_MATCH','UNAVAILABLE']},'method':{'const':'KEYWORD'},'items':{'type':'array','maxItems':20,'items':ref('KnowledgeReference')},'next_cursor':nullable(ident)})
    keyword_search['properties']['fallback_reason'] = fallback
    vector_search = strict({'vehicle_id':ident,'state':{'const':'AVAILABLE'},'method':{'const':'VECTOR'},'items':{'type':'array','minItems':1,'maxItems':5,'items':ref('KnowledgeVectorReference')},'next_cursor':{'type':'null'}})
    s['KnowledgeSearch'] = {'oneOf':[keyword_search,vector_search],'description':'默认KEYWORD兼容旧响应；明确开启且当前范围语料/许可/完整索引全部就绪才VECTOR。KEYWORD可带明确降级原因；VECTOR无游标/降级原因/分数，最多5份。'}
    s['KnowledgeDetail'] = strict({'vehicle_id': ident, 'document': {'oneOf':[strict({**public,'body':text(800)}),strict({**vector_public,'body':text(256)})]}})
    s['KnowledgeQuestion'] = strict({'vehicle_id': ident, 'question': text(1000)})
    def answer(method,reference):
        return strict({'vehicle_id':ident,'reply':text(20000),'model':text(256),'provided_sources':{'type':'array','minItems':1,'maxItems':3,'items':ref(reference)},'method':{'const':method}})
    keyword_answer=answer('KEYWORD','KnowledgeReference')
    keyword_answer['properties']['fallback_reason']={'enum':['INDEX_NOT_READY','VECTOR_UNAVAILABLE','QUERY_LIMIT']}
    s['KnowledgeAnswer'] = {'oneOf':[keyword_answer,answer('VECTOR','KnowledgeVectorReference')],'description':'只接收允许AI的当前实际摘录，返回前重验当前车主/车型/来源及片段；失效丢弃回答。provided_sources是实际提供的资料，不表示每句话事实已核实。'}
    auth = s['OperatorAuthenticatedUser']
    auth['properties']['can_knowledge'] = boolean
    auth['required'].append('can_knowledge')
    routes = [
        ('get', '/api/owner/community/image-sources', 'PublicImageSourcePage', None, True),
        ('post', '/api/owner/community/images/preview', 'PublicImagePreview', 'PublicImageSelection', False),
        ('get', '/api/owner/community/images', 'PublicImageOwnerPage', None, True),
        ('post', '/api/owner/community/images', 'PublicImageReceipt', 'PublicImageSubmit', False),
        ('post', '/api/owner/community/images/{id}/withdraw', 'PublicImageReceipt', 'PublicQaWithdraw', False),
        ('get', '/api/owner/community/images/{id}/preview', 'PublicImageBytes', None, False),
        ('get', '/api/admin/community/images', 'PublicImagePendingPage', None, True),
        ('get', '/api/admin/community/images/{id}/preview', 'PublicImageBytes', None, False),
        ('post', '/api/admin/community/images/{id}/moderate', 'PublicImageReceipt', 'PublicQaModerate', False),
        ('get', '/api/community/images', 'PublicImageList', None, False),
        ('get', '/api/community/images/{publicId}', 'PublicImageBytes', None, False),
        ('get', '/api/admin/knowledge/models', 'KnowledgeModelPage', None, True),
        ('get', '/api/admin/knowledge/documents', 'KnowledgeAdminPage', None, True),
        ('post', '/api/admin/knowledge/documents', 'KnowledgeDocument', 'KnowledgePayload', False),
        ('put', '/api/admin/knowledge/documents/{id}', 'KnowledgeDocument', 'KnowledgeUpdate', False),
        ('post', '/api/admin/knowledge/documents/{id}/status', 'KnowledgeDocument', 'KnowledgeStatus', False),
        ('get', '/api/knowledge/search', 'KnowledgeSearch', None, False),
        ('get', '/api/knowledge/documents/{reference}', 'KnowledgeDetail', None, False),
        ('post', '/api/knowledge/answer', 'KnowledgeAnswer', 'KnowledgeQuestion', False),
    ]
    for method, path, result, body, paging in routes:
        item = operation(method, path, '独立图片公开副本' if '/images' in path or '/image-sources' in path else '许可资料治理与可追溯关键词检索', 'F12,F20')
        item['x-roles'] = ('OPERATOR(can_knowledge)' if '/knowledge/' in path else 'OPERATOR(can_review)') if '/admin/' in path else 'OWNER'
        readonly = method == 'get' or path.endswith('/preview') or path.endswith('/answer')
        item['x-read-only'] = readonly
        item['x-implementation-status'] = 'r8-remaining-implemented'
        item['parameters'] = [{'name': name, 'in': 'path', 'required': True, 'schema': ident if name == 'id' else uuid} for name in ['id', 'publicId', 'reference'] if '{' + name + '}' in path]
        if paging:
            item['parameters'] += [{'name': 'page', 'in': 'query', 'schema': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}}, {'name': 'page_size', 'in': 'query', 'schema': {'const': 20}}]
        if path == '/api/owner/community/image-sources' or path == '/api/community/images':
            item['parameters'].append({'name': 'experience_id', 'in': 'query', 'required': True, 'schema': uuid})
        if path.startswith('/api/community/images') or path in ['/api/knowledge/search', '/api/knowledge/documents/{reference}']:
            item['parameters'].append({'name': 'vehicle_id', 'in': 'query', 'required': True, 'schema': ident})
        if path == '/api/knowledge/documents/{reference}':
            item['parameters'].append({'name':'chunk','in':'query','schema':{'type':'integer','minimum':0,'maximum':63},'description':'可选Unicode分块序号，响应给出精确UTF16偏移及摘录；不传保留前800字详情。'})
        if path == '/api/knowledge/search':
            item['parameters'] += [{'name': 'q', 'in': 'query', 'required': True, 'schema': text(80)}, {'name': 'cursor', 'in': 'query', 'schema': ident}]
        if result == 'PublicImageBytes':
            item['parameters'].append({'name': 'format', 'in': 'query', 'schema': {'enum': ['base64']}})
        if body:
            item['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref(body)}}}
            if not readonly:
                item['parameters'].append({'name': 'Idempotency-Key', 'in': 'header', 'required': True, 'schema': uuid})
        item['responses']['200']['content'] = {'application/json': {'schema': {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': ref(result)}}]}}}
        if result == 'PublicImageBytes':
            item['responses']['200']['content']['image/png'] = {'schema': {'type': 'string', 'format': 'binary'}}
        item['description'] = ('no-store。V060/V061，130表，17字段运营登录。图片独立逐张授权、另传脱敏副本，重新编码PNG移除元数据；原图不公开。实际图像隐私由车主与人工审核确认，父内容/来源/当前同款失效拒绝新读取；每父经验最多20张有效申请。'
                               '图片副本最多2MiB/600万像素/4096边长，原始读取有10MiB上限；二进制默认响应或format=base64兼容云托管JSON。'
                               '资料独立can_knowledge，许可/摘录/AI分别确认，发布冻结、停止终止当前读取，不提供内部许可凭证。默认检索KEYWORD；KNOWLEDGE_VECTOR_ENABLED显式开启后，当前范围全部许可/索引完整才VECTOR，否则带原因降级。段落引用可独立核验，AI只发送实际许可摘录并在调用后重验；不宣称领域准确率或Milvus接入。'
                               'PUBLIC_IMAGES_ENABLED/KNOWLEDGE_ENABLED/KNOWLEDGE_AI_ENABLED默认false，正式身份/供应商/真机/发布仍最后R9。')
        d['paths'].setdefault(path, {})[method] = item
    for path, methods in d['paths'].items():
        if '/community/question' in path:
            for item in methods.values():
                if isinstance(item, dict) and item.get('x-implementation-status') == 'r8c1b3a-public-qa-backend-implemented':
                    item['x-implementation-status'] = 'r8c1b3-public-qa-ui-and-backend-implemented'
                    item['description'] = item.get('description', '').replace('后端已实现，页面/本机恢复下一b3b；无公开图片、通知投递或正式身份接入声明。', '后端、实际页面及本机恢复已实现；图片走另行授权副本API，不声明正式身份接入。')
