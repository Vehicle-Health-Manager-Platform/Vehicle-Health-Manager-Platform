<script setup>
import { reactive, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { initialLocationState, createLocationFlow } from '../../services/merchant-location.js'
import { imageRequestKey } from '../../services/private-images.js'
const state = reactive(initialLocationState())
const flow = createLocationFlow({ state, token: () => merchantSession.accessToken, newKey: imageRequestKey })
let visible = false
watch(() => merchantSession.accessToken, () => { flow.reset(); if (visible && merchantSession.accessToken) flow.load() }, { flush: 'sync' })
onShow(() => { visible = true; if (merchantSession.accessToken) flow.load() })
onHide(() => { visible = false; flow.suspend() })
onUnload(() => { visible = false; flow.reset() })
function login() { flow.reset(); clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
</script>
<template>
  <view class="page">
    <text class="title">门店位置</text>
    <text class="copy">确认本店的 GCJ02 经纬度，让车主查看直线距离。请核对坐标来源；地址和旧资料中的坐标不会自动用于计算。</text>
    <button class="action" @tap="uni.navigateTo({ url: '/pages/merchant/profile' })">返回门店资料</button>
    <view v-if="state.loaded" class="panel">
      <text class="copy">{{ state.location ? '本店已确认位置（不代表平台实地核验）' : '尚未确认位置，车主将看到距离未知' }}</text>
      <text v-if="!state.canEdit" class="copy">店员可查看，只有店长可以维护。</text>
      <text class="label">GCJ02 经度</text>
      <input v-model="state.lng" class="field" :disabled="!state.canEdit || state.busy || state.saved" maxlength="12" placeholder="-180 至 180，最多七位小数" />
      <text class="label">GCJ02 纬度</text>
      <input v-model="state.lat" class="field" :disabled="!state.canEdit || state.busy || state.saved" maxlength="12" placeholder="-90 至 90，最多七位小数" />
      <checkbox-group v-if="state.canEdit" @change="state.confirmed = $event.detail.value.includes('confirmed')">
        <label class="copy"><checkbox value="confirmed" :checked="state.confirmed" :disabled="state.busy || state.saved" />我已核对坐标系及位置，确认这是本店的 GCJ02 坐标</label>
      </checkbox-group>
      <button v-if="state.canEdit && !state.saved" class="primary" :disabled="state.busy || !state.confirmed" @tap="flow.save(false)">保存确认的位置</button>
      <button v-if="state.canEdit && state.location && !state.saved" class="action" :disabled="state.busy" @tap="flow.save(true)">清除门店位置</button>
      <button v-if="state.canEdit && state.saved" class="action" @tap="flow.edit">继续修改</button>
    </view>
    <text v-if="state.busy" class="copy">正在读取或保存门店位置…</text>
    <text v-if="state.message" class="copy" :class="state.saved ? 'success' : 'error'">{{ state.message }}</text>
    <button v-if="!merchantSession.accessToken || ['unauthorized','forbidden'].includes(state.failureKind)" class="action" @tap="login">登录门店账号</button>
    <button v-else class="action" :disabled="state.busy" @tap="flow.load">重新载入门店位置</button>
  </view>
</template>
<style scoped>
.page{min-height:100vh;box-sizing:border-box;padding:40rpx 32rpx;background:#f2f3f5}.title{display:block;font-size:38rpx;font-weight:700;color:#1d2129}.panel{display:flex;flex-direction:column;background:#fff;padding:28rpx;border-radius:22rpx;margin-top:24rpx}.copy{display:block;font-size:25rpx;line-height:40rpx;color:#4e5969;margin-top:18rpx}.label{margin-top:24rpx;font-size:26rpx}.field{padding:22rpx;background:#f2f3f5;margin-top:12rpx;border-radius:12rpx}.action{background:#eef8f0;color:#008f24}.primary{background:#00b42a;color:white}button{margin-top:24rpx;font-size:27rpx;border-radius:14rpx}button::after{border:0}.error{color:#b42318}.success{color:#008f24}
</style>
