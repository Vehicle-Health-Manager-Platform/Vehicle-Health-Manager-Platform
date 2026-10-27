<script setup>
import {reactive,watch,onBeforeUnmount} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {initialAsrState,createAsrFlow} from '../services/asr-flow.js'
const props=defineProps({actor:{type:String,required:true},scope:{type:String,required:true},material:{type:Object,required:true},value:{type:String,default:''},disabled:Boolean})
const emit=defineEmits(['apply','reauth'])
const state=reactive(initialAsrState())
const flow=createAsrFlow({state,actor:()=>props.actor,scope:()=>props.scope,material:()=>props.material,value:()=>props.value,allowed:()=>!props.disabled,apply:text=>emit('apply',text)})
watch(()=>[props.actor,props.scope,props.material.materialId,props.material.durationMs],flow.reset,{flush:'sync'})
watch(()=>props.disabled,value=>{if(value)flow.reset()},{flush:'sync'})
watch(()=>state.consent,value=>{if(!value)flow.cancel()},{flush:'sync'})
onShow(flow.resume);onHide(flow.suspend);onUnload(flow.suspend);onBeforeUnmount(flow.suspend)
</script>
<template>
 <view class="asr" data-testid="asr-transcription">
  <text class="heading">语音转写（可选）</text><text class="hint">仅转写这份本人音频，处理可能需要等待。片段及修改均需核对，不自动发送给AI或保存档案。取消任务不删除音频，候选最多保留10分钟。</text>
  <label class="hint"><switch :checked="state.consent" :disabled="disabled" @change="state.consent=$event.detail.value" />同意将本份音频用于本次转写</label>
  <button :disabled="disabled||!state.consent||state.busy||['ready','applied','terminal','empty','cancelled'].includes(state.phase)" :loading="state.busy" @tap="flow.start">{{state.phase==='unknown'?'使用原请求确认创建':state.created?'查询当前任务':'创建转写任务'}}</button>
  <button v-if="state.phase!=='idle'&&state.phase!=='applied'" class="secondary" @tap="flow.cancel">取消本次转写</button>
  <button v-if="['terminal','empty','cancelled'].includes(state.phase)" class="secondary" @tap="flow.reset">清空任务状态后重新准备</button>
  <template v-if="state.result&&state.phase==='ready'">
   <text class="hint">来源：语音模型候选，可信度可能未知；不作为车辆诊断事实。</text>
   <view v-for="(segment,index) in state.result.segments" :key="index" class="segment"><text>{{segment.start_ms/1000}}–{{segment.end_ms/1000}}秒 · 可信度{{segment.confidence===null?'未知':segment.confidence}}</text><text>{{segment.text}}</text></view>
   <textarea :value="state.text" maxlength="1000" :disabled="disabled||state.busy" class="field" @input="flow.edit($event.detail.value)" />
   <label class="hint"><switch :checked="state.reviewed" :disabled="disabled||state.busy" @change="state.reviewed=$event.detail.value" />我已核对转写和本人修改</label>
   <button :disabled="disabled||!state.reviewed||state.busy" @tap="flow.confirm">核对当前授权后带入文字</button>
  </template>
  <text v-if="state.message" class="hint" role="status">{{state.message}}</text>
  <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" class="secondary" @tap="emit('reauth')">重新登录车主账号</button>
 </view>
</template>
<style scoped>
.asr{display:flex;flex-direction:column;gap:16rpx;padding:20rpx;border:1rpx solid #d6d3c9;border-radius:12rpx}.heading{font-size:26rpx;font-weight:600;color:#1a1d18}.hint,.segment{font-size:24rpx;line-height:38rpx;color:#454a41;word-break:break-all}.segment{display:flex;flex-direction:column}.field{width:100%;box-sizing:border-box;min-height:160rpx;padding:16rpx;border:1rpx solid #d6d3c9;border-radius:12rpx;font-size:26rpx;background:#fcfbf8}button{margin:0;padding:0 20rpx;min-height:76rpx;font-size:25rpx;border-radius:12rpx;background:#0b6b43;color:#fcfbf8}button::after{border:0}button[disabled]{background:#efede6;color:#63685e}.secondary{background:#e9f2ec;color:#074d30}
</style>
