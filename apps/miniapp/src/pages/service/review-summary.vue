<script setup>
import { computed, reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { createReviewSummaryFlow, initialReviewSummaryState } from '../../services/review-summary.js'
import { summaryView, summarySampleCopy, quoteSource, toneClass } from '../../services/public-review-display.js'
const state = reactive(initialReviewSummaryState())
let merchant = 0
const flow = createReviewSummaryFlow({ state, token: () => ownerSession.accessToken, merchant: () => merchant })
const statusView = computed(() => summaryView(state.value))
// 有要点才展开引用；其余状态只解释原因，不显示空列表。
const read = computed(() => {
  const value = state.value
  const quoted = ['AI_READY', 'FALLBACK'].includes(value?.status)
  return {
    quoted,
    rows: quoted ? (value.highlights || []).map(h => ({ key: `${h.public_id}|${h.quote}`, quote: h.quote, source: quoteSource(value.sources, h.public_id) })) : [],
    sample: quoted ? summarySampleCopy(value) : '',
  }
})
function login() { flow.reset(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
onLoad(q => { merchant = Number(q?.id) || 0 })
onShow(() => flow.reset())
onHide(() => flow.reset())
onUnload(() => flow.reset())
watch(() => ownerSession.accessToken, () => flow.reset(), { flush: 'sync' })
</script>

<template>
  <view class="reservation-page">
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">评价要点</text>
      <text class="ux-title">评价原文要点</text>
      <text class="ux-lead">从最近最多 20 条已获批、且车主另行授权 AI 用途的文字中选取完整原句。要点不是全面统计，也不代表平台质量认证；星级平均分需另行查看。</text>
    </view>

    <button class="reservation-primary" :disabled="state.busy || !ownerSession.accessToken" @tap="flow.load()">{{ state.busy ? '正在生成并核对来源…' : '生成 / 刷新评价要点' }}</button>

    <view v-if="state.value" class="ux-card ux-in ux-r2" data-testid="review-summary">
      <view class="head">
        <text class="ux-card-title">{{ statusView.title }}</text>
        <view class="ux-badge" :class="toneClass(statusView.tone)"><text>{{ state.value.status }}</text></view>
      </view>
      <text class="ux-card-note">{{ statusView.copy }}</text>

      <template v-if="read.quoted">
        <view class="ux-divider"></view>
        <view v-for="row in read.rows" :key="row.key" class="ux-row" :data-testid="`review-summary-quote-${row.key}`">
          <text class="quote">{{ row.quote }}</text>
          <view class="source">
            <text class="source-label">来源全文 · 发布于 {{ row.source.published_date }}</text>
            <text v-if="row.source.missing" class="source-missing">这段来源文字已撤回或停止展示，仅保留要点原句，请勿当作当前公开内容。</text>
            <text v-else class="source-text">{{ row.source.public_text }}</text>
          </view>
        </view>
        <text v-if="read.sample" class="ux-card-note">{{ read.sample }}</text>
        <text class="ux-card-note">已查看的信息无法收回；授权及来源可能变化，下次查询会重新核对。</text>
      </template>
    </view>

    <text v-if="state.message" class="status-line" role="status">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.failureKind)" class="reservation-primary" @tap="login">登录车主账号</button>
    <button @tap="uni.navigateBack()">返回门店</button>
  </view>
</template>

<style scoped>
/* 要点原句用引用感字重，来源全文退到次级字号，避免主次不分。 */
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.quote { color: #1a1d18; font-size: 30rpx; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.source { display: flex; flex-direction: column; gap: 8rpx; margin-top: 14rpx; padding: 18rpx; border-radius: 12rpx; background: #efede6; }
.source-label { color: #63685e; font-size: 23rpx; }
.source-text { color: #63685e; font-size: 25rpx; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.source-missing { color: #9a4700; font-size: 25rpx; line-height: 1.7; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
