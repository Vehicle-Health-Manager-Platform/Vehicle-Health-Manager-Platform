<script setup>
import {reactive,ref,computed,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {initialServiceCouponState,createServiceCouponFlow,couponEligibility} from '../../services/coupon-transactions.js'
import {imageRequestKey} from '../../services/private-images.js'
import {displayTime} from '../../services/reservations.js'
import {couponLocalTime} from '../../services/coupons.js'
import ServiceCouponQr from '../../components/ServiceCouponQr.vue'
const state=reactive(initialServiceCouponState()),uc=ref(0),tick=ref(Date.now()),token=()=>ownerSession.accessToken
const authorization=computed(()=>state.code&&Date.parse(state.code.expires_at)>tick.value?{user_coupon_id:state.code.user_coupon_id,authorization_revision:state.code.authorization_revision,code:state.code.code}:null)
const flow=createServiceCouponFlow({state,token,coupon:()=>uc.value,newKey:imageRequestKey,confirm:v=>new Promise(resolve=>uni.showModal({title:'生成服务券核销授权',content:`${v.terms.name}，限发行门店 ${v.merchant_id}。出示授权码允许本店核销此券，请在确认服务完成后出示。重新生成会使旧码失效。`,success:r=>resolve(r.confirm),fail:()=>resolve(false)}))})
let visible=false,timer
function stop(){visible=false;clearInterval(timer);flow.suspend()}
function login(){flow.reset();clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
watch(token,()=>{flow.reset();if(visible&&token())flow.load()},{flush:'sync'})
onLoad(q=>{uc.value=/^[1-9][0-9]{0,15}$/.test(q?.id||'')&&Number.isSafeInteger(Number(q.id))?Number(q.id):0})
onShow(()=>{visible=true;clearInterval(timer);tick.value=Date.now();timer=setInterval(()=>{tick.value=Date.now()},1000);if(token()&&!state.uncertain)flow.load()});onHide(stop);onUnload(()=>{stop();flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">服务券授权与核销记录</text><text>服务兑换不创建现金支付，核销佣金0.00。仅向发行门店出示授权码。</text><button v-if="!token()" @tap="login">登录车主账号</button>
 <template v-else><view v-if="state.value" class="reservation-panel"><text class="reservation-heading">{{ state.value.terms?.name||'券来源未知' }}</text><text>本人券编号 {{ uc }} · 发行门店 {{ state.value.merchant_id||'未知' }}</text><template v-if="state.value.terms"><text>标准项目 {{ state.value.terms.project_id }}</text><text>截止 {{ couponLocalTime(state.value.terms.valid_to) }}（北京时间）</text></template><text>{{ couponEligibility[state.value.eligibility] }}</text>
  <view v-if="state.value.redemption" class="receipt"><text>已核销 · {{ displayTime(state.value.redemption.redeemed_at) }}</text><text>发行门店 {{ state.value.redemption.merchant_id }} · 核销佣金 ¥0.00</text></view>
  <button v-else :disabled="state.busy||state.uncertain||state.value.eligibility!=='ELIGIBLE'" @tap="flow.generate">生成 / 更新核销授权码</button>
 </view><view v-if="authorization" class="reservation-panel"><ServiceCouponQr :authorization="authorization"/><text>本人券 {{ authorization.user_coupon_id }} · 授权版本 {{ authorization.authorization_revision }}</text><text class="code">授权码 {{ authorization.code }}</text><text>授权截止 {{ displayTime(state.code.expires_at) }}</text><text>隐藏页面会清除显示的授权码，需要时重新生成。</text></view>
 <text v-else-if="state.code">授权码已过期，请重新生成。</text><button v-if="state.uncertain" :disabled="state.busy" @tap="flow.generate">重试原授权请求</button><text v-if="state.busy">正在核对…</text><text v-if="state.message" role="status">{{ state.message }}</text><button v-if="state.kind==='unauthorized'" @tap="login">重新登录</button><button :disabled="state.busy||state.uncertain" @tap="flow.load">刷新本人券与核销状态</button><button @tap="uni.navigateTo({url:'/pages/coupon/mine'})">返回我的券</button></template>
</view></template>
<style scoped>.code{word-break:break-all;font-family:monospace;font-size:25rpx}.receipt{display:flex;flex-direction:column;gap:16rpx}</style>
