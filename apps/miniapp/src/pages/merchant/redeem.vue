<script setup>
import { reactive, ref, computed, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { merchantOrdersApi } from '../../services/merchant-orders.js'
import { redemptionApi, createRedemptionFlow, initialRedemptionState } from '../../services/order-redemption.js'
import { createReservationReadFlow, initialReservationReadState, displayTime } from '../../services/reservations.js'
import { imageRequestKey } from '../../services/private-images.js'
import { stateLabel } from '../../services/order-status.js'
const detail = reactive(initialReservationReadState()), receipt = reactive(initialReservationReadState()), state = reactive(initialRedemptionState())
const tick = ref(Date.now()), remaining = computed(() => Math.max(0, Math.ceil((state.waitUntil - tick.value) / 1000)))
let id = 0, visible = false, timer; const token = () => merchantSession.accessToken
const order = createReservationReadFlow({ state: detail, token, request: t => merchantOrdersApi.detail(t, id) })
const record = createReservationReadFlow({ state: receipt, token, request: t => redemptionApi.detail(t, 'merchant', id) })
async function refresh() { await Promise.all([order.load(), record.load()]) }
function confirm() { return new Promise(resolve => uni.showModal({ title: detail.value?.payment_summary?.test_mode ? '确认测试核销' : '确认核销', content: `订单 ${detail.value?.order_no}，应付 ¥${detail.value?.amount_due}。请核对车主出示的码。${detail.value?.payment_summary?.test_mode ? '未真实扣款，不可作为收款凭据。' : '核销后订单完成。'}`, success: r => resolve(r.confirm === true), fail: () => resolve(false) })) }
const submit = createRedemptionFlow({ state, token, order: () => id, api: redemptionApi, newKey: imageRequestKey, confirm, onChanged: refresh })
function login() { clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
function stop() { visible = false; clearInterval(timer); submit.suspend(); order.reset(); record.reset() }
watch(token, () => { submit.reset(); order.reset(); record.reset(); if (visible && token()) { submit.resume(); refresh() } }, { flush: 'sync' })
onLoad(q => { id = Number(q?.id) || 0 })
onShow(() => { visible = true; clearInterval(timer); tick.value = Date.now(); timer = setInterval(() => { tick.value = Date.now() }, 1000); submit.resume(); if (token()) refresh() })
onHide(stop); onUnload(stop)
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">核销验码</text>
    <button v-if="!merchantSession.accessToken" @tap="login">前往商家登录</button>
    <text v-if="detail.busy || receipt.busy" role="status">正在核对订单…</text>
    <view v-if="detail.message || receipt.message" class="reservation-panel" role="status"><text>{{ detail.message || receipt.message }}</text><button v-if="['unauthorized','forbidden'].includes(detail.failureKind) || ['unauthorized','forbidden'].includes(receipt.failureKind)" @tap="login">重新登录</button><button v-else @tap="refresh">重试查询</button></view>
    <view v-if="detail.value" class="reservation-panel">
      <text class="reservation-heading">{{ detail.value.project_snapshot?.project_name || '历史订单' }}</text>
      <text>订单号 {{ detail.value.order_no }}</text><text class="reservation-price">应付 ¥{{ detail.value.amount_due }}</text><text>{{ stateLabel(detail.value.status) }}</text>
      <text v-if="detail.value.payment_summary?.test_mode" data-testid="redeem-test-warning">测试核销，未真实扣款，不可作为收款凭据</text>
      <text v-if="detail.value.has_payment_exception">付款存在异常，暂不可核销，请核对付款记录。</text>
      <view v-if="receipt.value?.redemption" class="redemption-block" data-testid="redemption-receipt"><text class="reservation-heading">{{ receipt.value.redemption.test_mode ? '测试核销已完成' : '已核销' }}</text><text>核销时间 {{ displayTime(receipt.value.redemption.redeemed_at) }}</text><text v-if="receipt.value.redemption.test_mode">未真实扣款，不可作为收款凭据</text></view>
      <view class="redemption-block" v-else-if="detail.value.status==='PENDING_VERIFY' && receipt.loaded && !receipt.message && !detail.value.has_payment_exception && !state.saved">
        <text>请向车主核对六位核销码，确认车辆服务完成后提交。</text>
        <input class="reservation-field" v-model="state.code" type="number" maxlength="6" :disabled="state.busy" placeholder="输入六位核销码" aria-label="六位核销码" data-testid="redeem-code" />
        <button :disabled="state.busy || remaining>0 || !/^[0-9]{6}$/.test(state.code)" :loading="state.busy" data-testid="redeem-submit" @tap="submit.submit">{{ remaining>0 ? `请等待 ${remaining} 秒` : '验码并核销' }}</button>
      </view>
      <text v-else-if="detail.value.status==='COMPLETED' && !receipt.value?.redemption">此历史完成订单没有可信核销记录，请人工核对。</text>
      <text v-else-if="detail.value.status!=='PENDING_VERIFY'">当前订单不能核销，请按订单进度完成前序步骤。</text>
      <text v-if="state.message" role="status">{{ state.message }}</text><button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">重新登录</button>
      <button :disabled="state.busy" @tap="refresh">刷新核销结果</button>
    </view>
    <button @tap="uni.redirectTo({url:`/pages/merchant/order-detail?id=${id}`})">返回订单详情</button>
  </view>
</template>


<style scoped>
.redemption-block{display:flex;flex-direction:column;gap:18rpx}
.redemption-block input{height:48rpx;font-size:30rpx;letter-spacing:6rpx}
</style>
