<script setup>
import { reactive, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { createAiServicePlanFlow, initialAiServicePlanState, PLAN_TIERS, PLAN_REASONS } from '../../services/ai-service-plan.js'
const state = reactive(initialAiServicePlanState())
let visible = false
const flow = createAiServicePlanFlow({ state, token: () => ownerSession.accessToken,
  confirm: text => new Promise(resolve => uni.showModal({ title: '确认结构化项目建议用途', content: `同意将此问题及公开项目目录发送至第三方DeepSeek以匹配推荐/可选项目。请勿填写姓名、地址、联系方式或车辆标识。本次不读取车辆档案：\n${text}`, success: r => resolve(!!r.confirm), fail: () => resolve(false) })) })
function login() { flow.reset(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function choose(row) { const route = flow.projectRoute(row); if (route) uni.navigateTo({ url: route }) }
onShow(() => { visible = true; flow.load() })
onHide(() => { visible = false; flow.reset() })
onUnload(() => { visible = false; flow.reset() })
watch(() => ownerSession.accessToken, () => { flow.reset(); if (visible) flow.load() }, { flush: 'sync' })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">结构化项目建议</text>
    <text class="reservation-copy">先从公开目录匹配项目，再核对商家实际报价与时段。候选建议不能证明故障或技术必要性；本次不使用车辆档案和同款经验。</text>
    <view v-if="state.catalog" class="reservation-panel">
      <text>描述问题（1–300字），请勿填写身份、地址或联系方式</text>
      <textarea :value="state.draft" :disabled="state.busy" maxlength="600" class="reservation-field question" @input="flow.edit($event.detail.value)" />
      <text>{{ [...state.draft].length }} / 300字</text>
      <text class="reservation-heading">可明确指定最多3个项目</text>
      <text>只显示前40个有效项目；指定是你的选择，不代表技术上的必做。</text>
      <button v-for="p in state.catalog.items" :key="p.project_id" :disabled="state.busy" :aria-pressed="state.requested.includes(p.project_id)" @tap="flow.toggle(p.project_id)">{{ state.requested.includes(p.project_id) ? '已指定 · ' : '' }}{{ p.project_name }}</button>
      <button :disabled="state.busy || !state.draft.trim() || [...state.draft].length>300" @tap="flow.generate()">确认用途并生成项目建议</button>
    </view>
    <view v-if="state.value" class="reservation-panel">
      <text v-if="state.value.status==='AI_READY'">AI目录匹配结果</text>
      <text v-else-if="state.value.status==='MANUAL'">本次没有可用AI匹配，仅保留你明确指定的项目；可手动选择服务。</text>
      <text v-else-if="state.value.status==='SOURCE_CHANGED'">目录在生成期间变化，已丢弃旧方案，请刷新重新选择。</text>
      <text v-else>当前暂无有效候选项目。</text>
      <text class="evidence">本次规范问题：{{ state.value.question }}</text>
      <view v-for="row in state.value.items" :key="row.project_id" class="plan-row">
        <text class="reservation-heading">{{ PLAN_TIERS[row.tier] }} · {{ row.project_name }}</text>
        <text>{{ PLAN_REASONS[row.reason_code] }}</text>
        <text class="evidence">依据问题：{{ row.evidence_quote }}</text>
        <button :disabled="state.busy" @tap="choose(row)">核对当前项目与商家报价</button>
      </view>
      <text>服务详情会重新读取项目与报价，预约页继续核对最新报价和可用时段；AI结果不冻结价格或名额。</text>
    </view>
    <text v-if="state.busy">正在读取目录或生成并复核项目…</text>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">登录车主账号</button>
    <button :disabled="state.busy" @tap="flow.load()">刷新目录与选择</button>
    <button @tap="uni.switchTab({url:'/pages/service/index'})">手动选择全部服务</button>
    <button @tap="uni.navigateBack()">返回AI管家</button>
  </view>
</template>
<style scoped>.question{box-sizing:border-box;width:100%;min-height:220rpx}.plan-row{display:flex;flex-direction:column;gap:18rpx;padding:24rpx 0;border-top:1px solid #e5e6eb}.evidence{white-space:pre-wrap;overflow-wrap:anywhere}</style>
