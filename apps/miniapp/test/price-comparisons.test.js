import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {validPriceComparison,validPriceComparisonPage,validPriceComparisonDetail,createPriceComparisonApi} from '../src/services/price-comparisons.js'
import {validPricePolicy} from '../src/services/price-policies.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {ServiceError} from '../src/services/service-catalog.js'
const now='2026-11-01T00:00:00Z',copy=n=>JSON.parse(JSON.stringify(n))
const source=()=>({quote_id:1,state:'READY',source_hash:'a'.repeat(64),source:{quote_id:1,quote_version_id:3,quote_version:2,price:'12.34',merchant:{id:1,name:'合成门店',region_code:'440106'},project:{id:1,revision:1,project_name:'合成项目',service_content:'明确内容',quality_standard:'明确质检',source_note:'合成来源'}}})
const policy=()=>({quote_id:1,source_hash:'a'.repeat(64),lower_price:'10.00',upper_price:'15.00',price_unit:'次',applicability_note:'已确认本版本适用车型和内容',source_note:'合成区间来源',scope_confirmed:true,valid_from:now,valid_to:'2026-12-01T00:00:00Z',id:1,revision:2,status:'PUBLISHED',rule_version:'R8B_PRICE_SOURCE_V1',source_snapshot:source().source,created_at:'2026-10-16T00:00:00Z',published_at:'2026-10-16T00:01:00Z',stopped_at:null,comparison_available:true})
const comparison=()=>({quote_id:1,state:'WITHIN_RANGE',reason:'VERIFIED',review_required:false,current:source(),policy:policy()})
const unknown=(reason,mutate=()=>{})=>{const n=comparison();n.state='UNKNOWN';n.reason=reason;mutate(n);return n}
const detail=()=>({observed_at:now,comparison:comparison()}),page=()=>({observed_at:now,comparisons:{items:[comparison()],total:1,page:1,page_size:20}})
function mock(data,statusCode=200){const calls=[];return {calls,api:createPriceComparisonApi({baseUrl:'https://example.test/',runtime:()=>({request:o=>{calls.push(o);o.success({statusCode,data:{code:0,data}})}})})}}
test('current comparison verifies exact entity source hash window and minimal fields',()=>{
 assert.equal(validPriceComparison(comparison(),now),true);for(const mutate of [n=>n.quote_id=2,n=>n.current.quote_id=2,n=>n.policy.source_hash='b'.repeat(64),n=>n.policy.source_snapshot.project.service_content='different',n=>n.phone='private',n=>n.review_required=true,n=>n.reason='guess',n=>n.policy.status='DRAFT']){const n=comparison();mutate(n);assert.equal(validPriceComparison(n,now),false)}
})
test('both endpoints are inclusive and one cent outside requires review',()=>{
 for(const [lo,hi,state] of [['12.34','12.34','WITHIN_RANGE'],['12.34','15.00','WITHIN_RANGE'],['10.00','12.34','WITHIN_RANGE'],['12.35','15.00','BELOW_RANGE'],['10.00','12.33','ABOVE_RANGE']]){const n=comparison();Object.assign(n.policy,{lower_price:lo,upper_price:hi});n.state=state;n.review_required=state!=='WITHIN_RANGE';assert.equal(validPriceComparison(n,now),true);n.state=state==='WITHIN_RANGE'?'BELOW_RANGE':'WITHIN_RANGE';assert.equal(validPriceComparison(n,now),false)}
})
test('maximum supported prices compare exact cents without floating rounding',()=>{
 const n=comparison();n.current.source.price='99999999.99';n.policy.source_snapshot.price='99999999.99';n.policy.lower_price='99999999.98';n.policy.upper_price='99999999.99';assert.equal(validPriceComparison(n,now),true);n.policy.upper_price='99999999.98';n.state='ABOVE_RANGE';n.review_required=true;assert.equal(validPriceComparison(n,now),true)
})
test('missing unavailable legacy and invalid publication evidence are distinct unknowns',()=>{
 for(const [reason,mutate] of [['POLICY_MISSING',n=>n.policy=null],['SOURCE_UNAVAILABLE',n=>n.current=null],['SOURCE_UNKNOWN',n=>{n.current.state='UNKNOWN';n.current.source_hash=null}],['POLICY_SOURCE_UNKNOWN',n=>n.policy=null],['POLICY_SOURCE_UNKNOWN',()=>{}]])assert.equal(validPriceComparison(unknown(reason,mutate),now),true,reason)
 assert.equal(validPriceComparison(unknown('SOURCE_UNAVAILABLE'),now),false);assert.equal(validPriceComparison(unknown('POLICY_MISSING'),now),false);const n=unknown('POLICY_SOURCE_UNKNOWN');n.review_required=true;assert.equal(validPriceComparison(n,now),false)
})
test('draft stopped future and exclusive deadline cannot be treated as normal',()=>{
 const d=unknown('POLICY_NOT_PUBLISHED',n=>Object.assign(n.policy,{status:'DRAFT',revision:1,published_at:null}));assert.equal(validPriceComparison(d,now),true);const stopped=unknown('POLICY_STOPPED',n=>Object.assign(n.policy,{status:'STOPPED',revision:3,stopped_at:'2026-10-31T00:00:00Z'}));assert.equal(validPriceComparison(stopped,now),true);assert.equal(validPriceComparison(unknown('NOT_STARTED'),'2026-10-31T00:00:00Z'),true);assert.equal(validPriceComparison(unknown('EXPIRED'),'2026-12-01T00:00:00Z'),true);assert.equal(validPriceComparison(comparison(),'2026-12-01T00:00:00Z'),false);assert.equal(validPriceComparison(unknown('NOT_STARTED'),now),false)
})
test('source changes require explicit unknown and never reuse an old frozen interval',()=>{
 const n=unknown('SOURCE_CHANGED',n=>{n.current.source_hash='b'.repeat(64);n.current.source.quote_version=3;n.current.source.quote_version_id=4});assert.equal(validPriceComparison(n,now),true);n.state='WITHIN_RANGE';n.reason='VERIFIED';assert.equal(validPriceComparison(n,now),false);assert.equal(validPriceComparison(unknown('SOURCE_CHANGED'),now),false)
})
test('capability accepts original false creation receipt and current true without generating a result',()=>{
 const p=policy();assert.equal(validPricePolicy(p),true);p.comparison_available=false;assert.equal(validPricePolicy(p),true);p.comparison_available='true';assert.equal(validPricePolicy(p),false)
})
test('list and detail enforce exact observation anchor pagination and duplicate rejection',()=>{
 assert.equal(validPriceComparisonPage(page()),true);assert.equal(validPriceComparisonDetail(detail(),1),true);assert.equal(validPriceComparisonDetail(detail(),2),false);const p=page();p.comparisons.items.push(comparison());p.comparisons.total=2;assert.equal(validPriceComparisonPage(p),false);assert.equal(validPriceComparisonPage(page(),2),false);const bad=detail();bad.observed_at='2026-02-30T00:00:00Z';assert.equal(validPriceComparisonDetail(bad,1),false)
})
test('API is GET only and binds quote detail with no write body or customer selectors',async()=>{
 let m=mock(page());await m.api.list('t');assert.match(m.calls[0].url,/price-monitor\/comparisons\?page=1&page_size=20$/);m=mock(detail());await m.api.detail('t',1);assert.match(m.calls[0].url,/comparisons\/1$/);assert.equal(m.calls[0].method,'GET');assert.equal(m.calls[0].data,undefined);assert.deepEqual(m.calls[0].header,{Authorization:'Bearer t'});await assert.rejects(m.api.detail('t',2),e=>e.kind==='protocol');assert.throws(()=>m.api.detail('t',0));assert.throws(()=>m.api.list('t',0));assert.throws(()=>m.api.list(''))
})
test('protocol and permission failures do not fabricate an empty successful comparison',async()=>{
 const bad=detail();bad.comparison.state='ABOVE_RANGE';await assert.rejects(mock(bad).api.detail('t',1),e=>e.kind==='protocol');for(const [code,kind] of [[401,'unauthorized'],[403,'forbidden'],[404,'missing'],[503,'unavailable']])await assert.rejects(mock(detail(),code).api.detail('t',1),e=>e.kind===kind)
})
test('read flow drops late account detail permission and hide responses',async()=>{
 let token='one',id=1,allowed=true,finish;const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=>token,scope:()=>`compare:${id}`,allowed:()=>allowed,load:()=>new Promise(r=>finish=r)});for(const mutate of [()=>token='two',()=>id=2,()=>allowed=false]){let p=flow.load();mutate();flow.reset();finish(detail());await p;assert.equal(state.data,null)}allowed=true;const p=flow.load();flow.reset();finish(detail());await p;assert.equal(state.data,null)
})
test('fresh 404 clears previously observed price and review indicator',async()=>{
 let gone=false;const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=> 't',scope:()=> 'compare:1',load:async()=>{if(gone)throw new ServiceError('missing','gone');return detail()}});await flow.load();assert.equal(validPriceComparisonDetail(state.data,1),true);gone=true;await flow.load();assert.equal(state.data,null);assert.equal(state.kind,'missing')
})
test('login return requires independent permission and exact static or quote route',()=>{
 const pages=(route,options={})=>[{route,options},{route:'pages/operator/login'}];assert.equal(serviceLoginReturn(pages('pages/operator/price-comparisons'),'admin',{canPriceMonitor:true}),true);assert.equal(serviceLoginReturn(pages('pages/operator/price-comparisons',{quote_id:1}),'admin',{canPriceMonitor:true}),false);assert.equal(serviceLoginReturn(pages('pages/operator/price-comparison',{id:'1'}),'admin',{canPriceMonitor:true}),true);assert.equal(serviceLoginReturn(pages('pages/operator/price-comparison',{id:'1',merchant_id:'2'}),'admin',{canPriceMonitor:true}),false);assert.equal(serviceLoginReturn(pages('pages/operator/price-comparison',{id:'1'}),'admin',{canAnalytics:true}),false)
})
test('registered actual pages and source configuration share read API and permission',()=>{
 const read=p=>readFileSync(new URL('../src/'+p,import.meta.url),'utf8');for(const p of ['pages/operator/price-comparisons.vue','pages/operator/price-comparison.vue']){const s=read(p);for(const re of [/createPriceComparisonApi/,/operatorSession.canPriceMonitor/,/flow.reset\(/,/onHide\(/,/PriceComparisonResult/])assert.match(s,re);assert.doesNotMatch(s,/flow\.(save|retry)|setStorageSync/)}const component=read('components/PriceComparisonResult.vue');assert.match(component,/status==='DRAFT'\?'未发布草稿':'原冻结依据'/);assert.match(component,/不自动改价/);assert.match(read('pages/operator/price-policies.vue'),/pages\/operator\/price-comparisons/);const paths=JSON.parse(read('pages.json')).pages.map(v=>v.path);assert.equal(paths.includes('pages/operator/price-comparisons'),true);assert.equal(paths.includes('pages/operator/price-comparison'),true)
})
