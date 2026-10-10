<script setup>
import { reactive, ref } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { publicationApi } from '../../services/experience-publication.js'
import { operatorToken, setOperatorSession } from '../../services/operator-session.js'
const form = reactive({ account: '', password: '', sms: '' }), busy = ref(false), message = ref('')
let visible = false, generation = 0
function clear() { visible = false; generation++; form.password = ''; form.sms = ''; busy.value = false; message.value = '' }
async function submit(code) {
  if (busy.value || !visible) return
  const version = generation; busy.value = true; message.value = ''
  try {
    const result = code ? await publicationApi.code(form.account, form.password) : await publicationApi.login(form.account, form.password, form.sms)
    if (!visible || generation !== version) return
    if (code) message.value = '验证码已发送到账号登记手机号，五分钟内有效'
    else { setOperatorSession(result); form.password = ''; form.sms = ''; uni.redirectTo({ url: '/pages/operator/review' }) }
  } catch (e) { if (visible && generation === version) message.value = e.message || '登录未成功，请重试' }
  finally { if (visible && generation === version) busy.value = false }
}
onShow(() => { visible = true; if (operatorToken()) uni.redirectTo({ url: '/pages/operator/review' }) })
onHide(clear); onUnload(clear)
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">运营审核登录</text>
    <text class="reservation-copy">使用独立运营账号、密码和短信验证码登录。审核权限由后台配置，车主或员工登录不能代替。</text>
    <view class="reservation-panel">
      <text>运营账号</text><input v-model="form.account" maxlength="64" :disabled="busy" placeholder="输入运营账号" />
      <text>密码</text><input v-model="form.password" password maxlength="72" :disabled="busy" placeholder="输入密码" />
      <button :disabled="busy" @tap="submit(true)">发送短信验证码</button>
      <text>短信验证码</text><input v-model="form.sms" type="number" maxlength="6" :disabled="busy" placeholder="六位验证码" />
      <button class="reservation-primary" :disabled="busy" :loading="busy" @tap="submit(false)">登录审核工作区</button>
    </view>
    <text v-if="message" role="status">{{ message }}</text>
    <text class="reservation-copy">登录有效期为十五分钟，失效后需重新验证。账号与短信服务由运营管理员配置。</text>
  </view>
</template>

<style scoped>input{padding:20rpx;border:1rpx solid #e5e6eb;border-radius:12rpx;min-height:48rpx}</style>
