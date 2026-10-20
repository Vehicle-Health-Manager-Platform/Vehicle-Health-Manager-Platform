<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload, onShareAppMessage } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { archiveShareApi, archiveShareInput, createArchiveShareMutation } from '../../services/archive-shares.js'
import { imageRequestKey } from '../../services/private-images.js'

const vehicleId = ref(0), rows = ref([]), busy = ref(false), message = ref(''), kind = ref('')
const fields = reactive({ range: false, from: '', to: '', hours: 24 })
const prepared = ref(null)
const mutation = createArchiveShareMutation({ api: archiveShareApi, newKey: imageRequestKey })
const ttlLabels = ['1 小时', '24 小时', '7 天'], ttlHours = [1, 24, 168]
const ttlIndex = computed(() => ttlHours.indexOf(fields.hours))
const labels = { ACTIVE: '有效', EXPIRED: '已失效', REVOKED: '已撤销' }
let generation = 0, visible = false, expiryTimer
const revokeKeys = new Map()
const formatTime = value => new Date(value).toLocaleString()
function clearPrepared() { clearTimeout(expiryTimer); prepared.value = null }
function suspend() { generation++; rows.value = []; busy.value = false; clearPrepared() }
function fail(error) { message.value = error?.message || '操作未成功，请重试'; kind.value = error?.kind || 'server' }
async function run(action) {
  if (busy.value || !visible) return
  const current = generation, token = ownerSession.accessToken
  busy.value = true; message.value = ''; kind.value = ''
  try { await action(token, current) } catch (error) { if (current === generation && token === ownerSession.accessToken) fail(error) }
  finally { if (current === generation) busy.value = false }
}
const currentResponse = (token, current) => visible && current === generation && token === ownerSession.accessToken
async function refresh(token, current) {
  const result = await archiveShareApi.list(token, vehicleId.value)
  if (currentResponse(token, current)) rows.value = result.list
}
function load() { return run(refresh) }
function create() {
  clearPrepared()
  return run(async (token, current) => {
    const body = archiveShareInput(vehicleId.value, fields.hours, fields.range ? fields.from : '', fields.range ? fields.to : '')
    if (fields.range && (!fields.from || !fields.to)) throw new Error('请选择完整日期范围')
    const result = await mutation.create(token, body)
    if (!currentResponse(token, current)) return
    message.value = result.status === 'ACTIVE' ? '摘要已创建，请点「准备分享」后转发。' : '该次请求已处理，分享已失效。请重新创建。'
    await refresh(token, current)
  })
}
function prepare(row) {
  clearPrepared()
  return run(async (token, current) => {
    const result = await archiveShareApi.link(token, row.share_id)
    if (!currentResponse(token, current)) return
    const remaining = Date.parse(result.expires_at) - Date.now()
    if (remaining <= 0) throw new Error('分享已过期，请重新创建')
    prepared.value = result
    expiryTimer = setTimeout(clearPrepared, remaining)
    message.value = '已准备好。通过微信转发给接收者，对方须登录车主账号。'
  })
}
function revoke(row) {
  uni.showModal({ title: '撤销分享', content: '撤销后此链接将无法继续查看。已保存的截图无法收回。', success: result => {
    if (!result.confirm || !visible) return
    clearPrepared()
    run(async (token, current) => {
      if (!revokeKeys.has(row.share_id)) revokeKeys.set(row.share_id, imageRequestKey())
      await archiveShareApi.revoke(token, row.share_id, revokeKeys.get(row.share_id))
      if (!currentResponse(token, current)) return
      revokeKeys.delete(row.share_id); message.value = '分享已撤销'; await refresh(token, current)
    })
  } })
}
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
onLoad(query => { vehicleId.value = Number(query.vehicle_id); uni.hideShareMenu?.() })
onShow(() => { visible = true; load() })
onHide(() => { visible = false; suspend() })
onUnload(() => { visible = false; suspend(); mutation.reset(); revokeKeys.clear() })
watch(() => ownerSession.accessToken, () => { suspend(); mutation.reset(); revokeKeys.clear(); message.value = ''; if (visible) load() }, { flush: 'sync' })
onShareAppMessage(() => {
  const share = prepared.value
  if (!ownerSession.accessToken || !share || Date.parse(share.expires_at) <= Date.now()) return { title: '汽车健康管家', path: '/pages/index/index' }
  return { title: '车辆档案只读摘要', path: share.path }
})
</script>

