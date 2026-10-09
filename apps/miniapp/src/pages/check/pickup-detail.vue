<script setup>
import { reactive, watch } from 'vue'
import { onLoad,onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { merchantSession } from '../../services/merchant-session.js'
import { ownerSession } from '../../services/owner-session.js'
import { pickupApi,PICKUP_SLOTS,PICKUP_LABELS,FUEL_VALUES,FUEL_LABELS } from '../../services/pickup.js'
import { imageApi,imageRequestKey } from '../../services/private-images.js'
import { disputeApi,disputeStatusLabel,disputeActionLabel } from '../../services/dispute.js'
import { stateLabel } from '../../services/order-status.js'
import { createReservationReadFlow,initialReservationReadState,initialReservationWriteState,createReservationWriteFlow,displayTime } from '../../services/reservations.js'
const state=reactive(initialReservationReadState()),write=reactive(initialReservationWriteState()),handling=reactive(initialReservationWriteState()),review=reactive(initialReservationWriteState()),form=reactive({reason:'',note:''});let order=0,role='owner',visible=false,generation=0,decision='CONFIRM',reviewDecision='ACCEPT'
const token=()=>role==='merchant'?merchantSession.accessToken:ownerSession.accessToken
const flow=createReservationReadFlow({state,token,request:t=>pickupApi.detail(t,order)})
const action=createReservationWriteFlow({state:write,token,newKey:imageRequestKey,body:()=>decision==='CONFIRM'?{decision}:{decision,reason:form.reason.trim()},request:(t,b,k)=>pickupApi.decide(t,order,b.decision,b.reason,k),onConflict:()=>flow.load()})
// 商家的处理记录与车主的复核是两条独立写入：商家只能追加说明，只有车主能解除争议。
const handleFlow=createReservationWriteFlow({state:handling,token,newKey:imageRequestKey,body:()=>({note:form.note.trim()}),request:(t,b,k)=>disputeApi.handle(t,order,b.note,k),onConflict:()=>flow.load()})
const reviewFlow=createReservationWriteFlow({state:review,token,newKey:imageRequestKey,body:()=>reviewDecision==='ACCEPT'?{decision:'ACCEPT'}:{decision:'REJECT',note:form.note.trim()},request:(t,b,k)=>disputeApi.review(t,order,b.decision,b.note,k),onConflict:()=>flow.load()})
// 所有异步确认回调都要重新校验：这期间用户可能切换账号、离开页面或改了选择。
const stillValid=(actor,version)=>visible&&version===generation&&actor===token()
function confirm(){const actor=token(),version=generation;uni.showModal({title:'确认接车单',content:'确认后商家可继续派工，请先核对七张照片、里程及损伤记录。',success:r=>{if(r.confirm&&stillValid(actor,version)){decision='CONFIRM';action.save()}}})}
function dispute(){decision='DISPUTE';action.save()}
function submitHandling(){const actor=token(),version=generation;uni.showModal({title:'提交处理记录',content:'处理说明会写入审计并展示给车主。争议是否解除由车主复核决定，商家不能单方面恢复订单。',success:r=>{if(r.confirm&&stillValid(actor,version))handleFlow.save()}})}
function decideReview(choice){reviewDecision=choice;const actor=token(),version=generation;uni.showModal({title:choice==='ACCEPT'?'接受处理并恢复施工':'不接受处理',content:choice==='ACCEPT'?'接受后争议解除，订单回到争议前状态，可继续派工与施工。':'不接受时争议保持，请填写原因，商家可继续提交处理记录。',success:r=>{if(r.confirm&&stillValid(actor,version))reviewFlow.save()}})}
async function preview(file){const actor=token(),version=generation;try{const signed=await pickupApi.access(actor,order,file);if(version===generation&&actor===token())await imageApi.preview(signed.url)}catch(error){if(version===generation&&actor===token())state.message=error?.message||'图片未打开，请重试'}}
watch(token,()=>{generation++;flow.reset();action.reset();handleFlow.reset();reviewFlow.reset();if(visible&&token())flow.load()},{flush:'sync'});watch(()=>write.saved,saved=>{if(saved&&visible)flow.load()});watch(()=>handling.saved,saved=>{if(saved&&visible)flow.load()});watch(()=>review.saved,saved=>{if(saved&&visible)flow.load()})
onLoad(q=>{order=Number(q?.id)||0;role=q?.role==='merchant'?'merchant':'owner'});onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;generation++;flow.suspend();action.suspend();handleFlow.suspend();reviewFlow.suspend()});onUnload(()=>{generation++;flow.reset();action.reset();handleFlow.reset();reviewFlow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">接车单</text><button v-if="!token()" @tap="uni.navigateTo({url:`/pages/${role}/index`})">前往登录</button><text v-if="state.busy">正在加载接车单…</text><view v-if="state.message" class="reservation-panel"><text>{{state.message}}</text><button @tap="flow.load">重试 / 刷新</button></view>
  <view v-if="state.value" class="reservation-panel" data-testid="pickup-sheet"><text>接车单 #{{state.value.pickup_check_id}}</text><text>实到 {{displayTime(state.value.arrived_at)}}</text><text>手填里程 {{state.value.mileage}} km</text><text>比较基线 {{state.value.mileage_baseline?.mileage}} km · 差值 {{state.value.mileage_delta}} km</text><text v-if="state.value.mileage_reason">里程说明 {{state.value.mileage_reason}}</text><text>油量 {{FUEL_LABELS[FUEL_VALUES.indexOf(state.value.fuel_level)]}}</text><text>损伤 {{state.value.damage_status==='NONE'?'无损伤':'有损伤'}}</text><text v-for="(damage,index) in state.value.damages" :key="index">{{PICKUP_LABELS[damage.photo_slot]}} · 位置 {{(damage.x*100).toFixed(0)}}% / {{(damage.y*100).toFixed(0)}}%：{{damage.note}}</text><text v-if="state.value.arrival_reason">到店说明 {{state.value.arrival_reason}}</text><button v-for="slot in PICKUP_SLOTS" :key="slot" @tap="preview(state.value.photos[slot])">查看{{PICKUP_LABELS[slot]}}照片</button><template v-if="state.value.owner_confirm===0"><text>待车主确认；确认前不能派工。</text><view v-if="role==='owner'"><button :disabled="write.busy" @tap="confirm">确认接车单</button><textarea v-model="form.reason" maxlength="500" placeholder="如有异议，请填写具体问题（必填）" /><button :disabled="write.busy" @tap="dispute">提出异议</button></view></template><text v-else-if="state.value.owner_confirm===1">车主已于 {{displayTime(state.value.confirm_at)}} 确认接车单</text>
    <view v-else class="reservation-panel" data-testid="pickup-dispute"><text class="reservation-heading">争议处理</text><text v-if="state.value.dispute?.status==='RESOLVED' || state.value.owner_confirm===3">车主已复核接受处理，争议已解决，订单已恢复；可继续派工与施工。</text><text v-else>接车单有异议，派工与施工已阻断。</text><text>异议原因：{{state.value.dispute?.reason || state.value.dispute_reason}}</text>
      <template v-if="state.value.dispute"><text data-testid="dispute-status">{{disputeStatusLabel(state.value.dispute.status)}} · 争议前状态 {{stateLabel(state.value.dispute.from_status)}}</text><text>提出于 {{displayTime(state.value.dispute.opened_at)}}</text><text v-if="state.value.dispute.resolved_at">车主已于 {{displayTime(state.value.dispute.resolved_at)}} 复核接受处理，订单已恢复。</text>
        <view v-for="(record,index) in state.value.dispute.records" :key="index"><text data-testid="dispute-record">{{displayTime(record.created_at)}} · {{disputeActionLabel(record.action)}}</text><text v-if="record.note">{{record.note}}</text></view>
        <text v-if="state.value.dispute.status==='OPEN' && !state.value.dispute.records.length">商家尚未提交处理记录。</text>
        <template v-if="role==='merchant' && state.value.dispute.status==='OPEN'"><textarea v-model="form.note" maxlength="500" placeholder="填写处理说明（1–500 字），提交后车主可复核" /><button :disabled="handling.busy" :loading="handling.busy" data-testid="dispute-handle" @tap="submitHandling">提交处理记录</button></template>
        <template v-if="role==='owner' && state.value.dispute.can_review"><text>商家的处理说明在上方；接受后订单恢复，可继续派工与施工。</text><textarea v-model="form.note" maxlength="500" placeholder="接受处理可留空；不接受时请填写原因（1–500 字）" /><button :disabled="review.busy" :loading="review.busy" data-testid="dispute-accept" @tap="decideReview('ACCEPT')">接受处理并恢复施工</button><button :disabled="review.busy" :loading="review.busy" data-testid="dispute-reject" @tap="decideReview('REJECT')">不接受，继续沟通</button></template>
        <text v-else-if="role==='owner' && state.value.dispute.status==='OPEN'">商家尚未提交处理记录，暂不能复核。</text>
      </template>
      <text v-else>该异议产生于争议处理功能上线之前，暂无处理记录，请等待商家联系或联系运营处理。</text>
      <text v-if="handling.message" role="status">{{handling.message}}</text><text v-if="review.message" role="status">{{review.message}}</text>
    </view>
    <text v-if="write.message" role="status">{{write.message}}</text><button @tap="flow.load">刷新接车单</button></view>
</view></template>
