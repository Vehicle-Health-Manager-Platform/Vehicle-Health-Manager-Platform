<script setup>
import { reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { imageRequestKey } from '../../services/private-images.js'
import { createTextFlow, initialTextState, TEXT_STATUS, TEXT_REASONS } from '../../services/public-review-texts.js'
const state = reactive(initialTextState())
let order = 0, visible = false
const flow = createTextFlow({ state, token: () => ownerSession.accessToken, order: () => order, newKey: imageRequestKey,
  confirm: (action, text) => new Promise(resolve => uni.showModal({ title: action === 'SUBMIT' ? '授权审核并公开这段文字' : '撤销公开文字', content: action === 'SUBMIT' ? `确认授权预览文字经人工审核后向登录车主公开，并展示发布日期：\n${text}` : '撤销后停止公开并清除当前公开文字。原私人评价和星级授权不变，已查看的信息无法收回。', success: r => resolve(!!r.confirm), fail: () => resolve(false) })) })
function login() { flow.reset(); clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
onLoad(q => { order = Number(q?.id) || 0 })
onShow(() => { visible = true; flow.load() })
onHide(() => { visible = false; flow.reset() })
onUnload(() => { visible = false; flow.reset() })
watch(() => ownerSession.accessToken, () => { flow.reset(); if (visible) flow.load() }, { flush: 'sync' })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">独立公开评价文字</text>
    <text class="reservation-copy">请单独编写服务体验，不要填写姓名、地址、联系方式、车牌或证件。原私人评价和照片不会被复制或公开，星级需另行授权。</text>
    <view v-if="state.value" class="reservation-panel">
      <text>审核状态：{{ TEXT_STATUS[state.value.status] }}</text>
      <text>当前公开：{{ state.value.visible ? '正在展示获批文字' : '未公开' }}</text>
      <button :disabled="state.busy" @tap="uni.navigateTo({url:`/pages/order/review-summary-consent?id=${order}`})">管理独立AI用途授权</button>
      <text v-if="state.value.reason_code">驳回理由：{{ TEXT_REASONS[state.value.reason_code] }}</text>
      <text v-if="!state.value.can_submit">{{ state.value.unavailable_reason === 'DISABLED' ? '公开文本投稿与审核尚未开启。' : '当前履约或资金来源不满足公开条件。' }}已授权文字仍可撤销。</text>
      <template v-if="state.value.can_submit">
        <text>独立公开文本（1–300字）</text>
        <textarea :value="state.draft" :disabled="state.busy" maxlength="600" class="reservation-field text-area" placeholder="只描述服务体验和处理结果" @input="flow.edit($event.detail.value)" />
        <text>{{ [...state.draft].length }} / 300 字</text>
        <button :disabled="state.busy || !state.draft.trim() || [...state.draft].length>300" @tap="flow.preview()">生成脱敏预览</button>
        <view v-if="state.preview" class="preview">
          <text class="reservation-heading">即将授权的确切文字</text>
          <text class="public-text">{{ state.preview.public_text }}</text>
          <text>规则不能识别所有姓名、地址或变体信息，仍需人工审核。修改后须重新预览；提交后先待审，获批才展示。</text>
          <button :disabled="state.busy" @tap="flow.save('SUBMIT')">确认预览并授权审核</button>
        </view>
      </template>
      <button v-if="!['NONE','WITHDRAWN'].includes(state.value.status)" :disabled="state.busy" @tap="flow.save('WITHDRAW')">撤销文字授权并清除</button>
      <button v-if="state.retryAction" :disabled="state.busy" @tap="flow.save(state.retryAction)">按原请求重试</button>
    </view>
    <text v-if="state.busy">正在处理公开文字…</text>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button v-if="!ownerSession.accessToken || ['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">登录车主账号</button>
    <button :disabled="state.busy" @tap="flow.load()">刷新授权与审核状态</button>
    <button @tap="uni.navigateBack()">返回本人评价</button>
  </view>
</template>
<style scoped>.text-area{box-sizing:border-box;width:100%;min-height:240rpx}.preview{display:flex;flex-direction:column;gap:16rpx;padding:20rpx;background:#e9f2ec}.public-text{white-space:pre-wrap;overflow-wrap:anywhere}</style>
