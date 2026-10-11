<script setup>
import {reactive,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {merchantSession} from '../../services/merchant-session.js'
import {commissionsApi,initialCommissionListState,createCommissionListFlow,commissionKindLabel,commissionReasonLabel} from '../../services/commissions.js'
import {displayTime} from '../../services/reservations.js'
const state=reactive(initialCommissionListState());let visible=false
const token=()=>merchantSession.accessToken
const flow=createCommissionListFlow({state,token,request:(t,p,s)=>commissionsApi.list(t,p,s)})
function login(){uni.navigateTo({url:'/pages/merchant/index'})}
function order(id){uni.navigateTo({url:`/pages/merchant/order-detail?id=${id}`})}
watch(token,()=>{flow.reset();if(visible&&token())flow.load()},{flush:'sync'})
onShow(()=>{visible=true;flow.reset();if(token())flow.load()})
onHide(()=>{visible=false;flow.reset()})
onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">本店佣金账单</text><text>只读应计台账，资金结算另行核对。</text><button v-if="!token()" @tap="login">登录本店店长账号</button><text v-if="state.busy">正在读取账单…</text><view v-if="state.message" role="status"><text>{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">切换店长账号</button><button v-else :disabled="state.busy" @tap="flow.load()">刷新重试</button></view>
<text v-if="state.message && state.loaded">保留上次读取的账单，请重试核对最新金额。</text>
<view v-if="state.loaded"><view class="reservation-panel" data-testid="commission-policy"><text class="reservation-heading">当前政策</text><view v-if="state.policy.state==='CONFIGURED'"><text>平台订单按3%，前三个自然月免佣</text><text>入驻起点 {{displayTime(state.policy.effective_from)}}</text><text>免佣截至 {{displayTime(state.policy.waived_until)}}</text><text>按下单时间冻结，核销完成才应计。</text></view><text v-else>缺少可信政策；相关订单费用待核对，不作为免佣或已收费凭据。</text></view>
<view v-for="bucket in ['real','test']" :key="bucket" class="reservation-panel"><text class="reservation-heading">{{bucket==='test'?'测试应计（未真实收费）':'真实渠道应计'}}</text><text>应计 ¥{{state.summary[bucket].accrued}}</text><text>冲销 ¥{{state.summary[bucket].reversed}}</text><text>净应计 ¥{{state.summary[bucket].net}}</text><text>待核对订单 {{state.summary[bucket].pending_count}}</text></view><text>汇总包括本店全部台账，测试金额单独统计。记录更新可刷新查看。</text>
<text v-if="!state.items.length">本店尚无应计或退款台账。未完成订单不产生佣金。</text><view v-for="item in state.items" :key="item.entry_id" class="reservation-panel" data-testid="commission-entry"><text class="reservation-heading">订单 {{item.order_id}} · {{commissionKindLabel(item.kind)}}</text><text>金额 {{item.amount===null?'待核对':'¥'+item.amount}}</text><text>{{commissionReasonLabel(item.exemption)}}</text><text>原支付基数 ¥{{item.base_amount}}</text><text>下单 {{displayTime(item.booked_at)}}</text><text>记账 {{displayTime(item.occurred_at)}}</text><text v-if="item.test_mode">测试记录，未真实收费或退款。</text><text v-if="item.reference_entry_id">关联原台账 {{item.reference_entry_id}}</text><button @tap="order(item.order_id)">查看本店原订单</button></view><text>已显示 {{state.items.length}} 条 · 共 {{state.total}} 条</text><button v-if="state.page*20<state.total" :disabled="state.busy" @tap="flow.next">加载更多</button></view><button v-if="token()" :disabled="state.busy" @tap="flow.load()">刷新账单</button></view></template>
