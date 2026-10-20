<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { operatorSession, operatorToken, clearOperatorSession } from '../../services/operator-session.js'
import { publicationApi, initialPublicationState, createPublicationFlow } from '../../services/experience-publication.js'
import { REVIEW_REASONS } from '../../services/experience-cards.js'
import { imageRequestKey } from '../../services/private-images.js'
import { operatorCardFacts, operatorCapabilitySummary, operatorEntryGroups, reasonPicker, reasonLabel, toneClass } from '../../services/public-review-display.js'
const state = reactive(initialPublicationState()), reasons = ref({})
// 入口与权限过滤都在展示映射里做，模板只渲染分组结果。
const picker = reasonPicker(REVIEW_REASONS)
const groups = computed(() => operatorEntryGroups(operatorSession))
const capability = computed(() => operatorCapabilitySummary(operatorSession))
const flow = createPublicationFlow({ state, api: publicationApi, token: operatorToken, operator: true, newKey: imageRequestKey })
const cards = computed(() => (Array.isArray(state.rows) ? state.rows : []).map(card => ({ card, facts: operatorCardFacts(card) })))
const queue = computed(() => ({ pending: state.total, loaded: cards.value.length, blocked: cards.value.filter(row => !row.facts.canApprove).length }))
const queueCopy = computed(() => cards.value.length === 0 ? ''
  : `待审 ${queue.value.pending} 条 · 已加载 ${queue.value.loaded} 条${queue.value.blocked > 0 ? ` · 其中 ${queue.value.blocked} 条缺有效车型只能驳回` : ''} · 第 ${state.page} 页`)
let visible = false
function clear() { flow.reset(); reasons.value = {} }
function login() { clear(); clearOperatorSession(); uni.redirectTo({ url: '/pages/operator/login' }) }
function refresh() { reasons.value = {}; flow.load() }
function open(url) { uni.navigateTo({ url }) }
function reason(card, event) { reasons.value[card.card_id] = picker.codes[Number(event.detail.value)] }
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
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">运营工作台</text>
      <text class="ux-title">运营审核台</text>
      <text class="ux-lead">入口按当前账号权限显示。审核类操作都会写版本与审计；授权由车主单独给出，运营不能代改原文。</text>
    </view>

    <view class="ux-card ux-in ux-r2" data-testid="operator-capability">
      <view class="head">
        <text class="ux-card-title">我的权限</text>
        <view class="ux-badge" :class="toneClass(capability.tone)"><text>{{ capability.granted.length ? capability.granted.length + ' 项' : '无权限' }}</text></view>
      </view>
      <text class="ux-card-note">{{ capability.copy }}</text>
      <view v-if="capability.granted.length" class="chips">
        <view v-for="label in capability.granted" :key="label" class="chip"><text>{{ label }}</text></view>
      </view>
    </view>

    <view v-for="group in groups" :key="group.key" class="ux-card ux-in ux-r3" :data-testid="'operator-group-' + group.key">
      <text class="ux-card-title">{{ group.title }}</text>
      <text class="ux-card-note">{{ group.note }}</text>
      <view class="ux-divider"></view>
      <view v-for="entry in group.entries" :key="entry.key" class="ux-row entry" data-testid="operator-entry" @tap="open(entry.url)">
        <view class="entry-head">
          <text class="ux-row-title">{{ entry.label }}</text>
          <text class="chevron">›</text>
        </view>
        <text class="ux-row-copy">{{ entry.note }}</text>
      </view>
    </view>

    <view v-if="groups.length === 0" class="ux-empty ux-in ux-r3" data-testid="operator-empty">
      <text class="ux-empty-title">没有可用的运营入口</text>
      <text class="ux-empty-copy">当前账号没有配置任何运营权限，请联系管理员开通；你也可以退出后换账号登录。</text>
    </view>

    <view class="ux-card ux-in ux-r4" data-testid="pending-cards">
      <view class="head">
        <text class="ux-card-title">待审经验摘要</text>
        <view class="ux-badge" :class="toneClass(queue.pending > 0 ? 'warn' : 'muted')"><text>待审 {{ queue.pending }} 条</text></view>
      </view>
      <text class="ux-card-note">仅查看车主已明确授权的正式施工摘要。批准前会复核车型和来源；车主撤回后停止展示。</text>
      <text v-if="queueCopy" class="ux-card-note queue-line">{{ queueCopy }}</text>
      <view class="ux-divider"></view>

      <view v-if="!operatorSession.canReview" class="ux-card-note">当前账号未配置审核权限，待审摘要不可操作。</view>
      <text v-else-if="state.busy" class="ux-card-note" role="status">正在加载待审摘要…</text>
      <view v-else-if="state.loaded && cards.length === 0" class="ux-empty" data-testid="pending-empty">
        <text class="ux-empty-title">当前没有待审摘要</text>
        <text class="ux-empty-copy">只有车主明确授权、且来源核对通过的正式施工摘要才会进入这里；测试记录不会送审。</text>
      </view>

      <view v-for="row in cards" :key="row.card.card_id" class="ux-row" data-testid="pending-row">
        <view class="head">
          <text class="ux-row-title">{{ row.facts.title }}</text>
          <view class="ux-badge" :class="toneClass(row.facts.tone)"><text>{{ row.facts.canApprove ? '可批准' : '缺有效车型' }}</text></view>
        </view>
        <text class="ux-row-copy">{{ row.facts.month }} · {{ row.facts.work }} · {{ row.facts.parts }} · {{ row.facts.revisionCopy }}</text>
        <text class="ux-row-copy" :class="{ 'risk-copy': !row.facts.canApprove }">{{ row.facts.model }}</text>
        <button class="reservation-primary" :disabled="!row.facts.canApprove || state.busy || state.writing" :loading="state.writing" @tap="act(row.card,'APPROVE')">批准同款摘要展示</button>
        <picker :range="picker.labels" :disabled="state.busy || state.writing" @change="reason(row.card,$event)">
          <view class="reason">{{ reasonLabel(reasons[row.card.card_id], REVIEW_REASONS) || picker.placeholder }} ›</view>
        </picker>
        <button :disabled="!reasons[row.card.card_id] || state.busy || state.writing" @tap="act(row.card,'REJECT')">按所选理由驳回</button>
      </view>

      <button v-if="state.loaded && state.page * 20 < state.total" :disabled="state.busy || state.writing" @tap="flow.load(true)">加载下一页</button>
    </view>

    <text v-if="state.message" class="status-line" role="status">{{ state.message }}</text>
    <button v-if="state.kind === 'unauthorized'" class="reservation-primary" @tap="login">重新登录运营账号</button>
    <button :disabled="state.busy || state.writing" @tap="refresh">刷新待审列表</button>
    <button :disabled="state.writing || state.busy" @tap="logout">退出运营账号</button>
  </view>
</template>
<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.entry-head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.chevron { color: #8a8f84; font-size: 30rpx; }
.entry:active { opacity: 0.6; }
.chips { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 20rpx; }
.chip { padding: 8rpx 20rpx; border-radius: 999rpx; background: #efede6; color: #454a41; font-size: 23rpx; }
.queue-line { margin-top: 8rpx; }
.risk-copy { color: #b3261e; }
.reason { margin-top: 16rpx; padding: 22rpx; border: 1rpx solid #e0ded6; border-radius: 12rpx; font-size: 27rpx; color: #454a41; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
