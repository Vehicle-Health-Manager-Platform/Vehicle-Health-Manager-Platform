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
    vin=operation('post',path,'车主同意来源用途后查询VIN候选，人工确认车型','F03')
    vin.pop('parameters',None)
    vin['x-implementation-status']='vin-owner-candidate-implemented-requires-provider-acceptance'
    vin['x-roles']='当前有效OWNER会话；VIN_DECODE_ENABLED默认false，显式NHTSA_VPIC来源'
    vin['description']='仅POST JSON正文，禁止query，不自动登记车辆。VIN_VPIC_V1同意将VIN/可选年款发送美国NHTSA；仅美国市场登记覆盖，不保证中国车辆或内部车型精确匹配。调用前后复核会话/账号（失败也复核）；共享数据库每车主每UTC分钟5次，实例并发2。供应商10秒总期限、64KiB响应、不重定向、无应用级重试。返回需人工核对的CANDIDATE/PARTIAL/NO_MATCH，不提供model_id。旧GET未提供业务实现，应迁移本POST。'
    vin['requestBody']={'required':True,'content':{'application/json':{'schema':{'type':'object','additionalProperties':False,'required':['vin','consent','consent_version'],'properties':{
        'vin':{'type':'string','description':'首尾空白可去除，原始ASCII合法17位；服务端规范为大写','pattern':'^[ \\t\\r\\n]*[A-HJ-NPR-Za-hj-npr-z0-9]{17}[ \\t\\r\\n]*$'},
        'model_year':{'type':'integer','minimum':1980,'maximum':2100},'consent':{'const':True},'consent_version':{'enum':['VIN_VPIC_V1','VIN_LOCAL_V1']}}}}}}
    content=copy.deepcopy(vin['responses']['200']['content'])
    vin['responses']={code:{'description':desc,'content':copy.deepcopy(content),'headers':{'Cache-Control':{'schema':{'const':'no-store'}}}} for code,desc in [
        ('200','vin/model_year_hint(整数或null)/status(CANDIDATE|PARTIAL|NO_MATCH)/source=NHTSA_VPIC/coverage=US_MARKET_REGISTRATION_ONLY/requires_confirmation=true/fields。fields仅make/model/model_year/trim/vehicle_type非空文本；完整候选需make/model/model_year；NO_MATCH为空。'),
        ('400','VIN/年款/用途同意无效，额外字段/重复键/尾随JSON/query均拒绝'),('401','会话失效或账号停用/删除'),('403','非OWNER角色'),('429','每车主分钟限额或本实例并发满'),('503','关闭、来源不可用/超时/响应无效或数据库不可用') ]}
    paths[path]['post']=vin
    vin['x-roles']='当前有效OWNER会话；默认关闭。NHTSA_VPIC或LOCAL_LICENSED两种服务端固定来源，客户端用途同意必须一致'
    vin['description']+=' LOCAL_LICENSED模式使用VIN_LOCAL_V1同意，仅加载服务端配置的SHA256固定本地授权规则，逐次核验授权期及文件。额外返回source_revision/synthetic/catalog_candidates；source为LOCAL_LICENSED或LOCAL_SYNTHETIC，coverage=PINNED_LOCAL_RULES_ONLY。目录候选仅在品牌/车系/年款/配置文本精确匹配且全链有效且唯一时返回一个，否则为空；须人工确认，保存时仍重新校验目录。无授权的真实车牌反查未接入，合成测试结果不表示真实国内车型覆盖。'
    vin['responses']['200']['description']+=' 本地模式另含source_revision(修订标识)/synthetic(布尔)/catalog_candidates(0或1项：model_id/series_id/brand_id/brand_name/series_name/year/config_name)。'
    paths[path]['get']['deprecated']=True
    paths[path]['get']['description']='旧GET没有解码实现；迁移POST并提供VIN_VPIC_V1用途同意，勿将VIN置于查询参数。'

    path='/api/vehicle/license-recognition'
    license_=operation('post',path,'本人新车登记图片提取车牌/VIN候选，人工核对后手动选车型','F03')
    license_.pop('parameters',None)
    license_['x-implementation-status']='license-owner-candidate-implemented-requires-document-acceptance'
    license_['x-roles']='当前有效OWNER会话；本人CLEAN JPEG/PNG 1..4MiB；LICENSE_OCR_ENABLED默认false'
    license_['description']='仅POST JSON正文，无query、不接收vehicle_id、URL或对象key。复用当前车主私有图片上传，不要求尚未创建的车辆ID；NEW_VEHICLE_LICENSE_OCR_V1明确新车用途。调用前后复核会话/账号及文件归属/扫描/元数据，存储读取后推理前再复核；来源失败也复核。每车主UTC分钟5次、实例并发2，来源10秒期限/64KiB，无应用级重试/重定向；受限worker推理8秒且单任务无队列。只返回完整合法标签旁车牌/VIN，不返回姓名/住址/证件原文，不证明真伪或归属，不自动建车/匹配车型/关联证件附件。取消不承诺删除已上传照片，按既有私有附件策略保留。'
    license_['requestBody']={'required':True,'content':{'application/json':{'schema':{'type':'object','additionalProperties':False,'required':['file_id','purpose','consent','consent_version'],'properties':{
        'file_id':{'type':'integer','minimum':1,'maximum':9007199254740990},'purpose':{'const':'NEW_VEHICLE'},'consent':{'const':True},'consent_version':{'const':'NEW_VEHICLE_LICENSE_OCR_V1'}}}}}}
    field_status=['CANDIDATE','LOW_CONFIDENCE','AMBIGUOUS','NO_CANDIDATE']
    candidate={'type':'object','additionalProperties':False,'required':['value','confidence'],'properties':{'value':{'type':'string','minLength':7,'maxLength':17},'confidence':{'type':'number','minimum':0,'maximum':1}}}
    field={'type':'object','additionalProperties':False,'required':['key','status','candidates'],'properties':{'key':{'enum':['plate','vin']},'status':{'enum':field_status},'candidates':{'type':'array','maxItems':10,'items':candidate}},'description':'固定先plate后vin；字段值不重复。无值NO_CANDIDATE；多值AMBIGUOUS；单值confidence>=0.9为CANDIDATE，否则LOW_CONFIDENCE；不修复O/0、不删除内部空白或猜测。'}
    projection={'type':'object','additionalProperties':False,'required':['file_id','purpose','source','review_status','requires_confirmation','fields'],'properties':{
        'file_id':{'type':'integer','minimum':1,'maximum':9007199254740990},'purpose':{'const':'NEW_VEHICLE'},'source':{'const':'LOCAL_OCR'},
        'review_status':{'enum':['CANDIDATE','PARTIAL','LOW_CONFIDENCE','AMBIGUOUS','NO_CANDIDATE']},'requires_confirmation':{'const':True},'fields':{'type':'array','minItems':2,'maxItems':2,'items':field}}}
    success={'application/json':{'schema':{'allOf':[{'$ref':'#/components/schemas/ApiResponse'},{'type':'object','required':['data'],'properties':{'data':projection}}]}}}
    error_content=copy.deepcopy(license_['responses']['200']['content'])
    license_['responses']={code:{'description':desc,'content':copy.deepcopy(success if code=='200' else error_content),'headers':{'Cache-Control':{'schema':{'const':'no-store'}}}} for code,desc in [
        ('200','file_id/purpose=NEW_VEHICLE/source=LOCAL_OCR/review_status/requires_confirmation=true/fields。整体优先AMBIGUOUS再LOW_CONFIDENCE；两个高可信单值为CANDIDATE，一个为PARTIAL，均无值为NO_CANDIDATE。每字段保留所有候选及原始置信度，超10个报来源错误，不截断。'),
        ('400','图片ID/新车用途/同意无效，额外字段/重复键/尾随JSON/query拒绝'),('401','会话过期/撤销或账号停用/删除'),('403','非OWNER身份或绑定凭证'),('404','非本人/已删/非CLEAN/非JPEG或PNG/超大小图片'),('409','调用期间文件元数据变化'),('429','每车主分钟限额或本实例并发满'),('503','关闭、数据/私有存储/来源不可用、超时或来源协议错误') ]}
    paths[path]={'post':license_}
    paths['/api/vehicle/ocr-license']['get']['deprecated']=True
    paths['/api/vehicle/ocr-license']['get']['description']='旧GET没有行驶证识别业务实现；迁移POST /api/vehicle/license-recognition并提供当前本人file_id及新车用途同意。'

    path='/api/recognition/asr/jobs'
    create=operation('post',path,'本人同车辆同用途音频创建持久ASR候选任务','F05')
    create['x-implementation-status']='asr-durable-job-create-implemented-requires-model-acceptance'
    create['x-roles']='当前有效OWNER；本人车辆/同用途CLEAN input_material音频；ASR_ENABLED默认false'
    create['description']='严格JSON、无query、UUID幂等键；仅接受同一车辆及AI/ARCHIVE用途的本人audio/mpeg，现有16kHz mono/48kbps、1..60秒、<=3MiB政策保持。ASR_TRANSCRIBE_V1明确本次转写同意。每owner每UTC分钟5次新任务、最多2个未过期活动任务；幂等重放不重复排队或消耗限额。仅持久化作用域/原会话标识/源元数据hash/状态，不将JWT、原音频、对象key或转写放入普通回执/审计，不自动发送问题或保存档案。'
    identity={'job_id':{'type':'integer','minimum':1,'maximum':9007199254740990},'vehicle_id':{'type':'integer','minimum':1,'maximum':9007199254740990},'material_id':{'type':'integer','minimum':1,'maximum':9007199254740990},'purpose':{'enum':['AI','ARCHIVE']},'task':{'const':'ASR'}}
    reference={'type':'object','additionalProperties':False,'required':list(identity),'properties':copy.deepcopy(identity)}
    create['requestBody']={'required':True,'content':{'application/json':{'schema':{'type':'object','additionalProperties':False,'required':['vehicle_id','material_id','purpose','consent','consent_version'],'properties':{
        'vehicle_id':copy.deepcopy(identity['vehicle_id']),'material_id':copy.deepcopy(identity['material_id']),'purpose':copy.deepcopy(identity['purpose']),'consent':{'const':True},'consent_version':{'const':'ASR_TRANSCRIBE_V1'}}}}}}
    content=copy.deepcopy(create['responses']['200']['content'])
    def asr_responses(projection,descriptions):
        success={'application/json':{'schema':{'allOf':[{'$ref':'#/components/schemas/ApiResponse'},{'type':'object','required':['data'],'properties':{'data':projection}}]}}}
        return {code:{'description':desc,'content':copy.deepcopy(success if code=='200' else content),'headers':{'Cache-Control':{'schema':{'const':'no-store'}}}} for code,desc in descriptions}
    common=[('400','正文/ID/同意/幂等键无效，额外或重复键/尾随JSON/query均拒绝'),('401','当前会话失效或账号停用/删除'),('403','非OWNER或绑定凭证'),('404','非本人当前车辆、同用途音频或任务不可用'),('503','关闭、数据或任务服务不可用')]
    create['responses']=asr_responses(reference,[('200','创建回执仅job_id/vehicle_id/material_id/purpose/task；同键同正文返回原任务引用，不重放候选文本。'),*common,('429','每分钟新任务限额或已有2个活动任务')])
    paths[path]={'post':create}
    result={'type':'object','additionalProperties':False,'required':['version','task','source','status','language','duration_ms','segments','requires_confirmation','audio_recognition_performed'],'properties':{
        'version':{'const':1},'task':{'const':'ASR'},'source':{'const':'FASTER_WHISPER'},'status':{'enum':['TRANSCRIBED','EMPTY']},'language':{'type':['string','null'],'pattern':'^[a-z]{2,3}$'},'duration_ms':{'type':'integer','minimum':1000,'maximum':60000},'requires_confirmation':{'const':True},'audio_recognition_performed':{'const':True},
        'segments':{'type':'array','maxItems':50,'items':{'type':'object','additionalProperties':False,'required':['start_ms','end_ms','text','confidence'],'properties':{'start_ms':{'type':'integer','minimum':0,'maximum':59999},'end_ms':{'type':'integer','minimum':1,'maximum':60000},'text':{'type':'string','minLength':1,'maxLength':500},'confidence':{'type':['number','null'],'minimum':0,'maximum':1}}}}},
        'description':'duration_ms必须等于原音频声明并经读取校验；时间单调不重叠且0<=start<end<=duration_ms，文本无控制或孤立代理字符、无首尾空白。以换行连接全部片段最多1000 UTF16单元，不静默截断。TRANSCRIBED非空，EMPTY空且language可null。faster-whisper平均logprob不转换可信百分比，实际适配confidence=null。'}
    detail=operation('get',path+'/{id}','本人查询ASR排队状态与短期转写候选','F05')
    detail['x-implementation-status']='asr-durable-job-read-implemented-requires-model-acceptance'
    detail['x-roles']=create['x-roles']
    detail['description']='无query、no-store；返回前重新验证当前会话/本人车辆、同用途材料与原创建会话。原会话撤销不再交付候选；取消/来源变化/关闭/TTL到期清空结果。候选TTL自创建10分钟，查询失效后不可见，后台清理正文；任务元数据与原音频保留策略另管。'
    projection={'type':'object','additionalProperties':False,'required':[*identity,'state','expires_at','error_code','result'],'properties':{**copy.deepcopy(identity),'state':{'enum':['QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED','EXPIRED']},'expires_at':{'type':'string','format':'date-time'},'error_code':{'enum':[None,'UNAVAILABLE','SOURCE_CHANGED','AUTH_REVOKED','WORKER_LOST','INVALID_RESULT','EXPIRED','USER_CANCELLED','FEATURE_DISABLED']},'result':{'anyOf':[{'type':'null'},result]}}}
    detail['responses']=asr_responses(projection,[('200','固定任务作用域/状态/到期/error_code/result；只在SUCCEEDED返回经过严格校验的候选，其他状态result=null。'),*common])
    paths[path+'/{id}']={'get':detail}
    cancel=operation('post',path+'/{id}/cancel','本人取消ASR任务并清空候选','F05')
    cancel['x-implementation-status']='asr-durable-job-cancel-implemented'
    cancel['x-roles']='当前有效OWNER；本人当前车辆与任务；新有效会话可取消自己的旧任务'
    cancel['description']='严格空对象{}、无query、UUID幂等键。可取消排队/运行或已成功候选并清空；重复取消安全，不删除原音频。取消请求失败或创建结果未知不能声称服务端已取消；未知创建可经重新同意后同正文同键确认原任务，再立即发取消。'
    cancel['requestBody']={'required':True,'content':{'application/json':{'schema':{'type':'object','additionalProperties':False,'maxProperties':0}}}}
    cancel['responses']=asr_responses({'type':'object','additionalProperties':False,'required':['job_id'],'properties':{'job_id':copy.deepcopy(identity['job_id'])}},[('200','取消受理回执仅job_id；终态与过期状态以再次查询为准。'),*common])
    paths[path+'/{id}/cancel']={'post':cancel}

    # R4b: task lifecycle is shared, but document scopes and result protocol are independent.
    docpath='/api/recognition/documents/jobs'
    for method,suffix,template in [('post','',create),('get','/{id}',detail),('post','/{id}/cancel',cancel)]:
        item=copy.deepcopy(template)
        # Generated ids must follow the actual path, never retain the ASR operation id.
        item['operationId']=operation(method,docpath+suffix,'','F05')['operationId']
        item['summary']=template['summary'].replace('ASR','图文提取').replace('音频','图片/PDF')
        item['x-roles']='当前有效OWNER；本人同车辆同AI/ARCHIVE用途CLEAN辅助材料；DOCUMENT_ENABLED默认false；不接受任意file_id'
        item['x-implementation-status']='document-durable-job-implemented-local'
        item['description']='严格JSON、no-store、无query；创建DOCUMENT_EXTRACT_V1独立同意，UUID幂等。JPEG/PNG<=4MiB/4096边长/600万像素或PDF<=10MiB，提取最多4页；超页整份LIMIT_EXCEEDED，不返回部分成功。每类型每车主每UTC分钟5次；ASR及DOCUMENT合计最多2个活动任务，按类型领取/访问/清理。租约180秒，不自动重试，候选自创建起10分钟；来源及原会话在存储前后、推理后和GET重新校验。撤销可清空已成功候选但不删除原材料，不承诺立即停算。人工逐段选择/修改/核对并重新GET后仅带入问题或备注，仍需原发送/保存；不产生日期/金额/里程事实。'
        def replace(value):
            if isinstance(value,dict):return {k:replace(v) for k,v in value.items()}
            if isinstance(value,list):return [replace(v) for v in value]
            if isinstance(value,str):return value.replace('ASR_TRANSCRIBE_V1','DOCUMENT_EXTRACT_V1').replace('ASR','DOCUMENT').replace('音频','图片/PDF').replace('转写','图文提取')
            return value
        item=replace(item)
        if method=='get':
            schema=item['responses']['200']['content']['application/json']['schema']['allOf'][1]['properties']['data']
            schema['properties']['error_code']['enum']+=['INVALID_DOCUMENT','LIMIT_EXCEEDED']
            docresult=obj({'version':{'const':1},'task':{'const':'DOCUMENT'},'source':{'const':'PDFBOX_PADDLE'},'content_type':{'enum':['application/pdf','image/jpeg','image/png']},'content_sha256':{'type':'string','pattern':'^[a-f0-9]{64}$'},'page_count':{'type':'integer','minimum':1,'maximum':4},'status':{'enum':['EXTRACTED','EMPTY']},'requires_confirmation':{'const':True},'image_recognition_performed':{'type':'boolean'},'all_pages_processed':{'const':True},'pages':{'type':'array','minItems':1,'maxItems':4,'items':obj({'page':{'type':'integer','minimum':1,'maximum':4},'method':{'enum':['NATIVE_TEXT','OCR','EMPTY']},'text':{'type':'string','maxLength':4096},'raster_size':{'anyOf':[{'type':'null'},{'type':'array','minItems':2,'maxItems':2,'items':{'type':'integer','minimum':1,'maximum':4096}}]},'blocks':{'type':'array','maxItems':80,'items':obj({'text':{'type':'string','minLength':1,'maxLength':500},'confidence':{'type':['number','null'],'minimum':0,'maximum':1},'source':{'oneOf':[obj({'kind':{'const':'TEXT_OFFSET'},'start':{'type':'integer','minimum':0},'end':{'type':'integer','minimum':1}}),obj({'kind':{'const':'RASTER_POLYGON'},'points':{'type':'array','minItems':4,'maxItems':4,'items':{'type':'array','minItems':2,'maxItems':2,'items':{'type':'number','minimum':0,'maximum':4096}}}})]}})}})}})
            docresult['description']='整份最多4096 UTF16单元/80块，每块500；页码连续，page_count等于上传元数据；hash绑定私有源字节。原生块完整覆盖非空行，偏移UTF16末端不含，confidence=null；OCR块有实际凸四边形/原置信度，坐标在raster_size内且面积预算600万像素。all_pages_processed=true，不得构造部分成功。'
            schema['properties']['result']={'anyOf':[{'type':'null'},docresult]}
        paths[docpath+suffix]={method:item}
    upload=paths['/api/input-materials/{purpose}/{vehicleId}/upload']['post']
    upload['description']=upload.get('description','')+' R4b独立辅助图片通道：X-Material-Kind=image，通过字节解码校验JPEG/PNG，<=4MiB/4096边长/600万像素/单帧，duration_ms=0/pages=1；复用本人车辆/用途授权、扫描及私有存储，不扩展业务图片白名单。'
    for parameter in upload.get('parameters',[]):
        if parameter.get('name')=='X-Material-Kind':parameter['schema']={'type':'string','enum':['audio','pdf','image']}
