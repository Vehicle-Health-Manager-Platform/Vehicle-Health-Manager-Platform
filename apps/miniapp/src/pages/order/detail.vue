<script setup>
import {reactive,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {imageRequestKey} from '../../services/private-images.js'
import {reservationsApi as api,initialReservationReadState,createReservationReadFlow,initialReservationWriteState,createReservationWriteFlow,displayTime} from '../../services/reservations.js'
import {paymentsApi} from '../../services/payments.js'
import {stateLabel,closeReasonLabel} from '../../services/order-status.js'
const payState=reactive(initialReservationWriteState())
const state=reactive(initialReservationReadState()),write=reactive(initialReservationWriteState());let id=0,visible=false;const token=()=>ownerSession.accessToken
const flow=createReservationReadFlow({state,token,request:t=>api.detail(t,id)}),cancel=createReservationWriteFlow({state:write,token,newKey:imageRequestKey,body:()=>({order_id:id}),request:(t,_b,k)=>api.cancel(t,id,k)})
const pay=createReservationWriteFlow({state:payState,token,newKey:imageRequestKey,body:()=>({order_id:id,channel:'WECHAT'}),request:(t,_b,k)=>paymentsApi.create(t,id,k),onConflict:()=>flow.load()})
function login(){clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
function confirmCancel(){uni.showModal({title:'取消待支付预约',content:'取消后将释放本次预约名额。',success:r=>{if(r.confirm)cancel.save()}})}
watch(token,()=>{flow.reset();cancel.reset();pay.reset();if(visible&&token())flow.load()},{flush:'sync'});watch(()=>write.saved,r=>{if(r&&visible)flow.load()});onLoad(q=>{id=Number(q?.id)||0});onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;flow.suspend();cancel.suspend();pay.suspend()});onUnload(()=>{visible=false;flow.reset();cancel.reset();pay.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">订单详情</text><button v-if="!ownerSession.accessToken" @tap="login">前往车主登录</button><text v-if="state.busy">正在加载订单…</text>
  <view v-if="state.message" class="reservation-panel"><text>{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">重新登录</button><button v-else @tap="flow.load">重试订单详情</button></view>
  <view v-if="state.value" class="reservation-panel" data-testid="order-detail"><text class="reservation-heading">{{state.value.project_snapshot?.project_name || '历史订单'}}</text><text>{{state.value.merchant_snapshot?.merchant_name || '历史商家信息未提供'}}</text><text>{{state.value.merchant_snapshot?.address || '地址未提供'}}</text><text class="reservation-price">应付 ¥{{state.value.amount_due}}</text><text>订单号 {{state.value.order_no}}</text><text>{{displayTime(state.value.appointment_snapshot?.starts_at)}} 至 {{displayTime(state.value.appointment_snapshot?.ends_at)}}</text>
    <template v-if="state.value.status==='PENDING_PAYMENT'"><text>待支付 · 到期 {{displayTime(state.value.expires_at)}}</text><text>正式微信支付尚未配置，当前不可支付。未支付到期将自动关闭。</text><button :disabled="payState.busy" :loading="payState.busy" @tap="pay.save">{{state.value.payment_summary?.status==='FAILED'?'重新发起微信支付':'微信支付'}}</button><text v-if="payState.message" role="status">{{payState.message}}</text><button @tap="flow.load">刷新订单状态</button><button :disabled="write.busy" :loading="write.busy" @tap="confirmCancel">取消待支付预约</button></template>
    <template v-else-if="state.value.status==='PAID'"><text>已支付待接车</text><text data-testid="appointment-code">预约码 {{state.value.appointment_code||'请刷新获取'}}</text><text>到店向商家提供预约码；接车后可查看接车单。</text><button @tap="flow.load">刷新订单状态</button></template><template v-else-if="state.value.status==='CLOSED'"><text>已关闭 · {{closeReasonLabel(state.value.close_reason)||'历史关闭原因未提供'}}</text></template><text v-else data-testid="order-status">{{stateLabel(state.value.status)}}</text><text v-if="state.value.status==='RECEIVED'">请查看接车单并确认，或填写原因提出异议。</text><text v-if="state.value.status==='DISPUTED'">已提出异议，施工已暂停。商家提交处理记录后，请在接车单页复核是否接受；接受后订单恢复并继续派工。</text><button v-if="['RECEIVED','IN_SERVICE','PENDING_VERIFY','COMPLETED','DISPUTED'].includes(state.value.status)" @tap="uni.navigateTo({url:`/pages/check/pickup-detail?id=${state.value.order_id}&role=owner`})">查看本人接车单</button><view v-if="state.value.payment_summary"><text>支付状态 {{state.value.payment_summary.status}}</text><text v-if="state.value.payment_summary.test_mode">测试支付，未真实扣款，不可作为收款凭据</text><text v-if="state.value.payment_summary.requires_review">支付异常待核对，尚未执行退款</text><text v-if="state.value.payment_summary.status==='FAILED'">支付失败，可在订单有效期内重新发起。</text></view><text v-if="write.message" role="status">{{write.message}}</text>
  </view><button @tap="uni.redirectTo({url:'/pages/order/list'})">返回我的订单</button>
</view></template>
<style src="../../styles/reservations.css"></style>
