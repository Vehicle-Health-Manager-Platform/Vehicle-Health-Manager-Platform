<script setup>
import { reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { merchantOrdersApi } from '../../services/merchant-orders.js'
import { initialReservationReadState, createReservationReadFlow, displayTime } from '../../services/reservations.js'

const state = reactive(initialReservationReadState())
let orderId = 0, visible = false
const token = () => merchantSession.accessToken
const flow = createReservationReadFlow({ state, token, request: actor => merchantOrdersApi.detail(actor, orderId) })
function login() { clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
watch(token, () => { flow.reset(); if (visible && token()) flow.load() }, { flush: 'sync' })
onLoad(query => { orderId = Number(query?.id) || 0 })
onShow(() => { visible = true; if (token()) flow.load() })
onHide(() => { visible = false; flow.suspend() })
onUnload(() => { visible = false; flow.reset() })
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
      <text>{{ state.value.status === 'PENDING_PAYMENT' ? '待支付' : state.value.status === 'PAID' ? '已支付' : state.value.status === 'CLOSED' ? '已关闭' : state.value.status }}</text>
      <text v-if="state.value.status === 'PENDING_PAYMENT'">支付期限 {{ displayTime(state.value.expires_at) }}</text>
      <text v-if="state.value.status === 'CLOSED'">关闭原因 {{ state.value.close_reason === 'OWNER_CANCELLED' ? '车主取消' : state.value.close_reason === 'PAYMENT_EXPIRED' ? '未支付到期' : '其他' }}</text>
      <view v-if="state.value.payment_summary"><text>支付状态 {{ state.value.payment_summary.status }}</text><text v-if="state.value.payment_summary.test_mode">测试支付，未真实扣款，不可作为收款凭据</text></view>
      <text v-if="state.value.has_payment_exception">本订单存在付款异常，待核对</text>
      <button @tap="flow.load">刷新订单状态</button>
    </view>
    <button @tap="uni.redirectTo({ url: '/pages/merchant/orders' })">返回本店订单</button>
  </view>
</template>
<style src="../../styles/reservations.css"></style>
