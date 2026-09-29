<script setup>
import { ref } from 'vue'

const result = ref('')
const loading = ref(false)
const isDev = import.meta.env.DEV

async function verifyOwnership(vehicleId) {
  loading.value = true
  result.value = ''
  try {
    const login = await fetch('/api/dev/token', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ user_id: '1001' }),
    })
    const loginBody = await login.json()
    if (!login.ok) throw new Error(loginBody.message || '本地演示登录失败')
    const response = await fetch(`/api/demo/vehicles/${vehicleId}`, {
      headers: { Authorization: `Bearer ${loginBody.data.access_token}` },
    })
    const body = await response.json()
    result.value = `HTTP ${response.status} · 业务码 ${body.code} · ${body.message}`
  } catch (error) {
    result.value = error.message || '请求失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section v-if="isDev" class="local-demo">
    <h2>S0 本地权限样例</h2>
    <p>测试身份为用户 1001；本人车辆 1001 应成功，其他用户车辆 2001 应返回 40300。</p>
    <div class="actions">
      <button :disabled="loading" @click="verifyOwnership(1001)">读取本人车辆</button>
      <button :disabled="loading" @click="verifyOwnership(2001)">验证越权拦截</button>
    </div>
    <p v-if="result" role="status">{{ result }}</p>
  </section>
</template>

<style scoped>
.local-demo { max-width: 560px; }
.local-demo h2 { margin-top: 0; font-size: 18px; }
.actions { display: flex; flex-wrap: wrap; gap: 12px; }
button { min-height: 44px; padding: 0 16px; border: 0; border-radius: 8px; background: #00b42a; color: #fff; cursor: pointer; }
button:disabled { opacity: .5; cursor: wait; }
</style>
