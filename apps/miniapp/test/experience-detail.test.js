import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {createExperienceDetailApi,validExperienceDetail,validExperienceReference,experienceReferenceRoute,experienceReferenceOptions} from '../src/services/experience-detail.js'
import {createAsyncDiagnosisFlow,initialDiagnosisState} from '../src/services/async-diagnosis.js'
import {createApiRuntime} from '../src/services/api-runtime.js'
import {ServiceError} from '../src/services/service-catalog.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
const id='01234567-89ab-4cde-8fab-0123456789ab',other='11234567-89ab-4cde-8fab-0123456789ab'
const result=()=>({vehicle_id:2,experience:{experience_id:id,title:'施工经验摘要',summary:{version:1,work_minutes:40,part_kinds:0,no_parts:true,recorded_month:'2026-10'},model_id:3,published_at:'2026-10-09T00:00:00Z'}})
const fixture=(data=result(),statusCode=200)=>{const calls=[];return {calls,api:createExperienceDetailApi({baseUrl:'https://example.test/',runtime:()=>({request:o=>{calls.push(o);o.success({statusCode,data:{code:0,data}})}})})}}
test('detail binds the current receiving vehicle public UUID and exact model without private fields',()=>{
 assert.equal(validExperienceDetail(result(),2,id,3),true)
 for(const change of [n=>n.vehicle_id=1,n=>n.experience.experience_id=other,n=>n.experience.model_id=4,n=>n.user_id=1,n=>n.experience.card_id=1,n=>n.experience.summary.notes='private',n=>n.experience.summary.work_minutes=0,n=>n.experience.summary.no_parts=false]){const n=result();change(n);assert.equal(validExperienceDetail(n,2,id,3),false)}
})
test('detail publication timestamp rejects rollover and alternate time syntax',()=>{
 for(const stamp of ['2026-02-30T00:00:00Z','2026-10-09','2026-10-09T00:00:00+00:00','2026-10-09T00:00:00.000Z',null]){const n=result();n.experience.published_at=stamp;assert.equal(validExperienceDetail(n,2,id,3),false)}
})
test('application reference carries only the opaque public UUID and permits exact route options',()=>{
 const route=experienceReferenceRoute(id);assert.equal(route,`/pages/community/experiences?experience_id=${id}`);assert.doesNotMatch(route,/vehicle_id|user_id|card_id|token/)
 assert.deepEqual(experienceReferenceOptions({}),{valid:true,reference:''});assert.deepEqual(experienceReferenceOptions({experience_id:id}),{valid:true,reference:id})
 for(const n of ['1','../private',id.toUpperCase(),id+'?vehicle_id=1',null]){assert.equal(validExperienceReference(n),false);assert.equal(experienceReferenceRoute(n),'')}
 for(const q of [{experience_id:id,vehicle_id:'1'},{experience_id:id,cursor:'1'},{experience_id:[id]},null])assert.equal(experienceReferenceOptions(q).valid,false)
})
test('detail request is GET only with current owner token and required vehicle selector',async()=>{
 const f=fixture();await f.api.detail('owner',2,id,3);assert.equal(f.calls[0].method,'GET');assert.equal(f.calls[0].url,`https://example.test/api/community/experiences/${id}?vehicle_id=2`);assert.deepEqual(f.calls[0].header,{Authorization:'Bearer owner'});assert.equal(f.calls[0].data,undefined)
 for(const args of [['',2,id,3],['owner',0,id,3],['owner',2,'1',3],['owner',2,id,null]])assert.throws(()=>f.api.detail(...args))
 assert.throws(()=>createExperienceDetailApi({baseUrl:''}).detail('owner',2,id,3))
})
test('server scope mismatch and private extra response reject without a usable detail',async()=>{
 for(const change of [n=>n.vehicle_id=1,n=>n.experience.model_id=4,n=>n.experience.experience_id=other,n=>n.experience.order_id=1]){const n=result();change(n);await assert.rejects(fixture(n).api.detail('t',2,id,3),e=>e.kind==='protocol')}
})
test('missing denied and unavailable references stay explicit errors',async()=>{
 for(const [status,kind] of [[400,'invalid'],[401,'unauthorized'],[403,'forbidden'],[404,'missing'],[503,'unavailable']])await assert.rejects(fixture(result(),status).api.detail('t',2,id,3),e=>e.kind===kind)
})
test('native and cloud transport resolve the same reference without operator identity',async()=>{
 for(const cloud of [false,true]){const calls=[],send=o=>{calls.push(o);o.success({statusCode:200,data:{code:0,data:result()}})};const runtime=createApiRuntime({origin:'https://cloudrun.invalid',cloud:cloud?{env:'test',service:'api'}:{},runtime:()=>({request:send}),cloudApi:{init(){},callContainer:send}});const api=createExperienceDetailApi({baseUrl:'https://cloudrun.invalid',runtime:()=>runtime});await api.detail('owner',2,id,3);assert.equal(calls[0].header.Authorization,'Bearer owner');if(cloud)assert.equal(calls[0].path,`/api/community/experiences/${id}?vehicle_id=2`)}
})
test('late owner vehicle model reference and hidden-page replies are discarded',async()=>{
 let token='one',vehicle=2,model=3,reference=id,finish
 const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=>token,scope:()=>`${vehicle}:${model}:${reference}`,allowed:()=>Boolean(vehicle&&model&&reference),load:()=>new Promise(r=>finish=r)})
 for(const change of [()=>token='two',()=>vehicle=4,()=>model=5,()=>reference=other,()=>{}]){const pending=flow.load();change();flow.reset();finish(result());await pending;assert.equal(state.data,null);assert.equal(state.busy,false)}
})
test('fresh missing reference clears previously displayed facts and cannot become an empty success',async()=>{
 let missing=false;const state=initialDiagnosisState(),flow=createAsyncDiagnosisFlow({state,token:()=> 't',scope:()=>id,load:async()=>{if(missing)throw new ServiceError('missing','gone');return result()}});await flow.load();assert.ok(state.data);missing=true;await flow.load();assert.equal(state.data,null);assert.equal(state.kind,'missing')
})
test('actual existing experience page and AI evidence attach fresh controlled reference reading',()=>{
 const read=p=>readFileSync(new URL('../src/'+p,import.meta.url),'utf8'),page=read('pages/community/experiences.vue'),plan=read('pages/ai/service-plan.vue')
 for(const re of [/experienceReferenceOptions\(q/,/createExperienceDetailApi/,/createAsyncDiagnosisFlow/,/vehicle\.value\?\.model_id/,/detailFlow.reset\(/,/onHide\(/,/复制应用内引用入口/,/detail.data/,/kind==='missing'/,/watch\(\(\)=>state.kind/,/function refresh\(\)\{detailFlow.reset\(\);curatedFlow.reset\(\);start\(\)\}/,/@tap="refresh"/])assert.match(page,re)
 assert.match(plan,/experienceReferenceRoute\(row.experience_id\)/);assert.match(plan,/selectOwnerVehicle\(vehicle.value\)/);assert.doesNotMatch(page,/onShareAppMessage|setStorageSync/)
})
test('login return resumes only the exact owner experience reference page',()=>{
 const pages=q=>[{route:'pages/community/experiences',options:q},{route:'pages/owner/index'}]
 assert.equal(serviceLoginReturn(pages({}),'owner'),true);assert.equal(serviceLoginReturn(pages({experience_id:id}),'owner'),true)
 for(const q of [{experience_id:'private'},{experience_id:id,vehicle_id:1},{user_id:1}])assert.equal(serviceLoginReturn(pages(q),'owner'),false)
 for(const role of ['merchant','technician','admin'])assert.equal(serviceLoginReturn(pages({experience_id:id}),role),false)
})
