"""Actual read-only event reward records; issuance is an internal durable worker."""
def add_r6b2(d,operation):
    s=d['components']['schemas'];ref=lambda n:{'$ref':'#/components/schemas/'+n}
    strict=lambda p:{'type':'object','additionalProperties':False,'required':list(p),'properties':p}
    null=lambda n:{'anyOf':[n,{'type':'null'}]};identifier={'type':'integer','minimum':1,'maximum':9007199254740991};utc={'type':'string','format':'date-time'}
    s['InvitationRewardItem']=strict({'role':{'enum':['INVITER','INVITEE']},'own':{'type':'boolean'},'terms':ref('CouponTerms'),'user_coupon_id':null(identifier)})
    s['InvitationRewardRecord']=strict({'record_id':{'type':'string','format':'uuid'},'source_type':{'enum':['OWNER','STAFF']},'state':{'enum':['READY','WAIT_STOCK','WAIT_SOURCE','WAIT_ACCOUNT','UNKNOWN_POLICY','EXPIRED','INVALID_EVIDENCE','COMPLETE']},'qualified_at':utc,'completed_at':null(utc),'policy_revision':null(identifier),'rule_version':{'const':'R6B_REWARD_V1'},'worker_enabled':{'type':'boolean'},'rewards':{'type':'array','maxItems':2,'items':ref('InvitationRewardItem')}})
    s['InvitationRewardPage']=strict({'items':{'type':'array','maxItems':20,'items':ref('InvitationRewardRecord')},'page':{'type':'integer','minimum':1,'maximum':1000000},'page_size':{'const':20},'total':{'type':'integer','minimum':0}})
    for path,roles in [('/api/invite/rewards','OWNER'),('/api/merchant/invite/rewards','MERCHANT,STAFF')]:
        item=operation('get',path,'本人邀请奖励/员工推广新客奖励记录','F10');item['x-roles']=roles;item['x-read-only']=True;item['x-implementation-status']='r6b2-reward-delivery-implemented'
        item['description']='no-store。只读限定本人新客或本人邀请事件，员工限定本人及原推广门店；不泄露对方身份/持券编号。资格事务冻结政策，后台双方原子发券、同事件角色唯一、重复模板不影响PUBLIC一次限制。未配置/历史无快照UNKNOWN_POLICY，不追溯后来政策。缺源/账号/库存持久重试，原有效期不延长，GET不执行发券。INVITE_REWARD_ENABLED默认false。'
        item['parameters']=[{'name':'page','in':'query','schema':{'type':'integer','minimum':1,'maximum':1000000}},{'name':'page_size','in':'query','schema':{'const':20}}]
        item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','properties':{'data':ref('InvitationRewardPage')},'required':['data']}]}
        item['responses']['503']={'description':'数据库未配置/暂不可用，不发券'}
        d['paths'].setdefault(path,{})['get']=item
