import test from 'node:test'
import assert from 'node:assert/strict'
import {watch} from 'vue'
import {assessmentMonthUtc,assessmentMonth,validAssessmentBody,validAssessmentPolicy,createAssessmentPolicyApi} from '../src/services/assessment-policies.js'
import {createAssessmentPolicyRecovery} from '../src/services/assessment-policy-recovery.js'
import {createPointPolicyRecovery} from '../src/services/point-policy-recovery.js'
import {validOperatorUser} from '../src/services/experience-publication.js'
import {operatorSession,setOperatorSession,clearOperatorSession,operatorLandingPage} from '../src/services/operator-session.js'
import {serviceLoginReturn} from '../src/services/service-login-return.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../src/services/async-diagnosis.js'
import {ServiceError} from '../src/services/service-catalog.js'
const key='11111111-1111-4111-8111-111111111111',key2='22222222-2222-4222-8222-222222222222'
const body=()=>({merchant_id:1,acquisition_target:10,coupon_target:20,process_requirements:['CHECK_IN_CONFIRMED','QUALITY_SIGNED'],grade_thresholds:[{code:'A',label:'合成高档',min_score:'80.00'},{code:'B',label:'合成基础',min_score:'0.00'}],source_note:'合成测试规则来源',valid_from:'2026-10-31T16:00:00Z',valid_to:'2026-12-31T16:00:00Z'})
const row=(extra={})=>({id:1,...body(),merchant:{id:1,name:'合成门店',address:'合成地址'},revision:1,status:'DRAFT',rule_version:'R8B_POLICY_V1',scope:'MERCHANT',period:'BEIJING_MONTH',timezone:'Asia/Shanghai',weights:{acquisition:40,coupon:40,process:20},metric_source_version:'R8B_FACTS_V1',formula:'TARGET_RATE_AND_PROCESS_COMPLETENESS_V1',created_at:'2026-10-16T00:00:00Z',published_at:null,...extra})
const page=items=>({items,total:items.length,page:1,page_size:20})
const pending=()=>({action:'create',id:0,body:body(),key})
const memory=()=>{const values=new Map();return {values,getStorageSync:k=>values.get(k),setStorageSync:(k,v)=>values.set(k,JSON.parse(JSON.stringify(v))),removeStorageSync:k=>values.delete(k)}}
test('assessment targets evidence grades and fixed source formula are exact without example score defaults',()=>{
 assert.equal(validAssessmentPolicy(row()),true)
 for(const fields of [{acquisition_target:0},{coupon_target:1.5},{merchant_id:2},{weights:{acquisition:50,coupon:30,process:20}},{source_note:' x '},{period:'UTC_MONTH'},{total_score:0},{scoring_available:true}])assert.equal(validAssessmentPolicy(row(fields)),false,JSON.stringify(fields))
 for(const grade_thresholds of [[{code:'A',label:'甲',min_score:'100.01'},{code:'B',label:'乙',min_score:'0.00'}],[{code:'A',label:'甲',min_score:'0.00'},{code:'B',label:'乙',min_score:'80.00'}],[{code:'A',label:'甲',min_score:'80.00'},{code:'A',label:'乙',min_score:'0.00'}],[{code:'A',label:'甲',min_score:'80.00'},{code:'B',label:'乙',min_score:'0.01'}]] )assert.equal(validAssessmentBody({...body(),grade_thresholds},'create'),false)
 assert.equal(validAssessmentBody({...body(),process_requirements:[]},'create'),false);assert.equal(validAssessmentBody({...body(),process_requirements:['QUALITY_SIGNED','QUALITY_SIGNED']},'create'),false);assert.equal(validAssessmentBody({...body(),weights:{}},'create'),false)
})
test('policy windows are complete Beijing months including year and leap-day boundaries',()=>{
 assert.equal(assessmentMonthUtc('2026-01'),'2025-12-31T16:00:00Z');assert.equal(assessmentMonthUtc('2028-03'),'2028-02-29T16:00:00Z');assert.equal(assessmentMonth('2025-12-31T16:00:00Z'),'2026-01');assert.equal(assessmentMonthUtc('2026-13'),'')
 assert.equal(validAssessmentBody({...body(),valid_from:'2026-11-01T00:00:00Z'},'create'),false);assert.equal(validAssessmentBody({...body(),valid_from:'2026-10-31T16:00:01Z'},'create'),false)
})
test('assessment API binds original request and permits only real paged merchant and policy shapes',async()=>{
 const calls=[];let data=row();const api=createAssessmentPolicyApi({baseUrl:'https://example.test',runtime:()=>({request:o=>{calls.push(o);o.success({statusCode:200,data:{code:0,data}})}})})
 await api.write('token','create',0,body(),key);assert.equal(calls[0].header['Idempotency-Key'],key);data=page([row()]);await api.list('token');data=page([row().merchant]);await api.merchants('token');assert.match(calls.at(-1).url,/assessment\/merchants\?page=1&page_size=20$/)
 data={...page([row().merchant]),total:2};await assert.rejects(api.merchants('token'),e=>e.kind==='protocol');data=row({coupon_target:21});await assert.rejects(api.write('token','create',0,body(),key),e=>e.kind==='protocol');assert.throws(()=>api.write('token','create',0,{...body(),acquisition_target:0},key));assert.throws(()=>api.list('token',0))
})
test('nested grade object key order does not reject an equivalent first creation receipt',async()=>{
 const response=row();response.grade_thresholds=response.grade_thresholds.map(g=>({min_score:g.min_score,label:g.label,code:g.code}));const api=createAssessmentPolicyApi({baseUrl:'https://example.test',runtime:()=>({request:o=>o.success({statusCode:200,data:{code:0,data:response}})})});assert.deepEqual(await api.write('token','create',0,body(),key),response)
})
test('assessment capability is exact independent and ready before new token observers',()=>{
 const old={id:31,role:'operator',can_review:false,can_onboard:false,can_catalog:false,can_coupon:false,can_invite:false,can_diagnose:false,can_diagnose_manage:false,can_points:false};assert.equal(validOperatorUser(old),true);const user={...old,can_assessment:true};assert.equal(validOperatorUser(user),true);assert.equal(validOperatorUser({...user,can_assessment:'true'}),false);assert.equal(validOperatorUser({...old,can_assess:true}),false)
 clearOperatorSession();let observed;const stop=watch(()=>operatorSession.accessToken,t=>{if(t)observed=[operatorSession.actorId,operatorSession.canAssessment,operatorSession.canPoints]},{flush:'sync'});setOperatorSession({access_token:'synthetic-assessment',expires_in:900,user});assert.deepEqual(observed,[31,true,false]);assert.equal(operatorLandingPage(),'/pages/operator/assessment-policies');stop();assert.equal(serviceLoginReturn([{route:'pages/operator/assessment-policies'},{}],'admin',{canAssessment:true}),true);assert.equal(serviceLoginReturn([{route:'pages/operator/assessment-policies'},{}],'admin',{canPoints:true}),false);assert.equal(serviceLoginReturn([{route:'pages/operator/assessment-policies',options:{merchant_id:'2'}},{}],'admin',{canAssessment:true}),false);clearOperatorSession();assert.equal(operatorSession.canAssessment,false)
})
test('permanent assessment intents isolate account and point policy storage and cannot be overwritten',()=>{
 let actor=1;const storage=memory(),store=createAssessmentPolicyRecovery({runtime:()=>storage,actor:()=>actor}),points=createPointPolicyRecovery({runtime:()=>storage,actor:()=>actor}),p=pending();store.savePending(p);p.body.grade_thresholds[0].min_score='1.00';assert.equal(store.read().pending.body.grade_thresholds[0].min_score,'80.00');assert.equal(points.read().pending,null);actor=2;assert.equal(store.read().pending,null);actor=1;assert.throws(()=>store.savePending({...pending(),key:key2}));assert.throws(()=>store.complete({...pending(),key:key2}));const marker=store.marker();store.complete(pending());store.savePending({...pending(),key:key2});assert.throws(()=>store.discard(marker))
})
test('after an unknown create a rebuilt flow reads server before replaying the exact original policy',async()=>{
 const storage=memory(),store=createAssessmentPolicyRecovery({runtime:()=>storage,actor:()=>1}),calls=[];let first=true;const build=()=>{const state=initialDiagnosisState();return {state,flow:createAsyncDiagnosisFlow({state,recovery:store,token:()=> 'token',scope:()=> 'assessment',newKey:()=>key,confirm:async()=>true,load:async()=>page([row()]),write:async(t,a,id,b,k)=>{calls.push([JSON.parse(JSON.stringify(b)),k]);if(first){first=false;throw new ServiceError('unavailable','unknown')}return row()}})}};let h=build();await h.flow.load();await h.flow.save('create',0,body());assert.equal(h.state.retry,true);h.flow.reset();h=build();await h.flow.retry();assert.equal(calls.length,1);await h.flow.load();await h.flow.retry();assert.deepEqual(calls,[[body(),key],[body(),key]]);assert.equal(store.read().pending,null)
})
test('storage failure blocks policy write and delayed success cannot clear another operator intent',async()=>{
 let actor=1,token='one',resolve;const storage=memory(),store=createAssessmentPolicyRecovery({runtime:()=>storage,actor:()=>actor}),state=initialDiagnosisState();let writes=0;const flow=createAsyncDiagnosisFlow({state,recovery:store,token:()=>token,scope:()=>String(actor),newKey:()=>key,confirm:async()=>true,load:async()=>page([]),write:()=>{writes++;return new Promise(r=>{resolve=r})}});await flow.load();storage.setStorageSync=()=>{throw Error('quota')};await flow.save('create',0,body());assert.equal(writes,0);storage.setStorageSync=(k,v)=>storage.values.set(k,JSON.parse(JSON.stringify(v)));await flow.load();const writing=flow.save('create',0,body());await new Promise(r=>setTimeout(r,0));actor=2;token='two';flow.reset();resolve(row());await writing;assert.equal(state.data,null);actor=1;assert.deepEqual(store.read().pending,pending())
})
