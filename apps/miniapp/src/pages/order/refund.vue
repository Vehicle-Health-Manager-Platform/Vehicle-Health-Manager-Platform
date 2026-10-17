<script setup>
import {reactive,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession} from '../../services/owner-session.js'
import {merchantSession} from '../../services/merchant-session.js'
import {orderRefundsApi as api,refundStatusLabel} from '../../services/order-refunds.js'
import {imageRequestKey} from '../../services/private-images.js'
import {initialReservationReadState,initialReservationWriteState,createReservationReadFlow,createReservationWriteFlow,displayTime} from '../../services/reservations.js'
import {stateLabel} from '../../services/order-status.js'
const state=reactive(initialReservationReadState()),write=reactive(initialReservationWriteState()),queryState=reactive(initialReservationReadState()),form=reactive({reason:'',note:''})
let orderId=0,role='',visible=false,action=''
const token=()=>role==='owner'?ownerSession.accessToken:role==='merchant'?merchantSession.accessToken:''
const flow=createReservationReadFlow({state,token,request:t=>api.detail(t,role,orderId)})
const query=createReservationReadFlow({state:queryState,token,request:t=>api.reconcile(t,orderId)})
const save=createReservationWriteFlow({state:write,token,newKey:imageRequestKey,body:()=>({action,order_id:orderId,revision:state.value?.refund?.revision,reason:form.reason.trim(),note:form.note.trim()}),request:(t,b,k)=>b.action==='REQUEST'?api.request(t,b.order_id,b.reason,k):b.action==='WITHDRAW'?api.withdraw(t,b.order_id,b.revision,k):api.decide(t,b.order_id,b.revision,b.action,b.note,k),onConflict:()=>flow.load()})
function run(next){uni.showModal({title:next==='ACCEPT'?'同意取消并全额退款':next==='REQUEST'?'申请取消并全额退款':next==='WITHDRAW'?'撤回取消申请':'拒绝取消申请',content:next==='ACCEPT'?'确认后订单关闭，施工与核销停止；资金仍需供应商处理，不可撤回。':next==='REQUEST'?'提交即表示你同意全额退款取消。等待店长确认期间订单仍按原流程执行；本阶段不支持部分退款。':'确认提交本次操作？',success:r=>{if(r.confirm){if(write.saved)save.reset();action=next;save.save()}}})}
async function reconcile(){await query.load();if(visible&&queryState.value&&token())flow.load()}
function login(){uni.navigateTo({url:role==='merchant'?'/pages/merchant/index':'/pages/owner/index'})}
watch(token,()=>{flow.reset();save.reset();query.reset();form.reason='';form.note='';if(visible&&token())flow.load()},{flush:'sync'})
watch(()=>write.saved,v=>{if(v&&visible)flow.load()})
onLoad(q=>{orderId=Number(q?.id)||0;role=['owner','merchant'].includes(q?.role)?q.role:''})
onShow(()=>{visible=true;if(token())flow.load()})
onHide(()=>{visible=false;flow.reset();save.suspend();query.reset()})
onUnload(()=>{visible=false;flow.reset();save.reset();query.reset()})
</script>
<template><view class="reservation-page"><button v-if="write.retry" :disabled="write.busy" @tap="save.retry">重试原退款操作请求（不使用当前编辑内容）</button><text class="reservation-title">取消与全额退款</text><text>双方确认后全额退，暂不支持部分退款。</text><button v-if="!token()" @tap="login">登录对应账号</button><text v-if="state.busy">正在核对退款记录…</text><view v-if="state.message" role="status"><text>{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">登录对应账号</button><button v-else @tap="flow.load">重试</button></view>
<view v-if="state.value" class="reservation-panel"><text>订单 {{orderId}} · {{stateLabel(state.value.order_status)}}</text>
<view v-if="state.value.refund"><text class="reservation-heading">{{refundStatusLabel(state.value.refund.status)}}</text><text>全额 ¥{{state.value.refund.amount}}</text><text>申请原因：{{state.value.refund.reason}}</text><text>车主同意 {{displayTime(state.value.refund.owner_consented_at)}}</text><text v-if="state.value.refund.merchant_consented_at">店长同意 {{displayTime(state.value.refund.merchant_consented_at)}}</text><text v-if="state.value.refund.decision_note">门店说明：{{state.value.refund.decision_note}}</text><text v-if="state.value.refund.test_mode">隔离测试退款，未真实退回资金，不可作为退款凭据。</text><text v-if="state.value.refund.refunded_at">记录退款时间 {{displayTime(state.value.refund.refunded_at)}}</text>
<view v-if="state.value.refund.status==='REQUESTED'"><text>等待店长确认，订单仍按原流程执行。</text><text v-if="state.value.order_status!==state.value.refund.from_status">订单状态已变化，请车主撤回后重新申请。</text><button v-if="role==='owner'" :disabled="write.busy" @tap="run('WITHDRAW')">撤回申请</button><view v-if="role==='merchant'"><textarea v-model="form.note" maxlength="256" placeholder="处理说明；拒绝须填写原因"/><button :disabled="write.busy || state.value.order_status!==state.value.refund.from_status" @tap="run('ACCEPT')">同意取消并全额退款</button><button :disabled="write.busy" @tap="run('REJECT')">拒绝申请</button></view></view>
<button v-if="role==='merchant' && ['PENDING','FAILED','SUCCEEDED'].includes(state.value.refund.status)" :disabled="queryState.busy" :loading="queryState.busy" @tap="reconcile">核对退款结果</button></view>
<text v-else>尚无退款申请。未支付订单请回订单详情取消；已完成订单不开放本流程。</text>
<view v-if="role==='owner' && state.value.can_request"><textarea v-model="form.reason" maxlength="256" placeholder="填写取消与全额退款原因"/><button :disabled="write.busy" :loading="write.busy" @tap="run('REQUEST')">申请取消并全额退款</button></view><text v-if="write.message" role="status">{{write.message}}</text><text v-if="queryState.message" role="status">{{queryState.message}}</text><text v-else-if="queryState.loaded">{{refundStatusLabel(queryState.value?.refund?.status)}}</text><button @tap="flow.load">刷新订单与退款状态</button></view></view></template>
