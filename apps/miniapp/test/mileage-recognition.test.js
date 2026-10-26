import test from 'node:test'
import assert from 'node:assert/strict'
import {createMileageRecognition,mileageResponse} from '../src/services/mileage-recognition.js'
import {createCandidateFlow,initialCandidateState,candidateFields} from '../src/services/input-candidates.js'
import {createArchiveFlow,initialArchiveState} from '../src/services/archive-flow.js'
const data=(status='CANDIDATE',confidence=0.95)=>({vehicle_id:11,file_id:7,review_status:status,requires_confirmation:true,recognition:{version:1,scene:'archive',status:'CANDIDATE',fields:[{key:'mileage',value:'12345',confidence}]}})
function client(){const requests=[];let aborted=0;const api=createMileageRecognition({baseUrl:'https://api.example',runtime:()=>({request:o=>{requests.push(o);return {abort(){aborted++}}}})});return {api,requests,get aborted(){return aborted}}}
const args=()=>({token:'owner-test',vehicleId:11,fileId:7,consent:true})
test('missing explicit consent, identity, valid source or pre-aborted signal sends no request',async()=>{
 const c=client()
 for(const patch of [{consent:false},{consent:undefined},{token:''},{vehicleId:0},{fileId:0},{signal:{aborted:true}}]) await assert.rejects(c.api({...args(),...patch}))
 assert.equal(c.requests.length,0)
})
test('fixed gateway receives only JWT, selected identifiers and versioned consent',async()=>{
 const c=client(),p=c.api(args()),o=c.requests[0]
 assert.equal(o.url,'https://api.example/api/vehicle/mileage-recognition');assert.equal(o.method,'POST')
 assert.deepEqual(o.header,{Authorization:'Bearer owner-test','content-type':'application/json'})
 assert.deepEqual(o.data,{vehicle_id:11,file_id:7,consent:true,consent_version:'MILEAGE_OCR_V1'})
 o.success({statusCode:200,data:{code:0,data:data()}});assert.equal((await p).fields[0].value,'12345')
})
test('strict gateway responses reject wrong source, extra fields and inconsistent candidate semantics',()=>{
 const mutations=[d=>d.file_id++,d=>d.vehicle_id++,d=>d.raw='secret',d=>d.requires_confirmation=false,d=>d.review_status='AMBIGUOUS',d=>d.recognition.fields[0].key='title',d=>d.recognition.fields[0].value='2000001',d=>d.recognition.fields[0].value='001',d=>d.recognition.fields[0].confidence=null,d=>d.recognition.fields[0].confidence=0.5,d=>d.recognition.scene='vehicle',d=>d.recognition.fields.push({...d.recognition.fields[0]})]
 for(const mutate of mutations){const d=data();mutate(d);assert.throws(()=>mileageResponse(d,11,7))}
 assert.equal(mileageResponse(data('LOW_CONFIDENCE',0.2),11,7).fields[0].confidence,0.2)
 for(const status of ['AMBIGUOUS','NO_CANDIDATE']){const d=data(status);d.recognition={version:1,scene:'archive',status:'EMPTY',fields:[]};assert.equal(mileageResponse(d,11,7).status,'EMPTY')}
})
test('abort settles even without an abortable native task and ignores late callbacks',async()=>{
 let request;const api=createMileageRecognition({baseUrl:'https://api.example',runtime:()=>({request:o=>{request=o}})})
 const control=new AbortController(),p=api({...args(),signal:control.signal});control.abort();await assert.rejects(p)
 request.success({statusCode:200,data:{code:0,data:data()}})
})
test('errors and synchronous runtime failures never expose provider payloads',async()=>{
 for(const status of [401,404,409,429,503]){const c=client(),p=c.api(args());c.requests[0].success({statusCode:status,data:{message:'private provider detail'}});await assert.rejects(p,e=>!e.message.includes('private'))}
 const api=createMileageRecognition({baseUrl:'https://api.example',runtime:()=>{throw Error('secret')}});await assert.rejects(api(args()),e=>!e.message.includes('secret'))
})
for(const [status,confidence] of [['CANDIDATE',0.95],['LOW_CONFIDENCE',0.2]])test(`${status} requires manual review and acknowledgement; archive writes only on original save`,async()=>{
 const c=client(),form=initialArchiveState();Object.assign(form,{vehicleId:11,archiveType:1,title:'保养',recordedDate:'2026-10-24',fileIds:[7]})
 let saves=0;const archive=createArchiveFlow({state:form,token:()=>args().token,newKey:()=> 'test-key',api:{add:async()=>{saves++;return {archive_id:8,vehicle_id:11}}}})
 const state=initialCandidateState(),flow=createCandidateFlow({state,actor:()=>args().token,scope:()=> 'archive:11:1:7',scene:()=> 'archive',definitions:()=>candidateFields.archive,values:()=>form,apply:p=>Object.assign(form,p)})
 const p=flow.recognize(({signal})=>c.api({...args(),signal}));await Promise.resolve();c.requests[0].success({statusCode:200,data:{code:0,data:data(status,confidence)}});await p
 assert.equal(form.mileage,'');assert.equal(saves,0);assert.equal(flow.confirm(),false)
 flow.review();assert.equal(flow.confirm(),false);state.ack=true;assert.equal(flow.confirm(),true);assert.equal(form.mileage,'12345');assert.equal(saves,0)
 await archive.save();assert.equal(saves,1)
})
test('cancel or page hide aborts selected-image request and late results cannot populate candidates',async()=>{
 for(const action of ['cancel','suspend']){
  const c=client(),state=initialCandidateState(),form={mileage:''};let applied=0
  const flow=createCandidateFlow({state,actor:()=>args().token,scope:()=> 'archive:11:1:7',scene:()=> 'archive',definitions:()=>candidateFields.archive,values:()=>form,apply:()=>applied++})
  const p=flow.recognize(({signal})=>c.api({...args(),signal}));await Promise.resolve();flow[action]();await p
  c.requests[0].success({statusCode:200,data:{code:0,data:data()}});await Promise.resolve()
  assert.equal(c.aborted,1);assert.equal(state.phase,'closed');assert.equal(applied,0)
 }
})
