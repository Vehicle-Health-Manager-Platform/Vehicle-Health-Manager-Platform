"""Source-based operator curation, independent authority, and current owner projection."""


def add_r8c1b1(d, operation):
    s = d['components']['schemas']
    ref = lambda n: {'$ref': '#/components/schemas/' + n}
    strict = lambda fields: {'type': 'object', 'additionalProperties': False, 'required': list(fields), 'properties': fields}
    nullable = lambda schema: {'anyOf': [schema, {'type': 'null'}]}
    ident = {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740991}
    stamp = {'type': 'string', 'format': 'date-time', 'description': '规范UTC整秒。'}
    public_id = {'type': 'string', 'format': 'uuid', 'pattern': '^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$'}
    source_hash = {'type': 'string', 'pattern': '^[a-f0-9]{64}$'}
    priority = {'type': 'integer', 'minimum': 1, 'maximum': 1000000}
    s['OperatorAuthenticatedUser']['properties']['can_community_curate'] = {'type': 'boolean'}
    s['OperatorAuthenticatedUser']['required'].append('can_community_curate')
    s['CurationSource'] = strict({'experience': ref('PublicExperience'), 'source_hash': source_hash})
    fields = {'experience_id': public_id, 'source_hash': source_hash, 'priority': priority,
              'source_note': {'type': 'string', 'minLength': 2, 'maxLength': 256, 'description': '仅独立运营可读的来源及排序依据。'},
              'scope_confirmed': {'const': True}, 'valid_from': stamp, 'valid_to': stamp}
    s['CurationCreate'] = strict(fields)
    s['CurationUpdate'] = strict({**fields, 'expected_revision': ident})
    s['CurationStatus'] = strict({'expected_revision': ident, 'status': {'enum': ['PUBLISHED', 'STOPPED']}})
    s['ExperienceCuration'] = strict({**fields, 'id': ident, 'source_snapshot': ref('PublicExperience'),
                                    'revision': ident, 'status': {'enum': ['DRAFT', 'PUBLISHED', 'STOPPED']},
                                    'rule_version': {'const': 'R8C_CURATED_V1'}, 'created_at': stamp,
                                    'published_at': nullable(stamp), 'stopped_at': nullable(stamp)})
    for name, item in [('CurationSourcePage', 'CurationSource'), ('CurationPage', 'ExperienceCuration')]:
        s[name] = strict({'items': {'type': 'array', 'maxItems': 20, 'items': ref(item)},
                          'total': {'type': 'integer', 'minimum': 0},
                          'page': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}, 'page_size': {'const': 20}})
    s['CuratedExperience'] = strict({'curation_id': ident, 'revision': ident, 'priority': priority,
                                    'valid_from': stamp, 'valid_to': stamp, 'experience': ref('PublicExperience')})
    s['OwnerCuratedExperiences'] = strict({'vehicle_id': ident, 'model_id': nullable(ident),
                                          'selection_type': {'const': 'OPERATOR_CURATED'}, 'observed_at': stamp,
                                          'state': {'enum': ['UNKNOWN', 'UNAVAILABLE', 'CONFIGURED']},
                                          'items': {'type': 'array', 'maxItems': 20, 'items': ref('CuratedExperience')}})
    routes = [('get', '/api/admin/community/curation-sources', 'CurationSourcePage', None, True),
              ('get', '/api/admin/community/curations', 'CurationPage', None, True),
              ('post', '/api/admin/community/curations', 'ExperienceCuration', 'CurationCreate', False),
              ('get', '/api/admin/community/curations/{id}', 'ExperienceCuration', None, False),
              ('put', '/api/admin/community/curations/{id}', 'ExperienceCuration', 'CurationUpdate', False),
              ('put', '/api/admin/community/curations/{id}/status', 'ExperienceCuration', 'CurationStatus', False),
              ('get', '/api/community/hot', 'OwnerCuratedExperiences', None, False)]
    for method, path, result, body, paged in routes:
        owner = path == '/api/community/hot'
        item = operation(method, path, '有来源运营精选' if not owner else '本人同款运营精选（非浏览热度）', 'F12,F20')
        item['x-roles'] = 'OWNER' if owner else 'OPERATOR(can_community_curate)'
        item['x-implementation-status'] = 'r8c1b1-curation-implemented'
        item['description'] = ('no-store，V057/117表，独立默认关闭精选权限及14字段登录。当前获批授权/非TEST/可信源五字段、服务器哈希/快照，来源/排序/未来窗口确认、CAS草稿/冻结发布/停止、同公开UUID一发布版本、永久创建原键与审计原子。'
                               '来源分页total为基础候选数，可信来源过滤后本页可少于20；来源关闭503不阻止既有详情/停止/永久原创建回执。'
                               '车主本人车辆/有效同款、单只读观察时间，排序优先级升序/同值新id在前，最多20在窗口发布候选，原发布最后有效审计和当前源逐条复核，失效隐藏；空UNKNOWN，展示关闭UNAVAILABLE。'
                               '公开投影无运营说明/哈希/私有标识，不生成热度事件，详见CURATED_EXPERIENCES.md。')
        item['parameters'] = ([{'name': 'vehicle_id', 'in': 'query', 'required': True, 'schema': ident}] if owner else
                              [{'name': 'id', 'in': 'path', 'required': True, 'schema': ident}] if '{id}' in path else [])
        if paged:
            item['parameters'] += [{'name': 'page', 'in': 'query', 'schema': {'type': 'integer', 'minimum': 1, 'maximum': 1000000}},
                                   {'name': 'page_size', 'in': 'query', 'schema': {'const': 20}}]
        if body:
            item['parameters'].append({'name': 'Idempotency-Key', 'in': 'header', 'required': True, 'schema': {'type': 'string', 'format': 'uuid'}})
            item['requestBody'] = {'required': True, 'content': {'application/json': {'schema': ref(body)}}}
        else:
            item['x-read-only'] = True
        item['responses']['200']['content']['application/json']['schema'] = {'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'], 'properties': {'data': ref(result)}}]}
        item['responses'].update({'404': {'description': '当前获批来源、本人车辆或精选不存在'},
                                  '409': {'description': '来源/版本/未来窗口已变或同一公开UUID已有发布'},
                                  '503': {'description': '数据库、展示开关/依赖暂不可用；写使用原请求恢复'}})
        d['paths'].setdefault(path, {})[method] = item
