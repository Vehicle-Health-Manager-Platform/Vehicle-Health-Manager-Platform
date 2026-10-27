<script setup>
import {ref,computed,watch,onBeforeUnmount} from 'vue'
import {asrReceipts,recoverAndCancelAsr} from '../services/asr-flow.js'
const props=defineProps({actor:String,scope:String,disabled:Boolean})
const revision=ref(0),consent=ref(false),busy=ref(false),message=ref('')
const unsubscribe=asrReceipts.subscribe(()=>revision.value++)
onBeforeUnmount(unsubscribe)
const rows=computed(()=>{void revision.value;return asrReceipts.list(props.actor,props.scope)})
watch(()=>[props.actor,props.scope],()=>{consent.value=false;message.value=''},{flush:'sync'})
async function recover(row){if(busy.value||props.disabled)return;const actor=props.actor,scope=props.scope;busy.value=true
 try{await recoverAndCancelAsr(row,{actor,scope,consent:consent.value});if(actor===props.actor&&scope===props.scope)message.value='原任务已请求取消，音频没有删除。'}
 catch{if(actor===props.actor&&scope===props.scope)message.value='原请求尚未确认取消；请稍后重试，旧候选最多10分钟到期。'}
 finally{busy.value=false}
}
</script>
<template>
 <view v-if="rows.length" class="pending" data-testid="asr-pending">
  <text>有{{rows.length}}个转写请求尚未确认取消</text><text>未知创建结果保留原键。若原请求未到达，确认时可能先创建再立即请求取消；不会带入旧文字。</text>
  <label v-if="rows.some(row=>!row.jobId)"><switch :checked="consent" :disabled="disabled||busy" @change="consent=$event.detail.value" />重新同意原音频转写，以确认原请求后立即取消</label>
  <button v-for="row in rows" :key="row.key" :disabled="disabled||busy||!row.jobId&&!consent" @tap="recover(row)">{{row.jobId?'重试取消已知任务':'确认原创建并请求取消'}}</button>
  <text v-if="message" role="status">{{message}}</text>
 </view>
</template>
<style scoped>
.pending{display:flex;flex-direction:column;gap:16rpx;padding:20rpx;border:1rpx solid #d6d3c9;border-radius:12rpx;font-size:24rpx;line-height:38rpx;color:#454a41;word-break:break-all}button{margin:0;padding:0 20rpx;min-height:76rpx;font-size:25rpx;border-radius:12rpx;background:#0b6b43;color:#fcfbf8}button::after{border:0}button[disabled]{background:#efede6;color:#63685e}
</style>
