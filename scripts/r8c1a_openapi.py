"""Controlled, currently authorized same-model experience reference detail."""


def add_r8c1a(d, operation):
    schemas = d['components']['schemas']
    ref = lambda name: {'$ref': '#/components/schemas/' + name}
    ident = {'type': 'integer', 'minimum': 1, 'maximum': 9007199254740991}
    public_id = {'type': 'string', 'format': 'uuid',
                 'pattern': '^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$'}
    schemas['PublicExperienceDetail'] = {
        'type': 'object', 'additionalProperties': False,
        'required': ['vehicle_id', 'experience'],
        'properties': {'vehicle_id': ident, 'experience': ref('PublicExperience')}}
    path = '/api/community/experiences/{id}'
    item = operation('get', path, '当前获批同款经验的受控详情与引用', 'F12')
    item['x-roles'] = 'OWNER'
    item['x-read-only'] = True
    item['x-implementation-status'] = 'r8c1a-experience-detail-implemented'
    item['parameters'] = [
        {'name': 'id', 'in': 'path', 'required': True, 'schema': public_id},
        {'name': 'vehicle_id', 'in': 'query', 'required': True, 'schema': ident}]
    item['description'] = (
        'no-store，只读REPEATABLE_READ；精确公开UUID和单一必填本人vehicle_id，拒绝其他选择器。'
        '列表/详情共用当前有效同款/车主授权/审核修订/可信施工来源判断，双方车辆归属、原作者及车型/车系/品牌有效性重验，TEST永久排除。'
        '只返回原五字段公开摘要与接收者vehicle_id，不含私有来源或图片。撤回/重新授权审批使旧UUID失效；源变更/停用/转移/未知均404。'
        '公开编号不构成授权，复制应用内入口仅含UUID；接收者必须登录、选择本人同款车辆后重读。'
        '默认EXPERIENCE_PUBLICATION_ENABLED=false，关闭时详情503、原列表仍空。读取无写副作用；详见EXPERIENCE_DETAIL.md。')
    item['responses']['200']['content']['application/json']['schema'] = {
        'allOf': [ref('ApiResponse'), {'type': 'object', 'required': ['data'],
                                    'properties': {'data': ref('PublicExperienceDetail')}}]}
    item['responses'].update({'404': {'description': '本人车辆、当前同款授权/审核或可信来源不可用'},
                              '503': {'description': '发布开关关闭、数据库未配置或依赖暂不可读取'}})
    d['paths'][path] = {'get': item}
    for method, route in [('get', '/api/community/experiences'),
                          ('post', '/api/admin/experience-cards/{id}/moderate')]:
        d['paths'][route][method]['description'] += ' R8c1a同步核对车型/车系/品牌status=1，单条引用详情与列表共用当前有效来源判断。'
