<script setup>
import { reactive, watch } from 'vue'
import { onHide, onShow, onUnload } from '@dcloudio/uni-app'
import { clearMerchantSession, merchantSession } from '../../services/merchant-session.js'
import { merchantProfileApi, merchantStatusLabel, merchantTypeLabel } from '../../services/merchant-profile.js'
import { createProfileFlow, initialProfileState } from '../../services/profile-flow.js'
import { imageRequestKey } from '../../services/private-images.js'

const state = reactive(initialProfileState())
let visible = false
const token = () => merchantSession.accessToken
const flow = createProfileFlow({ state, api: merchantProfileApi, token, newKey: imageRequestKey })
// 已保存后表单只读：字段由服务端回写，继续改要显式点「继续修改」。
const locked = () => state.saving || Boolean(state.saved)
const editable = () => state.canEdit && !locked()

watch(token, () => { flow.reset(); if (visible && token()) flow.load() }, { flush: 'sync' })
onShow(() => { visible = true; if (token()) flow.load() })
onHide(() => { visible = false; flow.suspend() })
onUnload(() => { visible = false; flow.reset() })

function login() { clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
function openStaff() { uni.navigateTo({ url: '/pages/merchant/staff' }) }
</script>

<template>
  <view class="page">
    <text class="eyebrow">门店端</text>
    <text class="title">门店资料</text>
    <view v-if="!merchantSession.accessToken" class="panel">
      <text>请先登录门店账号。</text>
      <button @tap="login">前往门店登录</button>
    </view>
    <template v-else>
      <button @tap="openStaff">返回本店员工</button>
      <text v-if="state.busy" class="copy" role="status">正在加载门店资料…</text>
      <view v-if="state.message" class="panel" role="status">
        <text class="error" data-testid="profile-message">{{ state.message }}</text>
        <button v-if="['unauthorized', 'forbidden'].includes(state.failureKind)" @tap="login">重新登录</button>
        <button v-else :disabled="state.busy" @tap="flow.load">重试加载</button>
      </view>
      <template v-if="state.loaded">
        <view class="panel">
          <text class="heading">不可修改的信息</text>
          <text class="copy">{{ merchantTypeLabel(state.merchantType) }} · {{ merchantStatusLabel(state.status) }}</text>
          <text class="copy">区域编码 {{ state.regionCode || '未设置' }}</text>
          <text class="copy">门店类型、经营状态、区域与资质由入驻审核与运营维护，不经本页修改。</text>
        </view>
        <view class="panel">
          <text class="heading">对外资料</text>
          <text v-if="!state.canEdit" class="copy" data-testid="profile-readonly-notice">
            只有店长可以修改本店资料；店员可以查看。
          </text>
          <text class="label">门店名称</text>
          <input v-model="state.name" class="field" :disabled="!editable()" maxlength="64" placeholder="门店名称（2–64 字）" />
          <text class="label">门店地址</text>
          <input v-model="state.address" class="field" :disabled="!editable()" maxlength="256" placeholder="门店地址" />
          <text class="label">联系电话</text>
          <input v-model="state.contactPhone" class="field" :disabled="!editable()" type="number" maxlength="11" placeholder="可接收短信的手机号" />
          <text class="label">经度（可留空）</text>
          <input v-model="state.lng" class="field" :disabled="!editable()" placeholder="例如 113.2644" />
          <text class="label">纬度（可留空）</text>
          <input v-model="state.lat" class="field" :disabled="!editable()" placeholder="例如 23.1291" />
          <text v-if="state.saveMessage" :class="state.saved ? 'success' : 'error'" role="status" data-testid="profile-save-message">{{ state.saveMessage }}</text>
          <button v-if="state.canEdit && !state.saved" class="primary" :disabled="state.saving" :loading="state.saving" @tap="flow.save">保存门店资料</button>
          <button v-if="state.canEdit && state.saved" @tap="flow.edit">继续修改</button>
          <button :disabled="state.saving" @tap="flow.load">重新载入</button>
        </view>
      </template>
    </template>
  </view>
</template>

<style scoped>
.page { min-height:100vh;box-sizing:border-box;padding:52rpx 32rpx 96rpx;background:#f2f3f5; }.eyebrow { display:block;color:#008f24;font-size:23rpx; }.title { display:block;font-size:42rpx;font-weight:700;margin:14rpx 0 28rpx;color:#1d2129; }
.panel { display:flex;flex-direction:column;background:white;padding:30rpx;border-radius:24rpx;margin:24rpx 0; }.heading { font-size:30rpx;font-weight:600;color:#1d2129; }.copy { font-size:25rpx;color:#4e5969;margin-top:16rpx;line-height:38rpx; }.label { display:block;margin-top:20rpx;color:#4e5969;font-size:24rpx; }.error { color:#b42318;margin-top:16rpx; }.success { color:#008f24;margin-top:16rpx; }
.field { padding:22rpx;background:#f2f3f5;border-radius:12rpx;margin-top:8rpx;font-size:28rpx; }
button { margin:22rpx 0 0;background:#eef8f0;color:#008f24;font-size:27rpx;border-radius:14rpx; }button::after { border:0; }.primary { background:#00b42a;color:white; }
</style>
