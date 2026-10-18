<script setup>
import { roles } from '../../roles'

function openRole(path) {
  uni.navigateTo({ url: `/${path}` })
}
</script>

<template>
  <view class="page">
    <view class="ux-hero">
      <text class="eyebrow">单测试号 · 三角色预览</text>
      <text class="title">汽车健康管家</text>
      <text class="description">选择要查看的工作入口。业务权限仍由服务端核验。</text>
      <view class="dial">
        <view class="dial-lights">
          <view class="light ok"></view>
          <view class="light warn"></view>
          <view class="line"></view>
        </view>
        <text class="dial-note">业务运行状态以服务端为准</text>
      </view>
    </view>

    <view class="role-list">
      <view v-for="(role, index) in roles" :key="role.key" class="role-card" :class="`ux-in ux-r${index + 1}`" @tap="openRole(role.path)">
        <view class="role-copy">
          <text class="role-tag">{{ role.tag }}</text>
          <text class="role-title">{{ role.title }}</text>
          <text class="role-description">{{ role.description }}</text>
        </view>
        <text class="arrow">›</text>
      </view>
    </view>

    <button class="operator-entry" @tap="openRole('pages/operator/login')">运营审核 · 独立账号登录</button>
    <view class="notice">
      <text class="notice-title">当前为开发预览版本</text>
      <text class="notice-text">车辆档案、订单履约和技师报工等功能已接入；正式身份、支付与部分外部服务仍待验收。选择入口后需使用对应账号登录。</text>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; padding: 52rpx 32rpx 72rpx; box-sizing: border-box; background: #f3f1eb; }
.eyebrow { position: relative; color: #074d30; font-size: 23rpx; font-weight: 600; letter-spacing: 4rpx; }
.title { position: relative; display: block; margin-top: 16rpx; color: #1a1d18; font-size: 60rpx; line-height: 1.15; font-weight: 700; letter-spacing: 1rpx; }
.description { position: relative; display: block; margin-top: 20rpx; max-width: 30em; color: #63685e; font-size: 27rpx; line-height: 1.7; }
/* 仪表盘状态灯母题：入口页的记忆点，同时如实提示“状态以服务端为准”。 */
.dial { display: flex; align-items: center; gap: 18rpx; margin-top: 34rpx; }
.dial-lights { display: flex; align-items: center; gap: 12rpx; padding: 10rpx 18rpx; border: 1rpx solid rgba(26, 29, 24, 0.14); border-radius: 999rpx; background: #fcfbf8; }
.light { width: 14rpx; height: 14rpx; border-radius: 50%; }
.light.ok { background: #0b6b43; }
.light.warn { background: #c25a00; }
.line { width: 64rpx; height: 1rpx; background: rgba(26, 29, 24, 0.3); }
.dial-note { color: #8a8f84; font-size: 23rpx; }
.role-list { position: relative; display: flex; flex-direction: column; gap: 20rpx; margin-top: 36rpx; }
.role-card { position: relative; display: flex; align-items: center; justify-content: space-between; gap: 20rpx; min-height: 176rpx; padding: 30rpx 30rpx; border: 1rpx solid rgba(26, 29, 24, 0.14); border-left: 6rpx solid #0b6b43; border-radius: 20rpx; background: #fcfbf8; box-shadow: 0 2rpx 10rpx rgba(26, 29, 24, 0.07); box-sizing: border-box; transition: transform 160ms cubic-bezier(0.16, 1, 0.3, 1), box-shadow 160ms ease-out; }
.role-card:active { transform: translateY(3rpx); box-shadow: 0 10rpx 34rpx rgba(26, 29, 24, 0.12); }
.role-copy { display: flex; flex: 1; flex-direction: column; }
.role-tag { color: #074d30; font-size: 22rpx; font-weight: 600; letter-spacing: 2rpx; }
.role-title { margin-top: 8rpx; font-size: 36rpx; font-weight: 700; letter-spacing: 1rpx; color: #1a1d18; }
.role-description { margin-top: 10rpx; color: #63685e; font-size: 24rpx; line-height: 1.65; }
.arrow { display: flex; align-items: center; justify-content: center; width: 64rpx; height: 64rpx; border: 1rpx solid rgba(26, 29, 24, 0.3); border-radius: 50%; color: #074d30; font-size: 44rpx; font-weight: 300; box-sizing: border-box; }
.operator-entry { margin: 36rpx 0 0; min-height: 84rpx; border-radius: 12rpx; border: 1rpx solid rgba(26, 29, 24, 0.3); background: #fcfbf8; color: #1a1d18; font-size: 27rpx; font-weight: 600; }
.operator-entry::after { border: 0; }
.operator-entry:active { transform: translateY(2rpx); }
.notice { display: flex; flex-direction: column; margin-top: 40rpx; padding: 26rpx 30rpx; border: 1rpx solid rgba(26, 29, 24, 0.14); border-left: 6rpx solid #c25a00; border-radius: 4rpx 16rpx 16rpx 4rpx; background: #f7ecdf; }
.notice-title { color: #9a4700; font-size: 26rpx; font-weight: 650; }
.notice-text { margin-top: 10rpx; color: #454a41; font-size: 24rpx; line-height: 1.7; }
</style>
