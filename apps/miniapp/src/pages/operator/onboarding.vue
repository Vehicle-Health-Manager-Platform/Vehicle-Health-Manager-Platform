<script setup>
import { reactive, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { operatorSession, operatorToken, clearOperatorSession } from '../../services/operator-session.js'
import { onboardingApi, createOperatorOnboardingFlow, initialOperatorOnboardingState, CATEGORIES, REJECT_REASONS, APPLICATION_STATUS, confirmStillHolds } from '../../services/merchant-onboarding.js'
// 退出登录是运营身份自身的操作，沿用既有运营身份接口，避免在此重复实现一套会话撤销。
import { publicationApi } from '../../services/experience-publication.js'
import { imageApi } from '../../services/private-images.js'

const state = reactive(initialOperatorOnboardingState())
const flow = createOperatorOnboardingFlow({ state, api: onboardingApi, token: operatorToken })
const reasonCodes = Object.keys(REJECT_REASONS), reasonLabels = reasonCodes.map(code => REJECT_REASONS[code])
const reasons = ref({}), fileMessage = ref('')
let visible = false, generation = 0
const target = summary => `${summary.application_id}:${summary.revision}`

function clear() { visible = false; generation++; flow.reset(); reasons.value = {}; fileMessage.value = '' }
function login() { clear(); clearOperatorSession(); uni.redirectTo({ url: '/pages/operator/login' }) }
function refresh() { reasons.value = {}; fileMessage.value = ''; flow.close(); flow.load() }
function reason(summary, event) { reasons.value[summary.application_id] = reasonCodes[Number(event.detail.value)] }
async function open(summary) { fileMessage.value = ''; await flow.open(summary) }
async function view(file) {
  const actor = operatorToken(), version = generation
  try {
    const signed = await onboardingApi.fileAccess(actor, state.detail.application_id, file)
    if (version !== generation || actor !== operatorToken()) return
    await imageApi.preview(signed.url)
  } catch (error) { if (version === generation) fileMessage.value = error?.message || '资质文件预览未打开，请重试' }
}
function decide(summary, decision) {
  const selected = decision === 'APPROVE' ? null : reasons.value[summary.application_id]
  if (decision === 'REJECT' && !selected) { state.message = '请先选择驳回理由'; state.kind = ''; return }
  // 弹窗回调可能在任何时刻返回：确认前必须复核账号、页面可见性与目标版本都未变。
  const actor = operatorToken(), version = generation, opened = { actor, target: target(summary) }
  uni.showModal({
    title: decision === 'APPROVE' ? '批准入驻并开店' : '驳回入驻申请',
    content: decision === 'APPROVE'
      ? `将通过「${summary.merchant_name}」并按区域品类配额开店，同时创建待激活的店长账号。开通账号需要正式身份接入，届时才能登录门店端。`
      : `将以「${REJECT_REASONS[selected]}」驳回，车主可修改后重新提交。审核记录不可修改。`,
    success: result => {
      const now = { visible, token: operatorToken(), target: target(summary) }
      if (result.confirm && confirmStillHolds(opened, now) && version === generation) flow.decide(summary, decision, selected)
    },
  })
}
async function logout() {
  const token = operatorToken(); clear()
  try { if (token) await publicationApi.logout(token); login() }
  catch (e) { flow.resume(); state.message = `${e.message}；请重试退出以撤销服务端会话`; state.kind = e.kind; if (e.kind === 'unauthorized') login() }
}
watch(() => operatorSession.accessToken, () => { clear(); if (visible && operatorToken()) { visible = true; flow.resume(); flow.load() } }, { flush: 'sync' })
onShow(() => { visible = true; if (!operatorToken()) { login(); return } flow.resume(); flow.load() })
onHide(clear)
onUnload(clear)
</script>

<template>
  <view class="reservation-page" data-testid="merchant-onboarding-operator">
    <text class="reservation-title">商家入驻审核</text>
    <text class="reservation-copy">先核对门店信息与资质图片，再批准或按固定理由驳回。批准会在同一事务内创建门店与待激活店长账号，名额受区域品类配额限制。</text>
    <text v-if="!operatorSession.canOnboard">当前运营账号未配置入驻审核权限。</text>
    <text v-if="state.busy" role="status">正在加载…</text>
    <text v-else-if="state.loaded && !state.rows.length">当前没有待审入驻申请。</text>
    <view v-for="summary in state.rows" :key="summary.application_id" class="reservation-panel">
      <text class="reservation-heading">{{ summary.merchant_name }}</text>
      <text>{{ CATEGORIES[summary.category] }} · 区域 {{ summary.region_code }} · 第 {{ summary.revision }} 次提交</text>
      <text>提交时间 {{ summary.submitted_at }}</text>
      <button class="secondary" :disabled="state.busy || state.writing" @tap="open(summary)">查看详情与资质</button>
      <button class="reservation-primary" :disabled="state.busy || state.writing || state.detail?.application_id !== summary.application_id" :loading="state.writing" @tap="decide(summary, 'APPROVE')">批准并开店</button>
      <picker :range="reasonLabels" :disabled="state.busy || state.writing" @change="reason(summary, $event)">
        <view class="reason">{{ REJECT_REASONS[reasons[summary.application_id]] || '请选择驳回理由' }} ›</view>
      </picker>
      <button :disabled="!reasons[summary.application_id] || state.busy || state.writing" @tap="decide(summary, 'REJECT')">按所选理由驳回</button>
    </view>
    <view v-if="state.detail" class="reservation-panel">
      <text class="reservation-heading">申请详情 #{{ state.detail.application_id }}</text>
      <text>{{ state.detail.merchant_name }} · {{ CATEGORIES[state.detail.category] }}</text>
      <text>区域 {{ state.detail.region_code }}</text>
      <text>地址 {{ state.detail.address }}</text>
      <text>联系电话 {{ state.detail.contact_phone }}</text>
      <text>状态 {{ APPLICATION_STATUS[state.detail.status] }} · 第 {{ state.detail.revision }} 次提交</text>
      <text>资质图片（{{ state.detail.qualification_file_ids.length }} 张，仅可在此受控查看）</text>
      <view v-for="(file, index) in state.detail.qualification_file_ids" :key="file" class="image-row">
        <text>资质 {{ index + 1 }}</text>
        <button class="secondary" :disabled="state.busy || state.writing" @tap="view(file)">查看</button>
      </view>
      <text v-if="fileMessage" class="error" role="status">{{ fileMessage }}</text>
      <view v-for="review in state.detailReviews" :key="review.revision" class="review-row">
        <text>第 {{ review.revision }} 次：{{ review.decision === 'APPROVED' ? '通过' : `驳回 ${REJECT_REASONS[review.reason_code] || review.reason_code}` }}</text>
      </view>
      <button class="secondary" :disabled="state.busy || state.writing" @tap="flow.close">收起详情</button>
    </view>
    <button class="secondary" :disabled="state.busy || state.writing" @tap="uni.navigateTo({url:'/pages/operator/quotas'})">区域品类配额维护</button>
    <text v-if="state.message" role="status" :class="{ error: state.kind }">{{ state.message }}</text>
    <button v-if="state.kind === 'unauthorized'" @tap="login">重新登录运营账号</button>
    <button :disabled="state.busy || state.writing" @tap="refresh">刷新待审列表</button>
    <text v-if="state.loaded">待审 {{ state.total }} 条 · 第 {{ state.page }} 页</text>
    <button v-if="state.loaded && state.page * 20 < state.total" :disabled="state.busy || state.writing" @tap="flow.load(true)">加载下一页</button>
    <button class="secondary" :disabled="state.busy || state.writing" @tap="uni.navigateTo({url:'/pages/operator/review'})">返回经验卡片审核</button>
    <button :disabled="state.writing || state.busy" @tap="logout">退出运营账号</button>
  </view>
</template>

<style scoped>
.reason { padding: 22rpx; border: 1rpx solid #e5e6eb; border-radius: 12rpx; font-size: 28rpx; }
.image-row { display: flex; align-items: center; gap: 12rpx; margin-top: 12rpx; font-size: 24rpx; }
.review-row { margin-top: 10rpx; color: #4e5969; font-size: 24rpx; }
.secondary { align-self: flex-start; min-height: 68rpx; padding: 0 24rpx; margin: 12rpx 0 0; background: #eef8f0; color: #008f24; font-size: 24rpx; }
.error { color: #b42318; }
</style>
