<script setup>
import { reactive, ref, computed, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import CouponTerms from '../../components/CouponTerms.vue'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { initialCouponState, createCouponFlow, couponLocalTime, couponUtcTime } from '../../services/coupons.js'
import { quotesApi } from '../../services/merchant-quotes.js'
import { initialQuoteListState, createQuoteListFlow } from '../../services/quote-flow.js'
import { imageRequestKey } from '../../services/private-images.js'
import {
  couponDateFields, couponIssueEmptyView, couponIssuePagingView, couponIssueRowView,
  couponIssueWriteView, couponProjectOptionRows, couponTermsFormView, couponTypeRows,
} from '../../services/merchant-coupon-issue-display.js'
import { toneClass } from '../../services/ui-tone.js'

const state = reactive(initialCouponState()), token = () => merchantSession.accessToken
const editing = ref(false), current = ref(null)
const empty = () => ({ name: '', type: 'DISCOUNT', project_id: 0, amount: '', min_spend: '', total_quantity: '', valid_from: '', valid_to: '' })
const form = reactive(empty())
const dates = reactive({ fromDate: '', fromTime: '', toDate: '', toTime: '' })
const projects = reactive(initialQuoteListState())
const projectFlow = createQuoteListFlow({ state: projects, token, fetchPage: quotesApi.ownList })
const flow = createCouponFlow({ state, token, mode: 'merchant', newKey: imageRequestKey, confirm: ({ action, row, body }) => new Promise(resolve => uni.showModal({ title: '确认本店券操作', content: action === 'status' ? (body.status === 'PUBLISHED' ? '发布后权益、库存和有效期冻结；每人限领一次。' : '停止新领取，已领取权益保留。') : `${body.terms.name}；项目 ${body.terms.project_id}；库存 ${body.terms.total_quantity}。优惠由本店承担。`, success: r => resolve(r.confirm), fail: () => resolve(false) })) })
let visible = false

// 表单缺口、行内动作、空态、分页与写入结果都来自映射；模板不再拼校验与状态文案。
const typeRows = computed(() => couponTypeRows(form.type))
const formView = computed(() => couponTermsFormView(form, dates, projects))
const projectRows = computed(() => couponProjectOptionRows(projects))
const emptyView = computed(() => couponIssueEmptyView(state))
const pagingView = computed(() => couponIssuePagingView(state))
const writeView = computed(() => couponIssueWriteView(state))
const rowsView = computed(() => state.rows.map(row => ({ row, view: couponIssueRowView(row) })))

watch(() => state.completed, () => { if (state.completed) { editing.value = false; current.value = null; Object.assign(form, empty()) } })
function reset() { flow.reset(); projectFlow.reset(); editing.value = false; current.value = null; Object.assign(form, empty()); Object.assign(dates, { fromDate: '', fromTime: '', toDate: '', toTime: '' }) }
watch(token, () => { reset(); if (visible && token()) { flow.load(1); projectFlow.load() } }, { flush: 'sync' })
onShow(() => { visible = true; if (token()) { flow.load(1); projectFlow.load() } })
onHide(() => { visible = false; reset() }); onUnload(() => { visible = false; reset() })

function start(row = null) {
  current.value = row
  Object.assign(form, row ? { ...row.terms, total_quantity: String(row.terms.total_quantity) } : empty())
  Object.assign(dates, couponDateFields(row ? row.terms : null, couponLocalTime))
  editing.value = true
}
function datePart(key, event) { dates[key] = event.detail.value; form.valid_from = couponUtcTime(dates.fromDate, dates.fromTime); form.valid_to = couponUtcTime(dates.toDate, dates.toTime) }
function choose(event) {
  const option = projectRows.value[Number(event.detail.value)]
  return option?.selectable ? (form.project_id = option.projectId, true) : (form.project_id = 0, state.message = '只能选择本店有效且在售的项目', false)
}
function type(event) {
  form.type = typeRows.value[Number(event.detail.value)]?.key || 'DISCOUNT'
  form.amount = form.type === 'SERVICE' ? '0.00' : ''
  form.min_spend = form.type === 'SERVICE' ? '0.00' : ''
}
async function save() {
  if (!/^[1-9][0-9]{0,9}$/.test(form.total_quantity)) { state.message = '请填写正整数库存'; return }
  const terms = { ...form, total_quantity: Number(form.total_quantity) }
  const body = current.value ? { expected_revision: current.value.revision, terms } : { terms }
  await flow.write(current.value ? 'update' : 'create', current.value, body)
  if (!state.retryWrite && !state.kind) editing.value = false
}
function status(row, target) { flow.write('status', row, { expected_revision: row.revision, status: target }) }
function act(row, action) {
  if (action.action === 'update') return start(row)
  return status(row, action.target)
}
function login() { reset(); clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
</script>

<template>
  <view class="reservation-page" data-testid="coupon-issue-form">
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">门店工作台</text>
      <text class="ux-title">本店券库存与发行</text>
      <text class="ux-lead">券只适用于本店指定在售项目；发布后条款、库存与有效期冻结，已领取的权益不因停止领取而撤回。</text>
    </view>

    <view v-if="!token()" class="ux-card ux-in ux-r2" data-testid="coupon-issue-login">
      <text class="ux-card-title">需要门店账号</text>
      <text class="ux-card-note">券发行只能由本店账号操作。</text>
      <button class="reservation-primary" @tap="login">门店登录</button>
    </view>

    <template v-else>
      <view class="ux-card ux-in ux-r2">
        <text class="ux-card-note">优惠由本店承担；领取、冻结与核销分别走独立流程。</text>
        <button @tap="uni.navigateTo({ url: '/pages/merchant/coupon-redeem' })">服务券独立核销与记录</button>
        <button class="reservation-primary" :disabled="state.busy || state.retryWrite" data-testid="coupon-issue-create" @tap="start()">创建券草稿</button>
        <text v-if="writeView.retryCopy" class="risk-line">{{ writeView.retryCopy }}</text>
      </view>

      <view v-if="editing" class="ux-card ux-in ux-r3" data-testid="coupon-terms-card">
        <view class="head">
          <text class="ux-card-title">{{ current ? '修改草稿' : '新券草稿' }}</text>
          <view class="ux-badge" :class="toneClass(formView.ready ? 'ok' : 'muted')"><text>{{ formView.ready ? '信息齐全' : '待填写' }}</text></view>
        </view>
        <input class="field" :value="form.name" :disabled="state.busy || state.retryWrite" maxlength="128" placeholder="券名称（不超过 128 字）" data-testid="coupon-name" @input="form.name = $event.detail.value" />
        <picker :range="typeRows" range-key="label" :disabled="state.busy || state.retryWrite" data-testid="coupon-type" @change="type">
          <view class="field">{{ formView.typeLabel }} ›</view>
        </picker>
        <text class="ux-card-note">{{ formView.isService ? '服务兑换券：优惠与门槛固定为 0.00。' : '金额优惠券：需设置优惠金额与最低消费。' }}</text>
        <picker :range="projectRows" range-key="label" :disabled="projects.busy || state.busy || state.retryWrite" data-testid="coupon-project" @change="choose">
          <view class="field">{{ form.project_id ? `已选标准项目 ${form.project_id}` : '选择本店在售项目' }} ›</view>
        </picker>
        <text v-if="formView.projectHint" class="risk-line">{{ formView.projectHint }}</text>
        <text v-if="projects.message" class="risk-line">{{ projects.message }}</text>
        <button :disabled="projects.busy" @tap="projectFlow.load()">刷新本店项目</button>
        <button v-if="projects.items.length < projects.total" :disabled="projects.busy" @tap="projectFlow.load(true)">更多本店项目</button>
        <template v-if="!formView.amountLocked">
          <input class="field" :value="form.amount" :disabled="state.busy || state.retryWrite" placeholder="优惠金额，两位小数，如 5.00" data-testid="coupon-amount" @input="form.amount = $event.detail.value" />
          <input class="field" :value="form.min_spend" :disabled="state.busy || state.retryWrite" placeholder="最低消费，两位小数且不小于优惠" data-testid="coupon-min-spend" @input="form.min_spend = $event.detail.value" />
        </template>
        <input class="field" :value="form.total_quantity" type="number" :disabled="state.busy || state.retryWrite" placeholder="发行库存，正整数" data-testid="coupon-quantity" @input="form.total_quantity = $event.detail.value" />
        <text class="ux-card-note">{{ formView.dateCopy }}</text>
        <picker mode="date" :value="dates.fromDate" :disabled="state.busy || state.retryWrite" data-testid="coupon-valid-from" @change="datePart('fromDate', $event)">
          <view class="field">生效日期：{{ dates.fromDate || '请选择' }}</view>
        </picker>
        <picker mode="time" :value="dates.fromTime" :disabled="state.busy || state.retryWrite" @change="datePart('fromTime', $event)">
          <view class="field">生效时间：{{ dates.fromTime || '请选择' }}</view>
        </picker>
        <picker mode="date" :value="dates.toDate" :disabled="state.busy || state.retryWrite" data-testid="coupon-valid-to" @change="datePart('toDate', $event)">
          <view class="field">截止日期：{{ dates.toDate || '请选择' }}</view>
        </picker>
        <picker mode="time" :value="dates.toTime" :disabled="state.busy || state.retryWrite" @change="datePart('toTime', $event)">
          <view class="field">截止时间：{{ dates.toTime || '请选择' }}</view>
        </picker>
        <text class="ux-card-note">{{ formView.hint }}</text>
        <button class="reservation-primary" :disabled="state.busy || state.retryWrite || !formView.ready" data-testid="coupon-save" @tap="save">保存草稿</button>
        <button :disabled="state.busy || state.retryWrite" data-testid="coupon-close" @tap="editing = false">关闭编辑</button>
      </view>

      <view class="ux-card ux-in ux-r3" data-testid="coupon-board">
        <view class="head">
          <text class="ux-card-title">本店已发行券</text>
          <view class="ux-badge" :class="toneClass('muted')"><text>{{ pagingView.pageCopy }}</text></view>
        </view>
        <text v-if="state.busy" class="status-line" role="status">正在读取本店券…</text>
        <view v-if="emptyView.visible" class="ux-empty" data-testid="coupon-issue-empty">
          <text class="ux-empty-title">{{ emptyView.title }}</text>
          <text class="ux-empty-copy">{{ emptyView.copy }}</text>
        </view>
        <view v-for="row in rowsView" :key="row.row.coupon_id" class="record" :data-testid="`coupon-row-${row.row.coupon_id}`">
          <view class="head">
            <text class="ux-card-title">{{ row.view.typeLabel }}</text>
            <view class="ux-badge" :class="toneClass(row.view.tone)"><text>{{ row.view.statusLabel }}</text></view>
          </view>
          <CouponTerms :row="row.row" />
          <text class="ux-card-note">{{ row.view.stockCopy }} · {{ row.view.revisionCopy }}</text>
          <text class="ux-card-note">{{ row.view.noteCopy }}</text>
          <button v-for="action in row.view.actions" :key="action.key" :disabled="state.busy || state.retryWrite" @tap="act(row.row, action)">{{ action.label }}</button>
        </view>
        <view class="paging">
          <button v-if="!pagingView.hidden && pagingView.page > 1" :disabled="!pagingView.canPrev" @tap="flow.load(pagingView.page - 1)">上一页</button>
          <button v-if="!pagingView.hidden" :disabled="!pagingView.canNext" @tap="flow.load(pagingView.page + 1)">下一页</button>
        </view>
        <button :disabled="state.busy || state.retryWrite" @tap="flow.load(1)">刷新本店券</button>
      </view>

      <view v-if="writeView.message" class="ux-card ux-in ux-r3" role="status" data-testid="coupon-issue-message">
        <view class="head">
          <text class="ux-card-title">券操作</text>
          <view class="ux-badge" :class="toneClass(writeView.tone)"><text>{{ writeView.tone === 'ok' ? '已完成' : writeView.canRelogin ? '需重新登录' : '可重试' }}</text></view>
        </view>
        <text class="ux-card-note">{{ writeView.message }}</text>
        <button v-if="writeView.canRelogin" class="reservation-primary" @tap="login">重新登录门店账号</button>
        <button v-if="writeView.retryWrite" :disabled="state.busy" data-testid="coupon-issue-retry" @tap="flow.retry">重试原操作</button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.field { margin-top: 14rpx; padding: 22rpx; border: 1rpx solid #e0ded6; border-radius: 12rpx; font-size: 26rpx; color: #454a41; background: #efede6; }
.record { display: flex; flex-direction: column; gap: 10rpx; padding: 18rpx 0; border-top: 1rpx solid #e0ded6; }
.paging { display: flex; gap: 16rpx; }
.risk-line { display: block; margin-top: 12rpx; color: #b3261e; font-size: 25rpx; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
