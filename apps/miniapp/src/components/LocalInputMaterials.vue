<script setup>
import {computed,reactive,watch,onBeforeUnmount} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {createLocalMaterialsFlow,initialLocalMaterialsState} from '../services/local-input-materials.js'
import {createMaterialUploadFlow,materialScope} from '../services/input-material-upload.js'
import {imageRequestKey} from '../services/private-images.js'
import DocumentExtraction from './DocumentExtraction.vue'
import DocumentPendingRequests from './DocumentPendingRequests.vue'
import AsrTranscription from './AsrTranscription.vue'
import AsrPendingRequests from './AsrPendingRequests.vue'
const props=defineProps({actor:{type:String,required:true},scope:{type:String,required:true},targetValue:{type:String,default:''},disabled:Boolean})
const emit=defineEmits(['change','picker-change','apply-text','reauth'])
const state=reactive(initialLocalMaterialsState())
const flow=createLocalMaterialsFlow({state,actor:()=>props.actor,scope:()=>props.scope})
const uploadState=reactive({busyId:'',message:'',results:{}})
const uploadFlow=createMaterialUploadFlow({state:uploadState,items:()=>state.items,actor:()=>props.actor,scope:()=>props.scope,keyFactory:imageRequestKey})
const busy=computed(()=>Boolean(uploadState.busyId)||['requesting','recording','stopping','selecting','reading'].includes(state.phase))
const full=computed(()=>state.items.length>=3)
const sizeLabel=bytes=>bytes>=1024*1024?(bytes/1024/1024).toFixed(1)+' MiB':Math.ceil(bytes/1024)+' KiB'
watch(()=>[props.actor,props.scope],flow.reset,{flush:'sync'})
watch(()=>[props.actor,props.scope],uploadFlow.reset,{flush:'sync'})
watch(()=>state.items.length,count=>emit('change',{count}),{flush:'sync'})
watch(()=>state.phase,phase=>emit('picker-change',phase==='selecting'||phase==='reading'),{flush:'sync'})
onShow(flow.resume);onHide(flow.suspend);onUnload(flow.dispose);onBeforeUnmount(flow.dispose)
onHide(()=>{if(!['selecting','reading'].includes(state.phase))uploadFlow.reset()});onUnload(uploadFlow.reset);onBeforeUnmount(uploadFlow.reset)
function remove(id){delete uploadState.results[id];flow.remove(id)}
</script>

<template>
  <view class="materials" data-testid="local-input-materials">
    <text class="heading">本机辅助材料（{{state.items.length}}/3）</text>
    <text class="hint">可先试听或查看材料，再手动填写。音频、JPEG/PNG图片和PDF需单独点击上传；材料不会随文字提问或档案保存发送。已上传材料可另行同意识别，候选文字须人工核对后带入。</text>
    <AsrPendingRequests :actor="actor" :scope="scope" :disabled="disabled" />
    <DocumentPendingRequests :actor="actor" :scope="scope" :disabled="disabled" />
    <view class="actions">
      <button v-if="!['requesting','recording','stopping'].includes(state.phase)" :disabled="!actor||busy||full" data-testid="local-record-start" @tap="flow.startRecording">录制语音</button>
      <button v-if="state.phase==='recording'" data-testid="local-record-stop" @tap="flow.stopRecording">停止录音（{{state.seconds}}秒）</button>
      <button v-if="['requesting','recording','stopping'].includes(state.phase)" data-testid="local-record-cancel" @tap="flow.cancel">取消本段录音</button>
      <button v-if="['selecting','reading'].includes(state.phase)" @tap="flow.cancel">取消本次材料读取</button>
      <button :disabled="!actor||busy||full" data-testid="local-select-image" @tap="flow.select('image')">选择图片</button>
      <button :disabled="!actor||busy||full" data-testid="local-select-pdf" @tap="flow.select('pdf')">选择PDF</button>
    </view>
    <text v-if="state.phase==='requesting'" class="status" role="status">正在申请录音，权限确认后开始…</text>
    <text v-if="state.phase==='stopping'" class="status" role="status">正在结束录音…</text>
    <text v-if="state.phase==='reading'" class="status" role="status">正在读取本机材料…</text>
    <view v-for="item in state.items" :key="item.id" class="material" :data-testid="'local-material-'+item.kind">
      <text>{{item.label}} · {{sizeLabel(item.size)}}<text v-if="item.kind==='audio'"> · {{Math.ceil(item.durationMs/1000)}}秒</text></text>
      <view class="actions">
        <button v-if="item.kind==='audio'&&state.playingId!==item.id" :disabled="busy" @tap="flow.play(item.id)">试听</button>
        <button v-else-if="item.kind==='audio'" @tap="flow.stopPlayback">停止试听</button>
        <button v-else :disabled="busy" @tap="flow.preview(item.id)">预览</button>
        <button v-if="!uploadState.results[item.id]" :disabled="busy||!materialScope(scope)" :data-testid="'upload-material-'+item.kind" @tap="uploadFlow.upload(item.id)">{{uploadState.busyId===item.id?'上传检查中…':'上传本份材料'}}</button>
        <text v-if="uploadState.results[item.id]" class="hint">已上传 · 扫描通过 · {{item.kind==='audio'?'转写需单独操作':'提取需单独操作'}}</text>
        <button :disabled="busy" @tap="remove(item.id)">移除本机引用</button>
      </view>
      <AsrTranscription v-if="item.kind==='audio'&&uploadState.results[item.id]" :actor="actor" :scope="scope" :material="uploadState.results[item.id]" :value="targetValue" :disabled="disabled||busy" @apply="emit('apply-text',$event)" @reauth="emit('reauth')" />
      <DocumentExtraction v-if="item.kind!=='audio'&&uploadState.results[item.id]" :actor="actor" :scope="scope" :material="uploadState.results[item.id]" :value="targetValue" :disabled="disabled||busy" @apply="emit('apply-text',$event)" @reauth="emit('reauth')" />
    </view>
    <text v-if="state.message" class="status" role="status">{{state.message}}</text>
    <text v-if="uploadState.message" class="status" role="status">{{uploadState.message}}</text>
    <text class="hint">上传仅供本人在当前车辆与用途下使用。移除本机引用不会删除已上传材料；尚未配置服务时仍可本机查看和手动填写。</text>
    <button v-if="state.kind==='permission'" @tap="flow.authorize">去开启权限</button>
    <text class="hint">语音1–60秒、最多3MiB；图片上传最多4MiB（4096边长、600万像素），PDF最多10MiB；提取最多4页。离开页面后清除本机材料引用，原文件由微信管理。</text>
  </view>
</template>

<style scoped>
.materials{display:flex;flex-direction:column;gap:16rpx;padding:24rpx;margin:24rpx 0;border:1rpx solid #e0ded6;border-radius:16rpx;background:#fcfbf8}
.heading{font-size:28rpx;font-weight:500;color:#1a1d18}.hint{font-size:24rpx;line-height:36rpx;color:#63685e}.actions{display:flex;flex-wrap:wrap;gap:16rpx}.actions button,button{margin:0;padding:0 24rpx;min-height:72rpx;line-height:72rpx;font-size:26rpx;border-radius:16rpx;background:#fcfbf8;color:#0b6b43}.actions button[disabled]{color:#63685e;background:#efede6}button::after{border:0}.material{display:flex;flex-direction:column;gap:16rpx;padding-top:16rpx;border-top:1rpx solid #e0ded6;font-size:26rpx;line-height:40rpx;color:#454a41;word-break:break-all}.status{font-size:24rpx;line-height:36rpx;color:#c25a00}
</style>
