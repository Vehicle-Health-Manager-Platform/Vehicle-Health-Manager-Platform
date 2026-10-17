<script setup>
import OwnerTabShell from '../../components/OwnerTabShell.vue'

function openAccount() {
  uni.navigateTo({ url: '/pages/owner/index' })
}
</script>

<template>
  <OwnerTabShell label="我的" title="我的汽车生活" description="查看本人预约订单、券与有效期，管理登录账号。" next-action="查看积分任务与到账明细，管理个人用车资料。" :business-ready="true">
    <template #anonymous-action>
      <button class="account-button" @tap="uni.navigateTo({url:'/pages/welfare/invite-entry'})">有好友邀请码，先填写再登录</button>
    </template>
    <template #content>
    <button class="account-button" @tap="uni.navigateTo({url:'/pages/community/questions'})">我的私有车辆问答</button>
    <button class="account-button" @tap="uni.navigateTo({url:'/pages/order/list'})">我的订单</button>
    <button class="account-button" @tap="uni.navigateTo({url:'/pages/coupon/mine'})">我的券与截止日期</button>
    <button class="account-button" @tap="uni.navigateTo({url:'/pages/coupon/offers'})">领取运营精选券</button>
    <button class="account-button" @tap="uni.navigateTo({url:'/pages/welfare/invite'})">邀请记录与月度进度</button>
    <button class="account-button" @tap="uni.navigateTo({url:'/pages/welfare/invite-entry'})">输入好友邀请码</button>
    <button class="account-button" @tap="uni.navigateTo({url:'/pages/owner/onboarding'})">商家入驻申请</button>
    <button class="account-button" @tap="uni.navigateTo({url:'/pages/points/index'})">我的积分与到账记录</button>
    <button class="account-button" @tap="openAccount">账号与退出登录</button>
    </template>
  </OwnerTabShell>
</template>

<style scoped>
.account-button { margin: 28rpx 0 0; min-height: 88rpx; border-radius: 16rpx; color: #4e5969; background: #f2f3f5; font-size: 26rpx; }
</style>
