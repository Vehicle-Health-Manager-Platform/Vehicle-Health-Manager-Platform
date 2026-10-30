<script setup>
import {reactive,computed,watch,onMounted,onBeforeUnmount} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession} from '../services/owner-session.js'
import {vehicleApi} from '../services/vehicles.js'
import {initialVehicleState,createVehicleFlow} from '../services/vehicle-flow.js'
const props=defineProps({label:{type:String,required:true}}),emit=defineEmits(['select'])
const state=reactive(initialVehicleState()),flow=createVehicleFlow({state,api:vehicleApi,token:()=>ownerSession.accessToken,newKey:()=>''})
const years=computed(()=>[...new Set(state.model.map(m=>m.year))]),configs=computed(()=>state.model.filter(m=>m.year===state.year))
watch(()=>ownerSession.accessToken,()=>{flow.reset();emit('select',null);if(ownerSession.accessToken)flow.load('brand')},{flush:'sync'})
watch(()=>[state.brandId,state.seriesId,state.year,state.modelId,state.busy,state.failureKind],()=>{
 const model=state.model.find(m=>m.id===state.modelId)
 emit('select',!state.busy&&!state.failureKind&&model?{brand:state.brand.find(b=>b.id===state.brandId)?.name,series:state.series.find(s=>s.id===state.seriesId)?.name,year:model.year,config:model.config_name||'未命名配置',id:model.id}:null)
})
onShow(flow.resumeCatalog);onHide(flow.suspend);onUnload(flow.reset)
onMounted(flow.resumeCatalog);onBeforeUnmount(flow.reset)
function brand(e){const b=state.brand[Number(e.detail.value)];if(b)flow.selectBrand(b.id)}
function series(e){const s=state.series[Number(e.detail.value)];if(s)flow.selectSeries(s.id)}
</script>
<template>
 <view class="panel"><text class="title">{{props.label}}</text>
  <text class="label">品牌</text><picker :disabled="state.busy||!state.brand.length" :range="state.brand" range-key="name" @change="brand"><view class="field">{{state.brand.find(b=>b.id===state.brandId)?.name||'请选择品牌'}}</view></picker>
  <button v-if="state.brand.length<state.totals.brand" :disabled="state.busy" @tap="flow.load('brand',true)">更多品牌</button>
  <text class="label">车系</text><picker :disabled="state.busy||!state.series.length" :range="state.series" range-key="name" @change="series"><view class="field">{{state.series.find(s=>s.id===state.seriesId)?.name||'请选择车系'}}</view></picker>
  <button v-if="state.series.length<state.totals.series" :disabled="state.busy" @tap="flow.load('series',true)">更多车系</button>
  <text class="label">年款</text><picker :disabled="state.busy||!years.length" :range="years" @change="state.year=years[Number($event.detail.value)]||'';state.modelId=0"><view class="field">{{state.year||'请选择年款'}}</view></picker>
  <text class="label">配置</text><picker :disabled="state.busy||!configs.length" :range="configs.map(m=>m.config_name||'未命名配置')" @change="state.modelId=configs[Number($event.detail.value)]?.id||0"><view class="field">{{configs.find(m=>m.id===state.modelId)?.config_name||'请选择配置'}}</view></picker>
  <button v-if="state.model.length<state.totals.model" :disabled="state.busy" @tap="flow.load('model',true)">更多年款与配置</button>
  <text v-if="state.busy" class="note" role="status">正在加载车型…</text><text v-else-if="state.message" class="note" :class="{error:state.failureKind}" role="status">{{state.message}}</text>
  <button v-if="state.catalogRetry" :disabled="state.busy" @tap="flow.load(state.catalogRetry.kind,state.catalogRetry.more)">重试加载</button>
  <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="uni.navigateTo({url:'/pages/owner/index'})">重新登录</button>
 </view>
</template>
<style scoped>
.panel{padding:28rpx;border:1rpx solid #d6d3c9;border-radius:20rpx;background:#fcfbf8}.title{font-size:31rpx;font-weight:650;color:#1a1d18}.label{display:block;font-size:25rpx;color:#454a41;margin:22rpx 0 12rpx}.field{padding:22rpx 18rpx;border:1rpx solid #d6d3c9;border-radius:12rpx;color:#1a1d18;font-size:27rpx}.note{display:block;margin-top:22rpx;color:#63685e;font-size:24rpx;line-height:1.6}.error{color:#b3261e}button{margin-top:20rpx;background:#e9f2ec;color:#074d30;font-size:25rpx}button::after{border:0}
</style>
