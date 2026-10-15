"""Current-month diagnosis eligibility and independently authorized policies."""
def add_r6b3(d,operation):
    s=d['components']['schemas'];ref=lambda n:{'$ref':'#/components/schemas/'+n};strict=lambda p:{'type':'object','additionalProperties':False,'required':list(p),'properties':p};null=lambda p:{'anyOf':[p,{'type':'null'}]}
    identifier={'type':'integer','minimum':1,'maximum':9007199254740991};utc={'type':'string','format':'date-time'}
    fields={'required_count':{'type':'integer','minimum':1,'maximum':1000000},'source_note':{'type':'string','minLength':2,'maxLength':256},'valid_from':utc,'valid_to':utc}
    s['InviteDiagnosisInput']=strict(fields);s['InviteDiagnosisUpdate']=strict({**fields,'expected_revision':{'type':'integer','minimum':0,'maximum':9007199254740990}})
    s['InviteDiagnosisPolicy']=strict({**fields,'id':identifier,'revision':identifier,'status':{'enum':['DRAFT','PUBLISHED','STOPPED']},'created_at':utc,'published_at':null(utc),'rule_version':{'const':'R6B_DIAGNOSIS_V1'}})
    s['InviteDiagnosisPage']=strict({'items':{'type':'array','maxItems':20,'items':ref('InviteDiagnosisPolicy')},'page':{'type':'integer','minimum':1,'maximum':1000000},'page_size':{'const':20},'total':{'type':'integer','minimum':0}})
    s['InviteDiagnosisEligibility']=strict({'month':{'type':'string','pattern':'^[0-9]{4}-(0[1-9]|1[0-2])$'},'timezone':{'const':'Asia/Shanghai'},'qualified_count':{'type':'integer','minimum':0},'state':{'enum':['UNKNOWN','NOT_STARTED','EXPIRED','LOCKED','ELIGIBLE']},'policy':null(ref('InviteDiagnosisPolicy')),'usable_until':null(utc),'service_available':{'const':False},'rule_version':{'const':'R6B_DIAGNOSIS_V1'}})
    for method,path,result,body in [('get','/api/invite/diagnosis-eligibility','InviteDiagnosisEligibility',None),('get','/api/admin/invite/diagnosis-policies','InviteDiagnosisPage',None),('post','/api/admin/invite/diagnosis-policies','InviteDiagnosisPolicy','InviteDiagnosisInput'),('put','/api/admin/invite/diagnosis-policies/{id}','InviteDiagnosisPolicy','InviteDiagnosisUpdate'),('put','/api/admin/invite/diagnosis-policies/{id}/status','InviteDiagnosisPolicy','InvitePolicyStatusInput')]:
        item=operation(method,path,'当月邀请来源配置与非实时诊断资格','F10');item['x-roles']='OWNER' if path.endswith('diagnosis-eligibility') else 'OPERATOR(can_invite)';item['x-implementation-status']='r6b3-diagnosis-eligibility-implemented'
        item['description']='no-store。独立can_invite默认关闭，草稿/发布/停止、CAS与幂等审计，同一时刻至多一条PUBLISHED。OWNER当前北京时间月份，仅服务端可信QUALIFIED且新客账号当前有效的本人邀请计数，员工推广不算本人邀请。未配置UNKNOWN。达到阈值后截止取政策到期与下月起点较早者，停用/跨月/账号失效立即重新核对。service_available固定false，留言服务R7另行接入，真正提交时重验，GET不创建授权或奖励。'
        if body:item['requestBody']={'required':True,'content':{'application/json':{'schema':ref(body)}}}
        if method=='put':item['parameters'].append({'name':'Idempotency-Key','in':'header','required':True,'schema':{'type':'string','format':'uuid'}})
        if method=='get':item['parameters']=[]
        if method=='get' and path.endswith('diagnosis-policies'):item['parameters']=[{'name':'page','in':'query','schema':{'type':'integer','minimum':1,'maximum':1000000}},{'name':'page_size','in':'query','schema':{'const':20}}]
        for p in item['parameters']:
            if p['in']=='path':p['schema']=identifier
        item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','properties':{'data':ref(result)},'required':['data']}]}
        item['responses']['409']={'description':'政策状态、版本或唯一发布规则冲突'};item['responses']['503']={'description':'数据库未配置/事务暂不可用'}
        d['paths'].setdefault(path,{})[method]=item
