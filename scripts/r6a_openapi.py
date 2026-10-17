"""R6a invitation provenance; no public new-user qualification mutation."""
def add_r6a(document, operation):
    s = document['components']['schemas']
    strict = lambda p: {'type': 'object', 'additionalProperties': False, 'required': list(p), 'properties': p}
    ref = lambda n: {'$ref': '#/components/schemas/' + n}
    nullable = lambda n: {'anyOf': [n, {'type': 'null'}]}
    integer = lambda a, b: {'type': 'integer', 'minimum': a, 'maximum': b}
    count = integer(0, 9007199254740991)
    utc = {'type': 'string', 'format': 'date-time'}
    code = {'type': 'string', 'pattern': '^[0-9a-f]{32}$'}
    kind = {'enum': ['OWNER', 'STAFF']}
    rule = {'const': 'R6A_INVITE_V1'}
    s['InviteCode'] = strict({'value': code, 'source_type': kind, 'created_at': utc, 'landing_path': {'type': 'string', 'pattern': '^/pages/welfare/invite-entry\\?code=[0-9a-f]{32}$'}, 'rule_version': rule})
    s['InviteCodeView'] = strict({'code': nullable(ref('InviteCode'))})
    s['InviteCodeCreated'] = strict({'code': ref('InviteCode')})
    s['InviteCodeInput'] = strict({})
    s['InviteRecord'] = strict({'record_id': {'type': 'string', 'format': 'uuid'}, 'source_type': nullable(kind), 'identity_source': {'enum': ['VERIFIED_WECHAT', 'UNKNOWN', 'TEST']},
        'attribution_result': {'enum': ['ATTRIBUTED', 'NO_CODE', 'INVALID_CODE', 'SELF', 'SOURCE_UNAVAILABLE']}, 'qualification_status': {'enum': ['PENDING', 'UNKNOWN', 'TEST', 'QUALIFIED', 'REJECTED']},
        'qualification_reason': nullable({'enum': ['PHONE_SEEN', 'SOURCE_UNAVAILABLE']}), 'registered_at': utc, 'qualified_at': nullable(utc), 'reward_month': nullable({'type': 'string', 'pattern': '^\\d{4}-(0[1-9]|1[0-2])$'}), 'rule_version': rule})
    s['InviteRecordPage'] = strict({'items': {'type': 'array', 'maxItems': 20, 'items': ref('InviteRecord')}, 'total': count, 'page': integer(1, 1000000), 'page_size': {'const': 20}})
    s['InviteRegistrationView'] = strict({'registration': nullable(ref('InviteRecord'))})
    s['InviteProgress'] = strict({'qualified_count': count, 'completed_cycles': count, 'tier': integer(1,4), 'tier_count': integer(0,3), 'tier_target': integer(1,4), 'next_threshold': integer(1,9007199254740991),
        'month': {'type': 'string', 'pattern': '^\\d{4}-(0[1-9]|1[0-2])$'}, 'timezone': {'const': 'Asia/Shanghai'}, 'verified_identity_enabled': {'type': 'boolean'}, 'rule_version': rule,
        'pending_total': count, 'unknown_total': count, 'test_total': count, 'rejected_total': count})
    for prefix, roles in [('/api/invite', 'OWNER'), ('/api/merchant/invite', 'MERCHANT,STAFF')]:
        for method, suffix, result, title in [('get', '/qrcode' if roles=='OWNER' else '/code', 'InviteCodeView', '读取本人推广码（不创建）'), ('post','/code','InviteCodeCreated','幂等生成本人推广码'), ('get','/records','InviteRecordPage','本人邀请记录分页'), ('get','/progress','InviteProgress','北京时间四档月度循环')]:
            path=prefix+suffix
            item=operation(method,path,title,'F10')
            item['x-roles']=roles
            item['x-implementation-status']='r6a-invitation-attribution-implemented'
            item['description']='no-store；当前本人身份及门店复核。仅首次注册固定归因，客户端不能提交新客/验证状态。有效资格为同app服务端注册与手机号证据，UNKNOWN/TEST不计正式人数。每月新增1/2/3/4，累计1/3/6/10后重复；非正式渠道默认关闭。双向奖励在R6b接入，真实身份/原生分享R9验收。见INVITATIONS.md。'
            if method=='post': item['requestBody']={'required':True,'content':{'application/json':{'schema':ref('InviteCodeInput')}}}
            if suffix=='/records': item['parameters'] += [{'name':'page','in':'query','schema':integer(1,1000000)}, {'name':'page_size','in':'query','schema':{'const':20}}]
            item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','required':['data'],'properties':{'data':ref(result)}}]}
            item['responses']['503']={'description':'数据库暂不可用；写入保留原键重试'}
            document['paths'].setdefault(path,{})[method]=item
    path='/api/invite/registration'
    item=operation('get',path,'本人首次注册资格与归因','F10')
    item['x-roles']='OWNER';item['x-implementation-status']='r6a-invitation-attribution-implemented'
    item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','required':['data'],'properties':{'data':ref('InviteRegistrationView')}}]}
    document['paths'][path]={'get':item}
    wx=document['paths']['/api/auth/wx-login']['post']['requestBody']['content']['application/json']['schema']
    wx['properties']['invite_code']=code
    wx['properties']['role']={'enum':['owner','technician']}
    wx['properties']['code']={'type':'string','minLength':1,'maxLength':512}
    wx['description']='invite_code仅车主可提交，只在首次注册记录，不允许事后补填。严格重复字段/尾随JSON拒绝。'
    cloud=operation('post','/api/auth/cloud-login','显式启用的云托管登录与未知来源归因','F01,F10',False)
    cloud['requestBody']={'required':True,'content':{'application/json':{'schema':{'type':'object','additionalProperties':False,'required':['role'],'properties':{'role':{'enum':['owner','technician']},'invite_code':code}}}}}
    cloud['description']='WECHAT_CLOUD_RUN_ENABLED默认关闭。云托管新注册邀请身份证据为UNKNOWN，不计正式新客；网关边界实际验证后在R9接入。invite_code仅车主。'
    document['paths']['/api/auth/cloud-login']={'post':cloud}
