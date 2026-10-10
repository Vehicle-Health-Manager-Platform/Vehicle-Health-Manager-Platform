<script setup>
import { reactive, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { operatorSession, operatorToken, clearOperatorSession } from '../../services/operator-session.js'
import { publicationApi, initialPublicationState, createPublicationFlow } from '../../services/experience-publication.js'
import { REVIEW_REASONS } from '../../services/experience-cards.js'
import { imageRequestKey } from '../../services/private-images.js'
const state = reactive(initialPublicationState()), reasons = ref({}), reasonCodes = Object.keys(REVIEW_REASONS), reasonLabels = reasonCodes.map(code => REVIEW_REASONS[code])
const flow = createPublicationFlow({ state, api: publicationApi, token: operatorToken, operator: true, newKey: imageRequestKey })
let visible = false
function clear() { flow.reset(); reasons.value = {} }
function login() { clear(); clearOperatorSession(); uni.redirectTo({ url: '/pages/operator/login' }) }
function refresh() { reasons.value = {}; flow.load() }
function reason(card, event) { reasons.value[card.card_id] = reasonCodes[Number(event.detail.value)] }
async function act(card, decision) {
  const selected = decision === 'APPROVE' ? null : reasons.value[card.card_id]
  if (decision === 'REJECT' && !selected) { state.message = '请先选择驳回理由'; return }
  await flow.moderate(card, decision, selected)
}
async function logout() {
  const token = operatorToken(); clear();
  try { if (token) await publicationApi.logout(token); login() }
  catch (e) { flow.resume(); state.message = `${e.message}；请重试退出以撤销服务端会话`; state.kind = e.kind; if (e.kind === 'unauthorized') login() }
}
watch(() => operatorSession.accessToken, () => { clear(); if (visible && operatorToken()) { flow.resume(); flow.load() } }, { flush: 'sync' })
onShow(() => { visible = true; if (!operatorToken()) { login(); return } flow.resume(); flow.load() })
onHide(() => { visible = false; clear() }); onUnload(() => { visible = false; clear() })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">经验卡片审核</text>
    <text class="reservation-copy">仅查看车主已明确授权的正式施工摘要。批准前会复核车型和来源；车主撤回后停止展示。</text>
    <text v-if="!operatorSession.canReview">当前账号未配置审核权限。</text>
    <text v-if="state.busy" role="status">正在加载待审摘要…</text>
    <text v-else-if="state.loaded && !state.rows.length">当前没有待审摘要。</text>
    <view v-for="card in state.rows" :key="card.card_id" class="reservation-panel">
      <text class="reservation-heading">{{ card.title }}</text>
      <text>{{ card.summary.recorded_month }} · 实际工时 {{ card.summary.work_minutes }} 分钟</text>
      <text>{{ card.summary.no_parts ? '本次未使用配件' : `使用 ${card.summary.part_kinds} 种配件` }}</text>
      <text>{{ card.model_id ? `有效车型编号 ${card.model_id}` : '缺少有效车型，不能批准' }}</text>
      <button class="reservation-primary" :disabled="!card.model_id || state.busy || state.writing" :loading="state.writing" @tap="act(card,'APPROVE')">批准同款摘要展示</button>
      <picker :range="reasonLabels" :disabled="state.busy || state.writing" @change="reason(card,$event)">
        <view class="reason">{{ REVIEW_REASONS[reasons[card.card_id]] || '请选择驳回理由' }} ›</view>
      </picker>
      <button :disabled="!reasons[card.card_id] || state.busy || state.writing" @tap="act(card,'REJECT')">按所选理由驳回</button>
    </view>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button v-if="state.kind === 'unauthorized'" @tap="login">重新登录运营账号</button>
    <button :disabled="state.busy || state.writing" @tap="refresh">刷新待审列表</button>
    <text v-if="state.loaded">待审 {{ state.total }} 条 · 第 {{ state.page }} 页</text>
    <button v-if="state.loaded && state.page * 20 < state.total" :disabled="state.busy || state.writing" @tap="flow.load(true)">加载下一页</button>
    <button :disabled="state.writing || state.busy" @tap="logout">退出运营账号</button>
  </view>
</template>
<style src="../../styles/reservations.css"></style>
<style scoped>.reason{padding:22rpx;border:1rpx solid #e5e6eb;border-radius:12rpx;font-size:28rpx}</style>
