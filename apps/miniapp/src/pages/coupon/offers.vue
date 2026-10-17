<script setup>
import {reactive,ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import CouponTerms from '../../components/CouponTerms.vue'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {initialCouponState,createCouponFlow} from '../../services/coupons.js'
import {imageRequestKey} from '../../services/private-images.js'
const shop=ref(null),validRoute=ref(true),state=reactive(initialCouponState()),token=()=>ownerSession.accessToken
const flow=createCouponFlow({state,token,mode:'offers',shop:()=>shop.value,newKey:imageRequestKey,confirm:({row})=>new Promise(resolve=>uni.showModal({title:'确认领取',content:`${row.terms.name}；仅限门店 ${row.merchant_id} 的项目 ${row.terms.project_id}。每人限领一次，请核对有效期。`,success:r=>resolve(r.confirm),fail:()=>resolve(false)}))})
let visible=false
onLoad(q=>{if(q?.merchant_id!==undefined){if(!/^[1-9][0-9]{0,15}$/.test(q.merchant_id)||!Number.isSafeInteger(Number(q.merchant_id)))validRoute.value=false;else shop.value=Number(q.merchant_id)}})
watch(token,()=>{flow.reset();if(visible&&validRoute.value&&token())flow.load(1)},{flush:'sync'})
onShow(()=>{visible=true;if(validRoute.value&&token())flow.load(1)})
onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
function claim(row){flow.write('claim',row,{coupon_id:row.coupon_id,publication_revision:row.publication_revision})}
function login(){flow.reset();clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
</script>
<template><view class="reservation-page">
 <text class="reservation-title">{{ shop?'门店可领券':'运营精选券' }}</text>
 <text v-if="!validRoute">门店参数无效，请返回门店重新进入。</text>
 <view v-else-if="!token()"><text>请先登录车主账号。</text><button @tap="uni.navigateTo({url:'/pages/owner/index'})">车主登录</button></view>
 <template v-else>
  <button @tap="uni.navigateTo({url:'/pages/coupon/mine'})">我的券与截止日期</button>
  <text v-if="state.busy">正在处理…</text><text v-else-if="state.loaded&&!state.rows.length">当前没有可领取券；已领、售罄、未生效或已到期的券不会列出。</text>
  <view v-for="row in state.rows" :key="row.coupon_id" class="reservation-panel"><CouponTerms :row="row"/><button class="reservation-primary" :disabled="state.busy||state.retryWrite" @tap="claim(row)">领取</button></view>
  <text v-if="state.message" role="status">{{ state.message }}</text><button v-if="state.kind==='unauthorized'" @tap="login">重新登录车主账号</button><button v-if="state.retryWrite" :disabled="state.busy" @tap="flow.retry">重试原领取</button>
  <button :disabled="state.busy||state.retryWrite" @tap="flow.load(1)">刷新券列表</button>
  <button v-if="state.page>1" :disabled="state.busy||state.retryWrite" @tap="flow.load(state.page-1)">上一页</button><button v-if="state.page*20<state.total" :disabled="state.busy||state.retryWrite" @tap="flow.load(state.page+1)">下一页</button>
 </template>
</view></template>
