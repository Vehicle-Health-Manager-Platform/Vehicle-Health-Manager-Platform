<script setup>
import {reactive,watch} from 'vue'
import {onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {vehicleApi,vehicleFailure} from '../../services/vehicles.js'
import {initialVehicleEntryState,createVehicleEntryFlow} from '../../services/vehicle-entry.js'
import {vehicleEntryPrefill} from '../../services/vehicle-entry-session.js'
import {selectOwnerVehicle} from '../../services/owner-vehicle-selection.js'
import {initialVinState,createVinFlow} from '../../services/vin-decode.js'
import {VIN_DISCLOSURE,vinSummary} from '../../services/vin-candidate.js'
import LicenseRecognition from '../../components/LicenseRecognition.vue'
const state=reactive(initialVehicleEntryState())
const flow=createVehicleEntryFlow({state,api:vehicleApi,token:()=>ownerSession.accessToken,prefill:vehicleEntryPrefill})
const vinState=reactive(initialVinState())
const vinFlow=createVinFlow({state:vinState,vin:()=>state.vin,token:()=>ownerSession.accessToken,prefill:vehicleEntryPrefill})
watch(()=>[ownerSession.accessToken,state.mode],vinFlow.reset,{flush:'sync'})
watch(()=>[state.vin,vinState.year,vinState.consent],vinFlow.invalidate,{flush:'sync'})
watch(()=>ownerSession.accessToken,flow.reset,{flush:'sync'})
watch(()=>state.plate,flow.invalidate,{flush:'sync'})
onHide(flow.reset)
onUnload(flow.reset)
onHide(vinFlow.reset)
onUnload(vinFlow.reset)
function login(){clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
function manual(){try{uni.navigateTo({url:flow.prepare()})}catch(error){const safe=vehicleFailure(error);state.message=safe.message;state.failureKind=safe.kind}}
function confirmedVin(){try{uni.navigateTo({url:vinFlow.prepare()})}catch(error){const safe=vehicleFailure(error);vinState.message=safe.message;vinState.failureKind=safe.kind}}
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
   <template v-else-if="state.mode==='license'"><LicenseRecognition @reauth="login" /><button class="secondary" @tap="manual">直接手动填写车辆资料</button></template>
   <template v-else-if="state.mode==='vin'">
    <text class="label">17位VIN（车架号）</text><input v-model="state.vin" class="field" maxlength="17" placeholder="不含I、O、Q" />
    <text class="label">年款提示（可留空）</text><input v-model="vinState.year" class="field" maxlength="4" type="number" placeholder="1980至2100" />
    <text class="copy">{{VIN_DISCLOSURE}}</text>
    <checkbox-group @change="vinState.consent=$event.detail.value.includes('yes')"><label class="copy"><checkbox value="yes" :checked="vinState.consent" />我已阅读并同意本次查询用途</label></checkbox-group>
    <button :disabled="!vinState.consent||vinState.busy" :loading="vinState.busy" @tap="vinFlow.recognize">查询VIN候选</button>
    <button v-if="vinState.busy" class="secondary" @tap="vinFlow.invalidate">取消查询</button>
    <view v-if="vinState.candidate&&vinState.candidate.status!=='NO_MATCH'" class="match">
     <text class="heading">{{vinState.candidate.status==='PARTIAL'?'部分候选信息':'车型候选信息'}}</text><text class="copy">来源：NHTSA（美国市场登记）</text><text class="copy">{{vinSummary(vinState.candidate)}}</text>
     <checkbox-group @change="vinState.reviewed=$event.detail.value.includes('yes')"><label class="copy"><checkbox value="yes" :checked="vinState.reviewed" />我已核对，继续手动选择车型</label></checkbox-group>
     <button :disabled="!vinState.reviewed" @tap="confirmedVin">带入VIN和候选摘要</button>
    </view>
    <text v-if="vinState.message" class="copy" :class="{error:vinState.failureKind}" role="status">{{vinState.message}}</text>
    <button v-if="['unauthorized','forbidden'].includes(vinState.failureKind)" @tap="login">重新登录</button>
    <button class="secondary" :disabled="vinState.busy" @tap="manual">直接带入VIN，手动确认车型</button>
   </template>
   <text v-if="state.message" class="copy" :class="{error:state.failureKind}" role="status">{{state.message}}</text>
   <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">重新登录</button>
  </view>
 </view>
</template>
<style scoped>
.page{padding:52rpx 32rpx 96rpx;min-height:100vh;box-sizing:border-box;background:#efede6;display:flex;flex-direction:column}.eyebrow{color:#074d30;font-size:23rpx}.title{font-size:44rpx;font-weight:700;color:#1a1d18;margin-top:16rpx}.intro,.copy{font-size:26rpx;line-height:40rpx;color:#454a41;margin:16rpx 0}.panel{background:#fcfbf8;border-radius:24rpx;padding:32rpx;display:flex;flex-direction:column}.modes{display:grid;grid-template-columns:1fr 1fr;gap:16rpx;margin-bottom:20rpx}.label,.heading{margin:20rpx 0 12rpx;font-size:27rpx;color:#1a1d18}.field{height:84rpx;padding:0 20rpx;border:1rpx solid #d6d3c9;border-radius:12rpx;font-size:27rpx;background:#fcfbf8}button{margin:20rpx 0 0;min-height:84rpx;font-size:27rpx;border-radius:14rpx;background:#0b6b43;color:#fcfbf8}button::after{border:0}.modes button,.secondary{background:#e9f2ec;color:#074d30}.modes button{margin:0;font-size:24rpx}.modes .active{background:#074d30;color:#fcfbf8}button[disabled]{background:#efede6;color:#63685e}.match{margin-top:24rpx;padding:24rpx 0;border-top:1rpx solid #e0ded6;display:flex;flex-direction:column;color:#1a1d18}.error{color:#b3261e}
</style>
