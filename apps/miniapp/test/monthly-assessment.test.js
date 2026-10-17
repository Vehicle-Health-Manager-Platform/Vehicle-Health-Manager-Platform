import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {validAssessmentSnapshot,validAssessmentContext,validAssessmentFact,validStaffAssessment,validMonthlyAssessmentRequest,assessmentSettlementRequest,defaultAssessmentMonth,createMonthlyAssessmentApi} from '../src/services/monthly-assessment.js'
import {createMonthlyAssessmentRecovery} from '../src/services/monthly-assessment-recovery.js'
import {createAssessmentPolicyRecovery} from '../src/services/assessment-policy-recovery.js'
import {loadAdminMonthlyAssessment,loadMerchantMonthlyAssessment} from '../src/services/monthly-assessment-pages.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {ServiceError} from '../src/services/service-catalog.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
const copy=v=>JSON.parse(JSON.stringify(v)),key='11111111-1111-4111-8111-111111111111',key2='22222222-2222-4222-8222-222222222222',month='2026-11'
const merchant={id:1,name:'合成门店',address:'合成地址'}
const policy=()=>({id:1,merchant_id:1,merchant:{...merchant},acquisition_target:2,coupon_target:2,process_requirements:['CHECK_IN_CONFIRMED'],grade_thresholds:[{code:'A',label:'高档',min_score:'80.00'},{code:'B',label:'基础档',min_score:'0.00'}],source_note:'合成规则来源',valid_from:'2026-10-31T16:00:00Z',valid_to:'2026-12-31T16:00:00Z',revision:2,status:'PUBLISHED',rule_version:'R8B_POLICY_V1',scope:'MERCHANT',period:'BEIJING_MONTH',timezone:'Asia/Shanghai',weights:{acquisition:40,coupon:40,process:20},metric_source_version:'R8B_FACTS_V1',formula:'TARGET_RATE_AND_PROCESS_COMPLETENESS_V1',created_at:'2026-10-16T00:00:00Z',published_at:'2026-10-16T00:00:00Z',stopped_at:null})
const metric=(numerator,denominator,score)=>({numerator,denominator,score,state:'KNOWN',reason:'VERIFIED'})
const snapshot=(extra={})=>({id:1,merchant_id:1,period_month:month,revision:1,timezone:'Asia/Shanghai',policy:policy(),summary:{state:'CALCULATED',metrics:{acquisition:metric(1,2,'20.00'),coupon:metric(2,2,'40.00'),process:metric(1,2,'10.00')},total_score:'70.00',grade:{code:'B',label:'基础档'},fact_count:5},facts_hash:'a'.repeat(64),calculation_version:'R8B_CALC_V1',captured_from:'2026-11-30T16:00:00Z',captured_to:'2026-11-30T16:00:01Z',...extra})
const context=(admin=true,extra={})=>({merchant:{...merchant},period_month:month,timezone:'Asia/Shanghai',server_now:'2026-12-01T00:00:00Z',month_end:'2026-11-30T16:00:00Z',revision:1,latest:snapshot(),policy:policy(),state:'READY',can_settle:admin,...extra})
const staff=(extra={})=>({role:'MERCHANT',staff_id:1,merchant_id:1,period_month:month,timezone:'Asia/Shanghai',state:'FROZEN',revision:1,captured_from:'2026-11-30T16:00:00Z',captured_to:'2026-11-30T16:00:01Z',attribution:{included:1,excluded:0,unknown:0},...extra})
const fact=()=>({dimension:'PROCESS',source_type:'ORDER',source_id:'10',occurred_at:'2026-11-01T00:00:00Z',staff_id:1,state:'INCLUDED',reason:'VERIFIED_COMPLETION',evidence:{CHECK_IN_CONFIRMED:'PRESENT',_proof_hash:'b'.repeat(64)},proof_hash:'c'.repeat(64)})
const page=items=>({items,total:items.length,page:1,page_size:20})
const request=()=>({merchant_id:1,period_month:month,expected_revision:0})
const pending=()=>({action:'settle',id:0,body:request(),key})
const memory=()=>{const values=new Map();return {values,getStorageSync:k=>values.get(k),setStorageSync:(k,v)=>values.set(k,copy(v)),removeStorageSync:k=>values.delete(k)}}

