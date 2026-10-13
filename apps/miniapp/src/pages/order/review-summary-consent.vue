<script setup>
import { reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { imageRequestKey } from '../../services/private-images.js'
import { createSummaryConsentFlow, initialSummaryConsentState } from '../../services/review-summary.js'
const state = reactive(initialSummaryConsentState())
let order = 0, visible = false
const flow = createSummaryConsentFlow({ state, token: () => ownerSession.accessToken, order: () => order, newKey: imageRequestKey,
  confirm: (consent, text) => new Promise(resolve => uni.showModal({ title: consent ? '单独授权第三方AI用途' : '撤销AI用途授权', content: consent ? `同意将下列获批公开文字的原句发送至第三方DeepSeek以选取评价要点，并向登录车主展示摘录与来源。已经发送至第三方的内容无法收回。改稿需重新授权：\n${text}` : '停止后续AI用途和总结引用。公开文字与星级授权独立；已经发送至第三方或被查看的内容无法收回。', success: r => resolve(!!r.confirm), fail: () => resolve(false) })) })
function login() { flow.reset(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
onLoad(q => { order = Number(q?.id) || 0 })
onShow(() => { visible = true; flow.load() })
onHide(() => { visible = false; flow.reset() })
onUnload(() => { visible = false; flow.reset() })
watch(() => ownerSession.accessToken, () => { flow.reset(); if (visible) flow.load() }, { flush: 'sync' })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">评价AI用途授权</text>
    <text class="reservation-copy">此用途包括将获批公开文字原句发送至第三方DeepSeek选取要点，以及在门店总结中引用。须单独确认，改稿或重新审批后旧授权失效。</text>
    <view v-if="state.value" class="reservation-panel">
      <text>用途授权：{{ state.value.consented ? '已授权' : '未授权' }}</text>
      <text>当前有效：{{ state.value.effective ? '有效，可供总结引用' : '未生效' }}</text>
      <text v-if="state.publicText" class="public-text">{{ state.publicText }}</text>
      <text v-if="!state.value.can_consent">当前没有满足条件的获批文字，或总结功能尚未开启；已有授权仍可撤销。</text>
      <button v-if="state.value.can_consent && !state.value.effective" :disabled="state.busy" @tap="flow.save(true)">确认当前文字的AI用途</button>
      <button v-if="state.value.consented" :disabled="state.busy" @tap="flow.save(false)">撤销AI用途授权</button>
      <button v-if="state.retryConsent !== null" :disabled="state.busy" @tap="flow.save(state.retryConsent)">按原请求重试</button>
    </view>
    <text v-if="state.busy">正在核对AI用途授权…</text>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">登录车主账号</button>
    <button :disabled="state.busy" @tap="flow.load()">刷新当前文字与授权</button>
    <button @tap="uni.navigateBack()">返回公开文字</button>
  </view>
</template>
<style scoped>.public-text{white-space:pre-wrap;overflow-wrap:anywhere}</style>
