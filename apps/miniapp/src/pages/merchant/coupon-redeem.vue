<script setup>
import {reactive,ref,watch,computed} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {merchantSession,clearMerchantSession} from '../../services/merchant-session.js'
import {initialMerchantCouponState,createMerchantCouponFlow,couponTransactionsApi,parseServiceCouponQr} from '../../services/coupon-transactions.js'
import {initialQuoteListState,createQuoteListFlow} from '../../services/quote-flow.js'
import {imageRequestKey} from '../../services/private-images.js'
import {apiRuntime} from '../../services/api-runtime.js'
import {displayTime} from '../../services/reservations.js'
const state=reactive(initialMerchantCouponState()),form=reactive({coupon:'',revision:'',code:''}),records=reactive(initialQuoteListState()),tick=ref(Date.now()),scanning=ref(false)
const token=()=>merchantSession.accessToken,body=()=>({user_coupon_id:Number(form.coupon),authorization_revision:Number(form.revision),code:form.code}),blocked=computed(()=>state.busy||state.uncertain||scanning.value)
const flow=createMerchantCouponFlow({state,token,body,newKey:imageRequestKey,confirm:v=>new Promise(resolve=>uni.showModal({title:'确认本店服务券核销',content:`${v.terms.name}，${v.project_name}（标准项目 ${v.project_id}）。请确认服务已完成并核对车主授权；此操作消费券，佣金0.00，不产生现金支付。`,success:r=>resolve(r.confirm),fail:()=>resolve(false)}))})
const list=createQuoteListFlow({state:records,token,fetchPage:couponTransactionsApi.serviceRecords})
let visible=false,timer,scanEpoch=0
function clear(){flow.reset();list.reset();scanEpoch++;scanning.value=false;Object.assign(form,{coupon:'',revision:'',code:''})}
function stop(){visible=false;clearInterval(timer);scanEpoch++;scanning.value=false;flow.suspend();list.reset();Object.assign(form,{coupon:'',revision:'',code:''})}
function login(){clear();clearMerchantSession();uni.navigateTo({url:'/pages/merchant/index'})}
async function scan(){if(blocked.value||!token())return;const g=++scanEpoch,actor=token();scanning.value=true;try{const result=await new Promise((resolve,reject)=>apiRuntime.scanCode({onlyFromCamera:true,scanType:['qrCode'],success:resolve,fail:reject}));if(g!==scanEpoch||actor!==token()||!visible)return;const parsed=parseServiceCouponQr(result?.result);Object.assign(form,{coupon:String(parsed.user_coupon_id),revision:String(parsed.authorization_revision),code:parsed.code});await flow.preview()}catch(e){if(g===scanEpoch&&actor===token()&&visible&&!/cancel/i.test(e?.errMsg||''))state.message=e?.message||'扫码未成功，可手动填写车主出示的授权信息'}finally{if(g===scanEpoch)scanning.value=false}}
watch(()=>[form.coupon,form.revision,form.code],()=>flow.change(),{flush:'sync'})
watch(token,()=>{clear();if(visible&&token())list.load()},{flush:'sync'})
watch(()=>state.saved,r=>{if(r&&visible)list.load()})
onShow(()=>{visible=true;clearInterval(timer);tick.value=Date.now();timer=setInterval(()=>{tick.value=Date.now()},1000);if(token())list.load()});onHide(stop);onUnload(()=>{stop();clear()})
</script>
<template><view class="reservation-page"><text class="reservation-title">服务券独立核销</text><text>核对发行门店与项目、车主授权，并确认服务交付。服务券核销佣金0.00。</text><button v-if="!token()" @tap="login">登录门店账号</button><template v-else>
 <view class="reservation-panel"><button class="reservation-primary" :disabled="blocked" @tap="scan">扫描车主服务券授权码</button><text>也可填写车主页面显示的本人券编号、授权版本与32位授权码。</text><input v-model="form.coupon" type="number" :disabled="blocked" placeholder="本人券编号"/><input v-model="form.revision" type="number" :disabled="blocked" placeholder="授权版本"/><input v-model="form.code" :disabled="blocked" maxlength="32" placeholder="32位授权码"/><button :disabled="blocked||tick<state.waitUntil" @tap="flow.preview">核对本店服务券与授权</button></view>
 <view v-if="state.preview" class="reservation-panel"><text class="reservation-heading">{{ state.preview.terms.name }}</text><text>{{ state.preview.project_name }} · 标准项目 {{ state.preview.project_id }}</text><text>发行门店 {{ state.preview.merchant_id }} · 授权截止 {{ displayTime(state.preview.authorization_expires_at) }}</text><button v-if="state.preview.can_redeem" class="reservation-primary" :disabled="blocked||tick<state.waitUntil" @tap="flow.redeem">确认交付并核销服务券</button></view>
 <view v-if="state.saved" class="reservation-panel"><text class="reservation-heading">服务券已核销</text><text>{{ state.saved.terms.name }} · {{ displayTime(state.saved.redeemed_at) }}</text><text>核销凭据 {{ state.saved.redemption_id }} · 佣金 ¥{{ state.saved.commission_amount }}</text><text>此凭据不表示微信或现金收款。</text></view>
 <button v-if="state.uncertain" :disabled="state.busy||tick<state.waitUntil" @tap="flow.redeem">重试原核销请求</button><text v-if="state.busy||scanning">正在核对…</text><text v-if="state.message" role="status">{{ state.message }}</text><text v-if="tick<state.waitUntil">请等待 {{ Math.ceil((state.waitUntil-tick)/1000) }} 秒再核对</text><button v-if="state.kind==='unauthorized'" @tap="login">重新登录门店账号</button>
 <view class="reservation-panel"><text class="reservation-heading">本店服务券核销记录</text><text v-if="records.loaded&&!records.items.length">暂无核销记录。</text><view v-for="r in records.items" :key="r.redemption_id" class="record"><text>{{ r.terms.name }} · 本人券 {{ r.user_coupon_id }}</text><text>标准项目 {{ r.project_id }} · {{ displayTime(r.redeemed_at) }} · 佣金 ¥{{ r.commission_amount }}</text></view><text v-if="records.message">{{ records.message }}</text><button :disabled="records.busy" @tap="list.load()">刷新本店核销记录</button><button v-if="records.items.length<records.total" :disabled="records.busy" @tap="list.load(true)">更多核销记录</button></view>
 </template></view></template>
<style scoped>input{height:80rpx;padding:12rpx;background:#efede6;border-radius:12rpx;font-size:26rpx}.record{display:flex;flex-direction:column;gap:12rpx;padding:20rpx 0;border-top:1px solid #e0ded6}</style>