test('frozen snapshot verifies exact fields targets formula rounding and configured grade',()=>{
 assert.equal(validAssessmentSnapshot(snapshot()),true)
 for(const mutate of [r=>r.summary.total_score='99.00',r=>r.summary.grade.code='A',r=>r.summary.metrics.coupon.score='80.00',r=>r.policy.weights.process=30,r=>r.summary.fact_count=1,r=>r.merchant_id=2,r=>r.captured_from='2026-11-01T00:00:00Z',r=>r.policy.stopped_at='2026-11-01T00:00:00Z',r=>r.total_score=70]){const r=snapshot();mutate(r);assert.equal(validAssessmentSnapshot(r),false,JSON.stringify(r))}
 const r=snapshot();r.policy.acquisition_target=3;r.policy.coupon_target=3;r.summary.metrics={acquisition:metric(1,3,'13.33'),coupon:metric(1,3,'13.33'),process:metric(1,3,'6.67')};r.summary.total_score='33.33';assert.equal(validAssessmentSnapshot(r),true)
})
test('unknown dimensions cannot carry invented zero totals or a grade',()=>{
 const r=snapshot();r.summary.metrics.process={numerator:0,denominator:0,score:null,state:'UNKNOWN',reason:'NO_VALID_DENOMINATOR'};r.summary.state='UNKNOWN';r.summary.total_score=null;r.summary.grade=null;assert.equal(validAssessmentSnapshot(r),true);r.summary.total_score='0.00';assert.equal(validAssessmentSnapshot(r),false)
 const missing=snapshot({policy:null});missing.summary={state:'UNKNOWN',metrics:Object.fromEntries(['acquisition','coupon','process'].map(k=>[k,{numerator:0,denominator:0,score:null,state:'UNKNOWN',reason:'POLICY_UNKNOWN'}])),total_score:null,grade:null,fact_count:0};assert.equal(validAssessmentSnapshot(missing),true)
})
test('context binds server latest version month and scope and cannot grant merchant settlement',()=>{
 assert.equal(validAssessmentContext(context(),true),true);assert.equal(validAssessmentContext(context(false),false),true);assert.equal(validAssessmentContext(context(),false),false)
 assert.equal(validAssessmentContext(context(true,{revision:21}),true),false);assert.equal(validAssessmentContext(context(true,{month_end:'2026-12-01T00:00:00Z'}),true),false)
 assert.equal(validAssessmentContext(context(true,{state:'MONTH_OPEN',server_now:'2026-11-01T00:00:00Z',can_settle:false,latest:null,revision:0}),true),true)
 const wrong=context();wrong.policy.valid_from='2026-11-30T16:00:00Z';assert.equal(validAssessmentContext(wrong,true),false);assert.equal(defaultAssessmentMonth(Date.parse('2026-12-31T16:00:00Z')),'2026-12')
})
test('settlement body is built only from the exact loaded server context',()=>{
 assert.deepEqual(assessmentSettlementRequest(context(),1,month),{...request(),expected_revision:1});assert.throws(()=>assessmentSettlementRequest(context(),2,month));assert.throws(()=>assessmentSettlementRequest(context(),1,'2026-10'));assert.throws(()=>assessmentSettlementRequest(context(true,{can_settle:false}),1,month));assert.equal(validMonthlyAssessmentRequest({...request(),policy_id:1}),false)
})
test('facts and own attribution reject private fields unrecognized evidence and fake empty history',()=>{
 assert.equal(validAssessmentFact(fact()),true);assert.equal(validAssessmentFact({...fact(),phone:'13800000000'}),false);assert.equal(validAssessmentFact({...fact(),evidence:{url:'private'}}),false);assert.equal(validStaffAssessment(staff()),true);assert.equal(validStaffAssessment({...staff(),user_id:2}),false)
 assert.equal(validStaffAssessment(staff({state:'NO_SNAPSHOT',revision:0,captured_from:null,captured_to:null,attribution:{included:0,excluded:0,unknown:0}})),true);assert.equal(validStaffAssessment(staff({state:'NO_SNAPSHOT',revision:0,captured_from:null,captured_to:null})),false)
})
test('monthly API uses precise server scopes no merchant selectors and unchanged original write receipt',async()=>{
 const calls=[];let data=snapshot();const api=createMonthlyAssessmentApi({baseUrl:'https://example.test',runtime:()=>({request:o=>{calls.push(o);o.success({statusCode:200,data:{code:0,data}})}})})
 await api.write('t','settle',0,request(),key);assert.equal(calls[0].header['Idempotency-Key'],key);assert.deepEqual(calls[0].data,request());data=context();await api.context('t','admin',1,month);assert.match(calls.at(-1).url,/month\?period_month=2026-11&merchant_id=1$/);data=context(false);await api.context('t','merchant',null,month);assert.equal(calls.at(-1).url.includes('merchant_id'),false)
 assert.throws(()=>api.context('t','merchant',2,month));assert.throws(()=>api.context('t','owner',null,month));data=snapshot({revision:2});await assert.rejects(api.write('t','settle',0,request(),key),e=>e.kind==='protocol');data=page([snapshot()]);await api.history('t','admin',1,month);data=page([snapshot({merchant_id:2})]);await assert.rejects(api.history('t','admin',1,month),e=>e.kind==='protocol')
})
test('page loader uses context revision rather than a page and rejects changing versions',async()=>{
 const c=context(true,{revision:21,latest:snapshot({revision:21})});const args={api:{context:async()=>c,history:async()=>({...page([snapshot({revision:1})]),total:21,page:2})},merchantsApi:{merchants:async()=>page([merchant])},token:'t',merchant,month,page:2};const value=await loadAdminMonthlyAssessment(args);assert.equal(value.context.revision,21);assert.equal(value.items[0].revision,1);args.api.history=async()=>page([snapshot()]);await assert.rejects(loadAdminMonthlyAssessment(args),e=>e.kind==='protocol')
})
test('an unavailable original store still allows independent permission verification and permanent retry',async()=>{
 const args={api:{context:async()=>{throw new ServiceError('missing','shop disabled')},history:async()=>page([snapshot()])},merchantsApi:{merchants:async()=>page([])},token:'t',merchant,month};const loaded=await loadAdminMonthlyAssessment(args);assert.equal(loaded.context,null);assert.equal(loaded.selection_error,true);args.api.context=async()=>{throw new ServiceError('forbidden','revoked')};await assert.rejects(loadAdminMonthlyAssessment(args),e=>e.kind==='forbidden')
})
test('ordinary employee page never requests store context history or other staff facts',async()=>{
 const calls=[];const api={staff:async()=>staff({role:'STAFF'}),context:async()=>{calls.push('context')},history:async()=>{calls.push('history')},detail:async()=>{calls.push('detail')},facts:async()=>{calls.push('facts')}};const v=await loadMerchantMonthlyAssessment({api,token:'t',month,detailId:10});assert.equal(v.context,null);assert.deepEqual(calls,[])
})
test('manager loader binds own store and one frozen revision across independently read responses',async()=>{
 const api={staff:async()=>staff(),context:async()=>context(false),history:async()=>page([snapshot()])};assert.equal((await loadMerchantMonthlyAssessment({api,token:'t',month})).context.merchant.id,1);api.staff=async()=>staff({merchant_id:2});await assert.rejects(loadMerchantMonthlyAssessment({api,token:'t',month}),e=>e.kind==='protocol');api.staff=async()=>staff({revision:2});await assert.rejects(loadMerchantMonthlyAssessment({api,token:'t',month}),e=>e.kind==='protocol')
})
test('selected snapshot facts must match its frozen fact count and selected month',async()=>{
 const api={list:async()=>page([snapshot()]),detail:async()=>snapshot(),facts:async()=>page([fact()])};await assert.rejects(loadAdminMonthlyAssessment({api,merchantsApi:{merchants:async()=>page([])},token:'t',month,detailId:1}),e=>e.kind==='protocol');api.facts=async()=>page(Array.from({length:5},()=>fact()));assert.equal((await loadAdminMonthlyAssessment({api,merchantsApi:{merchants:async()=>page([])},token:'t',month,detailId:1})).detail.id,1)
})
test('independent permanent monthly intents isolate actor and policy and compare before clearing',()=>{
 let actor=1;const storage=memory(),store=createMonthlyAssessmentRecovery({runtime:()=>storage,actor:()=>actor}),policyStore=createAssessmentPolicyRecovery({runtime:()=>storage,actor:()=>actor});const p=pending();store.savePending(p);p.body.period_month='2026-10';assert.equal(store.read().pending.body.period_month,month);assert.equal(policyStore.read().pending,null);actor=2;assert.equal(store.read().pending,null);actor=1;assert.throws(()=>store.savePending({...pending(),key:key2}));const mark=store.marker();store.complete(pending());store.savePending({...pending(),key:key2});assert.throws(()=>store.discard(mark));assert.throws(()=>store.complete(pending()))
})
test('response loss restores exact original month revision and key after server verification',async()=>{
 const storage=memory(),store=createMonthlyAssessmentRecovery({runtime:()=>storage,actor:()=>1}),calls=[];let first=true;const build=()=>{const state=initialDiagnosisState();return {state,flow:createAsyncDiagnosisFlow({state,recovery:store,token:()=> 't',scope:()=> 'monthly:1',newKey:()=>key,confirm:async()=>true,load:async()=>page([snapshot({revision:2})]),write:async(t,a,id,b,k)=>{calls.push([copy(b),k]);if(first){first=false;throw new ServiceError('unavailable','unknown')}return snapshot()}})}};let h=build();await h.flow.load();await h.flow.save('settle',0,request());assert.equal(h.state.retry,true);h=build();await h.flow.retry();assert.equal(calls.length,1);await h.flow.load();await h.flow.retry();assert.deepEqual(calls,[[request(),key],[request(),key]]);assert.equal(store.read().pending,null)
})
test('confirmation freezes loaded month and revision and cancellation never submits',async()=>{
 const storage=memory(),store=createMonthlyAssessmentRecovery({runtime:()=>storage,actor:()=>1}),state=initialDiagnosisState();let confirm,body=request(),sent;const flow=createAsyncDiagnosisFlow({state,recovery:store,token:()=> 't',scope:()=> '1',newKey:()=>key,load:async()=>page([]),confirm:()=>new Promise(r=>{confirm=r}),write:async(t,a,id,b)=>{sent=copy(b);return snapshot()}});await flow.load();let writing=flow.save('settle',0,body);body.period_month='2026-10';confirm(true);await writing;assert.equal(sent.period_month,month);sent=null;writing=flow.save('settle',0,request());confirm(false);await writing;assert.equal(sent,null)
})
test('storage failure blocks writes and late response after identity change retains origin intent',async()=>{
 let actor=1,token='one',resolve,writes=0;const storage=memory(),store=createMonthlyAssessmentRecovery({runtime:()=>storage,actor:()=>actor}),state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,recovery:store,token:()=>token,scope:()=>String(actor),newKey:()=>key,confirm:async()=>true,load:async()=>page([]),write:()=>{writes++;return new Promise(r=>{resolve=r})}});await flow.load();storage.setStorageSync=()=>{throw Error('full')};await flow.save('settle',0,request());assert.equal(writes,0);storage.setStorageSync=(k,v)=>storage.values.set(k,copy(v));await flow.load();const writing=flow.save('settle',0,request());await new Promise(r=>setTimeout(r,0));actor=2;token='two';flow.reset();resolve(snapshot());await writing;assert.equal(state.data,null);actor=1;assert.deepEqual(store.read().pending,pending())
})
test('successful server result survives storage cleanup failure and unauthorized original request is retained',async()=>{
 const storage=memory(),store=createMonthlyAssessmentRecovery({runtime:()=>storage,actor:()=>1}),state=initialDiagnosisState();let writes=0;const flow=createAsyncDiagnosisFlow({state,recovery:store,token:()=> 't',scope:()=> '1',newKey:()=>key,confirm:async()=>true,load:async()=>page([]),write:async()=>{writes++;return snapshot()}});await flow.load();const remove=storage.removeStorageSync;storage.removeStorageSync=()=>{throw Error('busy')};await flow.save('settle',0,request());assert.equal(state.retry,true);assert.equal(state.kind,'storage');storage.removeStorageSync=remove;await flow.retry();assert.equal(writes,2);assert.equal(store.read().pending,null)
 store.savePending(pending());const again=createAsyncDiagnosisFlow({state:initialDiagnosisState(),recovery:store,token:()=> 't',scope:()=> '1',load:async()=>page([]),write:async()=>{throw new ServiceError('unauthorized','expired')}});await again.load();await again.retry();assert.deepEqual(store.read().pending,pending())
})
test('late server read cannot repopulate hidden page or enable settlement after token change',async()=>{
 let resolve,token='one';const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=>token,scope:()=>token,load:()=>new Promise(r=>{resolve=r})});const reading=flow.load();token='two';flow.reset();resolve(context());await reading;assert.equal(state.data,null)
})
test('new static login returns require correct role capability and an empty selector set',()=>{
 assert.equal(serviceLoginReturn([{route:'pages/operator/monthly-assessment'},{}],'admin',{canAssessment:true}),true);assert.equal(serviceLoginReturn([{route:'pages/operator/monthly-assessment'},{}],'admin',{canPoints:true}),false);assert.equal(serviceLoginReturn([{route:'pages/operator/monthly-assessment',options:{merchant_id:'2'}},{}],'admin',{canAssessment:true}),false);assert.equal(serviceLoginReturn([{route:'pages/merchant/assessment'},{}],'merchant'),true);assert.equal(serviceLoginReturn([{route:'pages/merchant/assessment',options:{staff_id:'2'}},{}],'merchant'),false);assert.equal(serviceLoginReturn([{route:'pages/merchant/assessment'},{}],'owner'),false)
})
test('actual pages bind tested loaders context request builder and account-isolated flow',()=>{
 const admin=readFileSync(new URL('../src/pages/operator/monthly-assessment.vue',import.meta.url),'utf8'),merchantPage=readFileSync(new URL('../src/pages/merchant/assessment.vue',import.meta.url),'utf8'),result=readFileSync(new URL('../src/components/AssessmentResult.vue',import.meta.url),'utf8');assert.match(admin,/loadAdminMonthlyAssessment\(\{api,merchantsApi/);assert.match(admin,/assessmentSettlementRequest\(state.data\?\.context/);assert.match(admin,/write:api.write/);assert.match(admin,/flow.save\('settle',0,body\)/);assert.match(merchantPage,/loadMerchantMonthlyAssessment\(\{api/);assert.match(result,/summary.state==='CALCULATED'/);assert.match(result,/总分与等级：未知/)
})
