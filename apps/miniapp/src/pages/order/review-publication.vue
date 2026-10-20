<script setup>
import { computed, reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { imageRequestKey } from '../../services/private-images.js'
import { createPublicationFlow, initialPublicationState } from '../../services/public-ratings.js'
import { publicationView, toneClass } from '../../services/public-review-display.js'
const state = reactive(initialPublicationState())
let order = 0, visible = false
const flow = createPublicationFlow({ state, token: () => ownerSession.accessToken, order: () => order, newKey: imageRequestKey,
  confirm: publish => new Promise(resolve => uni.showModal({ title: publish ? '确认公开星级' : '撤销公开星级', content: publish ? '向登录车主公开评分和评价日期，并计入商家平均评分。不会公开姓名、订单、正文或照片。你可以随时撤销。' : '撤销后停止公开，不删除本人原评价；已被查看的信息无法收回。', success: r => resolve(!!r.confirm), fail: () => resolve(false) })) })
// 测试订单与来源不合格要分开说明，撤销入口两种情形都在。
const statusView = computed(() => publicationView(state.value))
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
      <text class="ux-eyebrow">公开星级</text>
      <text class="ux-title">公开星级授权</text>
      <text class="ux-lead">只公开评分和评价日期，正文、照片与身份信息保持私有。公开星级会进入商家平均评分，但不代表平台质量认证；可随时撤销。</text>
    </view>

    <view v-if="state.value" class="ux-card ux-in ux-r2" data-testid="review-publication">
      <view class="head">
        <text class="ux-card-title">授权状态</text>
        <view class="ux-badge" :class="toneClass(statusView.tone)"><text>{{ statusView.label }}</text></view>
      </view>
      <text class="ux-card-note">{{ statusView.copy }}</text>
      <view class="ux-divider"></view>

      <view class="fact-row"><text class="fact-label">公开授权</text><text class="fact-value">{{ state.value.consented ? '已同意' : '未同意' }}</text></view>
      <view class="fact-row"><text class="fact-label">当前展示</text><text class="fact-value">{{ state.value.visible ? '公开并计入平均评分' : '未公开' }}</text></view>
      <view class="fact-row"><text class="fact-label">授权版本</text><text class="fact-value ux-num">{{ state.value.version }}</text></view>

      <button v-if="!state.value.consented && state.value.can_publish" class="reservation-primary" :disabled="state.busy" @tap="flow.save(true)">单独授权公开星级</button>
      <button v-if="state.value.consented" :disabled="state.busy" @tap="flow.save(false)">撤销公开授权</button>
      <button v-if="state.retryPublish !== null" :disabled="state.busy" @tap="flow.save(state.retryPublish)">按原请求重试（不使用当前版本）</button>
    </view>

    <text v-if="state.busy" class="status-line">正在核对授权状态…</text>
    <text v-if="state.message" class="status-line" role="status">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.failureKind)" class="reservation-primary" @tap="login">登录车主账号</button>
    <button :disabled="state.busy" @tap="flow.load()">刷新当前授权</button>
    <button @tap="uni.navigateBack()">返回本人评价</button>
  </view>
</template>

<style scoped>
/* 三个事实行左标签右取值：授权类信息比说明文字更需要快速扫读。 */
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.fact-row { display: flex; align-items: baseline; justify-content: space-between; gap: 16rpx; padding: 12rpx 0; border-top: 1rpx solid #e0ded6; }
.fact-row:first-of-type { border-top: 0; }
.fact-label { color: #63685e; font-size: 25rpx; }
.fact-value { color: #1a1d18; font-size: 27rpx; font-weight: 600; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
