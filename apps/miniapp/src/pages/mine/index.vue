<script setup>
import OwnerTabShell from '../../components/OwnerTabShell.vue'

function openAccount() {
  uni.navigateTo({ url: '/pages/owner/index' })
}

const groups = [
  { label: '订单与资产', items: [
    { title: '我的订单', hint: '预约、支付与核销状态', url: '/pages/order/list' },
    { title: '我的券与截止日期', hint: '金额券与服务券', url: '/pages/coupon/mine' },
    { title: '领取运营精选券', hint: '当前可领的公开券', url: '/pages/coupon/offers' },
    { title: '我的积分与到账记录', hint: '任务完成与兑换', url: '/pages/points/index' },
  ] },
  { label: '互动与私域', items: [
    { title: '我的私有车辆问答', hint: '仅本人可见的问答', url: '/pages/community/questions' },
    { title: '邀请记录与月度进度', hint: '好友邀请与奖励', url: '/pages/welfare/invite' },
    { title: '输入好友邀请码', hint: '填写后完成绑定', url: '/pages/welfare/invite-entry' },
  ] },
  { label: '合作与账号', items: [
    { title: '专属优惠', hint: '平台券与加油、充电、洗车、车险服务', url: '/pages/mine/services?kind=benefits' },
    { title: '二手车服务', hint: '车辆档案、估值、卖车与过户', url: '/pages/mine/services?kind=used' },
    { title: '新车选购', hint: '车型配置、目录对比与新车保险', url: '/pages/mine/services?kind=new' },
    { title: '商家入驻申请', hint: '提交门店开通资料', url: '/pages/owner/onboarding' },
  ] },
]

function open(url) {
  uni.navigateTo({ url })
}
</script>

<template>
  <OwnerTabShell label="我的" title="我的汽车生活" description="查看本人预约订单、券与有效期，管理登录账号。" next-action="查看积分任务与到账明细，管理个人用车资料。" :business-ready="true">
    <template #anonymous-action>
      <button class="invite-first" @tap="uni.navigateTo({url:'/pages/welfare/invite-entry'})">有好友邀请码，先填写再登录</button>
    </template>
    <template #content>
    <view v-for="(group, index) in groups" :key="group.label" class="group" :class="`ux-in ux-r${index + 1}`">
      <text class="group-label">{{ group.label }}</text>
      <view class="rows">
        <button v-for="item in group.items" :key="item.url" class="row" @tap="open(item.url)">
          <view class="row-copy"><text class="row-title">{{ item.title }}</text><text class="row-hint">{{ item.hint }}</text></view>
          <text class="chevron">›</text>
        </button>
      </view>
    </view>
    <view class="group ux-in ux-r4">
      <text class="group-label">账号</text>
      <view class="rows">
        <button class="row" @tap="openAccount">
          <view class="row-copy"><text class="row-title">账号与退出登录</text><text class="row-hint">切换或注销当前登录身份</text></view>
          <text class="chevron">›</text>
        </button>
      </view>
    </view>
    </template>
  </OwnerTabShell>
</template>

<style scoped>
/* 分组列表替代九连按钮：信息密度可控，入口有说明文字。 */
.group { margin-top: 32rpx; }
.group-label { display: block; padding: 0 8rpx; color: #8a8f84; font-size: 23rpx; font-weight: 600; letter-spacing: 4rpx; }
.rows { margin-top: 14rpx; border: 1rpx solid rgba(26, 29, 24, 0.14); border-radius: 20rpx; background: #fcfbf8; box-shadow: 0 2rpx 10rpx rgba(26, 29, 24, 0.07); overflow: hidden; }
.row { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; margin: 0; padding: 26rpx 30rpx; min-height: 0; width: 100%; border: 0; border-top: 1rpx solid rgba(26, 29, 24, 0.14); border-radius: 0; background: transparent; color: inherit; font-size: inherit; font-weight: inherit; line-height: normal; text-align: left; }
.row:first-child { border-top: 0; }
.row::after { border: 0; }
.row:active { background: #efede6; }
.row-copy { display: flex; flex-direction: column; }
.row-title { color: #1a1d18; font-size: 29rpx; font-weight: 650; }
.row-hint { margin-top: 6rpx; color: #8a8f84; font-size: 23rpx; }
.chevron { color: #8a8f84; font-size: 40rpx; font-weight: 300; flex-shrink: 0; }
.invite-first { margin: 28rpx 0 0; min-height: 84rpx; border-radius: 12rpx; border: 1rpx solid rgba(26, 29, 24, 0.3); background: #fcfbf8; color: #1a1d18; font-size: 27rpx; font-weight: 600; }
.invite-first::after { border: 0; }
</style>
