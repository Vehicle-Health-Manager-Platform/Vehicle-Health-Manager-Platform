<script setup>
import OwnerTabShell from '../../components/OwnerTabShell.vue'

function openAccount() {
  uni.navigateTo({ url: '/pages/owner/index' })
}
</script>

<template>
  <OwnerTabShell label="我的" title="我的汽车生活" description="管理订单、福利、积分与个人设置。" next-action="订单、福利和车友圈入口将在对应业务接口接入后开放。">
    <button class="account-button" @tap="uni.navigateTo({url:'/pages/order/list'})">我的订单</button>
    <button class="account-button" @tap="openAccount">账号与退出登录</button>
  </OwnerTabShell>
</template>

<style scoped>
.account-button { margin: 28rpx 0 0; min-height: 88rpx; border-radius: 16rpx; color: #4e5969; background: #f2f3f5; font-size: 26rpx; }
</style>
