<script setup>
import { reactive, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import OwnerTabShell from '../../components/OwnerTabShell.vue'
import VehicleList from '../../components/VehicleList.vue'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { selectedOwnerVehicle, selectOwnerVehicle } from '../../services/owner-vehicle-selection.js'
import { archiveApi } from '../../services/archives.js'
import { createHomeSummaryFlow, initialHomeSummaryState } from '../../services/home-summary-flow.js'
import { archiveInputTypeName } from '../../services/archive-entry-mode.js'
import HealthOverview from '../../components/HealthOverview.vue'
import CouponExpiryOverview from '../../components/CouponExpiryOverview.vue'
import InvitationOverview from '../../components/InvitationOverview.vue'

const state = reactive(initialHomeSummaryState())
const flow = createHomeSummaryFlow({ state, api: archiveApi, token: () => ownerSession.accessToken })
const typeNames = ['保养', '维修', '保险', '事故', '改装', '违章', '年检', '行驶证资料', '驾驶证资料', '本人故障记录']
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
      <CouponExpiryOverview />
      <InvitationOverview />
      <HealthOverview v-if="selectedOwnerVehicle" :vehicle-id="selectedOwnerVehicle.vehicle_id" />
      <button v-if="selectedOwnerVehicle" class="secondary" @tap="uni.navigateTo({url:'/pages/community/experiences'})">查看同款施工经验</button>
      <view v-if="state.vehicle" class="summary ux-in ux-r2" data-testid="home-archive-summary">
        <text class="eyebrow">当前车辆</text>
        <text class="name">{{ state.vehicle.model_name || '车型信息暂不可用' }}</text>
        <view class="meter">
          <view class="meter-item">
            <text class="meter-label">当前里程</text>
            <view class="meter-value"><text class="meter-num ux-num">{{ state.vehicle.current_mileage }}</text><text class="meter-unit">km</text></view>
          </view>
          <view v-if="state.vehicle.plate_no_masked" class="meter-item">
            <text class="meter-label">车牌</text>
            <text class="meter-plate ux-num">{{ state.vehicle.plate_no_masked }}</text>
          </view>
        </view>
        <text v-if="state.busy" class="detail" role="status">正在加载档案摘要…</text>
        <template v-else-if="state.loaded">
          <text class="count">已记录 {{ state.total }} 条养护档案</text>
          <view v-if="state.latest" class="recent">
            <text class="section">最近记录</text>
            <text class="recent-title">{{ state.latest.title }}</text>
            <text v-if="state.latest.input_type === 4 && state.latest.test_mode" class="badge warn">测试施工记录，未真实扣款</text>
            <text class="detail">{{ typeNames[state.latest.archive_type - 1] }} · {{ archiveInputTypeName(state.latest.input_type) }} · {{ state.latest.recorded_date }}</text>
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
/* 仪表盘读数卡：首页的记忆点——里程像仪表屏一样等宽大字可读。 */
.summary { display: flex; flex-direction: column; background: #fcfbf8; border: 1rpx solid rgba(26, 29, 24, 0.14); border-left: 6rpx solid #0b6b43; border-radius: 20rpx; box-shadow: 0 2rpx 10rpx rgba(26, 29, 24, 0.07); padding: 30rpx; margin-top: 28rpx; box-sizing: border-box; }
.eyebrow { color: #074d30; font-size: 23rpx; font-weight: 600; letter-spacing: 4rpx; }
.name { margin-top: 12rpx; font-size: 34rpx; font-weight: 700; color: #1a1d18; letter-spacing: 1rpx; }
.meter { display: flex; flex-wrap: wrap; gap: 20rpx 48rpx; margin-top: 24rpx; padding: 24rpx; border: 1rpx solid rgba(26, 29, 24, 0.14); border-radius: 12rpx; background: #efede6; }
.meter-item { display: flex; flex-direction: column; }
.meter-label { color: #63685e; font-size: 23rpx; letter-spacing: 2rpx; }
.meter-value { display: flex; align-items: baseline; gap: 8rpx; margin-top: 8rpx; }
.meter-num { color: #1a1d18; font-size: 64rpx; line-height: 1.05; font-weight: 700; letter-spacing: -1rpx; font-variant-numeric: tabular-nums; }
.meter-unit { color: #63685e; font-size: 26rpx; font-weight: 600; }
.meter-plate { margin-top: 14rpx; color: #1a1d18; font-size: 34rpx; font-weight: 650; letter-spacing: 4rpx; font-variant-numeric: tabular-nums; }
.detail { color: #454a41; font-size: 25rpx; line-height: 1.65; margin-top: 12rpx; }
.count { align-self: flex-start; padding: 8rpx 20rpx; border-radius: 999rpx; background: #e9f2ec; color: #074d30; font-size: 25rpx; font-weight: 600; margin-top: 26rpx; }
.recent { display: flex; flex-direction: column; align-items: flex-start; margin-top: 24rpx; padding-top: 22rpx; border-top: 1rpx solid rgba(26, 29, 24, 0.14); }
.section { color: #8a8f84; font-size: 23rpx; letter-spacing: 2rpx; }
.recent-title { color: #1a1d18; font-size: 29rpx; font-weight: 650; margin-top: 12rpx; }
.badge { align-self: flex-start; margin-top: 12rpx; padding: 6rpx 16rpx; border-radius: 999rpx; font-size: 22rpx; font-weight: 600; }
.badge.warn { background: #f7ecdf; color: #9a4700; }
.error { color: #b3261e; font-size: 25rpx; margin-top: 20rpx; }
button { margin: 24rpx 0 0; min-height: 80rpx; border-radius: 12rpx; font-size: 27rpx; font-weight: 600; box-sizing: border-box; }
button::after { border: 0; }
.primary { border: 1rpx solid #0b6b43; background: #0b6b43; color: #fcfbf8; }
.primary:active { transform: translateY(2rpx); }
.secondary { align-self: flex-start; padding: 0 28rpx; border: 1rpx solid rgba(26, 29, 24, 0.14); background: #e9f2ec; color: #074d30; }
</style>
