import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {reactive,ref,watch,effectScope} from 'vue'
import {createPublicImageApi,createPublicImageRecovery,validImageSelection,validPublicImageBody,publicImageRoute,PUBLIC_IMAGE_CONSENT} from '../src/services/public-images.js'
import {createScopedIntentRecovery} from '../src/services/scoped-intent-recovery.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {createQuestionConfirmation,pendingQuestion} from '../src/services/community-question-recovery.js'
import {TEXT_REASONS} from '../src/services/public-review-texts.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
const key='11111111-1111-4111-8111-111111111111',stamp='2026-10-17T00:00:00Z',png='iVBORw0KGgoAAA=='
const selection=()=>({experience_id:key,archive_id:1,source_file_id:2,copy_file_id:3})
const body=()=>({...selection(),preview_hash:'a'.repeat(64),consent_version:PUBLIC_IMAGE_CONSENT,consent:true})
const row=()=>({application_id:4,revision:1,status:'PENDING',preview_hash:'a'.repeat(64),experience_id:key,model_id:1,width:3,height:2,created_at:stamp,reason_code:null,source_available:true})
const page=items=>({items,total:items.length,page:1,page_size:20})
const flush=async()=>{for(let i=0;i<15;i++)await Promise.resolve()}
const disk=()=>{const m=new Map();return {getStorageSync:k=>m.get(k),setStorageSync:(k,v)=>m.set(k,structuredClone(v)),removeStorageSync:k=>m.delete(k)}}
test('image copy selectors require independent files and affirmative versioned consent',()=>{
 assert.ok(validImageSelection(selection()));assert.equal(validImageSelection({...selection(),copy_file_id:2}),false)
 assert.ok(validPublicImageBody(body(),'submit'));assert.equal(validPublicImageBody({...body(),consent:false},'submit'),false)
 assert.equal(validPublicImageBody({...body(),object_key:'private'},'submit'),false)
})
test('image routes and login scopes reject reviewer access by owners and extra selectors',()=>{
 assert.equal(publicImageRoute({mode:'review',experience_id:key}),null)
 assert.deepEqual(publicImageRoute({mode:'public',experience_id:key}),{mode:'public',reference:key})
 const pages=[{route:'pages/community/public-images',options:{mode:'review'}},{}]
 assert.equal(serviceLoginReturn(pages,'admin',{canReview:true}),true);assert.equal(serviceLoginReturn(pages,'owner'),false)
})
test('image bytes use authenticated JSON transport compatible with cloud container and never signed URLs',async()=>{
 let sent
 const api=createPublicImageApi({baseUrl:'https://example.test',runtime:()=>({request:r=>{sent=r;r.success({statusCode:200,data:{code:0,data:{png_base64:png}}})}})})
 assert.equal((await api.bytes('owner',2,key)).png_base64,png)
 assert.ok(sent.url.endsWith('?vehicle_id=2&format=base64'));assert.equal(sent.header.Authorization,'Bearer owner');assert.equal(sent.responseType,undefined)
 assert.equal((await api.previewBytes('reviewer','admin',4)).png_base64,png)
 assert.match(sent.url,/\/api\/admin\/community\/images\/4\/preview\?format=base64$/)
})
test('image client rejects wrong parent, leaked fields, dimensions and malformed copies',async()=>{
 let value={vehicle_id:2,experience_id:key,items:[{public_id:key,width:3,height:2,published_at:stamp}]}
 const api=createPublicImageApi({baseUrl:'https://example.test',runtime:()=>({request:r=>r.success({statusCode:200,data:{code:0,data:value}})})})
 assert.equal((await api.list('t',2,key)).items.length,1);value.items[0].object_key='private'
 await assert.rejects(api.list('t',2,key),e=>e.kind==='protocol')
 value={png_base64:'not-image'};await assert.rejects(api.bytes('t',2,key),e=>e.kind==='protocol')
 value={preview_hash:'a'.repeat(64),png_base64:png,width:4097,height:1,model_id:1};await assert.rejects(api.preview('t',selection()),e=>e.kind==='protocol')
})
test('image and public question recoveries cannot share a namespace and storage errors block dispatch',()=>{
 const storage=disk(),options={actor:()=>1,role:()=> 'owner',scope:()=> 'owner',runtime:()=>storage,now:()=>5}
 const a=createPublicImageRecovery(options),other=createScopedIntentRecovery({...options,namespace:'public-community',validate:()=>true})
 a.savePending({action:'submit',id:0,body:body(),key});assert.equal(other.read().pending,null)
 assert.equal(a.read().pending.body.copy_file_id,3)
 assert.throws(()=>a.savePending({action:'withdraw',id:4,body:{expected_revision:1},key}),/未决请求/)
})
function harness(mode='review',overrides={}){
 const hooks={},calls=[],owner=reactive({accessToken:'owner',actorId:1}),operator=reactive({accessToken:'reviewer',actorId:2,canReview:true}),vehicle=ref({vehicle_id:2,model_id:1}),storage=disk(),scope=effectScope()
 const api={pending:async()=>page([row()]),own:async()=>page([]),sources:async()=>page([{archive_id:1,source_file_id:2}]),previewBytes:async(...a)=>{calls.push(['bytes',...a]);return {png_base64:png}},write:async(...a)=>{calls.push(['write',...a]);return {}},preview:async()=>({preview_hash:'a'.repeat(64),png_base64:png,width:3,height:2,model_id:1}),...overrides}
 const imageApi={choose:async()=>({path:'/local/copy.png',size:50}),upload:async(...a)=>{calls.push(['upload',...a]);return {file_id:3}},access:async()=>({url:'https://private.example.test'}),preview:async()=>{}}
 const env={reactive,ref,watch,onLoad:f=>hooks.load=f,onShow:f=>hooks.show=f,onHide:f=>hooks.hide=f,onUnload:f=>hooks.unload=f,ownerSession:owner,operatorSession:operator,operatorToken:()=>operator.accessToken,selectedOwnerVehicle:vehicle,selectOwnerVehicle:v=>vehicle.value=v,createPublicImageApi:()=>api,createPublicImageRecovery:o=>createPublicImageRecovery({...o,runtime:()=>storage}),publicImageRoute,PUBLIC_IMAGE_CONSENT,createScopedIntentRecovery:o=>createScopedIntentRecovery({...o,runtime:()=>storage}),intentUuid:n=>n===key,initialDiagnosisState,createAsyncDiagnosisFlow,createQuestionConfirmation,pendingQuestion,imageApi,imageRequestKey:()=>key,MAX_IMAGE_BYTES:10485760,TEXT_REASONS,uni:{navigateTo:()=>{}}}
 const source=readFileSync(new URL('../src/pages/community/public-images.vue',import.meta.url),'utf8').match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'')
 const view=scope.run(()=>new Function(...Object.keys(env),source+';return {state,uploadState,choose,upload,select,preview,submit,act,view,gate,confirmation,seen,pictures,copyId,proposal,proposalViewed,consent,reference,useReference}')(...Object.values(env)))
 hooks.load(mode==='owner'?{mode,experience_id:key}:{mode});hooks.show()
 return {view,hooks,calls,operator,owner,dispose:()=>scope.stop()}
}
test('actual reviewer requires rendered current copy before approving and confirms exact image hash',async()=>{
 const h=harness();try{await flush();const r=h.view.state.data.applications.items[0];h.view.act(r,'APPROVE');assert.equal(h.view.confirmation.prompt,null);await h.view.view(r);h.view.act(r,'APPROVE');assert.equal(h.view.confirmation.prompt,null);h.view.seen.value[4]=r.preview_hash;h.view.act(r,'APPROVE');assert.ok(h.view.confirmation.prompt.lines[0].includes(r.preview_hash));h.view.gate.settle(true);await flush();assert.equal(h.calls.find(c=>c[0]==='write')[2],'moderate')}finally{h.dispose()}
})
test('actual page drops image bytes on hide and permission revocation cancels pending approval',async()=>{
 let resolve
 const h=harness('review',{previewBytes:()=>new Promise(r=>resolve=r)});try{await flush();const request=h.view.view(h.view.state.data.applications.items[0]);h.hooks.hide();resolve({png_base64:png});await request;assert.deepEqual(h.view.pictures.value,{})}finally{h.dispose()}
 const g=harness();try{await flush();const r=g.view.state.data.applications.items[0];await g.view.view(r);g.view.seen.value[4]=r.preview_hash;g.view.act(r,'APPROVE');g.operator.canReview=false;g.view.gate.settle(true);await flush();assert.equal(g.calls.some(c=>c[0]==='write'),false);assert.equal(g.view.state.data,null)}finally{g.dispose()}
})
test('actual owner uploads separately then requires rendered PNG and independent public consent',async()=>{
 const h=harness('owner');try{await flush();await h.view.choose();h.view.upload();assert.ok(h.view.confirmation.prompt);h.view.gate.settle(true);await flush();assert.equal(h.view.copyId.value,3);assert.equal(h.calls.some(c=>c[0]==='write'),false);h.view.select({archive_id:1,source_file_id:2});await h.view.preview();h.view.consent.value=true;h.view.submit();assert.equal(h.view.confirmation.prompt,null);h.view.proposalViewed.value=true;h.view.submit();assert.ok(h.view.confirmation.prompt.lines.some(l=>l.includes(PUBLIC_IMAGE_CONSENT)));h.view.gate.settle(true);await flush();const sent=h.calls.find(c=>c[0]==='write');assert.equal(sent[2],'submit');assert.equal(sent[4].source_file_id,2);assert.equal(sent[4].copy_file_id,3)}finally{h.dispose()}
})
