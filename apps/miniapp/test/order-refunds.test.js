import test from 'node:test'
import assert from 'node:assert/strict'
import {createOrderRefundsApi,validRefundDetail,refundStatusLabel} from '../src/services/order-refunds.js'
import {createReservationWriteFlow,initialReservationWriteState,createReservationReadFlow,initialReservationReadState} from '../src/services/reservations.js'
const key='11111111-1111-4111-8111-111111111111'
const record={refund_id:1,refund_no:'R'+'a'.repeat(32),payment_id:2,amount:'12.34',currency:'CNY',status:'REQUESTED',revision:1,from_status:'PAID',reason:'取消',decision_note:null,owner_consented_at:'2026-10-11T00:00:00Z',merchant_consented_at:null,refunded_at:null,test_mode:true}
const detail={order_id:3,order_status:'PAID',can_request:false,refund:record}
const fixture=(reply=detail)=>{let sent;return {api:createOrderRefundsApi({baseUrl:'http://test',runtime:()=>({request:o=>{sent=o;o.success({statusCode:200,data:{code:0,data:reply}})}})}),sent:()=>sent}}
test('refund contract requires server fields and dual consent before funds states',()=>{
 assert.equal(validRefundDetail(detail),true);assert.equal(validRefundDetail({...detail,refund:null,can_request:true}),true)
 for(const change of [{amount:'0.00'},{amount:12.34},{currency:'USD'},{revision:0},{status:'SUCCESS'},{owner_consented_at:null},{refund_no:1},{status:'SUCCEEDED',refunded_at:'2026-10-11T00:00:00Z'},{private_token:'secret'}])assert.equal(validRefundDetail({...detail,refund:{...record,...change}}),false)
 assert.equal(validRefundDetail({...detail,can_request:true}),false);assert.equal(validRefundDetail({...detail,order_status:'CLOSED',refund:{...record,status:'SUCCEEDED',merchant_consented_at:'2026-10-11T00:00:00Z',refunded_at:'2026-10-11T00:00:01Z'}}),true)
})
test('owner writes submit reason or revision and never client money or success',async()=>{
 const f=fixture();await f.api.request('owner',3,' 取消 ',key);assert.equal(f.sent().url,'http://test/api/order/3/refund');assert.deepEqual(f.sent().data,{reason:'取消'});assert.equal(f.sent().header['Idempotency-Key'],key)
 await f.api.withdraw('owner',3,1,key);assert.deepEqual(f.sent().data,{revision:1})
 assert.throws(()=>f.api.request('owner',3,'取消'),e=>e.kind==='invalid');assert.throws(()=>f.api.withdraw('owner',3,0,key),e=>e.kind==='invalid');assert.throws(()=>f.api.request('owner',3,' '.repeat(2),key),e=>e.kind==='invalid')
})
test('manager decision binds application revision and requires rejection reason',async()=>{
 const f=fixture();await f.api.decide('manager',3,1,'ACCEPT','',key);assert.equal(f.sent().url,'http://test/api/merchant/orders/3/refund/decision');assert.deepEqual(f.sent().data,{revision:1,decision:'ACCEPT'})
 await f.api.decide('manager',3,1,'REJECT','原因',key);assert.deepEqual(f.sent().data,{revision:1,decision:'REJECT',note:'原因'})
 assert.throws(()=>f.api.decide('manager',3,1,'REJECT','',key),e=>e.kind==='invalid');assert.throws(()=>f.api.decide('manager',3,1,'ACCEPT',''),e=>e.kind==='invalid')
})
test('reconciliation sends only empty body and validates requested order id',async()=>{
 const f=fixture();await f.api.reconcile('manager',3);assert.deepEqual(f.sent().data,{});assert.equal(f.sent().header['Idempotency-Key'],undefined)
 await assert.rejects(f.api.detail('owner','owner',4),e=>e.kind==='protocol');assert.throws(()=>f.api.detail('tech','technician',3),e=>e.kind==='forbidden');assert.throws(()=>f.api.detail('','owner',3),e=>e.kind==='unauthorized')
})
test('pending and failed labels never claim money success',()=>{assert.match(refundStatusLabel('PENDING'),/尚未确认到账/);assert.match(refundStatusLabel('FAILED'),/继续核对/);assert.match(refundStatusLabel('SUCCEEDED'),/记录/);assert.equal(refundStatusLabel('UNKNOWN'),'状态未提供')})
test('server errors do not expose supplier or SQL details',async()=>{
 for(const [status,kind] of [[400,'invalid'],[401,'unauthorized'],[403,'forbidden'],[404,'missing'],[409,'conflict'],[503,'unavailable']]){const api=createOrderRefundsApi({baseUrl:'http://test',runtime:()=>({request:o=>o.success({statusCode:status,data:{message:'private secret SQL'}})})});await assert.rejects(api.detail('owner','owner',3),e=>e.kind===kind&&!e.message.includes('private'))}
})
test('unknown write retries same key and holds no success',async()=>{
 const seen=[],state=initialReservationWriteState();let keys=0;const api=createOrderRefundsApi({baseUrl:'http://test',runtime:()=>({request:o=>{seen.push(o.header['Idempotency-Key']);o.fail({})}})});const flow=createReservationWriteFlow({state,token:()=> 'owner',body:()=>({reason:'取消'}),newKey:()=>{keys++;return key},request:(t,b,k)=>api.request(t,3,b.reason,k)})
 await flow.save();await flow.save();assert.equal(keys,1);assert.deepEqual(seen,[key,key]);assert.equal(state.saved,null)
})
test('identity changes and hidden pages discard old refund results',async()=>{
 let actor='owner',complete;const state=initialReservationReadState(),flow=createReservationReadFlow({state,token:()=>actor,request:()=>new Promise(r=>{complete=r})});const first=flow.load();actor='other';flow.reset();complete(detail);await first;assert.equal(state.value,null);const second=flow.load();flow.reset();complete(detail);await second;assert.equal(state.value,null)
})
