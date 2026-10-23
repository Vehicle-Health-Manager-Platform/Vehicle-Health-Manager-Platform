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
