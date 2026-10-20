<script setup>
import { reactive, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import OwnerTabShell from '../../components/OwnerTabShell.vue'
import VehicleList from '../../components/VehicleList.vue'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { healthMetrics,healthLabels } from '../../services/health-observations.js'
import { archiveApi, archiveFailure } from '../../services/archives.js'
import { imageApi } from '../../services/private-images.js'
import { selectedOwnerVehicle, selectOwnerVehicle } from '../../services/owner-vehicle-selection.js'
import { archiveEntryUrl, archiveInputTypeName } from '../../services/archive-entry-mode.js'
import { displayTime } from '../../services/reservations.js'
import { archiveReportApi } from '../../services/archive-report.js'
import { createArchiveReportFlow } from '../../services/archive-report-flow.js'

const vehicle = selectedOwnerVehicle, rows = ref([]), total = ref(0), page = ref(0)
const busy = ref(false), loaded = ref(false), message = ref(''), kind = ref(''), retryMore = ref(false)
const labels = ['保养', '维修', '保险', '事故', '改装', '违章', '年检', '行驶证资料', '驾驶证资料', '本人故障记录']
const reportRangeEnabled = ref(false), reportFrom = ref(''), reportTo = ref('')
const reportState = reactive({ busy: false, message: '', kind: '' })
const reportFlow = createArchiveReportFlow({ api: archiveReportApi, token: () => ownerSession.accessToken,
  vehicleId: () => vehicle.value?.vehicle_id || 0, onChange: next => Object.assign(reportState, next) })
let generation = 0
let visible = false
function suspend() { generation++; busy.value = false }
function clear() { suspend(); rows.value = []; total.value = 0; page.value = 0; loaded.value = false; message.value = ''; kind.value = '' }
function select(item) { selectOwnerVehicle(item) }
function setReportRange(enabled) {
  reportRangeEnabled.value = enabled
  if (!enabled) { reportFrom.value = ''; reportTo.value = '' }
  reportFlow.invalidate()
}
function setReportDate(edge, event) {
  if (edge === 'from') reportFrom.value = event.detail.value
  else reportTo.value = event.detail.value
  reportFlow.invalidate()
}
function exportReport() {
  const range = reportRangeEnabled.value ? { from: reportFrom.value, to: reportTo.value } : null
  reportFlow.export(range)
}
function loginForReport() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
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
function chooseHealth(item) {
  const owner=ownerSession.accessToken,current=generation,vehicleId=vehicle.value?.vehicle_id
  uni.showActionSheet({itemList:healthMetrics.map(key=>healthLabels[key]),success:result=>{
    if(!visible||current!==generation||owner!==ownerSession.accessToken||vehicleId!==vehicle.value?.vehicle_id||!healthMetrics[result.tapIndex])return
    uni.navigateTo({url:`/pages/archive/health-observation?vehicle_id=${vehicleId}&archive_id=${item.archive_id}&metric=${healthMetrics[result.tapIndex]}`})
  }})
}
async function preview(item, id) {
  const token = ownerSession.accessToken, current = generation
  try {
    const result = item.input_type === 4 ? await archiveApi.access(token, item.archive_id, id) : await imageApi.access(token, id)
    if (current !== generation || token !== ownerSession.accessToken) return
    await imageApi.preview(result.url)
  } catch { if (current === generation) { message.value = '图片预览未打开，请重试'; kind.value = 'preview' } }
}
watch(() => ownerSession.accessToken, () => { clear(); reportFlow.invalidate(); reportRangeEnabled.value = false; reportFrom.value = ''; reportTo.value = '' }, { flush: 'sync' })
watch(() => vehicle.value?.vehicle_id, (next, previous) => { if (next && next !== previous) { suspend(); reportFlow.invalidate(); reportRangeEnabled.value = false; reportFrom.value = ''; reportTo.value = ''; if (visible) load() } }, { flush: 'sync' })
onShow(() => { visible = true; reportFlow.resume(); if (vehicle.value) load() })
onHide(() => { visible = false; reportFlow.suspend(); suspend() })
onUnload(() => { visible = false; reportFlow.dispose(); clear() })
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function add(mode = 'manual') { if (vehicle.value) uni.navigateTo({ url: archiveEntryUrl(vehicle.value.vehicle_id, mode) }) }
</script>

<template>
  <OwnerTabShell label="档案" title="车辆健康档案" description="按车辆查看保养、维修等记录。" business-ready>
    <template #content>
      <VehicleList :selected-id="vehicle?.vehicle_id || 0" @select="select" />
      <button v-if="vehicle" class="secondary" @tap="uni.navigateTo({url:'/pages/community/my-cards'})">我的经验卡片</button>
      <button v-if="vehicle" class="secondary" @tap="uni.navigateTo({url:'/pages/community/experiences'})">同款施工经验</button>
      <button v-if="vehicle" class="secondary" @tap="uni.navigateTo({url:'/pages/archive/shares?vehicle_id=' + vehicle.vehicle_id})">分享档案摘要与撤销</button>
      <button v-if="vehicle" class="secondary" @tap="uni.navigateTo({url:'/pages/archive/health?vehicle_id=' + vehicle.vehicle_id})">六类本人当前读数</button>
      <button v-if="vehicle" class="secondary" @tap="uni.navigateTo({url:'/pages/archive/reminders?vehicle_id=' + vehicle.vehicle_id})">个人目标与提醒</button>
      <view v-if="vehicle" class="records" data-testid="archive-records">
        <view class="report-export" data-testid="archive-report-export">
          <view class="report-head">
            <view class="report-heading"><text class="report-kicker">本人可见 · 离线留存</text><text class="report-title">档案报告</text></view>
            <text class="report-badge">PDF</text>
          </view>
          <text class="report-copy">导出 {{ total }} 条当前可见档案。照片原件仍可在小程序档案页查看。</text>
          <view class="report-scope" aria-label="报告范围">
            <button class="scope-choice" :class="{selected: !reportRangeEnabled}" @tap="setReportRange(false)">全部记录</button>
            <button class="scope-choice" :class="{selected: reportRangeEnabled}" @tap="setReportRange(true)">按日期范围</button>
          </view>
          <view v-if="reportRangeEnabled" class="report-dates">
            <picker mode="date" :value="reportFrom" @change="setReportDate('from', $event)">
              <view class="date-choice"><text class="date-label">开始日期</text><text :class="{'date-empty': !reportFrom}">{{ reportFrom || '请选择' }}</text></view>
            </picker>
            <text class="date-divider">至</text>
            <picker mode="date" :value="reportTo" @change="setReportDate('to', $event)">
              <view class="date-choice"><text class="date-label">结束日期</text><text :class="{'date-empty': !reportTo}">{{ reportTo || '请选择' }}</text></view>
            </picker>
          </view>
          <text v-if="reportRangeEnabled" class="report-hint">日期首尾均包含；开始和结束日期都要选择。</text>
          <button class="report-action" data-testid="archive-report-download" :disabled="reportState.busy" :loading="reportState.busy" @tap="exportReport">
            {{ reportState.busy ? '正在生成报告' : '导出档案 PDF' }}
          </button>
          <text v-if="reportState.message" class="report-message" :class="{'report-error': reportState.kind !== 'success'}" role="status">{{ reportState.message }}</text>
          <button v-if="['unauthorized','forbidden'].includes(reportState.kind)" class="report-login" @tap="loginForReport">重新登录车主账号</button>
        </view>
        <view class="top"><text class="heading">{{ vehicle.model_name || '当前车辆' }}的记录</text><view class="actions"><button class="add" :disabled="busy" @tap="add('manual')">手动录入</button><button class="add" :disabled="busy" @tap="add('photo')">拍照录入</button></view></view>
        <text v-if="busy" class="copy" role="status">正在加载档案…</text>
        <text v-else-if="loaded && !rows.length" class="copy">这辆车还没有档案记录，可手动录入第一条。</text>
        <view v-for="item in rows" :key="item.archive_id" class="record">
          <view class="record-top"><text class="name">{{ item.title }}</text><text class="type">{{ labels[item.archive_type - 1] }} · {{ archiveInputTypeName(item.input_type) }}</text></view>
          <text class="copy">{{ item.recorded_date }}<text v-if="item.mileage !== null"> · {{ item.mileage }} km</text></text>
          <text v-if="item.notes" class="copy">{{ item.notes }}</text>
          <button v-if="[1,2].includes(item.archive_type) && [1,3].includes(item.input_type)" class="secondary" @tap="chooseHealth(item)">记录/修正本类读数</button>
          <button v-if="[1,2,3,6,7,8,9,10].includes(item.archive_type) && [1,3].includes(item.input_type)" class="secondary" @tap="uni.navigateTo({url:'/pages/archive/structured?vehicle_id=' + vehicle.vehicle_id + '&archive_id=' + item.archive_id})">查看/填写本条资料</button>
          <button v-if="[1,3,7].includes(item.archive_type) && [1,3].includes(item.input_type)" class="secondary" @tap="uni.navigateTo({url:'/pages/archive/reminder?vehicle_id=' + vehicle.vehicle_id + '&archive_id=' + item.archive_id})">设置本类目标与提醒</button>
          <view v-if="item.input_type === 4" class="service-source" data-testid="service-archive-source">
            <text v-if="item.test_mode" class="error" data-testid="archive-test-warning">测试施工记录，未真实扣款</text>
            <text class="copy">实际工时 {{ item.work_minutes }} 分钟</text>
            <text v-if="item.no_parts" class="copy">本次未使用配件</text>
            <text v-for="(part, index) in item.parts_used" :key="index" class="copy">{{ part.name }} · {{ part.brand }} · {{ part.model }} × {{ part.quantity }}</text>
            <text class="copy">报工 {{ displayTime(item.submitted_at) }}</text><text class="copy">质检签字 {{ displayTime(item.signed_at) }}</text>
            <text class="copy">核销 {{ displayTime(item.redeemed_at) }}</text>
            <text v-if="item.source.review_id === null" class="copy">订单完成后自动归档，无需先评价。</text>
            <button class="secondary" @tap="uni.navigateTo({url:`/pages/order/detail?id=${item.source.order_id}`})">查看来源订单</button>
          </view>
          <view v-if="item.file_ids.length" class="images"><button v-for="(id, index) in item.file_ids" :key="id" class="secondary" @tap="preview(item, id)">查看{{ item.input_type === 4 ? '施工' : '' }}图片 {{ index + 1 }}</button></view>
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
.service-source { display: flex; flex-direction: column; gap: 12rpx; margin: 16rpx 0; }
.record > .copy { white-space: pre-line; }
.records { margin-top: 28rpx; padding: 32rpx; border-radius: 24rpx; background: #fcfbf8; display: flex; flex-direction: column; }
.report-export { display: flex; flex-direction: column; gap: 16rpx; margin-bottom: 32rpx; padding: 26rpx 24rpx; border-left: 6rpx solid #0b6b43; border-radius: 16rpx; background: #e9f2ec; }
.report-head { display: flex; align-items: center; justify-content: space-between; }
.report-heading { display: flex; flex-direction: column; gap: 6rpx; }
.report-kicker { color: #074d30; font-size: 21rpx; font-weight: 600; letter-spacing: 1rpx; }
.report-title { color: #1a1d18; font-size: 30rpx; font-weight: 700; }
.report-badge { padding: 6rpx 12rpx; border: 1rpx solid #c6ded2; border-radius: 8rpx; color: #074d30; font-size: 20rpx; font-weight: 700; letter-spacing: 1rpx; }
.report-copy,.report-hint { color: #454a41; font-size: 23rpx; line-height: 34rpx; }
.report-scope { display: flex; gap: 12rpx; }
.scope-choice { min-height: 62rpx; padding: 0 20rpx; border: 1rpx solid #c6ded2; border-radius: 12rpx; background: #fcfbf8; color: #454a41; font-size: 23rpx; }
.scope-choice.selected { border-color: #0b6b43; background: #e9f2ec; color: #074d30; font-weight: 600; }
.report-dates { display: flex; align-items: center; gap: 12rpx; }
.date-choice { display: flex; flex: 1; flex-direction: column; gap: 8rpx; min-width: 0; padding: 14rpx; border: 1rpx solid #e0ded6; border-radius: 12rpx; background: #fcfbf8; color: #1a1d18; font-size: 23rpx; }
.date-label,.date-empty { color: #63685e; font-size: 20rpx; }
.date-divider { color: #63685e; font-size: 22rpx; }
.report-action { width: 100%; min-height: 76rpx; border-radius: 14rpx; background: #0b6b43; color: #fcfbf8; font-size: 25rpx; font-weight: 600; }
.report-login { align-self: flex-start; margin-top: 0; background: #e9f2ec; color: #074d30; }
.report-message { color: #074d30; font-size: 23rpx; line-height: 34rpx; }
.report-error { color: #b3261e; }
.top,.record-top { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.actions { display: flex; flex-wrap: wrap; gap: 12rpx; justify-content: flex-end; }
.heading { font-size: 30rpx; font-weight: 650; color: #1a1d18; }
.name { font-size: 28rpx; color: #1a1d18; font-weight: 600; }
.type { color: #074d30; font-size: 24rpx; }
.copy { margin-top: 14rpx; font-size: 25rpx; color: #454a41; line-height: 38rpx; }
.record { display: flex; flex-direction: column; padding: 24rpx 0; border-top: 1rpx solid #e0ded6; margin-top: 18rpx; }
.images { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 14rpx; }
button { margin: 0; padding: 0 22rpx; min-height: 70rpx; border-radius: 14rpx; font-size: 24rpx; }
button::after { border: 0; }
.add { background: #0b6b43; color: #fcfbf8; }
.secondary { align-self: flex-start; margin-top: 16rpx; background: #e9f2ec; color: #074d30; }
.error { margin-top: 20rpx; color: #b3261e; font-size: 25rpx; }
</style>
