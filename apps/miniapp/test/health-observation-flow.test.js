import test from 'node:test'
import assert from 'node:assert/strict'
import { createObservationFlow,initialObservationState,createObservationListFlow,initialObservationListState } from '../src/services/health-observation-flow.js'
import { HealthObservationError } from '../src/services/health-observations.js'
const measurement={measurement_name:'读数',value:'12.50',unit:'V',measured_on:'2026-10-12',method:'仪表记录',provenance:'OWNER_MEASURED'}
const source={vehicle_id:101,archive_id:11,metric:'AUX_BATTERY',today:'2026-10-12',observation:{revision:0,archive_id:null,measurement:{},status:'NOT_SET'}}
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r});return {promise,resolve}}
function editor(api,initial=source,confirm=async()=>true){const state=initialObservationState();let owner='owner',key=0;const client={context:async()=>initial,...api};const flow=createObservationFlow({state,api:client,token:()=>owner,newKey:()=>`key-${++key}`,confirmClear:confirm,confirmReplacement:confirm});flow.route(11,101,'AUX_BATTERY');return {state,flow,token:v=>{owner=v}}}
test('list and editor ignore old replies after hide or account/car changes',async()=>{
  const d=deferred(),state=initialObservationListState(),flow=createObservationListFlow({state,api:{list:()=>d.promise},token:()=> 'owner'});const p=flow.resume(101);flow.select(202);flow.suspend();d.resolve({list:['private'],today:'2026-10-12'});await p;assert.deepEqual(state.rows,[])
  const wait=deferred(),f=editor({context:()=>wait.promise});const loading=f.flow.resume();f.flow.suspend();wait.resolve(source);await loading;assert.equal(f.state.context,null);assert.deepEqual(f.state.form,{})
})
test('identical retry keeps key, edits change key and duplicate taps are blocked',async()=>{
  const seen=[],d=deferred();let count=0;const f=editor({save:async(t,c,b,key)=>{seen.push(key);if(++count<3)throw new HealthObservationError('network','retry');return d.promise}});await f.flow.resume();f.state.form={...measurement};await f.flow.save();await f.flow.save();assert.equal(seen[0],seen[1]);f.state.form.value='12.6';const p=f.flow.save();await f.flow.save();assert.equal(seen.length,3);assert.notEqual(seen[2],seen[1]);d.resolve();await p
})
test('conflict blocks further writes until source refresh',async()=>{
  let writes=0;const f=editor({save:async()=>{writes++;throw new HealthObservationError('conflict','refresh')}});await f.flow.resume();f.state.form={...measurement};await f.flow.save();await f.flow.save();assert.equal(writes,1);await f.flow.load();f.state.form={...measurement};await f.flow.save();assert.equal(writes,2)
})
test('replacement leaves form empty and confirmation/clear cancellation prevent writes',async()=>{
  const old={...source,observation:{revision:1,archive_id:14,measurement,status:'RECORDED_UNVERIFIED'}};let accepted=false;const bodies=[];const f=editor({save:async(t,c,b)=>bodies.push(b)},old,async()=>accepted);await f.flow.resume();assert.equal(f.state.form.value,'');f.state.form={...measurement};await f.flow.save();assert.equal(bodies.length,0);accepted=true;await f.flow.save();assert.equal(bodies[0].replace_source,true)
  const own={...source,observation:{...old.observation,archive_id:11}};accepted=false;const g=editor({save:async(t,c,b)=>bodies.push(b)},own,async()=>accepted);await g.flow.resume();await g.flow.save(true);assert.equal(bodies.length,1);accepted=true;await g.flow.save(true);assert.deepEqual(bodies[1].measurement,{})
})
test('account change during confirmation and late successful write cannot restore private form',async()=>{
  const d=deferred();let writes=0;const old={...source,observation:{revision:1,archive_id:14,measurement,status:'RECORDED_UNVERIFIED'}};const f=editor({save:async()=>writes++},old,()=>d.promise);await f.flow.resume();f.state.form={...measurement};const p=f.flow.save();f.token('other');f.flow.reset();d.resolve(true);await p;assert.equal(writes,0)
  const response=deferred(),g=editor({save:()=>response.promise});await g.flow.resume();g.state.form={...measurement};const saving=g.flow.save();g.flow.suspend();response.resolve();await saving;assert.equal(g.state.context,null);assert.deepEqual(g.state.form,{})
})
