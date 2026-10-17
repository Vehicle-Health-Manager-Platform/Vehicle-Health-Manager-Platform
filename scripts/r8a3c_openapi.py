"""Owner exchange quotes and independently recoverable owner interaction."""
def add_r8a3c(d, operation):
    s=d['components']['schemas'];ref=lambda n:{'$ref':'#/components/schemas/'+n}
    strict=lambda p:{'type':'object','additionalProperties':False,'required':list(p),'properties':p}
    ident={'type':'integer','minimum':1,'maximum':9007199254740990};count={'type':'integer','minimum':0,'maximum':9007199254740991};stamp={'type':'string','format':'date-time'}
    nullable=lambda v:{'anyOf':[v,{'type':'null'}]}
    s['PointExchangeMerchant']=strict({'id':ident,'name':{'type':'string'},'address':{'type':'string'}})
    s['PointExchangeProject']=strict({'id':ident,'name':{'type':'string'},'service_content':{'type':'string'}})
    s['PointOwnerExchangeOffer']=strict({'catalog':ref('PointExchangeCatalog'),'merchant':nullable(ref('PointExchangeMerchant')),'project':nullable(ref('PointExchangeProject')),'stock_left':nullable(count),'exchanged_count':count,'remaining_limit':count,'eligibility':{'enum':['ELIGIBLE','NOT_STARTED','EXPIRED','SOURCE_UNKNOWN','SOURCE_UNAVAILABLE','SOURCE_CHANGED','OUT_OF_STOCK','LIMIT_REACHED','INSUFFICIENT_POINTS','EXCHANGE_UNAVAILABLE']}})
    s['PointOwnerExchangeCatalog']=strict({'items':{'type':'array','maxItems':20,'items':ref('PointOwnerExchangeOffer')},'total':count,'page':{'type':'integer','minimum':1,'maximum':1000000},'page_size':{'const':20},'balance':count,'legacy_state':{'enum':['NONE','REVIEW_REQUIRED']},'exchange_available':{'type':'boolean'},'read_at':stamp})
    item=operation('get','/api/point/exchange-catalog','本人兑换目录、作用范围与当前资格快照','F11');item['x-roles']='OWNER';item['x-implementation-status']='r8a3c-owner-exchange-implemented'
    item['description']='no-store，只读快照，当前本人会话。只含PUBLISHED目录（含未开始/已过期），无默认价格；已核验余额不含旧积分/PENDING，exchange_available独立于获分开关。真实券发行/精选修订和原条款匹配、门店/项目有效时才展示当前作用范围；来源未知/失效/改变、时间/库存/模板跨版本终身限额/余额/资格逐项标状态。剩余次数=max(0,本目录每人限额-本人同模板全部成功数)。列表不锁业务或扣分发券，确认后POST必须重验，不接受客户端余额/价格。停止目录不再列入，原成功结果仍由exchanges恢复。'
    item['parameters'] += [{'name':'page','in':'query','schema':{'type':'integer','minimum':1,'maximum':1000000}},{'name':'page_size','in':'query','schema':{'const':20}}]
    item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','required':['data'],'properties':{'data':ref('PointOwnerExchangeCatalog')}}]}
    item['responses']['503']={'description':'数据库未配置或不可用；不显示伪造可兑结果'}
    d['paths'].setdefault('/api/point/exchange-catalog',{})['get']=item
