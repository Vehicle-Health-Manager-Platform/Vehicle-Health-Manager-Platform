import {apiOrigin,cloudRunEnabled} from './api-config.js'
import {apiRuntime} from './api-runtime.js'

const messages={400:'材料参数无效，请重新选择',401:'登录已失效，请重新登录',403:'当前身份无权上传辅助材料',404:'车辆或材料不可用，请重新选择',409:'材料上传发生冲突，请移除后重新选择',413:'材料过大，请缩小后重试',422:'材料格式或扫描未通过，请使用合规录音、JPEG/PNG或静态PDF',429:'上传过于频繁，请稍后重试',503:'材料上传服务尚未就绪，请稍后重试'}
export function materialScope(value){
  const match=/^(ai|archive):([1-9]\d*)(?::[1-9]\d*)?$/.exec(value||'')
  if(!match||!Number.isSafeInteger(Number(match[2])))return null
  return {purpose:match[1]==='ai'?'AI':'ARCHIVE',vehicleId:Number(match[2])}
}
export function createInputMaterialApi({baseUrl,runtime,cloudMode=false}){
  return {upload(token,scope,item,key){
    let task,settled=false,rejectPending
    const promise=new Promise((resolve,reject)=>{
      rejectPending=reject
      const fail=message=>{if(!settled){settled=true;reject(new Error(message))}}
      if(!token||!scope||!['AI','ARCHIVE'].includes(scope.purpose)||!Number.isSafeInteger(scope.vehicleId)||scope.vehicleId<1
        ||!['audio','pdf','image'].includes(item?.kind)||!/^[0-9a-f-]{36}$/i.test(key||''))return fail('请先登录并选择当前车辆和材料')
      if(cloudMode||!/^https?:\/\//.test(baseUrl||'')||/\.invalid\/?$/.test(baseUrl))return fail('材料上传需要配置可直达的后端地址')
      if(typeof item.path!=='string'||!(/^(?:wxfile:\/\/|file:\/\/\/|https?:\/\/tmp\/|[a-zA-Z]:[\\/])/.test(item.path)||/^\/(?!\/)/.test(item.path)))return fail('本机材料路径无效，请重新选择')
      const max=item.kind==='audio'?3*1024*1024:item.kind==='image'?4*1024*1024:10*1024*1024
      if(!Number.isSafeInteger(item.size)||item.size<1||item.size>max)return fail('材料大小无效或超过限制')
      try{task=runtime().uploadFile({url:baseUrl.replace(/\/$/,'')+`/api/input-materials/${scope.purpose}/${scope.vehicleId}/upload`,filePath:item.path,name:'file',timeout:60000,
        header:{Authorization:`Bearer ${token}`,'Idempotency-Key':key,'X-Material-Kind':item.kind},
        success:r=>{
          if(settled)return
          if(r?.statusCode!==200)return fail(messages[r?.statusCode]||'材料上传未成功，请稍后重试')
          let body;try{body=typeof r.data==='string'?JSON.parse(r.data):r.data}catch{return fail('材料上传响应无效，请稍后重试')}
          const data=body?.data,expected=item.kind==='audio'?'audio/mpeg':'application/pdf'
          if(body?.code!==0||!Number.isSafeInteger(data?.material_id)||data.material_id<1||data.purpose!==scope.purpose||data.vehicle_id!==scope.vehicleId
            ||!(item.kind==='image'?['image/jpeg','image/png'].includes(data.content_type):data.content_type===expected)||data.size_bytes!==item.size||data.scan_status!=='CLEAN'
            ||(item.kind==='audio'?(!Number.isInteger(data.duration_ms)||data.duration_ms<1000||data.duration_ms>60000||data.pages!==0):(!Number.isInteger(data.pages)||data.pages<1||data.pages>(item.kind==='image'?1:50)||data.duration_ms!==0)))return fail('材料上传响应校验失败，请稍后重试')
          settled=true;resolve({materialId:data.material_id,contentType:data.content_type,durationMs:data.duration_ms,pages:data.pages})
        },fail:()=>fail('无法连接材料服务，请检查网络后重试')})
      }catch{fail('当前环境无法上传材料，请更新微信后重试')}
    })
    return {promise,abort(){if(settled)return;settled=true;try{task?.abort?.()}catch{}rejectPending(new Error('材料上传已取消；已接收的材料可能仍保存在服务器'))}}
  }}
}
export const inputMaterialApi=createInputMaterialApi({baseUrl:apiOrigin,runtime:()=>apiRuntime,cloudMode:cloudRunEnabled})

export function createMaterialUploadFlow({state,items,actor,scope,api=inputMaterialApi,keyFactory}){
  let generation=0,pending=null
  const keys=new Map()
  const context=()=>actor()+'\0'+scope()
  function reset(){generation++;const old=pending;pending=null;old?.abort();keys.clear();state.busyId='';state.message='';state.results={}}
  async function upload(id){
    if(pending||!actor())return
    const item=items().find(i=>i.id===id),target=materialScope(scope())
    if(!item||!['audio','pdf','image'].includes(item.kind)||!target||state.results[id])return
    const epoch=generation,who=context()
    if(!keys.has(id))keys.set(id,keyFactory())
    state.busyId=id;state.message='正在上传并检查材料…'
    let operation
    try{
      operation=api.upload(actor(),target,item,keys.get(id));pending=operation
      const result=await operation.promise
      if(generation===epoch&&context()===who&&items().some(i=>i.id===id)){
        state.results={...state.results,[id]:result};state.message='材料已上传并通过扫描；识别需单独同意，未发送给AI或写入档案。'
      }
    }catch(error){if(generation===epoch&&context()===who&&items().some(i=>i.id===id))state.message=error.message}
    finally{if(generation===epoch){if(pending===operation)pending=null;state.busyId=''}}
  }
  return {upload,reset}
}
