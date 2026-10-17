"""Verified event capture, ledger reads and fixed-day check-in; exchange remains deferred."""
def add_r8a2(d, operation):
    s=d['components']['schemas'];ref=lambda n:{'$ref':'#/components/schemas/'+n}
    strict=lambda p:{'type':'object','additionalProperties':False,'required':list(p),'properties':p}
    null=lambda v:{'anyOf':[v,{'type':'null'}]}
    ident={'type':'integer','minimum':1,'maximum':9007199254740991};count={'type':'integer','minimum':0,'maximum':9007199254740991};stamp={'type':'string','format':'date-time'}
    task={'enum':['CHECK_IN','ARCHIVE_FIRST_COMPLETE','AI_FIRST_SUCCESS']};day={'type':'string','format':'date'}
    s['PointTaskContext']['properties']['earning_available']={'type':'boolean'}
    d['paths']['/api/point/tasks']['get']['description']='no-store，当前车主及会话复核。三任务来源/分值与窗口；earning_available取决于获分开关及当前同app已核验身份，不等于每项任务已完成或已到账，详见overview。'
    s['PointCheckinInput']=strict({'day':day,'policy_id':ident,'policy_revision':ident})
    s['PointCheckinReceipt']=strict({'event_id':ident,'day':day,'status':{'enum':['PENDING','CREDITED']},'points':{'type':'integer','minimum':1,'maximum':1000000}})
    s['PointOwnerTask']=strict({'task_type':task,'frequency':{'enum':['DAILY','LIFETIME']},'policy':null(ref('PointTaskPolicy')),'state':{'enum':['UNKNOWN','NOT_STARTED','EXPIRED','IDENTITY_UNVERIFIED','SOURCE_UNAVAILABLE','READY','PENDING','CREDITED']},'points_awarded':{'type':'integer','minimum':0,'maximum':1000000},'occurred_at':null(stamp),'credited_at':null(stamp)})
    s['PointOwnerTask']['properties']['policy']['description']='PENDING/CREDITED为登记时快照（状态代表当时）；其他状态为当前政策。'
    s['PointLedgerOverview']=strict({'balance':count,'balance_scope':{'const':'VERIFIED_TASKS_V1'},'legacy_state':{'enum':['NONE','REVIEW_REQUIRED']},'earning_available':{'type':'boolean'},'day':day,'timezone':{'const':'Asia/Shanghai'},'tasks':{'type':'array','minItems':3,'maxItems':3,'items':ref('PointOwnerTask')}})
    s['PointLedgerEntry']=strict({'id':ident,'delta':{'type':'integer','minimum':1,'maximum':1000000},'balance_after':count,'created_at':stamp,'task_type':task,'period_key':{'type':'string'},'policy_id':ident,'policy_revision':ident})
    s['PointLedgerPage']=strict({'items':{'type':'array','maxItems':20,'items':ref('PointLedgerEntry')},'total':count,'page':{'type':'integer','minimum':1,'maximum':1000000},'page_size':{'const':20}})
    for method,path,result,body in [('get','/api/point/overview','PointLedgerOverview',None),('get','/api/point/flow','PointLedgerPage',None),('post','/api/point/check-in','PointCheckinReceipt','PointCheckinInput')]:
        item=operation(method,path,'本人已核验积分与签到','F11');item['x-roles']='OWNER';item['x-implementation-status']='r8a2-ledger-implemented'
        item['description']='no-store，当前会话和本人范围。只读新可信账本，旧余额/流水不清零不导入，存在历史值显示待核对。签到锁等待后冻结北京日期/当前政策版本和时点，原键永久意图防跨日/24h回执过期重复；PENDING不代表到账，须刷新overview。源事务事件及单事件事务记账/余额/审计原子，可重启恢复，多消费者唯一。正式身份/AI/获分开关默认关闭，无历史扫描补发或客户端资格声明。'
        if body:item['requestBody']={'required':True,'content':{'application/json':{'schema':ref(body)}}}
        if path.endswith('/flow'):item['parameters'] += [{'name':'page','in':'query','schema':{'type':'integer','minimum':1,'maximum':1000000}},{'name':'page_size','in':'query','schema':{'const':20}}]
        item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','required':['data'],'properties':{'data':ref(result)}}]}
        item['responses'].update({'409':{'description':'日期/规则/身份来源变化，重读后明确确认'},'503':{'description':'数据库/审计暂不可用，保留原日期原请求重试'}})
        d['paths'].setdefault(path,{})[method]=item
