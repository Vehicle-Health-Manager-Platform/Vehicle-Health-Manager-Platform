<script setup>
import { reactive, toRefs, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { archiveShareApi } from '../../services/archive-shares.js'
import { createArchiveShareReader, initialArchiveShareReaderState } from '../../services/archive-share-reader.js'

const state = reactive(initialArchiveShareReaderState())
const { summary, expiresAt, busy, message, kind } = toRefs(state)
const reader = createArchiveShareReader({ state, api: archiveShareApi, token: () => ownerSession.accessToken })
const types = ['保养', '维修', '保险', '事故', '改装', '违章', '年检']
const sources = { 1: '拍照录入', 3: '手动录入', 4: '施工归档' }
function load() { return reader.load() }
function login() { reader.suspend(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
onLoad(query => { reader.setCapability(query.token); uni.hideShareMenu?.() })
onShow(() => reader.resume())
onHide(() => reader.suspend())
onUnload(() => reader.dispose())
watch(() => ownerSession.accessToken, () => reader.invalidate(), { flush: 'sync' })
</script>

<template>
  <view class="page">
    <text class="title">车辆档案只读摘要</text>
    <text class="hint">内容固定于分享创建时，仅供参考。后续档案不自动加入。不提供照片、签字或 PDF。</text>
    <text v-if="busy" class="message">正在校验分享链接…</text>
    <text v-if="message" class="message">{{ message }}</text>
    <button v-if="kind === 'unauthorized'" @tap="login">登录车主账号</button>
    <button v-if="ownerSession.accessToken && kind !== 'gone'" :disabled="busy" @tap="load">重新校验与查看</button>
    <view v-if="summary" class="card">
      <text class="heading">档案摘要</text>
      <text class="hint">摘要生成：{{ new Date(summary.generated_at).toLocaleString() }}</text>
      <text class="hint">链接到期：{{ new Date(expiresAt).toLocaleString() }}</text>
      <text class="hint">日期范围：{{ summary.from ? `${summary.from} 至 ${summary.to}` : '全部可见记录' }}</text>
      <text class="hint">登记里程：{{ summary.registered_mileage === null ? '未填写' : `${summary.registered_mileage} 公里` }}</text>
      <text v-if="summary.undated_count" class="hint">日期筛选排除 {{ summary.undated_count }} 条无日期记录</text>
      <text v-if="!summary.entries.length" class="hint">此范围暂无记录</text>
      <view v-for="(row, index) in summary.entries" :key="index" class="record">
        <text class="heading">{{ types[row.archive_type - 1] }}</text>
        <text class="hint">{{ row.recorded_date || '日期未填写' }} · {{ sources[row.input_type] }}</text>
        <text v-if="row.mileage !== null" class="hint">里程：{{ row.mileage }} 公里</text>
        <text v-if="row.work_minutes" class="hint">报工：{{ row.work_minutes }} 分钟</text>
        <text v-if="row.test_mode" class="test">测试数据，非正式施工验收</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page { padding: 32rpx; }
.title { display: block; font-size: 36rpx; font-weight: 700; }
.hint { display: block; margin-top: 16rpx; color: #63685e; font-size: 24rpx; line-height: 38rpx; }
.card { margin-top: 24rpx; padding: 28rpx; background: #fcfbf8; border-radius: 20rpx; }
.heading { font-size: 28rpx; font-weight: 600; }
.record { padding: 24rpx 0; margin-top: 20rpx; border-top: 1rpx solid #e0ded6; }
.message { display: block; margin: 24rpx 0; font-size: 26rpx; line-height: 40rpx; color: #63685e; }
.test { display: block; margin-top: 16rpx; color: #b3261e; font-size: 24rpx; }
button { margin-top: 20rpx; background: #0b6b43; color: #fcfbf8; border-radius: 14rpx; font-size: 26rpx; }
button::after { border: 0; }
</style>
