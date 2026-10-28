<script setup>
import {reactive,watch,onBeforeUnmount} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {initialAsrState} from '../services/asr-flow.js'
import {createDocumentFlow,appendDocumentBlock,documentMessage} from '../services/document-flow.js'
const props=defineProps({actor:{type:String,required:true},scope:{type:String,required:true},material:{type:Object,required:true},value:{type:String,default:''},disabled:Boolean})
const emit=defineEmits(['apply','reauth'])
const state=reactive(initialAsrState())
const flow=createDocumentFlow({state,actor:()=>props.actor,scope:()=>props.scope,material:()=>props.material,value:()=>props.value,allowed:()=>!props.disabled,apply:text=>emit('apply',text)})
watch(()=>[props.actor,props.scope,props.material.materialId,props.material.contentType,props.material.pages],flow.reset,{flush:'sync'})
watch(()=>props.disabled,value=>{if(value)flow.reset()},{flush:'sync'})
watch(()=>state.consent,value=>{if(!value)flow.cancel()},{flush:'sync'})
onShow(flow.resume);onHide(flow.suspend);onUnload(flow.suspend);onBeforeUnmount(flow.suspend)
</script>
<template>
 <view class="asr" data-testid="document-extraction">
  <text class="heading">图文提取（可选）</text><text class="hint">仅提取这份本人图片/PDF，最多4页。逐段选择后可修改，不自动提问或保存。取消不删除原材料；候选自创建起最多保留10分钟。</text>
  <label class="hint"><switch :checked="state.consent" :disabled="disabled" @change="state.consent=$event.detail.value" />同意将本份材料用于本次图文提取</label>
  <button :disabled="disabled||!state.consent||state.busy||['ready','applied','terminal','empty','cancelled'].includes(state.phase)" :loading="state.busy" @tap="flow.start">{{state.phase==='unknown'?'使用原请求确认创建':state.created?'查询当前任务':'创建图文提取任务'}}</button>
  <button v-if="state.phase!=='idle'&&state.phase!=='applied'" class="secondary" @tap="flow.cancel">取消本次图文提取</button>
  <button v-if="['terminal','empty','cancelled'].includes(state.phase)" class="secondary" @tap="flow.reset">清空任务状态后重新准备</button>
  <template v-if="state.result&&state.phase==='ready'">
   <text class="hint">原生文字可信度未知；OCR为模型原始置信度，不证明真伪或车辆事实。坐标基于提取时的栅格图像。</text>
   <view v-for="(page,pi) in state.result.pages" :key="page.page" class="segment">
    <text>第{{page.page}}页 · {{page.method}}{{page.raster_size?' · '+page.raster_size.join(' × '):''}}</text>
    <view v-for="(block,bi) in page.blocks" :key="bi" class="segment">
     <text>{{block.source.kind==='TEXT_OFFSET'?'文字位置 '+block.source.start+'–'+block.source.end+'（UTF16，末端不含）':'栅格坐标 '+block.source.points.map(p=>p.join(',')).join(' / ')}} · 可信度{{block.confidence===null?'未知':block.confidence}}</text>
     <text>{{block.text}}</text><button :disabled="disabled||state.busy" @tap="appendDocumentBlock(state,flow,pi,bi)">加入候选文字</button>
    </view>
   </view>
   <text class="hint">已选文字 {{state.text.length}}/1000；加入相同段落会再次追加，请核对后编辑。</text>
   <button :disabled="disabled||state.busy" class="secondary" @tap="flow.edit('')">清空候选文字</button>
   <textarea :value="state.text" maxlength="1000" :disabled="disabled||state.busy" class="field" @input="flow.edit($event.detail.value)" />
   <label class="hint"><switch :checked="state.reviewed" :disabled="disabled||state.busy" @change="state.reviewed=$event.detail.value" />我已核对图文提取和本人修改</label>
   <button :disabled="disabled||!state.reviewed||state.busy" @tap="flow.confirm">核对当前授权后带入文字</button>
  </template>
  <text v-if="state.message" class="hint" role="status">{{documentMessage(state.message)}}</text>
  <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" class="secondary" @tap="emit('reauth')">重新登录车主账号</button>
 </view>
</template>
<style scoped>
.asr{display:flex;flex-direction:column;gap:16rpx;padding:20rpx;border:1rpx solid #d6d3c9;border-radius:12rpx}.heading{font-size:26rpx;font-weight:600;color:#1a1d18}.hint,.segment{font-size:24rpx;line-height:38rpx;color:#454a41;word-break:break-all}.segment{display:flex;flex-direction:column}.field{width:100%;box-sizing:border-box;min-height:160rpx;padding:16rpx;border:1rpx solid #d6d3c9;border-radius:12rpx;font-size:26rpx;background:#fcfbf8}button{margin:0;padding:0 20rpx;min-height:76rpx;font-size:25rpx;border-radius:12rpx;background:#0b6b43;color:#fcfbf8}button::after{border:0}button[disabled]{background:#efede6;color:#63685e}.secondary{background:#e9f2ec;color:#074d30}
</style>
