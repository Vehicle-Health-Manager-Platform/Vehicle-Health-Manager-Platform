<script setup>
import { ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import OwnerTabShell from '../../components/OwnerTabShell.vue'
import VehicleList from '../../components/VehicleList.vue'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { archiveApi, archiveFailure } from '../../services/archives.js'
import { imageApi } from '../../services/private-images.js'
import { selectedOwnerVehicle, selectOwnerVehicle } from '../../services/owner-vehicle-selection.js'

const vehicle = selectedOwnerVehicle, rows = ref([]), total = ref(0), page = ref(0)
const busy = ref(false), loaded = ref(false), message = ref(''), kind = ref(''), retryMore = ref(false)
const labels = ['保养', '维修', '保险', '事故', '改装', '违章', '年检']
let generation = 0
let visible = false
function suspend() { generation++; busy.value = false }
function clear() { suspend(); rows.value = []; total.value = 0; page.value = 0; loaded.value = false; message.value = ''; kind.value = '' }
function select(item) { selectOwnerVehicle(item) }
async function load(more = false) {
  if (busy.value || !vehicle.value || !ownerSession.accessToken) return
  const current = ++generation, token = ownerSession.accessToken, vehicleId = vehicle.value.vehicle_id, next = more ? page.value + 1 : 1
  busy.value = true; message.value = ''; kind.value = ''; retryMore.value = more
  if (!more) { rows.value = []; total.value = 0; loaded.value = false }
  try {
    const result = await archiveApi.list(token, vehicleId, next)
    if (current !== generation || token !== ownerSession.accessToken || vehicle.value?.vehicle_id !== vehicleId) return
    rows.value = more ? [...rows.value, ...result.list] : result.list
    total.value = result.total; page.value = next; loaded.value = true
  } catch (error) {
    if (current !== generation || token !== ownerSession.accessToken) return
    const safe = archiveFailure(error); message.value = safe.message; kind.value = safe.kind
  } finally { if (current === generation) busy.value = false }
}
async function preview(id) {
  const token = ownerSession.accessToken, current = generation
  try {
    const result = await imageApi.access(token, id)
    if (current !== generation || token !== ownerSession.accessToken) return
    await imageApi.preview(result.url)
  } catch { if (current === generation) { message.value = '图片预览未打开，请重试'; kind.value = 'preview' } }
}
watch(() => ownerSession.accessToken, clear, { flush: 'sync' })
watch(() => vehicle.value?.vehicle_id, (next, previous) => { if (next && next !== previous) { suspend(); if (visible) load() } }, { flush: 'sync' })
onShow(() => { visible = true; if (vehicle.value) load() })
onHide(() => { visible = false; suspend() })
onUnload(() => { visible = false; clear() })
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function add() { if (vehicle.value) uni.navigateTo({ url: `/pages/archive/record-add?vehicle_id=${vehicle.value.vehicle_id}` }) }
</script>

<template>
  <OwnerTabShell label="档案" title="车辆健康档案" description="按车辆查看保养、维修等记录。" business-ready>
    <template #content>
      <VehicleList :selected-id="vehicle?.vehicle_id || 0" @select="select" />
      <view v-if="vehicle" class="records" data-testid="archive-records">
        <view class="top"><text class="heading">{{ vehicle.model_name || '当前车辆' }}的记录</text><button class="add" :disabled="busy" @tap="add">录入记录</button></view>
        <text v-if="busy" class="copy" role="status">正在加载档案…</text>
        <text v-else-if="loaded && !rows.length" class="copy">这辆车还没有档案记录，可手动录入第一条。</text>
        <view v-for="item in rows" :key="item.archive_id" class="record">
          <view class="record-top"><text class="name">{{ item.title }}</text><text class="type">{{ labels[item.archive_type - 1] }}</text></view>
          <text class="copy">{{ item.recorded_date }}<text v-if="item.mileage !== null"> · {{ item.mileage }} km</text></text>
          <text v-if="item.notes" class="copy">{{ item.notes }}</text>
          <view v-if="item.file_ids.length" class="images"><button v-for="(id, index) in item.file_ids" :key="id" class="secondary" @tap="preview(id)">查看图片 {{ index + 1 }}</button></view>
        </view>
        <text v-if="message" class="error" role="status">{{ message }}</text>
        <button v-if="['unauthorized','forbidden'].includes(kind)" class="secondary" @tap="login">重新登录</button>
        <button v-else-if="message && kind !== 'preview'" class="secondary" :disabled="busy" @tap="load(retryMore)">重试加载</button>
        <button v-else-if="loaded && rows.length < total" class="secondary" :disabled="busy" @tap="load(true)">加载更多记录</button>
      </view>
    </template>
  </OwnerTabShell>
</template>

<style scoped>
.records { margin-top: 28rpx; padding: 32rpx; border-radius: 24rpx; background: white; display: flex; flex-direction: column; }
.top,.record-top { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.heading { font-size: 30rpx; font-weight: 650; color: #1d2129; }
.name { font-size: 28rpx; color: #1d2129; font-weight: 600; }
.type { color: #008f24; font-size: 24rpx; }
.copy { margin-top: 14rpx; font-size: 25rpx; color: #4e5969; line-height: 38rpx; }
.record { display: flex; flex-direction: column; padding: 24rpx 0; border-top: 1rpx solid #e5e6eb; margin-top: 18rpx; }
.images { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 14rpx; }
button { margin: 0; padding: 0 22rpx; min-height: 70rpx; border-radius: 14rpx; font-size: 24rpx; }
button::after { border: 0; }
.add { background: #00b42a; color: white; }
.secondary { align-self: flex-start; margin-top: 16rpx; background: #eef8f0; color: #008f24; }
.error { margin-top: 20rpx; color: #b42318; font-size: 25rpx; }
</style>
