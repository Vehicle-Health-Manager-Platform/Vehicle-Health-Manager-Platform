<script setup>
import {computed,reactive,watch,onBeforeUnmount} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {createLocalMaterialsFlow,initialLocalMaterialsState} from '../services/local-input-materials.js'
const props=defineProps({actor:{type:String,required:true},scope:{type:String,required:true}})
const emit=defineEmits(['change','picker-change'])
const state=reactive(initialLocalMaterialsState())
const flow=createLocalMaterialsFlow({state,actor:()=>props.actor,scope:()=>props.scope})
const busy=computed(()=>['requesting','recording','stopping','selecting','reading'].includes(state.phase))
const full=computed(()=>state.items.length>=3)
const sizeLabel=bytes=>bytes>=1024*1024?(bytes/1024/1024).toFixed(1)+' MiB':Math.ceil(bytes/1024)+' KiB'
watch(()=>[props.actor,props.scope],flow.reset,{flush:'sync'})
watch(()=>state.items.length,count=>emit('change',{count}),{flush:'sync'})
watch(()=>state.phase,phase=>emit('picker-change',phase==='selecting'||phase==='reading'),{flush:'sync'})
onShow(flow.resume);onHide(flow.suspend);onUnload(flow.dispose);onBeforeUnmount(flow.dispose)
</script>

<template>
  <view class="materials" data-testid="local-input-materials">
    <text class="heading">本机辅助材料（{{state.items.length}}/3）</text>
    <text class="hint">可先录音、试听或查看图片/PDF，再手动填写。材料未上传、未识别，不会随文字提问或档案保存发送。</text>
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
        <button :disabled="busy" @tap="flow.remove(item.id)">移除</button>
      </view>
    </view>
    <text v-if="state.message" class="status" role="status">{{state.message}}</text>
    <button v-if="state.kind==='permission'" @tap="flow.authorize">去开启权限</button>
    <text class="hint">语音1–60秒、最多3MiB；图片/PDF每份最多10MiB。离开页面后清除本机材料引用，原文件由微信管理。</text>
  </view>
</template>

<style scoped>
.materials{display:flex;flex-direction:column;gap:16rpx;padding:24rpx;margin:24rpx 0;border:1rpx solid #e5e6eb;border-radius:16rpx;background:#f7f8fa}
.heading{font-size:28rpx;font-weight:500;color:#1d2129}.hint{font-size:24rpx;line-height:36rpx;color:#86909c}.actions{display:flex;flex-wrap:wrap;gap:16rpx}.actions button,button{margin:0;padding:0 24rpx;min-height:72rpx;line-height:72rpx;font-size:26rpx;border-radius:16rpx;background:#fff;color:#009a24}.actions button[disabled]{color:#86909c;background:#f2f3f5}button::after{border:0}.material{display:flex;flex-direction:column;gap:16rpx;padding-top:16rpx;border-top:1rpx solid #e5e6eb;font-size:26rpx;line-height:40rpx;color:#4e5969;word-break:break-all}.status{font-size:24rpx;line-height:36rpx;color:#ff7d00}
</style>
