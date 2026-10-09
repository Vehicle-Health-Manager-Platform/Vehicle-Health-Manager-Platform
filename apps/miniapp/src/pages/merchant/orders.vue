<script setup>
import { reactive, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { initialQuoteListState, createQuoteListFlow } from '../../services/quote-flow.js'
import { merchantOrdersApi } from '../../services/merchant-orders.js'
import { displayTime } from '../../services/reservations.js'

const state = reactive(initialQuoteListState()), status = ref(''), date = ref('')
let visible = false
const token = () => merchantSession.accessToken
const flow = createQuoteListFlow({ state, token, fetchPage: (actor, page) => merchantOrdersApi.list(actor, page, status.value, date.value) })
function changeStatus(value) { status.value = value; flow.reset(); if (token()) flow.load() }
function changeDate(event) { date.value = event.detail.value; flow.reset(); if (token()) flow.load() }
function clearDate() { date.value = ''; flow.reset(); if (token()) flow.load() }
function login() { clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
function open(row) { uni.navigateTo({ url: `/pages/merchant/order-detail?id=${row.order_id}` }) }
watch(token, () => { flow.reset(); if (visible && token()) flow.load() }, { flush: 'sync' })
onShow(() => { visible = true; if (token()) flow.load() })
onHide(() => { visible = false; flow.suspend() })
onUnload(() => { visible = false; flow.reset() })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">本店订单</text>
    <button v-if="!merchantSession.accessToken" @tap="login">前往商家登录</button>
    <template v-else>
      <view class="filters">
        <button :class="{ selected: status === '' }" @tap="changeStatus('')">全部</button>
        <button :class="{ selected: status === 'PENDING_PAYMENT' }" @tap="changeStatus('PENDING_PAYMENT')">待支付</button>
        <button :class="{ selected: status === 'PAID' }" @tap="changeStatus('PAID')">已支付</button>
        <button :class="{ selected: status === 'CLOSED' }" @tap="changeStatus('CLOSED')">已关闭</button>
      </view>
      <picker mode="date" :value="date" @change="changeDate"><view class="reservation-panel">预约日期：{{ date || '全部日期' }}</view></picker>
      <button v-if="date" @tap="clearDate">清除日期</button>
      <text v-if="state.busy" role="status">正在加载本店订单…</text>
      <view v-if="state.message" class="reservation-panel" role="status"><text>{{ state.message }}</text>
        <button v-if="['unauthorized', 'forbidden'].includes(state.failureKind)" @tap="login">重新登录</button>
        <button v-else :disabled="state.busy" @tap="flow.retry">重试本店订单</button>
      </view>
      <view v-if="state.loaded && !state.items.length && !state.busy" class="reservation-panel">当前筛选条件下暂无本店订单。</view>
      <view v-for="row in state.items" :key="row.order_id" class="reservation-panel" :data-testid="`merchant-order-${row.order_id}`">
        <text class="reservation-heading">{{ row.project_snapshot?.project_name || '历史订单' }}</text>
        <text>{{ row.status === 'PENDING_PAYMENT' ? '待支付' : row.status === 'PAID' ? '已支付' : row.status === 'CLOSED' ? '已关闭' : row.status }}</text>
        <text class="reservation-price">¥{{ row.amount_due }}</text>
        <text>预约 {{ displayTime(row.appointment_snapshot?.starts_at) }}</text>
        <text v-if="row.payment_summary?.test_mode">测试支付，未真实扣款</text>
        <text v-if="row.has_payment_exception">本订单存在付款异常，待核对</text>
        <button @tap="open(row)">查看订单详情</button>
      </view>
      <button v-if="state.items.length < state.total" :disabled="state.busy" @tap="flow.load(true)">加载更多本店订单</button>
    </template>
  </view>
</template>

<style scoped>.filters{display:flex;flex-wrap:wrap;gap:8rpx}.filters button{font-size:23rpx;margin:8rpx 0}.filters .selected{background:#00b42a;color:white}</style>
