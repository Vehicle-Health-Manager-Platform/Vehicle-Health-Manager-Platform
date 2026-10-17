"""Server-owned monthly context, scoped merchant views and own staff attribution."""
def add_r8b2b(d, operation):
    s=d['components']['schemas'];ref=lambda n:{'$ref':'#/components/schemas/'+n}
    strict=lambda p:{'type':'object','additionalProperties':False,'required':list(p),'properties':p}
    nullable=lambda n:{'anyOf':[n,{'type':'null'}]}
    ident={'type':'integer','minimum':1,'maximum':9007199254740990};count={'type':'integer','minimum':0};stamp={'type':'string','format':'date-time'};month={'type':'string','pattern':'^[1-9][0-9]{3}-(?:0[1-9]|1[0-2])$'}
    fields={'merchant':ref('AssessmentMerchant'),'period_month':month,'timezone':{'const':'Asia/Shanghai'},'server_now':stamp,'month_end':stamp,'revision':count,'latest':nullable(ref('AssessmentSnapshot')),'policy':nullable(ref('AssessmentFrozenPolicy')),'state':{'enum':['MONTH_OPEN','POLICY_UNKNOWN','READY']},'can_settle':{'type':'boolean'}}
    s['AssessmentMonthContext']=strict(fields)
    s['MerchantAssessmentMonthContext']=strict({**fields,'can_settle':{'const':False}})
    s['StaffAssessmentAttribution']=strict({'role':{'enum':['MERCHANT','STAFF']},'staff_id':ident,'merchant_id':ident,'period_month':month,'timezone':{'const':'Asia/Shanghai'},'state':{'enum':['NO_SNAPSHOT','FROZEN']},'revision':count,'captured_from':nullable(stamp),'captured_to':nullable(stamp),'attribution':strict({k:count for k in ['included','excluded','unknown']})})
    s['AssessmentPolicyContext']['properties']['scoring_available']={'const':True}
    for path,methods in d['paths'].items():
        if path.startswith('/api/admin/assessment/polic') or path=='/api/merchant/assessment/policy':
            for item in methods.values():item['description']=item.get('description','').replace('scoring_available=false，实际月份冻结/取数/计算R8b2','scoring_available=true表示月结读取能力，显式结算/修订使用独立API')
    routes=[('/api/admin/assessment/month','AssessmentMonthContext',True,False),('/api/admin/assessment/history','AssessmentSnapshotPage',True,True),('/api/merchant/assessment/month','MerchantAssessmentMonthContext',False,False),('/api/merchant/assessment/history','AssessmentSnapshotPage',False,True),('/api/merchant/assessment/snapshots/{id}','AssessmentSnapshot',False,False),('/api/merchant/assessment/snapshots/{id}/facts','AssessmentFactPage',False,True),('/api/merchant/assessment/staff','StaffAssessmentAttribution',False,False)]
    for path,result,admin,paged in routes:
        item=operation('get',path,'月结当前版本、本店范围及本人归因','F19');item['x-roles']='OPERATOR(can_assessment)' if admin else 'MERCHANT|STAFF(only own)' if path.endswith('/staff') else 'MERCHANT(manager)';item['x-implementation-status']='r8b2b-monthly-read-ui-implemented';item['description']='no-store，只读，不计算/改写成绩。运营按真实门店/月份取得服务器当前修订，不从历史分页推测；店长仅本店，普通员工仅本人冻结归因计数，拒绝跨店/员工选择器。上下文与历史为冻结时点，页面检测多请求版本变化并重读；政策/总分未知不能显示默认0。'
        for p in item['parameters']:
            if p['in']=='path':p['schema']=ident
        if '/snapshots/' not in path:
            item['parameters'].append({'name':'period_month','in':'query','required':True,'schema':month})
            if admin:item['parameters'].append({'name':'merchant_id','in':'query','required':True,'schema':ident})
        if paged:item['parameters'] += [{'name':'page','in':'query','schema':{'type':'integer','minimum':1,'maximum':1000000}},{'name':'page_size','in':'query','schema':{'const':20}}]
        item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','required':['data'],'properties':{'data':ref(result)}}]}
        item['responses'].update({'404':{'description':'本范围没有该快照或有效门店'},'503':{'description':'数据库或当前版本尚不可确认'}})
        d['paths'].setdefault(path,{})['get']=item
