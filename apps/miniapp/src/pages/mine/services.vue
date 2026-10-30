<script setup>
import {ref,computed} from 'vue'
import {onLoad} from '@dcloudio/uni-app'
import OwnerTabShell from '../../components/OwnerTabShell.vue'
import {lifeGroups,partnerService} from '../../services/car-life.js'
const kind=ref(''),group=computed(()=>Object.hasOwn(lifeGroups,kind.value)?lifeGroups[kind.value]:null)
onLoad(query=>{kind.value=typeof query?.kind==='string'?query.kind:''})
function open(item){if(item.partner)uni.navigateTo({url:`/pages/mine/partner?service=${item.partner}`});else if(item.tab)uni.switchTab({url:item.url});else uni.navigateTo({url:item.url})}
</script>
<template>
 <OwnerTabShell label="汽车生活" :title="group?.title||'服务入口无效'" :description="group?.description||'请从我的页面重新选择服务。'" next-action="选择平台功能或查看合作服务开通状态。" :business-ready="true">
  <template #content>
   <view v-if="group" class="cards"><button v-for="item in group.items" :key="item.title" class="card" @tap="open(item)"><text class="name">{{item.title}}</text><text class="hint">{{item.hint||(partnerService(item.partner).available?'由合作方提供，查看后确认进入':'合作服务暂未开通 · 查看说明')}}</text><text class="arrow">查看 ›</text></button></view>
   <view v-else class="notice" role="alert"><text>未找到该服务分类。</text><button @tap="uni.switchTab({url:'/pages/mine/index'})">返回我的</button></view>
  </template>
 </OwnerTabShell>
</template>
<style scoped>
.cards{display:flex;flex-direction:column;gap:22rpx}.card,.notice{margin:0;padding:30rpx;border:1rpx solid #d6d3c9;border-radius:20rpx;background:#fcfbf8;color:#1a1d18;text-align:left}.card::after{border:0}.name,.hint,.arrow{display:block}.name{font-size:31rpx;font-weight:650}.hint{margin-top:12rpx;font-size:25rpx;line-height:1.7;color:#63685e}.arrow{margin-top:22rpx;color:#0b6b43;font-size:26rpx}.card:active{background:#efede6}.notice{font-size:27rpx}
</style>
