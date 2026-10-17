"""Explicit monthly revisions. Merchant/employee read screens follow in R8b2b."""
def add_r8b2a(d, operation):
    s=d['components']['schemas'];ref=lambda n:{'$ref':'#/components/schemas/'+n}
    strict=lambda p:{'type':'object','additionalProperties':False,'required':list(p),'properties':p}
    ident={'type':'integer','minimum':1,'maximum':9007199254740990};count={'type':'integer','minimum':0};stamp={'type':'string','format':'date-time'};nullable=lambda n:{'anyOf':[n,{'type':'null'}]};sha={'type':'string','pattern':'^[0-9a-f]{64}$'};score={'type':'string','pattern':'^(?:0|[1-9][0-9]?|100)\\.[0-9]{2}$'}
    s['AssessmentMonthRequest']=strict({'merchant_id':ident,'period_month':{'type':'string','pattern':'^[1-9][0-9]{3}-(?:0[1-9]|1[0-2])$'},'expected_revision':{'type':'integer','minimum':0,'maximum':9007199254740990}})
    s['AssessmentFrozenPolicy']={**s['AssessmentPolicy'],'required':s['AssessmentPolicy']['required']+['stopped_at'],'properties':{**s['AssessmentPolicy']['properties'],'stopped_at':nullable(stamp)}}
    s['AssessmentMetric']=strict({'numerator':count,'denominator':count,'state':{'enum':['KNOWN','UNKNOWN']},'score':nullable(score),'reason':{'enum':['POLICY_UNKNOWN','NO_VALID_DENOMINATOR','SOURCE_UNKNOWN','VERIFIED']}})
    s['AssessmentSummary']=strict({'state':{'enum':['CALCULATED','UNKNOWN']},'metrics':strict({k:ref('AssessmentMetric') for k in ['acquisition','coupon','process']}),'total_score':nullable(score),'grade':nullable(strict({'code':{'type':'string'},'label':{'type':'string'}})),'fact_count':count})
    evidence={'type':'object','additionalProperties':False,'properties':{**{k:{'enum':['PRESENT','MISSING','UNKNOWN']} for k in ['CHECK_IN_CONFIRMED','PROTECTION_COMPLETE','REPORT_SUBMITTED','QUALITY_SIGNED']},'_proof_hash':sha,'full_refund':{'type':'boolean'}}}
    s['AssessmentFact']=strict({'dimension':{'enum':['ACQUISITION','COUPON','PROCESS']},'source_type':{'enum':['INVITE','ORDER','CASH_ORDER','UNPROVEN_ORDER','SERVICE_REDEMPTION']},'source_id':{'type':'string'},'occurred_at':nullable(stamp),'staff_id':nullable(ident),'state':{'enum':['INCLUDED','INCOMPLETE','EXCLUDED','UNKNOWN']},'reason':{'type':'string'},'evidence':evidence,'proof_hash':sha})
    s['AssessmentSnapshot']=strict({'id':ident,'merchant_id':ident,'period_month':s['AssessmentMonthRequest']['properties']['period_month'],'revision':ident,'facts_hash':sha,'calculation_version':{'const':'R8B_CALC_V1'},'timezone':{'const':'Asia/Shanghai'},'policy':nullable(ref('AssessmentFrozenPolicy')),'summary':ref('AssessmentSummary'),'captured_from':stamp,'captured_to':stamp})
    for name,item in [('AssessmentSnapshotPage','AssessmentSnapshot'),('AssessmentFactPage','AssessmentFact')]:s[name]=strict({'items':{'type':'array','maxItems':20,'items':ref(item)},'total':count,'page':{'type':'integer','minimum':1,'maximum':1000000},'page_size':{'const':20}})
    for method,path,result,body in [('get','/api/admin/assessment/snapshots','AssessmentSnapshotPage',None),('post','/api/admin/assessment/snapshots','AssessmentSnapshot','AssessmentMonthRequest'),('get','/api/admin/assessment/snapshots/{id}','AssessmentSnapshot',None),('get','/api/admin/assessment/snapshots/{id}/facts','AssessmentFactPage',None)]:
        item=operation(method,path,'显式门店月度结算与不可变事实修订','F19');item['x-roles']='OPERATOR(can_assessment)';item['x-implementation-status']='r8b2a-monthly-snapshot-implemented';item['description']='no-store。仅结束的北京自然月、整月覆盖政策、正式服务器证据，未知不造0分。CAS及永久原键回执，修订/事实/审计同事务；后续全退从原月剔除，新增修订保留旧包。逐项证据不含车主正文/手机号/私有URL。读取为冻结时点，非实时看板；门店页面后续R8b2b。'
        for p in item['parameters']:
            if p['in']=='path':p['schema']=ident
        if method=='get' and (path.endswith('snapshots') or path.endswith('/facts')):item['parameters'] += [{'name':'page','in':'query','schema':{'type':'integer','minimum':1,'maximum':1000000}},{'name':'page_size','in':'query','schema':{'const':20}}]
        if body:item['requestBody']={'required':True,'content':{'application/json':{'schema':ref(body)}}}
        item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','required':['data'],'properties':{'data':ref(result)}}]}
        item['responses'].update({'404':{'description':'门店/快照不可用'},'409':{'description':'月份未结束或CAS修订冲突'},'503':{'description':'数据库/审计/来源容量不可用，全回滚，重试原键'}})
        d['paths'].setdefault(path,{})[method]=item
