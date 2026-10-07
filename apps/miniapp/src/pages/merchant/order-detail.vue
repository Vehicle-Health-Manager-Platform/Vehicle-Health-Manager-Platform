<script setup>
import { reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { merchantOrdersApi } from '../../services/merchant-orders.js'
import { imageRequestKey } from '../../services/private-images.js'
import { initialReservationReadState, initialReservationWriteState, createReservationReadFlow, createReservationWriteFlow, displayTime } from '../../services/reservations.js'
import { stateLabel, actionLabel, closeReasonLabel } from '../../services/order-status.js'

const state = reactive(initialReservationReadState())
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
function run(entry) {
  uni.showModal({
    title: actionLabel(entry.action),
    content: `确认把本订单标记为「${stateLabel(entry.to_status)}」？操作会写入审计记录。`,
    success: result => {
      if (!result.confirm) return
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
    <text class="reservation-title">本店订单详情</text>
    <button v-if="!merchantSession.accessToken" @tap="login">前往商家登录</button>
    <text v-if="state.busy" role="status">正在加载订单…</text>
    <view v-if="state.message" class="reservation-panel" role="status"><text>{{ state.message }}</text>
      <button v-if="['unauthorized', 'forbidden'].includes(state.failureKind)" @tap="login">重新登录</button>
      <button v-else :disabled="state.busy" @tap="flow.load">重试订单详情</button>
    </view>
    <view v-if="state.value" class="reservation-panel" data-testid="merchant-order-detail">
      <text class="reservation-heading">{{ state.value.project_snapshot?.project_name || '历史订单' }}</text>
      <text>{{ state.value.project_snapshot?.service_content || '服务内容未提供' }}</text>
      <text>{{ state.value.merchant_snapshot?.merchant_name || '历史商家信息未提供' }}</text>
      <text>{{ state.value.merchant_snapshot?.address || '商家地址未提供' }}</text>
      <text class="reservation-price">应付 ¥{{ state.value.amount_due }}</text>
      <text>订单号 {{ state.value.order_no }}</text>
      <text>预约 {{ displayTime(state.value.appointment_snapshot?.starts_at) }} 至 {{ displayTime(state.value.appointment_snapshot?.ends_at) }}</text>
      <text>报价版本 {{ state.value.price_snapshot?.version || '未提供' }} · 下单价 ¥{{ state.value.price_snapshot?.price || state.value.amount_due }}</text>
      <text data-testid="merchant-order-status">{{ stateLabel(state.value.status) }}</text>
      <text v-if="state.value.status === 'PENDING_PAYMENT'">支付期限 {{ displayTime(state.value.expires_at) }}</text>
      <text v-if="state.value.close_reason">关闭原因 {{ closeReasonLabel(state.value.close_reason) }}</text>
      <view v-if="state.value.payment_summary"><text>支付状态 {{ state.value.payment_summary.status }}</text><text v-if="state.value.payment_summary.test_mode">测试支付，未真实扣款，不可作为收款凭据</text></view>
      <text v-if="state.value.has_payment_exception">本订单存在付款异常，待核对</text>
      <button v-if="state.value.status==='PAID'" @tap="uni.navigateTo({url:`/pages/merchant/pickup?id=${state.value.order_id}`})">接车检查</button>
      <button v-if="['RECEIVED','IN_SERVICE','PENDING_VERIFY','COMPLETED','DISPUTED'].includes(state.value.status)" @tap="uni.navigateTo({url:`/pages/check/pickup-detail?id=${state.value.order_id}&role=merchant`})">查看接车单</button>
      <view v-if="state.value.allowed_actions.length" data-testid="merchant-order-actions">
        <text class="reservation-heading">可执行操作</text>
        <text>操作会记录操作人与时间；车主确认、派工、报工、核销在各自步骤就绪前会给出具体原因。</text>
        <button v-for="entry in state.value.allowed_actions" :key="entry.action" :disabled="write.busy" :loading="write.busy" :data-testid="`order-action-${entry.action}`" @tap="run(entry)">{{ actionLabel(entry.action) }}</button>
      </view>
      <text v-else>当前状态在本店侧没有可执行的履约操作。</text>
      <text v-if="write.message" role="status">{{ write.message }}</text>
      <button :disabled="write.busy" @tap="flow.load">刷新订单状态</button>
    </view>
    <button @tap="uni.redirectTo({ url: '/pages/merchant/orders' })">返回本店订单</button>
  </view>
</template>
<style src="../../styles/reservations.css"></style>
