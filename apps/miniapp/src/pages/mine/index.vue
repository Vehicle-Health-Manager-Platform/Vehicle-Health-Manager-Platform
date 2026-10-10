<script setup>
import OwnerTabShell from '../../components/OwnerTabShell.vue'

function openAccount() {
  uni.navigateTo({ url: '/pages/owner/index' })
}
</script>

<template>
  <OwnerTabShell label="我的" title="我的汽车生活" description="查看本人预约订单，管理登录账号。" next-action="福利、积分和车友圈入口将在对应业务接口接入后开放。" :business-ready="true">
    <template #content>
    <button class="account-button" @tap="uni.navigateTo({url:'/pages/order/list'})">我的订单</button>
    <button class="account-button" @tap="uni.navigateTo({url:'/pages/owner/onboarding'})">商家入驻申请</button>
    <button class="account-button" @tap="openAccount">账号与退出登录</button>
    </template>
  </OwnerTabShell>
</template>

<style scoped>
.account-button { margin: 28rpx 0 0; min-height: 88rpx; border-radius: 16rpx; color: #4e5969; background: #f2f3f5; font-size: 26rpx; }
</style>
