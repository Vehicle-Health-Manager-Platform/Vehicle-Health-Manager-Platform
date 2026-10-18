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
/* 纯展示映射：状态徽章的语义色，与业务状态机无关。 */
const FILTERS=[['','全部订单'],['PENDING_PAYMENT','待支付'],['PAID','已支付'],['CLOSED','已关闭']]
function tone(status){return status==='PENDING_PAYMENT'?'warn':status==='CLOSED'?'muted':'ok'}
</script>
<template><view class="reservation-page"><text class="reservation-title ux-in">我的订单</text><button v-if="!ownerSession.accessToken" class="ux-in" @tap="login">前往车主登录</button><template v-else>
  <view class="filters ux-in">
    <button v-for="[value,label] in FILTERS" :key="value" class="chip" :class="{selected:filter===value}" @tap="change(value)">{{label}}</button>
  </view>
  <text v-if="state.busy" class="ux-loading">正在加载订单…</text><view v-if="state.message" class="reservation-panel"><text>{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">重新登录</button><button v-else @tap="flow.retry">重试本人订单</button></view>
  <view v-if="state.loaded&&!state.items.length&&!state.busy" class="reservation-panel">暂无本人订单。</view>
  <view v-for="(row,index) in state.items" :key="row.order_id" class="reservation-panel order-card" :class="`ux-in ux-r${Math.min(index,4)+1}`" :data-testid="`order-${row.order_id}`"><text class="reservation-heading">{{row.project_snapshot?.project_name || '历史订单'}}</text><text class="order-line">{{row.merchant_snapshot?.merchant_name || '历史商家信息未提供'}}</text><text class="reservation-price order-price">¥{{row.amount_due}}</text><text class="status" :class="tone(row.status)">{{stateLabel(row.status)}}</text><text v-if="row.payment_summary?.test_mode" class="flag">测试支付，未真实扣款</text><text v-if="row.payment_summary?.requires_review" class="flag risk">支付异常待核对</text><text class="order-line">{{displayTime(row.appointment_snapshot?.starts_at)}}</text><button class="reservation-primary" @tap="open(row)">查看订单详情</button></view>
  <button v-if="state.items.length<state.total" :disabled="state.busy" @tap="flow.load(true)">加载更多订单</button>
</template></view></template>
<style scoped>
.filters{display:flex;flex-wrap:wrap;gap:14rpx;margin:24rpx 0 8rpx}
.chip{margin:0;padding:0 28rpx;line-height:64rpx;min-height:0;font-size:25rpx;font-weight:600;color:#454a41;border:1rpx solid rgba(26,29,24,.3);background:#fcfbf8;border-radius:999rpx;box-sizing:border-box}
.chip.selected{border-color:#0b6b43;background:#0b6b43;color:#fcfbf8}
.order-card{animation-delay:0ms}
.order-price{font-size:40rpx;letter-spacing:-1rpx}
.order-line{color:#63685e;font-size:25rpx;line-height:1.6}
/* 状态徽章：色点 + 文字，不只靠颜色传递含义。 */
.status{align-self:flex-start;padding:6rpx 18rpx;border-radius:999rpx;font-size:23rpx;font-weight:600}
.status::before{content:'';display:inline-block;width:12rpx;height:12rpx;margin-right:10rpx;border-radius:50%;background:currentColor;vertical-align:middle}
.status.ok{color:#074d30;background:#e9f2ec}
.status.warn{color:#9a4700;background:#f7ecdf}
.status.muted{color:#63685e;background:#efede6}
.flag{align-self:flex-start;padding:6rpx 16rpx;border-radius:8rpx;font-size:22rpx;background:#efede6;color:#63685e}
.flag.risk{background:#f8ebea;color:#b3261e}
.ux-loading{display:block;margin-top:20rpx;color:#8a8f84;font-size:25rpx}
</style>
