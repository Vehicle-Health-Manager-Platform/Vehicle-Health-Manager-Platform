<script setup>
import { reactive,watch } from 'vue'
import { onLoad,onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { ownerSession,clearOwnerSession } from '../../services/owner-session.js'
import { healthObservationApi,healthLabels,unitLabels,provenanceValues,provenanceLabels,observationStatus } from '../../services/health-observations.js'
import { createObservationListFlow,initialObservationListState } from '../../services/health-observation-flow.js'
import { createHealthDashboardFlow,initialHealthDashboardState,healthStateLabels } from '../../services/health-dashboard.js'
import { imageRequestKey } from '../../services/private-images.js'
import HealthTrend from '../../components/HealthTrend.vue'
const state=reactive(initialObservationListState())
const flow=createObservationListFlow({state,api:healthObservationApi,token:()=>ownerSession.accessToken})
let vehicle=0,visible=false
const assessment=reactive(initialHealthDashboardState())
const assessmentFlow=createHealthDashboardFlow({state:assessment,token:()=>ownerSession.accessToken,vehicle:()=>vehicle,newKey:imageRequestKey,confirm:(kind,v)=>new Promise(resolve=>uni.showModal({title:v.confirmed?'确认本次读数与规则用途':'撤销本次确认',content:v.confirmed?`请核对${healthLabels[v.row.metric]}读数 ${v.row.observation.measurement.value} ${unitLabels[v.row.observation.measurement.unit]}、方法/日期/资料和规则来源。确认后仅计算来源规则参考分，不代表官方核验或实际诊断。`:'撤销后该指标参考分与关联建议不可用，原始读数仍保留。',success:r=>resolve(!!r.confirm),fail:()=>resolve(false)}))})
onLoad(q=>{vehicle=Number(q.vehicle_id)})
onShow(()=>{visible=true;flow.resume(vehicle);assessmentFlow.load()})
onHide(()=>{visible=false;flow.suspend();assessmentFlow.reset()})
onUnload(()=>{flow.suspend();assessmentFlow.reset()})
watch(()=>ownerSession.accessToken,()=>{flow.reset();assessmentFlow.reset();if(visible){flow.resume(vehicle);assessmentFlow.load()}},{flush:'sync'})
function login(){clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
function edit(row){uni.navigateTo({url:`/pages/archive/health-observation?vehicle_id=${vehicle}&archive_id=${row.archive_id}&metric=${row.metric}`})}
</script>
<template>
  <view class="page">
    <text class="title">六类本人当前读数</text>
    <text class="hint">未录入表示未知。本人读数不代表官方或门店核验，也不代表合格、健康分或适用车型结论；请核对原始资料与测量方法。</text>
    <text v-if="state.busy" class="hint">正在读取…</text>
    <text v-if="state.loaded" class="hint">核对日期：{{ state.today }}</text>
    <view v-if="assessment.value" class="card">
      <text class="title">本车规则参考分：{{assessment.value.overall.score===null?'未知':assessment.value.overall.score}}</text>
      <text class="hint">适用性与有效确认资料必须完整；不补齐缺失分数。参考分不代替实际检查。</text>
      <text v-if="assessment.value.overall.score!==null" class="hint">加权分子 {{assessment.value.overall.weighted_sum}} / 权重 {{assessment.value.overall.weight_total}}，四舍五入。</text>
      <view v-for="m in assessment.value.metrics" :key="m.metric" class="card">
        <text class="heading">{{healthLabels[m.metric]}} · {{healthStateLabels[m.status]}}</text>
        <text v-if="m.rule" class="hint">来源：{{m.rule.source_reference}} · 规则版本 {{m.rule_version}} · 核对 {{m.rule.source_checked_on}} / 有效至 {{m.rule.valid_until}}</text>
        <text v-if="m.rule?.applicability==='APPLICABLE'" class="hint">规则口径 {{m.rule.measurement_name}} / {{unitLabels[m.rule.unit]}} / {{m.rule.method}}；有效 {{m.rule.max_age_days}} 天，权重 {{m.rule.weight}}</text>
        <text v-for="(band,i) in m.rule?.bands || []" :key="i" class="hint">{{band.upper_bound===null?'其余读数':'读数不超过 '+band.upper_bound}} → {{band.score}} 分</text>
        <text v-if="m.observation.status==='RECORDED_UNVERIFIED'" class="hint">本人原始读数：{{m.observation.measurement.value}} {{unitLabels[m.observation.measurement.unit]}} · {{m.observation.measurement.measured_on}} · 方法 {{m.observation.measurement.method}} · 来源 #{{m.observation.archive_id}} / 版本 {{m.observation.revision}}</text>
        <text v-if="m.score!==null" class="reading">当前指标参考分 {{m.score}}</text>
        <button v-if="m.status==='CONFIRMATION_REQUIRED'" :disabled="assessment.busy" @tap="assessmentFlow.confirmRow(m,true)">核对来源并确认本次参考用途</button>
        <button v-if="m.confirmation.confirmed" :disabled="assessment.busy" @tap="assessmentFlow.confirmRow(m,false)">撤销本次确认</button>
        <HealthTrend :trend="m.trend" :metric="m.metric" />
      </view>
    </view>
    <text v-if="assessment.message" class="error" role="status">{{assessment.message}}</text><button :disabled="assessment.busy" @tap="assessmentFlow.load()">刷新规则、确认与可比趋势</button>
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
.page{padding:32rpx}.title{display:block;font-size:36rpx;font-weight:700}.hint{display:block;margin-top:16rpx;color:#63685e;font-size:24rpx;line-height:38rpx}.card{margin-top:24rpx;padding:28rpx;background:#fcfbf8;border-radius:20rpx}.heading{font-size:28rpx;font-weight:600}.reading{display:block;margin-top:18rpx;font-size:28rpx}.error{display:block;margin-top:20rpx;color:#b3261e}button{margin-top:20rpx;background:#0b6b43;color:#fcfbf8;border-radius:14rpx;font-size:26rpx}button::after{border:0}.secondary{background:#e9f2ec;color:#074d30}
</style>
