<script setup>
import { reactive,watch } from 'vue'
import { onLoad,onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { ownerSession,clearOwnerSession } from '../../services/owner-session.js'
import { healthObservationApi,healthLabels,unitLabels,provenanceValues,provenanceLabels,observationStatus } from '../../services/health-observations.js'
import { createObservationListFlow,initialObservationListState } from '../../services/health-observation-flow.js'
const state=reactive(initialObservationListState())
const flow=createObservationListFlow({state,api:healthObservationApi,token:()=>ownerSession.accessToken})
let vehicle=0,visible=false
onLoad(q=>{vehicle=Number(q.vehicle_id)})
onShow(()=>{visible=true;flow.resume(vehicle)})
onHide(()=>{visible=false;flow.suspend()})
onUnload(()=>flow.suspend())
watch(()=>ownerSession.accessToken,()=>{flow.reset();if(visible)flow.resume(vehicle)},{flush:'sync'})
function login(){clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
function edit(row){uni.navigateTo({url:`/pages/archive/health-observation?vehicle_id=${vehicle}&archive_id=${row.archive_id}&metric=${row.metric}`})}
</script>
<template>
  <view class="page">
    <text class="title">六类本人当前读数</text>
    <text class="hint">未录入表示未知。本人读数不代表官方或门店核验，也不代表合格、健康分或适用车型结论；请核对原始资料与测量方法。</text>
    <text v-if="state.busy" class="hint">正在读取…</text>
    <text v-if="state.loaded" class="hint">核对日期：{{ state.today }}</text>
    <view v-for="row in state.rows" :key="row.metric" class="card">
      <text class="heading">{{ healthLabels[row.metric] }}</text><text class="hint">{{ observationStatus[row.status] }}</text>
      <template v-if="row.status==='RECORDED_UNVERIFIED'">
        <text class="reading">{{ row.measurement.measurement_name }}：{{ row.measurement.value }} {{ unitLabels[row.measurement.unit] }}</text>
        <text class="hint">测量/资料日期：{{ row.measurement.measured_on }} · {{ provenanceLabels[provenanceValues.indexOf(row.measurement.provenance)] }}</text>
        <text class="hint">方法/资料位置：{{ row.measurement.method }}</text><text class="hint">来源记录日期：{{ row.recorded_date || '未填写' }}</text>
        <button class="secondary" @tap="edit(row)">查看来源与修正</button>
      </template>
      <button v-else class="secondary" @tap="uni.switchTab({url:'/pages/archive/index'})">从本人保养或维修档案录入</button>
      <button class="secondary" @tap="uni.navigateTo({url:'/pages/archive/health-history?vehicle_id='+vehicle+'&metric='+row.metric})">历史与修正履历</button>
    </view>
    <text v-if="state.message" class="error">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken||['unauthorized','forbidden'].includes(state.kind)" @tap="login">登录车主账号</button>
    <button v-else :disabled="state.busy" @tap="flow.load">刷新当前读数</button>
  </view>
</template>
<style scoped>
.page{padding:32rpx}.title{display:block;font-size:36rpx;font-weight:700}.hint{display:block;margin-top:16rpx;color:#697585;font-size:24rpx;line-height:38rpx}.card{margin-top:24rpx;padding:28rpx;background:#fff;border-radius:20rpx}.heading{font-size:28rpx;font-weight:600}.reading{display:block;margin-top:18rpx;font-size:28rpx}.error{display:block;margin-top:20rpx;color:#b42318}button{margin-top:20rpx;background:#00b42a;color:#fff;border-radius:14rpx;font-size:26rpx}button::after{border:0}.secondary{background:#eef8f0;color:#008f24}
</style>
