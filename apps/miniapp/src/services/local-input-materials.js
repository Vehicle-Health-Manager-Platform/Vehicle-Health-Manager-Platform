import { apiRuntime } from './api-runtime.js'

export const LOCAL_MATERIAL_LIMITS = Object.freeze({count:3,imageBytes:10*1024*1024,pdfBytes:10*1024*1024,audioBytes:3*1024*1024,minAudioMs:1000,maxAudioMs:60000})
export const RECORD_OPTIONS = Object.freeze({duration:60000,sampleRate:16000,numberOfChannels:1,encodeBitRate:48000,format:'mp3'})
const recorderLeases = new WeakMap()
let sequence = 0
const busyPhases = ['requesting','recording','stopping','selecting','reading']
const localPath = value => typeof value==='string' && value.length>0 && !/[\u0000-\u001f]/.test(value)
  && /^(?:wxfile:\/\/|file:\/\/\/|\/(?!\/)|[a-z]:[\\/]|https?:\/\/tmp\/)/i.test(value)
const safeFailure = (error,fallback) => /auth|permission|authorize|denied/i.test(String(error?.errMsg||''))
  ? {kind:'permission',message:'未获得所需权限，可在设置中开启后重试'} : {kind:'native',message:fallback}
export function initialLocalMaterialsState(){return {phase:'idle',seconds:0,items:[],playingId:'',message:'',kind:''}}

