<script setup>
import { computed } from 'vue'
import { useRoleIdentity } from '../services/role-identity.js'

const props = defineProps({
  role: { type: String, required: true },
  title: { type: String, required: true },
  description: { type: String, required: true },
  steps: { type: Array, required: true },
})

const { operation, phase, message, accessToken, phoneBound, requiresLogin, bindingToken, employeeCode,
  merchantAccount, merchantPassword, smsCode, busy, canRetry, tryLogin, tryBind,
  tryBindPhone, tryLogout, tryMerchantCode, tryMerchantLogin, retry, restartLogin } = useRoleIdentity(props.role)
const stateLabel = computed(() => ({ idle: '登录提示', loading: '正在处理', success: '操作完成', error: '需要处理' })[phase.value])
const codeValid = computed(() => /^\d{6}$/.test(smsCode.value.trim()))

function openOwnerTabs() { uni.switchTab({ url: '/pages/home/index' }) }
function openMerchantProjects() { uni.navigateTo({ url: '/pages/merchant/projects' }) }
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
      <view class="status-card" :class="phase" role="status" aria-live="polite">
        <text class="state-label">{{ stateLabel }}</text>
        <text class="state-text">{{ message }}</text>
        <button v-if="canRetry" class="retry-button" :disabled="busy" @tap="retry">重试本次操作</button>
        <button v-if="requiresLogin" class="retry-button" :disabled="busy" @tap="restartLogin">重新登录</button>
      </view>
      <button v-if="role !== 'merchant' && !accessToken" class="login-button" :loading="operation === 'login'" :disabled="busy" @tap="tryLogin">微信登录</button>
      <!-- #ifdef MP-WEIXIN -->
      <button v-if="role === 'owner' && accessToken && !phoneBound" class="login-button" open-type="getPhoneNumber" :loading="operation === 'phone'" :disabled="busy" @getphonenumber="tryBindPhone">授权并绑定手机号</button>
      <!-- #endif -->
      <!-- #ifndef MP-WEIXIN -->
      <text v-if="role === 'owner' && accessToken && !phoneBound" class="hint">手机号授权请在微信小程序中完成。</text>
      <!-- #endif -->
      <text v-if="phoneBound" class="hint">手机号已绑定</text>
      <button v-if="role === 'owner' && accessToken" class="login-button" :disabled="busy" @tap="openOwnerTabs">进入车主首页</button>
      <button v-if="role === 'merchant' && accessToken" class="login-button" :disabled="busy" @tap="openMerchantProjects">管理本店服务</button>
      <view v-if="accessToken" class="signed-in">
        <text class="hint">已登录。车辆、订单等业务内容待接入。</text>
        <button class="logout-button" :loading="operation === 'logout'" :disabled="busy" @tap="tryLogout">退出登录</button>
      </view>
      <view v-if="role === 'technician' && bindingToken && !accessToken" class="binding">
        <text class="input-label">员工码</text>
        <input v-model="employeeCode" password :disabled="busy" placeholder="商家发放的员工码" />
        <button class="login-button" :loading="operation === 'bind'" :disabled="busy || !employeeCode.trim()" @tap="tryBind">绑定技师身份</button>
      </view>
      <view v-if="role === 'merchant' && !accessToken" class="binding">
        <text class="input-label">商家账号</text>
        <input v-model="merchantAccount" :disabled="busy" placeholder="商家账号" maxlength="64" />
        <text class="input-label">密码</text>
        <input v-model="merchantPassword" :disabled="busy" password placeholder="密码" maxlength="256" />
        <button class="login-button" :loading="operation === 'code'" :disabled="busy || !merchantAccount.trim() || !merchantPassword.trim()" @tap="tryMerchantCode">获取短信验证码</button>
        <text class="input-label">短信验证码</text>
        <input v-model="smsCode" :disabled="busy" type="number" placeholder="六位短信验证码" maxlength="6" />
        <button class="login-button" :loading="operation === 'merchant-login'" :disabled="busy || !merchantAccount.trim() || !merchantPassword.trim() || !codeValid" @tap="tryMerchantLogin">登录商家端</button>
        <text class="hint">验证码发送到账号绑定的手机号；账号与短信服务需由管理员开通。</text>
      </view>
    </view>
    <text class="footer">角色入口仅供测试预览，业务权限由服务端核验。</text>
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
.status-card { padding: 24rpx; border-radius: 16rpx; background: #f2f3f5; }
.status-card.error { background: #fff2e8; }
.status-card.success { background: #e8f8eb; }
.state-label { display: block; color: #4e5969; font-size: 23rpx; }
.state-text { display: block; margin-top: 10rpx; font-size: 27rpx; line-height: 40rpx; }
.login-button { margin-top: 26rpx; border-radius: 16rpx; background: #00b42a; color: #fff; font-size: 28rpx; }
.login-button::after { border: 0; }
.logout-button, .retry-button { margin-top: 16rpx; border-radius: 16rpx; color: #4e5969; background: #f2f3f5; font-size: 26rpx; }
.retry-button { background: #fff; }
.binding { margin-top: 20rpx; }
.input-label { display: block; margin: 20rpx 0 10rpx; color: #4e5969; font-size: 24rpx; }
.binding input { padding: 20rpx; border: 1rpx solid #d9dfe8; border-radius: 12rpx; }
.hint, .footer { display: block; margin-top: 20rpx; color: #697585; font-size: 23rpx; line-height: 36rpx; }
.footer { text-align: center; }
</style>
