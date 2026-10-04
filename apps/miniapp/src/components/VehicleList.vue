<script setup>
import { ref, watch, onMounted } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../services/owner-session.js'
import { vehicleApi, vehicleFailure } from '../services/vehicles.js'
const rows = ref([]), total = ref(0), page = ref(0), busy = ref(false), loaded = ref(false), message = ref(''), kind = ref('')
const retryMore = ref(false)
let generation = 0
function suspend() { generation++; busy.value = false }
async function load(more = false) {
  if (busy.value || !ownerSession.accessToken) return
  const current = ++generation, token = ownerSession.accessToken, next = more ? page.value + 1 : 1
  busy.value = true; message.value = ''; kind.value = ''
  retryMore.value = more
  if (!more) { rows.value = []; total.value = 0; loaded.value = false }
  try {
    const data = await vehicleApi.list(token, next)
    if (current !== generation || token !== ownerSession.accessToken) return
    rows.value = more ? [...rows.value, ...data.list] : data.list
    total.value = data.total; page.value = next; loaded.value = true
  } catch (error) {
    if (current !== generation || token !== ownerSession.accessToken) return
    const safe = vehicleFailure(error); message.value = safe.message; kind.value = safe.kind
  } finally { if (current === generation) busy.value = false }
}
watch(() => ownerSession.accessToken, () => { suspend(); rows.value = []; total.value = 0; loaded.value = false; message.value = ''; kind.value = ''; page.value = 0 }, { flush: 'sync' })
onShow(() => load())
onMounted(() => load())
onHide(suspend)
onUnload(suspend)
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function add() { uni.navigateTo({ url: '/pages/vehicle/manual' }) }
</script>

<template>
  <view class="vehicles" data-testid="vehicle-list">
    <view class="top"><text class="heading">我的车辆</text><button class="add" :disabled="busy || ['unauthorized','forbidden'].includes(kind)" @tap="add">手动添加</button></view>
    <text v-if="busy" class="copy" role="status">正在加载车辆…</text>
    <text v-else-if="loaded && !rows.length" class="copy">还没有车辆，先添加一辆开始记录养护。</text>
    <view v-for="vehicle in rows" :key="vehicle.vehicle_id" class="vehicle">
      <text class="name">{{ vehicle.model_name || '车型信息暂不可用' }}</text>
      <text class="copy">当前里程 {{ vehicle.current_mileage }} km</text>
      <text v-if="vehicle.plate_no_masked" class="copy">车牌 {{ vehicle.plate_no_masked }}</text>
      <text v-if="vehicle.vin_masked" class="copy">VIN {{ vehicle.vin_masked }}</text>
    </view>
    <text v-if="message" class="error" role="status">{{ message }}</text>
    <button v-if="['unauthorized','forbidden'].includes(kind)" class="retry" @tap="login">重新登录</button>
    <button v-else-if="message" class="retry" :disabled="busy" @tap="load(retryMore)">重试加载</button>
    <button v-else-if="loaded && rows.length < total" class="retry" :disabled="busy" @tap="load(true)">加载更多车辆</button>
    <text class="note">档案录入将随后开放；图片上传成功不会自动创建养护记录。</text>
  </view>
</template>

<style scoped>
.vehicles { padding: 32rpx; border-radius: 24rpx; background: white; display: flex; flex-direction: column; }
.top { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.heading { font-size: 32rpx; color: #1d2129; font-weight: 650; }
button { margin: 0; padding: 0 24rpx; font-size: 25rpx; background: #eef8f0; color: #008f24; border-radius: 14rpx; }
button::after { border: 0; }
button[disabled] { background: #f2f3f5; color: #86909c; }
.vehicle { display: flex; flex-direction: column; padding: 24rpx 0; margin-top: 16rpx; border-top: 1rpx solid #e5e6eb; }
.name { font-size: 28rpx; color: #1d2129; font-weight: 600; }
.copy { margin-top: 16rpx; font-size: 25rpx; color: #4e5969; line-height: 38rpx; }
.error { margin-top: 24rpx; font-size: 25rpx; color: #b42318; }
.retry { align-self: flex-start; margin-top: 20rpx; }
.note { margin-top: 24rpx; font-size: 23rpx; color: #86909c; line-height: 36rpx; }
</style>
