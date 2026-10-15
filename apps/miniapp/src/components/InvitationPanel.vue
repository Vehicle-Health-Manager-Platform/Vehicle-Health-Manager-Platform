<script setup>
import {reactive,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../services/owner-session.js'
import {merchantSession,clearMerchantSession} from '../services/merchant-session.js'
import {initialInvitationState,createInvitationFlow,invitationLabels} from '../services/invitations.js'
import {imageRequestKey} from '../services/private-images.js'
import {couponLocalTime} from '../services/coupons.js'
import InvitationRewards from './InvitationRewards.vue'
import InvitationPoster from './InvitationPoster.vue'
const props=defineProps({staff:{type:Boolean,default:false}})
const state=reactive(initialInvitationState()),token=()=>props.staff?merchantSession.accessToken:ownerSession.accessToken
const flow=createInvitationFlow({state,token,mode:props.staff?'staff':'owner',keyFactory:imageRequestKey})
let visible=false
watch(token,()=>{flow.reset();if(visible&&token())flow.load(1)},{flush:'sync'})
onShow(()=>{visible=true;if(token())flow.load(1)})
onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
function login(){flow.reset();if(props.staff)clearMerchantSession();else clearOwnerSession();uni.navigateTo({url:props.staff?'/pages/merchant/index':'/pages/owner/index'})}
function copy(){if(state.code)uni.setClipboardData({data:state.code.value})}
</script>
<template><view class="reservation-page">
 <text class="reservation-title">{{staff?'我的店员推广':'邀请记录与月度进度'}}</text>
 <text>仅首次注册且完成服务端资格确认的新客计数。每月新增 1、2、3、4 人，累计 1、3、6、10 人后重复。</text>
 <view v-if="!token()"><button @tap="login">登录当前账号</button></view>
 <template v-else>
  <text v-if="state.busy">正在读取…</text>
  <view v-if="state.code" class="reservation-panel"><text>我的{{staff?'员工推广码':'邀请码'}}</text><text user-select>{{state.code.value}}</text><button :disabled="state.busy" @tap="copy">复制推广码</button><text>好友可在“我的 → 输入邀请码”进入后首次注册。微信分享暂未开放。</text></view>
  <button v-else-if="state.loaded" :disabled="state.busy" @tap="flow.create">生成我的推广码</button>
  <InvitationPoster v-if="state.code" :code="state.code.value" :staff="staff" />
  <view v-if="state.progress" class="reservation-panel">
   <text>{{state.progress.month}} · 北京时间</text><text>有效新客 {{state.progress.qualified_count}} 人 · 完成 {{state.progress.completed_cycles}} 轮</text>
   <text>当前第 {{state.progress.tier}} 档：{{state.progress.tier_count}} / {{state.progress.tier_target}} 人，下一节点累计 {{state.progress.next_threshold}} 人</text>
   <text>待确认 {{state.progress.pending_total}} · 来源未知 {{state.progress.unknown_total}} · 测试 {{state.progress.test_total}} · 不符合 {{state.progress.rejected_total}}（以上为全部历史记录）</text>
   <text v-if="!state.progress.verified_identity_enabled">新客资格确认暂未开放，来源未知和测试记录不会计入有效人数。</text>
  </view>
  <view v-if="!staff&&state.loaded" class="reservation-panel"><text>我的首次注册来源</text><text v-if="state.registration">{{state.registration.attribution_result==='ATTRIBUTED'?'已保存邀请归因':'未形成邀请归因'}} · {{invitationLabels[state.registration.qualification_status]}}</text><text v-else>历史注册来源未知，不补算新客。</text><button @tap="uni.navigateTo({url:'/pages/welfare/invite-entry'})">输入好友邀请码</button></view>
  <text v-if="state.loaded&&!state.rows.length">暂无邀请记录。</text>
  <view v-for="r in state.rows" :key="r.record_id" class="reservation-panel"><text>{{invitationLabels[r.qualification_status]}}</text><text>注册 {{couponLocalTime(r.registered_at)}}</text><text v-if="r.qualified_at">有效资格 {{couponLocalTime(r.qualified_at)}} · {{r.reward_month}}</text><text v-if="r.qualification_reason==='PHONE_SEEN'">手机号已有资格历史</text><text v-if="r.qualification_reason==='SOURCE_UNAVAILABLE'">确认资格时推广来源已失效</text></view>
  <text v-if="state.message" role="status">{{state.message}}</text><button v-if="state.kind==='unauthorized'" @tap="login">重新登录</button>
  <button :disabled="state.busy" @tap="flow.load(1)">刷新</button><button v-if="!state.code&&state.kind" :disabled="state.busy" @tap="flow.create">重试生成推广码</button>
  <button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">上一页</button><button v-if="state.page*20<state.total" :disabled="state.busy" @tap="flow.load(state.page+1)">下一页</button>
 </template>
 <InvitationRewards :staff="staff" />
</view></template>
