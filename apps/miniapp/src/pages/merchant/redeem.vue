<script setup>
import { reactive, ref, computed, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { merchantOrdersApi } from '../../services/merchant-orders.js'
import { redemptionApi, createRedemptionFlow, initialRedemptionState } from '../../services/order-redemption.js'
import { createReservationReadFlow, initialReservationReadState } from '../../services/reservations.js'
import { imageRequestKey } from '../../services/private-images.js'
import {
  redeemOrderFacts, redeemQueryFailureView, redeemReceiptView, redeemStageView, redeemSubmitView,
} from '../../services/merchant-redemption-display.js'
import { toneClass } from '../../services/ui-tone.js'
const detail = reactive(initialReservationReadState()), receipt = reactive(initialReservationReadState()), state = reactive(initialRedemptionState())
const tick = ref(Date.now()), remaining = computed(() => Math.max(0, Math.ceil((state.waitUntil - tick.value) / 1000)))
let id = 0, visible = false, timer; const token = () => merchantSession.accessToken
const order = createReservationReadFlow({ state: detail, token, request: t => merchantOrdersApi.detail(t, id) })
const record = createReservationReadFlow({ state: receipt, token, request: t => redemptionApi.detail(t, 'merchant', id) })
async function refresh() { await Promise.all([order.load(), record.load()]) }
function confirm() { return new Promise(resolve => uni.showModal({ title: detail.value?.payment_summary?.test_mode ? '确认测试核销' : '确认核销', content: `订单 ${detail.value?.order_no}，应付 ¥${detail.value?.amount_due}。请核对车主出示的码。${detail.value?.payment_summary?.test_mode ? '未真实扣款，不可作为收款凭据。' : '核销后订单完成。'}`, success: r => resolve(r.confirm === true), fail: () => resolve(false) })) }
const submit = createRedemptionFlow({ state, token, order: () => id, api: redemptionApi, newKey: imageRequestKey, confirm, onChanged: refresh })
// 阶段、凭据、提交按钮与失败口径全部来自映射；模板不再写四层 else-if 与内联码校验。
// 凭据行有两处来源：读取结果 `receipt.value.redemption` 与刚提交成功的回执 `state.saved`
// （两者字段同为 {redeemed_at, test_mode}）。合并后阶段卡与凭据卡同步生效，不会出现
// "提交已成功、重新读取还没回来"时又冒出一个空验码框的空档。
const receiptRow = computed(() => receipt.value?.redemption || state.saved || null)
const stageView = computed(() => redeemStageView(detail.value, { loaded: receipt.loaded, redemption: receiptRow.value }))
const facts = computed(() => redeemOrderFacts(detail.value))
const receiptView = computed(() => redeemReceiptView(receiptRow.value))
const failure = computed(() => redeemQueryFailureView(detail, receipt))
const submitView = computed(() => redeemSubmitView(state.code, state, remaining.value))
function login() { clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
function stop() { visible = false; clearInterval(timer); submit.suspend(); order.reset(); record.reset() }
watch(token, () => { submit.reset(); order.reset(); record.reset(); if (visible && token()) { submit.resume(); refresh() } }, { flush: 'sync' })
onLoad(q => { id = Number(q?.id) || 0 })
onShow(() => { visible = true; clearInterval(timer); tick.value = Date.now(); timer = setInterval(() => { tick.value = Date.now() }, 1000); submit.resume(); if (token()) refresh() })
onHide(stop); onUnload(stop)
</script>
<template>
  <view class="reservation-page" data-testid="redeem-form">
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">门店工作台</text>
      <text class="ux-title">核销验码</text>
      <text class="ux-lead">核对车主出示的六位核销码后再提交；核销即完成订单，测试订单不会真实扣款。</text>
    </view>

    <view v-if="!merchantSession.accessToken" class="ux-card ux-in ux-r2" data-testid="redeem-login">
      <text class="ux-card-title">需要门店账号</text>
      <text class="ux-card-note">核销只能由接单门店操作。</text>
      <button class="reservation-primary" @tap="login">前往门店登录</button>
    </view>

    <template v-else>
      <text v-if="detail.busy || receipt.busy" class="status-line" role="status">正在核对订单与核销记录…</text>

      <view v-if="failure.message" class="ux-card ux-in ux-r2" role="status" data-testid="redeem-error">
        <view class="head">
          <text class="ux-card-title">{{ failure.title }}</text>
          <view class="ux-badge" :class="toneClass(failure.tone)"><text>{{ failure.canRelogin ? '需重新登录' : '可重试' }}</text></view>
        </view>
        <text class="ux-card-note">{{ failure.message }}</text>
        <button v-if="failure.canRelogin" class="reservation-primary" @tap="login">重新登录</button>
        <button v-else @tap="refresh">重试查询</button>
      </view>

      <template v-if="detail.value">
        <view class="ux-card ux-in ux-r2" data-testid="redeem-order">
          <view class="head">
            <text class="ux-card-title">{{ facts.project }}</text>
            <view class="ux-badge" :class="toneClass(facts.exceptionCopy ? 'risk' : 'muted')"><text>{{ facts.statusLabel }}</text></view>
          </view>
          <text class="ux-card-note">{{ facts.orderNoCopy }}</text>
          <text class="amount ux-num">{{ facts.amountCopy }}</text>
          <text v-if="facts.testModeCopy" class="ux-card-note" data-testid="redeem-test-warning">{{ facts.testModeCopy }}</text>
          <text v-if="facts.exceptionCopy" class="risk-line">{{ facts.exceptionCopy }}</text>
        </view>

        <view class="ux-card ux-in ux-r3" data-testid="redeem-stage">
          <view class="head">
            <text class="ux-card-title">{{ stageView.title }}</text>
            <view class="ux-badge" :class="toneClass(stageView.tone)"><text>{{ stageView.stage === 'ready' ? '待验码' : stageView.stage === 'redeemed' ? '已完成' : '只读' }}</text></view>
          </view>
          <text class="ux-card-note">{{ stageView.copy }}</text>
        </view>

        <view v-if="receiptView.exists" class="ux-card ux-in ux-r3" data-testid="redemption-receipt">
          <view class="head">
            <text class="ux-card-title">{{ receiptView.title }}</text>
            <view class="ux-badge" :class="toneClass(receiptView.tone)"><text>{{ receiptView.tone === 'warn' ? '测试' : '真实' }}</text></view>
          </view>
          <text class="ux-card-note">{{ receiptView.timeCopy }}</text>
          <text v-if="receiptView.testModeCopy" class="ux-card-note">{{ receiptView.testModeCopy }}</text>
        </view>

        <view v-if="stageView.stage === 'ready'" class="ux-card ux-in ux-r3" data-testid="redeem-form-card">
          <text class="ux-card-title">输入核销码</text>
          <text class="ux-card-note">{{ submitView.hint }}</text>
          <input class="field code" v-model="state.code" type="number" maxlength="6" :disabled="state.busy" placeholder="输入六位核销码" aria-label="六位核销码" data-testid="redeem-code" />
          <button class="reservation-primary" :disabled="!submitView.ready" :loading="state.busy" data-testid="redeem-submit" @tap="submit.submit">{{ submitView.label }}</button>
        </view>

        <text v-if="state.message" class="risk-line" role="status">{{ state.message }}</text>
        <button v-if="['unauthorized', 'forbidden'].includes(state.failureKind)" @tap="login">重新登录</button>
        <button :disabled="state.busy" @tap="refresh">刷新核销结果</button>
      </template>

      <button class="footer-back" @tap="uni.redirectTo({ url: `/pages/merchant/order-detail?id=${id}` })">返回订单详情</button>
    </template>
  </view>
</template>
<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.amount { display: block; margin-top: 12rpx; color: #1a1d18; font-size: 44rpx; font-weight: 700; }
.field { margin-top: 14rpx; padding: 22rpx; border: 1rpx solid #e0ded6; border-radius: 12rpx; font-size: 26rpx; color: #454a41; background: #efede6; }
.code { font-size: 34rpx; letter-spacing: 10rpx; }
.risk-line { display: block; margin-top: 12rpx; color: #b3261e; font-size: 25rpx; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
.footer-back { margin-top: 24rpx; }
</style>
