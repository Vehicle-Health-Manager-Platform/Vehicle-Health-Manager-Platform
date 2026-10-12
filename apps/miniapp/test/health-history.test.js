import test from 'node:test'
import assert from 'node:assert/strict'
import { createHealthObservationApi,validHistoryPage,observationBody,HealthObservationError } from '../src/services/health-observations.js'
import { createHealthHistoryFlow,initialHealthHistoryState } from '../src/services/health-history-flow.js'
import { createObservationFlow,initialObservationState } from '../src/services/health-observation-flow.js'
const measurement={measurement_name:'电压',value:'12.50',unit:'V',measured_on:'2026-10-12',method:'仪表',provenance:'OWNER_MEASURED'}
const row=(revision=1)=>({metric:'AUX_BATTERY',revision,archive_id:11,recorded_date:'2026-10-01',measurement:{...measurement},status:'RECORDED_UNVERIFIED',operation:'RECORD',supersedes_revision:null,saved_at:'2026-10-12T00:00:00Z',corrected:false})
const page=(list=[row()],more=false)=>({vehicle_id:101,metric:'AUX_BATTERY',today:'2026-10-12',list,has_more:more,next_before_revision:more?list.at(-1).revision:null})
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r});return {resolve,promise}}
test('history contract verifies ordering, cursor, redaction and correction chain',()=>{
  const data=page([row(3),{...row(2),operation:'CORRECT',supersedes_revision:1}, {...row(1),corrected:true}])
  assert.equal(validHistoryPage(data,101,'AUX_BATTERY'),true)
  for(const change of [{vehicle_id:201},{has_more:true},{next_before_revision:1},{list:[row(1),row(2)]},{list:[{...row(),operation:'CORRECT',supersedes_revision:5}]},{list:[{...row(),status:'SOURCE_UNAVAILABLE'}]},{list:[{...row(),score:88}]}])assert.equal(validHistoryPage({...data,...change},101,'AUX_BATTERY'),false)
  const hidden={...row(),status:'SOURCE_UNAVAILABLE',archive_id:null,recorded_date:null,measurement:{}}
  assert.equal(validHistoryPage(page([hidden]),101,'AUX_BATTERY'),true)
  assert.equal(validHistoryPage(page([{...hidden,status:'NOT_SET',operation:'CLEAR'}]),101,'AUX_BATTERY'),true)
  assert.equal(validHistoryPage(page([{...row(),operation:'MIGRATED_SNAPSHOT'}]),101,'AUX_BATTERY'),true)
  assert.equal(validHistoryPage(page([row(2)],true),101,'AUX_BATTERY',3,1),true)
  assert.equal(validHistoryPage(page([row(3)]),101,'AUX_BATTERY',3),false)
})
test('history request carries bounded cursor and rejects malformed replies',async()=>{
  const api=createHealthObservationApi({baseUrl:'https://test.invalid',runtime:()=>({request:r=>{assert.match(r.url,/before_revision=3/);assert.equal(r.header.Authorization,'Bearer owner');r.success({statusCode:200,data:{code:0,data:page([row(2)])}})}})})
  assert.equal((await api.history('owner',101,'AUX_BATTERY',3)).list[0].revision,2)
  for(const before of [0,-1,2.5,2147483648])assert.throws(()=>api.history('owner',101,'AUX_BATTERY',before),{kind:'invalid'})
  const invalid=createHealthObservationApi({baseUrl:'https://test.invalid',runtime:()=>({request:r=>r.success({statusCode:200,data:{code:0,data:page([{...row(),measurement:{secret:'private'}}])}})})})
  await assert.rejects(invalid.history('owner',101,'AUX_BATTERY'),{kind:'protocol'})
})
test('history pagination retry keeps cursor and rows, duplicate taps blocked',async()=>{
  const state=initialHealthHistoryState(),cursors=[],wait=deferred();let count=0
  const flow=createHealthHistoryFlow({state,token:()=> 'owner',api:{history:async(t,v,m,b)=>{cursors.push(b);if(b===null)return page([row(3)],true);if(++count===1)throw new HealthObservationError('network','retry');return wait.promise}}})
  await flow.resume(101,'AUX_BATTERY');await flow.more();assert.equal(state.rows.length,1);assert.equal(state.next,3)
  const pending=flow.more();await flow.more();assert.deepEqual(cursors,[null,3,3]);wait.resolve(page([row(2),row(1)]));await pending
  assert.deepEqual(state.rows.map(r=>r.revision),[3,2,1]);assert.equal(state.hasMore,false);await flow.more();assert.equal(cursors.length,3)
})
test('hide, account change and car change discard late private history',async()=>{
  for(const action of ['hide','account','car']){
    const state=initialHealthHistoryState(),wait=deferred();let owner='one'
    const flow=createHealthHistoryFlow({state,token:()=>owner,api:{history:()=>wait.promise}})
    const pending=flow.resume(101,'AUX_BATTERY')
    if(action==='hide')flow.suspend();else if(action==='account'){owner='two';flow.reset()}else flow.resume(201,'OIL')
    wait.resolve(page());await pending;await Promise.resolve()
    if(action!=='car')assert.deepEqual(state.rows,[])
    else assert.equal(state.vehicleId,201)
  }
})
test('permission loss on next page clears already displayed private values',async()=>{
  for(const kind of ['unauthorized','forbidden','missing']){
    const state=initialHealthHistoryState(),flow=createHealthHistoryFlow({state,token:()=> 'owner',api:{history:async(t,v,m,b)=>{if(b!==null)throw new HealthObservationError(kind,'unavailable');return page([row(2)],true)}}})
    await flow.resume(101,'AUX_BATTERY');await flow.more();assert.deepEqual(state.rows,[]);assert.equal(state.next,null);assert.equal(state.loaded,false)
  }
})
test('correction requires current valid source, confirmation and a distinct retry fingerprint',async()=>{
  const context={vehicle_id:101,archive_id:11,metric:'AUX_BATTERY',today:'2026-10-12',observation:row(1)}
  assert.equal(observationBody(context,measurement,false,'CORRECT').operation,'CORRECT')
  assert.throws(()=>observationBody({...context,archive_id:14},measurement,false,'CORRECT'),{kind:'invalid'})
  assert.throws(()=>observationBody(context,{},true,'CORRECT'),{kind:'invalid'})
  const state=initialObservationState(),seen=[];let accepted=false,key=0
  const flow=createObservationFlow({state,token:()=> 'owner',newKey:()=>`key-${++key}`,confirmCorrection:async()=>accepted,confirmClear:async()=>true,confirmReplacement:async()=>true,api:{context:async()=>context,save:async(t,c,b,k)=>{seen.push({b,k});throw new HealthObservationError('network','retry')}}})
  flow.route(11,101,'AUX_BATTERY');await flow.resume();await flow.save(false,'CORRECT');assert.equal(seen.length,0)
  accepted=true;await flow.save(false,'CORRECT');await flow.save(false,'CORRECT');assert.equal(seen[0].k,seen[1].k)
  await flow.save(false,'RECORD');assert.notEqual(seen[1].k,seen[2].k)
})
