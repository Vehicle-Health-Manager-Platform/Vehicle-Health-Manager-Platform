import test from 'node:test'
import assert from 'node:assert/strict'
import { pickupBody, createPickupApi, PICKUP_SLOTS } from '../src/services/pickup.js'
import { createImageApi } from '../src/services/private-images.js'
import { createReservationWriteFlow,initialReservationWriteState } from '../src/services/reservations.js'
const form=()=>({code:'012345',mileage:'123',mileageReason:'',arrivalReason:'',fuel:'HALF',damageStatus:'NONE',damages:[],photos:Object.fromEntries(PICKUP_SLOTS.map((s,i)=>[s,{fileId:i+1}]))})
const key='01234567-89ab-4cde-8fab-0123456789ab'
const sheet={pickup_check_id:1,order_id:2,photos:Object.fromEntries(PICKUP_SLOTS.map((s,i)=>[s,i+1])),mileage:123,owner_confirm:0,damages:[],dispute:null}
test('接车必须七个独立上传、明确油量与损伤，预约码保留前导零',()=>{
  const f=form();assert.equal(pickupBody(2,f).appointment_code,'012345');assert.equal(pickupBody(2,f).mileage,123)
  f.photos.ROOF.fileId=0;assert.throws(()=>pickupBody(2,f));f.photos.ROOF.fileId=1;assert.throws(()=>pickupBody(2,f))
  for(const field of ['fuel','damageStatus','code','mileage']){const invalid=form();invalid[field]='';assert.throws(()=>pickupBody(2,invalid))}
})
test('损伤必须带照片位置与说明，无损伤明确清除标注',()=>{
  const f=form();f.damageStatus='PRESENT';assert.throws(()=>pickupBody(2,f));f.damages=[{photo_slot:'LEFT',x:0.5,y:0.25,note:' 划痕 '}]
  assert.equal(pickupBody(2,f).damages[0].note,'划痕');f.damages[0].x=1.1;assert.throws(()=>pickupBody(2,f));f.damageStatus='NONE';assert.deepEqual(pickupBody(2,f).damages,[])
})
test('商家上传使用独立路径和身份，仍携带原图片幂等键',async()=>{
  let call;const api=createImageApi({baseUrl:'http://local',prefix:'/api/merchant/files',runtime:()=>({uploadFile:o=>{call=o;o.success({statusCode:200,data:JSON.stringify({code:0,data:{file_id:1,size_bytes:10,content_type:'image/png'}})})}})})
  await api.upload('shop',{path:'original.png',size:10},key);assert.equal(call.url,'http://local/api/merchant/files/upload');assert.equal(call.header.Authorization,'Bearer shop');assert.equal(call.header['Idempotency-Key'],key);assert.equal(call.filePath,'original.png')
})
test('接车接口拒绝串单响应、越权及泄漏服务器错误',async()=>{
  let status=200,data=sheet;const api=createPickupApi({baseUrl:'http://local',runtime:()=>({request:o=>o.success({statusCode:status,data:{code:status===200?0:40300,data,message:'private SQL'}})})})
  assert.deepEqual(await api.detail('owner',2),sheet);await assert.rejects(api.detail('owner',3),e=>e.kind==='protocol');status=403;await assert.rejects(api.detail('foreign',2),e=>e.kind==='forbidden'&&!e.message.includes('SQL'));assert.throws(()=>api.context('',2),e=>e.kind==='unauthorized')
})
test('提交重试保持原内容和键，隐藏与身份切换丢弃迟到接车响应',async()=>{
  const state=initialReservationWriteState();let actor='shop', resolve, calls=[],attempt=0
  const flow=createReservationWriteFlow({state,token:()=>actor,body:()=>pickupBody(2,form()),newKey:()=>key,request:(t,b,k)=>{calls.push({t,b,k});if(attempt++===0)return Promise.reject(Object.assign(new Error(),{kind:'unavailable'}));return new Promise(r=>{resolve=r})}})
  await flow.save();const work=flow.save();assert.equal(calls.length,2);assert.deepEqual(calls[0],calls[1]);flow.suspend();resolve(sheet);await work;assert.equal(state.saved,null)
  const switched=flow.save();actor='other';flow.reset();resolve(sheet);await switched;assert.equal(state.saved,null)
})
test('车主决策只提交动作与本人订单，异议必须说明',async()=>{
  let sent;const api=createPickupApi({baseUrl:'http://local',runtime:()=>({request:o=>{sent=o;o.success({statusCode:200,data:{code:0,data:{...sheet,owner_confirm:o.data.decision==='DISPUTE'?2:1}}})}})})
  await api.decide('owner',2,'CONFIRM','',key);assert.equal(sent.url,'http://local/api/check/pickup/confirm');assert.equal(sent.header['Idempotency-Key'],key);assert.deepEqual(sent.data,{order_id:2,decision:'CONFIRM'})
  await api.decide('owner',2,'DISPUTE',' 照片不符 ',key);assert.deepEqual(sent.data,{order_id:2,decision:'DISPUTE',reason:'照片不符'})
  assert.throws(()=>api.decide('owner',2,'DISPUTE','  ',key));assert.throws(()=>api.decide('owner',2,'DISPUTE','x'.repeat(501),key))
})
