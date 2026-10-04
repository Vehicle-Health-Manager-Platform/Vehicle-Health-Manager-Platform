<script setup>
import { computed } from 'vue'
import { onHide, onUnload } from '@dcloudio/uni-app'
import OwnerTabShell from '../../components/OwnerTabShell.vue'
import VehicleList from '../../components/VehicleList.vue'
import { useImageFlow } from '../../services/image-flow.js'
import { clearOwnerSession } from '../../services/owner-session.js'

const { file, uploaded, busy, operation, message, failureKind, retryable, choose, upload, preview, retry, suspend, dispose } = useImageFlow()
onHide(suspend)
onUnload(dispose)
const requiresLogin = computed(() => ['unauthorized', 'forbidden'].includes(failureKind.value))
const size = computed(() => file.value ? `${(file.value.size / 1024 / 1024).toFixed(2)} MiB` : '')
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
</script>

<template>
  <OwnerTabShell label="档案" title="留下每次养护记录" description="管理本人车辆，为养护记录准备图片。" next-action="档案录入将在随后开放。" business-ready>
    <template #content>
      <VehicleList />
      <view class="images" data-testid="archive-images">
        <text class="heading">先准备养护图片</text>
        <text class="copy">上传保养或维修图片，可查看本人图片。车辆档案录入尚未开放。</text>
        <view v-if="file" class="selected">
          <image class="thumbnail" :src="file.path" mode="aspectFill" />
          <view class="details">
            <text class="file-title">{{ uploaded ? '图片已上传' : '待上传图片' }}</text>
            <text class="size">{{ size }} · {{ uploaded ? '可打开短时预览' : '仅保留在本次页面中' }}</text>
          </view>
        </view>
        <text v-else class="empty">还没有选择图片</text>
        <view class="buttons">
          <button class="secondary" :disabled="busy || requiresLogin" @tap="choose">{{ file ? '重新选择图片' : '选择图片' }}</button>
          <button v-if="file && !uploaded" class="primary" :disabled="busy || requiresLogin || ['rejected', 'invalid', 'too-large'].includes(failureKind)" :loading="operation === 'upload'" @tap="upload">上传图片</button>
          <button v-if="uploaded" class="primary" :disabled="busy || requiresLogin" :loading="operation === 'preview'" @tap="preview">预览已上传图片</button>
        </view>
        <text v-if="busy" class="status" role="status">{{ operation === 'upload' ? '正在上传并检查图片，请稍候…' : operation === 'preview' ? '正在获取图片预览…' : '正在选择图片…' }}</text>
        <text v-else-if="message" class="status" :class="{ error: failureKind }" role="status">{{ message }}</text>
        <button v-if="retryable" class="retry" :disabled="busy" @tap="retry">{{ uploaded ? '重试图片预览' : '使用原图片重试' }}</button>
        <button v-if="requiresLogin" class="retry" @tap="login">重新登录车主账号</button>
        <text class="note">JPG、PNG 或 WebP，每次一张，最大 10 MiB。上传成功后仍需等待档案录入开放；重新登录或关闭小程序会清除本次页面的图片信息。</text>
      </view>
    </template>
  </OwnerTabShell>
</template>

<style scoped>
.images { margin-top: 28rpx; padding: 32rpx; background: #fff; border-radius: 24rpx; display: flex; flex-direction: column; }
.heading { color: #1d2129; font-size: 32rpx; font-weight: 650; }
.copy { margin-top: 14rpx; color: #4e5969; font-size: 26rpx; line-height: 40rpx; }
.selected { display: flex; align-items: center; gap: 22rpx; padding: 24rpx 0; margin-top: 16rpx; border-top: 1rpx solid #e5e6eb; }
.thumbnail { width: 120rpx; height: 120rpx; flex-shrink: 0; border-radius: 12rpx; background: #f2f3f5; }
.details { display: flex; flex-direction: column; gap: 12rpx; }
.file-title { color: #1d2129; font-size: 28rpx; }
.size, .empty { color: #86909c; font-size: 24rpx; }
.empty { padding: 30rpx 0 12rpx; }
.buttons { display: flex; flex-wrap: wrap; gap: 16rpx; margin-top: 20rpx; }
button { margin: 0; padding: 0 24rpx; min-height: 80rpx; font-size: 26rpx; border-radius: 14rpx; }
button::after { border: 0; }
.primary { background: #00b42a; color: #fff; }
.secondary { background: #eef8f0; color: #008f24; }
button[disabled] { background: #f2f3f5; color: #86909c; }
.status { margin-top: 24rpx; color: #008f24; font-size: 25rpx; line-height: 38rpx; }
.error { color: #b42318; }
.retry { margin-top: 16rpx; align-self: flex-start; color: #008f24; background: #eef8f0; }
.note { margin-top: 24rpx; color: #86909c; font-size: 23rpx; line-height: 36rpx; }
</style>
