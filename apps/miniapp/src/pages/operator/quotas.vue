<script setup>
import { reactive, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { operatorSession, operatorToken, clearOperatorSession } from '../../services/operator-session.js'
import { onboardingApi, createOperatorOnboardingFlow, initialOperatorOnboardingState, CATEGORIES, confirmStillHolds } from '../../services/merchant-onboarding.js'
import { publicationApi } from '../../services/experience-publication.js'

const state = reactive(initialOperatorOnboardingState())
const flow = createOperatorOnboardingFlow({ state, api: onboardingApi, token: operatorToken })
const codes = Object.keys(CATEGORIES), labels = codes.map(code => CATEGORIES[code])
const draft = reactive({ region_code: '', category: codes[0], max_active: '' })
let visible = false, generation = 0

function clear() { visible = false; generation++; flow.reset() }
function login() { clear(); clearOperatorSession(); uni.redirectTo({ url: '/pages/operator/login' }) }
function pickCategory(event) { draft.category = codes[Number(event.detail.value)] }
function edit(quota) { draft.region_code = quota.region_code; draft.category = quota.category; draft.max_active = String(quota.max_active) }
function save() {
  const maximum = Number(draft.max_active)
  if (!Number.isInteger(maximum) || maximum < 0 || maximum > 100000) { state.message = '名额上限需为 0 至 100000 的整数'; state.kind = ''; return }
  const body = { region_code: draft.region_code.trim(), category: draft.category, max_active: maximum }
  // 配额直接决定能开多少家店，落库前再确认一次，并复核账号与页面状态未变。
  const actor = operatorToken(), version = generation, opened = { actor, target: `${body.region_code}/${body.category}` }
  uni.showModal({
    title: '保存区域品类配额',
    content: `将「${body.region_code} · ${CATEGORIES[body.category]}」的名额上限设为 ${maximum}。上限不能低于该区域品类当前有效门店数。`,
    success: result => {
      const now = { visible, token: operatorToken(), target: `${body.region_code}/${body.category}` }
      if (result.confirm && confirmStillHolds(opened, now) && version === generation) flow.saveQuota(body)
    },
  })
}
async function logout() {
  const token = operatorToken(); clear()
  try { if (token) await publicationApi.logout(token); login() }
  catch (e) { flow.resume(); state.message = `${e.message}；请重试退出以撤销服务端会话`; state.kind = e.kind; if (e.kind === 'unauthorized') login() }
}
watch(() => operatorSession.accessToken, () => { clear(); if (visible && operatorToken()) { visible = true; flow.resume(); flow.loadQuotas() } }, { flush: 'sync' })
onShow(() => { visible = true; if (!operatorToken()) { login(); return } flow.resume(); flow.loadQuotas() })
onHide(clear)
onUnload(clear)
</script>

<template>
  <view class="reservation-page" data-testid="merchant-onboarding-quotas">
    <text class="reservation-title">区域品类配额</text>
    <text class="reservation-copy">没有配额记录即视为 0，必须先配置名额才可能批准开店。上限不得低于该区域品类当前有效门店数。</text>
    <text v-if="!operatorSession.canOnboard">当前运营账号未配置入驻审核权限。</text>
    <view class="reservation-panel">
      <text class="reservation-heading">设置名额</text>
      <text>行政区划码</text>
      <input v-model="draft.region_code" type="number" maxlength="6" :disabled="state.busy || state.writing" placeholder="6 位数字，例如 440305" />
      <text>经营品类</text>
      <picker :range="labels" :disabled="state.busy || state.writing" @change="pickCategory"><view class="field">{{ CATEGORIES[draft.category] }} ›</view></picker>
      <text>名额上限</text>
      <input v-model="draft.max_active" type="number" maxlength="6" :disabled="state.busy || state.writing" placeholder="0 至 100000" />
      <button class="reservation-primary" :disabled="state.busy || state.writing" :loading="state.writing" @tap="save">保存配额</button>
    </view>
    <text v-if="state.busy" role="status">正在加载配额…</text>
    <text v-else-if="state.quotasLoaded && !state.quotas.length">尚未配置任何区域品类配额。</text>
    <view v-for="quota in state.quotas" :key="`${quota.region_code}/${quota.category}`" class="reservation-panel">
      <text class="reservation-heading">{{ quota.region_code }} · {{ CATEGORIES[quota.category] }}</text>
      <text>名额上限 {{ quota.max_active }} · 当前有效门店 {{ quota.active_stores }}</text>
      <button class="secondary" :disabled="state.busy || state.writing" @tap="edit(quota)">载入并修改</button>
    </view>
    <text v-if="state.message" role="status" :class="{ error: state.kind }">{{ state.message }}</text>
    <button v-if="state.kind === 'unauthorized'" @tap="login">重新登录运营账号</button>
    <button class="secondary" :disabled="state.busy || state.writing" @tap="flow.loadQuotas">刷新配额</button>
    <button class="secondary" :disabled="state.busy || state.writing" @tap="uni.navigateTo({url:'/pages/operator/onboarding'})">返回入驻审核</button>
    <button :disabled="state.writing || state.busy" @tap="logout">退出运营账号</button>
  </view>
</template>

<style scoped>
input { padding: 20rpx; border: 1rpx solid #e5e6eb; border-radius: 12rpx; min-height: 48rpx; }
.field { padding: 22rpx; border: 1rpx solid #e5e6eb; border-radius: 12rpx; font-size: 28rpx; }
.secondary { align-self: flex-start; min-height: 68rpx; padding: 0 24rpx; margin: 12rpx 0 0; background: #eef8f0; color: #008f24; font-size: 24rpx; }
.error { color: #b42318; }
</style>
