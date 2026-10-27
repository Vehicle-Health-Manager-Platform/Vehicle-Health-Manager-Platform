import {AsrError} from './asr-jobs.js'
import {createAsrFlow,createAsrReceipts,recoverAndCancelAsr} from './asr-flow.js'
import {documentApi,documentInput} from './document-jobs.js'
export const documentReceipts=createAsrReceipts()
export const documentMessage=text=>text.replaceAll('转写','图文提取').replaceAll('音频','材料').replaceAll('语音','文档').replaceAll('重新录制','重新准备')
export function createDocumentFlow(options){
 return createAsrFlow({...options,api:options.api||documentApi,receipts:options.receipts||documentReceipts,adapter:{
  context:m=>[m?.contentType,m?.pages],text:()=>'',signature:JSON.stringify,
  failure:job=>({LIMIT_EXCEEDED:'材料超过4页、文字或图像处理预算，未保留部分候选；请缩减材料后重新上传。',INVALID_DOCUMENT:'材料格式无效或含不支持的活动内容，请使用静态PDF或JPEG/PNG。',INVALID_RESULT:'提取来源或位置校验未通过，未保留候选，请手动填写。'}[job.error_code]),
  prepare(scope,m){
   const input=documentInput(scope,m?.materialId)
   if(!['application/pdf','image/jpeg','image/png'].includes(m?.contentType)||!Number.isInteger(m?.pages)||m.pages<1||m.pages>4||m.contentType!=='application/pdf'&&m.pages!==1)throw new AsrError('invalid','请选择不超过4页的PDF或单张JPEG/PNG')
   return {input,validationContext:{contentType:m.contentType,pages:m.pages}}
  },
 }})
}
export function appendDocumentBlock(state,flow,pageIndex,blockIndex){
 if(state.phase!=='ready'||state.busy)return false
 const block=state.result?.pages?.[pageIndex]?.blocks?.[blockIndex]
 if(!block)return false
 const next=state.text?state.text+'\n'+block.text:block.text
 if(next.length>1000){state.message='候选文字超过1000字，请先删减再加入；本段未加入。';return false}
 flow.edit(next);return true
}
export const recoverAndCancelDocument=(row,options)=>recoverAndCancelAsr(row,{api:documentApi,receipts:documentReceipts,...options})
