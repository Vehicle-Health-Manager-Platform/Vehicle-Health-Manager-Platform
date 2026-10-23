"""Source definitions for R9 contract metadata and the read-only delivery comparison."""
import copy


def add_r9_contract_updates(document, operation):
    paths=document['paths']
    for path,method,status in [
        ('/api/ai/chat','post','ai-chat-implemented-requires-provider'),
        ('/api/input-materials/{purpose}/{vehicleId}/upload','post','controlled-input-material-upload-implemented'),
        ('/api/input-materials/{purpose}/{vehicleId}/{id}/access','get','controlled-input-material-access-implemented'),
    ]:
        paths[path][method]['x-implementation-status']=status

    path='/api/check/delivery/compare'
    item=operation('post',path,'取车对比:接车基线与完工证据的只读对照(交付仍由核销完成)','F14')
    item.pop('parameters')  # This projection has no write intent or idempotency header.
    item['responses']['200']['description']='对照投影:order_id/order_status/result(READY|INCOMPLETE)/deliverable/baseline/work/missing'
    item['responses']['400']['description']='正文不是 {"order_id":正整数} 或携带 query,code=40001'
    item['responses']['403']['description']='角色不符，code=40300'
    content=item['responses']['200']['content']
    for status,description in [
        ('404','订单不存在或不属于当前身份，code=40400'),
        ('409','接车检查未完成，code=43001'),
        ('503','数据库不可用，code=50300'),
    ]:
        item['responses'][status]={'description':description,'content':copy.deepcopy(content)}
    item['requestBody']={'required':True,'content':{'application/json':{'schema':{
        'type':'object','additionalProperties':False,'required':['order_id'],
        'properties':{'order_id':{'type':'integer','minimum':1,'maximum':9007199254740991}},
    }}}}
    item['x-implementation-status']='delivery-compare-implemented'
    item['x-roles']='本单车主或本店 MERCHANT/STAFF'
    paths[path]['post']=item

    path='/api/admin/reconciliation'
    item=operation('get',path,'运营按北京时间日期及渠道读取本地资金记录；渠道账单及差额未知','F20')
    item['parameters']=[
        {'name':'date','in':'query','required':True,'schema':{'type':'string','format':'date','pattern':r'^20[0-9]{2}-[0-9]{2}-[0-9]{2}$'}},
        {'name':'channel','in':'query','required':True,'schema':{'type':'string','enum':['WECHAT','LOCAL_TEST']}},
    ]
    item['x-implementation-status']='local-finance-read-implemented'
    item['x-roles']='OPERATOR且当前有效会话拥有can_analytics；平台聚合，不含交易明细'
    item['responses']['200']['description']='只读本地日汇总，CNY两位十进制字符串；异常总额null；channel_statement的net_amount/difference均null'
    content=item['responses']['200']['content']
    for status,description in [('400','日期/渠道缺失、未知/重复query或格式错误，40001'),('403','角色或当前can_analytics不符，40300'),('409','未来日期或单日超过10000条，40900'),('503','数据库或来源不可用，50300')]:
        item['responses'][status]={'description':description,'content':copy.deepcopy(content)}
    def obj(properties):
        return {'type':'object','additionalProperties':False,'required':list(properties),'properties':properties}
    count={'type':'integer','minimum':0,'maximum':10000}
    money={'type':['string','null'],'pattern':r'^(0|[1-9][0-9]{0,11})\.[0-9]{2}$'}
    state={'type':'string','enum':['KNOWN','UNKNOWN']}
    total=obj({'state':state,'success_count':count,'anomaly_count':count,'other_count':count,'amount':money})
    report=obj({
        'date':{'type':'string','format':'date'},'channel':{'type':'string','enum':['WECHAT','LOCAL_TEST']},
        'test_mode':{'type':'boolean'},'timezone':{'const':'Asia/Shanghai'},'currency':{'const':'CNY'},
        'source_version':{'const':'R9_LOCAL_FINANCE_V1'},
        **{key:{'type':'string','format':'date-time'} for key in ['window_from','window_to','observed_at']},
        'payments':total,'refunds':copy.deepcopy(total),'unclassified_count':count,
        'local_net':obj({'state':state,'amount':{'type':['string','null'],'pattern':r'^-?(0|[1-9][0-9]{0,11})\.[0-9]{2}$'}}),
        'channel_statement':obj({'state':{'const':'UNAVAILABLE'},'net_amount':{'type':'null'},'difference':{'type':'null'}}),
    })
    item['responses']['200']['content']={'application/json':{'schema':{'allOf':[
        {'$ref':'#/components/schemas/ApiResponse'}, {'type':'object','required':['data'],'properties':{'data':report}},
    ]}}}
    item['responses']['200']['headers']={'Cache-Control':{'schema':{'const':'no-store'}}}
    paths[path]['get']=item
