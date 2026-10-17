import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {reactive,ref,watch,effectScope} from 'vue'
import {createPublicQaApi,createPublicQaRecovery,validQaPreview,validQaBody,publicQaRoute,publicQaReference,PUBLIC_QA_CONSENT,PUBLIC_QA_STATES} from '../src/services/public-community-questions.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {createQuestionConfirmation,pendingQuestion} from '../src/services/community-question-recovery.js'
import {QUESTION_CATEGORIES} from '../src/services/community-questions.js'
import {TEXT_REASONS} from '../src/services/public-review-texts.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
const key='11111111-1111-4111-8111-111111111111',stamp='2026-10-17T00:00:00Z'
const preview=()=>({model_id:1,category:'FAULT',question_text:'低速异响\n请核查',answer_text:'建议到店检查',redaction_version:'QA_TEXT_REDACTION_V1'})
const submit=()=>({question_message_id:1,answer_message_id:2,preview_hash:'a'.repeat(64),consent_version:PUBLIC_QA_CONSENT,consent:true})
const page=items=>({items,total:items.length,page:1,page_size:20})
const flush=async()=>{for(let i=0;i<15;i++)await Promise.resolve()}
function disk(){const data=new Map();return {data,getStorageSync:k=>data.get(k),setStorageSync:(k,v)=>data.set(k,structuredClone(v)),removeStorageSync:k=>data.delete(k)}}
test('preview accepts newlines but rejects extra private identifiers and wrong redaction version',()=>{
 assert.ok(validQaPreview(preview()));assert.equal(validQaPreview({...preview(),owner_id:1}),false)
 assert.equal(validQaPreview({...preview(),redaction_version:'old'}),false)
 assert.equal(validQaPreview({...preview(),question_text:'x'.repeat(301)}),false)
})
test('separate public consent and exact moderation reasons are mandatory',()=>{
 assert.ok(validQaBody(submit(),'submit'));assert.equal(validQaBody({...submit(),consent:false},'submit'),false)
 assert.equal(validQaBody({...submit(),answer_message_id:1},'submit'),false)
 assert.ok(validQaBody({expected_revision:1,decision:'REJECT',reason_code:'PRIVACY'},'moderate'))
 assert.equal(validQaBody({expected_revision:1,decision:'REJECT',reason_code:'NOT_SUITABLE'},'moderate'),false)
})
test('routes and login return distinguish reviewer and owner without accepting stray selectors',()=>{
 assert.equal(publicQaRoute({mode:'owner',case_id:'01'}),null)
 assert.equal(publicQaRoute({mode:'review',case_id:'1'}),null)
 assert.equal(publicQaRoute({mode:'public',public_id:key}).publicId,key)
 const pages=[{route:'pages/community/public-questions',options:{mode:'review'}},{}]
 assert.equal(serviceLoginReturn(pages,'admin',{canReview:true}),true)
 assert.equal(serviceLoginReturn(pages,'owner'),false)
 assert.equal(serviceLoginReturn(pages,'admin',{canReview:false}),false)
 assert.ok(publicQaReference(key).endsWith(key))
})
test('public client accepts sparse pages and rejects a different model or leaked private data',async()=>{
 let value={vehicle_id:2,items:[],next_cursor:7},sent
 const api=createPublicQaApi({baseUrl:'https://example.test',runtime:()=>({request:r=>{sent=r;r.success({statusCode:200,data:{code:0,data:value}})}})})
 assert.equal((await api.list('owner',2,1,9)).next_cursor,7);assert.match(sent.url,/cursor=9$/)
 value={vehicle_id:2,question:{public_id:key,model_id:2,category:'FAULT',question_text:'问题',answer_text:'答复',published_at:stamp}}
 await assert.rejects(api.detail('owner',2,1,key),e=>e.kind==='protocol')
 value.question.model_id=1;value.question.case_id=12
 await assert.rejects(api.detail('owner',2,1,key),e=>e.kind==='protocol')
})
test('review queue source-unavailable state is readable but malformed preview is rejected',async()=>{
 let value=page([{application_id:1,revision:1,status:'PENDING',preview:null,created_at:stamp,source_available:false}])
 const api=createPublicQaApi({baseUrl:'https://example.test',runtime:()=>({request:r=>r.success({statusCode:200,data:{code:0,data:value}})})})
 assert.equal((await api.pending('reviewer')).items[0].preview,null)
 value.items[0].source_available=true
 await assert.rejects(api.pending('reviewer'),e=>e.kind==='protocol')
})
test('public create recovery survives expiry while withdraw recovery expires and actors stay isolated',()=>{
 const storage=disk();let actor=1,time=10
 const options={actor:()=>actor,role:()=> 'owner',scope:()=> 'case:3',runtime:()=>storage,now:()=>time}
 const r=createPublicQaRecovery(options),intent={action:'submit',id:3,body:submit(),key}
 r.savePending(intent);time+=24*3600000;assert.deepEqual(r.read().pending,intent)
 actor=2;assert.equal(r.read().pending,null);actor=1;r.complete(intent)
 r.savePending({action:'withdraw',id:5,body:{expected_revision:1},key});time+=24*3600000
 assert.throws(()=>r.read(),/23小时/)
})
test('storage change prevents clearing another page intent and failed readback blocks dispatch',async()=>{
 const storage=disk(),r=createPublicQaRecovery({actor:()=>1,role:()=> 'owner',scope:()=> 'case:3',runtime:()=>storage})
 r.read();const intent={action:'submit',id:3,body:submit(),key};r.savePending(intent)
 const marker=r.marker();storage.data.set([...storage.data.keys()][0],{changed:true});assert.throws(()=>r.discard(marker),/其他页面/)
 let writes=0
 const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=> 't',scope:()=> 's',load:async()=>({loaded:true}),write:async()=>writes++,confirm:async()=>true,newKey:()=>key,recovery:{read:()=>({pending:null}),savePending:()=>{throw Error('disk failed')}}})
 await flow.load();await flow.save('submit',3,submit());assert.equal(writes,0);assert.equal(state.kind,'storage')
})
function harness(mode='owner',overrides={}){
 const hooks={},calls=[],owner=reactive({accessToken:'owner',actorId:1}),operator=reactive({accessToken:'reviewer',actorId:2,canReview:true}),vehicle=ref({vehicle_id:2,model_id:1}),storage=disk(),scope=effectScope()
 const api={own:async()=>page([]),pending:async()=>page([{application_id:4,revision:1,status:'PENDING',preview:preview(),source_available:true,created_at:stamp}]),preview:async(...args)=>{calls.push(['preview',...args]);return {preview:preview(),preview_hash:'a'.repeat(64)}},write:async(...args)=>{calls.push(['write',...args]);return {}},list:async()=>({vehicle_id:2,items:[],next_cursor:null}),...overrides}
 const privateApi={detail:async()=>{calls.push(['private']);return {messages:page([{message_id:1,sender:'OWNER',body:'原问题'},{message_id:2,sender:'OPERATOR',body:'原答复'}])}}}
 const env={reactive,ref,watch,onLoad:f=>hooks.load=f,onShow:f=>hooks.show=f,onHide:f=>hooks.hide=f,onUnload:f=>hooks.unload=f,ownerSession:owner,operatorSession:operator,operatorToken:()=>operator.accessToken,selectedOwnerVehicle:vehicle,selectOwnerVehicle:v=>vehicle.value=v,createCommunityQuestionApi:()=>privateApi,QUESTION_CATEGORIES,createPublicQaApi:()=>api,createPublicQaRecovery:o=>createPublicQaRecovery({...o,runtime:()=>storage}),publicQaRoute,publicQaReference,PUBLIC_QA_CONSENT,PUBLIC_QA_STATES,TEXT_REASONS,initialDiagnosisState,createAsyncDiagnosisFlow,createQuestionConfirmation,pendingQuestion,imageRequestKey:()=>key,uni:{navigateTo:()=>{},setClipboardData:()=>{}}}
 const source=readFileSync(new URL('../src/pages/community/public-questions.vue',import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'')
 const view=scope.run(()=>new Function(...Object.keys(env),source+';return {state,select,preview,submit,action,gate,confirmation,proposal,consent,selectedQuestion,selectedAnswer,next}')(...Object.values(env)))
 hooks.load(mode==='owner'?{mode,case_id:'3'}:{mode});hooks.show()
 return {hooks,view,calls,owner,operator,vehicle,dispose:()=>scope.stop()}
}
test('actual owner page confirms complete frozen preview and keeps public consent separate',async()=>{
 const h=harness();try{await flush();h.view.select({message_id:1,sender:'OWNER'});h.view.select({message_id:2,sender:'OPERATOR'});await h.view.preview();h.view.submit();assert.equal(h.calls.some(c=>c[0]==='write'),false)
 h.view.consent.value=true;h.view.submit();assert.ok(h.view.confirmation.prompt.lines.includes(preview().question_text));assert.ok(h.view.confirmation.prompt.lines.includes(preview().answer_text));h.view.selectedAnswer.value=9;h.view.gate.settle(true);await flush();assert.equal(h.calls.find(c=>c[0]==='write')[4].answer_message_id,2)
 }finally{h.dispose()}
})
test('actual reviewer never reads private detail and requires a valid source to approve',async()=>{
 const h=harness('review');try{await flush();assert.equal(h.calls.some(c=>c[0]==='private'),false);const row=h.view.state.data.applications.items[0];row.source_available=false;h.view.action(row,'APPROVE');assert.equal(h.view.confirmation.prompt,null);row.source_available=true;h.view.action(row,'APPROVE');assert.ok(h.view.confirmation.prompt);h.operator.canReview=false;h.view.gate.settle(true);await flush();assert.equal(h.calls.some(c=>c[0]==='write'),false);assert.equal(h.view.state.data,null)}finally{h.dispose()}
})
test('actual owner page ignores preview arriving after hide and keeps cross-page selected IDs',async()=>{
 let resolve
 const h=harness('owner',{preview:()=>new Promise(r=>resolve=r)});try{await flush();h.view.select({message_id:1,sender:'OWNER'});h.view.select({message_id:2,sender:'OPERATOR'});h.view.next();await flush();assert.equal(h.view.selectedQuestion.value,1);assert.equal(h.view.selectedAnswer.value,2);const pending=h.view.preview();h.hooks.hide();resolve({preview:preview(),preview_hash:'a'.repeat(64)});await pending;assert.equal(h.view.proposal.value,null);assert.equal(h.view.state.data,null)}finally{h.dispose()}
})
