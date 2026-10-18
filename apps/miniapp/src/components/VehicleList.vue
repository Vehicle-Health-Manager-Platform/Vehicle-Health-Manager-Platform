<script setup>
import { ref, watch, onMounted } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../services/owner-session.js'
import { vehicleApi, vehicleFailure } from '../services/vehicles.js'
import { selectionFromPage } from '../services/owner-vehicle-selection.js'
const props = defineProps({ selectedId: { type: Number, default: 0 } })
const emit = defineEmits(['select'])
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
    const selected = selectionFromPage(rows.value, props.selectedId)
    if (selected) emit('select', selected)
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
function add() { uni.navigateTo({ url: '/pages/vehicle/entry' }) }
</script>

<template>
  <view class="vehicles ux-in ux-r1" data-testid="vehicle-list">
    <view class="top"><text class="heading">我的车辆</text><button class="add" :disabled="busy || ['unauthorized','forbidden'].includes(kind)" @tap="add">添加车辆</button></view>
    <text v-if="busy" class="copy" role="status">正在加载车辆…</text>
    <text v-else-if="loaded && !rows.length" class="copy">还没有车辆，先添加一辆开始记录养护。</text>
    <view v-for="(vehicle, index) in rows" :key="vehicle.vehicle_id" class="vehicle" :class="{ selected: vehicle.vehicle_id === selectedId }" :style="{ animationDelay: `${80 + index * 60}ms` }" @tap="emit('select', vehicle)">
      <view class="vehicle-head">
        <text class="name">{{ vehicle.model_name || '车型信息暂不可用' }}</text>
        <view class="tick" :class="{ on: vehicle.vehicle_id === selectedId }">{{ vehicle.vehicle_id === selectedId ? '✓' : '' }}</view>
      </view>
      <text class="copy ux-num">当前里程 {{ vehicle.current_mileage }} km</text>
      <text v-if="vehicle.plate_no_masked" class="copy ux-num">车牌 {{ vehicle.plate_no_masked }}</text>
      <text v-if="vehicle.vin_masked" class="copy ux-num">VIN {{ vehicle.vin_masked }}</text>
    </view>
    <text v-if="message" class="error" role="status">{{ message }}</text>
    <button v-if="['unauthorized','forbidden'].includes(kind)" class="retry" @tap="login">重新登录</button>
    <button v-else-if="message" class="retry" :disabled="busy" @tap="load(retryMore)">重试加载</button>
    <button v-else-if="loaded && rows.length < total" class="retry" :disabled="busy" @tap="load(true)">加载更多车辆</button>
    <text class="note">选择车辆查看档案。图片需要在录入记录时关联，单独上传不会自动创建记录。</text>
  </view>
</template>

<style scoped>
.vehicles { padding: 30rpx; border: 1rpx solid rgba(26, 29, 24, 0.14); border-radius: 20rpx; background: #fcfbf8; box-shadow: 0 2rpx 10rpx rgba(26, 29, 24, 0.07); display: flex; flex-direction: column; animation: ux-rise 320ms cubic-bezier(0.16, 1, 0.3, 1) both; }
.top { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.heading { font-size: 31rpx; color: #1a1d18; font-weight: 700; letter-spacing: 1rpx; }
button { margin: 0; padding: 0 24rpx; min-height: 64rpx; font-size: 25rpx; font-weight: 600; border: 1rpx solid transparent; border-radius: 12rpx; background: #e9f2ec; color: #074d30; box-sizing: border-box; }
button::after { border: 0; }
button[disabled] { border-color: rgba(26, 29, 24, 0.14); background: #efede6; color: #8a8f84; opacity: 1; }
button:active { transform: translateY(2rpx); }
/* 车辆行：逐行错峰入场，选中态用左侧主色条 + 对勾指示，不止靠颜色区分。 */
.vehicle { display: flex; flex-direction: column; padding: 24rpx 20rpx; margin-top: 16rpx; border: 1rpx solid rgba(26, 29, 24, 0.14); border-left: 6rpx solid rgba(26, 29, 24, 0.14); border-radius: 12rpx; background: #fcfbf8; animation: ux-rise 320ms cubic-bezier(0.16, 1, 0.3, 1) both; transition: border-color 160ms ease-out, background 160ms ease-out; }
.vehicle.selected { border-left-color: #0b6b43; background: #e9f2ec; }
.vehicle-head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.tick { display: flex; align-items: center; justify-content: center; width: 44rpx; height: 44rpx; border: 1rpx solid rgba(26, 29, 24, 0.3); border-radius: 50%; color: transparent; font-size: 26rpx; font-weight: 700; box-sizing: border-box; flex-shrink: 0; }
.tick.on { border-color: #0b6b43; background: #0b6b43; color: #fcfbf8; }
.name { font-size: 29rpx; color: #1a1d18; font-weight: 650; }
.copy { margin-top: 12rpx; font-size: 25rpx; color: #63685e; line-height: 1.6; }
.error { margin-top: 24rpx; font-size: 25rpx; color: #b3261e; }
.retry { align-self: flex-start; margin-top: 20rpx; border-color: rgba(26, 29, 24, 0.3); background: #fcfbf8; color: #1a1d18; }
.note { margin-top: 24rpx; padding-top: 20rpx; border-top: 1rpx solid rgba(26, 29, 24, 0.14); font-size: 23rpx; color: #8a8f84; line-height: 1.65; }
</style>
