<script setup>
import { computed, reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { imageRequestKey } from '../../services/private-images.js'
import { createTextFlow, initialTextState, TEXT_REASONS } from '../../services/public-review-texts.js'
import { textStatusView, draftMeter, toneClass } from '../../services/public-review-display.js'
const state = reactive(initialTextState())
let order = 0, visible = false
const flow = createTextFlow({ state, token: () => ownerSession.accessToken, order: () => order, newKey: imageRequestKey,
  confirm: (action, text) => new Promise(resolve => uni.showModal({ title: action === 'SUBMIT' ? '授权审核并公开这段文字' : '撤销公开文字', content: action === 'SUBMIT' ? `确认授权预览文字经人工审核后向登录车主公开，并展示发布日期：\n${text}` : '撤销后停止公开并清除当前公开文字。原私人评价和星级授权不变，已查看的信息无法收回。', success: r => resolve(!!r.confirm), fail: () => resolve(false) })) })
// 状态徽标与提示文案集中在一处，页面只负责摆放。
const statusView = computed(() => textStatusView(state.value, TEXT_REASONS))
const meter = computed(() => draftMeter(state.draft, 300))
const canPreview = computed(() => !!state.value?.can_submit && !state.busy && !meter.value.empty && !meter.value.over)
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
      <text class="ux-eyebrow">独立公开文字</text>
      <text class="ux-title">独立公开评价文字</text>
      <text class="ux-lead">这段文字与本人评价、照片完全分开。请只写服务体验，不要填写姓名、地址、联系方式、车牌或证件；提交前必须先看脱敏预览，获批后才会展示。</text>
    </view>

    <view v-if="state.value" class="ux-card ux-in ux-r2" data-testid="public-review-text-state">
      <view class="head">
        <text class="ux-card-title">当前状态</text>
        <view class="ux-badge" :class="toneClass(statusView.tone)"><text>{{ statusView.label }}</text></view>
      </view>
      <text class="ux-card-note">{{ statusView.copy }}</text>
      <view class="ux-divider"></view>

      <view v-if="state.value.public_text" class="current">
        <text class="current-label">最近一次提交的文字</text>
        <text class="current-text">{{ state.value.public_text }}</text>
      </view>

      <template v-if="state.value.can_submit">
        <text class="field-label">独立公开文本（1–300 字）</text>
        <textarea :value="state.draft" :disabled="state.busy" maxlength="600" class="reservation-field text-area" placeholder="只描述服务体验和处理结果" data-testid="public-review-text-draft" @input="flow.edit($event.detail.value)" />
        <text class="ux-card-note ux-num" :class="{ over: meter.over }">{{ meter.text }}{{ meter.over ? ' · 已超出上限，请精简' : '' }}</text>
        <button :disabled="!canPreview" @tap="flow.preview()">生成脱敏预览</button>

        <view v-if="state.preview" class="preview" data-testid="public-review-text-preview">
          <text class="preview-title">即将授权的确切文字</text>
          <text class="preview-text">{{ state.preview.public_text }}</text>
          <text class="preview-note">规则不能识别所有姓名、地址或变体信息，仍需人工审核。改动正文后必须重新预览；提交后先待审，获批才展示。</text>
          <button class="reservation-primary" :disabled="state.busy" @tap="flow.save('SUBMIT')">确认预览并授权审核</button>
        </view>
      </template>

      <!-- 撤销入口与状态文案解耦：只要有过提交（含被驳回）就允许清空。 -->
      <button v-if="!['NONE','WITHDRAWN'].includes(state.value.status)" :disabled="state.busy" @tap="flow.save('WITHDRAW')">撤销文字授权并清除</button>
      <button @tap="uni.navigateTo({url:`/pages/order/review-summary-consent?id=${order}`})">管理独立 AI 用途授权</button>
      <button v-if="state.retryAction" :disabled="state.busy" @tap="flow.save(state.retryAction)">按原请求重试（不使用当前编辑内容）</button>
    </view>

    <text v-if="state.busy" class="status-line">正在处理公开文字…</text>
    <text v-if="state.message" class="status-line" role="status">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.failureKind)" class="reservation-primary" @tap="login">登录车主账号</button>
    <button :disabled="state.busy" @tap="flow.load()">刷新授权与审核状态</button>
    <button @tap="uni.navigateBack()">返回本人评价</button>
  </view>
</template>

<style scoped>
/* 预览块用主色浅底强调"这就是将被公开的确切文字"，与输入区区分开。 */
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.field-label { color: #63685e; font-size: 25rpx; }
.text-area { box-sizing: border-box; width: 100%; min-height: 240rpx; margin-top: 0; }
.over { color: #b3261e; }
.preview { display: flex; flex-direction: column; gap: 14rpx; padding: 22rpx; border-radius: 14rpx; background: #e9f2ec; }
.preview-title { color: #074d30; font-size: 27rpx; font-weight: 650; }
.preview-text { color: #1a1d18; font-size: 28rpx; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.preview-note { color: #63685e; font-size: 23rpx; line-height: 1.65; }
.current { display: flex; flex-direction: column; gap: 8rpx; }
.current-label { color: #63685e; font-size: 23rpx; }
.current-text { color: #1a1d18; font-size: 26rpx; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
