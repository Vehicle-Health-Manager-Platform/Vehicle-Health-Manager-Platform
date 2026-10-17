"""R8a3a operator catalog configuration; spending is deliberately a later step."""
def add_r8a3a(d, operation):
    s=d['components']['schemas'];ref=lambda n:{'$ref':'#/components/schemas/'+n}
    strict=lambda p:{'type':'object','additionalProperties':False,'required':list(p),'properties':p}
    ident={'type':'integer','minimum':1,'maximum':9007199254740990}
    stamp={'type':'string','format':'date-time'}
    positive={'type':'integer','minimum':1,'maximum':1000000}
    fields={'coupon_id':ident,'publication_revision':ident,'pool_revision':ident,'points_cost':positive,'per_owner_limit':positive,'source_note':{'type':'string','minLength':2,'maxLength':256},'valid_from':stamp,'valid_to':stamp}
    s['PointExchangeCatalogInput']=strict(fields)
    s['PointExchangeCatalogUpdate']=strict({**fields,'expected_revision':ident})
    s['PointExchangeCatalog']=strict({**fields,'id':ident,'revision':ident,'status':{'enum':['DRAFT','PUBLISHED','STOPPED']},'rule_version':{'const':'R8A_EXCHANGE_V1'},'limit_scope':{'const':'OWNER_COUPON_LIFETIME'},'terms':ref('CouponTerms'),'created_at':stamp,'published_at':{'anyOf':[stamp,{'type':'null'}]}})
    page=lambda item:strict({'items':{'type':'array','maxItems':20,'items':ref(item)},'total':{'type':'integer','minimum':0},'page':{'type':'integer','minimum':1,'maximum':1000000},'page_size':{'const':20}})
    s['PointExchangeCatalogPage']=page('PointExchangeCatalog')
    s['PointExchangeSourcePage']=page('CouponInventory')
    routes=[('get','/api/admin/point/exchange-sources','PointExchangeSourcePage',None),('get','/api/admin/point/exchange-catalog','PointExchangeCatalogPage',None),('post','/api/admin/point/exchange-catalog','PointExchangeCatalog','PointExchangeCatalogInput'),('put','/api/admin/point/exchange-catalog/{id}','PointExchangeCatalog','PointExchangeCatalogUpdate'),('put','/api/admin/point/exchange-catalog/{id}/status','PointExchangeCatalog','PointPolicyStatus')]
    for method,path,result,body in routes:
        item=operation(method,path,'积分兑换目录配置','F11');item['x-roles']='OPERATOR(can_points)';item['x-implementation-status']='r8a3a-catalog-implemented'
        item['description']='no-store，独立can_points。仅真实已发布精选池服务券/金额券，绑定发行及池修订，目录窗口须在原券范围内。正整数积分价/本人同模板终身累计限额/来源说明；草稿CAS、发布冻结、停止不重开，同模板最多一条发布目录。永久创建回执防过期重建。配置不扣分不发券，实际兑换尚未开放。来源列表含未开始/零库存来源，实际发券后续重验库存。'
        for p in item['parameters']:
            if p['in']=='path':p['schema']=ident
        if method=='get':item['parameters'] += [{'name':'page','in':'query','schema':{'type':'integer','minimum':1,'maximum':1000000}},{'name':'page_size','in':'query','schema':{'const':20}}]
        if method=='put':item['parameters'].append({'name':'Idempotency-Key','in':'header','required':True,'schema':{'type':'string','format':'uuid'}})
        if body:item['requestBody']={'required':True,'content':{'application/json':{'schema':ref(body)}}}
        item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','required':['data'],'properties':{'data':ref(result)}}]}
        item['responses'].update({'404':{'description':'目录或真实来源不可用'},'409':{'description':'版本、精选来源、窗口或唯一发布冲突'},'503':{'description':'数据库或审计不可用；重试原操作'}})
        d['paths'].setdefault(path,{})[method]=item
