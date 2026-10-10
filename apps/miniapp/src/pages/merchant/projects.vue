<script setup>
import { reactive, ref, watch } from 'vue'
import { onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { merchantSession,clearMerchantSession } from '../../services/merchant-session.js'
import { quotesApi } from '../../services/merchant-quotes.js'
import { initialQuoteListState,createQuoteListFlow,initialQuoteFormState,createQuoteFormFlow } from '../../services/quote-flow.js'
import { imageRequestKey } from '../../services/private-images.js'
const list=reactive(initialQuoteListState()),catalog=reactive(initialQuoteListState()),form=reactive(initialQuoteFormState())
const token=()=>merchantSession.accessToken
const own=createQuoteListFlow({state:list,token,fetchPage:quotesApi.ownList})
const standards=createQuoteListFlow({state:catalog,token,fetchPage:quotesApi.standards})
const edit=createQuoteFormFlow({state:form,api:quotesApi,token,newKey:imageRequestKey})
const editing=ref(false);let visible=false
function reset(){own.reset();standards.reset();edit.reset();editing.value=false}
watch(token,()=>{reset();if(visible && token()){own.load();standards.load()}},{flush:'sync'})
onShow(()=>{visible=true;if(token()){own.load();standards.load()}})
onHide(()=>{visible=false;own.suspend();standards.suspend();edit.suspend()})
onUnload(()=>{visible=false;reset()})
watch(()=>form.saved,result=>{if(result && visible)own.load()})
function login(){clearMerchantSession();uni.navigateTo({url:'/pages/merchant/index'})}
function start(row){edit.reset();editing.value=true;if(row){edit.edit(row);if(!row.available)form.status=0}}
function pick(event){const row=catalog.items[Number(event.detail.value)];if(row){form.projectId=row.id;form.name=row.project_name}}
function done(){edit.reset();editing.value=false}
</script>
<template>
  <view class="page">
    <text class="eyebrow">商家端</text><text class="title">本店服务与报价</text>
    <view v-if="!merchantSession.accessToken" class="panel"><text>请先登录门店账号。</text><button @tap="login">前往门店登录</button></view>
    <template v-else>
      <button @tap="uni.navigateTo({url:'/pages/merchant/slots'})">管理预约时段</button>
      <button class="primary" :disabled="form.busy" @tap="start()">新增选品</button>
      <view v-if="editing" class="panel" data-testid="quote-form">
        <text class="heading">{{ form.name || '选择标准项目' }}</text>
        <template v-if="!form.projectId">
          <picker :range="catalog.items" range-key="project_name" :disabled="catalog.busy || !catalog.items.length" @change="pick"><view class="field">选择服务项目</view></picker>
          <button v-if="catalog.items.length<catalog.total" :disabled="catalog.busy" @tap="standards.load(true)">加载更多标准项目</button>
          <text v-if="catalog.loaded && !catalog.items.length">暂无可选标准项目。</text>
        </template>
        <text v-if="catalog.busy" role="status">正在加载标准项目…</text>
        <view v-if="catalog.message"><text class="error">{{ catalog.message }}</text><button :disabled="catalog.busy" @tap="standards.retry">重试标准项目</button></view>
        <text class="copy">报价（元，填写两位小数）</text>
        <input v-model="form.price" class="field" :disabled="form.busy || !!form.saved || !form.available" maxlength="11" placeholder="例如 299.00" />
        <picker :range="form.available?['下架','上架']:['下架']" :value="form.status" :disabled="form.busy || !!form.saved" @change="form.status=Number($event.detail.value)"><view class="field">{{ form.status===1?'上架':'下架' }}</view></picker>
        <text v-if="!form.available" class="copy">标准项目已停用，仅可按原价下架。</text>
        <text v-if="form.message" :class="form.saved?'success':'error'" role="status">{{ form.message }}</text>
        <button v-if="!form.saved" class="primary" :disabled="form.busy || !form.projectId" :loading="form.busy" @tap="edit.save">保存报价</button>
        <button :disabled="form.busy" @tap="done">{{ form.saved?'返回本店列表':'取消编辑' }}</button>
      </view>
      <text v-if="list.busy" class="copy" role="status">正在加载本店服务…</text>
      <view v-if="list.message" class="panel"><text class="error">{{ list.message }}</text>
        <button v-if="['unauthorized','forbidden'].includes(list.failureKind)" @tap="login">重新登录</button><button v-else :disabled="list.busy" @tap="own.retry">重试本店服务</button>
      </view>
      <view v-if="list.loaded && !list.items.length && !list.busy" class="panel">本店还没有选品，可先添加标准项目。</view>
      <view v-for="row in list.items" :key="row.merchant_project_id" class="panel" :data-testid="`merchant-quote-${row.standard_project_id}`">
        <text class="heading">{{ row.project_name }}</text><text class="price">本店报价 ¥{{ row.price }}</text>
        <text class="copy">{{ row.status===1?'已上架':'已下架' }} · 版本 {{ row.version }}{{ row.available?'':' · 标准项目不可售' }}</text>
        <button :disabled="form.busy" @tap="start(row)">维护报价</button>
      </view>
      <button v-if="list.items.length<list.total" :disabled="list.busy" @tap="own.load(true)">加载更多本店服务</button>
    </template>
  </view>
</template>
<style scoped>
.page { min-height:100vh;box-sizing:border-box;padding:52rpx 32rpx 96rpx;background:#f2f3f5; }.eyebrow { display:block;color:#008f24;font-size:23rpx; }.title { display:block;font-size:42rpx;font-weight:700;margin:14rpx 0 28rpx;color:#1d2129; }
.panel { display:flex;flex-direction:column;background:white;padding:30rpx;border-radius:24rpx;margin:24rpx 0; }.heading { font-size:30rpx;font-weight:600;color:#1d2129; }.copy { font-size:25rpx;color:#4e5969;margin-top:16rpx;line-height:38rpx; }.price { margin-top:16rpx;color:#008f24;font-size:29rpx; }.field { padding:22rpx;background:#f2f3f5;border-radius:12rpx;margin-top:20rpx;font-size:28rpx; }.error { color:#b42318;margin-top:16rpx; }.success { color:#008f24;margin-top:16rpx; }
button { margin:22rpx 0 0;background:#eef8f0;color:#008f24;font-size:27rpx;border-radius:14rpx; }button::after { border:0; }.primary { background:#00b42a;color:white; }
</style>
