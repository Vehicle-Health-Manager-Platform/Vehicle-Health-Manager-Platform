// Isolated native acceptance only: recording events are synthetic; audio playback stays native.
// This module performs no file, network, identity or persistence operations.
const recordingOptions = Object.freeze({duration:60000,sampleRate:16000,numberOfChannels:1,encodeBitRate:48000,format:'mp3'})

function validFile(file) {
  if (!file || typeof file.path !== 'string' || !Number.isSafeInteger(file.size)
    || file.size < 1 || file.size > 3 * 1024 * 1024 || !Number.isSafeInteger(file.durationMs)
    || file.durationMs < 1000 || file.durationMs > 60000) return false
  // Reject saved/user files, remote hosts, escapes, traversal and control characters.
  if (!/^(?:wxfile:\/\/tmp(?:[_./-])|https?:\/\/tmp\/)[A-Za-z0-9_./-]+$/.test(file.path)) return false
  return !file.path.split('/').some(part => part === '..' || part === '.')
}

export function installNativeAudioFixture({runtime,file} = {}) {
  if (!runtime || typeof runtime.getRecorderManager !== 'function'
    || typeof runtime.createInnerAudioContext !== 'function' || !validFile(file)) {
    throw new Error('Invalid native audio fixture')
  }
  const fixture = {path:file.path,size:file.size,durationMs:file.durationMs}
  const names = ['getRecorderManager','createInnerAudioContext']
  const descriptors = names.map(name => Object.getOwnPropertyDescriptor(runtime,name))
  const originalRecorder = runtime.getRecorderManager, originalPlayer = runtime.createInnerAudioContext
  const handlers = {Start:new Set(),Stop:new Set(),Error:new Set()}, players = new Set()
  const counts = {recorderStarts:0,recorderStops:0,playerPlays:0,playerStops:0,playerDestroys:0,playerPlayEvents:0,playerEndedEvents:0,playerErrorEvents:0}
  let restored = false, recording = false
  const active = () => {if (restored) throw new Error('Native audio fixture restored')}
  const emit = (name,value) => {for (const handler of [...handlers[name]]) handler(value)}
  function restoreProperties() {
    names.forEach((name,index) => {
      if (descriptors[index]) Object.defineProperty(runtime,name,descriptors[index])
      else delete runtime[name]
    })
  }
  const recorder = {
    start(options) {
      active()
      if (!options || Object.entries(recordingOptions).some(([key,value]) => options[key] !== value)) {
        throw new Error('Invalid native recording options')
      }
      if (recording) throw new Error('Native audio fixture already recording')
      recording = true;counts.recorderStarts++;emit('Start')
    },
    stop() {
      active()
      if (!recording) return
      recording = false;counts.recorderStops++
      emit('Stop',{tempFilePath:fixture.path,fileSize:fixture.size,duration:fixture.durationMs})
    },
  }
  for (const name of Object.keys(handlers)) {
    recorder['on'+name] = handler => {
      active()
      if (typeof handler !== 'function') throw new Error('Invalid native recording listener')
      handlers[name].add(handler)
    }
    recorder['off'+name] = handler => {if (handler === undefined) handlers[name].clear();else handlers[name].delete(handler)}
  }
  function createPlayer() {
    active()
    const native = originalPlayer.call(runtime)
    if (!native || !['play','onPlay','onEnded','onError','destroy'].every(name => typeof native[name] === 'function')) return native
    const listeners = {Play:new Set(),Ended:new Set(),Error:new Set()}, bridges = {}
    let destroyed = false
    const player = {
      play() {active();if (!destroyed) {counts.playerPlays++;return native.play()}},
      stop() {if (!destroyed) {counts.playerStops++;return native.stop?.()}},
      destroy() {
        if (destroyed) return
        destroyed = true;players.delete(player);counts.playerDestroys++
        for (const name of Object.keys(listeners)) {
          listeners[name].clear()
          try {native['off'+name]?.(bridges[name])} catch {}
        }
        return native.destroy()
      },
    }
    for (const name of Object.keys(listeners)) {
      bridges[name] = value => {
        if (destroyed || restored) return
        counts['player'+name+'Events']++
        for (const handler of [...listeners[name]]) handler(value)
      }
      player['on'+name] = handler => {
        active()
        if (typeof handler !== 'function') throw new Error('Invalid native playback listener')
        if (!destroyed) listeners[name].add(handler)
      }
      player['off'+name] = handler => {if (handler === undefined) listeners[name].clear();else listeners[name].delete(handler)}
    }
    for (const name of ['src','autoplay']) Object.defineProperty(player,name,{enumerable:true,get:()=>native[name],set:value=>{native[name]=value}})
    players.add(player)
    try {for (const name of Object.keys(bridges)) native['on'+name](bridges[name])}
    catch (error) {try {player.destroy()} catch {};throw error}
    return player
  }
  try {runtime.getRecorderManager = () => {active();return recorder};runtime.createInnerAudioContext = createPlayer}
  catch (error) {restoreProperties();throw error}
  return {
    status() {
      return {...counts,recorderListeners:Object.values(handlers).reduce((sum,set)=>sum+set.size,0),activePlayers:players.size,recording,restored}
    },
    restore() {
      if (restored) return
      restored = true;recording = false
      for (const set of Object.values(handlers)) set.clear()
      for (const player of [...players]) {try {player.stop()} catch {};try {player.destroy()} catch {}}
      restoreProperties()
    },
  }
}
