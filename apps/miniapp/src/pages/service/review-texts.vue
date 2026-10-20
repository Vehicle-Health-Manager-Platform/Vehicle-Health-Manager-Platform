<script setup>
import { computed, reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { createTextListFlow, initialTextListState } from '../../services/public-review-texts.js'
import { textListCopy } from '../../services/public-review-display.js'
const state = reactive(initialTextListState())
let merchant = 0, visible = false
const flow = createTextListFlow({ state, token: () => ownerSession.accessToken, merchant: () => merchant })
// 空态与页脚分开：没有内容时不要显示"共 0 条"这种看起来像出错的行。
const statusView = computed(() => textListCopy(state.value))
const rows = computed(() => state.value?.items || [])
const more = computed(() => rows.value.length < (state.value?.total || 0))
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
      <text class="ux-eyebrow">公开评价文字</text>
      <text class="ux-title">门店公开评价文字</text>
      <text class="ux-lead">只展示车主单独授权、经规则脱敏与人工审核、且当前来源仍然有效的文字。日期为 UTC 日，不代表平台质量认证。</text>
    </view>

    <view v-if="state.value" class="ux-card ux-in ux-r2" data-testid="public-review-texts">
      <view v-if="statusView.empty" class="ux-empty">
        <text class="ux-empty-title">{{ statusView.emptyTitle }}</text>
        <text class="ux-empty-copy">{{ statusView.emptyCopy }}</text>
      </view>

      <view v-if="!statusView.empty" class="list" data-testid="public-text-list">
        <view v-for="row in rows" :key="row.public_id" class="ux-row" :data-testid="`public-text-${row.public_id}`">
          <text class="public-text">{{ row.public_text }}</text>
          <view class="meta"><view class="ux-badge ux-badge-muted ux-num"><text>{{ row.published_date }}</text></view></view>
        </view>
      </view>

      <text v-if="statusView.footer" class="ux-card-note">{{ statusView.footer }}</text>
      <button v-if="more" :disabled="state.busy" @tap="flow.load(true)">加载更多文字</button>
    </view>

    <text v-if="state.busy" class="status-line">正在加载公开文字…</text>
    <view v-if="state.message" class="ux-card" role="status"><text>{{ state.message }}</text></view>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.failureKind)" class="reservation-primary" @tap="login">登录车主账号</button>
    <button :disabled="state.busy" @tap="flow.load(false)">刷新公开文字</button>
    <button @tap="uni.navigateBack()">返回门店</button>
  </view>
</template>

<style scoped>
/* 正文允许换行与长串断行：脱敏后可能残留长数字串。 */
.public-text { color: #1a1d18; font-size: 28rpx; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.meta { margin-top: 12rpx; }
/* 列表首行不重复分隔线。 */
.list .ux-row:first-child { border-top: 0; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
