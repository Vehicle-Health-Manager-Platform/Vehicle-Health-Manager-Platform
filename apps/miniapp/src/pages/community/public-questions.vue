<script setup>
import {reactive,ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import VehicleList from '../../components/VehicleList.vue'
import QuestionConfirmation from '../../components/QuestionConfirmation.vue'
import QuestionRecovery from '../../components/QuestionRecovery.vue'
import {ownerSession} from '../../services/owner-session.js'
import {operatorSession,operatorToken} from '../../services/operator-session.js'
import {selectedOwnerVehicle,selectOwnerVehicle} from '../../services/owner-vehicle-selection.js'
import {createCommunityQuestionApi,QUESTION_CATEGORIES} from '../../services/community-questions.js'
import {createPublicQaApi,createPublicQaRecovery,publicQaRoute,publicQaReference,PUBLIC_QA_CONSENT,PUBLIC_QA_STATES} from '../../services/public-community-questions.js'
import {TEXT_REASONS} from '../../services/public-review-texts.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../../services/async-diagnosis.js'
import {createQuestionConfirmation,pendingQuestion} from '../../services/community-question-recovery.js'
import {imageRequestKey} from '../../services/private-images.js'
const route=ref(null),state=reactive(initialDiagnosisState()),confirmation=reactive({prompt:null}),prompt=ref(null)
const selectedQuestion=ref(0),selectedAnswer=ref(0),proposal=ref(null),consent=ref(false),reasons=ref({}),cursor=ref(null),referenceDraft=ref('')
const vehicle=selectedOwnerVehicle,api=createPublicQaApi(),privateApi=createCommunityQuestionApi()
const role=()=>route.value?.mode==='review'?'admin':'owner'
const token=()=>role()==='admin'?operatorToken():ownerSession.accessToken
const actor=()=>role()==='admin'?operatorSession.actorId:ownerSession.actorId
const allowed=()=>Boolean(route.value&&(route.value.mode==='review'?operatorSession.canReview:route.value.mode==='owner'||vehicle.value?.model_id))
const scope=()=>route.value?.mode==='review'?'review':`case:${route.value?.caseId||0}`
const recovery=createPublicQaRecovery({actor,role,scope}),gate=createQuestionConfirmation({state:confirmation,preview:()=>prompt.value})
async function load(t,p){
 if(route.value.mode==='review')return {applications:await api.pending(t,p)}
 if(route.value.mode==='owner'){
  const applications=await api.own(t,route.value.caseId,p)
  let messages=null
  try{messages=(await privateApi.detail(t,'owner',route.value.caseId,p)).messages}catch{/* Own publication recovery and withdrawal remain available. */}
  return {applications,messages}
 }
 return route.value.publicId?{detail:await api.detail(t,vehicle.value.vehicle_id,vehicle.value.model_id,route.value.publicId)}:{public:await api.list(t,vehicle.value.vehicle_id,vehicle.value.model_id,cursor.value)}
}
const flow=createAsyncDiagnosisFlow({state,token,allowed,scope:()=>`${role()}:${actor()}:${route.value?.mode}:${route.value?.caseId}:${route.value?.publicId}:${vehicle.value?.vehicle_id}:${vehicle.value?.model_id}`,load,write:(t,a,id,b,k)=>api.write(t,a,id,b,k),newKey:imageRequestKey,recovery:{
 read:()=>route.value?.mode==='public'?{pending:null}:recovery.read(),savePending:v=>recovery.savePending(v),complete:v=>recovery.complete(v),clearPending:v=>recovery.clearPending(v),clear:()=>{if(route.value?.mode!=='public')recovery.clear()},marker:()=>recovery.marker(),discard:m=>recovery.discard(m),
},confirm:gate.ask,onWritten:clearSelection})
let visible=false,previewEpoch=0
function clearPreview(){previewEpoch++;proposal.value=null;consent.value=false;prompt.value=null}
function clearSelection(){clearPreview();selectedQuestion.value=0;selectedAnswer.value=0;reasons.value={}}
function reset(){gate.cancel();flow.reset();clearSelection();referenceDraft.value=''}
const blocked=()=>state.busy||state.retry||state.kind==='storage'
function select(m){if(blocked())return;previewEpoch++;proposal.value=null;consent.value=false;if(m.sender==='OWNER')selectedQuestion.value=m.message_id;else selectedAnswer.value=m.message_id}
async function preview(){
 if(blocked()||route.value?.mode!=='owner'||!state.data?.messages)return
 const epoch=++previewEpoch,t=token(),id=route.value.caseId,q=selectedQuestion.value,a=selectedAnswer.value
 proposal.value=null;consent.value=false;state.busy=true
 try{const result=await api.preview(t,id,q,a);if(visible&&epoch===previewEpoch&&token()===t&&route.value?.caseId===id)proposal.value=result}
 catch(e){if(epoch===previewEpoch){state.message=e.message;state.kind=e.kind||'server';if(['unauthorized','forbidden','missing'].includes(e.kind))reset()}}
 finally{if(epoch===previewEpoch)state.busy=false}
}
function submit(){
 if(blocked()||!proposal.value||!consent.value)return
 const p=proposal.value.preview
 prompt.value={title:'确认另行公开这两条摘要',lines:[`问答 ${route.value.caseId} · 车型 ${p.model_id} · ${QUESTION_CATEGORIES[p.category]}`,'仅发布以下脱敏摘要，供已登录同款车主读取；不公开原问答或档案。人工审核不能保证发现所有隐私，请逐字检查。',p.question_text,p.answer_text,`授权版本 ${PUBLIC_QA_CONSENT} · 预览 ${proposal.value.preview_hash}`]}
 flow.save('submit',route.value.caseId,{question_message_id:selectedQuestion.value,answer_message_id:selectedAnswer.value,preview_hash:proposal.value.preview_hash,consent_version:PUBLIC_QA_CONSENT,consent:true})
}
function action(row,decision){
 if(blocked()||!state.data?.applications.items.includes(row))return
 const review=route.value.mode==='review',reason=decision==='REJECT'?reasons.value[row.application_id]:null
 if(review&&(decision==='APPROVE'&&(!row.preview||!row.source_available)||decision==='REJECT'&&!Object.hasOwn(TEXT_REASONS,reason))){state.message='请核对有效预览或选择驳回原因';return}
 if(!review&&row.status==='WITHDRAWN')return
 const p=row.preview
 prompt.value={title:review?'确认本次公开摘要审核':'确认撤回此公开申请',lines:[`申请 ${row.application_id} · 版本 ${row.revision}`,p?`车型 ${p.model_id} · ${QUESTION_CATEGORIES[p.category]}`:'源预览不可用',p?.question_text||'',p?.answer_text||'',review?(decision==='APPROVE'?'已人工核对隐私、内容和当前来源':TEXT_REASONS[reason]):'撤回后拒绝新的公开读取；已下载或复制的内容无法远程收回。']}
 flow.save(review?'moderate':'withdraw',row.application_id,review?{expected_revision:row.revision,decision,reason_code:reason}:{expected_revision:row.revision})
}
function open(id){reset();cursor.value=null;route.value={mode:'public',caseId:0,publicId:id};if(visible)flow.load()}
function importReference(){const raw=referenceDraft.value.trim(),id=raw.startsWith('/pages/community/public-questions?mode=public&public_id=')?raw.slice('/pages/community/public-questions?mode=public&public_id='.length):raw;if(publicQaReference(id))open(id);else state.message='请输入有效公开UUID或完整应用内引用'}
function copy(id){if(!blocked()&&state.data?.detail?.question.public_id===id)uni.setClipboardData({data:publicQaReference(id)})}
function next(){if(blocked())return;if(route.value.mode==='public'){cursor.value=state.data?.public?.next_cursor;if(cursor.value)flow.load()}else{clearPreview();flow.load(state.page+1)}}
function previous(){if(!blocked()&&state.page>1){clearPreview();flow.load(state.page-1)}}
function login(){uni.navigateTo({url:role()==='admin'?'/pages/operator/login':'/pages/owner/index'})}
watch(()=>[token(),actor(),allowed(),vehicle.value?.vehicle_id,vehicle.value?.model_id],()=>{reset();cursor.value=null;if(visible&&token()&&allowed())flow.load()},{flush:'sync'})
onLoad(q=>{route.value=publicQaRoute(q||{})});onShow(()=>{visible=true;if(token()&&allowed())flow.load()})
function hide(){visible=false;reset()}onHide(hide);onUnload(hide)
</script>
<template><view class="reservation-page">
 <text class="reservation-title">{{route?.mode==='owner'?'问答独立公开授权':route?.mode==='review'?'公开问答审核':'同款公开问答'}}</text>
 <text>公开内容与私有问答分开授权；引用只包含公开UUID，读取时重新核验当前来源和本人车型。</text>
 <text v-if="!route">入口参数无效</text><button v-if="!token()" @tap="login">登录当前身份</button><text v-if="route?.mode==='review'&&!allowed()">需要独立内容审核权限</text>
 <template v-if="route?.mode==='public'"><VehicleList :selected-id="vehicle?.vehicle_id||0" @select="selectOwnerVehicle"/><input v-model="referenceDraft" maxlength="256" placeholder="公开UUID或应用内引用"/><button :disabled="blocked()" @tap="importReference">核验收到的引用</button></template>
 <QuestionRecovery v-if="route&&route.mode!=='public'" :state="state" :flow="flow" :pending="pendingQuestion(recovery)"/>
 <text v-if="route?.mode==='public'&&state.message" role="status">{{state.message}}</text>
 <button :disabled="state.busy||!token()||!allowed()" @tap="clearSelection();flow.load(state.page)">刷新并核验当前状态</button>
 <template v-if="state.data">
  <template v-if="state.data.messages"><text>逐条选择一个问题及后续对应运营答复，可翻页选择；选择变化后须重新预览。</text><view v-for="m in state.data.messages.items" :key="m.message_id" class="reservation-panel"><text>{{m.message_id}} · {{m.sender==='OWNER'?'原问题':'运营答复'}}</text><text class="qa-text">{{m.body}}</text><button :disabled="blocked()" @tap="select(m)">{{selectedQuestion===m.message_id||selectedAnswer===m.message_id?'已选择':'选择此条'}}</button></view><text>问题 {{selectedQuestion||'未选'}} · 答复 {{selectedAnswer||'未选'}}</text><button :disabled="blocked()||!selectedQuestion||!selectedAnswer" @tap="preview">获取服务器脱敏预览</button></template>
  <view v-if="proposal" class="reservation-panel"><text>车型 {{proposal.preview.model_id}} · {{QUESTION_CATEGORIES[proposal.preview.category]}}</text><text class="qa-text">{{proposal.preview.question_text}}</text><text class="qa-text">{{proposal.preview.answer_text}}</text><checkbox-group @change="consent=$event.detail.value.includes('yes')"><label><checkbox value="yes" :checked="consent" :disabled="blocked()"/>已逐字检查摘要，同意独立公开用途</label></checkbox-group><button :disabled="blocked()||!consent" @tap="submit">完整确认并申请公开</button></view>
  <view v-for="row in state.data.applications?.items||[]" :key="row.application_id" class="reservation-panel"><text>申请 {{row.application_id}} · {{PUBLIC_QA_STATES[row.status]}} · 版本 {{row.revision}}</text><template v-if="row.preview"><text>车型 {{row.preview.model_id}} · {{QUESTION_CATEGORIES[row.preview.category]}}</text><text class="qa-text">{{row.preview.question_text}}</text><text class="qa-text">{{row.preview.answer_text}}</text></template><text v-else>来源预览不可用</text><template v-if="route.mode==='review'"><text>{{row.source_available?'当前来源可核验':'当前来源不可核验，禁止批准'}}</text><button :disabled="blocked()||!row.preview||!row.source_available" @tap="action(row,'APPROVE')">预览批准</button><picker :range="Object.values(TEXT_REASONS)" @change="reasons[row.application_id]=Object.keys(TEXT_REASONS)[Number($event.detail.value)]"><view>驳回原因：{{TEXT_REASONS[reasons[row.application_id]]||'请选择'}}</view></picker><button :disabled="blocked()" @tap="action(row,'REJECT')">预览驳回</button></template><template v-else><text>{{row.visible?'当前可供同款读取':'当前不公开展示'}}</text><button v-if="row.status!=='WITHDRAWN'" :disabled="blocked()" @tap="action(row,'WITHDRAW')">预览撤回</button></template></view>
  <view v-for="row in state.data.public?.items||[]" :key="row.public_id" class="reservation-panel"><text>{{QUESTION_CATEGORIES[row.category]}} · 已授权审核</text><text class="qa-text">{{row.question_text}}</text><button :disabled="blocked()" @tap="open(row.public_id)">核验当前详情</button></view>
  <view v-if="state.data.detail" class="reservation-panel"><text class="qa-text">{{state.data.detail.question.question_text}}</text><text class="qa-text">{{state.data.detail.question.answer_text}}</text><text>{{state.data.detail.question.published_at}}</text><button :disabled="blocked()" @tap="copy(state.data.detail.question.public_id)">复制应用内公开引用</button></view>
  <text v-if="state.data.public&&!state.data.public.items.length">本页暂无有效公开问答；可继续核验后续页。</text>
  <button v-if="state.page>1" :disabled="blocked()" @tap="previous">上一页</button><button v-if="state.data.public?.next_cursor||state.data.applications&&state.page*20<state.data.applications.total||state.data.messages&&state.page*20<state.data.messages.total" :disabled="blocked()" @tap="next">继续查询</button>
 </template><QuestionConfirmation :prompt="confirmation.prompt" @answer="gate.settle"/>
</view></template>
<style scoped>.qa-text{white-space:pre-wrap;overflow-wrap:anywhere}</style>
