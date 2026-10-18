<script setup>
import {reactive,ref,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import CouponTerms from '../../components/CouponTerms.vue'
import {merchantSession,clearMerchantSession} from '../../services/merchant-session.js'
import {initialCouponState,createCouponFlow,couponLocalTime,couponUtcTime} from '../../services/coupons.js'
import {quotesApi} from '../../services/merchant-quotes.js'
import {initialQuoteListState,createQuoteListFlow} from '../../services/quote-flow.js'
import {imageRequestKey} from '../../services/private-images.js'
const state=reactive(initialCouponState()),token=()=>merchantSession.accessToken,editing=ref(false),current=ref(null)
const empty=()=>({name:'',type:'DISCOUNT',project_id:0,amount:'',min_spend:'',total_quantity:'',valid_from:'',valid_to:''}),form=reactive(empty())
const dates=reactive({fromDate:'',fromTime:'',toDate:'',toTime:''})
const projects=reactive(initialQuoteListState()),projectFlow=createQuoteListFlow({state:projects,token,fetchPage:quotesApi.ownList})
const flow=createCouponFlow({state,token,mode:'merchant',newKey:imageRequestKey,confirm:({action,row,body})=>new Promise(resolve=>uni.showModal({title:'确认本店券操作',content:action==='status'?(body.status==='PUBLISHED'?'发布后权益、库存和有效期冻结；每人限领一次。':'停止新领取，已领取权益保留。'):`${body.terms.name}；项目 ${body.terms.project_id}；库存 ${body.terms.total_quantity}。优惠由本店承担。`,success:r=>resolve(r.confirm),fail:()=>resolve(false)}))})
let visible=false
watch(()=>state.completed,()=>{if(state.completed){editing.value=false;current.value=null;Object.assign(form,empty())}})
function reset(){flow.reset();projectFlow.reset();editing.value=false;current.value=null;Object.assign(form,empty());Object.assign(dates,{fromDate:'',fromTime:'',toDate:'',toTime:''})}
watch(token,()=>{reset();if(visible&&token()){flow.load(1);projectFlow.load()}},{flush:'sync'})
onShow(()=>{visible=true;if(token()){flow.load(1);projectFlow.load()}});onHide(()=>{visible=false;reset()});onUnload(()=>{visible=false;reset()})
function start(row=null){current.value=row;Object.assign(form,row?{...row.terms,total_quantity:String(row.terms.total_quantity)}:empty());const from=couponLocalTime(form.valid_from),to=couponLocalTime(form.valid_to);Object.assign(dates,{fromDate:from.slice(0,10),fromTime:from.slice(11),toDate:to.slice(0,10),toTime:to.slice(11)});editing.value=true}
function datePart(key,event){dates[key]=event.detail.value;form.valid_from=couponUtcTime(dates.fromDate,dates.fromTime);form.valid_to=couponUtcTime(dates.toDate,dates.toTime)}
function choose(e){const p=projects.items[Number(e.detail.value)];if(p?.available&&p.status===1)form.project_id=p.standard_project_id;else{form.project_id=0;state.message='只能选择本店有效且在售的项目'}}
function type(e){form.type=Number(e.detail.value)===1?'SERVICE':'DISCOUNT';form.amount=form.type==='SERVICE'?'0.00':'';form.min_spend=form.type==='SERVICE'?'0.00':''}
async function save(){if(!/^[1-9][0-9]{0,9}$/.test(form.total_quantity)){state.message='请填写正整数库存';return}const terms={...form,total_quantity:Number(form.total_quantity)};const body=current.value?{expected_revision:current.value.revision,terms}:{terms};await flow.write(current.value?'update':'create',current.value,body);if(!state.retryWrite&&!state.kind)editing.value=false}
function status(row,target){flow.write('status',row,{expected_revision:row.revision,status:target})}
function login(){reset();clearMerchantSession();uni.navigateTo({url:'/pages/merchant/index'})}
</script>
<template><view class="reservation-page"><text class="reservation-title">本店券库存与发行</text>
 <view v-if="!token()"><text>请先登录门店店长账号。</text><button @tap="uni.navigateTo({url:'/pages/merchant/index'})">门店登录</button></view>
 <template v-else><text>限本店指定在售项目；发布后条款冻结，已领券不因停止领取而撤回。</text><button @tap="uni.navigateTo({url:'/pages/merchant/coupon-redeem'})">服务券独立核销与记录</button><button :disabled="state.busy||state.retryWrite" @tap="start()">创建券草稿</button>
 <view v-if="editing" class="reservation-panel">
  <text class="reservation-heading">{{ current?'修改草稿':'新券草稿' }}</text>
  <input v-model="form.name" :disabled="state.busy||state.retryWrite" placeholder="券名称" maxlength="128"/>
  <picker :range="['金额优惠券','指定项目服务兑换券']" :disabled="state.busy||state.retryWrite" @change="type"><view class="field">{{ form.type==='SERVICE'?'服务兑换券':'金额优惠券' }} ›</view></picker>
  <picker :range="projects.items" range-key="project_name" :disabled="projects.busy||state.busy||state.retryWrite" @change="choose"><view class="field">{{ form.project_id?`已选标准项目 ${form.project_id}`:'选择本店在售项目' }} ›</view></picker>
  <text v-if="projects.message">{{ projects.message }}</text><button :disabled="projects.busy" @tap="projectFlow.load()">刷新本店项目</button><button v-if="projects.items.length<projects.total" :disabled="projects.busy" @tap="projectFlow.load(true)">更多本店项目</button>
  <template v-if="form.type==='DISCOUNT'"><input v-model="form.amount" :disabled="state.busy||state.retryWrite" placeholder="优惠金额，两位小数，如 5.00"/><input v-model="form.min_spend" :disabled="state.busy||state.retryWrite" placeholder="最低消费，两位小数且不小于优惠"/></template>
  <input v-model="form.total_quantity" type="number" :disabled="state.busy||state.retryWrite" placeholder="发行库存，正整数"/>
  <text>有效期按北京时间填写。</text>
  <picker mode="date" :value="dates.fromDate" :disabled="state.busy||state.retryWrite" @change="datePart('fromDate',$event)"><view class="field">生效日期：{{ dates.fromDate||'请选择' }}</view></picker>
  <picker mode="time" :value="dates.fromTime" :disabled="state.busy||state.retryWrite" @change="datePart('fromTime',$event)"><view class="field">生效时间：{{ dates.fromTime||'请选择' }}</view></picker>
  <picker mode="date" :value="dates.toDate" :disabled="state.busy||state.retryWrite" @change="datePart('toDate',$event)"><view class="field">截止日期：{{ dates.toDate||'请选择' }}</view></picker>
  <picker mode="time" :value="dates.toTime" :disabled="state.busy||state.retryWrite" @change="datePart('toTime',$event)"><view class="field">截止时间：{{ dates.toTime||'请选择' }}</view></picker>
  <button class="reservation-primary" :disabled="state.busy||state.retryWrite" @tap="save">保存草稿</button><button :disabled="state.busy||state.retryWrite" @tap="editing=false">关闭编辑</button>
 </view>
 <text v-if="state.busy">正在处理…</text><text v-else-if="state.loaded&&!state.rows.length">本店还没有新发行券。</text>
 <view v-for="row in state.rows" :key="row.coupon_id" class="reservation-panel"><CouponTerms :row="row"/><template v-if="row.status==='DRAFT'"><button :disabled="state.busy||state.retryWrite" @tap="start(row)">修改草稿</button><button :disabled="state.busy||state.retryWrite" @tap="status(row,'PUBLISHED')">发布并冻结条款</button></template><button v-if="row.status==='PUBLISHED'" :disabled="state.busy||state.retryWrite" @tap="status(row,'STOPPED')">停止新领取</button></view>
 <text v-if="state.message" role="status">{{ state.message }}</text><button v-if="state.kind==='unauthorized'" @tap="login">重新登录门店账号</button><button v-if="state.retryWrite" :disabled="state.busy" @tap="flow.retry">重试原操作</button><button :disabled="state.busy||state.retryWrite" @tap="flow.load(1)">刷新本店券</button>
 <button v-if="state.page>1" :disabled="state.busy||state.retryWrite" @tap="flow.load(state.page-1)">上一页</button><button v-if="state.page*20<state.total" :disabled="state.busy||state.retryWrite" @tap="flow.load(state.page+1)">下一页</button></template>
</view></template>
<style scoped>input,.field{margin-top:18rpx;padding:18rpx;background:#efede6;border-radius:12rpx;font-size:26rpx}input{min-height:56rpx}</style>
