import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {watch} from 'vue'
import {BUSINESS_METRICS,BUSINESS_MONEY,businessCashDifference,currentBusinessMonth,validBusinessDashboard,createBusinessDashboardApi} from '../src/services/business-dashboard.js'
import {validOperatorUser} from '../src/services/experience-publication.js'
import {operatorSession,setOperatorSession,clearOperatorSession,operatorLandingPage} from '../src/services/operator-session.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {ServiceError} from '../src/services/service-catalog.js'
const copy=n=>JSON.parse(JSON.stringify(n)),month='2026-09',merchant={id:1,name:'合成门店',address:'合成地址'}
const bucket=money=>({state:'KNOWN',count:0,unknown_count:0,excluded_count:0,amount:money?'0.00':null})
const value=()=>({merchant:{...merchant},period_month:month,timezone:'Asia/Shanghai',window_from:'2026-08-31T16:00:00Z',window_to:'2026-09-30T16:00:00Z',observed_at:'2026-10-16T00:00:00Z',currency:'CNY',source_version:'R8B_BUSINESS_V1',metrics:Object.fromEntries(Object.keys(BUSINESS_METRICS).map(k=>[k,{formal:bucket(BUSINESS_MONEY.has(k)),test:bucket(BUSINESS_MONEY.has(k)),unclassified_count:0}])),cash_difference:{formal:{state:'KNOWN',amount:'0.00'},test:{state:'KNOWN',amount:'0.00'}},assessment:null})
const unknown=(v,k,b='formal')=>{Object.assign(v.metrics[k][b],{state:'UNKNOWN',unknown_count:1,amount:null});if(['payments','refunds'].includes(k))v.cash_difference[b]={state:'UNKNOWN',amount:null}}
const user=()=>({id:1,role:'operator',...Object.fromEntries(['can_review','can_onboard','can_catalog','can_coupon','can_invite','can_diagnose','can_diagnose_manage','can_points','can_assessment','can_analytics'].map(k=>[k,k==='can_analytics']))})
function mock(data=value(),statusCode=200){const calls=[];return {calls,api:createBusinessDashboardApi({baseUrl:'https://example.test/',runtime:()=>({request:o=>{calls.push(o);o.success({statusCode,data:{code:0,data}})}})})}}

