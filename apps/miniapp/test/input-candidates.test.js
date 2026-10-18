import test from 'node:test'
import assert from 'node:assert/strict'
import {candidateFields,normalizeCandidate,recognitionCandidates,createCandidateFlow,initialCandidateState} from '../src/services/input-candidates.js'
import {createArchiveFlow,initialArchiveState} from '../src/services/archive-flow.js'
import {createVehicleFlow,initialVehicleState} from '../src/services/vehicle-flow.js'
import {createStructuredFlow,initialStructuredState} from '../src/services/archive-structured-flow.js'
import {createAiChatFlow,initialAiChatState} from '../src/services/ai-chat-flow.js'
const defer=()=>{let resolve,reject;const promise=new Promise((r,j)=>{resolve=r;reject=j});return {promise,resolve,reject}}
function harness({scene='archive',form={title:'原标题',notes:'',mileage:'',recordedDate:'2026-10-18'},apply}={}){
 let actor='owner-a',scope='archive:11:1',allowed=true;const state=initialCandidateState(),patches=[],timers=[]
 const flow=createCandidateFlow({state,actor:()=>actor,scope:()=>scope,scene:()=>scene,definitions:()=>candidateFields[scene],values:()=>form,allowed:()=>allowed,apply:p=>{patches.push(p);Object.assign(form,p);apply?.(p)},schedule:fn=>{timers.push(fn);return timers.length},cancelTimer:()=>{}})
 return {state,flow,form,patches,timers,setActor:v=>actor=v,setScope:v=>scope=v,setAllowed:v=>allowed=v}
}
function prepare(h,key='title',value='新标题'){h.flow.begin();h.flow.edit(key,value);h.flow.review()}
const result=(fields=[{key:'title',value:'识别候选',confidence:0.2}])=>({version:1,scene:'archive',status:'CANDIDATE',fields})
test('manual candidate does not alter form before review and explicit acknowledgement',()=>{
 const h=harness();prepare(h);assert.equal(h.form.title,'原标题');assert.equal(h.state.source,'MANUAL');assert.equal(h.flow.confirm(),false)
 h.state.ack=true;assert.equal(h.flow.confirm(),true);assert.equal(h.form.title,'新标题');assert.equal(h.flow.confirm(),false);assert.equal(h.patches.length,1);assert.equal(Object.isFrozen(h.patches[0]),true)
})
test('edits invalidate acknowledgement and require review again; blank values retain existing fields',()=>{
 const h=harness();prepare(h);h.state.ack=true;h.flow.edit('title','修改后');assert.equal(h.state.ack,false);assert.equal(h.flow.confirm(),false);h.flow.review();h.state.ack=true;h.flow.confirm();assert.equal(h.form.title,'修改后');assert.equal(h.form.recordedDate,'2026-10-18')
})
test('field errors never partially apply and no truncation is hidden',()=>{
 const h=harness();h.flow.begin();h.flow.edit('title','候选');h.flow.edit('mileage','2147483648');h.flow.review();assert.equal(h.state.phase,'editing');assert.equal(h.patches.length,0);assert.equal(h.form.title,'原标题')
 for(const [def,value] of [[candidateFields.archive[0],'字'.repeat(81)],[candidateFields.archive[2],'-1'],[candidateFields.archive[3],'2026-02-30'],[candidateFields.archive[0],'title\nnext']])assert.throws(()=>normalizeCandidate(def,value))
 assert.equal(normalizeCandidate(candidateFields.archive[1],'第一行\n第二行'),'第一行\n第二行')
})
test('VIN, plate, mileage and date normalize against actual form restrictions',()=>{
 assert.equal(normalizeCandidate(candidateFields.vehicle[1],'lsgud82c8ce064356'),'LSGUD82C8CE064356');assert.equal(normalizeCandidate(candidateFields.vehicle[0],'粤b12345'),'粤B12345')
 assert.throws(()=>normalizeCandidate(candidateFields.vehicle[1],'LSGUD82C8CE06435I'));assert.equal(normalizeCandidate(candidateFields.vehicle[2],'0'),'0')
})
test('actor, scope, hide, cancel and locked form fence manual confirmations',()=>{
 for(const action of ['actor','scope','hide','cancel','lock']){const h=harness();prepare(h);h.state.ack=true;if(action==='actor')h.setActor('owner-b');if(action==='scope')h.setScope('archive:12:1');if(action==='hide')h.flow.suspend();if(action==='cancel')h.flow.cancel();if(action==='lock')h.setAllowed(false);assert.equal(h.flow.confirm(),false);assert.equal(h.patches.length,0)}
})
test('concurrent form changes require a fresh candidate session',()=>{
 const h=harness();prepare(h);h.form.title='用户后来填写';h.state.ack=true;assert.equal(h.flow.confirm(),false);assert.match(h.state.message,/原表单已变化/);assert.equal(h.form.title,'用户后来填写')
})
test('default adapter is unavailable, never manufactures results or applies data',async()=>{
 const h=harness();await h.flow.recognize();assert.equal(h.state.phase,'editing');assert.equal(h.state.source,'MANUAL');assert.match(h.state.message,/尚未配置/);assert.equal(h.patches.length,0)
})
test('low/unknown confidence provider candidates still require manual review and never auto-write',async()=>{
 for(const confidence of [0,0.2,null,1]){const h=harness();await h.flow.recognize(async()=>result([{key:'title',value:'候选',confidence}]));assert.equal(h.state.source,'PROVIDER');assert.equal(h.form.title,'原标题');assert.equal(h.flow.confirm(),false);h.flow.review();assert.equal(h.state.rows[0].confidence,confidence);h.state.ack=true;h.flow.confirm();assert.equal(h.form.title,'候选')}
})
test('contract rejects mismatched scene/version, unknown/duplicate fields and malformed confidence',()=>{
 const invalid=[{...result(),scene:'vehicle'},{...result(),version:2},{...result(),extra:'private'},result([{key:'file_id',value:'1',confidence:1}]),result([{key:'title',value:'',confidence:1}]),result([{key:'title',value:'x',confidence:2}]),result([{key:'title',value:'x',confidence:'high'}]),result([{key:'title',value:'x',confidence:1},{key:'title',value:'y',confidence:1}]),{...result(),status:'EMPTY'}]
 for(const value of invalid)assert.throws(()=>recognitionCandidates(value,'archive',candidateFields.archive))
 assert.deepEqual(recognitionCandidates({version:1,scene:'archive',status:'EMPTY',fields:[]},'archive',candidateFields.archive),[])
})
test('empty, invalid and rejected adapters safely return to manual fallback without leaking errors',async()=>{
 for(const adapter of [async()=>({version:1,scene:'archive',status:'EMPTY',fields:[]}),async()=>({secret:'private'}),async()=>{throw new Error('secret provider URL')}]){const h=harness();await h.flow.recognize(adapter);assert.equal(h.state.phase,'editing');assert.equal(h.patches.length,0);assert.doesNotMatch(h.state.message,/secret/)}
})
test('timeout aborts adapter and ignores its late result',async()=>{
 const h=harness(),pending=defer();let signal;const run=h.flow.recognize(o=>{signal=o.signal;return pending.promise});await Promise.resolve();h.timers[0]();await run;assert.equal(signal.aborted,true);assert.match(h.state.message,/超时/);pending.resolve(result());await Promise.resolve();assert.equal(h.form.title,'原标题');assert.equal(h.state.phase,'editing')
})
test('cancel, identity and scope changes ignore late async responses and settle cancellation',async()=>{
 for(const action of ['cancel','hide','actor','scope']){const h=harness(),pending=defer();const run=h.flow.recognize(()=>pending.promise);await Promise.resolve();if(action==='cancel')h.flow.cancel();if(action==='hide')h.flow.suspend();if(action==='actor')h.setActor('other');if(action==='scope')h.setScope('archive:22:1');pending.resolve(result());await run;assert.equal(h.patches.length,0);assert.equal(h.form.title,'原标题')}
})
test('cancel-before-dispatch does not invoke adapter and old timeout cannot abort a new run',async()=>{
 const h=harness();let calls=0;const old=h.flow.recognize(()=>{calls++;return result()});h.flow.cancel();await old;assert.equal(calls,0)
 const a=defer(),b=defer();let second;const first=h.flow.recognize(()=>a.promise);await Promise.resolve();h.flow.cancel();await first;const next=h.flow.recognize(o=>{second=o.signal;return b.promise});await Promise.resolve();h.timers[1]();assert.equal(second.aborted,false);b.resolve(result());await next
})
test('confirmed archive candidates use the actual existing save flow and retry key without source/media fields',async()=>{
 const form=Object.assign(initialArchiveState(),{vehicleId:11,recordedDate:'2026-10-18'}),calls=[];let fail=true
 const save=createArchiveFlow({state:form,token:()=>'owner',newKey:()=> 'same-key',api:{add:async(t,body,key)=>{calls.push({body,key});if(fail)throw new Error();return {archive_id:9}}}})
 const h=harness({form});prepare(h,'title','确认的档案');assert.equal(calls.length,0);h.state.ack=true;h.flow.confirm();assert.equal(calls.length,0);await save.save();fail=false;await save.save();assert.equal(calls.length,2);assert.deepEqual(calls[0],calls[1]);assert.equal(calls[0].body.title,'确认的档案');assert.equal(calls[0].body.input_type,undefined);assert.equal(calls[0].body.source,undefined);assert.equal(calls[0].body.material_id,undefined);assert.equal(form.saved.archive_id,9)
})
test('vehicle candidate confirmation preserves user-selected model and uses actual manual save contract',async()=>{
 const form=Object.assign(initialVehicleState(),{modelId:3}),calls=[];const h=harness({scene:'vehicle',form});prepare(h,'mileage','32500');h.state.ack=true;h.flow.confirm();const save=createVehicleFlow({state:form,token:()=>'owner',newKey:()=>'key',api:{add:async(t,b)=>{calls.push(b);return {vehicle_id:4}}}});await save.save();assert.equal(calls[0].model_id,3);assert.equal(calls[0].current_mileage,32500);assert.equal(calls[0].add_type,4)
})
test('license candidates retain manual confirmation and revision checks in the actual structured save flow',async()=>{
 const context={vehicle_id:11,archive_id:8,archive_type:8,recorded_date:'2026-10-18',revision:2,fields:{},status:'NOT_SET'},state=initialStructuredState(),calls=[]
 const original=createStructuredFlow({state,token:()=>'owner',newKey:()=>'original-key',confirmClear:async()=>false,api:{context:async()=>context,save:async(t,c,b,k)=>calls.push({body:b,key:k})}})
 original.route(8,11);await original.resume()
 const candidate=initialCandidateState();const flow=createCandidateFlow({state:candidate,actor:()=>'owner',scope:()=>'license:11:8:2',scene:()=>'license',definitions:()=>[{key:'vin',kind:'vin',max:17}],values:()=>state.form,allowed:()=>!state.busy,apply:patch=>Object.assign(state.form,patch,{owner_confirmed:false})})
 flow.begin();flow.edit('vin','LSGUD82C8CE064356');flow.review();candidate.ack=true;flow.confirm();assert.equal(calls.length,0);assert.equal(state.form.owner_confirmed,false)
 await original.save(false);assert.equal(calls.length,1);assert.equal(calls[0].body.expected_revision,2);assert.equal(calls[0].body.fields.vin,'LSGUD82C8CE064356');assert.equal(calls[0].body.fields.owner_confirmed,undefined)
})
test('voice candidate only changes draft; actual text AI send remains explicit and has no media payload',async()=>{
 const form={question:''},h=harness({scene:'voice',form}),calls=[];prepare(h,'question','刹车有异响');h.state.ack=true;h.flow.confirm();assert.equal(calls.length,0)
 const chat=createAiChatFlow({state:initialAiChatState(),token:()=>'owner',vehicleId:()=>11,api:{chat:async(t,b)=>{calls.push(b);return {reply:'请检查',grounded:true}}}});await chat.send(form.question)
 assert.deepEqual(calls[0],{message:'刹车有异响',vehicleId:11,history:[]})
})
