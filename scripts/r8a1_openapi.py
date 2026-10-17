"""R8a1 task policy configuration; no points earning or exchange is claimed."""
def add_r8a1(d, operation):
    s=d['components']['schemas'];ref=lambda n:{'$ref':'#/components/schemas/'+n}
    strict=lambda p:{'type':'object','additionalProperties':False,'required':list(p),'properties':p}
    null=lambda n:{'anyOf':[n,{'type':'null'}]}
    ident={'type':'integer','minimum':1,'maximum':9007199254740991}
    stamp={'type':'string','format':'date-time'}
    task={'enum':['CHECK_IN','ARCHIVE_FIRST_COMPLETE','AI_FIRST_SUCCESS']}
    fields={'task_type':task,'points':{'type':'integer','minimum':1,'maximum':1000000},'source_note':{'type':'string','minLength':2,'maxLength':256},'valid_from':stamp,'valid_to':stamp}
    s['PointPolicyInput']=strict(fields)
    s['PointPolicyUpdate']=strict({**fields,'expected_revision':ident})
    s['PointPolicyStatus']=strict({'expected_revision':ident,'status':{'enum':['PUBLISHED','STOPPED']}})
    s['PointTaskPolicy']=strict({**fields,'id':ident,'revision':ident,'status':{'enum':['DRAFT','PUBLISHED','STOPPED']},'rule_version':{'const':'R8A_TASK_V1'},'frequency':{'enum':['DAILY','LIFETIME']},'timezone':{'const':'Asia/Shanghai'},'created_at':stamp,'published_at':null(stamp)})
    s['PointTaskPolicy']['allOf']=[{'if':{'properties':{'task_type':{'const':'CHECK_IN'}}},'then':{'properties':{'frequency':{'const':'DAILY'}}},'else':{'properties':{'frequency':{'const':'LIFETIME'}}}}]
    s['PointPolicyPage']=strict({'items':{'type':'array','maxItems':20,'items':ref('PointTaskPolicy')},'total':{'type':'integer','minimum':0},'page':{'type':'integer','minimum':1,'maximum':1000000},'page_size':{'const':20}})
    s['PointTaskContext']=strict({'tasks':{'type':'array','minItems':3,'maxItems':3,'items':strict({'task_type':task,'frequency':{'enum':['DAILY','LIFETIME']},'state':{'enum':['UNKNOWN','NOT_STARTED','EXPIRED','CONFIGURED']},'policy':null(ref('PointTaskPolicy'))})},'earning_available':{'const':False},'timezone':{'const':'Asia/Shanghai'}})
    routes=[('get','/api/point/tasks','PointTaskContext',None),('get','/api/admin/point/task-policies','PointPolicyPage',None),('post','/api/admin/point/task-policies','PointTaskPolicy','PointPolicyInput'),('put','/api/admin/point/task-policies/{id}','PointTaskPolicy','PointPolicyUpdate'),('put','/api/admin/point/task-policies/{id}/status','PointTaskPolicy','PointPolicyStatus')]
    for method,path,result,body in routes:
        item=operation(method,path,'积分任务来源配置与当前规则','F11');item['x-roles']='OWNER' if path=='/api/point/tasks' else 'OPERATOR(can_points)';item['x-implementation-status']='r8a1-policy-implemented'
        item['description']='no-store，独立can_points默认关闭。三任务固定频率，分值/来源/时间可配置，无种子。草稿CAS，发布冻结、每任务最多一个发布版本，停止不重开。新建永久提交键/正文哈希/首次结果防24h通用回执过期重复，所有回执重验权限。当前earning_available=false；不记账、不导入旧余额、不补历史、不发券。'
        for p in item['parameters']:
            if p['in']=='path':p['schema']=ident
        if method=='get' and path.endswith('/task-policies'):
            item['parameters'] += [{'name':'page','in':'query','schema':{'type':'integer','minimum':1,'maximum':1000000}},{'name':'page_size','in':'query','schema':{'const':20}}]
        if method=='put':item['parameters'].append({'name':'Idempotency-Key','in':'header','required':True,'schema':{'type':'string','format':'uuid'}})
        if body:item['requestBody']={'required':True,'content':{'application/json':{'schema':ref(body)}}}
        item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','required':['data'],'properties':{'data':ref(result)}}]}
        item['responses'].update({'404':{'description':'规则不可用'},'409':{'description':'版本、有效期或发布唯一性冲突'},'503':{'description':'数据库或审计不可用；保留原请求重试'}})
        d['paths'].setdefault(path,{})[method]=item
