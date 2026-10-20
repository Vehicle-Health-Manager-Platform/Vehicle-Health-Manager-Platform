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

    <view v-if="!signedIn" class="panel ux-in" role="status">
      <text class="panel-badge">未登录</text>
      <text class="panel-title">请先验证车主身份</text>
      <text class="panel-copy">登录后才能查看与本人关联的车辆和服务信息。</text>
      <button class="action ux-btn-primary" @tap="openLogin">前往微信登录</button>
      <slot name="anonymous-action" />
    </view>
    <view v-else-if="!businessReady" class="panel ux-in" role="status">
      <text class="panel-badge muted">待接入</text>
      <text class="panel-title">业务内容即将接入</text>
      <text class="panel-copy">{{ nextAction }}</text>
      <text class="panel-note">当前仅完成导航与身份入口；尚无可展示的业务数据。</text>
      <slot />
    </view>
    <slot name="content" v-if="signedIn" />
  </view>
</template>

<style scoped>
.page { min-height: 100vh; box-sizing: border-box; padding: 48rpx 32rpx 112rpx; background: #f3f1eb; }
/* 非对称 hero：右上角低透明主色光斑做氛围层，不用整幅渐变。 */
.hero { position: relative; padding: 20rpx 8rpx 40rpx; overflow: hidden; }
.hero::before { content: ''; position: absolute; left: 300rpx; top: -220rpx; width: 620rpx; height: 620rpx; background: radial-gradient(closest-side, rgba(11, 107, 67, 0.14), rgba(11, 107, 67, 0)); pointer-events: none; }
.eyebrow { position: relative; color: #074d30; font-size: 23rpx; font-weight: 600; letter-spacing: 4rpx; }
.title { position: relative; display: block; margin-top: 14rpx; color: #1a1d18; font-size: 52rpx; line-height: 1.15; font-weight: 700; letter-spacing: 1rpx; }
.description { position: relative; display: block; margin-top: 16rpx; max-width: 32em; color: #63685e; font-size: 27rpx; line-height: 1.7; }
.panel { position: relative; display: flex; flex-direction: column; padding: 34rpx 30rpx; border: 1rpx solid rgba(26, 29, 24, 0.14); border-radius: 20rpx; background: #fcfbf8; box-shadow: 0 2rpx 10rpx rgba(26, 29, 24, 0.07); }
.panel-badge { align-self: flex-start; padding: 8rpx 20rpx; border-radius: 999rpx; background: #e9f2ec; color: #074d30; font-size: 23rpx; font-weight: 600; }
.panel-badge.muted { background: #efede6; color: #63685e; }
.panel-title { margin-top: 20rpx; color: #1a1d18; font-size: 32rpx; font-weight: 700; }
.panel-copy { margin-top: 14rpx; color: #454a41; font-size: 26rpx; line-height: 1.7; }
.panel-note { margin-top: 28rpx; padding-top: 22rpx; border-top: 1rpx solid rgba(26, 29, 24, 0.14); color: #8a8f84; font-size: 23rpx; line-height: 1.65; }
.action { margin: 30rpx 0 0; min-height: 84rpx; border-radius: 12rpx; border: 1rpx solid #0b6b43; background: #0b6b43; color: #fcfbf8; font-size: 27rpx; font-weight: 600; }
.action::after { border: 0; }
.action:active { transform: translateY(2rpx); }
</style>
