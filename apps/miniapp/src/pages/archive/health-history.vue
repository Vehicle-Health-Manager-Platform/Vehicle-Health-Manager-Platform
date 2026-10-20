<script setup>
import { reactive,watch } from 'vue'
import { onLoad,onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { ownerSession,clearOwnerSession } from '../../services/owner-session.js'
import { healthObservationApi,healthLabels,unitLabels,provenanceLabels,provenanceValues,observationStatus,historyOperationLabels } from '../../services/health-observations.js'
import { initialHealthHistoryState,createHealthHistoryFlow } from '../../services/health-history-flow.js'
const state=reactive(initialHealthHistoryState())
const flow=createHealthHistoryFlow({state,api:healthObservationApi,token:()=>ownerSession.accessToken})
let vehicle=0,metric='',visible=false
onLoad(q=>{vehicle=Number(q.vehicle_id);metric=q.metric})
onShow(()=>{visible=true;flow.resume(vehicle,metric)})
onHide(()=>{visible=false;flow.suspend()})
onUnload(()=>{visible=false;flow.suspend()})
watch(()=>ownerSession.accessToken,()=>{flow.reset();if(visible)flow.resume(vehicle,metric)},{flush:'sync'})
function login(){flow.reset();clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
</script>
<template>
  <view class="page">
    <text class="title">{{ healthLabels[metric] || '本人读数' }} · 历史与修正</text>
    <text class="hint">按保存顺序从新到旧显示，测量日期另列。清空当前值后历史仍保留；被修正的原值也会保留。所有本人读数尚未核验。</text>
    <text class="hint">只有来源档案、测量名称、单位、方法及本人来源均相同才可比较。请逐条核对；此处不生成趋势、诊断或健康分。迁移快照不代表新增测量，此前履历未知。</text>
    <text v-if="state.loaded && !state.rows.length" class="hint">本类暂无历史记录</text>
    <view v-for="row in state.rows" :key="row.revision" class="card">
      <text class="heading">{{ historyOperationLabels[row.operation] }} · 版本 {{ row.revision }}</text>
      <text class="hint">保存时间（UTC）：{{ row.saved_at }}</text>
      <text v-if="row.supersedes_revision!==null" class="notice">修正版本 {{ row.supersedes_revision }}，原值保留</text>
      <text v-if="row.corrected" class="notice">本版本已被修正，请勿作为最终读数</text>
      <text class="hint">{{ observationStatus[row.status] }}</text>
      <template v-if="row.status==='RECORDED_UNVERIFIED'">
        <text class="reading">{{ row.measurement.measurement_name }}：{{ row.measurement.value }} {{ unitLabels[row.measurement.unit] }}</text>
        <text class="hint">测量/资料日期：{{ row.measurement.measured_on }}</text>
        <text class="hint">方法/资料位置：{{ row.measurement.method }}</text>
        <text class="hint">{{ provenanceLabels[provenanceValues.indexOf(row.measurement.provenance)] }} · 来源档案 {{ row.archive_id }}</text>
        <text class="hint">来源记录日期：{{ row.recorded_date || '未填写' }}</text>
        <button class="secondary" :disabled="state.busy" @tap="uni.navigateTo({url:'/pages/archive/health-observation?vehicle_id='+vehicle+'&archive_id='+row.archive_id+'&metric='+metric})">查看来源与当前读数</button>
      </template>
    </view>
    <text v-if="state.busy" class="hint">正在加载历史…</text>
    <text v-if="state.message" class="error">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken||['unauthorized','forbidden'].includes(state.kind)" @tap="login">登录车主账号</button>
    <template v-else>
      <button v-if="state.hasMore" :disabled="state.busy" @tap="flow.more">{{ state.kind?'重试加载更早记录':'加载更早记录' }}</button>
      <text v-else-if="state.loaded && state.rows.length" class="hint">已显示全部历史；来源或修正状态变化后请刷新</text>
      <button class="secondary" :disabled="state.busy" @tap="flow.load(false)">刷新历史与来源状态</button>
    </template>
  </view>
</template>
<style scoped>
.page{padding:32rpx}.title{display:block;font-size:36rpx;font-weight:700}.hint{display:block;margin-top:16rpx;color:#63685e;font-size:24rpx;line-height:38rpx}.card{margin-top:24rpx;padding:28rpx;background:#fcfbf8;border-radius:20rpx}.heading{font-size:28rpx;font-weight:600}.reading{display:block;margin-top:18rpx;font-size:28rpx}.notice{display:block;margin-top:16rpx;color:#9a4700;font-size:24rpx}.error{display:block;margin-top:20rpx;color:#b3261e}button{margin-top:20rpx;background:#0b6b43;color:#fcfbf8;border-radius:14rpx;font-size:26rpx}button::after{border:0}.secondary{background:#e9f2ec;color:#074d30}
</style>
