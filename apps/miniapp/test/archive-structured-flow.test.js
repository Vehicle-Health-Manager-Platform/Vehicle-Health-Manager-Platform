import test from 'node:test'
import assert from 'node:assert/strict'
import { createStructuredFlow, initialStructuredState } from '../src/services/archive-structured-flow.js'
import { ArchiveStructuredError } from '../src/services/archive-structured.js'
const source={vehicle_id:101,archive_id:11,archive_type:1,recorded_date:'2026-10-01',revision:0,fields:{},status:'NOT_SET'}
const deferred=()=>{let resolve;const promise=new Promise(r=>{resolve=r});return {promise,resolve}}
function fixture(api,confirmClear=async()=>true){const state=initialStructuredState();let owner='owner',keys=0;const flow=createStructuredFlow({state,api,token:()=>owner,newKey:()=>`key-${++keys}`,confirmClear});flow.route(11,101);return {state,flow,token:v=>{owner=v}}}
test('late loads after hiding or changing owner never restore private form',async()=>{
  const d=deferred(),f=fixture({context:()=>d.promise});const loading=f.flow.resume();f.flow.suspend();d.resolve({...source,fields:{provider_name:'私密'}});await loading;assert.equal(f.state.context,null);assert.deepEqual(f.state.form,{})
  const d2=deferred(),g=fixture({context:()=>d2.promise});const other=g.flow.resume();g.token('other');d2.resolve(source);await other;assert.equal(g.state.context,null)
})
test('same payload retry keeps key, edits get a new key and duplicate taps are blocked',async()=>{
  const seen=[],d=deferred();let count=0;const f=fixture({context:async()=>source,save:async(t,c,b,key)=>{seen.push({b,key});if(++count<3)throw new ArchiveStructuredError('network','重试');return d.promise}})
  await f.flow.resume();f.state.form.provider_name='单位';await f.flow.save();await f.flow.save();assert.equal(seen[0].key,seen[1].key)
  f.state.form.provider_name='新单位';const writing=f.flow.save();await f.flow.save();assert.equal(seen.length,3);assert.notEqual(seen[2].key,seen[1].key);d.resolve();await writing
})
test('conflict requires refreshed source before another save',async()=>{
  let calls=0;const f=fixture({context:async()=>source,save:async()=>{calls++;throw new ArchiveStructuredError('conflict','变化')}})
  await f.flow.resume();f.state.form.provider_name='单位';await f.flow.save();await f.flow.save();assert.equal(calls,1);await f.flow.load();f.state.form.provider_name='单位';await f.flow.save();assert.equal(calls,2)
})
test('clear cancellation and account change during confirmation prevent writes',async()=>{
  let writes=0;const api={context:async()=>({...source,revision:1,fields:{provider_name:'单位'},status:'OWNER_ENTERED_UNVERIFIED'}),save:async(t,c,b)=>{writes++;assert.deepEqual(b,{expected_revision:1,fields:{}})}}
  const f=fixture(api,async()=>false);await f.flow.resume();await f.flow.save(true);assert.equal(writes,0)
  const d=deferred(),g=fixture(api,()=>d.promise);await g.flow.resume();const pending=g.flow.save(true);g.token('other');g.flow.reset();d.resolve(true);await pending;assert.equal(writes,0)
  const h=fixture(api);await h.flow.resume();await h.flow.save(true);assert.equal(writes,1)
})
test('a late successful write after hiding cannot restore source or values',async()=>{
  const d=deferred(),f=fixture({context:async()=>source,save:()=>d.promise});await f.flow.resume();f.state.form.provider_name='单位';const pending=f.flow.save();f.flow.suspend();d.resolve();await pending;assert.equal(f.state.context,null);assert.deepEqual(f.state.form,{})
})
