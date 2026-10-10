<script setup>
import { reactive, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import VehicleList from '../../components/VehicleList.vue'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { selectedOwnerVehicle, selectOwnerVehicle } from '../../services/owner-vehicle-selection.js'
import { imageRequestKey } from '../../services/private-images.js'
import { CARD_STATES, EXPERIENCE_CONSENT_TEXT, experienceCardsApi, initialExperienceState, createExperienceFlow } from '../../services/experience-cards.js'

const vehicle = selectedOwnerVehicle, state = reactive(initialExperienceState()), agreements = ref({})
let visible = false
const flow = createExperienceFlow({ state, api: experienceCardsApi, token: () => ownerSession.accessToken,
  vehicle: () => vehicle.value?.vehicle_id, newKey: imageRequestKey })
function clear() { flow.reset(); agreements.value = {} }
function start() { flow.resume(); flow.load() }
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function agree(card, event) { agreements.value[card.card_id] = event.detail.value.includes('agree') }
async function act(card, action) { await flow.change(card, action, agreements.value[card.card_id] === true); if (!state.kind) agreements.value[card.card_id] = false }
function refresh() { agreements.value = {}; flow.load() }
watch(() => ownerSession.accessToken, () => { clear(); if (visible) start() }, { flush: 'sync' })
watch(() => vehicle.value?.vehicle_id, () => { clear(); if (visible) start() }, { flush: 'sync' })
onShow(() => { visible = true; start() })
onHide(() => { visible = false; clear() })
onUnload(() => { visible = false; clear() })
</script>

<template>
  <view class="reservation-page">
    <text class="reservation-title">我的经验卡片</text>
    <text class="reservation-copy">卡片默认私有，仅本人查看。授权后待审核，当前尚未公开。</text>
    <button v-if="!ownerSession.accessToken" @tap="login">前往车主登录</button>
    <VehicleList :selected-id="vehicle?.vehicle_id || 0" @select="selectOwnerVehicle" />
    <text v-if="state.busy" role="status">正在加载卡片…</text>
    <text v-else-if="state.loaded && !state.rows.length">这辆车还没有经验卡片。新施工完成归档后会生成私有摘要。</text>
    <view v-for="card in state.rows" :key="card.card_id" class="reservation-panel" data-testid="experience-card">
      <text class="reservation-heading">{{ card.title }}</text>
      <text data-testid="experience-state">{{ CARD_STATES[card.status] }}</text>
      <text v-if="card.test_mode" class="notice" data-testid="experience-test">测试施工，未真实扣款；此卡片不能授权送审。</text>
      <text>{{ card.summary.recorded_month }} · 实际工时 {{ card.summary.work_minutes }} 分钟</text>
      <text>{{ card.summary.no_parts ? '本次未使用配件' : `使用 ${card.summary.part_kinds} 种配件` }}</text>
      <text>摘要不含照片、施工原文、配件详情和个人身份信息。</text>
      <button :disabled="state.writing || state.busy" @tap="uni.navigateTo({url:`/pages/order/detail?id=${card.order_id}`})">查看来源订单</button>
      <template v-if="!card.test_mode && card.status !== 'PENDING_REVIEW'">
        <checkbox-group @change="agree(card,$event)"><label class="consent"><checkbox value="agree" :checked="!!agreements[card.card_id]" :disabled="state.writing || state.busy" /><text>{{ EXPERIENCE_CONSENT_TEXT }}</text></label></checkbox-group>
        <button class="reservation-primary" :disabled="!agreements[card.card_id] || state.busy || state.writing" :loading="state.writing" @tap="act(card,'consent')">{{ card.status === 'WITHDRAWN' ? '重新授权送审' : '授权送审' }}</button>
      </template>
      <button v-if="card.status !== 'WITHDRAWN'" :disabled="state.busy || state.writing" @tap="act(card,'withdraw')">{{ card.status === 'DRAFT' ? '保留私有并撤回授权' : '撤回授权' }}</button>
    </view>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录</button>
    <button v-else-if="vehicle" :disabled="state.busy || state.writing" @tap="refresh">刷新本人卡片</button>
    <button v-if="state.loaded && state.rows.length < state.total" :disabled="state.busy || state.writing" @tap="flow.load(true)">加载更多</button>
    <button @tap="uni.navigateBack({fail:()=>uni.switchTab({url:'/pages/archive/index'})})">返回档案</button>
  </view>
</template>
<style src="../../styles/reservations.css"></style>
<style scoped>
.consent{display:flex;align-items:flex-start;gap:12rpx;font-size:26rpx;line-height:40rpx}.notice{padding:18rpx;background:#fff7e6;color:#805300;border-radius:12rpx}
</style>
