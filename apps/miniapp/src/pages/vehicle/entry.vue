<script setup>
import {reactive,watch} from 'vue'
import {onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {vehicleApi,vehicleFailure} from '../../services/vehicles.js'
import {initialVehicleEntryState,createVehicleEntryFlow} from '../../services/vehicle-entry.js'
import {vehicleEntryPrefill} from '../../services/vehicle-entry-session.js'
import {selectOwnerVehicle} from '../../services/owner-vehicle-selection.js'
const state=reactive(initialVehicleEntryState())
const flow=createVehicleEntryFlow({state,api:vehicleApi,token:()=>ownerSession.accessToken,prefill:vehicleEntryPrefill})
watch(()=>ownerSession.accessToken,flow.reset,{flush:'sync'})
watch(()=>state.plate,flow.invalidate,{flush:'sync'})
onHide(flow.reset)
onUnload(flow.reset)
function login(){clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
function manual(){try{uni.navigateTo({url:flow.prepare()})}catch(error){const safe=vehicleFailure(error);state.message=safe.message;state.failureKind=safe.kind}}
function choose(row){try{selectOwnerVehicle(flow.select(row));uni.switchTab({url:'/pages/archive/index'})}catch(error){const safe=vehicleFailure(error);state.message=safe.message;state.failureKind=safe.kind}}
</script>
<template>
 <view class="page" data-testid="vehicle-entry">
  <text class="eyebrow">车主端 · 添加车辆</text><text class="title">选择录车方式</text>
  <text class="intro">已有车辆先匹配本人记录；新车辆由你确认车型和里程后保存。</text>
  <view v-if="!ownerSession.accessToken" class="panel"><text>请先登录车主账号</text><button @tap="login">前往登录</button></view>
  <view v-else class="panel">
   <view class="modes"><button :class="{active:state.mode==='manual'}" @tap="flow.mode('manual')">手动选择车型</button><button :class="{active:state.mode==='plate'}" @tap="flow.mode('plate')">车牌匹配本人车辆</button><button :class="{active:state.mode==='license'}" @tap="flow.mode('license')">行驶证录车</button><button :class="{active:state.mode==='vin'}" @tap="flow.mode('vin')">VIN 录车</button></view>
   <template v-if="state.mode==='manual'"><text class="copy">选择品牌、车系、年款与配置，确认当前里程；车牌和VIN可选填。</text><button @tap="manual">进入手动录车</button></template>
   <template v-else-if="state.mode==='plate'">
    <text class="label">车牌</text><input v-model="state.plate" class="field" maxlength="8" :disabled="state.busy" placeholder="普通或新能源大陆号牌" />
    <text class="copy">仅查找你已登记的车辆，不能查询其他车主，也不能凭车牌确定新车车型。</text>
    <button :disabled="state.busy" :loading="state.busy" @tap="flow.match">匹配本人车辆</button>
    <view v-for="row in state.matches" :key="row.vehicle_id" class="match"><text>{{row.model_name || '车型信息暂不可用'}}</text><text class="copy">{{row.plate_no_masked}} · {{row.current_mileage}} km</text><button @tap="choose(row)">选择此车查看档案</button></view>
    <button class="secondary" :disabled="state.busy" @tap="manual">带入车牌，手动登记新车</button>
   </template>
   <template v-else-if="state.mode==='license'"><text class="heading">行驶证识别尚未开通</text><text class="copy">当前可以手动填写证件上的车牌与VIN，并确认车型。不上传或保存行驶证照片。</text><button @tap="manual">手动填写车辆资料</button></template>
   <template v-else-if="state.mode==='vin'"><text class="label">17位VIN（车架号）</text><input v-model="state.vin" class="field" maxlength="17" placeholder="不含I、O、Q" /><text class="copy">VIN车型解码尚未开通。校验后将带入手动表单，请自行选择并确认车型。</text><button @tap="manual">校验VIN并手动确认车型</button></template>
   <text v-if="state.message" class="copy" :class="{error:state.failureKind}" role="status">{{state.message}}</text>
   <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">重新登录</button>
  </view>
 </view>
</template>
<style scoped>
.page{padding:52rpx 32rpx 96rpx;min-height:100vh;box-sizing:border-box;background:#f2f3f5;display:flex;flex-direction:column}.eyebrow{color:#008f24;font-size:23rpx}.title{font-size:44rpx;font-weight:700;color:#1d2129;margin-top:16rpx}.intro,.copy{font-size:26rpx;line-height:40rpx;color:#4e5969;margin:16rpx 0}.panel{background:white;border-radius:24rpx;padding:32rpx;display:flex;flex-direction:column}.modes{display:grid;grid-template-columns:1fr 1fr;gap:16rpx;margin-bottom:20rpx}.label,.heading{margin:20rpx 0 12rpx;font-size:27rpx;color:#1d2129}.field{height:84rpx;padding:0 20rpx;border:1rpx solid #c9cdd4;border-radius:12rpx;font-size:27rpx;background:#fafafa}button{margin:20rpx 0 0;min-height:84rpx;font-size:27rpx;border-radius:14rpx;background:#00b42a;color:white}button::after{border:0}.modes button,.secondary{background:#eef8f0;color:#008f24}.modes button{margin:0;font-size:24rpx}.modes .active{background:#008f24;color:white}button[disabled]{background:#f2f3f5;color:#86909c}.match{margin-top:24rpx;padding:24rpx 0;border-top:1rpx solid #e5e6eb;display:flex;flex-direction:column;color:#1d2129}.error{color:#b42318}
</style>
