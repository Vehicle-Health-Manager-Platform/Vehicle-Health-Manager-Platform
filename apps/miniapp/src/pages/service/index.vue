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
/* 分类筛选：药丸芯片，选中态实色主绿 + 对勾前缀由 aria 表达，不止靠颜色。 */
.categories { display: flex; flex-wrap: wrap; gap: 14rpx; margin-bottom: 26rpx; }
.category { margin: 0; padding: 0 28rpx; font-size: 25rpx; font-weight: 600; line-height: 66rpx; color: #454a41; border: 1rpx solid rgba(26, 29, 24, 0.3); background: #fcfbf8; border-radius: 999rpx; box-sizing: border-box; transition: transform 160ms cubic-bezier(0.16, 1, 0.3, 1); }
.category:active { transform: translateY(2rpx); }
.category.selected { border-color: #0b6b43; background: #0b6b43; color: #fcfbf8; }
/* 项目卡：左主色条 + 等宽参考价读数。 */
.project { display: flex; flex-direction: column; align-items: flex-start; text-align: left; line-height: normal; padding: 30rpx; margin: 0 0 22rpx; border: 1rpx solid rgba(26, 29, 24, 0.14); border-left: 6rpx solid #0b6b43; border-radius: 20rpx; background: #fcfbf8; box-shadow: 0 2rpx 10rpx rgba(26, 29, 24, 0.07); }
.name { font-size: 32rpx; font-weight: 700; color: #1a1d18; letter-spacing: 1rpx; }.copy { margin-top: 14rpx; color: #63685e; font-size: 25rpx; }
.price { margin-top: 20rpx; color: #074d30; font-size: 28rpx; font-weight: 600; font-variant-numeric: tabular-nums; }
.panel { display: flex; flex-direction: column; gap: 16rpx; padding: 28rpx; margin-bottom: 26rpx; font-size: 25rpx; line-height: 1.65; border: 1rpx solid rgba(26, 29, 24, 0.14); border-radius: 20rpx; background: #fcfbf8; box-shadow: 0 2rpx 10rpx rgba(26, 29, 24, 0.07); }
.panel input, .panel textarea { box-sizing: border-box; width: 100%; padding: 20rpx; background: #efede6; border: 1rpx solid rgba(26, 29, 24, 0.14); border-radius: 12rpx; color: #1a1d18; }
.panel input { height: 84rpx; }
.panel textarea { min-height: 160rpx; }
.notice { color: #63685e; padding: 24rpx 8rpx; font-size: 27rpx; }.error { color: #b3261e; font-size: 26rpx; }
.action { margin: 22rpx 0; border: 1rpx solid transparent; background: #e9f2ec; color: #074d30; font-size: 27rpx; font-weight: 600; border-radius: 12rpx; }button::after { border: 0; }
</style>
