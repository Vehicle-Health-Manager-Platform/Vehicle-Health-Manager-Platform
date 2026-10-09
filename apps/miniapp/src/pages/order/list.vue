<script setup>
import {reactive,ref,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {initialQuoteListState,createQuoteListFlow} from '../../services/quote-flow.js'
import {reservationsApi as api,displayTime} from '../../services/reservations.js'
import {stateLabel} from '../../services/order-status.js'
const state=reactive(initialQuoteListState()),filter=ref('');let visible=false
const token=()=>ownerSession.accessToken,flow=createQuoteListFlow({state,token,fetchPage:(t,n)=>api.list(t,n,filter.value)})
function change(value){filter.value=value;flow.reset();flow.load()}
function login(){clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
function open(row){uni.navigateTo({url:`/pages/order/detail?id=${row.order_id}`})}
watch(token,()=>{flow.reset();if(visible&&token())flow.load()},{flush:'sync'});onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;flow.suspend()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">我的订单</text><button v-if="!ownerSession.accessToken" @tap="login">前往车主登录</button><template v-else>
  <button @tap="change('')">全部订单</button><button @tap="change('PENDING_PAYMENT')">待支付</button><button @tap="change('PAID')">已支付</button><button @tap="change('CLOSED')">已关闭</button>
  <text v-if="state.busy">正在加载订单…</text><view v-if="state.message" class="reservation-panel"><text>{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">重新登录</button><button v-else @tap="flow.retry">重试本人订单</button></view>
  <view v-if="state.loaded&&!state.items.length&&!state.busy" class="reservation-panel">暂无本人订单。</view>
  <view v-for="row in state.items" :key="row.order_id" class="reservation-panel" :data-testid="`order-${row.order_id}`"><text class="reservation-heading">{{row.project_snapshot?.project_name || '历史订单'}}</text><text>{{row.merchant_snapshot?.merchant_name || '历史商家信息未提供'}}</text><text class="reservation-price">¥{{row.amount_due}}</text><text>{{stateLabel(row.status)}}</text><text v-if="row.payment_summary?.test_mode">测试支付，未真实扣款</text><text v-if="row.payment_summary?.requires_review">支付异常待核对</text><text>{{displayTime(row.appointment_snapshot?.starts_at)}}</text><button @tap="open(row)">查看订单详情</button></view>
  <button v-if="state.items.length<state.total" :disabled="state.busy" @tap="flow.load(true)">加载更多订单</button>
</template></view></template>
