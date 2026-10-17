import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {validCustomerRelation,validCustomerRelations,validCustomerRelationDetail,customerRelationOrderRoute,createCustomerRelationsApi} from '../src/services/customer-relations.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
import {ServiceError} from '../src/services/service-catalog.js'
const now='2026-10-16T10:00:00Z',created='2026-10-15T10:00:00Z',vehicle={brand:'合成',series:'合成',year:null,config_name:null}
const summary=()=>({relationship_order_id:1,order_count:1,last_order_at:created,last_service_at:null,identity_source:'UNKNOWN',latest_vehicle:{...vehicle}})
const order=()=>({order_id:1,order_status:'IN_SERVICE',created_at:created,service_at:null,project_name:'合成项目',vehicle:{...vehicle},service_context_available:true})
const page=items=>({items,total:items.length,page:1,page_size:20})
const list=()=>({observed_at:now,relationships:page([summary()])}),detail=()=>({observed_at:now,relationship:summary(),orders:page([order()])})
const copy=n=>JSON.parse(JSON.stringify(n))
function mock(data,statusCode=200){const calls=[];return {calls,api:createCustomerRelationsApi({baseUrl:'https://example.test/',runtime:()=>({request:o=>{calls.push(o);o.success({statusCode,data:{code:0,data}})}})})}}
test('minimal relationship has exact fields and unknown arrival without a fake date',()=>{
 assert.equal(validCustomerRelation(summary(),now),true);assert.equal(validCustomerRelations(list()),true);for(const mutate of [r=>r.phone='private',r=>r.latest_vehicle.vin='private',r=>r.order_count=0,r=>r.last_service_at='未知',r=>r.identity_source='FORMAL',r=>r.last_order_at='2026-10-17T00:00:00Z']){const r=summary();mutate(r);assert.equal(validCustomerRelation(r,now),false)}
})
test('source labels do not silently convert test or legacy identity into verified customers',()=>{
 for(const v of ['TEST','UNKNOWN','VERIFIED_WECHAT']){const r=summary();r.identity_source=v;assert.equal(validCustomerRelation(r,now),true)}const l=list();l.relationships.items[0].new_customer_count=1;assert.equal(validCustomerRelations(l),false)
})
test('relation paging rejects duplicate anchors wrong pages and partial claimed totals',()=>{
 const l=list();l.relationships=page([summary(),summary()]);assert.equal(validCustomerRelations(l),false);assert.equal(validCustomerRelations(list(),2),false);const n=list();n.relationships.total=2;assert.equal(validCustomerRelations(n),false);n.relationships.total=1;n.observed_at='2026-02-30T00:00:00Z';assert.equal(validCustomerRelations(n),false)
})
test('detail binds anchor order counts page and allowed service states',()=>{
 assert.equal(validCustomerRelationDetail(detail(),1),true);assert.equal(validCustomerRelationDetail(detail(),2),false);for(const mutate of [d=>d.orders.total=2,d=>d.relationship.order_count=2,d=>d.orders.items[0].order_status='PENDING_PAYMENT',d=>d.orders.items[0].vehicle.plate_no='private',d=>d.orders.items[0].user_id=99,d=>d.orders.items[0].created_at='2026-10-16T00:00:00Z']){const d=detail();mutate(d);assert.equal(validCustomerRelationDetail(d,1),false)}
})
test('completed or disputed orders cannot advertise archive context and arrival must match observed summary',()=>{
 for(const state of ['COMPLETED','DISPUTED','PAID']){const d=detail();d.orders.items[0].order_status=state;assert.equal(validCustomerRelationDetail(d,1),false);d.orders.items[0].service_context_available=false;assert.equal(validCustomerRelationDetail(d,1),true)}const d=detail();d.orders.items[0].service_at=now;assert.equal(validCustomerRelationDetail(d,1),false);d.relationship.last_service_at=now;assert.equal(validCustomerRelationDetail(d,1),true);d.orders.items[0].service_at='2026-10-14T00:00:00Z';assert.equal(validCustomerRelationDetail(d,1),false)
})
test('navigation uses only currently read orders and context availability',()=>{
 const d=detail();assert.equal(customerRelationOrderRoute(d,1),'/pages/merchant/order-detail?id=1');assert.equal(customerRelationOrderRoute(d,1,true),'/pages/workspace/context?role=merchant&id=1');assert.equal(customerRelationOrderRoute(d,2),'');d.orders.items[0].service_context_available=false;assert.equal(customerRelationOrderRoute(d,1,true),'');assert.equal(customerRelationOrderRoute(null,1),'')
})
test('API exposes GET only with server store scope and no owner selector or body',async()=>{
 let m=mock(list());await m.api.list('token');assert.match(m.calls[0].url,/merchant\/customer-relations\?page=1&page_size=20$/);m=mock(detail());await m.api.detail('token',1);assert.match(m.calls[0].url,/customer-relations\/1\?page=1&page_size=20$/);assert.equal(m.calls[0].method,'GET');assert.deepEqual(m.calls[0].header,{Authorization:'Bearer token'});assert.equal(m.calls[0].data,undefined);assert.throws(()=>m.api.detail('token',0));assert.throws(()=>m.api.list('token',0));assert.throws(()=>m.api.list(''))
})
test('API fails closed on mismatched anchor private payload revoked role and missing relation',async()=>{
 const d=detail();d.relationship.relationship_order_id=2;await assert.rejects(mock(d).api.detail('t',1),e=>e.kind==='protocol');for(const [code,kind] of [[401,'unauthorized'],[403,'forbidden'],[404,'missing'],[503,'unavailable']])await assert.rejects(mock(detail(),code).api.detail('t',1),e=>e.kind===kind)
})
test('switching account and relationship or hiding drops late results',async()=>{
 let token='one',id=1,done;const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=>token,scope:()=>`relation:${id}`,load:()=>new Promise(r=>done=r)});let pending=flow.load();token='two';flow.reset();done(detail());await pending;assert.equal(state.data,null);pending=flow.load();id=2;flow.reset();done(detail());await pending;assert.equal(state.data,null);pending=flow.load();flow.reset();done(detail());await pending;assert.equal(state.data,null)
})
test('fresh relation failure clears previous details and cannot keep a stale archive button',async()=>{
 let fail=false;const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=> 't',scope:()=> 'relation:1',load:async()=>{if(fail)throw new ServiceError('missing','relationship gone');return detail()}});await flow.load();assert.notEqual(customerRelationOrderRoute(state.data,1,true),'');fail=true;await flow.load();assert.equal(state.data,null);assert.equal(customerRelationOrderRoute(state.data,1,true),'');assert.equal(state.kind,'missing')
})
test('merchant login return requires static list or exact positive local order anchor',()=>{
 const pages=(route,options={})=>[{route,options},{route:'pages/merchant/index'}];assert.equal(serviceLoginReturn(pages('pages/merchant/customers'),'merchant'),true);assert.equal(serviceLoginReturn(pages('pages/merchant/customers',{user_id:1}),'merchant'),false);assert.equal(serviceLoginReturn(pages('pages/merchant/customer-relation',{id:'1'}),'merchant'),true);assert.equal(serviceLoginReturn(pages('pages/merchant/customer-relation',{id:'1',merchant_id:'2'}),'merchant'),false);assert.equal(serviceLoginReturn(pages('pages/merchant/customer-relation',{id:'1'}),'admin'),false)
})
test('actual registered pages use tested APIs read guards and original R7 authorization context',()=>{
 const read=p=>readFileSync(new URL('../src/'+p,import.meta.url),'utf8');for(const p of ['pages/merchant/customers.vue','pages/merchant/customer-relation.vue']){const s=read(p);assert.match(s,/createCustomerRelationsApi/);assert.match(s,/flow\.reset\(/);assert.match(s,/onHide\(/);assert.doesNotMatch(s,/flow\.(save|retry)|setStorageSync/)}assert.match(read('pages/merchant/customer-relation.vue'),/customerRelationOrderRoute\(state.data/);assert.match(read('pages/merchant/customer-relation.vue'),/逐条授权/);const routes=JSON.parse(read('pages.json')).pages.map(v=>v.path);assert.equal(routes.includes('pages/merchant/customers'),true);assert.equal(routes.includes('pages/merchant/customer-relation'),true)
})
