<script setup>
import { computed } from 'vue'
import { ownerSession } from '../services/owner-session'

const props = defineProps({
  label: { type: String, required: true },
  title: { type: String, required: true },
  description: { type: String, required: true },
  nextAction: { type: String, required: true },
  businessReady: { type: Boolean, default: false },
})

const signedIn = computed(() => Boolean(ownerSession.accessToken))

function openLogin() {
  uni.navigateTo({ url: '/pages/owner/index' })
}
</script>

<template>
  <view class="page">
    <view class="hero">
      <text class="eyebrow">车主端 · {{ label }}</text>
      <text class="title">{{ title }}</text>
      <text class="description">{{ description }}</text>
    </view>

    <view v-if="!signedIn" class="panel" role="status">
      <text class="panel-title">请先验证车主身份</text>
      <text class="panel-copy">登录后才能查看与本人关联的车辆和服务信息。</text>
      <button class="action" @tap="openLogin">前往微信登录</button>
    </view>
    <view v-else-if="!businessReady" class="panel" role="status">
      <text class="panel-title">业务内容即将接入</text>
      <text class="panel-copy">{{ nextAction }}</text>
      <text class="panel-note">当前仅完成导航与身份入口；尚无可展示的业务数据。</text>
      <slot />
    </view>
    <slot name="content" v-if="signedIn" />
  </view>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; padding: 52rpx 32rpx 96rpx; background: #f2f3f5; }
.hero { display: flex; flex-direction: column; padding: 20rpx 8rpx 42rpx; }
.eyebrow { color: #008f24; font-size: 23rpx; font-weight: 600; letter-spacing: 2rpx; }
.title { margin-top: 14rpx; color: #1d2129; font-size: 48rpx; font-weight: 700; }
.description { margin-top: 16rpx; color: #4e5969; font-size: 27rpx; line-height: 40rpx; }
.panel { display: flex; flex-direction: column; padding: 36rpx 32rpx; border-radius: 24rpx; background: #fff; }
.panel-title { color: #1d2129; font-size: 32rpx; font-weight: 650; }
.panel-copy { margin-top: 16rpx; color: #4e5969; font-size: 26rpx; line-height: 40rpx; }
.panel-note { margin-top: 28rpx; color: #86909c; font-size: 23rpx; line-height: 36rpx; }
.action { margin: 30rpx 0 0; min-height: 88rpx; border-radius: 16rpx; background: #00b42a; color: #fff; font-size: 28rpx; }
.action::after { border: 0; }
</style>
