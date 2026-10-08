<script setup>
import { reactive, watch } from 'vue'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { clearTechnicianSession, technicianSession } from '../../services/technician-session.js'
import { assignmentStatusLabel, confirmStillHolds, dispatchApi } from '../../services/technician-dispatch.js'
import { imageRequestKey } from '../../services/private-images.js'
import { createReservationReadFlow, createReservationWriteFlow, displayTime, initialReservationReadState, initialReservationWriteState } from '../../services/reservations.js'
import { stateLabel } from '../../services/order-status.js'

const state = reactive(initialReservationReadState())
const write = reactive(initialReservationWriteState())
let orderId = 0, visible = false
const token = () => technicianSession.accessToken
const flow = createReservationReadFlow({ state, token, request: actor => dispatchApi.order(actor, orderId) })
// 接单是唯一可用的施工开始入口：商家通用 START_SERVICE 已被服务端停用。
const accept = createReservationWriteFlow({
  state: write, token, newKey: imageRequestKey,
  body: () => ({ order_id: orderId }),
  request: (actor, body, key) => dispatchApi.accept(actor, body.order_id, key),
  onConflict: () => flow.load(),
})

function login() { clearTechnicianSession(); uni.navigateTo({ url: '/pages/technician/index' }) }
function confirmAccept() {
  if (write.busy || write.saved || !state.value?.can_accept) return
  // 模态回调是异步的：期间可能切账号或离开页面，旧确认不得用新身份提交。
  const actor = token()
  uni.showModal({
    title: '接单并开始施工',
    content: `确认接受工单 ${state.value.order_no}？确认后订单进入「施工中」，责任技师为您本人。`,
    success: result => {
      if (!result.confirm) return
      if (!confirmStillHolds({ actor }, { visible, token: token() })) return
      accept.save()
    },
  })
}

watch(() => write.saved, saved => { if (saved && visible) flow.load() })
watch(token, () => { flow.reset(); accept.reset(); if (visible && token()) flow.load() }, { flush: 'sync' })
onLoad(query => { orderId = Number(query?.id) || 0 })
onShow(() => { visible = true; if (token()) flow.load() })
onHide(() => { visible = false; flow.suspend(); accept.suspend() })
onUnload(() => { visible = false; flow.reset(); accept.reset() })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">工单详情</text>
    <button v-if="!technicianSession.accessToken" @tap="login">前往技师登录</button>
    <text v-if="state.busy" role="status">正在加载工单…</text>
    <view v-if="state.message" class="reservation-panel" role="status">
      <text>{{ state.message }}</text>
      <button v-if="['unauthorized', 'forbidden'].includes(state.failureKind)" @tap="login">重新登录</button>
      <button v-else :disabled="state.busy" @tap="flow.load">重试工单详情</button>
    </view>
    <view v-if="state.value" class="reservation-panel" data-testid="technician-order-detail">
      <text class="reservation-heading">{{ state.value.project_snapshot?.project_name || '历史订单' }}</text>
      <text>{{ state.value.project_snapshot?.service_content || '服务内容未提供' }}</text>
      <text>工单号 {{ state.value.order_no }}</text>
      <text>预约 {{ displayTime(state.value.appointment_snapshot?.starts_at) }} 至 {{ displayTime(state.value.appointment_snapshot?.ends_at) }}</text>
      <text data-testid="technician-detail-assignment">{{ assignmentStatusLabel(state.value.assignment_status) }}</text>
      <text data-testid="technician-detail-status">订单状态 {{ stateLabel(state.value.order_status) }}</text>
      <text>派工时间 {{ displayTime(state.value.assigned_at) }}</text>
      <text v-if="state.value.accepted_at">接单时间 {{ displayTime(state.value.accepted_at) }}</text>

      <button v-if="state.value.can_accept && !write.saved" data-testid="technician-accept" :disabled="write.busy" :loading="write.busy" @tap="confirmAccept">接单并开始施工</button>
      <text v-else-if="write.saved" data-testid="technician-accepted">已接单，订单进入「施工中」。</text>
      <text v-else-if="state.value.assignment_status === 'ACCEPTED'">您已接单；订单后续状态以订单状态为准。</text>
      <text v-else-if="state.value.order_status !== 'RECEIVED'">订单当前为「{{ stateLabel(state.value.order_status) }}」，暂不可接单。</text>
      <text v-else>当前工单尚不可接单：需商家完成派工、接车检查与车主确认；如已确认仍不可接单，请刷新后重试。</text>

      <text v-if="write.message" role="status" data-testid="technician-accept-message">{{ write.message }}</text>
      <text>施工报工、配件与质检记录在后续阶段接入；「已接单」不等于施工完成。</text>
      <button :disabled="write.busy" @tap="flow.load">刷新工单</button>
    </view>
    <button @tap="uni.redirectTo({ url: '/pages/technician/orders' })">返回我的工单</button>
  </view>
</template>
<style src="../../styles/reservations.css"></style>
