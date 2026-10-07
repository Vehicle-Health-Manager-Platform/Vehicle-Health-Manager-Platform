<script setup>
import { reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import OwnerTabShell from '../../components/OwnerTabShell.vue'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { serviceCatalogApi, serviceCategories } from '../../services/service-catalog.js'
import { initialServiceDetailState, createServiceDetailFlow } from '../../services/service-catalog-flow.js'
import { quotesApi } from '../../services/merchant-quotes.js'
import { initialQuoteListState,createQuoteListFlow } from '../../services/quote-flow.js'
const state = reactive(initialServiceDetailState())
const flow = createServiceDetailFlow({ state, api: serviceCatalogApi, token: () => ownerSession.accessToken })
const quotes=reactive(initialQuoteListState())
const quoteFlow=createQuoteListFlow({state:quotes,token:()=>ownerSession.accessToken,fetchPage:(token,page,sort)=>quotesApi.ownerList(token,id,sort,page)})
let id = 0, visible = false
onLoad(query => { id = /^\d+$/.test(query?.id || '') ? Number(query.id) : 0 })
async function load(){await flow.load(id);if(visible && state.item)quoteFlow.load()}
watch(() => ownerSession.accessToken, () => { flow.reset();quoteFlow.reset(); if (visible && ownerSession.accessToken) load() }, { flush: 'sync' })
onShow(() => { visible = true; if (ownerSession.accessToken) load() })
onHide(() => { visible = false; flow.suspend();quoteFlow.suspend() })
onUnload(() => { visible = false; flow.reset();quoteFlow.reset() })
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function back() { uni.switchTab({ url: '/pages/service/index' }) }
</script>
<template>
  <OwnerTabShell label="服务详情" title="了解服务项目" description="查看服务范围、质量标准与参考价格。" next-action="正在加载项目详情。" business-ready>
    <template #content>
      <view v-if="state.busy" class="notice" role="status">正在加载项目详情…</view>
      <view v-if="state.message" class="panel" role="status">
        <text class="error">{{ state.message }}</text>
        <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" class="action" @tap="login">重新登录</button>
        <button v-else-if="!['missing','invalid'].includes(state.failureKind)" class="action" :disabled="state.busy" @tap="load">重试加载详情</button>
      </view>
      <view v-if="state.item" class="panel" style="margin-top:24rpx" data-testid="owner-quotes">
        <text class="heading">商家报价</text>
        <view><button class="action" :disabled="quotes.busy" @tap="quoteFlow.sort('price_asc')">价格从低到高</button><button class="action" :disabled="quotes.busy" @tap="quoteFlow.sort('price_desc')">价格从高到低</button></view>
        <text v-if="quotes.busy" class="copy" role="status">正在加载商家报价…</text>
        <text v-if="quotes.loaded && !quotes.items.length && !quotes.busy" class="copy">暂无在售商家报价。</text>
        <view v-if="quotes.message"><text class="error">{{ quotes.message }}</text><button v-if="['unauthorized','forbidden'].includes(quotes.failureKind)" class="action" @tap="login">重新登录</button><button v-else class="action" :disabled="quotes.busy" @tap="quoteFlow.retry">重试商家报价</button></view>
        <view v-for="row in quotes.items" :key="row.merchant_project_id" class="quote" :data-testid="`owner-quote-${row.merchant_id}`">
          <text class="name">{{ row.merchant_name }}</text><text class="copy">{{ row.address }}</text><text class="price">商家报价 ¥{{ row.price }}</text><button class="action" @tap="uni.navigateTo({url:`/pages/order/book?id=${row.merchant_project_id}`})">预约此服务</button>
        </view>
        <button v-if="quotes.items.length<quotes.total" class="action" :disabled="quotes.busy" @tap="quoteFlow.load(true)">加载更多商家报价</button>
      </view>
      <view v-if="state.item" class="panel" data-testid="service-detail">
        <text class="name">{{ state.item.project_name }}</text>
        <text class="copy">{{ serviceCategories[state.item.category] }}</text>
        <text class="price">参考价 ¥{{ state.item.base_price_low }}–{{ state.item.base_price_high }}</text>
        <text class="copy">参考价用于了解项目范围，实际价格以商家报价为准。</text>
        <text class="heading">服务内容</text><text class="content">{{ state.item.service_content }}</text>
        <text class="heading">质量标准</text><text class="content">{{ state.item.quality_standard || '暂未提供质量标准' }}</text>
      </view>
      <button class="action" @tap="back">返回服务列表</button>
    </template>
  </OwnerTabShell>
</template>
<style scoped>
.panel { display: flex; flex-direction: column; padding: 32rpx; border-radius: 24rpx; background: #fff; }
.quote { display:flex;flex-direction:column;padding:24rpx 0;border-bottom:1rpx solid #e5e6eb; }
.name { color: #1d2129; font-size: 34rpx; font-weight: 650; }.copy { margin-top: 16rpx; color: #4e5969; font-size: 25rpx; line-height: 38rpx; }
.price { margin-top: 24rpx; font-size: 30rpx; color: #008f24; }.heading { margin-top: 32rpx; font-size: 29rpx; font-weight: 600; color: #1d2129; }
.content { margin-top: 16rpx; color: #4e5969; font-size: 27rpx; line-height: 42rpx; white-space: pre-wrap; overflow-wrap: anywhere; }
.notice { color: #4e5969; padding: 24rpx 0; }.error { color: #b42318; }.action { margin: 24rpx 0; background: #eef8f0; color: #008f24; font-size: 27rpx; border-radius: 14rpx; }button::after { border: 0; }
</style>
