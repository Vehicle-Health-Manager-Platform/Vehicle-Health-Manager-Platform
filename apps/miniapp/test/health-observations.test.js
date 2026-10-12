import test from 'node:test'
import assert from 'node:assert/strict'
import { createHealthObservationApi,validMeasurement,validObservation,observationBody,canReturnToHealth,healthMetrics } from '../src/services/health-observations.js'
const measurement={measurement_name:'读数',value:'12.50',unit:'V',measured_on:'2026-10-12',method:'仪表记录',provenance:'OWNER_MEASURED'}
const empty=(metric='AUX_BATTERY')=>({metric,revision:0,archive_id:null,recorded_date:null,measurement:{},status:'NOT_SET'})
const context={vehicle_id:101,archive_id:11,archive_type:1,recorded_date:'2026-10-01',metric:'AUX_BATTERY',today:'2026-10-12',observation:empty()}
const api=reply=>createHealthObservationApi({baseUrl:'https://test.invalid',runtime:()=>({request:reply})})
test('units, decimal formats, dates and provenance are strictly checked without a score',()=>{
  assert.equal(validMeasurement('AUX_BATTERY',measurement,context.today),true)
  for(const change of [{value:'1e2'},{value:'-1'},{value:'01'},{value:'1.1234'},{unit:'MM'},{measured_on:'2026-10-13'},{measured_on:'2026-02-29'},{provenance:'OFFICIAL'},{method:''},{score:100}])assert.equal(validMeasurement('AUX_BATTERY',{...measurement,...change},context.today),false)
  assert.equal(validMeasurement('COOLANT',{...measurement,value:'-25.5',unit:'CELSIUS'},context.today),true)
  assert.equal(validMeasurement('TRACTION_BATTERY',{...measurement,value:'101',unit:'PERCENT'},context.today),false)
})
test('not set and invalid source require redaction, recorded requires a valid source',()=>{
  assert.equal(validObservation(empty(),context.today),true)
  assert.equal(validObservation({...empty(),revision:1,status:'SOURCE_UNAVAILABLE'},context.today),true)
  assert.equal(validObservation({...empty(),status:'SOURCE_UNAVAILABLE'},context.today),false)
  assert.equal(validObservation({...empty(),revision:1,archive_id:11,measurement,status:'SOURCE_UNAVAILABLE'},context.today),false)
  assert.equal(validObservation({...empty(),revision:1,archive_id:11,recorded_date:'2026-10-01',measurement,status:'RECORDED_UNVERIFIED'},context.today),true)
})
test('replacement and clear body preserve versions without default data',()=>{
  assert.throws(()=>observationBody(context,{}))
  assert.deepEqual(observationBody(context,measurement),{metric:'AUX_BATTERY',expected_revision:0,measurement,replace_source:false})
  const old={...context,observation:{...empty(),revision:2,archive_id:14,status:'RECORDED_UNVERIFIED',measurement}}
  assert.equal(observationBody(old,measurement).replace_source,true)
  assert.deepEqual(observationBody(old,{},true),{metric:'AUX_BATTERY',expected_revision:2,measurement:{},replace_source:false})
})
test('context and six-row list must match car, metric, ordering and exact projection',async()=>{
  assert.deepEqual(await api(r=>r.success({statusCode:200,data:{code:0,data:context}})).context('owner',11,101,'AUX_BATTERY'),context)
  await assert.rejects(api(r=>r.success({statusCode:200,data:{code:0,data:{...context,vehicle_id:202}}})).context('owner',11,101,'AUX_BATTERY'),{kind:'protocol'})
  const list={vehicle_id:101,today:context.today,list:healthMetrics.map(empty)}
  await api(r=>r.success({statusCode:200,data:{code:0,data:list}})).list('owner',101)
  await assert.rejects(api(r=>r.success({statusCode:200,data:{code:0,data:{...list,list:[...list.list].reverse()}}})).list('owner',101),{kind:'protocol'})
})
test('PUT uses UUID and versioned private-field-free receipt, failures are controlled',async()=>{
  const key='11111111-1111-4111-8111-111111111111',body=observationBody(context,measurement),receipt={vehicle_id:101,archive_id:11,metric:'AUX_BATTERY',revision:1,status:'RECORDED_UNVERIFIED'}
  await api(r=>{assert.equal(r.method,'PUT');assert.equal(r.header['Idempotency-Key'],key);r.success({statusCode:200,data:{code:0,data:receipt}})}).save('owner',context,body,key)
  await assert.rejects(api(r=>r.success({statusCode:200,data:{code:0,data:{...receipt,measurement}}})).save('owner',context,body,key),{kind:'protocol'})
  for(const [statusCode,kind] of [[401,'unauthorized'],[403,'forbidden'],[404,'missing'],[409,'conflict'],[503,'unavailable']])await assert.rejects(api(r=>r.success({statusCode,data:{message:'secret'}})).list('owner',101),{kind})
  assert.equal(canReturnToHealth([{route:'pages/archive/health'},{route:'pages/owner/index'}]),true)
})
