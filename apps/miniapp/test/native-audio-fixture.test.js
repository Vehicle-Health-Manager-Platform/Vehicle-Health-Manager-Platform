import test from 'node:test'
import assert from 'node:assert/strict'
import {installNativeAudioFixture} from '../../../scripts/native-audio-fixture.mjs'
import {createLocalMaterialsFlow,initialLocalMaterialsState,RECORD_OPTIONS} from '../src/services/local-input-materials.js'

const file = {path:'http://tmp/synthetic-recording.mp3',size:8192,durationMs:2000}
function harness() {
  const contexts = []
  const originalRecorder = () => assert.fail('physical recorder must not run')
  function originalPlayer() {
    assert.equal(this,runtime)
    const handlers = {Play:new Set(),Ended:new Set(),Error:new Set()}
    const audio = {plays:0,stops:0,destroys:0,src:'',autoplay:true,
      play(){this.plays++},stop(){this.stops++},destroy(){this.destroys++},
      emit(name,value){for (const fn of [...handlers[name]]) fn(value)},handlers}
    for (const name of Object.keys(handlers)) {
      audio['on'+name] = fn => handlers[name].add(fn)
      audio['off'+name] = fn => handlers[name].delete(fn)
    }
    contexts.push(audio);return audio
  }
  const runtime = {getRecorderManager:originalRecorder,createInnerAudioContext:originalPlayer}
  return {runtime,contexts,originalRecorder,originalPlayer}
}

test('audio fixture rejects remote, saved, traversal, escaped and malformed file metadata before mutation',()=>{
  const h = harness()
  const invalidPaths = ['', 'https://example.com/private.mp3','http://tmp.evil/file.mp3','http://tmp@evil/file.mp3','file:///tmp/file.mp3','wxfile://usr/file.mp3','http://tmp/../secret.mp3','http://tmp/%2e%2e/secret.mp3','http://tmp/file.mp3?token=private','http://tmp/file\n.mp3','http://tmp/file\\name.mp3','http://tmp/']
  for (const path of invalidPaths) assert.throws(()=>installNativeAudioFixture({runtime:h.runtime,file:{...file,path}}),/Invalid native audio fixture/)
  for (const patch of [{size:0},{size:3*1024*1024+1},{size:1.5},{durationMs:999},{durationMs:60001},{durationMs:1000.5}]) {
    assert.throws(()=>installNativeAudioFixture({runtime:h.runtime,file:{...file,...patch}}),/Invalid native audio fixture/)
  }
  assert.equal(h.runtime.getRecorderManager,h.originalRecorder)
  assert.equal(h.runtime.createInnerAudioContext,h.originalPlayer)
})

test('synthetic recorder is singleton, validates fixed encoding and supports deduplicated event subscriptions',()=>{
  const h = harness(),fixture = installNativeAudioFixture({runtime:h.runtime,file}),recorder = h.runtime.getRecorderManager()
  assert.equal(h.runtime.getRecorderManager(),recorder)
  for (const [key,value] of Object.entries(RECORD_OPTIONS)) {
    assert.throws(()=>recorder.start({...RECORD_OPTIONS,[key]:typeof value === 'number'?value+1:'wav'}),/Invalid native recording options/)
  }
  const events = [],onStart = () => events.push('start'),onStop = value => events.push(value),onError = () => assert.fail('must not synthesize errors')
  recorder.onStart(onStart);recorder.onStart(onStart);recorder.onStop(onStop);recorder.onError(onError)
  assert.equal(fixture.status().recorderListeners,3)
  recorder.start({...RECORD_OPTIONS});assert.equal(fixture.status().recording,true)
  recorder.stop();recorder.stop()
  assert.deepEqual(events,['start',{tempFilePath:file.path,fileSize:file.size,duration:file.durationMs}])
  assert.equal(fixture.status().recorderStarts,1);assert.equal(fixture.status().recorderStops,1)
  recorder.offStart(onStart);recorder.offStop(onStop);recorder.offError(onError)
  assert.equal(fixture.status().recorderListeners,0);assert.equal(fixture.status().recording,false)
  fixture.restore()
})

