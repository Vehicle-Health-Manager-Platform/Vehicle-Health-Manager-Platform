<script setup>
import {ref,watch} from 'vue'
import {onLoad} from '@dcloudio/uni-app'
import OwnerTabShell from '../../components/OwnerTabShell.vue'
import {ownerSession} from '../../services/owner-session.js'
import {partnerService} from '../../services/car-life.js'
const service=ref(null),opened=ref(false),error=ref('')
onLoad(query=>{service.value=partnerService(typeof query?.service==='string'?query.service:'')})
watch(()=>ownerSession.accessToken,()=>{opened.value=false;error.value=''})
function open(){const actor=ownerSession.accessToken;if(!actor||!service.value?.available)return;uni.showModal({title:'前往第三方服务',content:`合作方：${service.value.provider}\n域名：${service.value.host}\n${service.value.message}`,confirmText:'确认前往',success:result=>{if(result.confirm&&ownerSession.accessToken===actor){error.value='';opened.value=true}}})}
</script>
<template>
 <view v-if="opened&&ownerSession.accessToken&&service?.available"><web-view :src="service.url" @error="opened=false;error='合作页面加载失败，请稍后重试或返回平台服务。'" /></view>
 <OwnerTabShell v-else label="合作服务" :title="service?.title||'服务入口无效'" description="请核对服务提供方及开通状态。" next-action="查看说明后选择是否进入合作方页面。" :business-ready="true">
  <template #content><view class="panel"><text class="status">{{service?.available?'合作服务已配置':'暂未开通'}}</text><text class="copy">{{service?.message||'未找到该服务，请从我的页面重新选择。'}}</text><text v-if="service?.available" class="copy">{{service.provider}} · {{service.host}}</text><text v-if="error" class="error" role="alert">{{error}}</text><button v-if="service?.available" @tap="open">查看并确认前往合作方</button><button class="secondary" @tap="uni.switchTab({url:'/pages/mine/index'})">返回我的</button></view></template>
 </OwnerTabShell>
</template>
<style scoped>
.panel{padding:32rpx;border:1rpx solid #d6d3c9;border-radius:20rpx;background:#fcfbf8}.status{display:block;color:#074d30;font-size:30rpx;font-weight:650}.copy{display:block;margin-top:20rpx;color:#454a41;line-height:1.7;font-size:27rpx}button{margin-top:28rpx;background:#0b6b43;color:#fcfbf8;font-size:27rpx;border-radius:14rpx}button::after{border:0}.secondary{background:#e9f2ec;color:#074d30}.error{display:block;color:#b3261e;margin-top:20rpx}
</style>
