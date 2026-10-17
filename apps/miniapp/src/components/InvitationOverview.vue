<script setup>
import {reactive,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession} from '../services/owner-session.js'
import {initialInvitationOverview,createInvitationOverviewFlow} from '../services/invitation-overview.js'
import {rewardLabels} from '../services/invitation-rewards.js'
const state=reactive(initialInvitationOverview()),token=()=>ownerSession.accessToken,flow=createInvitationOverviewFlow({state,token})
let visible=false
watch(token,()=>{flow.reset();if(visible&&token())flow.load()},{flush:'sync'})
onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view v-if="token()&&(state.policy?.state==='CONFIGURED'||state.rewards.length||state.message)" class="reservation-panel"><text class="reservation-heading">邀请进度与奖励</text><template v-if="state.progress"><text>{{state.progress.month}} · 本月有效新客 {{state.progress.qualified_count}} 人 · 第 {{state.progress.tier}} 档 {{state.progress.tier_count}} / {{state.progress.tier_target}}</text><text v-if="!state.progress.verified_identity_enabled">当前新客资格确认尚未开放，测试和来源未知记录不计数。</text></template><template v-if="state.policy?.state==='CONFIGURED'"><text>当前规则：邀请者 {{state.policy.policy.inviter_terms.name}}；新客 {{state.policy.policy.invitee_terms.name}}</text><text>资格确认时保存政策；奖励以实际到账记录为准。</text></template><view v-for="r in state.rewards" :key="r.record_id"><text>{{rewardLabels[r.state]}}</text></view><text v-if="state.message">{{state.message}}</text><button v-if="state.message" :disabled="state.busy" @tap="flow.load">重试邀请提醒</button><button @tap="uni.navigateTo({url:'/pages/welfare/invite'})">查看邀请与海报</button></view></template>
