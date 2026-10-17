<script setup>
import { reactive,watch } from 'vue'
import { onLoad,onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { ownerSession,clearOwnerSession } from '../../services/owner-session.js'
import { merchantDiscoveryApi } from '../../services/merchant-discovery.js'
import { createStorefrontFlow,initialStorefrontState } from '../../services/merchant-discovery-flow.js'
import { serviceCategories } from '../../services/service-catalog.js'
import { merchantTypeLabel } from '../../services/merchant-profile.js'
import { createDistanceFlow, initialDistanceState, distanceLabel } from '../../services/nearby-merchants.js'
const state=reactive(initialStorefrontState())
const flow=createStorefrontFlow({state,api:merchantDiscoveryApi,token:()=>ownerSession.accessToken})
const distance=reactive(initialDistanceState())
const distanceFlow=createDistanceFlow({state:distance,token:()=>ownerSession.accessToken,merchant:()=>state.merchant?.merchant_id,invalidate:()=>flow.reset()})
let visible=false
onLoad(q=>flow.route(Number(q?.id),Number(q?.project_id??0)))
onShow(()=>{visible=true;flow.resume()})
onHide(()=>{visible=false;flow.suspend();distanceFlow.reset()})
onUnload(()=>{visible=false;flow.suspend();distanceFlow.reset()})
watch(()=>ownerSession.accessToken,()=>{flow.reset();distanceFlow.reset();if(visible)flow.resume()},{flush:'sync'})
watch(()=>state.merchant,()=>distanceFlow.reset(),{flush:'sync'})
function login(){flow.reset();clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
function book(row){if(state.merchant&&!state.busy&&ownerSession.accessToken)uni.navigateTo({url:`/pages/order/book?id=${row.merchant_project_id}`})}
</script>
<template>
  <view class="page">
    <text class="title">商家详情</text>
    <text class="hint">门店资料与当前在售服务。预约时会重新核对报价和可用时段。</text>
    <view v-if="state.merchant" class="panel" data-testid="owner-storefront">
      <text class="name">{{ state.merchant.name }}</text>
      <text class="copy">{{ merchantTypeLabel(state.merchant.merchant_type) }}</text>
      <text class="copy">服务地址：{{ state.merchant.address }}</text>
      <text class="copy">{{ state.merchant.region_code?'门店已维护所属区域':'门店尚未维护所属区域，可按地址核对' }}</text>
      <!-- #ifdef MP-WEIXIN -->
      <button class="action" :disabled="distance.busy || state.busy" @tap="distanceFlow.load">{{ distance.busy ? '正在查询直线距离…' : '查看与我的直线距离' }}</button>
      <!-- #endif -->
      <text v-if="distance.result" class="copy">{{ distanceLabel(distance.result) }}</text>
      <text v-if="distance.message" class="error">{{ distance.message }}</text>
      <text class="hint">距离基于本次定位和店长确认的位置，仅为直线距离。营业时间暂未提供。</text>
      <button class="action" :disabled="state.busy" @tap="uni.navigateTo({url:`/pages/service/ratings?id=${state.merchant.merchant_id}`})">查看公开星级与平均评分</button>
      <button class="action" :disabled="state.busy" @tap="uni.navigateTo({url:`/pages/coupon/offers?merchant_id=${state.merchant.merchant_id}`})">查看本店可领券</button>
      <button class="action" :disabled="state.busy" @tap="uni.navigateTo({url:`/pages/service/review-texts?id=${state.merchant.merchant_id}`})">查看获批公开评价文字</button>
      <button class="action" :disabled="state.busy" @tap="uni.navigateTo({url:`/pages/service/review-summary?id=${state.merchant.merchant_id}`})">生成可核对来源的评价要点</button>
    </view>
    <view v-if="state.loaded" class="panel">
      <text class="heading">{{ state.projectId?'所选服务的当前报价':'本店在售服务' }}</text>
      <button v-if="state.projectId" class="action" :disabled="state.busy" @tap="flow.allProjects">查看本店全部在售服务</button>
      <text v-if="!state.items.length" class="copy">{{ state.projectId?'本店当前没有该服务的在售报价，可查看其他服务或返回列表。':'本店当前暂无在售服务。' }}</text>
      <view v-for="row in state.items" :key="row.merchant_project_id" class="offer" :data-testid="`storefront-offer-${row.merchant_project_id}`">
        <text class="name">{{ row.project_name }}</text><text class="copy">{{ serviceCategories[row.category] }}</text>
        <text class="price">报价 ¥{{ row.price }}</text>
        <text class="hint">最终按预约页重新读取的报价确认，下单还需校验时段名额。</text>
        <button class="primary" :disabled="state.busy" @tap="book(row)">预约此服务</button>
      </view>
      <button v-if="state.items.length<state.total" class="action" :disabled="state.busy" @tap="flow.load(true)">加载更多在售服务</button>
    </view>
    <text v-if="state.busy" class="hint">正在加载门店与报价…</text>
    <text v-if="state.message" class="error">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken||['unauthorized','forbidden'].includes(state.failureKind)" class="action" @tap="login">登录车主账号</button>
    <template v-else>
      <button v-if="state.message && !['invalid','missing'].includes(state.failureKind)" class="action" :disabled="state.busy" @tap="flow.retry">重试加载</button>
      <button class="action" :disabled="state.busy" @tap="flow.load(false)">刷新门店与报价</button>
    </template>
    <button class="action" @tap="uni.switchTab({url:'/pages/service/index'})">返回服务列表</button>
  </view>
</template>
<style scoped>
.page{padding:32rpx}.title{display:block;font-size:36rpx;font-weight:700}.panel{display:flex;flex-direction:column;margin-top:24rpx;padding:28rpx;background:#fff;border-radius:20rpx}.name{font-size:30rpx;font-weight:650}.heading{font-size:29rpx;font-weight:600}.copy,.hint{display:block;margin-top:16rpx;color:#697585;font-size:25rpx;line-height:38rpx}.hint{font-size:24rpx}.offer{margin-top:20rpx;padding:20rpx 0;border-top:1rpx solid #e5e6eb}.price{display:block;margin-top:20rpx;color:#008f24;font-size:30rpx}.error{display:block;margin-top:20rpx;color:#b42318}button{margin-top:20rpx;border-radius:14rpx;font-size:27rpx}button::after{border:0}.action{background:#eef8f0;color:#008f24}.primary{background:#00b42a;color:#fff}
</style>
