<script setup>
import { reactive, ref, watch } from 'vue'
import { onLoad, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { archiveApi } from '../../services/archives.js'
import { createArchiveFlow, initialArchiveState } from '../../services/archive-flow.js'
import { imageApi, imageRequestKey, DEVTOOLS_NOTICE } from '../../services/private-images.js'
import { archiveInputType } from '../../services/archive-entry-mode.js'

const state = reactive(initialArchiveState())
const flow = createArchiveFlow({ state, api: archiveApi, token: () => ownerSession.accessToken, newKey: imageRequestKey })
const types = ['保养', '维修', '保险', '事故', '改装', '违章', '年检']
const uploadBusy = ref(false), imageMessage = ref(''), candidate = ref(null)
const notice = ref(''), permissionDenied = ref(false)
let generation = 0, imageKey = '', routeVehicleId = 0, routeInputType = 3
const today = () => { const now = new Date(); return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}` }
onLoad(query => { routeVehicleId = Number(query.vehicle_id); routeInputType = archiveInputType(query); state.vehicleId = Number.isSafeInteger(routeVehicleId) && routeVehicleId > 0 ? routeVehicleId : 0; state.inputType = routeInputType; state.recordedDate = today() })
function clear() { generation++; flow.reset(); uploadBusy.value = false; imageMessage.value = ''; candidate.value = null; imageKey = ''; notice.value = ''; permissionDenied.value = false; state.vehicleId = routeVehicleId; state.inputType = routeInputType; state.recordedDate = today() }
watch(() => ownerSession.accessToken, clear, { flush: 'sync' })
onHide(clear)
onUnload(clear)
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function back() { uni.switchTab({ url: '/pages/archive/index' }) }
function pickType(event) { state.archiveType = Number(event.detail.value) + 1 }
function pickDate(event) { state.recordedDate = event.detail.value }
async function choose() {
  if (uploadBusy.value || state.busy || state.fileIds.length >= 5) return
  const current = ++generation, token = ownerSession.accessToken
  uploadBusy.value = true; imageMessage.value = ''; permissionDenied.value = false
  try {
    const file = await imageApi.choose(token, state.inputType === 1 ? 'camera' : 'mixed')
    if (current !== generation || token !== ownerSession.accessToken || !file) { notice.value = ''; return }
    notice.value = file.degraded ? DEVTOOLS_NOTICE : ''
    candidate.value = file; imageKey = imageRequestKey()
    await upload()
  } catch (error) {
    if (current !== generation) return
    permissionDenied.value = error?.kind === 'permission'
    imageMessage.value = error?.message || '图片选择失败，请重试'
  }
  finally { if (current === generation) uploadBusy.value = false }
}
async function authorize() {
  if (await imageApi.authorize()) { permissionDenied.value = false; imageMessage.value = '已打开设置，请允许使用摄像头后重新拍照' }
}
async function upload() {
  if (!candidate.value || !ownerSession.accessToken) return
  const current = generation, token = ownerSession.accessToken
  uploadBusy.value = true; imageMessage.value = ''
  try {
    const result = await imageApi.upload(token, candidate.value, imageKey)
    if (current !== generation || token !== ownerSession.accessToken) return
    state.fileIds = [...state.fileIds, result.file_id]
    candidate.value = null; imageKey = ''; imageMessage.value = '图片已上传，保存档案后才会归档'
  } catch (error) { if (current === generation) imageMessage.value = error?.message || '图片上传失败，请重试' }
  finally { if (current === generation) uploadBusy.value = false }
}
function remove(id) { if (!state.busy && !uploadBusy.value) state.fileIds = state.fileIds.filter(value => value !== id) }
async function preview(id) {
  const token = ownerSession.accessToken, current = generation
  try {
    const signed = await imageApi.access(token, id)
    if (current === generation && token === ownerSession.accessToken) await imageApi.preview(signed.url)
  } catch { if (current === generation) imageMessage.value = '图片预览未打开，请重试' }
}
</script>

<template>
  <view class="page" data-testid="archive-record-add">
    <text class="eyebrow">车主端 · {{ state.inputType === 1 ? '拍照录入' : '手动录入' }}</text><text class="title">新增车辆档案</text>
    <text class="intro">{{ state.inputType === 1 ? '拍摄至少一张图片，再填写真实的档案内容。' : '记录保养、维修及其他用车事项，可附最多五张图片。' }}</text>
    <view v-if="!ownerSession.accessToken" class="panel"><text>请先登录车主账号</text><button @tap="login">前往登录</button></view>
    <view v-else-if="!state.vehicleId" class="panel"><text>车辆信息无效，请返回选择车辆</text><button @tap="back">返回档案</button></view>
    <view v-else class="panel">
      <text class="label">档案类型</text>
      <picker :disabled="state.busy || uploadBusy || state.saved" :range="types" @change="pickType"><view class="field">{{ types[state.archiveType - 1] }}</view></picker>
      <text class="label">发生日期</text>
      <picker mode="date" :value="state.recordedDate" :disabled="state.busy || uploadBusy || state.saved" @change="pickDate"><view class="field">{{ state.recordedDate || '请选择日期' }}</view></picker>
      <text class="label">标题</text><input v-model="state.title" class="field" maxlength="80" :disabled="state.busy || uploadBusy || state.saved" placeholder="例如 更换机油" />
      <text class="label">当时里程（km，选填）</text><input v-model="state.mileage" class="field" type="number" maxlength="10" :disabled="state.busy || uploadBusy || state.saved" placeholder="非负整数" />
      <text class="label">备注（选填）</text><textarea v-model="state.notes" class="notes" maxlength="1000" :disabled="state.busy || uploadBusy || state.saved" placeholder="记录具体情况" />
      <text class="label">归档图片（{{ state.fileIds.length }}/5）</text>
      <view v-for="(id, index) in state.fileIds" :key="id" class="image-row"><text>图片 {{ index + 1 }} 已上传</text><button class="secondary" :disabled="state.busy || uploadBusy" @tap="preview(id)">预览</button><button class="secondary" :disabled="state.busy || uploadBusy" @tap="remove(id)">移除</button></view>
      <button v-if="state.fileIds.length < 5 && !state.saved" class="secondary" :disabled="state.busy || uploadBusy" @tap="choose">{{ state.inputType === 1 ? '拍照并上传' : '选择并上传图片' }}</button>
      <button v-if="candidate" class="secondary" :disabled="state.busy || uploadBusy" @tap="upload">使用原图片重试上传</button>
      <text v-if="notice" class="status" role="status">{{ notice }}</text>
      <text v-if="imageMessage" class="status">{{ imageMessage }}</text>
      <button v-if="permissionDenied" class="secondary" @tap="authorize">去开启相机权限</button>
      <text v-if="state.message" class="status" :class="{ error: state.failureKind }" role="status">{{ state.message }}</text>
      <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">重新登录</button>
      <button v-else-if="state.saved" @tap="back">返回查看档案</button>
      <button v-else :disabled="state.busy || uploadBusy || (state.inputType === 1 && !state.fileIds.length)" :loading="state.busy" @tap="flow.save">保存档案</button>
      <text class="note">保存失败时可使用原内容重试。仅已上传成功的图片会随档案保存。</text>
    </view>
  </view>
</template>

<style scoped>
.page { padding: 52rpx 32rpx 96rpx; min-height: 100vh; box-sizing: border-box; background: #f2f3f5; display: flex; flex-direction: column; }
.eyebrow { color: #008f24; font-size: 23rpx; }.title { color: #1d2129; font-size: 44rpx; font-weight: 700; margin-top: 16rpx; }
.intro { color: #4e5969; font-size: 26rpx; line-height: 40rpx; margin: 16rpx 0 28rpx; }
.panel { background: white; border-radius: 24rpx; padding: 32rpx; display: flex; flex-direction: column; }
.label { color: #4e5969; font-size: 26rpx; margin: 24rpx 0 12rpx; }
.field { height: 84rpx; line-height: 84rpx; padding: 0 20rpx; border: 1rpx solid #c9cdd4; border-radius: 12rpx; font-size: 27rpx; color: #1d2129; box-sizing: border-box; background: #fafafa; }
.notes { min-height: 180rpx; padding: 18rpx 20rpx; border: 1rpx solid #c9cdd4; border-radius: 12rpx; font-size: 27rpx; box-sizing: border-box; width: 100%; background: #fafafa; }
.image-row { display: flex; align-items: center; flex-wrap: wrap; gap: 12rpx; margin-top: 14rpx; font-size: 24rpx; }
button { margin: 24rpx 0 0; min-height: 80rpx; border-radius: 14rpx; font-size: 27rpx; background: #00b42a; color: white; }
button::after { border: 0; }button[disabled] { background: #f2f3f5; color: #86909c; }
.secondary { align-self: flex-start; min-height: 68rpx; padding: 0 24rpx; margin: 12rpx 0 0; background: #eef8f0; color: #008f24; font-size: 24rpx; }
.status { margin-top: 20rpx; color: #008f24; font-size: 25rpx; }.error { color: #b42318; }
.note { margin-top: 24rpx; color: #86909c; font-size: 23rpx; line-height: 36rpx; }
</style>
