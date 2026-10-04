<script setup>
import { reactive, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import OwnerTabShell from '../../components/OwnerTabShell.vue'
import VehicleList from '../../components/VehicleList.vue'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { selectedOwnerVehicle, selectOwnerVehicle } from '../../services/owner-vehicle-selection.js'
import { archiveApi } from '../../services/archives.js'
import { createHomeSummaryFlow, initialHomeSummaryState } from '../../services/home-summary-flow.js'

const state = reactive(initialHomeSummaryState())
const flow = createHomeSummaryFlow({ state, api: archiveApi, token: () => ownerSession.accessToken })
const typeNames = ['保养', '维修', '保险', '事故', '改装', '违章', '年检']
let visible = false
watch(() => selectedOwnerVehicle.value, row => { if (visible) flow.select(row); else flow.reset() }, { flush: 'sync' })
watch(() => ownerSession.accessToken, flow.reset, { flush: 'sync' })
onShow(() => { visible = true; if (selectedOwnerVehicle.value) { flow.select(selectedOwnerVehicle.value); flow.load() } })
onHide(() => { visible = false; flow.suspend() })
onUnload(() => { visible = false; flow.reset() })
function select(item) { selectOwnerVehicle(item) }
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function archive() { uni.switchTab({ url: '/pages/archive/index' }) }
</script>

<template>
  <OwnerTabShell label="首页" title="爱车健康，一眼掌握" description="从本人车辆和养护记录查看真实用车信息。" next-action="首页数据正在接入。" business-ready>
    <template #content>
      <VehicleList :selected-id="selectedOwnerVehicle?.vehicle_id || 0" @select="select" />
      <view v-if="state.vehicle" class="summary" data-testid="home-archive-summary">
        <text class="eyebrow">当前车辆</text>
        <text class="name">{{ state.vehicle.model_name || '车型信息暂不可用' }}</text>
        <text v-if="state.vehicle.plate_no_masked" class="detail">车牌 {{ state.vehicle.plate_no_masked }}</text>
        <text class="detail">当前里程 {{ state.vehicle.current_mileage }} km</text>
        <text v-if="state.busy" class="detail" role="status">正在加载档案摘要…</text>
        <template v-else-if="state.loaded">
          <text class="count">已记录 {{ state.total }} 条养护档案</text>
          <view v-if="state.latest" class="recent">
            <text class="section">最近记录</text>
            <text class="recent-title">{{ state.latest.title }}</text>
            <text class="detail">{{ typeNames[state.latest.archive_type - 1] }} · {{ state.latest.recorded_date }}</text>
          </view>
          <text v-else class="detail">这辆车还没有档案记录。</text>
          <button class="primary" @tap="archive">{{ state.latest ? '查看完整档案' : '去录入第一条记录' }}</button>
        </template>
        <template v-if="state.message">
          <text class="error" role="status">{{ state.message }}</text>
          <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" class="secondary" @tap="login">重新登录</button>
          <button v-else class="secondary" :disabled="state.busy" @tap="flow.load">重试加载档案</button>
        </template>
      </view>
    </template>
  </OwnerTabShell>
</template>

<style scoped>
.summary { display: flex; flex-direction: column; background: #fff; border-radius: 24rpx; padding: 32rpx; margin-top: 28rpx; }
.eyebrow { color: #008f24; font-size: 23rpx; }.name { margin-top: 12rpx; font-size: 34rpx; font-weight: 650; color: #1d2129; }
.detail { color: #4e5969; font-size: 25rpx; line-height: 38rpx; margin-top: 12rpx; }.count { color: #008f24; font-size: 29rpx; font-weight: 650; margin-top: 24rpx; }
.recent { display: flex; flex-direction: column; margin-top: 24rpx; padding-top: 22rpx; border-top: 1rpx solid #e5e6eb; }
.section { color: #4e5969; font-size: 24rpx; }.recent-title { color: #1d2129; font-size: 29rpx; font-weight: 600; margin-top: 12rpx; }
.error { color: #b42318; font-size: 25rpx; margin-top: 20rpx; }
button { margin: 24rpx 0 0; min-height: 78rpx; border-radius: 14rpx; font-size: 27rpx; }button::after { border: 0; }
.primary { background: #00b42a; color: white; }.secondary { align-self: flex-start; padding: 0 24rpx; background: #eef8f0; color: #008f24; }
</style>
