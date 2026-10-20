<script setup>
import { reactive, ref, watch, computed } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { clearMerchantSession, merchantSession } from '../../services/merchant-session.js'
import { merchantOrdersApi } from '../../services/merchant-orders.js'
import { confirmStillHolds, dispatchApi } from '../../services/technician-dispatch.js'
import { imageRequestKey } from '../../services/private-images.js'
import { createQuoteListFlow, initialQuoteListState } from '../../services/quote-flow.js'
import { createReservationReadFlow, createReservationWriteFlow, displayTime, initialReservationReadState, initialReservationWriteState } from '../../services/reservations.js'
import { candidateRowView, dispatchCandidateCopy, dispatchExistingFacts, dispatchStageView, dispatchSubmitCopy, shouldLoadCandidates } from '../../services/merchant-scheduling-display.js'
import { merchantOrderStatusView } from '../../services/merchant-order-display.js'
import { toneClass } from '../../services/ui-tone.js'

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

// 阶段、行视图与提交文案都来自映射：模板不再有 4 层 v-if 与 7 处状态字面量。
const stageView = computed(() => dispatchStageView(order.value, assignment))
const orderStatus = computed(() => merchantOrderStatusView(order.value?.status))
const existing = computed(() => dispatchExistingFacts(assignment.value))
const rows = computed(() => (Array.isArray(candidates.items) ? candidates.items : []).map(item => ({ item, view: candidateRowView(item, selected.value) })))
const candidateCopy = computed(() => dispatchCandidateCopy(candidates))
const submit = computed(() => dispatchSubmitCopy(selected.value, labelOf(selected.value)))

