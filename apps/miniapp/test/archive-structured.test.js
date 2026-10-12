import test from 'node:test'
import assert from 'node:assert/strict'
import { createArchiveStructuredApi, validStructuredFields, structuredBody, structuredForm, canReturnToStructured } from '../src/services/archive-structured.js'
const context={vehicle_id:101,archive_id:11,archive_type:1,recorded_date:'2026-10-01',revision:0,fields:{},status:'NOT_SET'}
const key='11111111-1111-4111-8111-111111111111'
function api(reply){return createArchiveStructuredApi({baseUrl:'https://test.invalid',runtime:()=>({request:reply})})}
test('typed fields reject unknown, cross-type, controls and fabricated enum values',()=>{
  assert.equal(validStructuredFields(1,{service_items:['机油'],performed_mileage:0}),true)
  for(const fields of [{score:90},{performed_mileage:1.2},{service_items:[]},{service_items:['机油','机油']},{provider_name:null},{provider_name:'a\n'},{insurer_name:'公司'}])assert.equal(validStructuredFields(1,fields),false)
  assert.equal(validStructuredFields(7,{inspection_result:'YES'}),false)
  assert.equal(validStructuredFields(3,{coverage_type:'OTHER'}),true)
})
test('dates order, lengths and full replacement/clear are explicit',()=>{
  assert.equal(validStructuredFields(3,{coverage_end:'2027-02-29'}),false)
  assert.equal(validStructuredFields(3,{coverage_start:'2027-01-02',coverage_end:'2027-01-01'}),false)
  assert.equal(validStructuredFields(3,{policy_number:'x'.repeat(65)}),false)
  const body=structuredBody(context,{provider_name:' 单位 ',service_items:'机油\n滤芯',performed_mileage:'0'})
  assert.deepEqual(body.fields,{provider_name:'单位',service_items:['机油','滤芯'],performed_mileage:0})
  assert.deepEqual(structuredBody(context,{},true),{expected_revision:0,fields:{}})
  assert.throws(()=>structuredBody(context,{}));assert.throws(()=>structuredBody(context,{performed_mileage:'1e3'}))
  assert.equal(structuredForm({...context,fields:body.fields}).service_items,'机油\n滤芯')
})
test('source response requires current vehicle, exact projection and unverified status',async()=>{
  await assert.rejects(api(r=>r.success({statusCode:200,data:{code:0,data:{...context,vehicle_id:202}}})).context('owner',11,101),{kind:'protocol'})
  await assert.rejects(api(r=>r.success({statusCode:200,data:{code:0,data:{...context,extra:'private'}}})).context('owner',11,101),{kind:'protocol'})
  await assert.rejects(api(r=>r.success({statusCode:200,data:{code:0,data:{...context,fields:{provider_name:'单位'}}}})).context('owner',11,101),{kind:'protocol'})
  assert.deepEqual(await api(r=>r.success({statusCode:200,data:{code:0,data:context}})).context('owner',11,101),context)
})
test('save uses PUT UUID and a private-field-free matching receipt',async()=>{
  const body=structuredBody(context,{provider_name:'单位'}),receipt={vehicle_id:101,archive_id:11,archive_type:1,revision:1,status:'OWNER_ENTERED_UNVERIFIED'}
  const client=api(r=>{assert.equal(r.method,'PUT');assert.equal(r.header['Idempotency-Key'],key);assert.deepEqual(r.data,body);r.success({statusCode:200,data:{code:0,data:receipt}})})
  assert.deepEqual(await client.save('owner',context,body,key),receipt)
  await assert.rejects(api(r=>r.success({statusCode:200,data:{code:0,data:{...receipt,fields:body.fields}}})).save('owner',context,body,key),{kind:'protocol'})
})
test('errors and login return are controlled',async()=>{
  for(const [code,kind] of [[401,'unauthorized'],[403,'forbidden'],[404,'missing'],[409,'conflict'],[503,'unavailable']])await assert.rejects(api(r=>r.success({statusCode:code,data:{message:'secret'}})).context('owner',11,101),{kind})
  assert.equal(canReturnToStructured([{route:'pages/archive/structured'},{route:'pages/owner/index'}]),true)
  assert.equal(canReturnToStructured([{route:'pages/home/index'},{route:'pages/owner/index'}]),false)
})
