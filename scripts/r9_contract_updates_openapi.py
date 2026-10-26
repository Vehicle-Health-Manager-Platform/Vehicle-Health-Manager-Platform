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

    path='/api/vehicle/mileage-recognition'
    item=operation('post',path,'从本人CLEAN图片读取仪表里程候选，明确同意后调用私有OCR，需本人确认','F03')
    item.pop('parameters',None)
    item['x-implementation-status']='mileage-recognition-gateway-implemented-requires-model'
    item['x-roles']='当前有效OWNER会话、本人有效车辆、本人CLEAN JPEG/PNG；MILEAGE_OCR_ENABLED默认false'
    item['description']='no-store、无query、无自动落库。MILEAGE_OCR_V1用途确认，图片1–4MiB，服务端读取私有字节；调用前后复核会话/车辆/文件。实例并发2，私有HTTP15秒硬截止、64KiB响应上限，无重试/重定向。低置信度保留；歧义不自动选值。服务凭据不下发客户端，真实模型与小程序接线另验。'
    item['requestBody']={'required':True,'content':{'application/json':{'schema':{'type':'object','additionalProperties':False,'required':['vehicle_id','file_id','consent','consent_version'],'properties':{
        'vehicle_id':{'type':'integer','minimum':1,'maximum':9007199254740990},'file_id':{'type':'integer','minimum':1,'maximum':9007199254740990},'consent':{'const':True},'consent_version':{'const':'MILEAGE_OCR_V1'}}}}}}
    item['responses']['200']['description']='候选投影: vehicle_id/file_id/review_status(CANDIDATE|LOW_CONFIDENCE|AMBIGUOUS|NO_CANDIDATE)/requires_confirmation=true/recognition(version=1,scene=archive,status=CANDIDATE|EMPTY,fields)。单候选mileage字符串及原置信度，歧义/无候选fields为空；不返回对象key/URL或OCR原文。'
    for code,desc in [('400','正文、重复键、用途确认或query无效'),('401','会话撤销/过期或用户失效'),('403','非正式车主角色'),('404','非本人车辆或图片，图片非CLEAN/已删/类型大小不符'),('409','调用期间车辆或图片事实变化'),('429','本实例已有两个识别在处理'),('503','关闭、数据/存储/OCR不可用、结果无效或超时')]:
        item['responses'][code]={'description':desc,'content':copy.deepcopy(item['responses']['200']['content'])}
    item['responses']['200']['headers']={'Cache-Control':{'schema':{'const':'no-store'}}}
    paths[path]={'post':item}
    path='/api/check/pickup/mileage-recognition'
    pickup=copy.deepcopy(item)
    meta=operation('post',path,'从本店接车仪表图片读取里程候选，员工核对后才带入表单','F14')
    for key in ('operationId','summary','tags'):pickup[key]=meta[key]
    pickup['x-implementation-status']='pickup-mileage-recognition-implemented'
    pickup['x-roles']='本店有效MERCHANT/STAFF会话、PAID订单、仍归订单车主的车辆、当前员工本人CLEAN JPEG/PNG'
    pickup['description']='默认随MILEAGE_OCR_ENABLED关闭；no-store、无query、无自动落库。PICKUP_MILEAGE_OCR_V1独立用途同意；只读1–4MiB私有图片，调用前后复核门店/员工会话、订单、车辆和未用于接车单的图片。与车主端共享实例两项并发限额及15秒供应商截止。仅候选，原八张照片、预约码、里程回退原因、车主确认/派工约束不变。'
    schema=pickup['requestBody']['content']['application/json']['schema']
    schema['required']=['order_id','file_id','consent','consent_version']
    schema['properties']['order_id']=schema['properties'].pop('vehicle_id')
    schema['properties']['consent_version']={'const':'PICKUP_MILEAGE_OCR_V1'}
    pickup['responses']['200']['description']='order_id/file_id/review_status/requires_confirmation=true/recognition（archive场景、仅mileage候选）。歧义不选值，低置信保留。人工核对仅带入原表单，不自动提交接车、不改变车辆或订单。'
    pickup['responses']['403']['description']='非MERCHANT/STAFF角色或绑定凭证'
    pickup['responses']['404']['description']='非本店订单、车辆已转移、非当前员工图片、图片不安全或已用于接车单'
    pickup['responses']['409']['description']='当前订单不可接车或在途订单/车辆/图片事实已变化'
    paths[path]={'post':pickup}

    path='/api/vehicle/vin-decode'
    vin=operation('post',path,'车主明确同意后查询NHTSA有限覆盖VIN候选，人工选车型','F03')
    vin.pop('parameters',None)
    vin['x-implementation-status']='vin-owner-candidate-implemented-requires-provider-acceptance'
    vin['x-roles']='当前有效OWNER会话；VIN_DECODE_ENABLED默认false，显式NHTSA_VPIC来源'
    vin['description']='仅POST JSON正文，禁止query，不自动登记车辆。VIN_VPIC_V1同意将VIN/可选年款发送美国NHTSA；仅美国市场登记覆盖，不保证中国车辆或内部车型精确匹配。调用前后复核会话/账号（失败也复核）；共享数据库每车主每UTC分钟5次，实例并发2。供应商10秒总期限、64KiB响应、不重定向、无应用级重试。返回需人工核对的CANDIDATE/PARTIAL/NO_MATCH，不提供model_id。旧GET未提供业务实现，应迁移本POST。'
    vin['requestBody']={'required':True,'content':{'application/json':{'schema':{'type':'object','additionalProperties':False,'required':['vin','consent','consent_version'],'properties':{
        'vin':{'type':'string','description':'首尾空白可去除，原始ASCII合法17位；服务端规范为大写','pattern':'^[ \\t\\r\\n]*[A-HJ-NPR-Za-hj-npr-z0-9]{17}[ \\t\\r\\n]*$'},
        'model_year':{'type':'integer','minimum':1980,'maximum':2100},'consent':{'const':True},'consent_version':{'const':'VIN_VPIC_V1'}}}}}}
    content=copy.deepcopy(vin['responses']['200']['content'])
    vin['responses']={code:{'description':desc,'content':copy.deepcopy(content),'headers':{'Cache-Control':{'schema':{'const':'no-store'}}}} for code,desc in [
        ('200','vin/model_year_hint(整数或null)/status(CANDIDATE|PARTIAL|NO_MATCH)/source=NHTSA_VPIC/coverage=US_MARKET_REGISTRATION_ONLY/requires_confirmation=true/fields。fields仅make/model/model_year/trim/vehicle_type非空文本；完整候选需make/model/model_year；NO_MATCH为空。'),
        ('400','VIN/年款/用途同意无效，额外字段/重复键/尾随JSON/query均拒绝'),('401','会话失效或账号停用/删除'),('403','非OWNER角色'),('429','每车主分钟限额或本实例并发满'),('503','关闭、来源不可用/超时/响应无效或数据库不可用') ]}
    paths[path]['post']=vin
    paths[path]['get']['deprecated']=True
    paths[path]['get']['description']='旧GET没有解码实现；迁移POST并提供VIN_VPIC_V1用途同意，勿将VIN置于查询参数。'