function login() { clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
function refresh() { orderFlow.load(); assignmentFlow.load() }
function pick(row) { if (!write.busy && !write.saved && !write.retry) selected.value = row.technician_id }
function labelOf(technicianId) { return candidates.items.find(row => row.technician_id === technicianId)?.label || `#${technicianId}` }

function confirmAssign() {
  if (write.busy || write.saved || write.retry || !selected.value) return
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
// 派工结果未读到之前不拉候选：否则可能对已有派工的订单重复提交，只能靠服务端 40905 兜底。
// 监听阶段而不是 order：阶段要到 assignment.loaded 之后才变 ready，那时才是该拉候选的时机。
watch(() => stageView.value.stage, () => {
  if (!visible || !shouldLoadCandidates(stageView.value.stage, candidates)) return
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
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">门店工作台</text>
      <text class="ux-title">派工</text>
      <text class="ux-lead">候选只含本店已绑定微信的在职技师。派工后必须由本人接单并开始施工，商家不能代接。</text>
    </view>

    <button v-if="write.retry" class="reservation-primary" :disabled="write.busy" @tap="assign.retry">重试原派工请求（不使用当前编辑内容）</button>

    <view v-if="!merchantSession.accessToken" class="ux-card ux-in ux-r2" data-testid="dispatch-login">
      <text class="ux-card-title">需要门店账号</text>
      <text class="ux-card-note">派工只能由本店账号执行。</text>
      <button class="reservation-primary" @tap="login">前往门店登录</button>
    </view>

    <template v-else>
      <text v-if="order.busy || assignment.busy" class="status-line" role="status">正在加载订单与派工状态…</text>

      <view v-if="order.message || assignment.message" class="ux-card ux-in ux-r2" role="status" data-testid="dispatch-error">
        <view class="head">
          <text class="ux-card-title">加载未成功</text>
          <view class="ux-badge" :class="toneClass('risk')"><text>{{ ['unauthorized', 'forbidden'].includes(order.failureKind || assignment.failureKind) ? '需重新登录' : '可重试' }}</text></view>
        </view>
        <text class="ux-card-note">{{ order.message || assignment.message }}</text>
        <button v-if="['unauthorized', 'forbidden'].includes(order.failureKind || assignment.failureKind)" class="reservation-primary" @tap="login">重新登录</button>
        <button v-else :disabled="order.busy" @tap="refresh">重新加载派工信息</button>
      </view>

      <view v-if="order.value" class="ux-card ux-in ux-r2" data-testid="dispatch-order">
        <view class="head">
          <text class="ux-card-title">{{ order.value.project_snapshot?.project_name || '历史订单' }}</text>
          <view class="ux-badge" :class="toneClass(orderStatus.tone)"><text data-testid="dispatch-order-status">{{ orderStatus.label }}</text></view>
        </view>
        <text class="ux-card-note">订单号 {{ order.value.order_no }} · 预约 {{ displayTime(order.value.appointment_snapshot?.starts_at) }}</text>
        <text class="ux-card-note">{{ orderStatus.copy }}</text>
      </view>

      <view v-if="order.value" class="ux-card ux-in ux-r3" :data-testid="'dispatch-stage-' + stageView.stage">
        <view class="head">
          <text class="ux-card-title">{{ stageView.title }}</text>
          <view class="ux-badge" :class="toneClass(stageView.tone)"><text>{{ stageView.stage === 'ready' ? '可派工' : stageView.stage === 'existing' ? '已派工' : '不可派工' }}</text></view>
        </view>
        <text class="ux-card-note">{{ stageView.copy }}</text>

        <!-- 已有派工：只读展示，服务端不允许客户端覆盖 -->
        <template v-if="stageView.stage === 'existing'">
          <view class="ux-divider"></view>
          <view data-testid="dispatch-existing">
            <view class="fact"><text class="fact-label">技师</text><text class="fact-value">{{ existing.technician }}</text></view>
            <view class="fact"><text class="fact-label">派工状态</text><text class="fact-value" data-testid="dispatch-existing-status">{{ existing.statusLabel }}</text></view>
            <view class="fact"><text class="fact-label">派工时间</text><text class="fact-value">{{ existing.assignedCopy }}</text></view>
            <view v-if="existing.acceptedCopy" class="fact"><text class="fact-label">接单时间</text><text class="fact-value">{{ existing.acceptedCopy }}</text></view>
            <text v-if="existing.pendingCopy" class="ux-card-note">{{ existing.pendingCopy }}</text>
          </view>
        </template>

        <button v-if="stageView.stage === 'loading'" :disabled="assignment.busy" @tap="refresh">重新读取派工结果</button>
        <button v-if="stageView.stage === 'disputed'" @tap="uni.navigateTo({ url: `/pages/check/pickup-detail?id=${orderId}&role=merchant` })">打开接车单处理争议</button>
      </view>

      <template v-if="stageView.stage === 'ready'">
        <text v-if="candidates.busy" class="status-line" role="status">正在加载候选技师…</text>

        <view v-if="candidates.message" class="ux-card ux-in ux-r3" role="status" data-testid="dispatch-candidates-error">
          <text class="ux-card-note">{{ candidates.message }}</text>
          <button :disabled="candidates.busy" @tap="candidateFlow.retry">重试候选技师</button>
        </view>

        <view v-if="candidateCopy.empty && candidates.loaded && !candidates.busy" class="ux-empty ux-in ux-r3" data-testid="dispatch-empty">
          <text class="ux-empty-title">{{ candidateCopy.emptyTitle }}</text>
          <text class="ux-empty-copy">{{ candidateCopy.emptyCopy }}</text>
        </view>

        <view v-for="row in rows" :key="row.item.technician_id" class="ux-card ux-in ux-r3" :data-testid="`candidate-${row.item.technician_id}`">
          <view class="head">
            <text class="ux-card-title">{{ row.view.label }}</text>
            <view v-if="row.view.selected" class="ux-badge" :class="toneClass('ok')" data-testid="candidate-selected"><text>已选择</text></view>
          </view>
          <button :class="{ 'reservation-primary': row.view.selected }" :disabled="write.busy || Boolean(write.saved)" @tap="pick(row.item)">{{ row.view.actionLabel }}</button>
        </view>

        <text v-if="candidateCopy.footer" class="status-line">{{ candidateCopy.footer }}</text>
        <button v-if="candidates.items.length < candidates.total" :disabled="candidates.busy" @tap="candidateFlow.load(true)">加载更多候选技师</button>

        <view class="ux-card ux-in ux-r4">
          <text class="ux-card-title">确认派工</text>
          <text class="ux-card-note">{{ submit.hint }}</text>
          <button v-if="submit.ready" data-testid="dispatch-submit" class="reservation-primary" :disabled="write.busy || Boolean(write.saved)" :loading="write.busy" @tap="confirmAssign">{{ submit.label }}</button>
          <text v-if="write.message" class="risk-line" role="status" data-testid="dispatch-message">{{ write.message }}</text>
          <button v-if="write.saved" @tap="refresh">刷新派工结果</button>
        </view>
      </template>

      <button :disabled="order.busy" @tap="refresh">刷新派工信息</button>
    </template>

    <button @tap="uni.redirectTo({ url: `/pages/merchant/order-detail?id=${orderId}` })">返回订单详情</button>
  </view>
</template>
<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.fact { display: flex; align-items: baseline; justify-content: space-between; gap: 20rpx; padding: 12rpx 0; border-top: 1rpx solid #efede6; }
.fact-label { flex: none; color: #63685e; font-size: 24rpx; }
.fact-value { color: #1a1d18; font-size: 26rpx; font-weight: 600; text-align: right; }
.risk-line { display: block; margin-top: 12rpx; color: #b3261e; font-size: 25rpx; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
