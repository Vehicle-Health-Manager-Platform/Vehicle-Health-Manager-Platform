<script setup>
import {reactive,watch,onBeforeUnmount} from 'vue'
import {onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession} from '../services/owner-session.js'
import {vehicleEntryPrefill} from '../services/vehicle-entry-session.js'
import {LICENSE_DISCLOSURE,initialLicenseState,createLicenseFlow} from '../services/license-recognition.js'
import {vehicleFailure} from '../services/vehicles.js'
defineEmits(['reauth'])
const state=reactive(initialLicenseState())
const flow=createLicenseFlow({state,token:()=>ownerSession.accessToken,prefill:vehicleEntryPrefill})
watch(()=>ownerSession.accessToken,flow.reset,{flush:'sync'})
watch(()=>state.consent,flow.invalidate,{flush:'sync'})
onHide(flow.reset);onUnload(flow.reset);onBeforeUnmount(flow.reset)
const statusText={CANDIDATE:'候选',PARTIAL:'部分字段',LOW_CONFIDENCE:'低可信',AMBIGUOUS:'多值待确认',NO_CANDIDATE:'无候选'}
function confirm(){try{uni.navigateTo({url:flow.prepare()})}catch(error){const safe=vehicleFailure(error);state.message=safe.message;state.failureKind=safe.kind}}
</script>
<template>
 <view class="license" data-testid="license-recognition">
  <text class="heading">行驶证车牌 / VIN 提取</text><text class="hint">仅提取标注字段，不能证明证件真伪；车型和里程仍需手动确认。支持4MiB以内的JPEG/PNG。上传成功不等于识别成功，服务不可用时可直接手动录车。</text>
  <button :disabled="state.busy" @tap="flow.choose">{{state.selected?'重新选择照片':'选择行驶证照片（暂不上传）'}}</button>
  <image v-if="state.selected" class="preview" :src="state.selected.path" mode="aspectFit" />
  <text class="hint">{{LICENSE_DISCLOSURE}}</text>
  <label class="hint"><switch :checked="state.consent" :disabled="!state.selected" @change="state.consent=$event.detail.value" />我同意本次新车上传识别用途</label>
  <button :disabled="!state.selected||!state.consent||state.busy" :loading="state.busy" @tap="flow.run">{{state.phase==='uploading'?'正在上传':state.phase==='recognizing'?'正在识别':state.uploaded?'重新识别已上传照片':'上传并识别'}}</button>
  <button v-if="state.busy" class="secondary" @tap="flow.invalidate">取消本次处理</button>
  <template v-if="state.candidate">
   <text class="heading">{{statusText[state.candidate.review_status]}} · 本地OCR</text>
   <view v-for="field in state.candidate.fields" :key="field.key" class="candidate">
    <text class="hint">{{field.key==='plate'?'车牌':'VIN'}}：{{statusText[field.status]}}</text>
    <text v-for="row in field.candidates" :key="row.value" class="hint">{{row.value}}（OCR可信度 {{row.confidence}}）</text>
   </view>
   <text class="hint">核对后填写或修正下方内容；手动修改不代表模型原始结果，多值项请自行确定。</text>
   <text class="label">待带入车牌（可留空）</text><input class="field" :value="state.form.plate" maxlength="8" placeholder="核对照片上的车牌" @input="flow.edit('plate',$event.detail.value)" />
   <text class="label">待带入VIN（可留空）</text><input class="field" :value="state.form.vin" maxlength="17" placeholder="核对17位VIN，不含I/O/Q" @input="flow.edit('vin',$event.detail.value)" />
   <label class="hint"><switch :checked="state.reviewed" @change="state.reviewed=$event.detail.value" />我已逐项对照照片核对，包括本人修改</label>
   <button :disabled="!state.reviewed||state.busy" @tap="confirm">带入车牌/VIN，手动选择车型</button>
  </template>
  <text v-if="state.message" class="hint" :class="{error:state.failureKind}" role="status">{{state.message}}</text>
  <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" class="secondary" @tap="$emit('reauth')">重新登录</button>
 </view>
</template>
<style scoped>
.license{display:flex;flex-direction:column;gap:16rpx}.heading{font-size:28rpx;font-weight:600;color:#1a1d18}.hint{font-size:25rpx;line-height:40rpx;color:#454a41;word-break:break-all}.preview{width:100%;height:300rpx;border:1rpx solid #d6d3c9;border-radius:12rpx}.label{font-size:26rpx;color:#1a1d18}.field{height:84rpx;padding:0 20rpx;border:1rpx solid #d6d3c9;border-radius:12rpx;font-size:27rpx;background:#fcfbf8}.candidate{display:flex;flex-direction:column;gap:8rpx}button{margin:0;min-height:84rpx;font-size:26rpx;border-radius:14rpx;background:#0b6b43;color:#fcfbf8}button::after{border:0}button[disabled]{background:#efede6;color:#63685e}.secondary{background:#e9f2ec;color:#074d30}.error{color:#b3261e}
</style>
