<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { operatorSession, operatorToken, clearOperatorSession } from '../../services/operator-session.js'
import { createTextListFlow, initialTextListState, TEXT_REASONS } from '../../services/public-review-texts.js'
import { imageRequestKey } from '../../services/private-images.js'
import { moderationCardView, moderationConsoleCopy, reasonPicker, reasonLabel, toneClass } from '../../services/public-review-display.js'
const state = reactive(initialTextListState()), reasons = ref({})
// 理由 code 与可读文案分离：模板只渲染文案，提交时仍用 code。
const picker = reasonPicker(TEXT_REASONS)
const flow = createTextListFlow({ state, token: operatorToken, operator: true, newKey: imageRequestKey,
  confirm: (row, decision) => new Promise(resolve => uni.showModal({ title: decision === 'APPROVE' ? '确认完成隐私与内容审核' : '确认驳回', content: decision === 'APPROVE' ? '请确认已人工检查姓名、地址、联系信息、车辆标识及其他隐私，并核对内容是否适合公开。批准将公开车主授权的确切文字，不能代改。' : '按所选固定理由驳回，车主修改后须重新授权和审核。', success: r => resolve(!!r.confirm), fail: () => resolve(false) })) })
const consoleCopy = computed(() => moderationConsoleCopy(state.value))
const rows = computed(() => (state.value && Array.isArray(state.value.items) ? state.value.items : []).map(row => ({ ...row, view: moderationCardView(row) })))
const permission = computed(() => operatorSession.canReview
  ? { label: '具备审核权限', tone: 'ok', copy: '批准与驳回都会写审计与版本；同一决定重复提交按幂等重放处理。' }
  : { label: '无审核权限', tone: 'risk', copy: '当前账号没有文字审核权限，列表与按钮均不可用。' })
let visible = false
function clear() { flow.reset(); reasons.value = {} }
function login() { clear(); clearOperatorSession(); uni.redirectTo({ url: '/pages/operator/login' }) }
function select(row, event) { reasons.value[row.review_id] = picker.codes[Number(event.detail.value)] }
function act(row, decision) { const reason = decision === 'APPROVE' ? null : reasons.value[row.review_id]; if (decision === 'REJECT' && !reason) return; flow.moderate(row, decision, reason) }
onShow(() => { visible = true; if (!operatorToken()) { login(); return } flow.load() })
onHide(() => { visible = false; clear() })
onUnload(() => { visible = false; clear() })
watch(() => operatorSession.accessToken, () => { clear(); if (visible && operatorToken()) flow.load() }, { flush: 'sync' })
</script>
<template>
  <view class="reservation-page">
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">内容与授权审核</text>
      <text class="ux-title">公开评价文字审核</text>
      <text class="ux-lead">只查看车主独立授权的脱敏文本，不查看原私人评价或图片。规则不能覆盖所有语义隐私，批准前必须人工检查；文字不能代改。</text>
    </view>

    <view class="ux-card ux-in ux-r2">
      <view class="head">
        <text class="ux-card-title">待审队列</text>
        <view class="ux-badge" :class="toneClass(permission.tone)"><text>{{ permission.label }}</text></view>
      </view>
      <text class="ux-card-note">{{ permission.copy }}</text>
      <text v-if="consoleCopy.footer" class="ux-card-note queue-footer" data-testid="moderation-queue">{{ consoleCopy.footer }}</text>
    </view>

    <view v-if="state.busy" class="ux-card ux-in ux-r3"><text class="ux-card-note">正在核对待审文字…</text></view>

    <view v-if="consoleCopy.empty" class="ux-empty ux-in ux-r3" data-testid="moderation-empty">
      <text class="ux-empty-title">{{ consoleCopy.emptyTitle }}</text>
      <text class="ux-empty-copy">{{ consoleCopy.emptyCopy }}</text>
    </view>

    <view v-for="row in rows" :key="row.review_id" class="ux-card ux-in ux-r3" data-testid="moderation-row">
      <view class="head">
        <text class="ux-card-title">评价 #{{ row.review_id }}</text>
        <view class="ux-badge" :class="toneClass(row.view.tone)"><text>{{ row.view.label }}</text></view>
      </view>
      <text class="ux-card-note">{{ row.view.versionCopy }} · {{ row.view.copy }}</text>
      <view class="ux-divider"></view>
      <text class="public-text">{{ row.public_text }}</text>
      <button class="reservation-primary" :disabled="state.busy || !operatorSession.canReview || !row.can_approve" @tap="act(row,'APPROVE')">已人工检查，批准公开</button>
      <picker :range="picker.labels" :disabled="state.busy || !operatorSession.canReview" @change="select(row,$event)">
        <view class="reason">{{ reasonLabel(reasons[row.review_id], TEXT_REASONS) || picker.placeholder }} ›</view>
      </picker>
      <button :disabled="state.busy || !operatorSession.canReview || !reasons[row.review_id]" @tap="act(row,'REJECT')">按理由驳回 / 原请求重试</button>
    </view>

    <button v-if="state.value && state.value.items.length < state.value.total" :disabled="state.busy" @tap="flow.load(true)">加载下一页</button>
    <text v-if="state.message" class="status-line" role="status">{{ state.message }}</text>
    <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" class="reservation-primary" @tap="login">重新登录运营账号</button>
    <button :disabled="state.busy" @tap="flow.load(false)">刷新待审列表</button>
    <button @tap="uni.navigateBack()">返回运营审核</button>
  </view>
</template>
<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.queue-footer { margin-top: 16rpx; }
.public-text { white-space: pre-wrap; overflow-wrap: anywhere; color: #1a1d18; font-size: 28rpx; line-height: 1.7; }
.reason { padding: 22rpx; border: 1rpx solid #e0ded6; border-radius: 12rpx; font-size: 27rpx; color: #454a41; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
