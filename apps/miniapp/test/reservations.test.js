import test from 'node:test'
import assert from 'node:assert/strict'
import {createReservationsApi,ReservationError,initialReservationWriteState,createReservationWriteFlow,initialReservationReadState,createReservationReadFlow,chinaDate} from '../src/services/reservations.js'
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return {promise,resolve,reject}}
const key='11111111-1111-4111-8111-111111111111'
test('reservation API sends only chosen fields and preserves server conflict code',async()=>{
  let request;const api=createReservationsApi({baseUrl:'http://test',runtime:()=>({request:o=>{request=o;o.success({statusCode:409,data:{code:40901,message:'private'}})}})})
  const body={merchant_project_id:1,quote_version_id:2,vehicle_id:3,slot_id:4};await assert.rejects(api.create('owner',body,key),e=>e.kind==='conflict'&&e.code===40901&&e.message==='报价已更新，请重新确认');assert.deepEqual(request.data,body);assert.equal(request.header.Authorization,'Bearer owner');assert.equal(request.header['Idempotency-Key'],key)
})
test('ambiguous booking retries original key, changed selection uses new key, double taps blocked',async()=>{
  const state=initialReservationWriteState(),calls=[];let pending=deferred(),selected=1,n=0;const flow=createReservationWriteFlow({state,token:()=> 'owner',newKey:()=>String(++n),body:()=>({slot_id:selected}),request:(_t,b,k)=>{calls.push({b,k});return pending.promise}})
  let saving=flow.save();await flow.save();assert.equal(calls.length,1);pending.reject(new ReservationError('network','retry'));await saving;pending=deferred();saving=flow.save();pending.reject(new ReservationError('network','retry'));await saving;assert.equal(calls[0].k,calls[1].k);selected=2;pending=deferred();saving=flow.save();pending.resolve({order_id:1});await saving;assert.notEqual(calls[1].k,calls[2].k)
})
test('price conflict requires explicit confirmation and fresh key',async()=>{
  let confirmed=true,n=0;const state=initialReservationWriteState(),calls=[];const flow=createReservationWriteFlow({state,token:()=> 'owner',newKey:()=>String(++n),body:()=>{if(!confirmed)throw new ReservationError('invalid','confirm');return {quote_version_id:1}},request:async(_t,_b,k)=>{calls.push(k);throw new ReservationError('conflict','updated',40901)},onConflict:()=>{confirmed=false}})
  await flow.save();await flow.save();assert.equal(calls.length,1);confirmed=true;await flow.save();assert.deepEqual(calls,['1','2'])
})
test('hidden or changed identity cannot receive late order response',async()=>{
  const state=initialReservationWriteState(),pending=deferred();let actor='a';const flow=createReservationWriteFlow({state,token:()=>actor,body:()=>({slot_id:1}),newKey:()=>key,request:()=>pending.promise});const saving=flow.save();actor='b';flow.reset();pending.resolve({order_id:1});await saving;assert.equal(state.saved,null)
})
test('detail reader clears rejected data and ignores hidden late response',async()=>{
  const state=initialReservationReadState(),pending=deferred();const flow=createReservationReadFlow({state,token:()=> 'a',request:()=>pending.promise});const read=flow.load();flow.suspend();pending.resolve({order_id:1});await read;assert.equal(state.value,null)
  const second=createReservationReadFlow({state,token:()=> 'a',request:async()=>{throw new ReservationError('missing','订单不可用')}});state.value={order_id:2};await second.load();assert.equal(state.value,null);assert.equal(state.failureKind,'missing')
})
test('reservation date follows Beijing calendar independent of browser timezone',()=>{assert.equal(chinaDate(new Date('2026-10-07T16:30:00Z')),'2026-10-08')})
