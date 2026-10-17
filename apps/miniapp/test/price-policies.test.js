import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {watch} from 'vue'
import {PRICE_FIELDS,validPriceBody,validPriceSource,validPricePolicy,createPricePolicyApi,priceLocalUtc,priceBeijing} from '../src/services/price-policies.js'
import {createPricePolicyRecovery} from '../src/services/price-policy-recovery.js'
import {createAssessmentPolicyRecovery} from '../src/services/assessment-policy-recovery.js'
import {validOperatorUser} from '../src/services/experience-publication.js'
import {operatorSession,setOperatorSession,clearOperatorSession,operatorLandingPage} from '../src/services/operator-session.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {ServiceError} from '../src/services/service-catalog.js'
const key='11111111-1111-4111-8111-111111111111',key2='22222222-2222-4222-8222-222222222222',copy=n=>JSON.parse(JSON.stringify(n))
const source=()=>({quote_id:1,state:'READY',source_hash:'a'.repeat(64),source:{quote_id:1,quote_version_id:3,quote_version:2,price:'12.34',merchant:{id:1,name:'合成门店',region_code:'440106'},project:{id:1,revision:1,project_name:'合成项目',service_content:'明确内容',quality_standard:'明确质检',source_note:'合成项目来源'}}})
const body=()=>({quote_id:1,source_hash:'a'.repeat(64),lower_price:'10.00',upper_price:'15.00',price_unit:'次',applicability_note:'确认指定车型及内容',source_note:'合成价格来源',scope_confirmed:true,valid_from:'2026-11-01T00:00:00Z',valid_to:'2026-12-01T00:00:00Z'})
const policy=()=>({...body(),id:1,revision:1,status:'DRAFT',rule_version:'R8B_PRICE_SOURCE_V1',source_snapshot:source().source,created_at:'2026-10-16T00:00:00Z',published_at:null,stopped_at:null,comparison_available:false})
const page=n=>({items:n,total:n.length,page:1,page_size:20})
const pending=()=>({action:'create',id:0,body:body(),key})
const memory=()=>{const values=new Map();return {values,getStorageSync:k=>values.get(k),setStorageSync:(k,v)=>values.set(k,copy(v)),removeStorageSync:k=>values.delete(k)}}
function api(data,statusCode=200){const calls=[];return {calls,api:createPricePolicyApi({baseUrl:'https://example.test',runtime:()=>({request:o=>{calls.push(o);o.success({statusCode,data:{code:0,data}})}})})}}
test('exact positive decimal interval confirms applicability and allows inclusive equal bounds',()=>{
 assert.equal(validPriceBody(body(),'create'),true);for(const mutate of [b=>b.lower_price='0.00',b=>b.lower_price='16.00',b=>b.upper_price=15,b=>b.upper_price='015.00',b=>b.scope_confirmed=false,b=>b.price_unit=' 次 ',b=>b.source_snapshot=source().source,b=>b.valid_to=b.valid_from]){const b=body();mutate(b);assert.equal(validPriceBody(b,'create'),false)}assert.equal(validPriceBody({...body(),upper_price:'10.00'},'create'),true);assert.equal(validPriceBody({...body(),upper_price:'99999999.99'},'create'),true)
})
test('server sources are exact and unknown legacy source cannot carry a confirmed hash',()=>{
 assert.equal(validPriceSource(source()),true);const s=source();s.state='UNKNOWN';s.source_hash=null;s.source.quote_version_id=null;s.source.quote_version=null;assert.equal(validPriceSource(s),true);s.source.price='0.00';assert.equal(validPriceSource(s),true);s.source.price='-0.01';assert.equal(validPriceSource(s),true);s.source_hash='a'.repeat(64);assert.equal(validPriceSource(s),false);assert.equal(validPriceSource({...source(),phone:'private'}),false);const bad=source();bad.source.quote_id=2;assert.equal(validPriceSource(bad),false)
})
test('frozen policy validates scope binding timestamps and explicitly unavailable comparison',()=>{
 assert.equal(validPricePolicy(policy()),true);for(const mutate of [p=>p.quote_id=2,p=>p.comparison_available='true',p=>p.status='PUBLISHED',p=>p.created_at=p.valid_from,p=>p.source_snapshot.project.source_note='',p=>p.submission_key=key]){const p=policy();mutate(p);assert.equal(validPricePolicy(p),false)}const p=policy();p.status='PUBLISHED';p.revision=2;p.published_at='2026-10-16T00:01:00Z';assert.equal(validPricePolicy(p),true);p.status='STOPPED';p.stopped_at='2026-10-16T00:02:00Z';assert.equal(validPricePolicy(p),true)
})
test('Beijing editor converts exactly and rejects rolled dates rather than guessing',()=>{
 assert.equal(priceLocalUtc('2026-11-01 08:00:00'),body().valid_from);assert.equal(priceBeijing(body().valid_from),'2026-11-01 08:00:00');assert.equal(priceLocalUtc('2026-02-30 08:00:00'),'');assert.equal(priceLocalUtc('2026-11-01 24:00:00'),'');assert.equal(priceLocalUtc('2026-11-01T08:00:00'),'')
})
test('source/list/detail APIs bind exact paging and current entity without accepting extra payloads',async()=>{
 let a=api(page([source()]));await a.api.quotes('t');assert.match(a.calls[0].url,/price-monitor\/quotes\?page=1&page_size=20$/);a=api(policy());await a.api.detail('t',1);await assert.rejects(a.api.detail('t',2),e=>e.kind==='protocol');a=api(page([policy()]));await a.api.list('t');assert.equal(a.calls[0].method,'GET');assert.throws(()=>a.api.list('t',0));assert.throws(()=>a.api.list(''));await assert.rejects(api(page([policy(),policy()])).api.list('t'),e=>e.kind==='protocol')
})
test('writes retain immutable original body and key and reject altered receipt fields',async()=>{
 const a=api(policy()),b=body();await a.api.write('t','create',0,b,key);assert.equal(a.calls[0].method,'POST');assert.deepEqual(a.calls[0].data,b);assert.equal(a.calls[0].header['Idempotency-Key'],key);const p=policy();p.lower_price='11.00';await assert.rejects(api(p).api.write('t','create',0,b,key),e=>e.kind==='protocol');assert.throws(()=>a.api.write('t','update',1,b,key));for(const [code,kind] of [[401,'unauthorized'],[403,'forbidden'],[409,'conflict'],[503,'unavailable']])await assert.rejects(api(policy(),code).api.write('t','create',0,b,key),e=>e.kind===kind)
})
test('pending intent is permanent account isolated and independent of assessment storage',()=>{
 let actor=1;const m=memory(),r=createPricePolicyRecovery({runtime:()=>m,actor:()=>actor}),old=createAssessmentPolicyRecovery({runtime:()=>m,actor:()=>actor});r.savePending(pending());const p=pending();p.body.lower_price='9.00';assert.throws(()=>r.savePending(p));assert.equal(old.read().pending,null);actor=2;assert.equal(r.read().pending,null);actor=1;const mark=r.marker();r.complete(pending());r.savePending({...pending(),key:key2});assert.throws(()=>r.discard(mark));assert.throws(()=>r.complete(pending()));assert.equal(r.read().pending.key,key2)
})
test('response loss restores original source hash interval and key only after fresh authorized read',async()=>{
 const m=memory(),r=createPricePolicyRecovery({runtime:()=>m,actor:()=>1}),sent=[];let first=true;function build(){const state=initialDiagnosisState();return {state,flow:createAsyncDiagnosisFlow({state,recovery:r,token:()=> 't',scope:()=> 'price:1',newKey:()=>key,confirm:async()=>true,load:async()=>page([policy()]),write:async(t,a,id,b,k)=>{sent.push([copy(b),k]);if(first){first=false;throw new ServiceError('unavailable','unknown result')}return policy()}})}}let h=build();await h.flow.load();await h.flow.save('create',0,body());assert.equal(h.state.retry,true);h=build();await h.flow.retry();assert.equal(sent.length,1);await h.flow.load();await h.flow.retry();assert.deepEqual(sent,[[body(),key],[body(),key]]);assert.equal(r.read().pending,null)
})
test('confirmation freezes source intent and cancellation never dispatches',async()=>{
 const m=memory(),r=createPricePolicyRecovery({runtime:()=>m,actor:()=>1}),state=initialDiagnosisState();let done,sent;const b=body(),flow=createAsyncDiagnosisFlow({state,recovery:r,token:()=> 't',scope:()=> 'p',newKey:()=>key,load:async()=>page([]),confirm:()=>new Promise(resolve=>done=resolve),write:async(t,a,id,value)=>{sent=value;return policy()}});await flow.load();let p=flow.save('create',0,b);b.source_hash='b'.repeat(64);b.lower_price='9.00';done(true);await p;assert.equal(sent.source_hash,'a'.repeat(64));assert.equal(sent.lower_price,'10.00');sent=null;p=flow.save('create',0,body());done(false);await p;assert.equal(sent,null)
})
test('storage failure blocks dispatch before the original request can be sent',async()=>{
 const m=memory();m.setStorageSync=()=>{throw Error('storage blocked')};const r=createPricePolicyRecovery({runtime:()=>m,actor:()=>1}),state=initialDiagnosisState();let sent=0;const flow=createAsyncDiagnosisFlow({state,recovery:r,token:()=> 't',scope:()=> 'p',newKey:()=>key,confirm:async()=>true,load:async()=>page([]),write:async()=>sent++});await flow.load();await flow.save('create',0,body());assert.equal(sent,0);assert.equal(state.kind,'storage')
})
test('token switch hide and revoked capability drop late results and keep accounts separate',async()=>{
 let token='one',allowed=true,finish;const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=>token,allowed:()=>allowed,scope:()=>token,load:()=>new Promise(r=>finish=r)});let p=flow.load();token='two';flow.reset();finish(page([policy()]));await p;assert.equal(state.data,null);p=flow.load();allowed=false;flow.reset();finish(page([policy()]));await p;assert.equal(state.data,null)
})
test('new independent permission has exact thirteen-field shape before publishing token',()=>{
 const names=['review','onboard','catalog','coupon','invite','diagnose','diagnose_manage','points','assessment','analytics','price_monitor'],u={id:1,role:'operator',...Object.fromEntries(names.map(k=>['can_'+k,k==='price_monitor']))};assert.equal(validOperatorUser(u),true);assert.equal(validOperatorUser({...u,can_price_monitor:1}),false);const old=copy(u);delete old.can_price_monitor;assert.equal(validOperatorUser(old),true);clearOperatorSession();let seen;const stop=watch(()=>operatorSession.accessToken,()=>seen=operatorSession.canPriceMonitor,{flush:'sync'});setOperatorSession({access_token:'new',expires_in:60,user:u});assert.equal(seen,true);assert.equal(operatorSession.canAnalytics,false);assert.equal(operatorLandingPage(),'/pages/operator/price-policies');stop();clearOperatorSession()
})
test('static login return requires independent permission and no source selectors',()=>{
 const pages=q=>[{route:'pages/operator/price-policies',options:q},{route:'pages/operator/login'}];assert.equal(serviceLoginReturn(pages({}),'admin',{canPriceMonitor:true}),true);assert.equal(serviceLoginReturn(pages({}),'admin',{canCatalog:true}),false);assert.equal(serviceLoginReturn(pages({quote_id:1}),'admin',{canPriceMonitor:true}),false)
})
test('actual registered configuration page uses confirmed source body recovery and immutable presentation',()=>{
 const read=p=>readFileSync(new URL('../src/'+p,import.meta.url),'utf8'),s=read('pages/operator/price-policies.vue');for(const pattern of [/createPricePolicyApi/,/createPricePolicyRecovery/,/operatorSession.canPriceMonitor/,/validPriceBody/,/scope_confirmed:form.confirmed/,/confirmation.value=JSON.parse/,/flow.discardLocal/,/onHide\(/,/source_snapshot/,/priceLocalUtc/])assert.match(s,pattern);assert.equal(JSON.parse(read('pages.json')).pages.some(v=>v.path==='pages/operator/price-policies'),true);assert.match(read('services/operator-session.js'),/canPriceMonitor: false/)
})
