<script setup>
import {computed,reactive,ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {merchantSession,clearMerchantSession} from '../../services/merchant-session.js'
import {initialReservationReadState,createReservationReadFlow} from '../../services/reservations.js'
import {deliveryCompareApi} from '../../services/delivery-compare.js'
import {deliveryPageView,deliveryResultView,deliveryBaselineView,deliveryWorkView,deliveryMissingView,deliveryFailureView} from '../../services/delivery-compare-display.js'
const state=reactive(initialReservationReadState()),order=ref(0),role=ref('owner')
// 车主与商家看的是同一份投影：授权在服务端按身份判定，页面只切会话与文案。
const token=()=>role.value==='merchant'?merchantSession.accessToken:ownerSession.accessToken
const flow=createReservationReadFlow({state,token,request:t=>deliveryCompareApi.compare(t,order.value)})
// 映射吃载荷：流程把投影放在 state.value，直接把 state 传进去会静默拿到 undefined。
const page=computed(()=>deliveryPageView(role.value)),result=computed(()=>deliveryResultView(state.value,role.value)),baseline=computed(()=>deliveryBaselineView(state.value)),work=computed(()=>deliveryWorkView(state.value)),missing=computed(()=>deliveryMissingView(state.value)),failure=computed(()=>deliveryFailureView(state))
let visible=false
function login(){if(role.value==='merchant'){clearMerchantSession();uni.navigateTo({url:'/pages/merchant/index'})}else{clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}}
function open(path){if(order.value>0)uni.navigateTo({url:`${path}?id=${order.value}${role.value==='merchant'?'&role=merchant':''}`})}
watch(()=>[token(),order.value],()=>{flow.reset();if(visible&&token())flow.load()},{flush:'sync'})
onLoad(q=>{order.value=/^[1-9]\d*$/.test(String(q?.id||''))?Number(q.id):0;role.value=q?.role==='merchant'?'merchant':'owner'})
onShow(()=>{visible=true;if(token()&&order.value>0)flow.load()})
onHide(()=>{visible=false;flow.reset()})
onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">{{page.title}}</text><text>{{page.hint}}</text>
  <button v-if="!token()" @tap="login">车主登录</button>
  <text v-if="state.busy">正在核对取车对比…</text>
  <view v-if="state.message" class="reservation-panel" data-testid="delivery-compare-failure"><text>{{failure.title}}</text><text role="status">{{failure.message}}</text><button v-if="failure.conflict" @tap="open('/pages/check/pickup-detail')">先查看接车单</button><button v-if="failure.canLogin" @tap="login">重新登录</button><button v-else @tap="flow.load">{{failure.retryLabel}}</button></view>
  <view v-if="state.value" class="reservation-panel" data-testid="delivery-compare-result"><text class="reservation-heading">{{result.label}}</text><text>{{result.statusCopy}}</text><text>{{result.deliverableCopy}}</text></view>
  <view v-if="state.value" class="reservation-panel" data-testid="delivery-compare-baseline"><text class="reservation-heading">接车基线</text><text>{{baseline.mileageCopy}}</text><text>{{baseline.deltaCopy}}</text><text>{{baseline.fuelCopy}}</text><text>{{baseline.damageCopy}}</text><text>{{baseline.photoCopy}}</text><text>{{baseline.confirmCopy}}</text><text v-if="baseline.confirmAtCopy">{{baseline.confirmAtCopy}}</text></view>
  <view v-if="state.value" class="reservation-panel" data-testid="delivery-compare-work"><text class="reservation-heading">完工证据</text><text>{{work.protectionCopy}}</text><text>{{work.reportCopy}}</text><text v-if="work.signedAtCopy">{{work.signedAtCopy}}</text><text>{{work.evidenceCopy}}</text><text v-if="work.spareCopy">{{work.spareCopy}}</text></view>
  <view v-if="state.value" class="reservation-panel" data-testid="delivery-compare-missing"><text class="reservation-heading">缺口</text><text>{{missing.copy}}</text></view>
  <button v-if="state.value" @tap="open('/pages/check/pickup-detail')">查看接车单</button>
  <button v-if="state.value" @tap="open('/pages/order/work-evidence')">查看施工进度与前后照片</button>
  <button v-if="token()" :disabled="state.busy" @tap="flow.load">刷新取车对比</button>
</view></template>
