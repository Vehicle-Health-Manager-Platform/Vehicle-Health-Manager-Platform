<script setup>
import { reactive, ref, watch } from 'vue'
import { onHide, onShow, onUnload } from '@dcloudio/uni-app'
import { clearTechnicianSession, technicianSession } from '../../services/technician-session.js'
import { assignmentStatusLabel, dispatchApi } from '../../services/technician-dispatch.js'
import { createQuoteListFlow, initialQuoteListState } from '../../services/quote-flow.js'
import { displayTime } from '../../services/reservations.js'
import { stateLabel } from '../../services/order-status.js'

const state = reactive(initialQuoteListState()), status = ref('')
let visible = false
const token = () => technicianSession.accessToken
const flow = createQuoteListFlow({ state, token, fetchPage: (actor, page) => dispatchApi.orders(actor, page, status.value) })

function changeStatus(value) { status.value = value; flow.reset(); if (token()) flow.load() }
function login() { clearTechnicianSession(); uni.navigateTo({ url: '/pages/technician/index' }) }
function open(row) { uni.navigateTo({ url: `/pages/technician/order-detail?id=${row.order_id}` }) }
watch(token, () => { flow.reset(); if (visible && token()) flow.load() }, { flush: 'sync' })
onShow(() => { visible = true; if (token()) flow.load() })
onHide(() => { visible = false; flow.suspend() })
onUnload(() => { visible = false; flow.reset() })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">我的工单</text>
    <button v-if="!technicianSession.accessToken" @tap="login">前往技师登录</button>
    <template v-else>
      <view class="filters">
        <button :class="{ selected: status === '' }" @tap="changeStatus('')">全部</button>
        <button :class="{ selected: status === 'ASSIGNED' }" @tap="changeStatus('ASSIGNED')">待接单</button>
        <button :class="{ selected: status === 'ACCEPTED' }" @tap="changeStatus('ACCEPTED')">已接单</button>
      </view>
      <text>只显示派给您本人的工单；「已接单」代表您接过该单，订单是否仍在施工以订单状态为准。</text>
      <text v-if="state.busy" role="status">正在加载本人工单…</text>
      <view v-if="state.message" class="reservation-panel" role="status">
        <text>{{ state.message }}</text>
        <text v-if="['unauthorized', 'forbidden'].includes(state.failureKind)" data-testid="technician-session-action">
          账号可能已被店长停用，或微信绑定已被撤销。请向店长索取新的员工码，然后重新绑定。
        </text>
        <button v-if="['unauthorized', 'forbidden'].includes(state.failureKind)" @tap="login">重新绑定员工码</button>
        <button v-else :disabled="state.busy" @tap="flow.retry">重试本人工单</button>
      </view>
      <view v-if="state.loaded && !state.items.length && !state.busy" class="reservation-panel" data-testid="technician-orders-empty">
        当前筛选条件下暂无派给您的工单；商家派工后会出现在这里。
      </view>
      <view v-for="row in state.items" :key="row.assignment_id" class="reservation-panel" :data-testid="`technician-order-${row.order_id}`">
        <text class="reservation-heading">{{ row.project_snapshot?.project_name || '历史订单' }}</text>
        <text>工单号 {{ row.order_no }}</text>
        <text>预约 {{ displayTime(row.appointment_snapshot?.starts_at) }} 至 {{ displayTime(row.appointment_snapshot?.ends_at) }}</text>
        <text data-testid="technician-assignment-status">{{ assignmentStatusLabel(row.assignment_status) }}</text>
        <text data-testid="technician-order-status">订单状态 {{ stateLabel(row.order_status) }}</text>
        <text v-if="row.can_accept" data-testid="technician-can-accept">可接单并开始施工</text>
        <button @tap="open(row)">查看工单详情</button>
      </view>
      <button v-if="state.items.length < state.total" :disabled="state.busy" @tap="flow.load(true)">加载更多工单</button>
      <button :disabled="state.busy" @tap="flow.load()">刷新本人工单</button>
    </template>
  </view>
</template>

<style scoped>.filters{display:flex;flex-wrap:wrap;gap:8rpx}.filters button{font-size:23rpx;margin:8rpx 0}.filters .selected{background:#00b42a;color:white}</style>
