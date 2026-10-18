<script setup>
import { reactive, watch, computed } from 'vue'
import InputCandidateReview from '../../components/InputCandidateReview.vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { archiveStructuredApi, structuredFields, structuredTypes } from '../../services/archive-structured.js'
import { createStructuredFlow, initialStructuredState } from '../../services/archive-structured-flow.js'
import { imageRequestKey } from '../../services/private-images.js'

const state=reactive(initialStructuredState())
const licenseCandidates=computed(()=>(structuredFields[8]||[]).filter(d=>['text','date','number'].includes(d.kind)).map(d=>({...d,kind:d.key==='vin'?'vin':d.key==='plate_number'?'plate':d.kind,max:d.max||100})))
const flow=createStructuredFlow({state,api:archiveStructuredApi,token:()=>ownerSession.accessToken,newKey:imageRequestKey,
  confirmClear:()=>new Promise(resolve=>uni.showModal({title:'清空本条结构化资料',content:'移除本页填写的资料，原档案、照片及个人提醒目标保留。',success:r=>resolve(Boolean(r.confirm)),fail:()=>resolve(false)})),
})
let visible=false
onLoad(query=>flow.route(Number(query.archive_id),Number(query.vehicle_id)))
onShow(()=>{visible=true;flow.resume()})
onHide(()=>{visible=false;flow.suspend()})
onUnload(()=>{visible=false;flow.reset()})
watch(()=>ownerSession.accessToken,()=>{flow.reset();if(visible)flow.resume()},{flush:'sync'})
watch(()=>JSON.stringify({...state.form,owner_confirmed:undefined}),()=>{if(!state.busy&&state.form.owner_confirmed)state.form.owner_confirmed=false},{flush:'sync'})
function login(){flow.reset();clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
function optionLabel(def){const index=def.values.indexOf(state.form[def.key]);return index<0?'未填写':def.labels[index]}
</script>

<template>
  <view class="page">
    <text class="title">{{ structuredTypes[state.context?.archive_type] || '本人档案资料' }}</text>
    <text class="hint">本人填写，尚非官方核验。请逐项核对原资料，确认关键字段和来源后勾选本人确认；修改字段后需再次确认。未填写不会推断。证件/身份字段仅本人可读，不进入公开分享或第三方AI。</text>
    <text v-if="state.busy" class="hint">正在处理…</text>
    <view v-if="state.context" class="card">
      <InputCandidateReview v-if="state.context.archive_type===8" :actor="ownerSession.accessToken" :scope="'license:'+state.context.vehicle_id+':'+state.context.archive_id+':'+state.context.revision" scene="license" :fields="licenseCandidates" :values="state.form" :disabled="state.busy||state.kind==='conflict'" @apply="Object.assign(state.form,$event,{owner_confirmed:false})" />
      <text class="heading">来源：{{ structuredTypes[state.context.archive_type] }} · {{ state.context.recorded_date || '日期未填写' }}</text>
      <text class="hint">{{ state.context.status === 'NOT_SET' ? '本条尚无结构化资料' : '现有资料为本人填写、未核验' }}</text>
      <view v-for="def in structuredFields[state.context.archive_type]" :key="def.key">
        <text class="label">{{ def.label }}{{def.kind==='boolean'?'':'（未确认资料可留空）'}}</text>
        <template v-if="def.kind === 'boolean'"><switch :checked="state.form[def.key]===true" :disabled="state.busy" @change="state.form[def.key]=$event.detail.value" /></template>
        <template v-else-if="def.kind === 'date'">
          <picker mode="date" :value="state.form[def.key]" :disabled="state.busy" @change="state.form[def.key]=$event.detail.value"><view class="input">{{ state.form[def.key] || '选择资料所载日期' }}</view></picker>
          <button v-if="state.form[def.key]" class="secondary" :disabled="state.busy" @tap="state.form[def.key]=''">清除该日期</button>
        </template>
        <template v-else-if="def.kind === 'enum'">
          <picker :range="def.labels" :disabled="state.busy" @change="state.form[def.key]=def.values[Number($event.detail.value)]"><view class="input">{{ optionLabel(def) }}</view></picker>
          <button v-if="state.form[def.key]" class="secondary" :disabled="state.busy" @tap="state.form[def.key]=''">改为未填写</button>
        </template>
        <textarea v-else-if="def.kind === 'items'" v-model="state.form[def.key]" class="input items" :maxlength="def.max" :disabled="state.busy" placeholder="每行一项，最多20项，每项80字符" />
        <input v-else v-model="state.form[def.key]" class="input" :type="def.kind === 'number' ? 'number' : 'text'" :maxlength="def.max" :disabled="state.busy" placeholder="按本人记录填写，可留空" />
      </view>
      <text class="hint">本人确认的故障/违章状态用于首页提醒；修正或清空后重新计算。其他资料不自动改写登记里程、提醒目标或已有分享摘要。</text>
      <button :disabled="state.busy || state.kind === 'conflict'" :loading="state.busy" @tap="flow.save(false)">保存本人资料</button>
      <button v-if="Object.keys(state.context.fields).length" class="danger" :disabled="state.busy || state.kind === 'conflict'" @tap="flow.save(true)">清空本条结构化资料</button>
    </view>
    <text v-if="state.message" class="message" :class="{error:state.kind}">{{ state.message }}</text>
    <button v-if="['unauthorized','forbidden'].includes(state.kind) || !ownerSession.accessToken" @tap="login">登录车主账号</button>
    <button v-else class="secondary" :disabled="state.busy" @tap="flow.load">{{ state.kind === 'conflict' ? '刷新并复核资料' : '重新读取资料' }}</button>
  </view>
</template>

<style scoped>
.page{padding:32rpx}.title{display:block;font-size:36rpx;font-weight:700}.hint{display:block;margin-top:16rpx;color:#697585;font-size:24rpx;line-height:38rpx}.card{margin-top:24rpx;padding:28rpx;background:#fff;border-radius:20rpx}.heading{font-size:28rpx;font-weight:600}.label{display:block;margin:24rpx 0 12rpx;font-size:25rpx}.input{padding:20rpx;border:1rpx solid #d9dfe8;border-radius:12rpx;font-size:26rpx}.items{width:100%;box-sizing:border-box;min-height:180rpx}.message{display:block;margin-top:20rpx;font-size:25rpx;line-height:38rpx;color:#008f24}.error{color:#b42318}button{margin-top:20rpx;background:#00b42a;color:#fff;border-radius:14rpx;font-size:26rpx}button::after{border:0}.secondary{background:#eef8f0;color:#008f24}.danger{background:#fff2e8;color:#b42318}
</style>
