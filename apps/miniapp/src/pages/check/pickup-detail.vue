<script setup>
import { reactive, watch } from 'vue'
import { onLoad,onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { merchantSession } from '../../services/merchant-session.js'
import { ownerSession } from '../../services/owner-session.js'
import { pickupApi,PICKUP_SLOTS,PICKUP_LABELS,FUEL_VALUES,FUEL_LABELS } from '../../services/pickup.js'
import { imageApi,imageRequestKey } from '../../services/private-images.js'
import { createReservationReadFlow,initialReservationReadState,initialReservationWriteState,createReservationWriteFlow,displayTime } from '../../services/reservations.js'
const state=reactive(initialReservationReadState()),write=reactive(initialReservationWriteState()),form=reactive({reason:''});let order=0,role='owner',visible=false,generation=0,decision='CONFIRM'
const token=()=>role==='merchant'?merchantSession.accessToken:ownerSession.accessToken
const flow=createReservationReadFlow({state,token,request:t=>pickupApi.detail(t,order)})
const action=createReservationWriteFlow({state:write,token,newKey:imageRequestKey,body:()=>decision==='CONFIRM'?{decision}:{decision,reason:form.reason.trim()},request:(t,b,k)=>pickupApi.decide(t,order,b.decision,b.reason,k),onConflict:()=>flow.load()})
function confirm(){const actor=token(),version=generation;uni.showModal({title:'确认接车单',content:'确认后商家可继续派工，请先核对七张照片、里程及损伤记录。',success:r=>{if(r.confirm&&visible&&version===generation&&actor===token()){decision='CONFIRM';action.save()}}})}
function dispute(){decision='DISPUTE';action.save()}
async function preview(file){const actor=token(),version=generation;try{const signed=await pickupApi.access(actor,order,file);if(version===generation&&actor===token())await imageApi.preview(signed.url)}catch(error){if(version===generation&&actor===token())state.message=error?.message||'图片未打开，请重试'}}
watch(token,()=>{generation++;flow.reset();action.reset();if(visible&&token())flow.load()},{flush:'sync'});watch(()=>write.saved,saved=>{if(saved&&visible)flow.load()})
onLoad(q=>{order=Number(q?.id)||0;role=q?.role==='merchant'?'merchant':'owner'});onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;generation++;flow.suspend();action.suspend()});onUnload(()=>{generation++;flow.reset();action.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">接车单</text><button v-if="!token()" @tap="uni.navigateTo({url:`/pages/${role}/index`})">前往登录</button><text v-if="state.busy">正在加载接车单…</text><view v-if="state.message" class="reservation-panel"><text>{{state.message}}</text><button @tap="flow.load">重试 / 刷新</button></view>
  <view v-if="state.value" class="reservation-panel" data-testid="pickup-sheet"><text>接车单 #{{state.value.pickup_check_id}}</text><text>实到 {{displayTime(state.value.arrived_at)}}</text><text>手填里程 {{state.value.mileage}} km</text><text>比较基线 {{state.value.mileage_baseline?.mileage}} km · 差值 {{state.value.mileage_delta}} km</text><text v-if="state.value.mileage_reason">里程说明 {{state.value.mileage_reason}}</text><text>油量 {{FUEL_LABELS[FUEL_VALUES.indexOf(state.value.fuel_level)]}}</text><text>损伤 {{state.value.damage_status==='NONE'?'无损伤':'有损伤'}}</text><text v-for="(damage,index) in state.value.damages" :key="index">{{PICKUP_LABELS[damage.photo_slot]}} · 位置 {{(damage.x*100).toFixed(0)}}% / {{(damage.y*100).toFixed(0)}}%：{{damage.note}}</text><text v-if="state.value.arrival_reason">到店说明 {{state.value.arrival_reason}}</text><button v-for="slot in PICKUP_SLOTS" :key="slot" @tap="preview(state.value.photos[slot])">查看{{PICKUP_LABELS[slot]}}照片</button><template v-if="state.value.owner_confirm===0"><text>待车主确认；确认前不能派工。</text><view v-if="role==='owner'"><button :disabled="write.busy" @tap="confirm">确认接车单</button><textarea v-model="form.reason" maxlength="500" placeholder="如有异议，请填写具体问题（必填）" /><button :disabled="write.busy" @tap="dispute">提出异议</button></view></template><text v-else-if="state.value.owner_confirm===1">车主已于 {{displayTime(state.value.confirm_at)}} 确认接车单</text><view v-else><text>接车单有异议，已停止派工，请联系商家处理。</text><text>异议原因：{{state.value.dispute_reason}}</text></view><text v-if="write.message" role="status">{{write.message}}</text><button @tap="flow.load">刷新接车单</button></view>
</view></template>
<style src="../../styles/reservations.css"></style>
