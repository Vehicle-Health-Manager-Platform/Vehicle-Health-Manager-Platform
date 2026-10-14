<script setup>
import {reactive,ref,watch,computed} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {vehicleApi} from '../../services/vehicles.js'
import {imageRequestKey} from '../../services/private-images.js'
import {initialQuoteListState,createQuoteListFlow} from '../../services/quote-flow.js'
import {reservationsApi,initialReservationReadState,createReservationReadFlow,chinaDate,displayTime} from '../../services/reservations.js'
import {initialCouponBookingState,createCouponBookingFlow,couponEligibility} from '../../services/coupon-transactions.js'
import {couponLocalTime} from '../../services/coupons.js'
const quote=reactive(initialReservationReadState()),vehicles=reactive(initialQuoteListState()),slots=reactive(initialQuoteListState()),state=reactive(initialCouponBookingState())
const vehicle=ref(0),slot=ref(0),day=ref(chinaDate()),token=()=>ownerSession.accessToken,blocked=computed(()=>state.busy||state.uncertain||Boolean(state.saved))
let id=0,visible=false,routeActor='',routeVehicle=0,routeSlot=0,applied=false
const prices=createReservationReadFlow({state:quote,token,request:t=>reservationsApi.quote(t,id)})
const cars=createQuoteListFlow({state:vehicles,token,fetchPage:async(t,n)=>{const r=await vehicleApi.list(t,n);return{...r,items:r.list}}})
const times=createQuoteListFlow({state:slots,token,fetchPage:(t,n)=>{if(!quote.value)throw new Error('请先读取报价');return reservationsApi.slots(t,quote.value.merchant_id,quote.value.standard_project_id,day.value,n)}})
const flow=createCouponBookingFlow({state,token,newKey:imageRequestKey,selection:()=>({merchant_project_id:id,quote_version_id:quote.value?.quote_version_id||0,vehicle_id:vehicle.value,slot_id:slot.value})})
async function load(){if(state.uncertain)return;flow.change();await prices.load();if(!visible||!token())return;if(quote.value)times.load();cars.load();if(!applied&&routeActor===token()){vehicle.value=routeVehicle;slot.value=routeSlot;applied=true;if(vehicle.value&&slot.value)flow.load()}}
function clear(){flow.reset();prices.reset();cars.reset();times.reset();vehicle.value=0;slot.value=0}
function chooseVehicle(row){if(blocked.value)return;vehicle.value=row.vehicle_id;flow.change()}
function chooseSlot(row){if(blocked.value)return;slot.value=row.slot_id;flow.change()}
function chooseDay(e){if(blocked.value)return;day.value=e.detail.value;slot.value=0;flow.change();times.reset();if(quote.value)times.load()}
function login(){clear();clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
watch(token,()=>{clear();routeActor='';if(visible&&token())load()},{flush:'sync'})
watch(()=>state.saved,r=>{if(r&&visible)uni.redirectTo({url:`/pages/order/detail?id=${r.order_id}`})})
onLoad(q=>{const number=n=>typeof n==='string'&&/^[1-9][0-9]{0,15}$/.test(n)&&Number.isSafeInteger(Number(n))?Number(n):0;id=number(q?.id);routeVehicle=number(q?.vehicle_id);routeSlot=number(q?.slot_id);routeActor=token()})
onShow(()=>{visible=true;if(token()&&!state.uncertain)load()})
onHide(()=>{visible=false;flow.suspend();prices.reset();cars.reset();times.reset()});onUnload(()=>{visible=false;clear()})
</script>
<template><view class="reservation-page"><text class="reservation-title">使用金额券预约</text><text>每单一券，不叠加，仅限发行门店指定项目；抵扣后须至少现金实付0.01元。</text>
 <button v-if="!token()" @tap="login">登录车主账号</button><template v-else>
 <view v-if="state.uncertain" class="reservation-panel"><text>创建结果尚未确定，请使用原请求重试，避免重复预约。</text><button :disabled="state.busy" @tap="flow.save">重试原用券预约</button><button @tap="uni.navigateTo({url:'/pages/order/list'})">核对我的订单</button></view>
 <template v-else>
  <view v-if="quote.value" class="reservation-panel"><text class="reservation-heading">{{ quote.value.project_name }}</text><text>{{ quote.value.merchant_name }}</text><text class="reservation-price">当前原价 ¥{{ quote.value.price }}</text></view>
  <text v-if="quote.message">{{ quote.message }}</text><button :disabled="blocked" @tap="load">刷新当前报价与预约信息</button>
  <view class="reservation-panel"><text class="reservation-heading">本人车辆</text><text v-if="vehicle">当前所选车辆 {{ vehicle }}</text><button v-for="row in vehicles.items" :key="row.vehicle_id" :disabled="blocked" @tap="chooseVehicle(row)">{{ vehicle===row.vehicle_id?'✓ ':'' }}{{ row.model_name||row.plate_no_masked||'本人车辆' }}</button><text v-if="vehicles.loaded&&!vehicles.items.length">请先添加本人车辆。</text><text v-if="vehicles.message">{{ vehicles.message }}</text><button :disabled="blocked||vehicles.busy" @tap="cars.retry">刷新本人车辆</button><button v-if="vehicles.items.length<vehicles.total" :disabled="blocked||vehicles.busy" @tap="cars.load(true)">更多本人车辆</button></view>
  <view class="reservation-panel"><text class="reservation-heading">当前时段</text><picker mode="date" :value="day" :start="chinaDate()" :end="chinaDate(new Date(Date.now()+30*86400000))" :disabled="blocked" @change="chooseDay"><view class="reservation-field">{{ day }} · 选择日期</view></picker><text v-if="slot">所选时段编号 {{ slot }}</text><button v-for="row in slots.items" :key="row.slot_id" :disabled="blocked" @tap="chooseSlot(row)">{{ slot===row.slot_id?'✓ ':'' }}{{ displayTime(row.starts_at) }} · 剩余 {{ row.capacity_left }}</button><text v-if="slots.loaded&&!slots.items.length">当天暂无可用时段。</text><text v-if="slots.message">{{ slots.message }}</text><button :disabled="blocked||slots.busy" @tap="times.retry">刷新时段</button><button v-if="slots.items.length<slots.total" :disabled="blocked||slots.busy" @tap="times.load(true)">更多时段</button></view>
  <button :disabled="blocked||!vehicle||!slot||!quote.value" @tap="flow.load(1)">读取本人券并核对适用资格</button>
  <template v-if="state.context"><view class="reservation-panel"><text>本人车辆 {{ state.context.vehicle_id }} · {{ displayTime(state.context.slot.starts_at) }}</text><text>时段剩余 {{ state.context.slot.capacity_left }}</text><text v-if="!state.context.coupons.items.length">还没有本人券，可先在个人中心领取。</text></view>
   <view v-for="row in state.context.coupons.items" :key="row.user_coupon_id" class="reservation-panel"><text class="reservation-heading">{{ row.terms?.name||'历史来源未知券' }}</text><template v-if="row.terms"><text>{{ row.terms.type==='DISCOUNT'?`满 ¥${row.terms.min_spend} 减 ¥${row.terms.amount}`:'服务兑换券' }}</text><text>截止 {{ couponLocalTime(row.terms.valid_to) }}（北京时间）</text></template><text>{{ couponEligibility[row.eligibility] }}</text><button :disabled="blocked||row.eligibility!=='ELIGIBLE'" @tap="flow.choose(row)">{{ state.selectedId===row.user_coupon_id?'已选金额券':'选择此金额券' }}</button></view>
   <button v-if="state.context.coupons.page>1" :disabled="blocked" @tap="flow.load(state.context.coupons.page-1)">上一页本人券</button><button v-if="state.context.coupons.page*20<state.context.coupons.total" :disabled="blocked" @tap="flow.load(state.context.coupons.page+1)">下一页本人券</button>
   <view v-if="state.context.selected" class="reservation-panel"><text>{{ state.context.selected.terms?.name||'所选券来源未知' }}</text><text v-if="state.context.selected.eligibility==='ELIGIBLE'" class="reservation-price">原价 ¥{{ state.context.quote.price }} − 券 ¥{{ state.context.selected.discount_amount }} = 现金实付 ¥{{ state.context.selected.cash_amount }}</text><text v-else>{{ couponEligibility[state.context.selected.eligibility] }}</text><text>待付最晚15分钟、时段开始或券截止时关闭。未付取消/超时释放；全额退款确认成功后返原券，不续期。</text><button :disabled="blocked||!state.context.can_create" @tap="flow.confirm">{{ state.confirmed?'已确认优惠与实付':'确认优惠与实付' }}</button><button class="reservation-primary" :disabled="blocked||!state.confirmed||!state.context.can_create" @tap="flow.save">复核并创建用券预约</button></view>
  </template>
 </template>
 <text v-if="state.busy">正在核对或创建…</text><text v-if="state.message" role="status">{{ state.message }}</text><button v-if="state.kind==='unauthorized'" @tap="login">重新登录</button><button @tap="uni.navigateTo({url:'/pages/order/list'})">我的订单</button><button :disabled="blocked" @tap="uni.navigateBack()">返回选择</button>
 </template></view></template>
