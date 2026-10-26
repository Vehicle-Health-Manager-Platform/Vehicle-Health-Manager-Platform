<script setup>
import {reactive,computed,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {merchantSession,clearMerchantSession} from '../../services/merchant-session.js'
import {orderRefundsApi as api} from '../../services/order-refunds.js'
import {imageRequestKey} from '../../services/private-images.js'
import {initialReservationReadState,initialReservationWriteState,createReservationReadFlow,createReservationWriteFlow} from '../../services/reservations.js'
import {refundStageView,refundFactsView,refundActionsView,refundActionBody,refundFailureView,refundWriteResultView,createRefundConfirmationFlow} from '../../services/order-refund-display.js'
import {toneClass} from '../../services/ui-tone.js'
const state=reactive(initialReservationReadState()),write=reactive(initialReservationWriteState()),queryState=reactive(initialReservationReadState())
const route=reactive({id:0,role:''}),form=reactive({reason:'',note:''}),confirmation=reactive({busy:false,message:''})
let visible=false,preparedBody=null
const token=()=>route.role==='owner'?ownerSession.accessToken:route.role==='merchant'?merchantSession.accessToken:''
const flow=createReservationReadFlow({state,token,request:t=>api.detail(t,route.role,route.id)})
const query=createReservationReadFlow({state:queryState,token,request:t=>api.reconcile(t,route.id)})
const save=createReservationWriteFlow({state:write,token,newKey:imageRequestKey,body:()=>preparedBody,
  request:(t,b,k)=>b.action==='REQUEST'?api.request(t,b.order_id,b.reason,k):b.action==='WITHDRAW'?api.withdraw(t,b.order_id,b.revision,k):api.decide(t,b.order_id,b.revision,b.action,b.note,k),onConflict:()=>flow.load()})
const confirmFlow=createRefundConfirmationFlow({state:confirmation,
  capture(action){
    if(!visible||!token()||state.value?.order_id!==route.id)return null
    const body=refundActionBody(action,state.value,route.role,form,state,write,false,queryState)
    return body?{actor:token(),role:route.role,orderId:route.id,detail:state.value,form:{...form},body}:null
  },
  confirm:options=>new Promise(resolve=>uni.showModal({...options,success:r=>resolve(r.confirm===true),fail:()=>resolve(false)})),
  async dispatch(body){if(write.saved)save.reset();preparedBody=body;await save.save()},
  onChanged:message=>{confirmation.message=message},
})
const stage=computed(()=>refundStageView(state.value)),facts=computed(()=>refundFactsView(state.value))
const actions=computed(()=>refundActionsView(state.value,route.role,form,state,write,confirmation.busy,queryState))
const failure=computed(()=>refundFailureView(state,write,queryState)),saved=computed(()=>refundWriteResultView(write))
const queryResult=computed(()=>refundStageView(queryState.value))
const locked=computed(()=>write.busy||confirmation.busy||queryState.busy)
function run(action){confirmation.message='';confirmFlow.run(action)}
async function reconcile(){if(!actions.value.reconcileReady)return;await query.load();if(visible&&queryState.value&&token())flow.load()}
function refresh(){if(!locked.value)flow.load()}
function login(){if(route.role==='merchant')clearMerchantSession();else clearOwnerSession();uni.navigateTo({url:route.role==='merchant'?'/pages/merchant/index':'/pages/owner/index'})}
function clear(){confirmFlow.cancel();flow.reset();save.reset();query.reset();preparedBody=null;form.reason='';form.note='';confirmation.message=''}
watch(token,()=>{clear();if(visible&&token())flow.load()},{flush:'sync'})
watch(()=>write.saved,v=>{if(v&&visible){state.value=v;state.loaded=true;flow.load()}},{flush:'sync'})
onLoad(q=>{route.id=Number(q?.id)||0;route.role=['owner','merchant'].includes(q?.role)?q.role:''})
onShow(()=>{visible=true;if(token())flow.load()})
onHide(()=>{visible=false;confirmFlow.cancel();flow.reset();save.suspend();query.reset()})
onUnload(()=>{visible=false;clear()})
</script>
<template>
  <view class="reservation-page" data-testid="refund-page">
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">订单售后</text><text class="ux-title">取消与全额退款</text>
      <text class="ux-lead">双方确认后全额退，暂不支持部分退款。订单关闭和操作保存均不代表资金已到账。</text>
    </view>
    <view v-if="!token()" class="ux-card ux-in ux-r2" data-testid="refund-login">
      <text class="ux-card-title">需要对应账号</text><text class="copy">本人车主可申请或撤回，接单门店的店长可决定及核对结果。</text>
      <button class="reservation-primary" @tap="login">登录对应账号</button>
    </view>
    <template v-else>
      <text v-if="state.busy" class="status-line" role="status">正在核对退款记录…</text>
      <view v-if="failure.readMessage" class="ux-card ux-in ux-r2" role="status" data-testid="refund-read-error">
        <text class="ux-card-title">退款记录未能核对</text><text class="copy">{{failure.readMessage}}</text>
        <button v-if="failure.canRelogin" class="reservation-primary" @tap="login">登录对应账号</button>
        <button v-else :disabled="locked" @tap="refresh">重试查询</button>
      </view>
      <view v-if="write.retry" class="ux-card ux-in ux-r2" data-testid="refund-original-retry">
        <text class="ux-card-title">上次操作结果尚未确定</text><text class="copy">保留原请求与原填写内容；修改表单或刷新不能替代原请求核对。</text>
        <button class="reservation-primary" :disabled="locked" :loading="write.busy" @tap="save.retry">重试原退款操作请求（不使用当前编辑内容）</button>
      </view>
      <template v-if="state.value">
        <view class="ux-card ux-in ux-r2" data-testid="refund-order">
          <view class="head"><text class="ux-card-title">{{facts.orderCopy}}</text><view class="ux-badge ux-badge-muted"><text>{{facts.statusLabel}}</text></view></view>
          <text class="copy">车主和店长须分别确认，实际退款状态请查看下方记录。</text>
        </view>
        <view class="ux-card ux-in ux-r3" data-testid="refund-stage">
          <view class="head"><text class="ux-card-title">{{stage.title}}</text><view class="ux-badge" :class="toneClass(stage.tone)"><text>{{stage.changed?'需重新申请':'当前记录'}}</text></view></view>
          <text class="copy">{{stage.copy}}</text>
        </view>
        <view v-if="state.value.refund" class="ux-card ux-in ux-r3" data-testid="refund-record">
          <text class="ux-card-title">退款申请记录</text><text class="amount ux-num">{{facts.amountCopy}}</text>
          <view class="ux-row"><text class="ux-row-title">申请原因</text><text class="ux-row-copy">{{facts.reason}}</text></view>
          <view class="ux-row"><text class="ux-row-copy">车主同意 {{facts.ownerTime}}</text><text v-if="facts.merchantTime" class="ux-row-copy">店长同意 {{facts.merchantTime}}</text></view>
          <text v-if="facts.note" class="copy">门店说明：{{facts.note}}</text>
          <text v-if="facts.testCopy" class="risk-line" data-testid="refund-test-warning">{{facts.testCopy}}</text>
          <text v-if="facts.refundedTime" class="copy">记录退款时间 {{facts.refundedTime}}</text>
        </view>
        <view v-if="actions.canRequest" class="ux-card ux-in ux-r3" data-testid="refund-request-form">
          <text class="ux-card-title">申请全额退款</text><text class="copy">{{actions.requestHint}}</text>
          <textarea class="field" v-model="form.reason" maxlength="256" :disabled="actions.formDisabled" placeholder="填写取消与全额退款原因" aria-label="取消与全额退款原因" data-testid="refund-reason"/>
          <button class="reservation-primary" :disabled="!actions.requestReady" :loading="write.busy" @tap="run('REQUEST')">申请取消并全额退款</button>
        </view>
        <view v-if="actions.canWithdraw" class="ux-card ux-in ux-r3" data-testid="refund-withdraw">
          <text class="ux-card-title">等待店长处理</text><text class="copy">如需继续服务，可在店长同意前撤回申请。</text><text v-if="actions.lockCopy" class="copy">{{actions.lockCopy}}</text>
          <button :disabled="!actions.withdrawReady" @tap="run('WITHDRAW')">撤回申请</button>
        </view>
        <view v-if="actions.canDecide" class="ux-card ux-in ux-r3" data-testid="refund-decision-form">
          <text class="ux-card-title">店长处理</text><text class="copy">{{actions.decisionHint}}</text>
          <textarea class="field" v-model="form.note" maxlength="256" :disabled="actions.formDisabled" placeholder="处理说明；拒绝须填写原因" aria-label="退款处理说明" data-testid="refund-note"/>
          <button class="reservation-primary" :disabled="!actions.acceptReady" @tap="run('ACCEPT')">同意取消并全额退款</button>
          <button :disabled="!actions.rejectReady" @tap="run('REJECT')">拒绝申请</button>
        </view>
        <view v-if="actions.canReconcile" class="ux-card ux-in ux-r3" data-testid="refund-reconcile">
          <text class="ux-card-title">核对资金处理结果</text><text class="copy">从原退款记录核对结果，不新建退款申请，也不由客户端填写到账状态。</text><text v-if="actions.lockCopy" class="copy">{{actions.lockCopy}}</text>
          <button class="reservation-primary" :disabled="!actions.reconcileReady" :loading="queryState.busy" @tap="reconcile">核对退款结果</button>
        </view>
      </template>
      <view v-if="saved.exists" class="ux-card ux-in ux-r3" role="status" data-testid="refund-write-result">
        <text class="ux-card-title">最近一次{{saved.title}}</text><text class="copy">{{saved.copy}}</text><text class="ux-card-note">以上为提交回执，最新进度以下次读取为准。</text>
      </view>
      <view v-if="queryState.value" class="ux-card ux-in ux-r3" role="status" data-testid="refund-query-result">
        <text class="ux-card-title">本次核对：{{queryResult.title}}</text><text class="copy">{{queryResult.copy}}</text>
      </view>
      <view v-if="failure.writeMessage || failure.queryMessage || confirmation.message" class="ux-card ux-in ux-r3" role="status" data-testid="refund-action-error">
        <text class="ux-card-title">本次操作需要核对</text><text v-if="failure.writeMessage" class="copy">{{failure.writeMessage}}</text><text v-if="failure.queryMessage" class="copy">{{failure.queryMessage}}</text><text v-if="confirmation.message" class="copy">{{confirmation.message}}</text>
        <button v-if="failure.canRelogin" class="reservation-primary" @tap="login">登录对应账号</button>
      </view>
      <button class="footer-refresh" :disabled="locked||state.busy" @tap="refresh">刷新订单与退款状态</button>
    </template>
  </view>
</template>
<style scoped>
.head { display:flex; align-items:center; justify-content:space-between; gap:16rpx; }
.copy { display:block; margin-top:14rpx; color:var(--ux-ink-soft); font-size:26rpx; line-height:1.7; white-space:pre-wrap; word-break:break-all; }
.amount { display:block; margin-top:16rpx; font-size:44rpx; font-weight:700; }
.field { box-sizing:border-box; width:100%; margin-top:20rpx; min-height:160rpx; padding:22rpx; border:1rpx solid var(--ux-line); border-radius:var(--ux-radius-ctl); background:var(--ux-surface-sunk); color:var(--ux-ink); font-size:26rpx; }
.risk-line { display:block; margin-top:16rpx; color:var(--ux-risk); font-size:25rpx; line-height:1.6; }
.status-line { display:block; margin:16rpx 0; color:var(--ux-muted); font-size:26rpx; }
.footer-refresh { margin-top:24rpx; }
</style>
