<script setup>
import { roles } from '../../roles'

function openRole(path) {
  uni.navigateTo({ url: `/${path}` })
}
</script>

<template>
  <view class="page">
    <view class="intro">
      <text class="eyebrow">单测试号 · 三角色预览</text>
      <text class="title">汽车健康管家</text>
      <text class="description">选择要查看的工作入口。业务权限仍由服务端核验。</text>
    </view>

    <view class="role-list">
      <view v-for="role in roles" :key="role.key" class="role-card" @tap="openRole(role.path)">
        <view class="role-copy">
          <text class="role-tag">{{ role.tag }}</text>
          <text class="role-title">{{ role.title }}</text>
          <text class="role-description">{{ role.description }}</text>
        </view>
        <text class="arrow">›</text>
      </view>
    </view>

    <view class="notice">
      <text class="notice-title">当前是 S0 工程骨架</text>
      <text class="notice-text">车辆、订单、支付和报工等功能将按阶段计划接入。此入口仅用于开发预览，不授予任何业务身份。</text>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; padding: 52rpx 32rpx 64rpx; box-sizing: border-box; }
.intro { display: flex; flex-direction: column; padding: 24rpx 8rpx 46rpx; }
.eyebrow { color: #008f24; font-size: 24rpx; font-weight: 600; letter-spacing: 2rpx; }
.title { margin-top: 16rpx; color: #1d2129; font-size: 52rpx; font-weight: 700; }
.description { margin-top: 20rpx; color: #4e5969; font-size: 28rpx; line-height: 42rpx; }
.role-list { display: flex; flex-direction: column; gap: 20rpx; }
.role-card { display: flex; align-items: center; justify-content: space-between; min-height: 184rpx; padding: 28rpx 32rpx; border-radius: 24rpx; background: #fff; box-sizing: border-box; }
.role-card:active { background: #f7fff8; }
.role-copy { display: flex; flex: 1; flex-direction: column; }
.role-tag { color: #008f24; font-size: 22rpx; font-weight: 600; }
.role-title { margin-top: 6rpx; font-size: 36rpx; font-weight: 650; }
.role-description { margin-top: 8rpx; color: #86909c; font-size: 24rpx; }
.arrow { margin-left: 20rpx; color: #00b42a; font-size: 56rpx; font-weight: 300; }
.notice { display: flex; flex-direction: column; margin-top: 40rpx; padding: 28rpx 32rpx; border-left: 6rpx solid #00b42a; border-radius: 8rpx 16rpx 16rpx 8rpx; background: #e8f8eb; }
.notice-title { color: #1d2129; font-size: 27rpx; font-weight: 600; }
.notice-text { margin-top: 10rpx; color: #4e5969; font-size: 24rpx; line-height: 38rpx; }
</style>
