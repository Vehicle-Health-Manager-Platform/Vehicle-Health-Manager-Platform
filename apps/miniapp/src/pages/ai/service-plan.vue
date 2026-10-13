<script setup>
import { reactive, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { createAiServicePlanFlow, initialAiServicePlanState, PLAN_TIERS, PLAN_REASONS } from '../../services/ai-service-plan.js'
import VehicleList from '../../components/VehicleList.vue'
import { createVehicleEvidenceFlow, initialVehicleEvidenceState, applicabilityLabels } from '../../services/vehicle-plan-evidence.js'
import { healthLabels, observationStatus, unitLabels } from '../../services/health-observations.js'
import { selectOwnerVehicle } from '../../services/owner-vehicle-selection.js'
const state = reactive(initialAiServicePlanState())
const vehicle = ref(null), evidence = reactive(initialVehicleEvidenceState())
const evidenceFlow = createVehicleEvidenceFlow({ state: evidence, token: () => ownerSession.accessToken, selection: () => ({ vehicle: vehicle.value?.vehicle_id || 0, projects: state.value?.items.map(x => x.project_id) || [] }) })
function clearEvidence() { evidenceFlow.reset(); vehicle.value = null }
function moreExperiences() { if (!vehicle.value || !evidence.value) return; selectOwnerVehicle(vehicle.value); uni.navigateTo({url:'/pages/community/experiences'}) }
watch(() => [vehicle.value?.vehicle_id, state.draft, state.requested, state.value], () => evidenceFlow.reset(), { flush: 'sync' })
let visible = false
const flow = createAiServicePlanFlow({ state, token: () => ownerSession.accessToken,
  confirm: text => new Promise(resolve => uni.showModal({ title: '确认结构化项目建议用途', content: `同意将此问题及公开项目目录发送至第三方DeepSeek以匹配推荐/可选项目。请勿填写姓名、地址、联系方式或车辆标识。本次不读取车辆档案：\n${text}`, success: r => resolve(!!r.confirm), fail: () => resolve(false) })) })
function login() { flow.reset(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function choose(row) { if (!evidenceFlow.canChoose(row.project_id)) return; const route = flow.projectRoute(row); if (route) uni.navigateTo({ url: route }) }
onShow(() => { visible = true; flow.load() })
onHide(() => { visible = false; flow.reset(); clearEvidence() })
onUnload(() => { visible = false; flow.reset(); clearEvidence() })
watch(() => ownerSession.accessToken, () => { flow.reset(); clearEvidence(); if (visible) flow.load() }, { flush: 'sync' })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">结构化项目建议</text>
    <text class="reservation-copy">先从公开目录匹配项目，再核对本车证据、商家实际报价与时段。候选建议不能证明故障或技术必要性。</text>
    <view v-if="ownerSession.accessToken" class="reservation-panel">
      <text class="reservation-heading">可选：核对本车证据</text>
      <VehicleList :selected-id="vehicle?.vehicle_id || 0" @select="vehicle=$event" />
      <text>车辆与读数只在平台内展示，不发送至第三方AI。车型为本人登记，尚未经识别核验；同款经验不能证明本车故障或必做项目。</text>
      <button @tap="clearEvidence">仅按问题和公开目录选择</button>
      <button :disabled="!vehicle || !state.value || evidence.busy || state.busy" @tap="evidenceFlow.load()">读取本车证据并核对本次项目</button>
      <text v-if="vehicle && !evidence.value">已选车辆，继续选择项目前请读取本车证据。</text>
      <text v-if="evidence.busy">正在读取当前车辆证据…</text>
      <text v-if="evidence.message" role="status">{{ evidence.message }}</text>
    </view>
    <view v-if="evidence.value" class="reservation-panel">
      <text class="reservation-heading">{{ evidence.value.vehicle.model_name || '有效车型未知' }} · 本人登记未核验</text>
      <view v-for="o in evidence.value.observations.list" :key="o.metric" class="plan-row">
        <text>{{ healthLabels[o.metric] }} · {{ observationStatus[o.status] }}</text>
        <template v-if="o.status==='RECORDED_UNVERIFIED'">
          <text>{{ o.measurement.measurement_name }}：{{ o.measurement.value }} {{ unitLabels[o.measurement.unit] }}</text>
          <text>测量日 {{ o.measurement.measured_on }} · 方法 {{ o.measurement.method }} · 来源档案 #{{ o.archive_id }}（版本 {{ o.revision }}）</text>
        </template>
      </view>
      <text class="reservation-heading">同款获批施工经验（最多20条）</text>
      <text v-if="!evidence.value.experiences.items.length">暂无可展示经验；不据此推断适用性。</text>
      <text v-for="x in evidence.value.experiences.items" :key="x.experience_id">{{ x.summary.recorded_month }} · 工时 {{ x.summary.work_minutes }} 分钟 · 配件类别 {{ x.summary.part_kinds }} 种</text>
      <button v-if="evidence.value.experiences.next_cursor" @tap="moreExperiences">查看同款经验分页</button>
    </view>
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
        <template v-if="evidence.value">
          <view v-for="a in evidence.value.applicability.filter(x=>x.project_id===row.project_id)" :key="a.project_id">
            <text>{{ applicabilityLabels[a.verdict] }}</text>
            <text v-if="a.source_reference">来源：{{ a.source_reference }} · 规则版本 {{ a.rule_version }}（来源真实性尚未核验）</text>
          </view>
        </template>
        <button :disabled="state.busy || !evidenceFlow.canChoose(row.project_id)" @tap="choose(row)">核对当前项目与商家报价</button>
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
