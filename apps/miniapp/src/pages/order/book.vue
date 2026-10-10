<script setup>
import {reactive,ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {vehicleApi} from '../../services/vehicles.js'
import {imageRequestKey} from '../../services/private-images.js'
import {initialQuoteListState,createQuoteListFlow} from '../../services/quote-flow.js'
import {reservationsApi as api,ReservationError,initialReservationWriteState,createReservationWriteFlow,initialReservationReadState,createReservationReadFlow,chinaDate,displayTime} from '../../services/reservations.js'
const quote=reactive(initialReservationReadState()),vehicles=reactive(initialQuoteListState()),slots=reactive(initialQuoteListState()),write=reactive(initialReservationWriteState())
const day=ref(chinaDate()),vehicle=ref(0),slot=ref(0),confirmed=ref(false)
let id=0,visible=false;const token=()=>ownerSession.accessToken
const prices=createReservationReadFlow({state:quote,token,request:t=>api.quote(t,id)})
const cars=createQuoteListFlow({state:vehicles,token,fetchPage:async(t,n)=>{try{const r=await vehicleApi.list(t,n);return {...r,items:r.list}}catch(e){throw new ReservationError(e.kind||'network',e.message)}}})
const times=createQuoteListFlow({state:slots,token,fetchPage:(t,n)=>{if(!quote.value)throw new ReservationError('invalid','请先加载报价');return api.slots(t,quote.value.merchant_id,quote.value.standard_project_id,day.value,n)}})
const submit=createReservationWriteFlow({state:write,token,newKey:imageRequestKey,body:()=>{if(!confirmed.value||!quote.value||!vehicle.value||!slot.value)throw new ReservationError('invalid','请选择本人车辆、时段并确认报价');return {merchant_project_id:id,quote_version_id:quote.value.quote_version_id,vehicle_id:vehicle.value,slot_id:slot.value}},request:api.create,onConflict:async code=>{slot.value=0;if(code===40901){confirmed.value=false;prices.reset();await prices.load()}times.reset();if(visible&&quote.value)await times.load()}})
async function load(){const previous=quote.value?.quote_version_id;await prices.load();if(!visible)return;if(previous&&quote.value?.quote_version_id!==previous){confirmed.value=false;slot.value=0}if(quote.value)times.load();cars.load()}
function reset(){prices.reset();cars.reset();times.reset();submit.reset();confirmed.value=false;vehicle.value=0;slot.value=0}
watch(token,()=>{reset();if(visible&&token())load()},{flush:'sync'})
onLoad(q=>{id=Number(q?.id)||0});onShow(()=>{visible=true;if(token())load()});onHide(()=>{visible=false;prices.suspend();cars.suspend();times.suspend();submit.suspend()});onUnload(()=>{visible=false;reset()})
watch(()=>write.saved,result=>{if(result&&visible)uni.redirectTo({url:`/pages/order/detail?id=${result.order_id}`})})
function changeDay(event){day.value=event.detail.value;slot.value=0;times.reset();if(quote.value)times.load()}
function login(){clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
</script>
<template><view class="reservation-page">
  <text class="reservation-title">预约服务</text><text class="reservation-copy">选择车辆与时段，订单创建后为待支付。</text>
  <button v-if="!ownerSession.accessToken" @tap="login">前往车主登录</button>
  <template v-else>
    <text v-if="quote.busy">正在加载报价…</text>
    <view v-if="quote.message" class="reservation-panel"><text>{{quote.message}}</text><button v-if="['unauthorized','forbidden'].includes(quote.failureKind)" @tap="login">重新登录</button><button v-else @tap="load">重试预约信息</button></view>
    <view v-if="quote.value" class="reservation-panel"><text class="reservation-heading">{{quote.value.project_name}}</text><text>{{quote.value.merchant_name}} · {{quote.value.address}}</text><text class="reservation-price">报价 ¥{{quote.value.price}}</text><button :disabled="write.busy" @tap="confirmed=!confirmed">{{confirmed?'已确认当前报价':'确认当前报价'}}</button></view>
    <view class="reservation-panel"><text class="reservation-heading">本人车辆</text><text v-if="vehicles.busy">正在加载车辆…</text><text v-if="vehicles.loaded&&!vehicles.items.length">请先在档案中添加本人车辆。</text>
      <button v-for="row in vehicles.items" :key="row.vehicle_id" :disabled="write.busy" :data-testid="`booking-vehicle-${row.vehicle_id}`" @tap="vehicle=row.vehicle_id">{{vehicle===row.vehicle_id?'✓ ':''}}{{row.model_name || row.plate_no_masked || '本人车辆'}} · {{row.plate_no_masked || '未填车牌'}}</button>
      <text v-if="vehicles.message">{{vehicles.message}}</text><button v-if="vehicles.message" @tap="cars.retry">重试预约车辆</button><button v-if="vehicles.items.length<vehicles.total" @tap="cars.load(true)">加载更多车辆</button>
    </view>
    <view class="reservation-panel"><text class="reservation-heading">预约时段</text><picker mode="date" :value="day" :start="chinaDate()" :end="chinaDate(new Date(Date.now()+30*86400000))" :disabled="write.busy" @change="changeDay"><view class="reservation-field">{{day}} · 选择日期</view></picker>
      <text v-if="slots.busy">正在加载时段…</text><text v-if="slots.loaded&&!slots.items.length&&!slots.busy">当天暂无可用预约时段。</text>
      <button v-for="row in slots.items" :key="row.slot_id" :disabled="write.busy" :data-testid="`booking-slot-${row.slot_id}`" @tap="slot=row.slot_id">{{slot===row.slot_id?'✓ ':''}}{{displayTime(row.starts_at)}} 至 {{displayTime(row.ends_at)}} · 剩余 {{row.capacity_left}}</button>
      <text v-if="slots.message">{{slots.message}}</text><button v-if="slots.message" @tap="times.retry">重试预约时段</button><button v-if="slots.items.length<slots.total" @tap="times.load(true)">加载更多时段</button>
    </view>
    <view class="reservation-panel"><text>最迟在创建后15分钟或时段开始时关闭未支付订单。支付功能尚未接入。</text><text v-if="write.message" role="status">{{write.message}}</text><button class="reservation-primary" :loading="write.busy" :disabled="write.busy||!!write.saved" @tap="submit.save">创建待支付预约</button></view>
  </template>
</view></template>
