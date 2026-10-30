import test from 'node:test'
import assert from 'node:assert/strict'
import {createVehicleFlow,initialVehicleState} from '../src/services/vehicle-flow.js'

for(const kind of ['series','model']) {
 test(`comparison resumes interrupted ${kind} loading after returning to the page`,async()=>{
  const state=initialVehicleState(),calls=[]
  state.brand=[{id:1,name:'brand'}]
  if(kind==='model') { state.brandId=1;state.series=[{id:2,name:'series'}] }
  let finishOld
  const fresh=kind==='series'?{id:2,name:'current series'}:{id:3,year:'2026',config_name:'current config'}
  const api={catalog(_actor,scope,parent,page){
   calls.push({scope,parent,page})
   if(calls.length===1)return new Promise(resolve=>{finishOld=resolve})
   return Promise.resolve({list:[fresh],total:1})
  }}
  const flow=createVehicleFlow({state,api,token:()=> 'owner',newKey:()=> ''})
  const pending=kind==='series'?flow.selectBrand(1):flow.selectSeries(2)
  flow.suspend()
  finishOld({list:[{id:99,name:'stale'}],total:1})
  await pending
  assert.equal(state.brand.length,1)
  assert.equal(state[kind].length,0)
  assert.equal(state.busy,false)
  assert.equal(state.catalogRetry,null)
  await flow.resumeCatalog()
  assert.deepEqual(calls,[{scope:kind,parent:kind==='series'?1:2,page:1},{scope:kind,parent:kind==='series'?1:2,page:1}])
  assert.deepEqual(state[kind],[fresh])
  assert.equal(state.busy,false)
 })
}
