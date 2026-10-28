import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {reactive,ref,watch,effectScope} from 'vue'
import {createKnowledgeApi,createKnowledgeRecovery,validKnowledgeBody,validKnowledgePayload,validKnowledgeReference,knowledgeRoute,knowledgeReference,KNOWLEDGE_FIELDS} from '../src/services/licensed-knowledge.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {createQuestionConfirmation,pendingQuestion} from '../src/services/community-question-recovery.js'
import {operatorSession,operatorToken,setOperatorSession,clearOperatorSession,operatorLandingPage} from '../src/services/operator-session.js'
import {validOperatorUser} from '../src/services/experience-publication.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
const key='11111111-1111-4111-8111-111111111111',stamp='2026-10-17T00:00:00Z'
const payload=()=>({title:'合成冷却液资料',publisher:'合成出版者',document_version:'v1',source_reference:'synthetic source',license_reference:'internal permission reference',license_confirmed:true,model_id:1,valid_from:stamp,valid_to:'2026-12-31T00:00:00Z',search_allowed:true,excerpt_allowed:true,ai_allowed:false,body:'冷却液合成测试资料，不能作为正式维修依据。'})
const document=()=>({id:1,revision:1,status:'DRAFT',source_hash:'a'.repeat(64),public_id:null,payload:payload(),created_at:stamp,published_at:null})
const reference=()=>({reference:key,revision:2,model_id:1,title:'合成资料',publisher:'合成出版者',document_version:'v1',source_reference:'synthetic-source',valid_from:stamp,valid_to:'2026-12-31T00:00:00Z',excerpt:'合成摘录',ai_allowed:true})
const page=items=>({items,total:items.length,page:1,page_size:20})
const flush=async()=>{for(let i=0;i<20;i++)await Promise.resolve()}
const disk=()=>{const m=new Map();return {getStorageSync:k=>m.get(k),setStorageSync:(k,v)=>m.set(k,structuredClone(v)),removeStorageSync:k=>m.delete(k)}}
test('knowledge config validates exact license scopes and model without using placeholder data',()=>{
 assert.ok(validKnowledgePayload(payload()));assert.ok(validKnowledgeBody(payload(),'create'))
 assert.equal(validKnowledgePayload({...payload(),license_confirmed:'true'}),false)
 assert.equal(validKnowledgePayload({...payload(),model_id:0}),false)
 assert.equal(validKnowledgeBody({...payload(),expected_revision:1},'create'),false)
 assert.ok(validKnowledgeBody({...payload(),expected_revision:1},'update'))
})
test('current references reject internal license leaks, wrong model and unregistered routes',()=>{
 assert.ok(validKnowledgeReference(reference(),1));assert.equal(validKnowledgeReference({...reference(),license_reference:'private'},1),false)
 assert.equal(validKnowledgeReference(reference(),2),false);assert.equal(knowledgeRoute({reference:key,model_id:'1'}),null)
 assert.deepEqual(knowledgeRoute({reference:key}),{reference:key});assert.ok(knowledgeReference(key).endsWith(key))
})
test('knowledge permission is explicit, independently cleared and has a real login destination',()=>{
 const user={id:1,role:'operator',can_review:false,can_onboard:false,can_catalog:false,can_coupon:false,can_invite:false,can_diagnose:false,can_diagnose_manage:false,can_points:false,can_assessment:false,can_analytics:false,can_price_monitor:false,can_community_curate:false,can_community_answer:false,can_community_manage:false,can_knowledge:true}
 assert.ok(validOperatorUser(user));setOperatorSession({user,access_token:'operator',expires_in:900});assert.equal(operatorSession.canKnowledge,true);assert.equal(operatorSession.canReview,false);assert.equal(operatorLandingPage(),'/pages/operator/knowledge')
 const pages=[{route:'pages/operator/knowledge',options:{}},{}];assert.equal(serviceLoginReturn(pages,'admin',operatorSession),true)
 delete user.can_knowledge;assert.ok(validOperatorUser(user));setOperatorSession({user,access_token:'legacy',expires_in:900});assert.equal(operatorSession.canKnowledge,false);assert.equal(serviceLoginReturn(pages,'admin',operatorSession),false);clearOperatorSession()
})
test('search client encodes keywords and preserves unknown sparse cursors without claiming vector results',async()=>{
 let result={vehicle_id:1,state:'UNKNOWN',method:'KEYWORD',items:[],next_cursor:5},sent
 const api=createKnowledgeApi({baseUrl:'https://example.test',runtime:()=>({request:r=>{sent=r;r.success({statusCode:200,data:{code:0,data:result}})}})})
 assert.equal((await api.search('owner',1,1,'冷却液 & ?',9)).next_cursor,5);assert.match(sent.url,/q=%E5%86%B7/)
 assert.equal(sent.header.Authorization,'Bearer owner');result.method='VECTOR';await assert.rejects(api.search('owner',1,1,'冷却液'),e=>e.kind==='protocol')
})
test('AI references must be explicitly AI-licensed and bound to the selected model',async()=>{
 let value={vehicle_id:1,reply:'合成参考',model:'synthetic-model',provided_sources:[reference()],method:'KEYWORD'}
 const api=createKnowledgeApi({baseUrl:'https://example.test',runtime:()=>({request:r=>r.success({statusCode:200,data:{code:0,data:value}})})})
 assert.equal((await api.answer('owner',1,1,'冷却液')).provided_sources.length,1)
 value.provided_sources[0].ai_allowed=false;await assert.rejects(api.answer('owner',1,1,'冷却液'),e=>e.kind==='protocol')
 value.provided_sources=[];await assert.rejects(api.answer('owner',1,1,'冷却液'),e=>e.kind==='protocol')
})
test('knowledge permanent create recovery is isolated from owner and expires status operations',()=>{
 const storage=disk();let now=10
 const options={actor:()=>1,role:()=> 'admin',scope:()=> 'documents',runtime:()=>storage,now:()=>now},r=createKnowledgeRecovery(options),intent={action:'create',id:0,body:payload(),key}
 r.savePending(intent);now+=25*3600000;assert.deepEqual(r.read().pending,intent)
 const other=createKnowledgeRecovery({...options,actor:()=>2});assert.equal(other.read().pending,null);r.complete(intent)
 r.savePending({action:'status',id:1,body:{expected_revision:1,status:'PUBLISHED'},key});now+=24*3600000;assert.throws(()=>r.read(),/23小时/)
})
function operatorHarness(){
 const hooks={},calls=[],operator=reactive({accessToken:'operator',actorId:1,canKnowledge:true}),storage=disk(),scope=effectScope()
 const api={admin:async()=>page([document()]),models:async()=>page([{model_id:1,brand:'合成',series:'车系',year:'2026',config_name:'配置'}]),write:async(...args)=>{calls.push(args);return {}}}
 const env={reactive,ref,watch,onShow:f=>hooks.show=f,onHide:f=>hooks.hide=f,onUnload:f=>hooks.unload=f,operatorSession:operator,operatorToken:()=>operator.accessToken,createKnowledgeApi:()=>api,createKnowledgeRecovery:o=>createKnowledgeRecovery({...o,runtime:()=>storage}),validKnowledgeBody,KNOWLEDGE_FIELDS,initialDiagnosisState,createAsyncDiagnosisFlow,createQuestionConfirmation,pendingQuestion,imageRequestKey:()=>key,uni:{navigateTo:()=>{}}}
 const source=readFileSync(new URL('../src/pages/operator/knowledge.vue',import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'')
 const view=scope.run(()=>new Function(...Object.keys(env),source+';return {state,form,editing,edit,save,status,confirmation,gate}')(...Object.values(env)));hooks.show()
 return {hooks,calls,operator,view,dispose:()=>scope.stop()}
}
test('actual operator clears reused license consent and freezes complete publication confirmation',async()=>{
 const h=operatorHarness();try{await flush();const row=h.view.state.data.items[0];h.view.edit(row);assert.equal(h.view.form.license_confirmed,false);assert.equal(h.view.form.ai_allowed,false);h.view.status(row,'PUBLISHED');assert.ok(h.view.confirmation.prompt.lines.includes(payload().body));assert.ok(h.view.confirmation.prompt.lines.includes(payload().license_reference));row.revision=8;h.view.gate.settle(true);await flush();assert.equal(h.calls[0][3].expected_revision,1)}finally{h.dispose()}
})
test('actual operator rejects late confirmation after permission is revoked',async()=>{
 const h=operatorHarness();try{await flush();h.view.status(h.view.state.data.items[0],'PUBLISHED');h.operator.canKnowledge=false;h.view.gate.settle(true);await flush();assert.equal(h.calls.length,0);assert.equal(h.view.state.data,null)}finally{h.dispose()}
})
function ownerHarness(overrides={}){
 const hooks={},calls=[],owner=reactive({accessToken:'owner',actorId:1}),vehicle=ref({vehicle_id:1,model_id:1}),scope=effectScope()
 const api={search:async()=>({vehicle_id:1,state:'UNKNOWN',method:'KEYWORD',items:[],next_cursor:null}),answer:async(...args)=>{calls.push(args);return {reply:'合成参考'}},...overrides}
 const env={reactive,ref,watch,onLoad:f=>hooks.load=f,onShow:f=>hooks.show=f,onHide:f=>hooks.hide=f,onUnload:f=>hooks.unload=f,ownerSession:owner,selectedOwnerVehicle:vehicle,selectOwnerVehicle:v=>vehicle.value=v,createKnowledgeApi:()=>api,knowledgeRoute,knowledgeReference,initialDiagnosisState,createAsyncDiagnosisFlow,createQuestionConfirmation,uni:{navigateTo:()=>{},setClipboardData:()=>{}}}
 const source=readFileSync(new URL('../src/pages/community/knowledge.vue',import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'')
 const view=scope.run(()=>new Function(...Object.keys(env),source+';return {state,question,query,search,answer,confirmation,gate}')(...Object.values(env)));hooks.load({});hooks.show()
 return {hooks,calls,owner,vehicle,view,dispose:()=>scope.stop()}
}
test('actual owner AI flow requires full use confirmation and sends the frozen original question',async()=>{
 const h=ownerHarness();try{h.view.question.value='冷却液资料问题';const pending=h.view.answer();assert.equal(h.calls.length,0);assert.ok(h.view.confirmation.prompt.lines.includes('冷却液资料问题'));h.view.question.value='之后编辑的文字';h.view.gate.settle(true);await pending;assert.equal(h.calls[0][3],'冷却液资料问题')}finally{h.dispose()}
})
test('actual owner page discards late AI result after switching vehicle or hiding',async()=>{
 let resolve
 const h=ownerHarness({answer:()=>new Promise(r=>resolve=r)});try{h.view.question.value='资料问题';const pending=h.view.answer();h.view.gate.settle(true);await flush();h.vehicle.value={vehicle_id:2,model_id:2};resolve({reply:'old-model-answer'});await pending;assert.equal(h.view.state.data,null)}finally{h.dispose()}
})

test('embedding permission is a strict independent versioned pair and legacy stays valid',()=>{
 assert.ok(validKnowledgePayload(payload()))
 const current={...payload(),embedding_allowed:false,embedding_policy_version:'KNOWLEDGE_EMBEDDING_V1'}
 assert.ok(validKnowledgePayload(current));assert.ok(validKnowledgeBody({...current,embedding_allowed:true},'create'))
 for(const changes of [{embedding_allowed:true,embedding_policy_version:undefined},{embedding_allowed:'true'},{embedding_policy_version:'OLD'},{embedding_allowed:null}])assert.equal(validKnowledgePayload({...current,...changes}),false)
 assert.equal(validKnowledgePayload({...payload(),embedding_allowed:true}),false)
})
test('operator new and edited drafts never inherit embedding consent',async()=>{
 const h=operatorHarness();try{await flush();assert.equal(h.view.form.embedding_allowed,false);assert.equal(h.view.form.embedding_policy_version,'KNOWLEDGE_EMBEDDING_V1');const row=h.view.state.data.items[0];row.payload.embedding_allowed=true;row.payload.embedding_policy_version='KNOWLEDGE_EMBEDDING_V1';h.view.edit(row);assert.equal(h.view.form.embedding_allowed,false);assert.equal(h.view.form.embedding_policy_version,'KNOWLEDGE_EMBEDDING_V1') }finally{h.dispose()}
})
test('full draft confirmation freezes independent embedding consent and policy',async()=>{
 const h=operatorHarness();try{await flush();h.view.edit(h.view.state.data.items[0]);for(const [key,value] of Object.entries(payload()))h.view.form[key]=key==='model_id'?String(value):value;h.view.form.embedding_allowed=true;h.view.save();assert.ok(h.view.confirmation.prompt.lines.some(line=>line.includes('本地私有嵌入 true')&&line.includes('KNOWLEDGE_EMBEDDING_V1')));h.view.form.embedding_allowed=false;h.view.gate.settle(true);await flush();assert.equal(h.calls[0][3].embedding_allowed,true);assert.equal(h.calls[0][3].embedding_policy_version,'KNOWLEDGE_EMBEDDING_V1')}finally{h.dispose()}
})
test('legacy and new pending creation bodies retain original fields and key',()=>{
 for(const body of [payload(),{...payload(),embedding_allowed:true,embedding_policy_version:'KNOWLEDGE_EMBEDDING_V1'}]){const runtime=disk(),r=createKnowledgeRecovery({actor:()=>1,role:()=> 'admin',scope:()=> 'documents',runtime:()=>runtime,now:()=>1});const original={action:'create',id:0,body,key};r.savePending(original);assert.deepEqual(r.read().pending,original)}
})
test('mounted synchronous token watcher cannot erase operator actor during login',()=>{
 clearOperatorSession();const scope=effectScope()
 try{scope.run(()=>watch(()=>[operatorToken(),operatorSession.actorId,operatorSession.canKnowledge],()=>{operatorToken()},{flush:'sync'}));setOperatorSession({access_token:'synthetic-new-operator',expires_in:900,user:{id:7,can_review:false,can_onboard:false,can_knowledge:true}});assert.equal(operatorSession.actorId,7);assert.equal(operatorToken(),'synthetic-new-operator');assert.equal(operatorSession.canKnowledge,true)}finally{scope.stop();clearOperatorSession()}
})
test('malformed primitive payloads reject safely instead of throwing inside response callbacks',async()=>{
 for(const value of [undefined,null,true,5,'invalid',[],{}]){assert.equal(validKnowledgePayload(value),false);assert.equal(validKnowledgeBody(value,'create'),false)}
 const calls=[],api=createKnowledgeApi({baseUrl:'https://fixture.test',runtime:()=>({request:o=>calls.push(o)})});const pending=api.admin('operator');calls[0].success({statusCode:200,data:{code:0,data:page([{...document(),payload:'invalid'}])}});await assert.rejects(pending,e=>e.kind==='protocol')
})

test('operator embedding status is optional, strict and never implies public VECTOR',async()=>{
 const {validKnowledgeEmbedding,embeddingStateLabel,validKnowledgeRow}=await import('../src/services/licensed-knowledge.js')
 const enabled={state:'SUCCEEDED',error_code:null,profile_hash:'a'.repeat(64),updated_at:stamp}
 assert.ok(validKnowledgeEmbedding(enabled));assert.ok(validKnowledgeRow({...document(),embedding:enabled}))
 assert.ok(validKnowledgeEmbedding({state:'DISABLED',error_code:null,profile_hash:null,updated_at:null}))
 for(const state of ['NOT_ALLOWED','NOT_QUEUED'])assert.ok(validKnowledgeEmbedding({...enabled,state,updated_at:null}))
 for(const error_code of ['WORKER_LOST','UNAVAILABLE','INVALID_VECTOR','LIMIT_EXCEEDED'])assert.ok(validKnowledgeEmbedding({...enabled,state:'FAILED',error_code}))
 assert.match(embeddingStateLabel('SUCCEEDED'),/已生成/);assert.match(embeddingStateLabel('FAILED'),/不自动重试/)
 for(const bad of [{...enabled,state:'VECTOR'},{...enabled,error_code:'UNAVAILABLE'},{...enabled,profile_hash:'bad'},{...enabled,updated_at:null},{...enabled,vector:[1]},{...enabled,state:'FAILED',error_code:'private diagnostic'},{...enabled,state:'DISABLED'},{...enabled,state:'CANCELLED',error_code:'LIMIT_EXCEEDED'},null])assert.equal(validKnowledgeEmbedding(bad),false)
})
test('admin list validates new embedding state while legacy receipts stay accepted',async()=>{
 let row={...document(),embedding:{state:'QUEUED',error_code:null,profile_hash:'a'.repeat(64),updated_at:stamp}}
 const api=createKnowledgeApi({baseUrl:'https://example.test',runtime:()=>({request:r=>r.success({statusCode:200,data:{code:0,data:page([row])}})})})
 assert.equal((await api.admin('operator')).items[0].embedding.state,'QUEUED')
 row.embedding.private_token='must never arrive';await assert.rejects(api.admin('operator'),e=>e.kind==='protocol')
 delete row.embedding;assert.equal((await api.admin('operator')).items.length,1)
})

test('vector references preserve Unicode and boundary whitespace with exact positions',()=>{
 const excerpt=' 片段😀\n',r={...reference(),excerpt,chunk:1,start_offset:224,end_offset:224+excerpt.length}
 assert.ok(validKnowledgeReference(r,1));assert.ok(validKnowledgeReference({...r,body:excerpt},1,true))
 for(const bad of [{...r,end_offset:r.end_offset+1},{...r,chunk:64},{...r,start_offset:-1},{...r,body:'wrong'}, {...r,excerpt:'x\u0085'}, {...r,score:.9}])assert.equal(validKnowledgeReference(bad,1,Boolean(bad.body)),false)
 assert.ok(validKnowledgeReference({...reference(),excerpt:'边界空白 '},1))
})
test('positioned knowledge routes carry only a bounded chunk and reference',()=>{
 assert.deepEqual(knowledgeRoute({reference:key,chunk:'0'}),{reference:key,chunk:0});assert.match(knowledgeReference(key,63),/&chunk=63$/)
 for(const chunk of ['-1','64','00','1.0','NaN'])assert.equal(knowledgeRoute({reference:key,chunk}),null)
 assert.equal(knowledgeRoute({chunk:'0'}),null);assert.equal(knowledgeReference(key,64),'')
})
test('search accepts complete vector shape and rejects mislabeled mixed or paginated results',async()=>{
 const r={...reference(),chunk:0,start_offset:0,end_offset:reference().excerpt.length}
 let value={vehicle_id:1,state:'AVAILABLE',method:'VECTOR',items:[r],next_cursor:null}
 const api=createKnowledgeApi({baseUrl:'https://example.test',runtime:()=>({request:q=>q.success({statusCode:200,data:{code:0,data:value}})})})
 assert.equal((await api.search('owner',1,1,'刹车')).method,'VECTOR')
 for(const change of [{fallback_reason:'VECTOR_UNAVAILABLE'},{next_cursor:5},{items:[reference()]},{state:'UNKNOWN'},{items:[r,r]},{items:[]}]){const old=value;value={...old,...change};await assert.rejects(api.search('owner',1,1,'刹车'),e=>e.kind==='protocol');value=old}
 await assert.rejects(api.search('owner',1,1,'刹车',5),e=>e.kind==='protocol')
})
test('keyword fallbacks are explicit known reasons and never carry vector chunks',async()=>{
 let value={vehicle_id:1,state:'AVAILABLE',method:'KEYWORD',items:[reference()],next_cursor:null,fallback_reason:'VECTOR_UNAVAILABLE'}
 const api=createKnowledgeApi({baseUrl:'https://example.test',runtime:()=>({request:q=>q.success({statusCode:200,data:{code:0,data:value}})})})
 assert.equal((await api.search('owner',1,1,'冷却')).fallback_reason,'VECTOR_UNAVAILABLE');value.fallback_reason='private upstream details';await assert.rejects(api.search('owner',1,1,'冷却'),e=>e.kind==='protocol')
 value.fallback_reason='INDEX_NOT_READY';value.items=[{...reference(),chunk:0,start_offset:0,end_offset:reference().excerpt.length}];await assert.rejects(api.search('owner',1,1,'冷却'),e=>e.kind==='protocol')
})
test('positioned detail binds requested chunk and copies an independently verifiable route',async()=>{
 const r={...reference(),body:reference().excerpt,chunk:1,start_offset:224,end_offset:224+reference().excerpt.length};let value={vehicle_id:1,document:r},sent
 const api=createKnowledgeApi({baseUrl:'https://example.test',runtime:()=>({request:q=>{sent=q;q.success({statusCode:200,data:{code:0,data:value}})}})})
 assert.equal((await api.detail('owner',1,1,key,1)).document.body,r.excerpt);assert.match(sent.url,/&chunk=1$/)
 value.document.chunk=2;await assert.rejects(api.detail('owner',1,1,key,1),e=>e.kind==='protocol');await assert.rejects(api.detail('owner',1,1,key),e=>e.kind==='protocol')
})
test('AI vector sources require positions and keyword fallbacks retain source semantics',async()=>{
 let value={vehicle_id:1,reply:'合成AI参考',model:'synthetic-ai',method:'VECTOR',provided_sources:[{...reference(),chunk:0,start_offset:0,end_offset:reference().excerpt.length}]}
 const api=createKnowledgeApi({baseUrl:'https://example.test',runtime:()=>({request:q=>q.success({statusCode:200,data:{code:0,data:value}})})})
 assert.equal((await api.answer('owner',1,1,'冷却')).method,'VECTOR');value.provided_sources=[reference()];await assert.rejects(api.answer('owner',1,1,'冷却'),e=>e.kind==='protocol')
 value.method='KEYWORD';value.fallback_reason='QUERY_LIMIT';assert.equal((await api.answer('owner',1,1,'冷却')).method,'KEYWORD');value.fallback_reason='CURSOR_PAGING';await assert.rejects(api.answer('owner',1,1,'冷却'),e=>e.kind==='protocol')
})
