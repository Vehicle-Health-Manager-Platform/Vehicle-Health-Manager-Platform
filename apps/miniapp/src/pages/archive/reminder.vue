<script setup>
import { reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { archiveReminderApi, reminderLabels, reminderStatusLabels } from '../../services/archive-reminders.js'
import { createReminderEditFlow, initialReminderEditState } from '../../services/archive-reminder-flow.js'
import { imageRequestKey } from '../../services/private-images.js'

const state = reactive(initialReminderEditState())
const confirm = (title, content) => new Promise(resolve => uni.showModal({ title, content, success: result => resolve(Boolean(result.confirm)), fail: () => resolve(false) }))
const flow = createReminderEditFlow({ state, api: archiveReminderApi, token: () => ownerSession.accessToken, newKey: imageRequestKey,
  confirmReplacement: () => confirm('替换本类提醒', '本次将改用当前档案作为来源，并替换原目标。原始档案不会被改写。'),
  confirmDisable: () => confirm('停用提醒', '停用后不再提示本类目标，原目标及来源将保留，可重新启用。'),
})
let visible = false
onLoad(query => flow.route(Number(query.archive_id), Number(query.vehicle_id)))
onShow(() => { visible = true; flow.resume() })
onHide(() => { visible = false; flow.suspend() })
onUnload(() => { visible = false; flow.reset() })
watch(() => ownerSession.accessToken, () => { flow.reset(); if (visible) flow.resume() }, { flush: 'sync' })
function login() { flow.reset(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
</script>

<template>
  <view class="page">
    <text class="title">{{ reminderLabels[state.context?.archive_type] || '设置个人目标' }}</text>
    <text class="hint">目标由本人填写，达到日期或保养里程时提示。请按自己的养护计划、保单或年检资料核对目标。</text>
    <text v-if="state.busy" class="hint">正在处理…</text>
    <view v-if="state.context" class="card">
      <text class="heading">当前选择的来源档案</text>
      <text class="hint">{{ reminderLabels[state.context.archive_type] }} · {{ state.context.recorded_date || '记录日期未填写' }}</text>
      <text class="hint">当前登记里程：{{ state.context.current_mileage === null ? '未知' : `${state.context.current_mileage} 公里` }}</text>
      <text class="hint">本类设置：{{ reminderStatusLabels[state.context.setting.status] }}</text>
      <text v-if="state.context.setting.revision > 0 && state.context.setting.archive_id !== state.context.archive_id" class="notice">当前目标来自其他或已失效档案。保存前将确认替换来源与目标。</text>
      <view v-if="state.context.setting.archive_id && state.context.setting.archive_id !== state.context.archive_id">
        <text class="hint">原来源记录：{{ state.context.setting.recorded_date || '日期未填写' }}</text>
        <text v-if="state.context.setting.due_date" class="hint">原日期目标：{{ state.context.setting.due_date }}</text>
        <text v-if="state.context.setting.due_mileage !== null" class="hint">原里程目标：{{ state.context.setting.due_mileage }} 公里</text>
      </view>
      <text class="label">{{ state.context.archive_type === 1 ? '目标日期（可选）' : '目标日期' }}</text>
      <picker mode="date" :value="state.date" :disabled="state.busy" @change="state.date = $event.detail.value"><view class="input">{{ state.date || '选择日期' }}</view></picker>
      <button v-if="state.date && state.context.archive_type === 1" class="secondary" :disabled="state.busy" @tap="state.date = ''">清除日期目标</button>
      <template v-if="state.context.archive_type === 1">
        <text class="label">目标登记里程（可选，公里）</text>
        <input v-model="state.mileage" class="input" type="number" maxlength="10" :disabled="state.busy" placeholder="例如 12000；由本人确定" />
        <text class="hint">日期、里程至少填写一项，任一达到即提示。没有提前提醒。</text>
      </template>
      <button :disabled="state.busy || state.kind === 'conflict'" :loading="state.busy" @tap="flow.save(true)">{{ state.context.setting.active ? '保存目标' : '启用并保存目标' }}</button>
      <button v-if="state.context.setting.active && state.context.setting.archive_id === state.context.archive_id" class="danger" :disabled="state.busy || state.kind === 'conflict'" @tap="flow.save(false)">停用提醒</button>
    </view>
    <text v-if="state.message" class="message" :class="{ error: state.kind }">{{ state.message }}</text>
    <button v-if="['unauthorized', 'forbidden'].includes(state.kind) || !ownerSession.accessToken" @tap="login">登录车主账号</button>
    <button v-else class="secondary" :disabled="state.busy" @tap="flow.load">{{ state.kind === 'conflict' ? '刷新并复核目标与来源' : '刷新设置' }}</button>
  </view>
</template>

<style scoped>
.page { padding: 32rpx; }
.title { display: block; font-size: 36rpx; font-weight: 700; }
.hint { display: block; margin-top: 16rpx; color: #697585; font-size: 24rpx; line-height: 38rpx; }
.card { margin-top: 24rpx; padding: 28rpx; background: #fff; border-radius: 20rpx; }
.heading { font-size: 28rpx; font-weight: 600; }
.label { display: block; margin: 24rpx 0 12rpx; font-size: 25rpx; }
.input { padding: 20rpx; border: 1rpx solid #d9dfe8; border-radius: 12rpx; font-size: 26rpx; }
.notice,.message { display: block; margin-top: 20rpx; font-size: 25rpx; line-height: 38rpx; color: #008f24; }
.error { color: #b42318; }
button { margin-top: 20rpx; background: #00b42a; color: #fff; border-radius: 14rpx; font-size: 26rpx; }
button::after { border: 0; }
.secondary { background: #eef8f0; color: #008f24; }
.danger { background: #fff2e8; color: #b42318; }
</style>