test('dashboard verifies exact minimal fields and all eight independent metric buckets',()=>{
 assert.equal(validBusinessDashboard(value()),true)
 for(const mutate of [v=>v.currency='USD',v=>v.timezone='UTC',v=>v.source_version='guess',v=>v.phone='13800000000',v=>v.metrics.private={text:'private'},v=>v.merchant.owner_id=1,v=>v.metrics.payments.formal.amount=0,v=>v.metrics.orders_created.formal.amount='0.00']){const v=value();mutate(v);assert.equal(validBusinessDashboard(v),false)}
})
test('cash difference supports negative months and rejects mismatched totals',()=>{
 const v=value();Object.assign(v.metrics.refunds.formal,{count:1,amount:'20.00'});v.cash_difference.formal.amount='-20.00';assert.equal(validBusinessDashboard(v),true);v.cash_difference.formal.amount='0.00';assert.equal(validBusinessDashboard(v),false)
})
test('decimal subtraction preserves cents and large money without floating arithmetic',()=>{
 for(const [a,b,c] of [['0.00','0.01','-0.01'],['1.00','0.99','0.01'],['100.00','99.99','0.01'],['999999999999999999999999.99','0.01','999999999999999999999999.98'],['90071992547409.93','90071992547409.92','0.01'],['1.00','1.00','0.00']])assert.equal(businessCashDifference(a,b),c)
 assert.equal(businessCashDifference('01.00','0.00'),null);assert.equal(businessCashDifference('-1.00','0.00'),null)
})
test('unknown payment provenance hides money and resulting difference rather than inventing zero',()=>{
 const v=value();unknown(v,'payments');assert.equal(validBusinessDashboard(v),true);v.metrics.payments.formal.amount='0.00';assert.equal(validBusinessDashboard(v),false)
})
test('unclassified evidence invalidates completeness of both formal and test columns',()=>{
 const v=value();v.metrics.orders_created.unclassified_count=1;for(const b of ['formal','test'])v.metrics.orders_created[b].state='UNKNOWN';assert.equal(validBusinessDashboard(v),true);v.metrics.orders_created.test.state='KNOWN';assert.equal(validBusinessDashboard(v),false)
})
test('test acquisitions can be excluded without being counted as valid formal new customers',()=>{
 const v=value();v.metrics.new_customers.test.excluded_count=3;assert.equal(validBusinessDashboard(v),true);assert.equal(v.metrics.new_customers.formal.count,0);assert.equal(v.metrics.new_customers.test.count,0);v.metrics.new_customers.test.excluded_count=-1;assert.equal(validBusinessDashboard(v),false)
})
test('commission accepts signed verified accrual and waiver zero but not negative payments',()=>{
 const v=value();Object.assign(v.metrics.commission_accrual.formal,{count:1,amount:'-0.30'});assert.equal(validBusinessDashboard(v),true);v.metrics.commission_accrual.formal.amount='0.00';assert.equal(validBusinessDashboard(v),true);Object.assign(v.metrics.payments.test,{count:1,amount:'-0.30'});assert.equal(validBusinessDashboard(v),false)
})
test('month windows bind to server observation and Beijing rollover',()=>{
 const v=value();v.observed_at='2026-09-15T00:00:00Z';v.window_to=v.observed_at;assert.equal(validBusinessDashboard(v),true);v.window_to='2026-09-30T16:00:00Z';assert.equal(validBusinessDashboard(v),false)
 assert.equal(currentBusinessMonth(Date.parse('2026-09-30T15:59:59Z')),'2026-09');assert.equal(currentBusinessMonth(Date.parse('2026-09-30T16:00:00Z')),'2026-10')
})
test('frozen monthly score is minimal separate and never an invented live score',()=>{
 const v=value();v.assessment={snapshot_id:2,revision:3,state:'CALCULATED',total_score:'70.00',grade:{code:'B',label:'基础'},captured_from:'2026-10-01T00:00:00Z',captured_to:'2026-10-01T00:00:01Z'};assert.equal(validBusinessDashboard(v),true);v.assessment.facts=[];assert.equal(validBusinessDashboard(v),false);delete v.assessment.facts;v.assessment.state='UNKNOWN';assert.equal(validBusinessDashboard(v),false);v.assessment.total_score=null;v.assessment.grade=null;assert.equal(validBusinessDashboard(v),true);v.assessment.captured_from='2026-09-01T00:00:00Z';assert.equal(validBusinessDashboard(v),false)
})
test('API uses GET and exact admin selector while merchant has no store selector',async()=>{
 const {api,calls}=mock();await api.dashboard('t','admin',1,month);await api.dashboard('t','merchant',null,month);assert.match(calls[0].url,/admin\/business\/dashboard\?period_month=2026-09&merchant_id=1$/);assert.match(calls[1].url,/merchant\/business\/dashboard\?period_month=2026-09$/)
 for(const o of calls){assert.equal(o.method,'GET');assert.deepEqual(o.header,{Authorization:'Bearer t'});assert.equal(o.data,undefined)}
 assert.throws(()=>api.dashboard('t','merchant',1,month));assert.throws(()=>api.dashboard('t','owner',null,month));assert.throws(()=>api.dashboard('','admin',1,month));assert.throws(()=>api.dashboard('t','admin',1,'2026-13'))
})
test('API rejects wrong response scope and impossible unknown totals and propagates permission failure',async()=>{
 const v=value();v.merchant.id=2;await assert.rejects(mock(v).api.dashboard('t','admin',1,month),e=>e.kind==='protocol');const n=value();unknown(n,'refunds');n.cash_difference.formal.amount='0.00';await assert.rejects(mock(n).api.dashboard('t','merchant',null,month),e=>e.kind==='protocol');await assert.rejects(mock(value(),403).api.dashboard('t','admin',1,month),e=>e.kind==='forbidden');await assert.rejects(mock(value(),409).api.dashboard('t','admin',1,month),e=>e.kind==='conflict')
})
test('merchant source paging rejects duplicate or private stores and binds page',async()=>{
 const page={items:[merchant],total:1,page:1,page_size:20};const m=mock(page);await m.api.merchants('t');assert.match(m.calls[0].url,/business\/merchants\?page=1&page_size=20$/);page.items[0]={...merchant,phone:'private'};await assert.rejects(mock(page).api.merchants('t'),e=>e.kind==='protocol');assert.throws(()=>m.api.merchants('t',0));await assert.rejects(mock({...page,page:2}).api.merchants('t'),e=>e.kind==='protocol')
})
test('independent analytics login flag is exact boolean and populated before token watchers',()=>{
 const u=user();assert.equal(validOperatorUser(u),true);const old=copy(u);delete old.can_analytics;assert.equal(validOperatorUser(old),true);assert.equal(validOperatorUser({...u,can_analytics:1}),false);assert.equal(validOperatorUser({...u,can_all:true}),false)
 clearOperatorSession();let seen;const stop=watch(()=>operatorSession.accessToken,()=>{seen=operatorSession.canAnalytics},{flush:'sync'});setOperatorSession({access_token:'new-token',expires_in:60,user:u});assert.equal(seen,true);assert.equal(operatorSession.canAssessment,false);assert.equal(operatorLandingPage(),'/pages/operator/business-dashboard');setOperatorSession({access_token:'other-token',expires_in:60,user:{...old,can_assessment:true}});assert.equal(operatorSession.canAnalytics,false);stop();clearOperatorSession()
})
test('login return accepts only registered static business pages and explicit capability',()=>{
 const pages=(route,options={})=>[{route,options},{route:'pages/operator/login'}];assert.equal(serviceLoginReturn(pages('pages/operator/business-dashboard'),'admin',{canAnalytics:true}),true);assert.equal(serviceLoginReturn(pages('pages/operator/business-dashboard'),'admin',{canAssessment:true}),false);assert.equal(serviceLoginReturn(pages('pages/operator/business-dashboard',{merchant_id:1}),'admin',{canAnalytics:true}),false);assert.equal(serviceLoginReturn(pages('pages/merchant/business-dashboard'),'merchant'),true);assert.equal(serviceLoginReturn(pages('pages/merchant/business-dashboard',{id:1}),'merchant'),false)
})
test('read flow drops late results after account switch and hide reset',async()=>{
 let t='one',finish;const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=>t,scope:()=>t,load:()=>new Promise(r=>{finish=r})});let p=flow.load();t='two';flow.reset();finish(value());await p;assert.equal(state.data,null);p=flow.load();flow.reset();finish(value());await p;assert.equal(state.data,null);assert.equal(state.busy,false)
})
test('permission failure clears previously read dashboard and never invokes a write',async()=>{
 let allowed=true,fail=false,writes=0;const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=> 't',scope:()=> 'business:1',allowed:()=>allowed,load:async()=>{if(fail)throw new ServiceError('forbidden','revoked');return value()},write:()=>writes++});await flow.load();assert.equal(validBusinessDashboard(state.data),true);fail=true;await flow.load();assert.equal(state.data,null);assert.equal(state.kind,'forbidden');allowed=false;await flow.load();assert.equal(writes,0)
})
test('registered real pages wire read APIs token reset and independent permission without mutation UI',()=>{
 const read=p=>readFileSync(new URL('../src/'+p,import.meta.url),'utf8'),admin=read('pages/operator/business-dashboard.vue'),shop=read('pages/merchant/business-dashboard.vue'),component=read('components/BusinessDashboardView.vue');for(const page of [admin,shop]){assert.match(page,/createBusinessDashboardApi/);assert.match(page,/onHide\(/);assert.match(page,/flow\.reset\(/);assert.doesNotMatch(page,/flow\.(save|retry)|recovery:/)}assert.match(admin,/operatorSession\.canAnalytics/);assert.match(shop,/api\.dashboard\(t,'merchant',null/);assert.match(component,/不代表已结资金/);assert.match(component,/不会随看板刷新自动重算/)
 const pages=JSON.parse(read('pages.json')).pages.map(v=>v.path);assert.equal(pages.includes('pages/operator/business-dashboard'),true);assert.equal(pages.includes('pages/merchant/business-dashboard'),true)
})
