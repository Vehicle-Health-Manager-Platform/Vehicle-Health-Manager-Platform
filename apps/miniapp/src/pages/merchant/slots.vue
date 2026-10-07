<script setup>
import {reactive,ref,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {merchantSession,clearMerchantSession} from '../../services/merchant-session.js'
import {quotesApi} from '../../services/merchant-quotes.js'
import {initialQuoteListState,createQuoteListFlow} from '../../services/quote-flow.js'
import {imageRequestKey} from '../../services/private-images.js'
import {reservationsApi as api,ReservationError,initialReservationWriteState,createReservationWriteFlow,chinaDate,displayTime} from '../../services/reservations.js'
const state=reactive(initialQuoteListState()),projects=reactive(initialQuoteListState()),write=reactive(initialReservationWriteState()),closing=reactive(initialReservationWriteState())
const form=reactive({project:0,day:chinaDate(new Date(Date.now()+86400000)),start:'10:00',end:'11:00',capacity:'1'}),closeId=ref(0);let visible=false
const token=()=>merchantSession.accessToken,flow=createQuoteListFlow({state,token,fetchPage:api.ownSlots}),catalog=createQuoteListFlow({state:projects,token,fetchPage:quotesApi.ownList})
const publish=createReservationWriteFlow({state:write,token,newKey:imageRequestKey,body:()=>{if(!form.project||!/^([1-9]|[1-9]\d|100)$/.test(form.capacity))throw new ReservationError('invalid','请选择在售项目，容量填写1–100');return {standard_project_id:form.project,starts_at:`${form.day}T${form.start}:00+08:00`,ends_at:`${form.day}T${form.end}:00+08:00`,capacity:Number(form.capacity)}},request:api.publish})
const close=createReservationWriteFlow({state:closing,token,newKey:imageRequestKey,body:()=>({slot_id:closeId.value}),request:(t,b,k)=>api.close(t,b.slot_id,k)})
function reset(){flow.reset();catalog.reset();publish.reset();close.reset();form.project=0;closeId.value=0}
function login(){clearMerchantSession();uni.navigateTo({url:'/pages/merchant/index'})}
function closeSlot(row){uni.showModal({title:'关闭预约时段',content:'关闭后停止新预约，已有订单保持原约定。',success:r=>{if(r.confirm){if(closeId.value!==row.slot_id)close.reset();closeId.value=row.slot_id;close.save()}}})}
watch(()=>write.saved,r=>{if(r&&visible)flow.load()});watch(()=>closing.saved,r=>{if(r&&visible)flow.load()});watch(token,()=>{reset();if(visible&&token()){flow.load();catalog.load()}},{flush:'sync'})
onShow(()=>{visible=true;if(token()){flow.load();catalog.load()}});onHide(()=>{visible=false;flow.suspend();catalog.suspend();publish.suspend();close.suspend()});onUnload(()=>{visible=false;reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">本店预约时段</text><button v-if="!merchantSession.accessToken" @tap="login">前往商家登录</button><template v-else>
  <view class="reservation-panel"><text class="reservation-heading">发布项目时段</text><text>未来30天内、同一天；同项目时段不可重叠。发布后时间和容量不可编辑。</text>
    <button v-for="row in projects.items.filter(r=>r.available&&r.status===1)" :key="row.standard_project_id" :disabled="write.busy||!!write.saved" :data-testid="`slot-project-${row.standard_project_id}`" @tap="form.project=row.standard_project_id">{{form.project===row.standard_project_id?'✓ ':''}}{{row.project_name}}</button>
    <text v-if="projects.loaded&&!projects.items.some(r=>r.available&&r.status===1)">暂无在售项目，请先维护本店报价。</text><text v-if="projects.message">{{projects.message}}</text><button v-if="projects.message" @tap="catalog.retry">重试本店选品</button><button v-if="projects.items.length<projects.total" @tap="catalog.load(true)">加载更多本店选品</button>
    <picker mode="date" :value="form.day" :start="chinaDate()" :end="chinaDate(new Date(Date.now()+30*86400000))" :disabled="write.busy||!!write.saved" @change="form.day=$event.detail.value"><view class="reservation-field">日期 {{form.day}}</view></picker>
    <picker mode="time" :value="form.start" :disabled="write.busy||!!write.saved" @change="form.start=$event.detail.value"><view class="reservation-field">开始 {{form.start}}</view></picker><picker mode="time" :value="form.end" :disabled="write.busy||!!write.saved" @change="form.end=$event.detail.value"><view class="reservation-field">结束 {{form.end}}</view></picker>
    <text>预约名额（1–100）</text><input v-model="form.capacity" class="reservation-field" type="number" maxlength="3" :disabled="write.busy||!!write.saved" placeholder="预约名额" />
    <text v-if="write.message" role="status">{{write.message}}</text><button v-if="!write.saved" class="reservation-primary" :disabled="write.busy" :loading="write.busy" @tap="publish.save">发布预约时段</button><button v-else @tap="publish.reset">继续发布时段</button>
  </view><text v-if="state.busy">正在加载本店时段…</text><view v-if="state.message" class="reservation-panel"><text>{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">重新登录</button><button v-else @tap="flow.retry">重试本店时段</button></view><view v-if="state.loaded&&!state.items.length&&!state.busy" class="reservation-panel">暂无本店预约时段。</view>
  <text v-if="closing.message">{{closing.message}}</text><view v-for="row in state.items" :key="row.slot_id" class="reservation-panel" :data-testid="`merchant-slot-${row.slot_id}`"><text class="reservation-heading">{{row.project_name}}</text><text>{{displayTime(row.starts_at)}} 至 {{displayTime(row.ends_at)}}</text><text>{{row.open?'开放预约':'已关闭'}} · 剩余 {{row.capacity_left}} / {{row.capacity}}</text><button v-if="row.open" :disabled="closing.busy" @tap="closeSlot(row)">关闭预约时段</button></view><button v-if="state.items.length<state.total" :disabled="state.busy" @tap="flow.load(true)">加载更多本店时段</button>
</template></view></template>
<style src="../../styles/reservations.css"></style>
