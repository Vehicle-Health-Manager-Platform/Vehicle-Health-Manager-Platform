<script setup>
import {reactive,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession} from '../services/owner-session.js'
import {initialEligibilityState,createEligibilityFlow,diagnosisLabels} from '../services/invite-diagnosis.js'
import {couponLocalTime} from '../services/coupons.js'
const token=()=>ownerSession.accessToken,state=reactive(initialEligibilityState()),flow=createEligibilityFlow({state,token})
let visible=false
watch(token,()=>{flow.reset();if(visible&&token())flow.load()},{flush:'sync'})
onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view v-if="token()" class="reservation-panel"><text class="reservation-heading">非实时人工诊断邀请条件</text><text v-if="state.busy">正在核对…</text><template v-if="state.data"><text>{{diagnosisLabels[state.data.state]}}</text><text>{{state.data.month}} · 北京时间 · 当前有效新客 {{state.data.qualified_count}} 人</text><template v-if="state.data.policy"><text>本月需 {{state.data.policy.required_count}} 人 · 规则版本 {{state.data.policy.revision}}</text><text>{{state.data.policy.source_note}}</text><text>规则截止 {{couponLocalTime(state.data.policy.valid_to)}}</text></template><text v-if="state.data.usable_until">当前资格可用至 {{couponLocalTime(state.data.usable_until)}}；停用、跨月或账号状态变化后重新核对。</text><text>受理状态和提交资格在留言页重新核对；满足邀请条件不代表人工在线或已经接单。</text><button @tap="uni.navigateTo({url:'/pages/diagnosis/mine'})">查看人工诊断与本人留言</button></template><text v-if="state.message">{{state.message}}</text><button :disabled="state.busy" @tap="flow.load">刷新邀请条件</button><button @tap="uni.navigateTo({url:'/pages/welfare/invite'})">查看邀请进度与奖励</button></view></template>
