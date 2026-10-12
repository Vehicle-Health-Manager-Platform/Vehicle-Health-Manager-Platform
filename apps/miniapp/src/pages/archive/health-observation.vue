<script setup>
import { reactive,watch,computed } from 'vue'
import { onLoad,onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { ownerSession,clearOwnerSession } from '../../services/owner-session.js'
import { healthObservationApi,healthLabels,healthUnits,unitLabels,provenanceLabels,provenanceValues,observationStatus } from '../../services/health-observations.js'
import { createObservationFlow,initialObservationState } from '../../services/health-observation-flow.js'
import { imageRequestKey } from '../../services/private-images.js'
const state=reactive(initialObservationState())
const unitChoices=computed(()=> (healthUnits[state.context?.metric] || []).map(unit=>unitLabels[unit]))
const confirm=(title,content)=>new Promise(resolve=>uni.showModal({title,content,success:r=>resolve(Boolean(r.confirm)),fail:()=>resolve(false)}))
const flow=createObservationFlow({state,api:healthObservationApi,token:()=>ownerSession.accessToken,newKey:imageRequestKey,
  confirmClear:()=>confirm('清空本类当前读数','本类将显示未录入，原档案及照片保留。'),
  confirmReplacement:()=>confirm('更换本类读数来源','使用当前档案及本次读数替换原来源与当前读数。请先核对原资料。'),
})
let visible=false
onLoad(q=>flow.route(Number(q.archive_id),Number(q.vehicle_id),q.metric))
onShow(()=>{visible=true;flow.resume()})
onHide(()=>{visible=false;flow.suspend()})
onUnload(()=>{visible=false;flow.reset()})
watch(()=>ownerSession.accessToken,()=>{flow.reset();if(visible)flow.resume()},{flush:'sync'})
function login(){flow.reset();clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
</script>
<template>
  <view class="page">
    <text class="title">{{ healthLabels[state.context?.metric] || '本人读数' }}</text>
    <text class="hint">原始读数由本人提供，尚未核验。请按车型与测量资料核对适用性、名称、单位和方法；本页不提供健康诊断或分数。</text>
    <text v-if="state.busy" class="hint">正在处理…</text>
    <view v-if="state.context" class="card">
      <text class="heading">来源：本人{{ state.context.archive_type===1?'保养':'维修' }}记录 · {{ state.context.recorded_date || '日期未填写' }}</text>
      <text class="hint">本类当前状态：{{ observationStatus[state.context.observation.status] }}</text>
      <view v-if="state.context.observation.status!=='NOT_SET' && state.context.observation.archive_id!==state.context.archive_id" class="notice">
        <text class="hint">保存前将确认更换来源。原始档案不变。</text>
        <template v-if="state.context.observation.status==='RECORDED_UNVERIFIED'">
          <text class="hint">原名称：{{ state.context.observation.measurement.measurement_name }}</text>
          <text class="hint">原读数：{{ state.context.observation.measurement.value }} {{ unitLabels[state.context.observation.measurement.unit] }} · {{ state.context.observation.measurement.measured_on }}</text>
          <text class="hint">原方法：{{ state.context.observation.measurement.method }}</text>
        </template>
      </view>
      <text class="label">测量名称</text><input v-model="state.form.measurement_name" class="input" maxlength="80" :disabled="state.busy" placeholder="按实际资料填写，明确测的是什么" />
      <text class="label">读数（最多3位小数）</text><input v-model="state.form.value" class="input" maxlength="14" :disabled="state.busy" placeholder="填写原始读数，不输入单位或百分号" />
      <text class="label">单位</text>
      <picker :range="unitChoices" :disabled="state.busy" @change="state.form.unit=healthUnits[state.context.metric][Number($event.detail.value)]"><view class="input">{{ unitLabels[state.form.unit] || '请选择单位' }}</view></picker>
      <text class="label">实际测量/资料日期</text><picker mode="date" :end="state.context.today" :value="state.form.measured_on" :disabled="state.busy" @change="state.form.measured_on=$event.detail.value"><view class="input">{{ state.form.measured_on || '选择实际日期' }}</view></picker>
      <text class="label">测量方法或资料位置</text><input v-model="state.form.method" class="input" maxlength="120" :disabled="state.busy" placeholder="注明仪表/检测方法，或抄录资料的位置" />
      <text class="label">本人数据来源</text><picker :range="provenanceLabels" :disabled="state.busy" @change="state.form.provenance=provenanceValues[Number($event.detail.value)]"><view class="input">{{ provenanceLabels[provenanceValues.indexOf(state.form.provenance)] || '请选择来源' }}</view></picker>
      <text class="hint">百分比只表示资料中的读数，不是平台健康分。数据有效期、合格阈值及车型适用性尚未核验。</text>
      <button :disabled="state.busy||state.kind==='conflict'" :loading="state.busy" @tap="flow.save(false)">保存本人读数</button>
      <button v-if="state.context.observation.archive_id===state.context.archive_id" class="danger" :disabled="state.busy||state.kind==='conflict'" @tap="flow.save(true)">清空本类当前读数</button>
    </view>
    <text v-if="state.message" class="message" :class="{error:state.kind}">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken||['unauthorized','forbidden'].includes(state.kind)" @tap="login">登录车主账号</button>
    <button v-else class="secondary" :disabled="state.busy" @tap="flow.load">{{ state.kind==='conflict'?'刷新并复核来源与读数':'重新读取' }}</button>
  </view>
</template>
<style scoped>
.page{padding:32rpx}.title{display:block;font-size:36rpx;font-weight:700}.hint{display:block;margin-top:16rpx;color:#697585;font-size:24rpx;line-height:38rpx}.card{margin-top:24rpx;padding:28rpx;background:#fff;border-radius:20rpx}.heading{font-size:28rpx;font-weight:600}.label{display:block;margin:24rpx 0 12rpx;font-size:25rpx}.input{padding:20rpx;border:1rpx solid #d9dfe8;border-radius:12rpx;font-size:26rpx}.message{display:block;margin-top:20rpx;font-size:25rpx;line-height:38rpx;color:#008f24}.error{color:#b42318}button{margin-top:20rpx;background:#00b42a;color:#fff;border-radius:14rpx;font-size:26rpx}button::after{border:0}.secondary{background:#eef8f0;color:#008f24}.danger,.notice{background:#fff2e8;color:#b42318}.notice{padding:16rpx;border-radius:12rpx}
</style>
