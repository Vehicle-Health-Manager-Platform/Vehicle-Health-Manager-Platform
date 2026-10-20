<script setup>
import {reactive,watch,computed} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession} from '../services/owner-session.js'
import {initialCouponState,createCouponFlow,couponAvailability,couponLocalTime} from '../services/coupons.js'
const state=reactive(initialCouponState()),token=()=>ownerSession.accessToken,flow=createCouponFlow({state,token,mode:'mine'})
const rows=computed(()=>state.rows.filter(r=>r.source_known&&r.status==='AVAILABLE').slice(0,3))
let visible=false
watch(token,()=>{flow.reset();if(visible&&token())flow.load(1)},{flush:'sync'})
onShow(()=>{visible=true;if(token())flow.load(1)});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view v-if="token()" class="reservation-panel"><text class="reservation-heading">券有效期提醒</text><text>最近领取券的实际截止日期</text><text v-if="state.busy">正在读取…</text><text v-else-if="state.loaded&&!rows.length">最近领取券中暂无未使用且来源明确的券。</text>
 <view v-for="row in rows" :key="row.user_coupon_id" class="expiry"><text>{{ row.terms.name }} · {{ couponAvailability[row.availability] }}</text><text>截止 {{ couponLocalTime(row.terms.valid_to) }}（北京时间）</text></view>
 <text v-if="state.message">{{ state.message }}</text><button v-if="state.message" :disabled="state.busy" @tap="flow.load(1)">重试有效期提醒</button><button @tap="uni.navigateTo({url:'/pages/coupon/mine'})">查看全部券与有效期</button>
</view></template>
<style scoped>.expiry{display:flex;flex-direction:column;gap:10rpx;margin-top:18rpx;font-size:25rpx;color:#454a41}</style>
