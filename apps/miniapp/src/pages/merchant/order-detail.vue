<script setup>
import { reactive, watch, computed } from 'vue'
import CouponOrderSummary from '../../components/CouponOrderSummary.vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { merchantOrdersApi } from '../../services/merchant-orders.js'
import { imageRequestKey } from '../../services/private-images.js'
import { initialReservationReadState, initialReservationWriteState, createReservationReadFlow, createReservationWriteFlow, displayTime } from '../../services/reservations.js'
import { actionLabel, closeReasonLabel } from '../../services/order-status.js'
import { merchantActionHint, merchantEntryList, merchantOrderStatusView, merchantPaymentView, merchantPriceFacts, paymentExceptionCopy } from '../../services/merchant-order-display.js'
import { toneClass } from '../../services/ui-tone.js'

const state = reactive(initialReservationReadState())
const genericActions = computed(() => (state.value?.allowed_actions || []).filter(entry => entry.action !== 'COMPLETE'))
const statusView = computed(() => merchantOrderStatusView(state.value?.status))
const paymentView = computed(() => merchantPaymentView(state.value?.payment_summary))
const priceFacts = computed(() => merchantPriceFacts(state.value || {}))
// 入口矩阵与"下一步干什么"都来自映射：模板里不再有 7 组 includes(...)。
const entries = computed(() => merchantEntryList(state.value))
const actionHint = computed(() => merchantActionHint(state.value?.status))
const exceptionCopy = computed(() => paymentExceptionCopy(state.value))
const write = reactive(initialReservationWriteState())
const acting = { orderId: 0, action: '' }
let orderId = 0, visible = false
const token = () => merchantSession.accessToken
const flow = createReservationReadFlow({ state, token, request: actor => merchantOrdersApi.detail(actor, orderId) })
// 服务端是唯一权威：这里只提交动作名，目标状态由后端矩阵决定。
const apply = createReservationWriteFlow({
  state: write, token, newKey: imageRequestKey,
  body: () => ({ order_id: acting.orderId, action: acting.action }),
  request: (actor, body, key) => merchantOrdersApi.act(actor, body.order_id, body.action, undefined, key),
  onConflict: () => flow.load(),
})
function login() { clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
function open(entry) {
  const query = Object.entries({ id: orderId, ...entry.params }).map(([key, value]) => `${key}=${value}`).join('&')
  uni.navigateTo({ url: `${entry.route}?${query}` })
}
function run(entry) {
  uni.showModal({
    title: actionLabel(entry.action),
    content: `确认把本订单标记为「${merchantOrderStatusView(entry.to_status).label}」？操作会写入审计记录。`,
    success: result => {
      if (!result.confirm) return
      if (write.retry) { write.message = '请先核对或重试原履约请求，再执行其他动作'; return }
      if (acting.orderId !== orderId || acting.action !== entry.action) apply.reset()
      acting.orderId = orderId; acting.action = entry.action
      apply.save()
    },
  })
}
watch(() => write.saved, saved => { if (saved && visible) flow.load() })
watch(token, () => { flow.reset(); apply.reset(); if (visible && token()) flow.load() }, { flush: 'sync' })
onLoad(query => { orderId = Number(query?.id) || 0 })
onShow(() => { visible = true; if (token()) flow.load() })
onHide(() => { visible = false; flow.suspend(); apply.suspend() })
onUnload(() => { visible = false; flow.reset(); apply.reset() })
</script>
<template>
  <view class="reservation-page">
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">门店工作台</text>
      <text class="ux-title">本店订单详情</text>
      <text class="ux-lead">状态、金额与可执行动作都由服务端判定；本页只解释原因与提供入口。</text>
    </view>

    <button v-if="write.retry" class="reservation-primary" :disabled="write.busy" @tap="apply.retry">重试原核销请求（不使用当前编辑内容）</button>

    <view v-if="!merchantSession.accessToken" class="ux-card ux-in ux-r2" data-testid="merchant-order-login">
      <text class="ux-card-title">需要门店账号</text>
      <text class="ux-card-note">订单详情只能由本店账号查看。</text>
      <button class="reservation-primary" @tap="login">前往门店登录</button>
    </view>

    <text v-if="state.busy" class="status-line" role="status">正在加载订单…</text>

    <view v-if="state.message" class="ux-card ux-in ux-r2" role="status" data-testid="merchant-order-error">
      <view class="head">
        <text class="ux-card-title">加载未成功</text>
        <view class="ux-badge" :class="toneClass('risk')"><text>{{ ['unauthorized', 'forbidden'].includes(state.failureKind) ? '需要重新登录' : '可重试' }}</text></view>
      </view>
      <text class="ux-card-note">{{ state.message }}</text>
      <button v-if="['unauthorized', 'forbidden'].includes(state.failureKind)" class="reservation-primary" @tap="login">重新登录</button>
      <button v-else :disabled="state.busy" @tap="flow.load">重试订单详情</button>
    </view>

    <view v-if="state.value" class="ux-card ux-in ux-r2" data-testid="merchant-order-detail">
      <view class="head">
        <text class="ux-card-title">{{ state.value.project_snapshot?.project_name || '历史订单' }}</text>
        <view class="ux-badge" :class="toneClass(statusView.tone)"><text data-testid="merchant-order-status">{{ statusView.label }}</text></view>
      </view>
      <text class="ux-card-note">{{ statusView.copy }}</text>
      <text class="ux-card-note">{{ state.value.project_snapshot?.service_content || '服务内容未提供' }}</text>
      <view class="ux-divider"></view>

      <view class="fact"><text class="fact-label">应付金额</text><text class="fact-value ux-num">{{ priceFacts.amount }}</text></view>
      <view class="fact"><text class="fact-label">报价来源</text><text class="fact-value">{{ priceFacts.quoteVersion }} · {{ priceFacts.quotePrice }}</text></view>
      <view class="fact"><text class="fact-label">订单号</text><text class="fact-value ux-num">{{ state.value.order_no }}</text></view>
      <view class="fact"><text class="fact-label">预约时间</text><text class="fact-value">{{ displayTime(state.value.appointment_snapshot?.starts_at) }} 至 {{ displayTime(state.value.appointment_snapshot?.ends_at) }}</text></view>
      <view v-if="state.value.status === 'PENDING_PAYMENT'" class="fact"><text class="fact-label">支付期限</text><text class="fact-value">{{ displayTime(state.value.expires_at) }}</text></view>
      <view v-if="state.value.close_reason" class="fact"><text class="fact-label">关闭原因</text><text class="fact-value">{{ closeReasonLabel(state.value.close_reason) }}</text></view>
      <view class="fact"><text class="fact-label">门店</text><text class="fact-value">{{ state.value.merchant_snapshot?.merchant_name || '历史商家信息未提供' }}</text></view>
      <view class="fact"><text class="fact-label">地址</text><text class="fact-value">{{ state.value.merchant_snapshot?.address || '商家地址未提供' }}</text></view>

      <CouponOrderSummary v-if="state.value.coupon_summary" :summary="state.value.coupon_summary" />

      <view class="ux-divider"></view>
      <view class="head">
        <text class="ux-card-title">支付</text>
        <view class="ux-badge" :class="toneClass(paymentView.tone)" data-testid="merchant-payment-status"><text>{{ paymentView.label }}</text></view>
      </view>
      <text class="ux-card-note">{{ paymentView.copy }}</text>
      <text v-if="exceptionCopy" class="risk-line">{{ exceptionCopy }}</text>

      <text v-if="state.value.status === 'DISPUTED'" class="alert-line">车主已对接车单提出异议，派工与施工已阻断。请打开接车单提交处理记录；是否解除争议由车主复核决定，商家不能单方面恢复订单。</text>
      <text v-else-if="state.value.status === 'RECEIVED'" class="alert-line">已完成接车检查；车主确认后可派工给本店已绑定微信的技师，未确认或存在异议时服务端会拒绝派工。</text>
    </view>

    <view v-if="state.value" class="ux-card ux-in ux-r3" data-testid="merchant-order-actions">
      <view class="head">
        <text class="ux-card-title">可执行操作</text>
        <view class="ux-badge" :class="toneClass(genericActions.length ? 'warn' : 'muted')"><text>{{ genericActions.length ? genericActions.length + ' 项' : '无' }}</text></view>
      </view>
      <text class="ux-card-note">操作会记录操作人与时间；派工、报工、核销在各自步骤就绪前会给出具体原因。</text>
      <view class="ux-divider"></view>
      <button v-for="entry in genericActions" :key="entry.action" class="reservation-primary" :disabled="write.busy" :loading="write.busy" :data-testid="`order-action-${entry.action}`" @tap="run(entry)">{{ actionLabel(entry.action) }}</button>
      <text v-if="!genericActions.length" class="ux-card-note">{{ actionHint }}</text>
      <text v-if="write.message" class="risk-line" role="status">{{ write.message }}</text>
    </view>

    <view v-if="state.value" class="ux-card ux-in ux-r3" data-testid="merchant-order-entries">
      <text class="ux-card-title">相关处理</text>
      <text class="ux-card-note">按当前状态列出值得进入的下一步；能否执行仍由服务端在不满足前置条件时拒绝并说明原因。</text>
      <view class="ux-divider"></view>
      <button v-for="entry in entries" :key="entry.key" :data-testid="`merchant-order-${entry.key}`" @tap="open(entry)">{{ entry.label }}</button>
      <text v-if="!entries.length" class="ux-card-note">当前状态没有需要进入的处理页；如订单已关闭且涉及退款，请从车主侧退款流程发起。</text>
    </view>

    <button :disabled="write.busy || state.busy" @tap="flow.load">刷新订单状态</button>
    <button @tap="uni.redirectTo({ url: '/pages/merchant/orders' })">返回本店订单</button>
  </view>
</template>
<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.fact { display: flex; align-items: baseline; justify-content: space-between; gap: 20rpx; padding: 12rpx 0; border-top: 1rpx solid #efede6; }
.fact-label { flex: none; color: #63685e; font-size: 24rpx; }
.fact-value { color: #1a1d18; font-size: 26rpx; font-weight: 600; text-align: right; }
.risk-line { display: block; margin-top: 12rpx; color: #b3261e; font-size: 25rpx; }
.alert-line { display: block; margin-top: 16rpx; padding: 20rpx; border-radius: 12rpx; background: #f7ecdf; color: #9a4700; font-size: 25rpx; line-height: 1.65; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
