"""Atomic verified-point exchanges and a sourced debit branch in the same ledger."""
def add_r8a3b(d, operation):
    s=d['components']['schemas'];ref=lambda n:{'$ref':'#/components/schemas/'+n}
    strict=lambda p:{'type':'object','additionalProperties':False,'required':list(p),'properties':p}
    ident={'type':'integer','minimum':1,'maximum':9007199254740990};balance={'type':'integer','minimum':0,'maximum':9007199254740991};stamp={'type':'string','format':'date-time'}
    s['PointExchangeInput']=strict({'catalog_id':ident,'catalog_revision':ident})
    s['PointExchangeReceipt']=strict({'exchange_id':ident,'user_coupon_id':ident,'catalog_id':ident,'catalog_revision':ident,'coupon_id':ident,'publication_revision':ident,'points_spent':{'type':'integer','minimum':1,'maximum':1000000},'balance_after':balance,'exchanged_at':stamp,'status':{'const':'SUCCESS'},'rule_version':{'const':'R8A_EXCHANGE_V1'},'catalog':ref('PointExchangeCatalog')})
    s['PointExchangePage']=strict({'items':{'type':'array','maxItems':20,'items':ref('PointExchangeReceipt')},'total':balance,'page':{'type':'integer','minimum':1,'maximum':1000000},'page_size':{'const':20}})
    s['PointLedgerCreditEntry']=s['PointLedgerEntry']
    s['PointLedgerDebitEntry']=strict({'id':ident,'delta':{'type':'integer','minimum':-1000000,'maximum':-1},'balance_after':balance,'created_at':stamp,'exchange_id':ident,'catalog_id':ident,'catalog_revision':ident,'coupon_id':ident})
    s['PointLedgerEntry']={'oneOf':[ref('PointLedgerCreditEntry'),ref('PointLedgerDebitEntry')]}
    d['paths']['/api/point/flow']['get']['description']+=' R8a3b起含正数任务收入和负数兑换支出联合结构，均为独立有来源流水；按统一账本id倒序分页，旧收入字段不变。'
    for method,path,result,body in [('post','/api/point/exchange','PointExchangeReceipt','PointExchangeInput'),('get','/api/point/exchanges','PointExchangePage',None)]:
        item=operation(method,path,'本人已核验积分原子兑换与成功事实','F11');item['x-roles']='OWNER';item['x-implementation-status']='r8a3b-atomic-exchange-implemented'
        item['description']='no-store，仅本人。新兑换需独立POINTS_EXCHANGE_ENABLED（默认false）及同app服务器已核验身份。目录版本冻结，按本人/真实券来源/目录/可信账户锁序，锁等待后统一时点复核窗口、发行/精选版本、库存、余额和同模板终身累计次数。旧余额不导入。扣分/流水/本人券/POINTS凭证/库存/审计同事务；失败全回滚。永久本人原键+正文哈希/首次结果防24h回执过期重复，成功回执不受当前来源到期/开关关闭影响，仍重验当前账号/会话。成功不自助撤销返分，R5退款仅返原券且不延长原有效期。'
        if body:item['requestBody']={'required':True,'content':{'application/json':{'schema':ref(body)}}}
        if method=='get':item['parameters'] += [{'name':'page','in':'query','schema':{'type':'integer','minimum':1,'maximum':1000000}},{'name':'page_size','in':'query','schema':{'const':20}}]
        item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','required':['data'],'properties':{'data':ref(result)}}]}
        item['responses'].update({'404':{'description':'目录或来源不可用'},'409':{'description':'当前资格、目录、来源版本、窗口、积分、次数或库存冲突'},'503':{'description':'数据库或审计不可用；保留原键原正文恢复'}})
        d['paths'].setdefault(path,{})[method]=item
