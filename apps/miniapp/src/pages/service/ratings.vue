<script setup>
import { computed, reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { createRatingsFlow, initialRatingsState } from '../../services/public-ratings.js'
import { ratingHeadline, ratingRow, toneClass } from '../../services/public-review-display.js'
const state = reactive(initialRatingsState())
let merchant = 0, visible = false
const flow = createRatingsFlow({ state, token: () => ownerSession.accessToken, merchant: () => merchant })
// 空样本给破折号与"暂无"，不让 null/NaN 漏到页面上。
const headline = computed(() => ratingHeadline(state.value?.summary))
// 视图模型：行文案与条形宽度都在这里定，模板只负责摆放。
const read = computed(() => {
  const rows = (state.value?.items || []).map(ratingRow)
  const total = Number.isFinite(state.value?.summary?.count) ? state.value.summary.count : 0
  return { empty: rows.length === 0, rows, more: rows.length < total }
})
function login() { flow.reset(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
onLoad(q => { merchant = Number(q?.id) || 0 })
onShow(() => { visible = true; flow.load() })
onHide(() => { visible = false; flow.reset() })
onUnload(() => { visible = false; flow.reset() })
watch(() => ownerSession.accessToken, () => { flow.reset(); if (visible) flow.load() }, { flush: 'sync' })
</script>

<template>
  <view class="reservation-page">
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">公开星级</text>
      <text class="ux-title">门店公开星级</text>
      <text class="ux-lead">仅统计车主单独授权、当前符合履约与资金资格的非测试评价。只展示评分与 UTC 日期，不含正文、照片与身份信息；平均分不代表平台质量认证。</text>
    </view>

    <view v-if="state.value" class="ux-card ux-in ux-r2" data-testid="public-ratings">
      <view class="ux-metric">
        <text class="ux-metric-label">平均分</text>
        <view class="score-row">
          <text class="ux-metric-value ux-num">{{ headline.value }}</text>
          <text v-if="headline.unit" class="ux-metric-unit">{{ headline.unit }}</text>
        </view>
      </view>
      <view class="badge-row"><view class="ux-badge" :class="toneClass(headline.tone)"><text>{{ headline.caption }}</text></view></view>
      <view class="ux-divider"></view>

      <view v-if="read.empty" class="ux-empty">
        <text class="ux-empty-title">暂无有效公开评价</text>
        <text class="ux-empty-copy">车主单独授权公开星级后才会出现在这里；授权撤销或来源核对不通过会立即移出统计。</text>
      </view>

      <view v-if="!read.empty" class="list">
        <view v-for="(row, index) in read.rows" :key="index" class="ux-row" :data-testid="`public-rating-${index}`">
          <view class="row-head">
            <text class="ux-row-title ux-num">{{ row.label }}</text>
            <text class="row-date ux-num">{{ row.date }}</text>
          </view>
          <view class="bar"><view class="bar-fill" :style="{ width: row.bar }"></view></view>
        </view>
      </view>

      <text v-if="!read.empty" class="ux-card-note">授权或资格变化会改变样本；分页期间可能有变化，刷新可重新核对。</text>
      <button v-if="read.more" :disabled="state.busy" @tap="flow.load(true)">加载更多星级</button>
    </view>

    <text v-if="state.busy" class="status-line">正在加载公开星级…</text>
    <view v-if="state.message" class="ux-card" role="status"><text>{{ state.message }}</text></view>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.failureKind)" class="reservation-primary" @tap="login">登录车主账号</button>
    <button :disabled="state.busy" @tap="flow.load(false)">刷新公开星级</button>
    <button @tap="uni.navigateBack()">返回门店</button>
  </view>
</template>

<style scoped>
/* 读数与条形：等宽数字对齐，条形只表达分级，不做精确刻度。 */
.score-row { display: flex; align-items: baseline; gap: 8rpx; }
.badge-row { margin-top: 16rpx; }
.row-head { display: flex; align-items: baseline; justify-content: space-between; gap: 16rpx; }
/* 列表首行不重复分隔线：卡片内已经有 divider。 */
.list .ux-row:first-child { border-top: 0; }
.row-date { color: #63685e; font-size: 23rpx; }
.bar { height: 8rpx; margin-top: 14rpx; border-radius: 999rpx; background: #efede6; overflow: hidden; }
.bar-fill { height: 100%; border-radius: 999rpx; background: #0b6b43; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
