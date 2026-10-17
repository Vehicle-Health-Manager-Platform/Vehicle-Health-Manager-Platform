<script setup>
import {reactive,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession} from '../services/owner-session.js'
import {merchantSession} from '../services/merchant-session.js'
import {initialRewardState,createInvitationRewardFlow,rewardLabels} from '../services/invitation-rewards.js'
import {couponLocalTime} from '../services/coupons.js'
const props=defineProps({staff:{type:Boolean,default:false}})
const state=reactive(initialRewardState()),token=()=>props.staff?merchantSession.accessToken:ownerSession.accessToken
const flow=createInvitationRewardFlow({state,token,mode:props.staff?'staff':'owner'})
let visible=false
watch(token,()=>{flow.reset();if(visible&&token())flow.load(1)},{flush:'sync'})
onShow(()=>{visible=true;if(token())flow.load(1)})
onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view v-if="token()" class="reservation-panel">
 <text class="reservation-title">{{staff?'推广新客奖励记录':'我的邀请奖励记录'}}</text>
 <text>资格确认时保存原政策。待发放不等于已到账，原券有效期不延长。</text>
 <text v-if="staff">员工拉新计入考核，奖励券只发给有效新客。</text>
 <text v-if="state.busy">正在读取奖励…</text>
 <text v-if="state.loaded&&!state.rows.length">暂无奖励记录；来源未知、测试和未确认资格的注册不会发券。</text>
 <view v-for="r in state.rows" :key="r.record_id" class="reservation-panel">
  <text>{{rewardLabels[r.state]}}</text><text>资格 {{couponLocalTime(r.qualified_at)}}</text>
  <text v-if="!r.worker_enabled&&['READY','WAIT_STOCK','WAIT_SOURCE','WAIT_ACCOUNT'].includes(r.state)">奖励后台尚未开放，保留待发放记录。</text>
  <text v-if="r.completed_at">到账 {{couponLocalTime(r.completed_at)}}</text>
  <view v-for="item in r.rewards" :key="item.role"><text>{{item.own?'我的奖励':item.role==='INVITEE'?'新客奖励':'邀请人奖励'}} · {{item.terms.name}}</text><text>有效至 {{couponLocalTime(item.terms.valid_to)}}</text></view>
 </view>
 <button v-if="!staff&&state.rows.some(r=>r.state==='COMPLETE')" @tap="uni.navigateTo({url:'/pages/coupon/mine'})">查看我的券</button>
 <text v-if="state.message" role="status">{{state.message}}</text>
 <button :disabled="state.busy" @tap="flow.load(1)">刷新奖励</button>
 <button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">奖励上一页</button><button v-if="state.page*20<state.total" :disabled="state.busy" @tap="flow.load(state.page+1)">奖励下一页</button>
</view></template>
