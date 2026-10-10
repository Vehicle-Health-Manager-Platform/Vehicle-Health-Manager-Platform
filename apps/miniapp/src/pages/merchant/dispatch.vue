<script setup>
import { reactive, ref, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { clearMerchantSession, merchantSession } from '../../services/merchant-session.js'
import { merchantOrdersApi } from '../../services/merchant-orders.js'
import { assignmentStatusLabel, confirmStillHolds, dispatchApi } from '../../services/technician-dispatch.js'
import { imageRequestKey } from '../../services/private-images.js'
import { createQuoteListFlow, initialQuoteListState } from '../../services/quote-flow.js'
import { createReservationReadFlow, createReservationWriteFlow, displayTime, initialReservationReadState, initialReservationWriteState } from '../../services/reservations.js'
import { stateLabel } from '../../services/order-status.js'

const order = reactive(initialReservationReadState())
const assignment = reactive(initialReservationReadState())
const candidates = reactive(initialQuoteListState())
const write = reactive(initialReservationWriteState())
const selected = ref(0)
// 上一次真正提交的目标；换人必须换幂等键，否则会被服务端判成「同键异体」。
const submitted = { technicianId: 0 }
let orderId = 0, visible = false
const token = () => merchantSession.accessToken

const orderFlow = createReservationReadFlow({ state: order, token, request: actor => merchantOrdersApi.detail(actor, orderId) })
const assignmentFlow = createReservationReadFlow({ state: assignment, token, request: actor => dispatchApi.assignment(actor, orderId) })
const candidateFlow = createQuoteListFlow({ state: candidates, token, fetchPage: (actor, page) => dispatchApi.candidates(actor, page) })
const assign = createReservationWriteFlow({
  state: write, token, newKey: imageRequestKey,
  body: () => ({ technician_id: selected.value }),
  request: (actor, body, key) => dispatchApi.assign(actor, orderId, body.technician_id, key),
  onConflict: () => { selected.value = 0; submitted.technicianId = 0; refresh() },
})

function login() { clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
function refresh() { orderFlow.load(); assignmentFlow.load() }
function pick(row) { if (!write.busy && !write.saved) selected.value = row.technician_id }
function labelOf(technicianId) { return candidates.items.find(row => row.technician_id === technicianId)?.label || `#${technicianId}` }

function confirmAssign() {
  if (write.busy || write.saved || !selected.value) return
  // 幂等键绑定调用者与目标。换人后必须重新开一次流程，否则旧键会撞上旧正文。
  if (submitted.technicianId !== selected.value) { assign.reset(); submitted.technicianId = selected.value }
  // 模态是异步的：期间可能切账号、离开页面或换人，回调必须校验当时的身份与目标。
  const actor = token(), target = selected.value, label = labelOf(target)
  uni.showModal({
    title: '确认派工',
    content: `确认把订单 ${order.value?.order_no || orderId} 派给技师「${label}」？派工后需由该技师本人接单并开始施工。`,
    success: result => {
      if (!result.confirm) return
      if (!confirmStillHolds({ actor, target }, { visible, token: token(), target: selected.value })) return
      assign.save()
    },
  })
}

watch(() => write.saved, saved => { if (saved && visible) refresh() })
watch(() => order.value, () => {
  if (!visible || order.value?.status !== 'RECEIVED' || assignment.value?.assignment || candidates.loaded || candidates.busy) return
  candidateFlow.load()
}, { flush: 'post' })
watch(token, () => {
  selected.value = 0; submitted.technicianId = 0
  orderFlow.reset(); assignmentFlow.reset(); candidateFlow.reset(); assign.reset()
  if (visible && token()) refresh()
}, { flush: 'sync' })

onLoad(query => { orderId = Number(query?.id) || 0 })
onShow(() => { visible = true; if (token()) refresh() })
onHide(() => { visible = false; orderFlow.suspend(); assignmentFlow.suspend(); candidateFlow.suspend(); assign.suspend() })
onUnload(() => {
  visible = false; selected.value = 0
  orderFlow.reset(); assignmentFlow.reset(); candidateFlow.reset(); assign.reset()
})
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">派工</text>
    <button v-if="!merchantSession.accessToken" @tap="login">前往门店登录</button>
    <template v-else>
      <text v-if="order.busy || assignment.busy" role="status">正在加载订单与派工状态…</text>
      <view v-if="order.message || assignment.message" class="reservation-panel" role="status">
        <text>{{ order.message || assignment.message }}</text>
        <button v-if="['unauthorized', 'forbidden'].includes(order.failureKind || assignment.failureKind)" @tap="login">重新登录</button>
        <button v-else :disabled="order.busy" @tap="refresh">重新加载派工信息</button>
      </view>

      <view v-if="order.value" class="reservation-panel" data-testid="dispatch-order">
        <text class="reservation-heading">{{ order.value.project_snapshot?.project_name || '历史订单' }}</text>
        <text>订单号 {{ order.value.order_no }}</text>
        <text>预约 {{ displayTime(order.value.appointment_snapshot?.starts_at) }}</text>
        <text data-testid="dispatch-order-status">{{ stateLabel(order.value.status) }}</text>
      </view>

      <!-- 已有派工：只读展示，服务端不允许客户端覆盖 -->
      <view v-if="assignment.value?.assignment" class="reservation-panel" data-testid="dispatch-existing">
        <text class="reservation-heading">已有派工</text>
        <text>技师 {{ assignment.value.assignment.technician_label }}</text>
        <text data-testid="dispatch-existing-status">{{ assignmentStatusLabel(assignment.value.assignment.status) }}</text>
        <text>派工时间 {{ displayTime(assignment.value.assignment.assigned_at) }}</text>
        <text v-if="assignment.value.assignment.accepted_at">接单时间 {{ displayTime(assignment.value.assignment.accepted_at) }}</text>
        <text v-else>技师尚未接单；一单仅一位技师，如需换人请联系管理员按后续流程处理。</text>
      </view>

      <view v-else-if="order.value?.status === 'DISPUTED'" class="reservation-panel">
        <text>车主已对接车单提出异议，派工与施工已阻断。请先在接车单中处理争议。</text>
      </view>
      <view v-else-if="order.value && order.value.status !== 'RECEIVED'" class="reservation-panel">
        <text>订单当前为「{{ stateLabel(order.value.status) }}」，尚未满足派工条件（需已接车且车主已确认）。</text>
      </view>
      <!-- 派工结果未读到之前不能给候选列表：否则可能对已有派工的订单重复提交，只能靠服务端 40905 兜底。 -->
      <view v-else-if="order.value?.status === 'RECEIVED' && !assignment.loaded" class="reservation-panel" role="status">
        <text>正在读取派工结果…</text>
        <button :disabled="assignment.busy" @tap="refresh">重新读取派工结果</button>
      </view>

      <template v-else-if="order.value?.status === 'RECEIVED'">
        <text class="reservation-heading">本店候选技师</text>
        <text>候选范围是已在本店完成微信绑定的在职技师；选择后需二次确认。</text>
        <text v-if="candidates.busy" role="status">正在加载候选技师…</text>
        <view v-if="candidates.message" class="reservation-panel" role="status">
          <text>{{ candidates.message }}</text>
          <button :disabled="candidates.busy" @tap="candidateFlow.retry">重试候选技师</button>
        </view>
        <view v-if="candidates.loaded && !candidates.items.length && !candidates.busy" class="reservation-panel" data-testid="dispatch-empty">
          暂无已绑定微信的有效技师，请联系管理员维护技师账号。
        </view>
        <view v-for="row in candidates.items" :key="row.technician_id" class="reservation-panel" :data-testid="`candidate-${row.technician_id}`">
          <text>{{ row.label }}</text>
          <text v-if="selected === row.technician_id" data-testid="candidate-selected">已选择</text>
          <button :disabled="write.busy || Boolean(write.saved)" @tap="pick(row)">{{ selected === row.technician_id ? '已选择该技师' : '选择该技师' }}</button>
        </view>
        <button v-if="candidates.items.length < candidates.total" :disabled="candidates.busy" @tap="candidateFlow.load(true)">加载更多候选技师</button>

        <button v-if="selected" data-testid="dispatch-submit" :disabled="write.busy || Boolean(write.saved)" :loading="write.busy" @tap="confirmAssign">
          派工给「{{ labelOf(selected) }}」
        </button>
        <text v-else>请先选择一位技师，再进行二次确认。</text>
        <text v-if="write.message" role="status" data-testid="dispatch-message">{{ write.message }}</text>
        <button v-if="write.saved" @tap="refresh">刷新派工结果</button>
      </template>

      <button :disabled="order.busy" @tap="refresh">刷新派工信息</button>
    </template>
    <button @tap="uni.redirectTo({ url: `/pages/merchant/order-detail?id=${orderId}` })">返回订单详情</button>
  </view>
</template>
