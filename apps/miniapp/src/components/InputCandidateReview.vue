<script setup>
import {reactive,ref,watch,onBeforeUnmount} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {createCandidateFlow,initialCandidateState} from '../services/input-candidates.js'
import {recognizeMileage,recognizePickupMileage} from '../services/mileage-recognition.js'
const selectedFile=ref(0), consent=ref(false)
const props=defineProps({actor:{type:String,required:true},scope:{type:String,required:true},scene:{type:String,required:true},fields:{type:Array,required:true},values:{type:Object,required:true},disabled:Boolean,vehicleId:{type:Number,default:0},orderId:{type:Number,default:0},fileIds:{type:Array,default:()=>[]}})
const emit=defineEmits(['apply','busy'])
const state=reactive(initialCandidateState())
const flow=createCandidateFlow({state,actor:()=>props.actor,scope:()=>props.scope+':'+selectedFile.value,scene:()=>props.scene,definitions:()=>props.fields,values:()=>props.values,allowed:()=>!props.disabled,timeoutMs:(props.vehicleId||props.orderId)?30000:15000,apply:patch=>emit('apply',patch)})
watch(()=>[props.actor,props.scope,props.vehicleId,props.orderId,props.scene,JSON.stringify(props.fields),JSON.stringify(props.fileIds)],()=>{selectedFile.value=0;consent.value=false;flow.cancel()},{flush:'sync'})
watch(selectedFile,()=>{consent.value=false;flow.cancel()},{flush:'sync'})
watch(consent,value=>{if(!value)flow.cancel()},{flush:'sync'})
watch(()=>props.disabled,value=>{if(value){consent.value=false;flow.cancel()}},{flush:'sync'})
watch(()=>state.phase,value=>emit('busy',value==='recognizing'),{flush:'sync'})
function cancel(){consent.value=false;flow.cancel()}
function suspend(){consent.value=false;flow.suspend()}
function recognize(){
 if(!consent.value||!props.fileIds.includes(selectedFile.value)||!(props.vehicleId||props.orderId)||props.disabled)return
 const token=props.actor,vehicleId=props.vehicleId,orderId=props.orderId,fileId=selectedFile.value
 flow.recognize(({signal})=>orderId?recognizePickupMileage({token,orderId,fileId,consent:true,signal}):recognizeMileage({token,vehicleId,fileId,consent:true,signal}))
}
onShow(flow.resume);onHide(suspend);onUnload(suspend);onBeforeUnmount(suspend)
</script>
<template>
 <view class="candidates" data-testid="input-candidate-review">
  <text class="heading">材料内容核对</text>
  <text class="hint">可对照原材料手动整理候选；确认仅带入本页，仍需原保存或发送按钮。</text>
  <view v-if="(vehicleId||orderId)&&fileIds.length" class="row">
   <text class="hint">里程图片识别（可选）：选定一张已上传图片，仅用于提取{{orderId?'本次接车':'本次档案'}}里程。服务未启用时请手动整理；多个里程无法确定时不会自动选择。</text>
   <picker data-testid="mileage-source" :disabled="disabled" :range="fileIds.map((id,index)=>'图片 '+(index+1))" @change="selectedFile=fileIds[Number($event.detail.value)]"><view class="field">{{selectedFile?'已选图片 '+(fileIds.indexOf(selectedFile)+1):'请选择识别图片'}}</view></picker>
   <label class="hint"><switch data-testid="mileage-consent" :checked="consent" :disabled="disabled||!selectedFile" @change="consent=$event.detail.value" />同意将选定图片用于本次里程识别，结果由我核对</label>
   <button data-testid="mileage-recognize" :disabled="disabled||!selectedFile||!consent||state.phase==='recognizing'" @tap="recognize">{{state.phase==='recognizing'?'正在识别，可取消':'识别所选图片的里程'}}</button>
  </view>
  <button v-if="['closed','applied'].includes(state.phase)" :disabled="disabled||!actor" data-testid="candidate-begin" @tap="flow.begin">手动整理候选</button>
  <template v-else>
   <text class="hint">来源：{{state.phase==='recognizing'?'正在处理所选图片，尚无候选结果':state.source==='PROVIDER'?'机器候选（尚需本人核对）':'本人手动整理（未机器识别）'}}</text>
   <view v-for="def in fields" :key="def.key" class="row">
    <text>{{def.label}}</text><text class="hint">当前：{{values[def.key]||'未填写'}}</text>
    <textarea v-if="def.kind==='multiline'" :value="state.form[def.key]||''" :maxlength="def.max||100" :disabled="disabled||state.phase==='recognizing'" class="field multiline" @input="flow.edit(def.key,$event.detail.value)" />
    <input v-else :data-testid="'candidate-'+def.key" :value="state.form[def.key]||''" :maxlength="def.max||100" :disabled="disabled||state.phase==='recognizing'" class="field" @input="flow.edit(def.key,$event.detail.value)" />
    <text v-if="state.source==='PROVIDER'&&state.rows.find(r=>r.key===def.key)" class="hint">可信度：{{state.rows.find(r=>r.key===def.key)?.confidence??'未知'}}，请核对原材料</text>
   </view>
   <button v-if="state.phase==='editing'" :disabled="disabled" data-testid="candidate-review" @tap="flow.review">检查候选内容</button>
   <template v-if="state.phase==='review'">
    <text class="hint">以下已填写项目将替换当前表单对应值，空项保留原值。</text>
    <view v-for="row in state.rows" :key="row.key" class="hint">{{fields.find(d=>d.key===row.key)?.label}}：{{row.value}}</view>
    <label class="hint"><switch data-testid="candidate-ack" :checked="state.ack" :disabled="disabled" @change="state.ack=$event.detail.value" />我已逐项对照原材料核对</label>
    <button :disabled="disabled||!state.ack" data-testid="candidate-confirm" @tap="flow.confirm">确认带入表单</button>
   </template>
   <button data-testid="candidate-cancel" class="secondary" @tap="cancel">取消本次候选</button>
  </template>
  <text v-if="state.message" class="hint" role="status">{{state.message}}</text>
 </view>
</template>
<style scoped>
.candidates{display:flex;flex-direction:column;gap:16rpx;padding:24rpx;margin:24rpx 0;border:1rpx solid #e0ded6;border-radius:16rpx;background:#fcfbf8}.heading{font-size:28rpx;font-weight:600;color:#1a1d18}.hint{font-size:24rpx;line-height:38rpx;color:#454a41;white-space:pre-wrap;word-break:break-all}.row{display:flex;flex-direction:column;gap:12rpx;font-size:26rpx;color:#1a1d18}.field{border:1rpx solid #d6d3c9;border-radius:12rpx;padding:16rpx;font-size:26rpx;background:#fcfbf8}.multiline{width:100%;min-height:160rpx;box-sizing:border-box}button{margin:0;padding:0 24rpx;line-height:76rpx;font-size:26rpx;background:#0b6b43;color:#fcfbf8;border-radius:12rpx}button[disabled]{color:#63685e;background:#e0ded6}button::after{border:0}.secondary{background:#fcfbf8;color:#074d30}
</style>
