"""Independent assessment source policy; monthly calculations are R8b2."""
def add_r8b1(d, operation):
    s=d['components']['schemas'];ref=lambda n:{'$ref':'#/components/schemas/'+n}
    strict=lambda p:{'type':'object','additionalProperties':False,'required':list(p),'properties':p}
    ident={'type':'integer','minimum':1,'maximum':9007199254740990};stamp={'type':'string','format':'date-time'}
    evidence={'type':'array','minItems':1,'maxItems':4,'uniqueItems':True,'items':{'enum':['CHECK_IN_CONFIRMED','PROTECTION_COMPLETE','REPORT_SUBMITTED','QUALITY_SIGNED']}}
    grade=strict({'code':{'type':'string','pattern':'^[A-Z][A-Z0-9_]{0,15}$'},'label':{'type':'string','minLength':1,'maxLength':32},'min_score':{'type':'string','pattern':'^(?:0|[1-9][0-9]?|100)\\.[0-9]{2}$'}})
    grades={'type':'array','minItems':2,'maxItems':10,'items':grade,'description':'code/label各自唯一，最低分0.00–100.00按两位小数严格递减，末档0.00，不预置阈值。'}
    fields={'merchant_id':ident,'acquisition_target':{'type':'integer','minimum':1,'maximum':1000000},'coupon_target':{'type':'integer','minimum':1,'maximum':1000000},'process_requirements':evidence,'grade_thresholds':grades,'source_note':{'type':'string','minLength':2,'maxLength':256},'valid_from':stamp,'valid_to':stamp}
    s['AssessmentPolicyInput']=strict(fields);s['AssessmentPolicyUpdate']=strict({**fields,'expected_revision':ident})
    s['AssessmentMerchant']=strict({'id':ident,'name':{'type':'string'},'address':{'type':'string'}})
    s['AssessmentPolicy']=strict({**fields,'id':ident,'merchant':ref('AssessmentMerchant'),'revision':ident,'status':{'enum':['DRAFT','PUBLISHED','STOPPED']},'rule_version':{'const':'R8B_POLICY_V1'},'scope':{'const':'MERCHANT'},'period':{'const':'BEIJING_MONTH'},'timezone':{'const':'Asia/Shanghai'},'weights':strict({'acquisition':{'const':40},'coupon':{'const':40},'process':{'const':20}}),'metric_source_version':{'const':'R8B_FACTS_V1'},'formula':{'const':'TARGET_RATE_AND_PROCESS_COMPLETENESS_V1'},'created_at':stamp,'published_at':{'anyOf':[stamp,{'type':'null'}]}})
    page=lambda item:strict({'items':{'type':'array','maxItems':20,'items':ref(item)},'total':{'type':'integer','minimum':0},'page':{'type':'integer','minimum':1,'maximum':1000000},'page_size':{'const':20}})
    s['AssessmentPolicyPage']=page('AssessmentPolicy');s['AssessmentMerchantPage']=page('AssessmentMerchant')
    s['AssessmentPolicyContext']=strict({'state':{'enum':['UNKNOWN','NOT_STARTED','CONFIGURED','EXPIRED']},'policy':{'anyOf':[ref('AssessmentPolicy'),{'type':'null'}]},'scoring_available':{'const':False},'timezone':{'const':'Asia/Shanghai'}})
    s['OperatorAuthenticatedUser']=strict({'id':ident,'role':{'const':'operator'},**{k:{'type':'boolean'} for k in ['can_review','can_onboard','can_catalog','can_coupon','can_invite','can_diagnose','can_diagnose_manage','can_points','can_assessment']}})
    s['OperatorLoginResult']=strict({'access_token':{'type':'string'},'token_type':{'const':'Bearer'},'expires_in':{'const':900},'user':ref('OperatorAuthenticatedUser')})
    d['paths']['/api/auth/operator/login']['post']['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','required':['data'],'properties':{'data':ref('OperatorLoginResult')}}]}
    routes=[('get','/api/admin/assessment/merchants','AssessmentMerchantPage',None),('get','/api/admin/assessment/policies','AssessmentPolicyPage',None),('post','/api/admin/assessment/policies','AssessmentPolicy','AssessmentPolicyInput'),('put','/api/admin/assessment/policies/{id}','AssessmentPolicy','AssessmentPolicyUpdate'),('put','/api/admin/assessment/policies/{id}/status','AssessmentPolicy','PointPolicyStatus'),('get','/api/merchant/assessment/policy','AssessmentPolicyContext',None)]
    for method,path,result,body in routes:
        item=operation(method,path,'门店月度考核来源政策','F19');item['x-roles']='MERCHANT(manager)' if path.startswith('/api/merchant/') else 'OPERATOR(can_assessment)';item['x-implementation-status']='r8b1-source-policy-implemented'
        item['description']='no-store，独立can_assessment默认关闭。按真实有效门店、未来完整北京时间自然月配置；固定40/40/20，正整数拉新/券目标、非空支持证据子集和有来源等级阈值。草稿CAS、发布冻结/停止不重开、每店最多一条发布政策；永久规范化嵌套JSON创建意图防回执过期重复，权限优先。配置不生成成绩，旧assessment默认0不导入，scoring_available=false，实际月份冻结/取数/计算R8b2。'
        for p in item['parameters']:
            if p['in']=='path':p['schema']=ident
        if method=='get' and path.startswith('/api/admin/'):
            item['parameters'] += [{'name':'page','in':'query','schema':{'type':'integer','minimum':1,'maximum':1000000}},{'name':'page_size','in':'query','schema':{'const':20}}]
        if method=='put':item['parameters'].append({'name':'Idempotency-Key','in':'header','required':True,'schema':{'type':'string','format':'uuid'}})
        if body:item['requestBody']={'required':True,'content':{'application/json':{'schema':ref(body)}}}
        item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','required':['data'],'properties':{'data':ref(result)}}]}
        item['responses'].update({'404':{'description':'门店/政策不可用'},'409':{'description':'月份、修订或单店发布唯一性冲突'},'503':{'description':'数据库/审计不可用，保留原请求重试'}})
        d['paths'].setdefault(path,{})[method]=item
