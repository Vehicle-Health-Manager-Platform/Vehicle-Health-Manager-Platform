<script setup>
defineProps({preview:{type:Object,default:null}});defineEmits(['close'])
</script>
<template><view v-if="preview" class="workspace-preview"><text>本次图片预览 · 到期可重新打开</text><scroll-view scroll-y class="workspace-preview-scroll"><image :key="preview.url" :src="preview.url" mode="widthFix" style="width:100%" @error="$emit('close')"/></scroll-view><button @tap="$emit('close')">关闭图片</button></view></template>
<style scoped>.workspace-preview{position:fixed;top:0;right:0;bottom:0;left:0;z-index:9999;padding:24rpx;background:#fff;display:flex;flex-direction:column;gap:16rpx}.workspace-preview-scroll{flex:1;min-height:0;height:0}</style>
