import test from 'node:test'
import assert from 'node:assert/strict'
import {vinResponse,vinSourceLabel} from '../src/services/vin-candidate.js'
import {createVinDecode} from '../src/services/vin-decode.js'
import {createVehicleFlow,initialVehicleState} from '../src/services/vehicle-flow.js'
const vin='LTEST123456789012'
const candidate={model_id:3,series_id:2,brand_id:1,brand_name:'合成品牌',series_name:'合成车系',year:'2026',config_name:'配置A'}
const value=()=>({vin,model_year_hint:null,status:'CANDIDATE',source:'LOCAL_SYNTHETIC',coverage:'PINNED_LOCAL_RULES_ONLY',requires_confirmation:true,source_revision:'SYNTHETIC-1',synthetic:true,fields:{make:'合成品牌',model:'合成车系',model_year:'2026',trim:'配置A'},catalog_candidates:[{...candidate}]})
test('synthetic VIN source remains explicit and exact candidates cannot override source facts',()=>{
 assert.match(vinSourceLabel(vinResponse(value(),vin,null)),/合成测试规则（非真实识别）/)
 for(const changes of [{synthetic:false},{source_revision:'bad\nrevision'},{catalog_candidates:[{...candidate,year:'2025'}]},{catalog_candidates:[candidate,candidate]},{catalog_candidates:[{...candidate,model_id:0}]},{coverage:'US_MARKET_REGISTRATION_ONLY'}])assert.throws(()=>vinResponse({...value(),...changes},vin,null))
})
test('local provider sends distinct consent and rejects remote-source responses',async()=>{
 let options;const decode=createVinDecode({baseUrl:'https://test.invalid',provider:'LOCAL_LICENSED',runtime:()=>({request(o){options=o;return {abort(){}}}})})
 const pending=decode({token:'one',vin,consent:true});assert.equal(options.data.consent_version,'VIN_LOCAL_V1');options.success({statusCode:200,data:{code:0,data:value()}});assert.equal((await pending).synthetic,true)
 const other=decode({token:'one',vin,consent:true});options.success({statusCode:200,data:{code:0,data:{...value(),source:'NHTSA_VPIC'}}});await assert.rejects(other,{kind:'protocol'})
})
function harness({changed=false,paginated=false}={}){
 let token='one',writes=0;const state=initialVehicleState();const api={catalog:async(t,kind,parent,page)=>({list:kind==='brand'?(paginated&&page===1?[{id:9,name:'其他'}]:[{id:1,name:'合成品牌'}]):kind==='series'?[{id:2,name:'合成车系'}]:[{id:3,year:'2026',config_name:changed?'配置B':'配置A'}],total:kind==='brand'&&paginated?2:1}),add(){writes++}}
 const flow=createVehicleFlow({state,api,token:()=>token,newKey:()=>''});return {state,flow,api,setToken(v){token=v;flow.reset()},get writes(){return writes}}
}
test('explicit exact candidate selection reloads paginated live catalog and never saves',async()=>{
 const h=harness({paginated:true});await h.flow.selectCandidate(candidate);assert.equal(h.state.modelId,3);assert.equal(h.state.year,'2026');assert.equal(h.writes,0)
 const changed=harness({changed:true});await changed.flow.selectCandidate(candidate);assert.equal(changed.state.modelId,0);assert.equal(changed.state.failureKind,'invalid')
})
test('logout or page suspension during catalog selection discards late candidates',async()=>{
 for(const cancel of [h=>h.setToken('two'),h=>h.flow.suspend()]){const h=harness();let finish;h.api.catalog=()=>new Promise(resolve=>{finish=resolve});const pending=h.flow.selectCandidate(candidate);cancel(h);finish({list:[{id:1,name:'合成品牌'}],total:1});await pending;assert.equal(h.state.modelId,0);assert.equal(h.state.message,'')}
})
test('candidate confirmation refreshes renamed brands and permits a clean retry after recovery',async()=>{
 const h=harness(),original=h.api.catalog;let renamed=true,brandReads=0
 h.api.catalog=async(...args)=>{const value=await original(...args);if(args[1]==='brand'){brandReads++;if(renamed)value.list[0].name='改名品牌'}return value}
 await h.flow.selectCandidate(candidate);assert.equal(h.state.modelId,0);assert.equal(h.state.failureKind,'invalid')
 renamed=false;await h.flow.selectCandidate(candidate);assert.equal(h.state.modelId,3);assert.equal(h.state.failureKind,'');assert.equal(brandReads,2)
})
