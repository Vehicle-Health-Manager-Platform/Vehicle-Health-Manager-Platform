<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { initialQuoteListState, createQuoteListFlow } from '../../services/quote-flow.js'
import { merchantOrdersApi } from '../../services/merchant-orders.js'
import { apiRuntime } from '../../services/api-runtime.js'
import { pickupApi } from '../../services/pickup.js'
import { createPickupScanFlow } from '../../services/pickup-scan.js'
import { MERCHANT_ORDER_FILTERS, merchantOrderListCopy, merchantOrderRow } from '../../services/merchant-order-display.js'
import { toneClass } from '../../services/ui-tone.js'

const state = reactive(initialQuoteListState()), status = ref(''), date = ref('')
let visible = false
const token = () => merchantSession.accessToken
const scanState = reactive({ busy: false, message: '' })
const scanner = createPickupScanFlow({ state: scanState, token, runtime: () => apiRuntime, context: (actor, id) => pickupApi.context(actor, id), navigate: url => uni.navigateTo({ url }) })
const flow = createQuoteListFlow({ state, token, fetchPage: (actor, page) => merchantOrdersApi.list(actor, page, status.value, date.value) })
// 行的展示事实与列表空态都来自映射，模板不再做状态判断。
const rows = computed(() => (Array.isArray(state.items) ? state.items : []).map(item => ({ item, view: merchantOrderRow(item) })))
const listCopy = computed(() => merchantOrderListCopy(state, { status: status.value, date: date.value }))
const filtering = computed(() => !!status.value || !!date.value)
function changeStatus(value) { if (status.value === value) return; status.value = value; flow.reset(); if (token()) flow.load() }
function changeDate(event) { date.value = event.detail.value; flow.reset(); if (token()) flow.load() }
function clearDate() { date.value = ''; flow.reset(); if (token()) flow.load() }
function login() { clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
function open(row) { uni.navigateTo({ url: `/pages/merchant/order-detail?id=${row.order_id}` }) }
watch(token, () => { scanner.reset(); flow.reset(); if (visible && token()) flow.load() }, { flush: 'sync' })
onShow(() => { visible = true; if (token()) flow.load() })
onHide(() => { visible = false; scanner.reset(); flow.suspend() })
onUnload(() => { visible = false; scanner.reset(); flow.reset() })
</script>
<template>
  <view class="reservation-page">
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">门店工作台</text>
      <text class="ux-title">本店订单</text>
      <text class="ux-lead">按状态与预约日期筛选。金额与状态以服务端记录为准；测试支付不计入真实收款。</text>
    </view>

    <view v-if="!merchantSession.accessToken" class="ux-card ux-in ux-r2" data-testid="merchant-orders-login">
      <text class="ux-card-title">需要门店账号</text>
      <text class="ux-card-note">本店订单只能由本店账号查看，且只能看到本店订单。</text>
      <button class="reservation-primary" @tap="login">前往门店登录</button>
    </view>

    <template v-else>
      <view class="ux-card ux-in ux-r2">
        <view class="head">
          <text class="ux-card-title">接车</text>
          <view class="ux-badge" :class="toneClass('ok')"><text>扫码即校验</text></view>
        </view>
        <text class="ux-card-note">扫车主核销码进入接车检查；车牌与联系人按授权范围展示，不落本地缓存。</text>
        <button class="reservation-primary" :disabled="scanState.busy" :loading="scanState.busy" @tap="scanner.scan">扫码接车</button>
        <text v-if="scanState.message" class="status-line" role="status">{{ scanState.message }}</text>
      </view>

      <view class="ux-card ux-in ux-r3">
        <text class="ux-card-title">筛选</text>
        <view class="filters">
          <button v-for="option in MERCHANT_ORDER_FILTERS" :key="option.key" :class="{ on: status === option.key }" :data-testid="'merchant-filter-' + (option.key || 'all')" @tap="changeStatus(option.key)">{{ option.label }}</button>
        </view>
        <picker mode="date" :value="date" @change="changeDate">
          <view class="date-row" data-testid="merchant-date-filter">预约日期：{{ date || '全部日期' }}</view>
        </picker>
        <button v-if="date" class="quiet" @tap="clearDate">清除日期</button>
        <text v-if="filtering" class="ux-card-note">当前为筛选结果，切换「全部」可看完整列表。</text>
      </view>

      <text v-if="state.busy" class="status-line" role="status">正在加载本店订单…</text>

      <view v-if="state.message" class="ux-card ux-in ux-r3" role="status" data-testid="merchant-orders-error">
        <view class="head">
          <text class="ux-card-title">加载未成功</text>
          <view class="ux-badge" :class="toneClass('risk')"><text>{{ state.failureKind === 'unauthorized' || state.failureKind === 'forbidden' ? '需要重新登录' : '可重试' }}</text></view>
        </view>
        <text class="ux-card-note">{{ state.message }}</text>
        <button v-if="['unauthorized', 'forbidden'].includes(state.failureKind)" class="reservation-primary" @tap="login">重新登录</button>
        <button v-else :disabled="state.busy" @tap="flow.retry">重试本店订单</button>
      </view>

      <view v-if="listCopy.empty && !state.busy && !state.message" class="ux-empty ux-in ux-r3" data-testid="merchant-orders-empty">
        <text class="ux-empty-title">{{ listCopy.emptyTitle }}</text>
        <text class="ux-empty-copy">{{ listCopy.emptyCopy }}</text>
      </view>

      <view v-for="row in rows" :key="row.item.order_id" class="ux-card ux-in ux-r3" :data-testid="`merchant-order-${row.item.order_id}`">
        <view class="head">
          <text class="ux-card-title">{{ row.view.title }}</text>
          <view class="ux-badge" :class="toneClass(row.view.statusTone)"><text>{{ row.view.statusLabel }}</text></view>
        </view>
        <view class="facts">
          <text class="fact-amount ux-num">{{ row.view.amount }}</text>
          <text class="fact-time">{{ row.view.appointment }}</text>
        </view>
        <text class="ux-card-note">{{ row.view.statusCopy }}</text>
        <view v-if="row.view.testMode || row.view.exception || row.view.closeCopy" class="flags">
          <view v-if="row.view.testMode" class="ux-badge" :class="toneClass('warn')"><text>测试支付</text></view>
          <view v-if="row.view.exception" class="ux-badge" :class="toneClass('risk')"><text>付款异常</text></view>
          <text v-if="row.view.closeCopy" class="ux-card-note">{{ row.view.closeCopy }}</text>
        </view>
        <text v-if="row.view.exception" class="risk-line">{{ row.view.exceptionCopy }}</text>
        <button @tap="open(row.item)">查看订单详情</button>
      </view>

      <text v-if="listCopy.footer" class="status-line">{{ listCopy.footer }}</text>
      <button v-if="state.items.length < state.total" :disabled="state.busy" @tap="flow.load(true)">加载更多本店订单</button>
    </template>
  </view>
</template>
<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.facts { display: flex; align-items: baseline; justify-content: space-between; gap: 16rpx; margin-top: 18rpx; }
.fact-amount { color: #1a1d18; font-size: 40rpx; font-weight: 700; letter-spacing: -1rpx; }
.fact-time { color: #63685e; font-size: 24rpx; }
.flags { display: flex; flex-wrap: wrap; align-items: center; gap: 12rpx; margin-top: 16rpx; }
.risk-line { display: block; margin-top: 12rpx; color: #b3261e; font-size: 25rpx; }
.filters { display: flex; flex-wrap: wrap; gap: 12rpx; margin: 20rpx 0 8rpx; }
.filters button { min-height: 64rpx; padding: 0 26rpx; margin: 0; font-size: 25rpx; color: #454a41; background: #efede6; border: 1rpx solid transparent; border-radius: 999rpx; }
.filters button.on { color: #fcfbf8; background: #0b6b43; border-color: #0b6b43; }
.date-row { margin-top: 16rpx; padding: 22rpx; border: 1rpx solid #e0ded6; border-radius: 12rpx; font-size: 26rpx; color: #454a41; }
.quiet { margin-top: 12rpx; font-size: 25rpx; color: #074d30; background: #e9f2ec; border: 0; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
