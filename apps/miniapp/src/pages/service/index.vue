<script setup>
import { reactive, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import OwnerTabShell from '../../components/OwnerTabShell.vue'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { serviceCatalogApi, serviceCategories } from '../../services/service-catalog.js'
import { initialServiceListState, createServiceListFlow } from '../../services/service-catalog-flow.js'

const state = reactive(initialServiceListState())
const flow = createServiceListFlow({ state, api: serviceCatalogApi, token: () => ownerSession.accessToken })
let visible = false
watch(() => ownerSession.accessToken, () => { flow.reset(); if (visible && ownerSession.accessToken) flow.load() }, { flush: 'sync' })
onShow(() => { visible = true; if (ownerSession.accessToken) flow.load() })
onHide(() => { visible = false; flow.suspend() })
onUnload(() => { visible = false; flow.reset() })
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function detail(item) { uni.navigateTo({ url: `/pages/service/detail?id=${item.id}` }) }
</script>

<template>
  <OwnerTabShell label="服务" title="找到合适的服务" description="查看服务内容与参考价格，了解爱车养护项目。" next-action="正在加载服务项目。" business-ready>
    <template #content>
      <view class="categories" aria-label="服务分类">
        <button v-for="(name, index) in serviceCategories" :key="index" :class="['category', { selected: state.category === index }]"
          :data-testid="`service-category-${index}`" :aria-pressed="state.category === index" @tap="flow.select(index)">{{ name }}</button>
      </view>
      <view v-if="state.busy" class="notice" role="status">正在加载服务项目…</view>
      <view v-if="state.message" class="panel" role="status">
        <text class="error">{{ state.message }}</text>
        <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" class="action" @tap="login">重新登录</button>
        <button v-else class="action" :disabled="state.busy" @tap="flow.retry">重试加载服务</button>
      </view>
      <view v-if="state.loaded && !state.items.length && !state.busy" class="notice">该分类暂无服务项目。</view>
      <button v-for="item in state.items" :key="item.id" class="project" :data-testid="`service-project-${item.id}`" @tap="detail(item)">
        <text class="name">{{ item.project_name }}</text>
        <text class="copy">{{ serviceCategories[item.category] }} · 查看服务详情</text>
        <text class="price">参考价 ¥{{ item.base_price_low }}–{{ item.base_price_high }}</text>
      </button>
      <button v-if="state.loaded && state.items.length < state.total" class="action" :disabled="state.busy" @tap="flow.load(true)">加载更多服务</button>
    </template>
  </OwnerTabShell>
</template>

<style scoped>
.categories { display: flex; flex-wrap: wrap; gap: 14rpx; margin-bottom: 24rpx; }
.category { margin: 0; padding: 0 24rpx; font-size: 25rpx; line-height: 66rpx; color: #4e5969; background: #fff; border-radius: 16rpx; }
.category.selected { background: #00b42a; color: #fff; }
.project { display: flex; flex-direction: column; text-align: left; line-height: normal; padding: 30rpx; margin: 0 0 22rpx; border-radius: 24rpx; background: #fff; }
.name { font-size: 32rpx; font-weight: 650; color: #1d2129; }.copy { margin-top: 16rpx; color: #4e5969; font-size: 25rpx; }
.price { margin-top: 20rpx; color: #008f24; font-size: 28rpx; }.panel { padding: 28rpx; margin-bottom: 24rpx; background: #fff; border-radius: 24rpx; }
.notice { color: #4e5969; padding: 24rpx 0; font-size: 27rpx; }.error { color: #b42318; font-size: 26rpx; }
.action { margin: 22rpx 0; background: #eef8f0; color: #008f24; font-size: 27rpx; border-radius: 14rpx; }button::after { border: 0; }
</style>