export function createLocalMaterialsFlow({state,runtime=()=>apiRuntime,actor,scope,now=()=>Date.now(),schedule=(fn)=>setInterval(fn,250),cancelTimer=id=>clearInterval(id)}={}){
  let epoch=0,visible=true,disposed=false,lease=null,clock=null,player=null,preserved=null,pending=null
  const context=()=>String(actor()||'')+'\0'+String(scope()||'')
  const same=(e,c)=>!disposed&&epoch===e&&context()===c
  const live=(e,c)=>same(e,c)&&visible
  const fail=(kind,message)=>{state.kind=kind;state.message=message}
  const stopClock=()=>{if(clock!==null)cancelTimer(clock);clock=null}
  function stopPlayback(){
    const audio=player;player=null;state.playingId=''
    if(state.phase==='playing')state.phase='idle'
    if(audio){try{audio.stop?.()}catch{}try{audio.destroy?.()}catch{}}
  }
  function cancelRecording(){
    stopClock()
    if(!lease)return
    lease.cancelled=true
    try{lease.manager.stop()}catch{}
    // Retain the singleton lease until its terminal event; a late stop must
    // never be interpreted as the next component's recording.
  }
  function reset(){
    epoch++;preserved=null;pending=null;cancelRecording();stopPlayback()
    Object.assign(state,initialLocalMaterialsState())
  }
  function suspend(){
    if(state.phase==='selecting'||state.phase==='reading'&&preserved){
      if(!preserved)preserved={epoch,context:context(),items:[...state.items]}
      visible=false;state.items=[];stopPlayback();return
    }
    visible=false;reset()
  }
  function resume(){
    if(disposed)return
    visible=true
    if(preserved&&same(preserved.epoch,preserved.context))state.items=preserved.items
    preserved=null
    if(pending&&same(pending.epoch,pending.context)&&state.items.length<LOCAL_MATERIAL_LIMITS.count)state.items=[...state.items,pending.item]
    pending=null
  }
  function dispose(){if(disposed)return;visible=false;reset();disposed=true}
  function ready(){
    if(disposed||!visible)return false
    if(!actor()){fail('unauthorized','请先登录当前账号');return false}
    if(busyPhases.includes(state.phase))return false
    if(state.items.length>=LOCAL_MATERIAL_LIMITS.count){fail('limit','最多保留3份本机辅助材料，请先移除一份');return false}
    stopPlayback();state.kind='';state.message='';return true
  }
  function add(item,e,c){
    if(!same(e,c))return
    if(visible){if(state.items.length<LOCAL_MATERIAL_LIMITS.count)state.items=[...state.items,item]}
    else pending={epoch:e,context:c,item}
  }
  async function measure(path,reported){
    if(Number.isSafeInteger(reported)&&reported>0)return reported
    return new Promise(resolve=>{
      try{const fs=runtime().getFileSystemManager?.();if(!fs?.getFileInfo){resolve(0);return}
        fs.getFileInfo({filePath:path,success:r=>resolve(Number.isSafeInteger(r.size)&&r.size>0?r.size:0),fail:()=>resolve(0)})
      }catch{resolve(0)}
    })
  }
  async function select(kind){
    if(!['image','pdf'].includes(kind)||!ready())return
    const target=runtime(),e=++epoch,c=context()
    const available=name=>typeof target.supports==='function'?target.supports(name):typeof target[name]==='function'
    const method=kind==='pdf'?'chooseMessageFile':available('chooseMedia')?'chooseMedia':'chooseImage'
    if(!available(method)){fail('unsupported',kind==='pdf'?'当前环境不支持选择PDF，请先手动填写':'当前环境不支持选择图片，请先手动填写');return}
    state.phase='selecting'
    try{
      const result=await new Promise((resolve,reject)=>target[method]({count:1,
        ...(kind==='pdf'?{type:'file',extension:['pdf']}:method==='chooseMedia'?{mediaType:['image'],sourceType:['album','camera'],sizeType:['original']}:{sourceType:['album','camera'],sizeType:['original']}),
        success:resolve,fail:reject}))
      if(!same(e,c))return
      const file=result?.tempFiles?.[0],path=file?.tempFilePath||file?.path||result?.tempFilePaths?.[0]
      if(!localPath(path)){fail('invalid','无法读取本机文件，请重新选择');return}
      const name=typeof file?.name==='string'?file.name:''
      if(kind==='pdf'&&(!/\.pdf$/i.test(name)||name.length>200||/[\u0000-\u001f/\\]/.test(name))){fail('invalid','请选择名称正常的PDF文件');return}
      state.phase='reading'
      const size=await measure(path,file?.size)
      if(!same(e,c))return
      if(!size||size>(kind==='pdf'?LOCAL_MATERIAL_LIMITS.pdfBytes:LOCAL_MATERIAL_LIMITS.imageBytes)){fail('invalid','文件为空、无法读取或超过10MiB，请重新选择');return}
      add({id:'local-'+(++sequence),kind,path,size,label:kind==='pdf'?name:'本机图片',durationMs:null},e,c)
    }catch(error){if(same(e,c)&&!/cancel/i.test(String(error?.errMsg||''))){const f=safeFailure(error,'文件选择失败，请重试');fail(f.kind,f.message)}}
    finally{if(same(e,c))state.phase='idle'}
  }
  function startRecording(){
    if(!ready())return
    const target=runtime(),manager=target.getRecorderManager?.()
    const required=['start','stop','onStart','onStop','onError','offStart','offStop','offError']
    if(!manager||!required.every(k=>typeof manager[k]==='function')){fail('unsupported','当前环境不支持安全录音，请先手动填写');return}
    if(recorderLeases.has(manager)){fail('busy','上一段录音正在结束，请稍后重试');return}
    const e=++epoch,c=context(),entry={manager,epoch:e,context:c,cancelled:false,started:false,finished:false}
    lease=entry;recorderLeases.set(manager,entry);state.phase='requesting';state.seconds=0
    const release=()=>{
      if(lease===entry){lease=null;stopClock()}
      if(recorderLeases.get(manager)===entry)recorderLeases.delete(manager)
      for(const [name,fn]of [['offStart',onStart],['offStop',onStop],['offError',onError]]){try{manager[name](fn)}catch{}}
    }
    const onStart=()=>{
      if(entry.finished)return
      if(entry.cancelled||!live(e,c)){entry.cancelled=true;try{manager.stop()}catch{}return}
      entry.started=true;entry.startedAt=now();state.phase='recording'
      clock=schedule(()=>{if(live(e,c)&&!entry.cancelled){state.seconds=Math.min(60,Math.floor((now()-entry.startedAt)/1000));if(state.seconds>=60)stopRecording()}})
    }
    const onStop=async result=>{
      if(entry.finished)return
      entry.finished=true
      const accept=!entry.cancelled&&live(e,c)
      release()
      if(!accept){if(live(e,c))state.phase='idle';return}
      state.phase='reading'
      try{
        const path=result?.tempFilePath,duration=result?.duration
        if(!localPath(path)||!Number.isFinite(duration)||duration<LOCAL_MATERIAL_LIMITS.minAudioMs||duration>LOCAL_MATERIAL_LIMITS.maxAudioMs){fail('invalid','请录制1–60秒的语音');return}
        const size=await measure(path,result.fileSize)
        if(!live(e,c))return
        if(!size||size>LOCAL_MATERIAL_LIMITS.audioBytes){fail('invalid','录音为空、无法读取或超过3MiB，请重新录制');return}
        add({id:'local-'+(++sequence),kind:'audio',path,size,label:'本机语音',durationMs:Math.round(duration)},e,c)
      }finally{if(live(e,c))state.phase='idle'}
    }
    const onError=error=>{if(entry.finished)return;entry.finished=true;release();if(live(e,c)&&!entry.cancelled){state.phase='idle';const f=safeFailure(error,'录音失败，请重新录制或手动填写');fail(f.kind,f.message)}}
    try{manager.onStart(onStart);manager.onStop(onStop);manager.onError(onError);manager.start({...RECORD_OPTIONS})}
    catch{release();if(live(e,c)){state.phase='idle';fail('native','无法开始录音，请先手动填写')}}
  }
  function stopRecording(){
    if(!lease||lease.cancelled||!['requesting','recording'].includes(state.phase))return
    if(state.phase==='requesting'){cancelRecording();state.phase='idle';return}
    state.phase='stopping';stopClock()
    try{lease.manager.stop()}catch{fail('native','录音未能正常结束，请稍后重试')}
  }
  function cancel(){
    epoch++;pending=null;preserved=null;cancelRecording();stopPlayback();state.phase='idle';state.seconds=0;state.kind='';state.message=''
  }
  function remove(id){
    if(busyPhases.includes(state.phase))return
    if(state.playingId===id)stopPlayback()
    state.items=state.items.filter(i=>i.id!==id)
  }
  function play(id){
    if(!visible||disposed||!actor()||busyPhases.includes(state.phase))return
    const item=state.items.find(i=>i.id===id&&i.kind==='audio')
    if(!item||!localPath(item.path))return
    stopPlayback();const e=epoch,c=context(),audio=runtime().createInnerAudioContext?.()
    if(!audio||!['play','onPlay','onEnded','onError','destroy'].every(k=>typeof audio[k]==='function')){try{audio?.destroy?.()}catch{}fail('unsupported','当前环境不支持试听');return}
    player=audio;state.playingId=id;state.phase='playing';state.message='';state.kind=''
    const current=()=>player===audio&&live(e,c)
    try{
      audio.onPlay(()=>{if(!current()){try{audio.stop?.()}catch{}}})
      audio.onEnded(()=>{if(current())stopPlayback()})
      audio.onError(()=>{if(current()){stopPlayback();fail('native','试听失败，可重新录制或手动填写')}})
      audio.autoplay=false;audio.src=item.path;audio.play()
    }catch{if(current()){stopPlayback();fail('native','试听未能开始')}}
  }
  function preview(id){
    if(!visible||disposed||!actor()||busyPhases.includes(state.phase))return
    const item=state.items.find(i=>i.id===id)
    if(!item||!localPath(item.path))return
    const e=epoch,c=context(),target=runtime()
    const method=item.kind==='pdf'?'openDocument':item.kind==='image'?'previewImage':''
    if(!method)return
    try{target[method]({...(item.kind==='pdf'?{filePath:item.path,fileType:'pdf',showMenu:false}:{urls:[item.path],current:item.path}),fail:()=>{if(live(e,c))fail('native','预览未打开，可继续手动填写')}})}catch{if(live(e,c))fail('unsupported','当前环境不支持文件预览')}
  }
  function authorize(){const e=epoch,c=context();try{runtime().openSetting({success:()=>{if(live(e,c))state.message='设置返回后可重新录制或选择材料'}})}catch{if(live(e,c))fail('unsupported','请在微信设置中开启所需权限')}}
  return {select,startRecording,stopRecording,cancel,remove,play,preview,stopPlayback,authorize,reset,suspend,resume,dispose}
}
