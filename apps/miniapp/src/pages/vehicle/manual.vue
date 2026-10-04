<script setup>
import { reactive, computed, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { vehicleApi } from '../../services/vehicles.js'
import { imageRequestKey } from '../../services/private-images.js'
import { createVehicleFlow, initialVehicleState } from '../../services/vehicle-flow.js'
const state = reactive(initialVehicleState())
const flow = createVehicleFlow({ state, api: vehicleApi, token: () => ownerSession.accessToken, newKey: imageRequestKey })
const years = computed(() => [...new Set(state.model.map(model => model.year))])
const configs = computed(() => state.model.filter(model => model.year === state.year))
const locked = computed(() => state.busy || Boolean(state.saved) || ['unauthorized', 'forbidden'].includes(state.failureKind))
watch(() => ownerSession.accessToken, () => flow.reset(), { flush: 'sync' })
onShow(() => {
  if (state.saved) return
  if (!state.brand.length) flow.load('brand')
  else if (state.brandId && !state.series.length) flow.load('series')
  else if (state.seriesId && !state.model.length) flow.load('model')
})
onHide(flow.suspend)
onUnload(flow.reset)
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function back() { uni.switchTab({ url: '/pages/archive/index' }) }
function pickBrand(event) { const item = state.brand[Number(event.detail.value)]; if (item) flow.selectBrand(item.id) }
function pickSeries(event) { const item = state.series[Number(event.detail.value)]; if (item) flow.selectSeries(item.id) }
function pickYear(event) { state.year = years.value[Number(event.detail.value)] || ''; state.modelId = 0 }
function pickModel(event) { state.modelId = configs.value[Number(event.detail.value)]?.id || 0 }
function retryCatalog() { if (state.catalogRetry) flow.load(state.catalogRetry.kind, state.catalogRetry.more) }
</script>

<template>
  <view class="page" data-testid="vehicle-manual">
    <text class="eyebrow">车主端 · 手动录入</text><text class="title">添加我的车辆</text>
    <text class="intro">选择车型并记录当前里程，车牌与VIN可稍后补充。</text>
    <view v-if="!ownerSession.accessToken" class="panel"><text>请先登录车主账号</text><button @tap="login">前往登录</button></view>
    <view v-else class="panel">
      <text class="label">品牌</text>
      <picker :disabled="locked || !state.brand.length" :range="state.brand" range-key="name" @change="pickBrand"><view class="field">{{ state.brand.find(item => item.id === state.brandId)?.name || '请选择品牌' }}</view></picker>
      <button v-if="state.brand.length < state.totals.brand" class="more" :disabled="locked" @tap="flow.load('brand', true)">加载更多品牌</button>
      <text class="label">车系</text>
      <picker :disabled="locked || !state.series.length" :range="state.series" range-key="name" @change="pickSeries"><view class="field">{{ state.series.find(item => item.id === state.seriesId)?.name || '请选择车系' }}</view></picker>
      <button v-if="state.series.length < state.totals.series" class="more" :disabled="locked" @tap="flow.load('series', true)">加载更多车系</button>
      <text class="label">年款</text>
      <picker :disabled="locked || !years.length" :range="years" @change="pickYear"><view class="field">{{ state.year || '请选择年款' }}</view></picker>
      <text class="label">配置</text>
      <picker :disabled="locked || !configs.length" :range="configs.map(item => item.config_name || '未命名配置')" @change="pickModel"><view class="field">{{ configs.find(item => item.id === state.modelId)?.config_name || (state.modelId ? '未命名配置' : '请选择配置') }}</view></picker>
      <button v-if="state.model.length < state.totals.model" class="more" :disabled="locked" @tap="flow.load('model', true)">加载更多年款与配置</button>
      <text class="label">当前里程（km）</text><input v-model="state.mileage" class="field" type="number" maxlength="10" :disabled="locked" placeholder="例如 32500" />
      <text class="label">车牌（选填）</text><input v-model="state.plate" class="field" maxlength="8" :disabled="locked" placeholder="普通或新能源大陆号牌" />
      <text class="label">VIN（选填）</text><input v-model="state.vin" class="field" maxlength="17" :disabled="locked" placeholder="17位车架号，不含I、O、Q" />
      <text v-if="state.busy" class="status" role="status">正在处理，请稍候…</text>
      <text v-else-if="state.message" class="status" :class="{ error: state.failureKind }" role="status">{{ state.message }}</text>
      <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">重新登录</button>
      <button v-else-if="state.saved" @tap="back">返回查看车辆</button>
      <template v-else>
        <button :disabled="locked || !state.modelId" :loading="state.busy" @tap="flow.save">保存车辆</button>
        <button v-if="state.catalogRetry" class="more" :disabled="locked" @tap="retryCatalog">重试加载车型</button>
      </template>
      <text class="note">车型暂无选项时请稍后再试。保存失败可再次点击保存；信息未修改时会沿用原请求，避免重复添加。</text>
    </view>
  </view>
</template>

<style scoped>
.page { padding: 52rpx 32rpx 96rpx; min-height: 100vh; box-sizing: border-box; background: #f2f3f5; display: flex; flex-direction: column; }
.eyebrow { color: #008f24; font-size: 23rpx; }
.title { color: #1d2129; font-size: 44rpx; font-weight: 700; margin-top: 16rpx; }
.intro { color: #4e5969; font-size: 26rpx; line-height: 40rpx; margin: 16rpx 0 28rpx; }
.panel { background: white; border-radius: 24rpx; padding: 32rpx; display: flex; flex-direction: column; }
.label { color: #4e5969; font-size: 26rpx; margin: 24rpx 0 12rpx; }
.field { height: 84rpx; line-height: 84rpx; padding: 0 20rpx; border: 1rpx solid #c9cdd4; border-radius: 12rpx; font-size: 27rpx; color: #1d2129; box-sizing: border-box; background: #fafafa; }
.field:focus { border-color: #00b42a; }
input[disabled] { color: #86909c; }
button { margin: 28rpx 0 0; min-height: 84rpx; font-size: 28rpx; border-radius: 14rpx; background: #00b42a; color: white; }
button::after { border: 0; }
button[disabled] { background: #f2f3f5; color: #86909c; }
.more { align-self: flex-start; background: #eef8f0; color: #008f24; font-size: 24rpx; min-height: 68rpx; }
.status { color: #008f24; margin-top: 24rpx; font-size: 25rpx; line-height: 38rpx; }
.error { color: #b42318; }
.note { color: #86909c; margin-top: 24rpx; font-size: 23rpx; line-height: 36rpx; }
</style>