<template>
  <view class="page">
    <text class="title">分享档案摘要</text>
    <text class="hint">仅分享创建时的只读摘要。不含车牌、VIN、标题备注、照片、签字或 PDF。链接可以转发，接收者须登录车主账号。</text>
    <view v-if="ownerSession.accessToken" class="card">
      <text class="heading">创建限时分享</text>
      <view class="line"><text>指定日期范围</text><switch :checked="fields.range" :disabled="busy" color="#0b6b43" @change="fields.range = $event.detail.value" /></view>
      <view v-if="fields.range" class="dates">
        <picker mode="date" :value="fields.from" :disabled="busy" @change="fields.from = $event.detail.value"><text>{{ fields.from || '开始日期' }}</text></picker>
        <picker mode="date" :value="fields.to" :disabled="busy" @change="fields.to = $event.detail.value"><text>{{ fields.to || '结束日期' }}</text></picker>
      </view>
      <picker :range="ttlLabels" :value="ttlIndex" :disabled="busy" @change="fields.hours = ttlHours[Number($event.detail.value)]"><view class="line"><text>有效期</text><text>{{ ttlLabels[ttlIndex] }} ›</text></view></picker>
      <button :disabled="busy" :loading="busy" @tap="create">创建摘要</button>
      <!-- #ifdef MP-WEIXIN -->
      <button v-if="prepared" open-type="share" :disabled="busy">转发已准备的摘要</button>
      <!-- #endif -->
      <!-- #ifndef MP-WEIXIN -->
      <text v-if="prepared" class="hint">请在微信小程序中使用原生转发。</text>
      <!-- #endif -->
    </view>
    <text v-if="message" class="message" :class="{ error: kind }">{{ message }}</text>
    <button v-if="!ownerSession.accessToken || kind === 'unauthorized'" @tap="login">登录车主账号</button>
    <button v-if="ownerSession.accessToken" class="secondary" :disabled="busy" @tap="load">刷新分享状态</button>
    <view v-for="row in rows" :key="row.share_id" class="card">
      <text class="heading">{{ labels[row.status] }}</text>
      <text class="hint">创建：{{ formatTime(row.created_at) }}</text><text class="hint">到期：{{ formatTime(row.expires_at) }}</text>
      <button v-if="row.status === 'ACTIVE'" class="secondary" :disabled="busy" @tap="prepare(row)">准备分享</button>
      <button v-if="row.status !== 'REVOKED'" class="danger" :disabled="busy" @tap="revoke(row)">撤销</button>
    </view>
    <text v-if="ownerSession.accessToken && !busy && !rows.length" class="hint">暂无分享记录</text>
  </view>
</template>

<style scoped>
.page { padding: 32rpx; }
.title { display: block; font-size: 38rpx; font-weight: 700; }
.hint { display: block; margin-top: 16rpx; font-size: 24rpx; line-height: 38rpx; color: #63685e; }
.card { margin-top: 24rpx; padding: 28rpx; border-radius: 20rpx; background: #fcfbf8; }
.heading { font-size: 29rpx; font-weight: 600; }
.line,.dates { display: flex; justify-content: space-between; align-items: center; margin: 24rpx 0; font-size: 26rpx; }
.dates picker { padding: 16rpx; background: #efede6; border-radius: 10rpx; }
button { margin-top: 20rpx; font-size: 26rpx; border-radius: 14rpx; background: #0b6b43; color: #fcfbf8; }
button::after { border: 0; }
.secondary { background: #e9f2ec; color: #074d30; }
.danger { background: #f7ecdf; color: #b3261e; }
.message { display: block; margin-top: 22rpx; color: #074d30; font-size: 25rpx; line-height: 38rpx; }
.error { color: #b3261e; }
</style>
