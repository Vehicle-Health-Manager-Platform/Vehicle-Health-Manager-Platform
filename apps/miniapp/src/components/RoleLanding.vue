<script setup>
import { computed, ref } from 'vue'
import { bindTechnician, requestWechatLogin } from '../services/wechat-auth'

const props = defineProps({
  role: { type: String, required: true },
  title: { type: String, required: true },
  description: { type: String, required: true },
  steps: { type: Array, required: true },
})

const busy = ref(false)
const status = ref('微信登录服务端已接入；真实联调仍需私有凭据和后端地址')
const bindingToken = ref('')
const employeeCode = ref('')
const supportsWechatLogin = computed(() => props.role !== 'merchant')

async function tryLogin() {
  if (busy.value) return
  busy.value = true
  status.value = '正在请求微信身份…'
  try {
    const result = await requestWechatLogin(props.role)
    bindingToken.value = result.binding_token || ''
    if (result.status === 'BIND_REQUIRED') {
      status.value = '微信身份已验证，请输入商家发放的员工码完成技师绑定'
    } else if (props.role === 'owner') {
      status.value = result.user?.phone_bound ? '车主身份已验证' : '车主身份已验证；手机号尚未绑定'
    } else {
      status.value = '技师身份与商家权限已验证'
    }
  } catch (error) {
    status.value = error.message || '登录请求失败，请检查网络与服务端配置'
  } finally {
    busy.value = false
  }
}

async function tryBind() {
  if (busy.value) return
  busy.value = true
  try {
    await bindTechnician(bindingToken.value, employeeCode.value.trim())
    bindingToken.value = ''
    employeeCode.value = ''
    status.value = '技师身份绑定成功，业务权限已验证'
  } catch (error) {
    status.value = error.message || '员工码绑定失败'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <view class="page">
    <view class="hero">
      <text class="eyebrow">{{ role.toUpperCase() }} · S0 预览</text>
      <text class="title">{{ title }}</text>
      <text class="description">{{ description }}</text>
    </view>

    <view class="section">
      <text class="section-title">将要接入的工作流程</text>
      <view v-for="(step, index) in steps" :key="step" class="step">
        <text class="number">{{ index + 1 }}</text>
        <text class="step-text">{{ step }}</text>
      </view>
    </view>

    <view class="state">
      <text class="state-label">接入状态</text>
      <text class="state-text">{{ status }}</text>
      <button v-if="supportsWechatLogin" class="login-button" :loading="busy" :disabled="busy" @tap="tryLogin">验证微信登录接口</button>
      <view v-if="role === 'technician' && bindingToken" class="binding">
        <input v-model="employeeCode" password placeholder="商家发放的员工码" />
        <button class="login-button" :loading="busy" :disabled="busy || !employeeCode.trim()" @tap="tryBind">绑定技师身份</button>
      </view>
      <text v-else class="hint">商家按原文使用账号密码及短信验证，接入后端后开放。</text>
    </view>
    <text class="footer">角色入口仅供测试预览，不能替代后端授权。</text>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; padding: 36rpx 32rpx 72rpx; box-sizing: border-box; }
.hero { display: flex; flex-direction: column; padding: 28rpx 10rpx 40rpx; }
.eyebrow { color: #008f24; font-size: 23rpx; font-weight: 600; letter-spacing: 2rpx; }
.title { margin-top: 14rpx; font-size: 48rpx; font-weight: 700; }
.description { margin-top: 14rpx; color: #4e5969; font-size: 27rpx; line-height: 40rpx; }
.section, .state { margin-bottom: 24rpx; padding: 32rpx; border-radius: 24rpx; background: #fff; }
.section-title { display: block; margin-bottom: 18rpx; font-size: 28rpx; font-weight: 650; }
.step { display: flex; align-items: center; min-height: 72rpx; }
.number { display: flex; align-items: center; justify-content: center; width: 38rpx; height: 38rpx; border-radius: 50%; background: #e8f8eb; color: #008f24; font-size: 22rpx; font-weight: 600; }
.step-text { margin-left: 22rpx; color: #4e5969; font-size: 26rpx; }
.state-label { display: block; color: #86909c; font-size: 23rpx; }
.state-text { display: block; margin-top: 10rpx; font-size: 27rpx; line-height: 40rpx; }
.login-button { margin-top: 26rpx; border-radius: 16rpx; background: #00b42a; color: #fff; font-size: 28rpx; }
.login-button::after { border: 0; }
.binding { margin-top: 20rpx; }
.binding input { padding: 20rpx; border: 1rpx solid #d9dfe8; border-radius: 12rpx; }
.hint, .footer { display: block; margin-top: 20rpx; color: #86909c; font-size: 23rpx; line-height: 36rpx; }
.footer { text-align: center; }
</style>
