<script setup>
import { reactive, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import VehicleList from '../../components/VehicleList.vue'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { selectedOwnerVehicle, selectOwnerVehicle } from '../../services/owner-vehicle-selection.js'
import { publicationApi, initialPublicationState, createPublicationFlow } from '../../services/experience-publication.js'
const vehicle = selectedOwnerVehicle, state = reactive(initialPublicationState())
const flow = createPublicationFlow({ state, api: publicationApi, token: () => ownerSession.accessToken, vehicle: () => vehicle.value?.vehicle_id })
let visible = false
function start() { flow.resume(); flow.load() }
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
watch(() => ownerSession.accessToken, () => { flow.reset(); if (visible) start() }, { flush: 'sync' })
watch(() => vehicle.value?.vehicle_id, () => { flow.reset(); if (visible) start() }, { flush: 'sync' })
onShow(() => { visible = true; start() }); onHide(() => { visible = false; flow.reset() }); onUnload(() => { visible = false; flow.reset() })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">同款施工经验</text>
    <text class="reservation-copy">同一车型、车主授权且审核通过的施工摘要。只展示工时与配件种类，不含照片、施工原文和个人身份。</text>
    <button v-if="!ownerSession.accessToken" @tap="login">前往车主登录</button>
    <VehicleList :selected-id="vehicle?.vehicle_id || 0" @select="selectOwnerVehicle" />
    <text v-if="vehicle && !vehicle.model_id">当前车辆缺少车型信息，暂不能匹配同款经验。</text>
    <text v-if="state.busy" role="status">正在查询同款经验…</text>
    <text v-else-if="state.loaded && !state.rows.length">暂无可展示的同款摘要。车主撤回或来源变化的记录会隐藏。</text>
    <view v-for="card in state.rows" :key="card.experience_id" class="reservation-panel" data-testid="public-experience">
      <text class="reservation-heading">{{ card.title }}</text>
      <text>{{ card.summary.recorded_month }} · 实际工时 {{ card.summary.work_minutes }} 分钟</text>
      <text>{{ card.summary.no_parts ? '本次未使用配件' : `使用 ${card.summary.part_kinds} 种配件` }}</text>
      <text>已授权并通过审核 · {{ card.published_at.slice(0,10) }}</text>
    </view>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录车主账号</button>
    <button v-if="vehicle" :disabled="state.busy" @tap="flow.load()">刷新同款经验</button>
    <button v-if="state.cursor !== null" :disabled="state.busy" @tap="flow.load(true)">继续查询</button>
    <button @tap="uni.navigateTo({url:'/pages/community/my-cards'})">管理我的经验授权</button>
  </view>
</template>
<style src="../../styles/reservations.css"></style>
