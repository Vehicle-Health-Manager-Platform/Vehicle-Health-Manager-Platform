<script setup>
import { reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import ArchiveReminderList from '../../components/ArchiveReminderList.vue'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { archiveReminderApi } from '../../services/archive-reminders.js'
import { createReminderListFlow, initialReminderListState } from '../../services/archive-reminder-flow.js'
const state = reactive(initialReminderListState())
const flow = createReminderListFlow({ state, api: archiveReminderApi, token: () => ownerSession.accessToken })
let vehicle = 0, visible = false
onLoad(query => { vehicle = Number(query.vehicle_id) })
onShow(() => { visible = true; flow.resume(vehicle) })
onHide(() => { visible = false; flow.suspend() })
onUnload(() => { visible = false; flow.suspend() })
watch(() => ownerSession.accessToken, () => { flow.reset(); if (visible) flow.select(vehicle) }, { flush: 'sync' })
function login() { flow.reset(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
</script>
<template>
  <view class="page">
    <text class="title">个人目标与提醒</text>
    <text class="hint">目标关联本人档案，到日期或保养里程才提示。请核对证件资料和自己的养护计划。</text>
    <text v-if="state.busy" class="hint">正在读取目标与来源…</text>
    <view v-if="state.loaded" class="card">
      <text class="hint">业务日期：{{ state.today }}</text>
      <text class="hint">登记里程：{{ state.currentMileage === null ? '未知' : `${state.currentMileage} 公里` }}</text>
      <ArchiveReminderList :rows="state.rows" :vehicle-id="state.vehicleId" />
    </view>
    <text v-if="state.message" class="error">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.kind)" @tap="login">登录车主账号</button>
    <button v-else :disabled="state.busy" @tap="flow.load">刷新提醒</button>
  </view>
</template>
<style scoped>
.page { padding: 32rpx; }.title { display: block; font-size: 36rpx; font-weight: 700; }
.hint { display: block; margin-top: 16rpx; color: #63685e; font-size: 24rpx; line-height: 38rpx; }
.card { padding: 28rpx; margin-top: 24rpx; border-radius: 20rpx; background: #fcfbf8; }
.error { display: block; margin-top: 20rpx; color: #b3261e; font-size: 25rpx; }
button { margin-top: 20rpx; background: #e9f2ec; color: #074d30; border-radius: 14rpx; font-size: 26rpx; }button::after { border: 0; }
</style>
