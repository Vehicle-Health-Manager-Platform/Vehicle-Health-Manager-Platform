<script setup>
import { reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession } from '../../services/owner-session.js'
import { imageRequestKey } from '../../services/private-images.js'
import { createPublicationFlow, initialPublicationState } from '../../services/public-ratings.js'
const state = reactive(initialPublicationState())
let order = 0, visible = false
const flow = createPublicationFlow({ state, token: () => ownerSession.accessToken, order: () => order, newKey: imageRequestKey,
  confirm: publish => new Promise(resolve => uni.showModal({ title: publish ? '确认公开星级' : '撤销公开星级', content: publish ? '向登录车主公开评分和评价日期，并计入商家平均评分。不会公开姓名、订单、正文或照片。你可以随时撤销。' : '撤销后停止公开，不删除本人原评价；已被查看的信息无法收回。', success: r => resolve(!!r.confirm), fail: () => resolve(false) })) })
onLoad(q => { order = Number(q?.id) || 0 })
onShow(() => { visible = true; flow.load() })
onHide(() => { visible = false; flow.reset() })
onUnload(() => { visible = false; flow.reset() })
watch(() => ownerSession.accessToken, () => { flow.reset(); if (visible) flow.load() }, { flush: 'sync' })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">公开星级授权</text>
    <text class="reservation-copy">仅公开评分和评价日期，正文、照片和身份信息保持私有。公开星级不代表平台质量认证。</text>
    <view v-if="state.value" class="reservation-panel">
      <text>公开授权：{{ state.value.consented ? '已授权' : '未授权' }}</text>
      <text>当前展示：{{ state.value.visible ? '公开并计入平均评分' : '未公开' }}</text>
      <text v-if="!state.value.can_publish">{{ state.value.unavailable_reason === 'TEST_ORDER' ? '测试评价不能公开或计入评分。' : '当前履约或资金来源未满足公开条件。你仍可撤销已有授权。' }}</text>
      <button v-if="!state.value.consented && state.value.can_publish" :disabled="state.busy" @tap="flow.save(true)">单独授权公开星级</button>
      <button v-if="state.value.consented" :disabled="state.busy" @tap="flow.save(false)">撤销公开授权</button>
      <button v-if="state.retryPublish !== null" :disabled="state.busy" @tap="flow.save(state.retryPublish)">按原请求重试</button>
    </view>
    <text v-if="state.busy">正在核对授权状态…</text>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button :disabled="state.busy" @tap="flow.load()">刷新当前授权</button>
    <button @tap="uni.navigateBack()">返回本人评价</button>
  </view>
</template>
