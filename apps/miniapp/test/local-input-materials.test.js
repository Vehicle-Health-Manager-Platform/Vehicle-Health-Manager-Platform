import test from 'node:test'
import assert from 'node:assert/strict'
import {createLocalMaterialsFlow,initialLocalMaterialsState,RECORD_OPTIONS,LOCAL_MATERIAL_LIMITS} from '../src/services/local-input-materials.js'

function manager(){
 const handlers={Start:new Set(),Stop:new Set(),Error:new Set()},calls=[]
 const m={calls,handlers,start:o=>calls.push(['start',o]),stop:()=>calls.push(['stop'])}
 for(const kind of Object.keys(handlers)){m['on'+kind]=fn=>handlers[kind].add(fn);m['off'+kind]=fn=>handlers[kind].delete(fn)}
 m.emit=(kind,value)=>Promise.all([...handlers[kind]].map(fn=>fn(value)))
 return m
}
function harness(extra={}){
 const state=initialLocalMaterialsState(),record=extra.manager||manager(),calls=[],timers=[],audio=[]
 let actor='owner-one',scope='archive:1:1',time=0
 const native={
  supports:()=>true,getRecorderManager:()=>record,
  chooseMedia:o=>calls.push(['image',o]),chooseImage:o=>calls.push(['legacy-image',o]),chooseMessageFile:o=>calls.push(['pdf',o]),
  getFileSystemManager:()=>({getFileInfo:o=>calls.push(['size',o])}),
  createInnerAudioContext:()=>{const events={};const a={events,stops:0,destroys:0,play(){},stop(){this.stops++},destroy(){this.destroys++},onPlay:f=>events.play=f,onEnded:f=>events.ended=f,onError:f=>events.error=f};audio.push(a);return a},
  previewImage:o=>calls.push(['preview',o]),openDocument:o=>calls.push(['document',o]),openSetting:o=>calls.push(['setting',o]),
  request:()=>assert.fail('local capture must never use the network'),uploadFile:()=>assert.fail('must not upload'),
  ...extra.runtime,
 }
 const flow=createLocalMaterialsFlow({state,runtime:()=>native,actor:()=>actor,scope:()=>scope,now:()=>time,schedule:fn=>{timers.push(fn);return timers.length},cancelTimer:()=>{}})
 return {state,flow,record,calls,native,audio,timers,setActor:v=>{actor=v},setScope:v=>{scope=v},setTime:v=>{time=v}}
}
const image=(path='/tmp/picture.png',size=1024)=>({tempFiles:[{tempFilePath:path,size}]})
const pdf=(name='保养记录.pdf',size=1024)=>({tempFiles:[{path:'/tmp/document.pdf',name,size}]})
const recording={tempFilePath:'/tmp/voice.mp3',duration:1500,fileSize:4096}
async function choose(h,kind='image',result){
 const work=h.flow.select(kind);h.calls.at(-1)[1].success(result || (kind==='pdf'?pdf():image()));await work
}
async function recordOne(h){h.flow.startRecording();await h.record.emit('Start');h.flow.stopRecording();await h.record.emit('Stop',recording)}

