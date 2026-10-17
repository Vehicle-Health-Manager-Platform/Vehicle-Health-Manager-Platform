"""R6b1 configuration contracts. No issuance or unlock endpoint is claimed."""
def add_r6b1(d, operation):
    s=d['components']['schemas'];ref=lambda n:{'$ref':'#/components/schemas/'+n}
    strict=lambda p:{'type':'object','additionalProperties':False,'required':list(p),'properties':p}
    null=lambda n:{'anyOf':[n,{'type':'null'}]};identifier={'type':'integer','minimum':1,'maximum':9007199254740991};utc={'type':'string','format':'date-time'}
    fields={'scope':{'enum':['OWNER','STAFF']},'source_note':{'type':'string','minLength':2,'maxLength':256},'valid_from':utc,'valid_to':utc,'inviter_coupon_id':null(identifier),'inviter_publication_revision':null(identifier),'invitee_coupon_id':identifier,'invitee_publication_revision':identifier}
    s['InvitePolicyInput']=strict(fields);s['InvitePolicyUpdateInput']=strict({**fields,'expected_revision':{'type':'integer','minimum':0,'maximum':9007199254740990}})
    s['InvitePolicyStatusInput']=strict({'expected_revision':{'type':'integer','minimum':0,'maximum':9007199254740990},'status':{'enum':['PUBLISHED','STOPPED']}})
    s['InviteRewardPolicy']=strict({**fields,'id':identifier,'revision':identifier,'status':{'enum':['DRAFT','PUBLISHED','STOPPED']},'rule_version':{'const':'R6B_REWARD_V1'},'created_at':utc,'published_at':null(utc),'inviter_terms':null(ref('CouponTerms')),'invitee_terms':null(ref('CouponTerms'))})
    s['InviteRewardPolicyPage']=strict({'items':{'type':'array','maxItems':20,'items':ref('InviteRewardPolicy')},'page':{'type':'integer','minimum':1,'maximum':1000000},'page_size':{'const':20},'total':{'type':'integer','minimum':0}})
    public=dict(s['InviteRewardPolicy']['properties']);public.pop('source_note');s['InviteRewardPolicyPublic']=strict(public)
    s['InviteRewardPolicyCurrent']=strict({'scope':{'enum':['OWNER','STAFF']},'state':{'enum':['UNKNOWN','NOT_STARTED','EXPIRED','CONFIGURED','SOURCE_UNAVAILABLE']},'policy':null(ref('InviteRewardPolicyPublic'))})
    for method,path,result,body in [('get','/api/admin/invite/reward-policies','InviteRewardPolicyPage',None),('get','/api/admin/invite/reward-coupons','CouponInventoryPage',None),('post','/api/admin/invite/reward-policies','InviteRewardPolicy','InvitePolicyInput'),('put','/api/admin/invite/reward-policies/{id}','InviteRewardPolicy','InvitePolicyUpdateInput'),('put','/api/admin/invite/reward-policies/{id}/status','InviteRewardPolicy','InvitePolicyStatusInput'),('get','/api/invite/reward-policy','InviteRewardPolicyCurrent',None)]:
        item=operation(method,path,'邀请奖励政策配置与来源读取','F10');item['x-roles']='OWNER' if path=='/api/invite/reward-policy' else 'OPERATOR(can_invite)';item['x-implementation-status']='r6b1-reward-policy-implemented'
        item['description']='no-store，独立can_invite默认关闭，当前权限重验。OWNER两项/ST AFF仅新客，真实已发布精选券来源与有效期；发布冻结版本、同类型唯一PUBLISHED、停止不重开。配置不发券/不占库存，缺配置UNKNOWN；可靠发券见R6b2内部任务和奖励记录，解锁另在后续实现。'.replace('ST AFF','STAFF')
        if body:item['requestBody']={'required':True,'content':{'application/json':{'schema':ref(body)}}}
        if method=='put':item['parameters'].append({'name':'Idempotency-Key','in':'header','required':True,'schema':{'type':'string','format':'uuid'}})
        for p in item['parameters']:
            if p['in']=='path':p['schema']=identifier
        if path.endswith(('reward-policies','reward-coupons')) and method=='get':item['parameters'] += [{'name':'page','in':'query','schema':{'type':'integer','minimum':1,'maximum':1000000}},{'name':'page_size','in':'query','schema':{'const':20}}]
        if path=='/api/invite/reward-policy':item['parameters']=[{'name':'scope','in':'query','required':True,'schema':{'enum':['OWNER','STAFF']}}]
        item['responses']['200']['content']['application/json']['schema']={'allOf':[ref('ApiResponse'),{'type':'object','properties':{'data':ref(result)},'required':['data']}]}
        item['responses']['409']={'description':'规则版本/来源改变或同类型已存在发布政策'};item['responses']['503']={'description':'事务暂不可用，原正文/键重试'}
        d['paths'].setdefault(path,{})[method]=item
