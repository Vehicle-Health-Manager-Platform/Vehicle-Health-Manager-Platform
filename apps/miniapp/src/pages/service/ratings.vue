<script setup>
import { reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession } from '../../services/owner-session.js'
import { createRatingsFlow, initialRatingsState } from '../../services/public-ratings.js'
const state = reactive(initialRatingsState())
let merchant = 0, visible = false
const flow = createRatingsFlow({ state, token: () => ownerSession.accessToken, merchant: () => merchant })
onLoad(q => { merchant = Number(q?.id) || 0 })
onShow(() => { visible = true; flow.load() })
onHide(() => { visible = false; flow.reset() })
onUnload(() => { visible = false; flow.reset() })
watch(() => ownerSession.accessToken, () => { flow.reset(); if (visible) flow.load() }, { flush: 'sync' })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">门店公开星级</text>
    <text class="reservation-copy">仅统计单独授权、当前符合履约与资金资格的非测试评价。匿名展示评分及 UTC 评价日期；平均分不代表平台质量认证。</text>
    <view v-if="state.value" class="reservation-panel">
      <text v-if="state.value.summary.count">平均 {{ state.value.summary.average_rating }} / 5 分 · {{ state.value.summary.count }} 条有效公开评价</text>
      <text v-else>暂无有效公开评价，评分未知。</text>
      <view v-for="(row,index) in state.value.items" :key="index" class="rating-row"><text>{{ row.rating }} / 5 分 · {{ row.submitted_date }}</text></view>
      <text class="reservation-copy">授权或资格变化会改变样本；分页期间可能有变化，可刷新重新查看。</text>
      <button v-if="state.value.items.length < state.value.summary.count" :disabled="state.busy" @tap="flow.load(true)">加载更多星级</button>
    </view>
    <text v-if="state.busy">正在加载公开星级…</text>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button :disabled="state.busy" @tap="flow.load(false)">刷新公开星级</button>
    <button @tap="uni.navigateBack()">返回门店</button>
  </view>
</template>
<style scoped>.rating-row{padding:20rpx 0;border-top:1px solid #e0ded6}</style>
