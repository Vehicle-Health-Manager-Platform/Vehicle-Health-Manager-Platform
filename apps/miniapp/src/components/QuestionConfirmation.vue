<script setup>
defineProps({prompt:{type:Object,default:null}})
defineEmits(['answer'])
</script>
<template><view v-if="prompt" class="question-mask"><scroll-view scroll-y class="question-sheet"><view class="reservation-panel question-confirm"><text class="reservation-heading">{{prompt.title}}</text><text v-for="(line,i) in prompt.lines" :key="i" class="question-text">{{line}}</text><text>确认后，原请求会保存在当前设备用于断线恢复，可能包含本次私有文字；共享设备使用后请核对并清理。</text><button @tap="$emit('answer',true)">确认本次操作</button><button @tap="$emit('answer',false)">取消</button></view></scroll-view></view></template>
<style scoped>.question-mask{position:fixed;top:0;right:0;bottom:0;left:0;z-index:1000;background:rgba(0,0,0,.35);display:flex;flex-direction:column;justify-content:flex-end}.question-sheet{height:75vh;background:#fcfbf8}.question-text{white-space:pre-wrap;overflow-wrap:anywhere}.question-confirm{border:2rpx solid #0b6b43;margin:24rpx}</style>
