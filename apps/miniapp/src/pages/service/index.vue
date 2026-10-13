<script setup>
import { reactive, watch, ref } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import OwnerTabShell from '../../components/OwnerTabShell.vue'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { serviceCatalogApi, serviceCategories } from '../../services/service-catalog.js'
import { initialServiceListState, createServiceListFlow } from '../../services/service-catalog-flow.js'
import { serviceSearchApi } from '../../services/service-search.js'
import { symptomHandoff } from '../../services/symptom-handoff.js'

const state = reactive(initialServiceListState())
const search=ref(''),query=ref(''),symptom=ref(''),searchMessage=ref('')
const flow = createServiceListFlow({ state, api: {list:(token,category,page)=>query.value?serviceSearchApi.list(token,query.value,category,page):serviceCatalogApi.list(token,category,page)}, token: () => ownerSession.accessToken })
function find(clear=false){const value=clear?'':search.value.trim();if(!clear&&(!value||[...value].length>80)){searchMessage.value='请填写1–80字目录关键词';return}const category=state.category;flow.reset();state.category=category;query.value=value;if(clear)search.value='';searchMessage.value='';flow.load()}
function diagnose(){const route=symptomHandoff.route(symptom.value);if(!route){searchMessage.value='请登录并填写1–300字症状，勿填写身份或联系方式';return}uni.navigateTo({url:route})}
let visible = false
watch(() => ownerSession.accessToken, () => { flow.reset();query.value='';search.value='';symptom.value='';searchMessage.value=''; if (visible && ownerSession.accessToken) flow.load() }, { flush: 'sync' })
onShow(() => { visible = true; if (ownerSession.accessToken) flow.load() })
onHide(() => { visible = false; flow.suspend();query.value='';search.value='';symptom.value='';searchMessage.value='' })
onUnload(() => { visible = false; flow.reset() })
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function detail(item) { uni.navigateTo({ url: `/pages/service/detail?id=${item.id}` }) }
</script>

<template>
  <OwnerTabShell label="服务" title="找到合适的服务" description="查看服务内容与参考价格，了解爱车养护项目。" next-action="正在加载服务项目。" business-ready>
    <template #content>
      <view class="panel">
        <text>当前目录文字搜索（名称/服务内容）</text><input v-model="search" maxlength="160" placeholder="1–80字关键词，勿填身份资料" />
        <button class="action" :disabled="state.busy" @tap="find()">搜索当前有效项目</button><button v-if="query" class="action" :disabled="state.busy" @tap="find(true)">恢复分类目录</button>
        <text>描述症状，带入AI方案核对（1–300字）</text><textarea v-model="symptom" maxlength="600" placeholder="请勿填写姓名、地址、联系方式或车辆标识" />
        <button class="action" @tap="diagnose">带入AI方案并核对用途</button><text>仅先带入问题；生成时再确认第三方AI用途。目录文字匹配不能证明诊断。</text><text v-if="searchMessage" role="status">{{searchMessage}}</text>
      </view>
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
.panel{display:flex;flex-direction:column;gap:16rpx;font-size:25rpx;line-height:38rpx}.panel input,.panel textarea{box-sizing:border-box;width:100%;padding:20rpx;background:#f7f8fa;border:1rpx solid #d9dfe8;border-radius:12rpx}.panel input{height:84rpx}.panel textarea{min-height:160rpx}
</style>
