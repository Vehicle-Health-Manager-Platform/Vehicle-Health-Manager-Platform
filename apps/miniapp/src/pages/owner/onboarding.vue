<script setup>
import { reactive, ref, watch, computed } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { onboardingApi, createOwnerOnboardingFlow, initialOwnerOnboardingState, CATEGORIES, REJECT_REASONS, APPLICATION_STATUS } from '../../services/merchant-onboarding.js'
import { imageApi, imageRequestKey, DEVTOOLS_NOTICE } from '../../services/private-images.js'

const MAX_FILES = 9
const state = reactive(initialOwnerOnboardingState())
const flow = createOwnerOnboardingFlow({ state, api: onboardingApi, token: () => ownerSession.accessToken, newKey: imageRequestKey })
const codes = Object.keys(CATEGORIES), labels = codes.map(code => CATEGORIES[code])
const reasonCodes = Object.keys(REJECT_REASONS)
const draft = reactive({ merchant_name: '', category: codes[0], region_code: '', address: '', contact_phone: '' })
const fileIds = ref([]), uploadBusy = ref(false), imageMessage = ref(''), candidate = ref(null), notice = ref(''), permissionDenied = ref(false)
let visible = false, generation = 0, imageKey = ''

// 待审或已通过时不允许再次提交：后端同样以 49001 拒绝，这里提前把入口收起来。
const editable = computed(() => !state.application || state.application.status === 'REJECTED')
const statusText = computed(() => (state.application ? APPLICATION_STATUS[state.application.status] : '尚未提交'))
const reasonText = computed(() => (state.application?.review_reason ? REJECT_REASONS[state.application.review_reason] : ''))

