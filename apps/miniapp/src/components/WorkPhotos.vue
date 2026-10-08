<script setup>
defineProps({ files: { type: Array, default: () => [] }, kind: String, label: String, disabled: Boolean, limit: { type: Number, default: 9 } })
defineEmits(['choose', 'remove', 'retry', 'preview'])
</script>
<template>
  <view class="photo-group">
    <text class="reservation-heading">{{ label }} · {{ files.length }}/{{ limit }}</text>
    <view v-for="(entry, index) in files" :key="entry.key" class="photo-entry">
      <image v-if="entry.localPath" :src="entry.localPath" class="photo-thumbnail" mode="aspectFit" />
      <text>照片 {{ index + 1 }} · {{ entry.message }}</text>
      <button v-if="entry.fileId" :disabled="disabled" @tap="$emit('preview', entry.fileId)">预览安全图片</button>
      <button v-else-if="entry.candidate" :disabled="disabled" @tap="$emit('retry', entry)">原图重试上传</button>
      <button :disabled="disabled" @tap="$emit('remove', entry)">移除，重新拍摄</button>
    </view>
    <button :disabled="disabled || files.length >= limit" @tap="$emit('choose', kind)">拍摄{{ label }}</button>
  </view>
</template>
<style scoped>
.photo-group,.photo-entry{display:flex;flex-direction:column;gap:12rpx;padding:16rpx 0}.photo-entry{border-bottom:1px solid #e5e6eb}.photo-thumbnail{width:100%;height:220rpx;background:#f2f3f5;border-radius:8rpx}
</style>
