"""Minimal store-local customer relationships; original order archive consent remains authoritative."""
def add_r8b3b(d, operation):
    s=d['components']['schemas'];ref=lambda n:{'$ref':'#/components/schemas/'+n}
    strict=lambda p:{'type':'object','additionalProperties':False,'required':list(p),'properties':p}
    nullable=lambda p:{'anyOf':[p,{'type':'null'}]}
    ident={'type':'integer','minimum':1,'maximum':9007199254740990};stamp={'type':'string','format':'date-time'}
    s['CustomerRelationVehicle']=strict({k:nullable({'type':'string'}) for k in ['brand','series','year','config_name']})
    s['CustomerRelation']=strict({'relationship_order_id':ident,'order_count':ident,'last_order_at':stamp,'last_service_at':nullable(stamp),'identity_source':{'enum':['VERIFIED_WECHAT','TEST','UNKNOWN']},'latest_vehicle':ref('CustomerRelationVehicle')})
    s['CustomerRelationOrder']=strict({'order_id':ident,'order_status':{'enum':['PAID','RECEIVED','IN_SERVICE','PENDING_VERIFY','DISPUTED','COMPLETED']},'created_at':stamp,'service_at':nullable(stamp),'project_name':nullable({'type':'string'}),'vehicle':ref('CustomerRelationVehicle'),'service_context_available':{'type':'boolean'}})
    def paged(schema):return strict({'items':{'type':'array','maxItems':20,'items':ref(schema)},'total':{'type':'integer','minimum':0},'page':{'type':'integer','minimum':1,'maximum':1000000},'page_size':{'const':20}})
    s['CustomerRelations']=strict({'observed_at':stamp,'relationships':paged('CustomerRelation')})
    s['CustomerRelationDetail']=strict({'observed_at':stamp,'relationship':ref('CustomerRelation'),'orders':paged('CustomerRelationOrder')})
    for path,result in [('/api/merchant/customer-relations','CustomerRelations'),('/api/merchant/customer-relations/{id}','CustomerRelationDetail')]:
        item=operation('get',path,'本店最小服务关系及本店订单','F16,F20')
        item['x-roles']='MERCHANT(manager, own store only)';item['x-read-only']=True;item['x-implementation-status']='r8b3b-local-customer-relations-implemented'
        item['parameters']=([{'name':'id','in':'path','required':True,'schema':ident}] if '{id}' in path else [])+[{'name':'page','in':'query','schema':{'type':'integer','minimum':1,'maximum':1000000}},{'name':'page_size','in':'query','schema':{'const':20}}]
        item['description']='no-store，严格只读。同店付后/完成状态订单，当前有效账号及车辆归属；按车主内部归并但不输出全局客户ID，当前最小有效本店订单作为关系锚点，失效锚点404。普通员工/技师/运营拒绝。仅输出车型/项目/状态/下单时间；最近服务时间须接车及匹配流转，无源null。永久身份来源不代表有效新客或支付证明。拒绝门店/用户/车辆选择器，不输出姓名/电话/车牌/VIN/档案正文/签名地址。service_context_available只提示当前工单可用，读取仍沿R7逐单逐条授权即时复核，撤销/源变化/工单失效拒绝新读取。不新增长期授权或联系/导出用途。详见CUSTOMER_RELATIONS.md。'
        item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','required':['data'],'properties':{'data':ref(result)}}]}
        item['responses'].update({'404':{'description':'当前本店有效关系不存在、锚点失效或跨店'},'503':{'description':'数据库未配置或来源暂不可读取'}})
        d['paths'].setdefault(path,{})['get']=item
