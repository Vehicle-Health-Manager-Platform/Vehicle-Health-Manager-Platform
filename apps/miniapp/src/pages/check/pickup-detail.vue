<script setup>
import { reactive, watch } from 'vue'
import { onLoad,onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { merchantSession } from '../../services/merchant-session.js'
import { ownerSession } from '../../services/owner-session.js'
import { pickupApi,PICKUP_SLOTS,PICKUP_LABELS,FUEL_VALUES,FUEL_LABELS } from '../../services/pickup.js'
import { imageApi } from '../../services/private-images.js'
import { createReservationReadFlow,initialReservationReadState,displayTime } from '../../services/reservations.js'
const state=reactive(initialReservationReadState());let order=0,role='owner',visible=false,generation=0
const token=()=>role==='merchant'?merchantSession.accessToken:ownerSession.accessToken
const flow=createReservationReadFlow({state,token,request:t=>pickupApi.detail(t,order)})
async function preview(file){const actor=token(),version=generation;try{const signed=await pickupApi.access(actor,order,file);if(version===generation&&actor===token())await imageApi.preview(signed.url)}catch(error){if(version===generation&&actor===token())state.message=error?.message||'图片未打开，请重试'}}
watch(token,()=>{generation++;flow.reset();if(visible&&token())flow.load()},{flush:'sync'})
onLoad(q=>{order=Number(q?.id)||0;role=q?.role==='merchant'?'merchant':'owner'});onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;generation++;flow.suspend()});onUnload(()=>{generation++;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">接车单</text><button v-if="!token()" @tap="uni.navigateTo({url:`/pages/${role}/index`})">前往登录</button><text v-if="state.busy">正在加载接车单…</text><view v-if="state.message" class="reservation-panel"><text>{{state.message}}</text><button @tap="flow.load">重试 / 刷新</button></view>
  <view v-if="state.value" class="reservation-panel" data-testid="pickup-sheet"><text>接车单 #{{state.value.pickup_check_id}}</text><text>实到 {{displayTime(state.value.arrived_at)}}</text><text>手填里程 {{state.value.mileage}} km</text><text>比较基线 {{state.value.mileage_baseline?.mileage}} km · 差值 {{state.value.mileage_delta}} km</text><text v-if="state.value.mileage_reason">里程说明 {{state.value.mileage_reason}}</text><text>油量 {{FUEL_LABELS[FUEL_VALUES.indexOf(state.value.fuel_level)]}}</text><text>损伤 {{state.value.damage_status==='NONE'?'无损伤':'有损伤'}}</text><text v-for="(damage,index) in state.value.damages" :key="index">{{PICKUP_LABELS[damage.photo_slot]}} · 位置 {{(damage.x*100).toFixed(0)}}% / {{(damage.y*100).toFixed(0)}}%：{{damage.note}}</text><text v-if="state.value.arrival_reason">到店说明 {{state.value.arrival_reason}}</text><button v-for="slot in PICKUP_SLOTS" :key="slot" @tap="preview(state.value.photos[slot])">查看{{PICKUP_LABELS[slot]}}照片</button><text>待车主确认，确认功能随后接入。当前不能开始施工。</text><button @tap="flow.load">刷新接车单</button></view>
</view></template>
<style src="../../styles/reservations.css"></style>