function clear() { visible = false; generation++; flow.reset(); uploadBusy.value = false; imageMessage.value = ''; candidate.value = null; imageKey = ''; notice.value = ''; permissionDenied.value = false; fileIds.value = [] }
function login() { clear(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function pickCategory(event) { draft.category = codes[Number(event.detail.value)] }
async function choose() {
  if (uploadBusy.value || state.busy || state.writing || fileIds.value.length >= MAX_FILES) return
  const current = ++generation, token = ownerSession.accessToken
  uploadBusy.value = true; imageMessage.value = ''; permissionDenied.value = false
  try {
    const file = await imageApi.choose(token, 'mixed')
    if (current !== generation || token !== ownerSession.accessToken || !file) { notice.value = ''; return }
    notice.value = file.degraded ? DEVTOOLS_NOTICE : ''
    candidate.value = file; imageKey = imageRequestKey()
    await upload()
  } catch (error) {
    if (current !== generation) return
    permissionDenied.value = error?.kind === 'permission'
    imageMessage.value = error?.message || '图片选择失败，请重试'
  } finally { if (current === generation) uploadBusy.value = false }
}
async function upload() {
  if (!candidate.value || !ownerSession.accessToken) return
  const current = generation, token = ownerSession.accessToken
  uploadBusy.value = true; imageMessage.value = ''
  try {
    const result = await imageApi.upload(token, candidate.value, imageKey)
    if (current !== generation || token !== ownerSession.accessToken) return
    fileIds.value = [...fileIds.value, result.file_id]
    candidate.value = null; imageKey = ''; imageMessage.value = '资质图片已上传，提交申请后才会送审'
  } catch (error) { if (current === generation) imageMessage.value = error?.message || '图片上传失败，请重试' }
  finally { if (current === generation) uploadBusy.value = false }
}
function remove(id) { if (!state.busy && !state.writing && !uploadBusy.value) fileIds.value = fileIds.value.filter(value => value !== id) }
async function preview(id) {
  const token = ownerSession.accessToken, current = generation
  try {
    const signed = await imageApi.access(token, id)
    if (current === generation && token === ownerSession.accessToken) await imageApi.preview(signed.url)
  } catch { if (current === generation) imageMessage.value = '图片预览未打开，请重试' }
}
async function authorize() { if (await imageApi.authorize()) { permissionDenied.value = false; imageMessage.value = '已打开设置，请允许使用相册或相机后重试' } }
function submit() {
  flow.submit({ merchant_name: draft.merchant_name.trim(), category: draft.category, region_code: draft.region_code.trim(),
    address: draft.address.trim(), contact_phone: draft.contact_phone.trim(), qualification_file_ids: [...fileIds.value] })
}
watch(() => ownerSession.accessToken, () => { clear(); if (visible && ownerSession.accessToken) { visible = true; flow.resume(); flow.load() } }, { flush: 'sync' })
onShow(() => { visible = true; if (!ownerSession.accessToken) return; flow.resume(); flow.load() })
onHide(clear)
onUnload(clear)
</script>

<template>
  <view class="page" data-testid="merchant-onboarding-owner">
    <text class="eyebrow">车主端 · 商家入驻</text><text class="title">申请成为商家</text>
    <text class="intro">提交门店与资质信息，由运营审核。审核通过后门店与店长账号会一并创建，账号激活在正式身份接入后开放。</text>
    <view v-if="!ownerSession.accessToken" class="panel"><text>请先登录车主账号</text><button @tap="login">前往登录</button></view>
    <view v-else class="panel">
      <text class="label">当前状态</text>
      <text role="status">{{ statusText }}<template v-if="state.application">（第 {{ state.application.revision }} 次提交）</template></text>
      <text v-if="state.application?.status === 'REJECTED'" class="error">驳回原因：{{ reasonText || state.application.review_reason }}</text>
      <text v-if="state.application?.status === 'APPROVED'">门店编号 {{ state.application.merchant_id }}。店长账号待激活，暂不能登录商家端。</text>
      <text v-if="state.application?.status === 'PENDING_REVIEW'" class="note">待审核期间不能修改或重复提交，请等待审核结果。</text>

      <template v-if="editable">
        <text class="label">商家名称</text><input v-model="draft.merchant_name" class="field" maxlength="64" :disabled="state.writing || uploadBusy" placeholder="2 至 64 个字符" />
        <text class="label">经营品类</text>
        <picker :range="labels" :disabled="state.writing || uploadBusy" @change="pickCategory"><view class="field">{{ CATEGORIES[draft.category] }} ›</view></picker>
        <text class="label">行政区划码</text><input v-model="draft.region_code" class="field" type="number" maxlength="6" :disabled="state.writing || uploadBusy" placeholder="6 位数字，例如 440305" />
        <text class="label">门店地址</text><input v-model="draft.address" class="field" maxlength="256" :disabled="state.writing || uploadBusy" placeholder="详细地址" />
        <text class="label">联系电话</text><input v-model="draft.contact_phone" class="field" type="number" maxlength="11" :disabled="state.writing || uploadBusy" placeholder="大陆手机号" />
        <text class="label">资质图片（{{ fileIds.length }}/{{ MAX_FILES }}）</text>
        <view v-for="(id, index) in fileIds" :key="id" class="image-row"><text>图片 {{ index + 1 }} 已上传</text><button class="secondary" :disabled="state.writing || uploadBusy" @tap="preview(id)">预览</button><button class="secondary" :disabled="state.writing || uploadBusy" @tap="remove(id)">移除</button></view>
        <button v-if="fileIds.length < MAX_FILES" class="secondary" :disabled="state.writing || uploadBusy" @tap="choose">选择并上传资质图片</button>
        <button v-if="candidate" class="secondary" :disabled="state.writing || uploadBusy" @tap="upload">使用原图片重试上传</button>
        <text v-if="notice" class="note" role="status">{{ notice }}</text>
        <text v-if="imageMessage" class="status">{{ imageMessage }}</text>
        <button v-if="permissionDenied" class="secondary" @tap="authorize">去开启相册权限</button>
        <button class="primary" :disabled="state.busy || state.writing || uploadBusy" :loading="state.writing" @tap="submit">{{ state.application ? '重新提交申请' : '提交入驻申请' }}</button>
      </template>

      <text v-if="state.message" class="status" :class="{ error: state.kind && state.kind !== 'unavailable' }" role="status">{{ state.message }}</text>
      <button v-if="['unauthorized', 'forbidden'].includes(state.kind)" @tap="login">重新登录</button>
      <button class="secondary" :disabled="state.busy || state.writing" @tap="flow.load">刷新状态</button>
      <text class="note">审核历史只会追加，不会改写。资质图片为私有文件，仅审核时通过受控访问查看。</text>
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
.image-row { display: flex; align-items: center; flex-wrap: wrap; gap: 12rpx; margin-top: 14rpx; font-size: 24rpx; }
button { margin: 24rpx 0 0; min-height: 80rpx; border-radius: 14rpx; font-size: 27rpx; background: #00b42a; color: white; }
button::after { border: 0; }button[disabled] { background: #f2f3f5; color: #86909c; }
.secondary { align-self: flex-start; min-height: 68rpx; padding: 0 24rpx; margin: 12rpx 0 0; background: #eef8f0; color: #008f24; font-size: 24rpx; }
.status { margin-top: 20rpx; color: #008f24; font-size: 25rpx; }.error { color: #b42318; }
.note { margin-top: 24rpx; color: #86909c; font-size: 23rpx; line-height: 36rpx; }
</style>
