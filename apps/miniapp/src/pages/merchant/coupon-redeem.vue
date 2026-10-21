<script setup>
import { reactive, ref, watch, computed } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { initialMerchantCouponState, createMerchantCouponFlow, couponTransactionsApi, parseServiceCouponQr } from '../../services/coupon-transactions.js'
import { initialQuoteListState, createQuoteListFlow } from '../../services/quote-flow.js'
import { imageRequestKey } from '../../services/private-images.js'
import { apiRuntime } from '../../services/api-runtime.js'
import { displayTime } from '../../services/reservations.js'
import {
  couponFailureView, couponPreviewView, couponRecordsCopy, couponSavedView, couponSubmitView,
} from '../../services/merchant-redemption-display.js'
import { toneClass } from '../../services/ui-tone.js'
const state = reactive(initialMerchantCouponState()), form = reactive({ coupon: '', revision: '', code: '' }), records = reactive(initialQuoteListState()), tick = ref(Date.now()), scanning = ref(false)
const token = () => merchantSession.accessToken, body = () => ({ user_coupon_id: Number(form.coupon), authorization_revision: Number(form.revision), code: form.code })
const flow = createMerchantCouponFlow({ state, token, body, newKey: imageRequestKey, confirm: v => new Promise(resolve => uni.showModal({ title: '确认本店服务券核销', content: `${v.terms.name}，${v.project_name}（标准项目 ${v.project_id}）。请确认服务已完成并核对车主授权；此操作消费券，佣金0.00，不产生现金支付。`, success: r => resolve(r.confirm), fail: () => resolve(false) })) })
const list = createQuoteListFlow({ state: records, token, fetchPage: couponTransactionsApi.serviceRecords })
// 表单进度、预览、凭据、记录与两个按钮的可用性都来自映射。
const submitView = computed(() => couponSubmitView(state, tick.value, form))
const locked = computed(() => submitView.value.busy || submitView.value.cooldown.active)
const previewView = computed(() => couponPreviewView(state.preview))
const savedView = computed(() => couponSavedView(state.saved))
const failure = computed(() => couponFailureView(state))
const recordsCopy = computed(() => couponRecordsCopy(records))
let visible = false, timer, scanEpoch = 0
function clear() { flow.reset(); list.reset(); scanEpoch++; scanning.value = false; Object.assign(form, { coupon: '', revision: '', code: '' }) }
function stop() { visible = false; clearInterval(timer); scanEpoch++; scanning.value = false; flow.suspend(); list.reset(); Object.assign(form, { coupon: '', revision: '', code: '' }) }
function login() { clear(); clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
async function scan() {
  if (locked.value || !token()) return
  const g = ++scanEpoch, actor = token(); scanning.value = true
  try {
    const result = await new Promise((resolve, reject) => apiRuntime.scanCode({ onlyFromCamera: true, scanType: ['qrCode'], success: resolve, fail: reject }))
    if (g !== scanEpoch || actor !== token() || !visible) return
    const parsed = parseServiceCouponQr(result?.result)
    Object.assign(form, { coupon: String(parsed.user_coupon_id), revision: String(parsed.authorization_revision), code: parsed.code })
    await flow.preview()
  } catch (e) {
    if (g === scanEpoch && actor === token() && visible && !/cancel/i.test(e?.errMsg || '')) state.message = e?.message || '扫码未成功，可手动填写车主出示的授权信息'
  } finally { if (g === scanEpoch) scanning.value = false }
}
watch(() => [form.coupon, form.revision, form.code], () => flow.change(), { flush: 'sync' })
watch(token, () => { clear(); if (visible && token()) list.load() }, { flush: 'sync' })
watch(() => state.saved, r => { if (r && visible) list.load() })
onShow(() => { visible = true; clearInterval(timer); tick.value = Date.now(); timer = setInterval(() => { tick.value = Date.now() }, 1000); if (token()) list.load() })
onHide(stop); onUnload(() => { stop(); clear() })
</script>
<template>
  <view class="reservation-page" data-testid="coupon-redeem-form">
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">门店工作台</text>
      <text class="ux-title">服务券独立核销</text>
      <text class="ux-lead">核对发行门店与项目、车主授权，并确认服务交付。服务券核销佣金 0.00，不产生现金支付记录。</text>
    </view>

    <view v-if="!merchantSession.accessToken" class="ux-card ux-in ux-r2" data-testid="coupon-login">
      <text class="ux-card-title">需要门店账号</text>
      <text class="ux-card-note">服务券核销只能由门店账号操作。</text>
      <button class="reservation-primary" @tap="login">登录门店账号</button>
    </view>

    <template v-else>
      <view class="ux-card ux-in ux-r2" data-testid="coupon-form">
        <view class="head">
          <text class="ux-card-title">车主授权</text>
          <view class="ux-badge" :class="toneClass(submitView.form.ready ? 'ok' : 'muted')"><text>{{ submitView.form.ready ? '信息齐全' : '待填写' }}</text></view>
        </view>
        <button class="reservation-primary" :disabled="locked || scanning" data-testid="coupon-scan" @tap="scan">{{ scanning ? '正在扫码…' : '扫描车主服务券授权码' }}</button>
        <text class="ux-card-note">也可填写车主页面显示的本人券编号、授权版本与 32 位授权码。</text>
        <input class="field" v-model="form.coupon" type="number" :disabled="locked" placeholder="本人券编号" data-testid="coupon-id" />
        <input class="field" v-model="form.revision" type="number" :disabled="locked" placeholder="授权版本" data-testid="coupon-revision" />
        <input class="field" v-model="form.code" :disabled="locked" maxlength="32" placeholder="32位授权码" data-testid="coupon-code" />
        <text class="ux-card-note">{{ submitView.form.hint }}<text v-if="!submitView.form.codeOk"> · 授权码 {{ submitView.form.codeProgress }}</text></text>
        <text v-if="submitView.cooldown.label" class="risk-line">{{ submitView.cooldown.label }}</text>
        <button :disabled="!submitView.canPreview" :loading="state.busy" data-testid="coupon-preview" @tap="flow.preview">{{ submitView.previewLabel }}</button>
      </view>

      <view v-if="previewView.exists" class="ux-card ux-in ux-r3" data-testid="coupon-preview-card">
        <view class="head">
          <text class="ux-card-title">{{ previewView.name }}</text>
          <view class="ux-badge" :class="toneClass(previewView.tone)"><text>{{ previewView.canRedeem ? '可核销' : previewView.tone === 'ok' ? '已核销' : '不可核销' }}</text></view>
        </view>
        <text class="ux-card-note">{{ previewView.projectCopy }}</text>
        <text class="ux-card-note">{{ previewView.issuerCopy }}</text>
        <text v-if="previewView.redeemedCopy" class="ux-card-note">{{ previewView.redeemedCopy }}</text>
        <text v-if="previewView.blockedCopy" class="risk-line">{{ previewView.blockedCopy }}</text>
        <button v-if="previewView.canRedeem" class="reservation-primary" :disabled="!submitView.canRedeem" :loading="state.busy" data-testid="coupon-redeem" @tap="flow.redeem">{{ submitView.redeemLabel }}</button>
      </view>

      <view v-if="savedView.exists" class="ux-card ux-in ux-r3" data-testid="coupon-saved">
        <view class="head">
          <text class="ux-card-title">{{ savedView.title }}</text>
          <view class="ux-badge" :class="toneClass(savedView.tone)"><text>佣金 0.00</text></view>
        </view>
        <text v-for="(line, index) in savedView.lines" :key="index" class="ux-card-note">{{ line }}</text>
      </view>

      <button v-if="failure.uncertain" :disabled="locked" data-testid="coupon-retry" @tap="flow.redeem">{{ submitView.retryLabel }}</button>
      <text v-if="state.busy || scanning" class="status-line" role="status">正在核对…</text>
      <view v-if="failure.message" class="ux-card ux-in ux-r3" role="status" data-testid="coupon-error">
        <view class="head">
          <text class="ux-card-title">操作未成功</text>
          <view class="ux-badge" :class="toneClass(failure.tone)"><text>{{ failure.canRelogin ? '需重新登录' : '可重试' }}</text></view>
        </view>
        <text class="ux-card-note">{{ failure.message }}</text>
        <button v-if="failure.canRelogin" class="reservation-primary" @tap="login">重新登录门店账号</button>
      </view>

      <view class="ux-card ux-in ux-r3" data-testid="coupon-records">
        <view class="head">
          <text class="ux-card-title">本店服务券核销记录</text>
          <view class="ux-badge" :class="toneClass('muted')"><text>{{ records.items.length }} 条</text></view>
        </view>
        <view v-if="recordsCopy.empty" class="ux-empty" data-testid="coupon-empty">
          <text class="ux-empty-title">{{ recordsCopy.emptyTitle }}</text>
          <text class="ux-empty-copy">{{ recordsCopy.emptyCopy }}</text>
        </view>
        <view v-for="row in records.items" :key="row.redemption_id" class="record">
          <text class="ux-card-note">{{ row.terms.name }} · 本人券 {{ row.user_coupon_id }}</text>
          <text class="ux-card-note">标准项目 {{ row.project_id }} · {{ displayTime(row.redeemed_at) }} · 佣金 ¥{{ row.commission_amount }}</text>
        </view>
        <text v-if="recordsCopy.footer" class="ux-card-note">{{ recordsCopy.footer }}</text>
        <text v-if="records.message" class="risk-line">{{ records.message }}</text>
        <button :disabled="records.busy" @tap="list.load()">刷新本店核销记录</button>
        <button v-if="records.items.length < records.total" :disabled="records.busy" @tap="list.load(true)">更多核销记录</button>
      </view>
    </template>
  </view>
</template>
<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.field { margin-top: 14rpx; padding: 22rpx; border: 1rpx solid #e0ded6; border-radius: 12rpx; font-size: 26rpx; color: #454a41; background: #efede6; }
.record { display: flex; flex-direction: column; gap: 8rpx; padding: 18rpx 0; border-top: 1rpx solid #e0ded6; }
.risk-line { display: block; margin-top: 12rpx; color: #b3261e; font-size: 25rpx; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
