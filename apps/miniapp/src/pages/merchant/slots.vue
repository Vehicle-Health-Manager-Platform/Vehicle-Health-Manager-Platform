<script setup>
import { reactive, ref, watch, computed } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { quotesApi } from '../../services/merchant-quotes.js'
import { initialQuoteListState, createQuoteListFlow } from '../../services/quote-flow.js'
import { imageRequestKey } from '../../services/private-images.js'
import { reservationsApi as api, ReservationError, initialReservationWriteState, createReservationWriteFlow, chinaDate } from '../../services/reservations.js'
import { slotFormFacts, slotListCopy, slotProjectOptions, slotRowView } from '../../services/merchant-scheduling-display.js'
import { toneClass } from '../../services/ui-tone.js'

const state = reactive(initialQuoteListState()), projects = reactive(initialQuoteListState()), write = reactive(initialReservationWriteState()), closing = reactive(initialReservationWriteState())
const form = reactive({ project: 0, day: chinaDate(new Date(Date.now() + 86400000)), start: '10:00', end: '11:00', capacity: '1' }), closeId = ref(0)
let visible = false
const token = () => merchantSession.accessToken
const flow = createQuoteListFlow({ state, token, fetchPage: api.ownSlots }), catalog = createQuoteListFlow({ state: projects, token, fetchPage: quotesApi.ownList })
const publish = createReservationWriteFlow({ state: write, token, newKey: imageRequestKey, body: () => { if (!form.project || !/^([1-9]|[1-9]\d|100)$/.test(form.capacity)) throw new ReservationError('invalid', '请选择在售项目，容量填写1–100'); return { standard_project_id: form.project, starts_at: `${form.day}T${form.start}:00+08:00`, ends_at: `${form.day}T${form.end}:00+08:00`, capacity: Number(form.capacity) } }, request: api.publish })
const close = createReservationWriteFlow({ state: closing, token, newKey: imageRequestKey, body: () => ({ slot_id: closeId.value }), request: (t, b, k) => api.close(t, b.slot_id, k) })
// 选项、行视图与空态都来自映射；模板不再 filter / 拼状态文案。
const options = computed(() => slotProjectOptions(projects.items, form.project))
const rows = computed(() => (Array.isArray(state.items) ? state.items : []).map(item => ({ item, view: slotRowView(item) })))
const listCopy = computed(() => slotListCopy(state))
const facts = computed(() => slotFormFacts(form))
function reset() { flow.reset(); catalog.reset(); publish.reset(); close.reset(); form.project = 0; closeId.value = 0 }
function login() { clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
function closeSlot(row) { if (closing.retry && closeId.value !== row.slot_id) { closing.message = '请先核对或重试原关闭请求'; return } uni.showModal({ title: '关闭预约时段', content: '关闭后停止新预约，已有订单保持原约定。', success: r => { if (r.confirm) { if (closeId.value !== row.slot_id) close.reset(); closeId.value = row.slot_id; close.save() } } }) }
watch(() => write.saved, r => { if (r && visible) flow.load() })
watch(() => closing.saved, r => { if (r && visible) flow.load() })
watch(token, () => { reset(); if (visible && token()) { flow.load(); catalog.load() } }, { flush: 'sync' })
onShow(() => { visible = true; if (token()) { flow.load(); catalog.load() } })
onHide(() => { visible = false; flow.suspend(); catalog.suspend(); publish.suspend(); close.suspend() })
onUnload(() => { visible = false; reset() })
</script>
<template>
  <view class="reservation-page">
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">门店工作台</text>
      <text class="ux-title">本店预约时段</text>
      <text class="ux-lead">名额是"未关闭订单数"的剩余量，由服务端在时段行上加减；关闭只停止新预约，不影响已有订单。</text>
    </view>

    <button v-if="write.retry" class="reservation-primary" :disabled="write.busy" @tap="publish.retry">重试原时段发布请求（不使用当前编辑内容）</button>
    <button v-if="closing.retry" class="reservation-primary" :disabled="closing.busy" @tap="close.retry">重试原时段关闭请求（不使用当前编辑内容）</button>

    <view v-if="!merchantSession.accessToken" class="ux-card ux-in ux-r2" data-testid="slots-login">
      <text class="ux-card-title">需要门店账号</text>
      <text class="ux-card-note">时段只能由本店账号维护。</text>
      <button class="reservation-primary" @tap="login">前往门店登录</button>
    </view>

    <template v-else>
      <view class="ux-card ux-in ux-r2" data-testid="slot-publish">
        <view class="head">
          <text class="ux-card-title">发布项目时段</text>
          <view class="ux-badge" :class="toneClass(facts.ready ? 'ok' : 'warn')"><text>{{ facts.ready ? '可发布' : '待补全' }}</text></view>
        </view>
        <text class="ux-card-note">未来 30 天内、同一天；同项目时段不可重叠。发布后时间和容量不可编辑。</text>
        <view class="ux-divider"></view>

        <text class="label">选择在售项目</text>
        <view class="projects">
          <button v-for="option in options" :key="option.id" :class="{ on: option.selected }" :disabled="write.busy || !!write.saved" :data-testid="`slot-project-${option.id}`" @tap="form.project = option.id">{{ option.selected ? '✓ ' : '' }}{{ option.label }}</button>
        </view>
        <text v-if="projects.loaded && !options.length" class="ux-card-note" data-testid="slot-no-project">暂无在售项目，请先维护本店报价。</text>
        <text v-if="projects.message" class="risk-line">{{ projects.message }}</text>
        <button v-if="projects.message" @tap="catalog.retry">重试本店选品</button>
        <button v-if="projects.items.length < projects.total" @tap="catalog.load(true)">加载更多本店选品</button>

        <text class="label">时间与名额</text>
        <picker mode="date" :value="form.day" :start="chinaDate()" :end="chinaDate(new Date(Date.now() + 30 * 86400000))" :disabled="write.busy || !!write.saved" @change="form.day = $event.detail.value">
          <view class="field">日期 {{ form.day }}</view>
        </picker>
        <view class="row-2">
          <picker mode="time" :value="form.start" :disabled="write.busy || !!write.saved" @change="form.start = $event.detail.value"><view class="field">开始 {{ form.start }}</view></picker>
          <picker mode="time" :value="form.end" :disabled="write.busy || !!write.saved" @change="form.end = $event.detail.value"><view class="field">结束 {{ form.end }}</view></picker>
        </view>
        <input v-model="form.capacity" class="field capacity" type="number" maxlength="3" :disabled="write.busy || !!write.saved" placeholder="预约名额（1–100）" />
        <text class="ux-card-note">{{ facts.windowCopy }} · {{ facts.capacityCopy }}</text>
        <text class="ux-card-note">{{ facts.hint }}</text>

        <text v-if="write.message" class="risk-line" role="status">{{ write.message }}</text>
        <button v-if="!write.saved" class="reservation-primary" :disabled="write.busy" :loading="write.busy" @tap="publish.save">发布预约时段</button>
        <button v-else @tap="publish.reset">继续发布时段</button>
      </view>

      <text v-if="state.busy" class="status-line" role="status">正在加载本店时段…</text>

      <view v-if="state.message" class="ux-card ux-in ux-r3" role="status" data-testid="slots-error">
        <view class="head">
          <text class="ux-card-title">加载未成功</text>
          <view class="ux-badge" :class="toneClass('risk')"><text>{{ ['unauthorized', 'forbidden'].includes(state.failureKind) ? '需重新登录' : '可重试' }}</text></view>
        </view>
        <text class="ux-card-note">{{ state.message }}</text>
        <button v-if="['unauthorized', 'forbidden'].includes(state.failureKind)" class="reservation-primary" @tap="login">重新登录</button>
        <button v-else @tap="flow.retry">重试本店时段</button>
      </view>

      <view v-if="listCopy.empty && !state.busy && !state.message" class="ux-empty ux-in ux-r3" data-testid="slots-empty">
        <text class="ux-empty-title">{{ listCopy.emptyTitle }}</text>
        <text class="ux-empty-copy">{{ listCopy.emptyCopy }}</text>
      </view>

      <text v-if="closing.message" class="risk-line" role="status">{{ closing.message }}</text>

      <view v-for="row in rows" :key="row.item.slot_id" class="ux-card ux-in ux-r3" :data-testid="`merchant-slot-${row.item.slot_id}`">
        <view class="head">
          <text class="ux-card-title">{{ row.view.project }}</text>
          <view class="ux-badge" :class="toneClass(row.view.tone)"><text>{{ row.view.statusLabel }}</text></view>
        </view>
        <text class="ux-card-note">{{ row.view.window }}</text>
        <view class="capacity-row">
          <text class="capacity ux-num">{{ row.view.capacityCopy }}</text>
          <view v-if="row.view.full" class="ux-badge" :class="toneClass('warn')"><text>已约满</text></view>
        </view>
        <text v-if="row.view.note" class="ux-card-note">{{ row.view.note }}</text>
        <button v-if="row.item.open" :disabled="closing.busy" @tap="closeSlot(row.item)">关闭预约时段</button>
      </view>

      <text v-if="listCopy.footer" class="status-line">{{ listCopy.footer }}</text>
      <button v-if="state.items.length < state.total" :disabled="state.busy" @tap="flow.load(true)">加载更多本店时段</button>
    </template>
  </view>
</template>
<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.label { display: block; margin-top: 20rpx; color: #63685e; font-size: 24rpx; letter-spacing: 1rpx; }
.projects { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 14rpx; }
.projects button { min-height: 64rpx; padding: 0 26rpx; margin: 0; font-size: 25rpx; color: #454a41; background: #efede6; border: 1rpx solid transparent; border-radius: 999rpx; }
.projects button.on { color: #fcfbf8; background: #0b6b43; border-color: #0b6b43; }
.field { margin-top: 14rpx; padding: 22rpx; border: 1rpx solid #e0ded6; border-radius: 12rpx; font-size: 26rpx; color: #454a41; }
.row-2 { display: flex; gap: 12rpx; }
.row-2 picker { flex: 1; }
.capacity-row { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; margin-top: 16rpx; }
.capacity { color: #1a1d18; font-size: 32rpx; font-weight: 700; }
.risk-line { display: block; margin-top: 12rpx; color: #b3261e; font-size: 25rpx; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
