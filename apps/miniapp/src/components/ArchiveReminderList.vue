<script setup>
import { reminderLabels, reminderStatusLabels, reminderSourceLabels } from '../services/archive-reminders.js'
defineProps({ rows: { type: Array, required: true }, vehicleId: { type: Number, required: true } })
function open(row, vehicle) { uni.navigateTo({ url: `/pages/archive/reminder?vehicle_id=${vehicle}&archive_id=${row.archive_id}` }) }
function archive() { uni.switchTab({ url: '/pages/archive/index' }) }
</script>

<template>
  <view class="reminders">
    <view v-for="row in rows" :key="row.archive_type" class="item">
      <view class="line"><text class="name">{{ reminderLabels[row.archive_type] }}</text><text :class="row.status === 'DUE' ? 'due' : 'status'">{{ reminderStatusLabels[row.status] }}</text></view>
      <text v-if="row.due_date" class="detail">个人目标日期：{{ row.due_date }}</text>
      <text v-if="row.due_mileage !== null" class="detail">个人目标里程：{{ row.due_mileage }} 公里</text>
      <text v-if="row.archive_id" class="detail">来源：本人{{ reminderSourceLabels[row.archive_type] }}记录 · {{ row.recorded_date || '日期未填写' }}</text>
      <text v-if="row.triggered_by.length" class="due-copy">{{ row.triggered_by.includes('DATE') ? '目标日期已到。' : '' }}{{ row.triggered_by.includes('MILEAGE') ? '登记里程已达到目标。' : '' }}</text>
      <button v-if="row.archive_id" class="action" @tap="open(row, vehicleId)">查看来源与目标设置</button>
      <button v-else class="action" @tap="archive">去档案选择来源并设置</button>
    </view>
  </view>
</template>

<style scoped>
.item { padding: 24rpx 0; border-top: 1rpx solid #e5e6eb; margin-top: 18rpx; }
.line { display: flex; justify-content: space-between; align-items: center; }
.name { color: #1d2129; font-size: 27rpx; font-weight: 600; }
.status { color: #697585; font-size: 23rpx; }
.due { color: #b42318; font-size: 24rpx; font-weight: 600; }
.detail { display: block; margin-top: 12rpx; color: #697585; font-size: 24rpx; line-height: 36rpx; }
.due-copy { display: block; margin-top: 12rpx; color: #b42318; font-size: 24rpx; line-height: 36rpx; }
.action { margin: 18rpx 0 0; padding: 0 20rpx; background: #eef8f0; color: #008f24; border-radius: 12rpx; font-size: 24rpx; }
.action::after { border: 0; }
</style>
