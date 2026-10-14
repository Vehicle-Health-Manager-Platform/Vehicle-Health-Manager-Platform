<script setup>
import {reactive,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import CouponTerms from '../../components/CouponTerms.vue'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {initialCouponState,createCouponFlow} from '../../services/coupons.js'
const state=reactive(initialCouponState()),token=()=>ownerSession.accessToken,flow=createCouponFlow({state,token,mode:'mine'})
let visible=false
function login(){flow.reset();clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
watch(token,()=>{flow.reset();if(visible&&token())flow.load(1)},{flush:'sync'})
onShow(()=>{visible=true;if(token())flow.load(1)});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">我的券与有效期</text>
 <view v-if="!token()"><text>请先登录车主账号。</text><button @tap="uni.navigateTo({url:'/pages/owner/index'})">车主登录</button></view>
 <template v-else><button @tap="uni.navigateTo({url:'/pages/coupon/offers'})">查看可领券</button><text v-if="state.busy">正在加载…</text><text v-else-if="state.loaded&&!state.rows.length">还没有领取券。</text>
 <view v-for="row in state.rows" :key="row.user_coupon_id" class="reservation-panel"><CouponTerms :row="row" mine/></view>
 <text v-if="state.message" role="status">{{ state.message }}</text><button v-if="state.kind==='unauthorized'" @tap="login">重新登录车主账号</button><button :disabled="state.busy" @tap="flow.load(1)">刷新我的券</button>
 <button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">上一页</button><button v-if="state.page*20<state.total" :disabled="state.busy" @tap="flow.load(state.page+1)">下一页</button></template>
</view></template>
