<script setup>
import { reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { createTextListFlow, initialTextListState } from '../../services/public-review-texts.js'
const state = reactive(initialTextListState())
let merchant = 0, visible = false
const flow = createTextListFlow({ state, token: () => ownerSession.accessToken, merchant: () => merchant })
function login() { flow.reset(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
onLoad(q => { merchant = Number(q?.id) || 0 })
onShow(() => { visible = true; flow.load() })
onHide(() => { visible = false; flow.reset() })
onUnload(() => { visible = false; flow.reset() })
watch(() => ownerSession.accessToken, () => { flow.reset(); if (visible) flow.load() }, { flush: 'sync' })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">门店公开评价文字</text>
    <text class="reservation-copy">仅展示车主单独授权、经规则脱敏与人工审核且当前来源有效的文字。发布日期为UTC日，不代表平台质量认证。</text>
    <view v-if="state.value" class="reservation-panel">
      <text v-if="!state.value.items.length">暂无有效公开文字。</text>
      <view v-for="row in state.value.items" :key="row.public_id" class="text-row"><text class="public-text">{{ row.public_text }}</text><text>发布于 {{ row.published_date }}</text></view>
      <text>授权和资格变化可能影响分页，刷新可重新核对。</text>
      <button v-if="state.value.items.length < state.value.total" :disabled="state.busy" @tap="flow.load(true)">加载更多文字</button>
    </view>
    <text v-if="state.busy">正在加载公开文字…</text>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">登录车主账号</button>
    <button :disabled="state.busy" @tap="flow.load(false)">刷新公开文字</button>
    <button @tap="uni.navigateBack()">返回门店</button>
  </view>
</template>
<style scoped>.text-row{display:flex;flex-direction:column;gap:18rpx;padding:24rpx 0;border-top:1px solid #e0ded6}.public-text{white-space:pre-wrap;overflow-wrap:anywhere}</style>
