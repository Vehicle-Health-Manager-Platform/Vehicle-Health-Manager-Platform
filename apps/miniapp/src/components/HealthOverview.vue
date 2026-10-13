<script setup>
import { reactive, watch, onMounted } from 'vue'
import { onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { ownerSession } from '../services/owner-session.js'
import { createHealthDashboardFlow,initialHealthDashboardState,healthStateLabels,reminderLabels,reminderStateLabels } from '../services/health-dashboard.js'
import { healthLabels } from '../services/health-observations.js'
import { planBookingHandoff } from '../services/plan-booking-handoff.js'
import HealthScore from './HealthScore.vue'
const props=defineProps({vehicleId:{type:Number,required:true}}),state=reactive(initialHealthDashboardState())
const flow=createHealthDashboardFlow({state,token:()=>ownerSession.accessToken,vehicle:()=>props.vehicleId})
let visible=true
function load(){if(visible&&props.vehicleId&&ownerSession.accessToken)flow.load()}
watch(()=>props.vehicleId,()=>{flow.reset();load()},{flush:'sync'});watch(()=>ownerSession.accessToken,()=>{flow.reset();load()},{flush:'sync'})
onMounted(load);onShow(()=>{visible=true;load()});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
function action(a){if(a.type==='SERVICES')uni.switchTab({url:'/pages/service/index'});else if(a.type==='PROJECT'){const route=planBookingHandoff.route(a.project_id,props.vehicleId);if(route)uni.navigateTo({url:route})}else uni.navigateTo({url:`/pages/archive/${a.type==='TARGET'?'reminder':'structured'}?vehicle_id=${props.vehicleId}&archive_id=${a.archive_id}`})}
</script>
<template><view class="health-overview">
 <text class="heading">本车健康与提醒</text>
 <view v-if="state.value" class="health-content">
  <view class="score-row" @tap="uni.navigateTo({url:'/pages/archive/health?vehicle_id='+vehicleId})">
   <HealthScore :score="state.value.overall.score" />
   <text class="copy">{{state.value.overall.status==='REFERENCE_READY'?'由当前有效来源规则及本人确认读数加权计算，可查看证据和规则版本。':'有效规则或确认读数不完整，当前不计算全车参考分。'}}</text>
  </view>
  <text class="copy">本人资料与运营来源声明尚非官方核验，参考分不代替实际检查。</text>
  <text v-for="m in state.value.metrics.filter(x=>x.status!=='REFERENCE_READY'||x.score<100)" :key="m.metric" class="copy">{{healthLabels[m.metric]}} · {{healthStateLabels[m.status]}}{{m.score===null?'':' · 参考分 '+m.score}}</text>
  <button @tap="uni.navigateTo({url:'/pages/archive/health?vehicle_id='+vehicleId})">查看完整指标、规则与趋势</button>
  <view v-for="g in state.value.reminders" :key="g.kind" class="reminder-group">
   <text class="heading">{{reminderLabels[g.kind]}} · {{reminderStateLabels[g.status]}}</text>
   <view v-for="a in g.items.slice(0,5)" :key="a.archive_id" class="reminder-item">
    <text>{{a.title}}</text><text v-if="a.severity==='OWNER_URGENT'">本人标记需尽快检查</text>
    <text v-if="a.remaining_days!==null">距本人目标 {{a.remaining_days}} 天</text><text v-if="a.remaining_km!==null">距本人目标 {{a.remaining_km}} 公里</text>
    <text class="copy">来源：{{a.source_reference}} · 来源版本 {{a.source_revision}} / 提前偏好 {{a.preference_revision}}</text>
    <button @tap="action(a.action)">{{a.action.type==='PROJECT'?'查看本车当前报价与时段':a.action.type==='SERVICES'?'选择当前服务':'核对来源与处理记录'}}</button>
   </view>
   <button v-if="g.items.length>5||g.limited" @tap="uni.switchTab({url:'/pages/archive/index'})">查看更多本人记录（首页只展示部分）</button>
  </view>
  <button @tap="uni.switchTab({url:'/pages/archive/index'})">从本人档案设置或核对保养、保险、年检目标</button>
  <button @tap="uni.navigateTo({url:'/pages/archive/reminder-preference?vehicle_id='+vehicleId})">设置本人提前天数、里程与保养项目</button>
  <button @tap="uni.navigateTo({url:'/pages/archive/record-add?vehicle_id='+vehicleId+'&archive_type=10'})">录入本人故障来源</button>
  <button @tap="uni.navigateTo({url:'/pages/archive/record-add?vehicle_id='+vehicleId+'&archive_type=6'})">录入本人违章来源</button>
 </view>
 <text v-if="state.busy" role="status">正在读取当前来源与提醒…</text><text v-if="state.message" role="status">{{state.message}}</text><button :disabled="state.busy" @tap="flow.load()">刷新健康与提醒</button>
</view></template>
<style scoped>.health-overview{background:#fff;border-radius:24rpx;padding:28rpx;margin-top:24rpx;display:flex;flex-direction:column;gap:20rpx}.health-content{display:flex;flex-direction:column;gap:18rpx}.heading{font-size:28rpx;font-weight:600}.copy{font-size:24rpx;color:#697585;line-height:36rpx}.score-row{display:flex;align-items:center;gap:24rpx}.reminder-group,.reminder-item{display:flex;flex-direction:column;gap:12rpx;padding-top:20rpx;border-top:1px solid #e5e6eb;font-size:25rpx}button{font-size:25rpx;background:#eef8f0;color:#008f24;margin:12rpx 0 0}</style>
