<script setup>
import { reactive, ref, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { planBookingHandoff } from '../../services/plan-booking-handoff.js'
import { createPlanBookingFlow, initialPlanBookingState } from '../../services/plan-booking.js'
import { imageRequestKey } from '../../services/private-images.js'
import { chinaDate, displayTime } from '../../services/reservations.js'
import { applicabilityLabels } from '../../services/vehicle-plan-evidence.js'
const selected = ref(null), state = reactive(initialPlanBookingState())
let visible = false
const flow = createPlanBookingFlow({ state, token: () => selected.value?.actor === ownerSession.accessToken ? ownerSession.accessToken : '', selection: () => selected.value || { project: 0, vehicle: 0 }, newKey: imageRequestKey })
onLoad(q => { const project = /^\d+$/.test(q?.project_id || '') ? Number(q.project_id) : 0; selected.value = planBookingHandoff.take(project) })
onShow(() => { visible = true; if (selected.value && !state.uncertain) flow.loadQuotes() })
onHide(() => { visible = false; flow.suspend() })
onUnload(() => { visible = false; flow.reset(); selected.value = null })
watch(() => ownerSession.accessToken, () => { flow.reset(); selected.value = null }, { flush: 'sync' })
watch(() => state.saved, r => { if (visible && r) uni.redirectTo({ url: `/pages/order/detail?id=${r.order_id}` }) })
function login() { flow.reset(); selected.value = null; clearOwnerSession(); uni.navigateTo({url:'/pages/owner/index'}) }
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">按本车方案预约</text>
    <text class="reservation-copy">选择当前商家报价与时段，提交前再次复核。本车资料只在平台内使用，方案不锁定价格或名额。</text>
    <text v-if="!selected">车辆与项目交接已失效，请返回方案页重新选择。</text>
    <template v-else>
      <view v-if="state.uncertain" class="reservation-panel">
        <text>创建结果尚未确定。请用原请求取得回执；离开后先查看我的订单，避免重复预约。</text>
        <button :disabled="state.busy" @tap="flow.save()">用原请求重试取得回执</button>
      </view>
      <template v-else>
        <view class="reservation-panel">
          <text class="reservation-heading">已带入方案所选车辆</text>
          <text>选择商家后核对当前车型、项目与报价。</text>
          <button :disabled="state.busy" @tap="flow.loadQuotes(false,'price_asc')">当前报价从低到高</button>
          <button :disabled="state.busy" @tap="flow.loadQuotes(false,'price_desc')">当前报价从高到低</button>
          <button :disabled="state.busy" @tap="flow.loadQuotes(false,'rating_desc')">按有效公开星级从高到低</button>
          <text v-if="state.loaded && !state.quotes.length">暂无在售报价，可手动选择其他服务。</text>
          <view v-for="q in state.quotes" :key="q.merchant_project_id" class="booking-row">
            <text>{{q.merchant_name}} · {{q.address}}</text><text>当前列表价 ¥{{q.price}}</text>
            <text v-if="q.rating_summary">{{q.rating_summary.average_rating===null?'暂无有效公开星级':q.rating_summary.average_rating+' 星 · '+q.rating_summary.count+' 个有效公开样本'}}</text>
            <button :disabled="state.busy" @tap="flow.chooseQuote(q)">{{state.quoteId===q.merchant_project_id?'已选 · ':''}}读取本车适用性与当前时段</button>
          </view>
          <button v-if="state.loaded && state.quotes.length<state.total" :disabled="state.busy" @tap="flow.loadQuotes(true)">加载更多当前报价</button>
        </view>
        <picker v-if="state.quoteId" mode="date" :value="state.day" :start="chinaDate()" :end="chinaDate(new Date(Date.now()+30*86400000))" :disabled="state.busy" @change="flow.chooseDay($event.detail.value)"><view class="reservation-field">{{state.day}} · 选择日期</view></picker>
        <view v-if="state.context" class="reservation-panel">
          <text class="reservation-heading">{{state.context.quote.project_name}} · {{state.context.quote.merchant_name}}</text>
          <text>{{state.context.evidence.vehicle.model_name || '有效车型未知'}}（本人登记，尚未核验）</text>
          <text>{{applicabilityLabels[state.context.evidence.applicability[0].verdict]}}</text>
          <text v-if="state.context.evidence.applicability[0].source_reference">来源：{{state.context.evidence.applicability[0].source_reference}} · 规则版本 {{state.context.evidence.applicability[0].rule_version}}</text>
          <text>来源为运营声明，需人工核对；该提示不证明技术必做。</text>
          <text class="reservation-price">当前报价 ¥{{state.context.quote.price}} · 版本 {{state.context.quote.version}}</text>
          <text v-if="!state.context.slots.items.length">当天暂无可用时段，可更换日期或商家。</text>
          <button v-for="s in state.context.slots.items" :key="s.slot_id" :disabled="state.busy || state.context.evidence.applicability[0].verdict==='NOT_APPLICABLE'" @tap="flow.chooseSlot(s)">{{state.slot===s.slot_id?'已选 · ':''}}{{displayTime(s.starts_at)}} 至 {{displayTime(s.ends_at)}} · 剩余 {{s.capacity_left}}</button>
          <button v-if="state.context.slots.items.length<state.context.slots.total" :disabled="state.busy" @tap="flow.loadContext(true)">加载更多时段</button>
          <button :disabled="state.busy || state.context.evidence.applicability[0].verdict==='NOT_APPLICABLE'" @tap="flow.confirm()">{{state.confirmed?'已确认 · ':''}}核对本车适用性提示并确认当前报价</button>
          <button :disabled="state.busy || !state.confirmed || !state.slot || state.context.evidence.applicability[0].verdict==='NOT_APPLICABLE'" @tap="flow.save()">复核并创建待支付预约</button>
        </view>
        <button v-if="state.quoteId" :disabled="state.busy" @tap="flow.loadContext()">刷新本车、报价与时段</button>
      </template>
    </template>
    <text v-if="state.busy" role="status">正在读取或复核预约信息…</text>
    <text v-if="state.message" role="status">{{state.message}}</text>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录车主账号</button>
    <button @tap="uni.navigateTo({url:'/pages/order/list'})">核对我的订单</button>
    <button v-if="!state.uncertain && !state.busy" @tap="uni.switchTab({url:'/pages/service/index'})">手动选择其他服务</button>
    <button @tap="uni.navigateBack()">返回方案</button>
  </view>
</template>
<style scoped>.booking-row{display:flex;flex-direction:column;gap:16rpx;padding:24rpx 0;border-top:1px solid #e5e6eb}</style>
