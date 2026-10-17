<script setup>
import {reactive,ref,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {createOwnerPointExchangeApi,createOwnerExchangeRecovery,createOwnerExchangeFlow,prepareOwnerExchange,EXCHANGE_STATES} from '../../services/owner-point-exchanges.js'
import {initialDiagnosisState} from '../../services/async-diagnosis.js'
import {imageRequestKey} from '../../services/private-images.js'
import {displayTime} from '../../services/reservations.js'
const state=reactive(initialDiagnosisState()),historyPage=ref(1),quote=ref(null),lastSuccess=ref(null),api=createOwnerPointExchangeApi(),token=()=>ownerSession.accessToken
const blocked=()=>state.busy||state.retry||state.kind==='storage',recovery=createOwnerExchangeRecovery({actor:()=>ownerSession.actorId})
function confirm(action){if(action==='discard-local')return new Promise(resolve=>uni.showModal({title:'核对并清理本机兑换请求',content:'请核对刚读取的成功兑换记录和当前余额。只清理本机请求，不删除成功事实、不撤销已发券、不返积分，也不会自动重新兑换。',success:r=>resolve(r.confirm),fail:()=>resolve(false)}));const q=quote.value;if(!q)return Promise.resolve(false);const c=q.catalog,t=c.terms;return new Promise(resolve=>uni.showModal({title:'确认积分兑换',content:`${t.name} · ${t.type==='SERVICE'?'服务券':`金额券 ${t.amount} 元，满 ${t.min_spend} 元可用`}\n门店：${q.merchant.name}\n项目：${q.project.name}\n扣除 ${c.points_cost} 积分；确认时余额 ${q.balance}\n同模板已兑 ${q.exchanged_count} 次，终身限额 ${c.per_owner_limit} 次，跨版本累计\n兑换窗口：${displayTime(c.valid_from)} 至 ${displayTime(c.valid_to)}\n原券窗口：${displayTime(t.valid_from)} 至 ${displayTime(t.valid_to)}\n依据：${c.source_note}\n成功后不支持自行撤销返分，不延长原券期限。`,success:r=>resolve(r.confirm),fail:()=>resolve(false)}))}
const flow=createOwnerExchangeFlow({state,token,actor:()=>ownerSession.actorId,historyPage:()=>historyPage.value,api,recovery,newKey:imageRequestKey,confirm,onWritten:receipt=>{lastSuccess.value=receipt;quote.value=null}})
let visible=false
function exchange(row){if(blocked())return;try{const prepared=prepareOwnerExchange(row,state.data?.offers);quote.value=prepared.quote;flow.save('exchange',0,prepared.body)}catch(error){state.message=error.message}}
function historyNext(delta){if(blocked())return;historyPage.value+=delta;flow.load(state.page)}
function login(){clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
function reset(){flow.reset();historyPage.value=1;quote.value=null;lastSuccess.value=null}
function mine(){uni.navigateTo({url:'/pages/coupon/mine'})}
watch(()=>[ownerSession.accessToken,ownerSession.actorId],()=>{reset();if(visible&&token()&&ownerSession.actorId)flow.load()},{flush:'sync'})
watch(()=>state.kind,k=>{if(['unauthorized','forbidden','missing'].includes(k)){quote.value=null;lastSuccess.value=null}})
onShow(()=>{visible=true;if(token()&&ownerSession.actorId)flow.load()});onHide(()=>{visible=false;reset()});onUnload(()=>{visible=false;reset()})
</script>
<template>
 <view class="reservation-page">
  <text class="reservation-title">积分兑换券</text><text>每人同模板次数跨版本累计。成功兑换不支持自行撤销返分；原券过期不延期。</text>
  <button v-if="!token()||!ownerSession.actorId" @tap="login">登录车主账号</button><text v-if="state.busy">正在核对本人兑换信息…</text><text v-if="state.message" role="status">{{state.message}}</text>
  <button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录</button>
  <view v-if="state.retry" class="reservation-panel"><text>存在本机已确认的原兑换请求。重试沿用原目录和修订，不按当前目录自动换价；停止或过期也须先核对原请求是否已成功。</text><button :disabled="state.busy" @tap="flow.retry">重试原兑换请求</button></view>
  <button v-if="state.kind==='storage'" :disabled="state.busy" @tap="flow.discardLocal">核对服务器并清理本机请求</button><button v-if="token()&&ownerSession.actorId" :disabled="state.busy" @tap="flow.load(state.page)">刷新目录与成功记录</button>
  <view v-if="lastSuccess" class="reservation-panel"><text class="reservation-heading">兑换已成功</text><text>{{lastSuccess.catalog.terms.name}} · 扣除 {{lastSuccess.points_spent}} 积分</text><text>当次余额 {{lastSuccess.balance_after}}，当前余额以刷新结果为准。</text><text>{{displayTime(lastSuccess.exchanged_at)}} · 本人券 #{{lastSuccess.user_coupon_id}}</text><button @tap="mine">查看我的券与有效期</button></view>
  <template v-if="state.data">
   <view class="reservation-panel"><text class="reservation-heading">已核验可用积分 {{state.data.offers.balance}}</text><text v-if="state.data.offers.legacy_state==='REVIEW_REQUIRED'">历史积分来源待核对，未计入可兑余额。</text><text v-if="!state.data.offers.exchange_available">当前兑换资格尚未确认，请稍后刷新或重新核对账号。</text><text>核对时间 {{displayTime(state.data.offers.read_at)}}。实际提交时会重新核对库存、积分和有效期。</text></view>
   <text class="reservation-heading">兑换目录</text><text v-if="!state.data.offers.items.length">尚无已发布兑换目录，价格未知；没有默认兑换价。</text>
   <view v-for="r in state.data.offers.items" :key="r.catalog.id" class="reservation-panel"><text class="reservation-heading">{{r.catalog.terms.name}}</text><text>{{r.catalog.terms.type==='SERVICE'?'服务券':'金额券'}} · {{r.catalog.points_cost}} 积分</text><text v-if="r.catalog.terms.type==='DISCOUNT'">抵扣 {{r.catalog.terms.amount}} 元，满 {{r.catalog.terms.min_spend}} 元可用；预约保留正现金实付。</text><text v-else>指定服务独立核销，不创建支付。</text>
    <template v-if="r.merchant&&r.project"><text>发行门店：{{r.merchant.name}} · {{r.merchant.address}}</text><text>指定项目：{{r.project.name}}</text><text>{{r.project.service_content}}</text></template><text v-else>当前门店或项目来源暂不可核对。</text>
    <text>库存 {{r.stock_left===null?'未知':r.stock_left}} · 本人同模板已兑 {{r.exchanged_count}} 次 · 终身限额 {{r.catalog.per_owner_limit}} 次 · 剩余 {{r.remaining_limit}} 次</text><text>兑换窗口 {{displayTime(r.catalog.valid_from)}} 至 {{displayTime(r.catalog.valid_to)}}</text><text>原券有效期 {{displayTime(r.catalog.terms.valid_from)}} 至 {{displayTime(r.catalog.terms.valid_to)}}</text><text>来源：{{r.catalog.source_note}}</text><text>{{EXCHANGE_STATES[r.eligibility]}}</text><button v-if="r.eligibility==='ELIGIBLE'" :disabled="blocked()||!state.data.offers.exchange_available" @tap="exchange(r)">核对并兑换</button>
   </view>
   <button v-if="state.page>1" :disabled="blocked()" @tap="flow.load(state.page-1)">上一页目录</button><button v-if="state.page*20<state.data.offers.total" :disabled="blocked()" @tap="flow.load(state.page+1)">下一页目录</button>
   <text class="reservation-heading">本人成功兑换</text><text v-if="!state.data.history.items.length">暂无成功兑换记录。</text><view v-for="r in state.data.history.items" :key="r.exchange_id" class="reservation-panel"><text>{{r.catalog.terms.name}} · 扣除 {{r.points_spent}} 积分 · 当次余额 {{r.balance_after}}</text><text>{{displayTime(r.exchanged_at)}} · 本人券 #{{r.user_coupon_id}}</text><text>原券期限 {{displayTime(r.catalog.terms.valid_from)}} 至 {{displayTime(r.catalog.terms.valid_to)}}，过期仍过期。</text><text>登记时依据：{{r.catalog.source_note}}</text><button @tap="mine">查看我的券与实际状态</button></view>
   <button v-if="historyPage>1" :disabled="blocked()" @tap="historyNext(-1)">上一页成功记录</button><button v-if="historyPage*20<state.data.history.total" :disabled="blocked()" @tap="historyNext(1)">下一页成功记录</button>
  </template><button @tap="uni.navigateTo({url:'/pages/points/index'})">我的积分与收支明细</button>
 </view>
</template>