test('local capture needs an actor but no backend configuration; cancellation is quiet',async()=>{
 const h=harness();h.setActor('');await h.flow.select('image');assert.equal(h.calls.length,0);assert.equal(h.state.kind,'unauthorized')
 h.setActor('owner');const work=h.flow.select('pdf');h.calls.at(-1)[1].fail({errMsg:'chooseMessageFile:fail cancel'});await work
 assert.equal(h.state.message,'');assert.equal(h.state.items.length,0)
 await choose(h);assert.equal(h.state.items[0].kind,'image');assert.equal(h.state.phase,'idle')
})
test('PDF and size rules reject unsupported, remote, oversized and malformed local candidates',async()=>{
 for(const result of [pdf('file.exe'),pdf('../file.pdf'),pdf('file.pdf',10*1024*1024+1),pdf('bad\n.pdf')]){
  const h=harness();await choose(h,'pdf',result);assert.equal(h.state.items.length,0);assert.equal(h.state.kind,'invalid')
 }
 const remote=harness();await choose(remote,'image',image('https://example.com/private.png'));assert.equal(remote.state.items.length,0)
 for(const path of ['//example.com/x.png','ftp://example.com/x.png','file://server/share/file.png']){const h=harness();await choose(h,'image',image(path));assert.equal(h.state.items.length,0)}
 const missing=harness({runtime:{supports:()=>false}});await missing.flow.select('pdf');assert.equal(missing.state.kind,'unsupported');assert.equal(missing.calls.length,0)
 const h=harness();await choose(h,'pdf');assert.equal(h.state.items[0].label,'保养记录.pdf')
})
test('missing reported size uses the native file information, and late results cannot restore another scope',async()=>{
 const h=harness();const work=h.flow.select('image');h.calls[0][1].success(image('/tmp/file.png',0));await Promise.resolve()
 assert.equal(h.calls.at(-1)[0],'size')
 h.setScope('archive:2:1');h.flow.reset();h.calls.at(-1)[1].success({size:1000});await work;assert.deepEqual(h.state.items,[])
})
test('three-material limit, removal and native image fallback work without persistence or upload',async()=>{
 const h=harness({runtime:{supports:n=>n!=='chooseMedia'}})
 for(let i=0;i<3;i++)await choose(h)
 assert.equal(h.calls[0][0],'legacy-image');assert.equal(h.state.items.length,3)
 const n=h.calls.length;await h.flow.select('pdf');assert.equal(h.calls.length,n);assert.equal(h.state.kind,'limit')
 h.flow.remove(h.state.items[0].id);await choose(h,'pdf');assert.equal(h.state.items.length,3)
})
test('picker-induced hide preserves only the same context and accepts the result when shown',async()=>{
 const h=harness();await choose(h)
 const first=h.state.items[0],work=h.flow.select('pdf');h.flow.suspend();assert.deepEqual(h.state.items,[])
 h.calls.at(-1)[1].success(pdf());await work;assert.deepEqual(h.state.items,[])
 h.flow.resume();assert.equal(h.state.items.length,2);assert.equal(h.state.items[0].id,first.id)
 const old=h.flow.select('image');h.flow.suspend();h.setActor('new-owner');h.flow.reset();h.calls.at(-1)[1].success(image());await old;h.flow.resume();assert.deepEqual(h.state.items,[])
})
test('unload discards pending selectors; permission errors are fixed safe messages',async()=>{
 const h=harness();const pending=h.flow.select('pdf');h.flow.dispose();h.calls[0][1].success(pdf());await pending;h.flow.resume();assert.deepEqual(h.state.items,[])
 const denied=harness();const work=denied.flow.select('image');denied.calls[0][1].fail({errMsg:'permission denied /private/path'});await work
 assert.equal(denied.state.kind,'permission');assert.doesNotMatch(denied.state.message,/private/)
})
test('recording starts only after native acknowledgement, uses fixed options and stops with a verified local candidate',async()=>{
 const h=harness();h.flow.startRecording();assert.equal(h.state.phase,'requesting');assert.deepEqual(h.record.calls[0],['start',RECORD_OPTIONS])
 await h.record.emit('Start');assert.equal(h.state.phase,'recording');h.setTime(2500);h.timers[0]();assert.equal(h.state.seconds,2)
 h.flow.stopRecording();assert.equal(h.state.phase,'stopping');await h.record.emit('Stop',recording)
 assert.equal(h.state.phase,'idle');assert.equal(h.state.items[0].kind,'audio');assert.equal(h.state.items[0].durationMs,1500)
 assert.ok(Object.values(h.record.handlers).every(s=>s.size===0))
})
test('recording rejects too short, too long, remote and oversized native results',async()=>{
 for(const patch of [{duration:999},{duration:60001},{fileSize:LOCAL_MATERIAL_LIMITS.audioBytes+1},{tempFilePath:'https://example.com/voice.mp3'}]){
  const h=harness();h.flow.startRecording();await h.record.emit('Start');await h.record.emit('Stop',{...recording,...patch})
  assert.equal(h.state.items.length,0);assert.equal(h.state.kind,'invalid');assert.equal(h.state.phase,'idle')
 }
})
test('recording auto-stops at 60 seconds and missing native capabilities degrade without throwing',async()=>{
 const h=harness();h.flow.startRecording();await h.record.emit('Start');h.setTime(60000);h.timers[0]();assert.equal(h.state.phase,'stopping');assert.equal(h.record.calls.at(-1)[0],'stop')
 await h.record.emit('Stop',{...recording,duration:60000});assert.equal(h.state.items.length,1)
 const no=harness({runtime:{getRecorderManager:()=>undefined}});no.flow.startRecording();assert.equal(no.state.kind,'unsupported')
})
test('singleton recorder remains leased until cancelled old operation receives its final event',async()=>{
 const shared=manager(),a=harness({manager:shared}),b=harness({manager:shared})
 a.flow.startRecording();await shared.emit('Start');a.flow.suspend();b.flow.startRecording();assert.equal(b.state.kind,'busy')
 await shared.emit('Stop',recording);assert.equal(a.state.items.length,0)
 b.flow.startRecording();await shared.emit('Start');b.flow.stopRecording();await shared.emit('Stop',recording)
 assert.equal(b.state.items.length,1);assert.equal(a.state.items.length,0)
})
test('cancelled permission prompt stops a late native start, and stale terminal callbacks do not replace the next recording',async()=>{
 const h=harness();h.flow.startRecording();const oldStop=[...h.record.handlers.Stop][0]
 h.flow.cancel();await h.record.emit('Start');assert.equal(h.record.calls.at(-1)[0],'stop')
 await h.record.emit('Stop',recording);assert.equal(h.state.items.length,0)
 h.flow.startRecording();await h.record.emit('Start');await oldStop(recording);assert.equal(h.state.phase,'recording')
 await h.record.emit('Stop',recording);assert.equal(h.state.items.length,1)
})
test('duplicate queued recorder terminal callbacks cannot add the same audio twice',async()=>{
 const h=harness();h.flow.startRecording();await h.record.emit('Start')
 const stop=[...h.record.handlers.Stop][0],error=[...h.record.handlers.Error][0]
 await stop(recording);await stop(recording);error({errMsg:'late error'})
 assert.equal(h.state.items.length,1);assert.equal(h.state.phase,'idle');assert.equal(h.state.message,'')
})
test('a synchronous native start failure invalidates callbacks already queued by that attempt',async()=>{
 const m=manager();let lateStart,lateStop
 m.start=()=>{lateStart=[...m.handlers.Start][0];lateStop=[...m.handlers.Stop][0];throw Error('native start rejected')}
 const h=harness({manager:m});h.flow.startRecording();lateStart();await lateStop(recording)
 assert.equal(h.state.phase,'idle');assert.equal(h.state.items.length,0);assert.equal(h.state.kind,'native')
 assert.ok(Object.values(m.handlers).every(s=>s.size===0))
})
test('late recorded-size response and record errors cannot cross account changes',async()=>{
 const h=harness();h.flow.startRecording();await h.record.emit('Start');const finished=h.record.emit('Stop',{...recording,fileSize:0})
 h.setActor('another-owner');h.flow.reset();h.calls.at(-1)[1].success({size:1000});await finished;assert.deepEqual(h.state.items,[])
 const denied=harness();denied.flow.startRecording();await denied.record.emit('Error',{errMsg:'auth denied: private'});assert.equal(denied.state.kind,'permission');assert.ok(Object.values(denied.record.handlers).every(s=>s.size===0))
})
test('audio plays local candidates only and is destroyed on hide, remove or late callbacks',async()=>{
 const h=harness();await recordOne(h);const item=h.state.items[0]
 h.flow.play(item.id);const a=h.audio[0];assert.equal(a.src,recording.tempFilePath);assert.equal(h.state.phase,'playing')
 h.flow.remove(item.id);assert.equal(a.destroys,1);a.events.ended();assert.equal(h.state.phase,'idle');assert.equal(h.state.items.length,0)
 await recordOne(h);h.flow.play(h.state.items[0].id);const b=h.audio.at(-1);h.flow.suspend();assert.equal(b.destroys,1);b.events.play();assert.deepEqual(h.state.items,[])
})
test('PDF opens only a selected local file and stale preview errors cannot update a changed scope',async()=>{
 const h=harness();await choose(h,'pdf');h.flow.preview(h.state.items[0].id);assert.equal(h.calls.at(-1)[0],'document');assert.equal(h.calls.at(-1)[1].showMenu,false)
 const fail=h.calls.at(-1)[1].fail;h.setScope('new-scope');h.flow.reset();fail();assert.equal(h.state.message,'')
})
