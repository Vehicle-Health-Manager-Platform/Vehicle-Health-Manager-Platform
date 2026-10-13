<script setup>
import { reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { createReviewSummaryFlow, initialReviewSummaryState } from '../../services/review-summary.js'
const state = reactive(initialReviewSummaryState())
let merchant = 0
const flow = createReviewSummaryFlow({ state, token: () => ownerSession.accessToken, merchant: () => merchant })
const source = id => state.value?.sources.find(s => s.public_id === id)
function login() { flow.reset(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
onLoad(q => { merchant = Number(q?.id) || 0 })
onShow(() => flow.reset())
onHide(() => flow.reset())
onUnload(() => flow.reset())
watch(() => ownerSession.accessToken, () => flow.reset(), { flush: 'sync' })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">评价原文要点</text>
    <text class="reservation-copy">从最近最多20条已获批且另行授权AI用途的有效文字选取完整原句。要点不是全面统计或平台质量认证，星级平均分另行查看。</text>
    <button :disabled="state.busy || !ownerSession.accessToken" @tap="flow.load()">{{ state.busy ? '正在生成并核对来源…' : '生成 / 刷新评价要点' }}</button>
    <view v-if="state.value" class="reservation-panel">
      <text v-if="state.value.status==='AI_READY'">AI选取的完整原句</text>
      <text v-else-if="state.value.status==='FALLBACK'">原文摘录：本次未使用成功的AI输出。</text>
      <text v-else-if="state.value.status==='SOURCE_CHANGED'">生成期间授权或来源已变化，已丢弃旧内容，请重新查询。</text>
      <text v-else-if="state.value.status==='DISABLED'">评价总结功能尚未开启，可返回查看公开文字。</text>
      <text v-else>暂无有效的AI用途授权文字。</text>
      <text v-if="state.value.sample_count">本次候选 {{ state.value.sample_count }} 条 · 当前符合AI用途的文字 {{ state.value.eligible_count }} 条；只覆盖最近20条。</text>
      <view v-for="(h,index) in state.value.highlights" :key="index" class="highlight">
        <text class="public-text">{{ h.quote }}</text>
        <text>来源全文 · 发布于 {{ source(h.public_id)?.published_date }}</text>
        <text class="public-text">{{ source(h.public_id)?.public_text }}</text>
      </view>
      <text>已查看的信息无法收回；授权及来源可能变化，下次查询会重新核对。</text>
    </view>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">登录车主账号</button>
    <button @tap="uni.navigateBack()">返回门店</button>
  </view>
</template>
<style scoped>.highlight{display:flex;flex-direction:column;gap:18rpx;padding:24rpx 0;border-top:1px solid #e5e6eb}.public-text{white-space:pre-wrap;overflow-wrap:anywhere}</style>
