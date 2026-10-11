import test from 'node:test'
import assert from 'node:assert/strict'
import {createPaymentsApi,paymentReconciliationMessage} from '../src/services/payments.js'
import {validPaymentSummary} from '../src/services/payment-contract.js'
import {createReservationsApi,createReservationWriteFlow,initialReservationWriteState,createReservationReadFlow,initialReservationReadState} from '../src/services/reservations.js'
const key='11111111-1111-4111-8111-111111111111'
const payment={payment_id:1,order_id:2,channel:'LOCAL_TEST',test_mode:true,status:'SUCCEEDED',amount:'12.34',currency:'CNY',requires_review:false}
test('reconcile sends empty POST without client payment facts, binds receipt to requested id',async()=>{
  let sent,payload=payment;const api=createPaymentsApi({baseUrl:'http://test',runtime:()=>({request:o=>{sent=o;o.success({statusCode:200,data:{code:0,data:payload}})}})})
  assert.equal((await api.reconcile('owner',1)).status,'SUCCEEDED');assert.equal(sent.method,'POST');assert.equal(sent.url,'http://test/api/payments/1/reconcile');assert.deepEqual(sent.data,{});assert.equal(sent.header['Idempotency-Key'],undefined)
  payload={...payment,payment_id:2};await assert.rejects(api.reconcile('owner',1),e=>e.kind==='protocol');await assert.rejects(api.detail('owner',1),e=>e.kind==='protocol')
  assert.throws(()=>api.reconcile('owner',0),e=>e.kind==='invalid');assert.throws(()=>api.reconcile('',1),e=>e.kind==='unauthorized')
})
test('pending reconciliation and provider error never imply success or real funds',async()=>{
  let status=200;const pending={...payment,status:'PENDING'};const api=createPaymentsApi({baseUrl:'http://test',runtime:()=>({request:o=>o.success({statusCode:status,data:{code:0,data:pending}})})})
  assert.equal((await api.reconcile('owner',1)).status,'PENDING');assert.match(paymentReconciliationMessage(pending),/尚未确认到账/);assert.match(paymentReconciliationMessage(payment),/测试支付，未真实扣款/);assert.match(paymentReconciliationMessage({...payment,requires_review:true}),/尚未执行退款/)
  status=503;await assert.rejects(api.reconcile('owner',1),e=>e.kind==='unavailable');assert.equal(paymentReconciliationMessage(null),'')
})
test('reconciliation responses are discarded on identity change and page exit',async()=>{
  let actor='owner',complete;const state=initialReservationReadState();const flow=createReservationReadFlow({state,token:()=>actor,request:()=>new Promise(resolve=>{complete=resolve})})
  const first=flow.load();actor='other';flow.reset();complete(payment);await first;assert.equal(state.value,null);assert.equal(state.loaded,false)
  const second=flow.load();flow.reset();complete(payment);await second;assert.equal(state.value,null);assert.equal(state.busy,false)
})
test('reconciliation retry after network error preserves an unknown result',async()=>{
  let fail=true;const api=createPaymentsApi({baseUrl:'http://test',runtime:()=>({request:o=>fail?o.fail({}):o.success({statusCode:200,data:{code:0,data:{...payment,status:'PENDING'}}})})});const state=initialReservationReadState(),flow=createReservationReadFlow({state,token:()=> 'owner',request:t=>api.reconcile(t,1)})
  await flow.load();assert.equal(state.value,null);assert.equal(state.loaded,false);fail=false;await flow.load();assert.equal(state.value.status,'PENDING');assert.match(paymentReconciliationMessage(state.value),/尚未确认到账/)
})
test('client payment entry always requests WECHAT and never sends amount or success',async()=>{
  let sent;const api=createPaymentsApi({baseUrl:'http://test',runtime:()=>({request:o=>{sent=o;o.success({statusCode:503,data:{code:50300,message:'private'}})}})})
  await assert.rejects(api.create('owner',2,key),e=>e.kind==='unavailable'&&e.message==='正式微信支付尚未配置，当前不可支付')
  assert.deepEqual(sent.data,{order_id:2,channel:'WECHAT'});assert.equal(sent.header['Idempotency-Key'],key)
})
test('test receipt and review facts retained, false real-payment marker rejected',async()=>{
  let payload=payment;const api=createPaymentsApi({baseUrl:'http://test',runtime:()=>({request:o=>o.success({statusCode:200,data:{code:0,data:payload}})})})
  assert.equal((await api.detail('owner',1)).test_mode,true)
  payload={...payment,test_mode:false};await assert.rejects(api.detail('owner',1),e=>e.kind==='protocol')
  payload=null;await assert.rejects(api.detail('owner',1),e=>e.kind==='protocol')
  assert.equal(validPaymentSummary({...payment,requires_review:true}),true)
})
test('payment uncertainty retries same key and cannot mark success from network failure',async()=>{
  const keys=[],state=initialReservationWriteState();let n=0
  const api=createPaymentsApi({baseUrl:'http://test',runtime:()=>({request:o=>{keys.push(o.header['Idempotency-Key']);o.fail({})}})})
  const flow=createReservationWriteFlow({state,token:()=> 'owner',body:()=>({order_id:2,channel:'WECHAT'}),newKey:()=>{n++;return key},request:(t,b,k)=>api.create(t,b.order_id,k)})
  await flow.save();await flow.save();assert.equal(n,1);assert.equal(keys[0],keys[1]);assert.equal(state.saved,null)
})
test('PAID order filter retains test payment flag and rejects malformed receipt',async()=>{
  let sent;let summary=payment;const api=createReservationsApi({baseUrl:'http://test',runtime:()=>({request:o=>{sent=o;o.success({statusCode:200,data:{code:0,data:{page:1,page_size:20,total:1,items:[{order_id:2,order_no:'R2',status:'PAID',amount_due:'12.34',payment_summary:summary}]}}})}})})
  const result=await api.list('owner',1,'PAID');assert.match(sent.url,/status=PAID/);assert.equal(result.items[0].payment_summary.test_mode,true)
  summary={...payment,test_mode:false};await assert.rejects(api.list('owner',1,'PAID'),e=>e.kind==='protocol')
})
