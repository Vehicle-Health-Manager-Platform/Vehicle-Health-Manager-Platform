<script setup>
import { computed, reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { imageRequestKey } from '../../services/private-images.js'
import { createSummaryConsentFlow, initialSummaryConsentState } from '../../services/review-summary.js'
import { consentStatusView, toneClass } from '../../services/public-review-display.js'
const state = reactive(initialSummaryConsentState())
let order = 0, visible = false
const flow = createSummaryConsentFlow({ state, token: () => ownerSession.accessToken, order: () => order, newKey: imageRequestKey,
  confirm: (consent, text) => new Promise(resolve => uni.showModal({ title: consent ? '单独授权第三方AI用途' : '撤销AI用途授权', content: consent ? `同意将下列获批公开文字的原句发送至第三方DeepSeek以选取评价要点，并向登录车主展示摘录与来源。已经发送至第三方的内容无法收回。改稿需重新授权：\n${text}` : '停止后续AI用途和总结引用。公开文字与星级授权独立；已经发送至第三方或被查看的内容无法收回。', success: r => resolve(!!r.confirm), fail: () => resolve(false) })) })
// "授权已失效"必须能看出来：文字改版后旧授权不再生效。
const statusView = computed(() => consentStatusView(state.value))
function login() { flow.reset(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
onLoad(q => { order = Number(q?.id) || 0 })
onShow(() => { visible = true; flow.load() })
onHide(() => { visible = false; flow.reset() })
onUnload(() => { visible = false; flow.reset() })
watch(() => ownerSession.accessToken, () => { flow.reset(); if (visible) flow.load() }, { flush: 'sync' })
</script>

<template>
  <view class="reservation-page">
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">第三方 AI 用途</text>
      <text class="ux-title">评价 AI 用途授权</text>
      <text class="ux-lead">这一项把获批公开文字的原句发送给第三方 DeepSeek 以选取要点，并在门店总结中引用。它与公开文字、公开星级是三项独立授权；改稿或重新审批后旧授权会自动失效。</text>
    </view>

    <view v-if="state.value" class="ux-card ux-in ux-r2" data-testid="review-summary-consent">
      <view class="head">
        <text class="ux-card-title">授权状态</text>
        <view class="ux-badge" :class="toneClass(statusView.tone)"><text>{{ statusView.label }}</text></view>
      </view>
      <text class="ux-card-note">{{ statusView.copy }}</text>
      <view class="ux-divider"></view>

      <view v-if="state.publicText" class="text-block">
        <text class="text-label">当前批准的公开文字 · 版本 {{ state.value.text_version }}</text>
        <text class="public-text">{{ state.publicText }}</text>
      </view>
      <text v-else class="ux-card-note">尚未读取到已获批文字，可返回公开文字页查看当前状态。</text>

      <view v-if="!state.value.can_consent" class="ux-card-note">当前没有满足条件的获批文字，或总结功能尚未开启；已有授权仍可撤销。</view>
      <button v-if="state.value.can_consent && !state.value.effective" class="reservation-primary" :disabled="state.busy" @tap="flow.save(true)">{{ state.value.consented ? '按当前文字重新授权' : '确认当前文字的 AI 用途' }}</button>
      <button v-if="state.value.consented" :disabled="state.busy" @tap="flow.save(false)">撤销 AI 用途授权</button>
      <button v-if="state.retryConsent !== null" :disabled="state.busy" @tap="flow.save(state.retryConsent)">按原请求重试（不使用当前文字版本）</button>
    </view>

    <text v-if="state.busy" class="status-line">正在核对 AI 用途授权…</text>
    <text v-if="state.message" class="status-line" role="status">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.failureKind)" class="reservation-primary" @tap="login">登录车主账号</button>
    <button :disabled="state.busy" @tap="flow.load()">刷新当前文字与授权</button>
    <button @tap="uni.navigateBack()">返回公开文字</button>
  </view>
</template>

<style scoped>
/* 被授权的确切文字用浅底块隔离，避免与说明文字混读。 */
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.text-block { display: flex; flex-direction: column; gap: 10rpx; padding: 22rpx; border-radius: 14rpx; background: #efede6; }
.text-label { color: #63685e; font-size: 23rpx; }
.public-text { color: #1a1d18; font-size: 27rpx; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