test('real local-material flow cancels synthetic recording without a candidate and releases all recorder listeners',async()=>{
  const h = harness(),fixture = installNativeAudioFixture({runtime:h.runtime,file})
  const state = initialLocalMaterialsState(),flow = createLocalMaterialsFlow({state,runtime:()=>h.runtime,actor:()=> 'synthetic-owner',scope:()=> 'archive:991',schedule:()=>1,cancelTimer:()=>{}})
  flow.startRecording();assert.equal(state.phase,'recording');flow.cancel();await Promise.resolve()
  assert.equal(state.items.length,0);assert.equal(state.phase,'idle');assert.equal(fixture.status().recorderListeners,0)
  flow.startRecording();flow.stopRecording();await Promise.resolve();await Promise.resolve()
  assert.equal(state.items.length,1);assert.equal(state.items[0].path,file.path)
  assert.equal(fixture.status().recorderListeners,0);assert.equal(fixture.status().recorderStarts,2);assert.equal(fixture.status().recorderStops,2)
  flow.dispose();fixture.restore()
})

test('audio playback forwards original methods and counts success only on actual native events',()=>{
  const h = harness(),fixture = installNativeAudioFixture({runtime:h.runtime,file}),player = h.runtime.createInnerAudioContext(),native = h.contexts[0]
  const events = [],first = () => events.push('play'),second = () => events.push('second')
  player.onPlay(first);player.onPlay(first);player.onPlay(second)
  player.onEnded(()=>events.push('ended'));player.onError(value=>events.push(value.errMsg))
  player.autoplay = false;player.src = file.path;player.play()
  assert.equal(native.autoplay,false);assert.equal(native.src,file.path);assert.equal(native.plays,1)
  assert.equal(fixture.status().playerPlays,1);assert.equal(fixture.status().playerPlayEvents,0);assert.deepEqual(events,[])
  native.emit('Play');assert.equal(fixture.status().playerPlayEvents,1);assert.deepEqual(events,['play','second'])
  player.offPlay(second);native.emit('Play');assert.deepEqual(events,['play','second','play'])
  native.emit('Error',{errMsg:'native failure'});native.emit('Ended')
  assert.equal(fixture.status().playerErrorEvents,1);assert.equal(fixture.status().playerEndedEvents,1)
  player.stop();player.destroy();player.destroy();native.emit('Play')
  assert.equal(native.stops,1);assert.equal(native.destroys,1);assert.equal(fixture.status().activePlayers,0)
  assert.equal(fixture.status().playerStops,1);assert.equal(fixture.status().playerDestroys,1);assert.equal(fixture.status().playerPlayEvents,2)
  fixture.restore()
})

test('restore clears listeners and real contexts, restores exact functions, and is idempotent',()=>{
  const h = harness(),fixture = installNativeAudioFixture({runtime:h.runtime,file:{...file,path:'wxfile://tmp_synthetic.mp3'}}),recorder = h.runtime.getRecorderManager()
  let lateCalls = 0;recorder.onStart(()=>{});recorder.onStop(()=>lateCalls++);recorder.start({...RECORD_OPTIONS})
  const player = h.runtime.createInnerAudioContext(),native = h.contexts[0]
  player.onPlay(()=>lateCalls++);player.play();fixture.restore();fixture.restore();native.emit('Play')
  assert.equal(h.runtime.getRecorderManager,h.originalRecorder);assert.equal(h.runtime.createInnerAudioContext,h.originalPlayer)
  assert.equal(fixture.status().restored,true);assert.equal(fixture.status().recording,false);assert.equal(fixture.status().recorderListeners,0);assert.equal(fixture.status().activePlayers,0)
  assert.equal(native.destroys,1);assert.equal(native.stops,1);assert.equal(lateCalls,0)
  assert.throws(()=>recorder.start({...RECORD_OPTIONS}),/fixture restored/)
  assert.doesNotThrow(()=>JSON.stringify(fixture.status()))
})
