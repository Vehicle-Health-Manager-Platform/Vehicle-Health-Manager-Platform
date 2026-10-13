<script setup>
import { reactive, ref, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { operatorSession, operatorToken, clearOperatorSession } from '../../services/operator-session.js'
import { createTextListFlow, initialTextListState, TEXT_REASONS } from '../../services/public-review-texts.js'
import { imageRequestKey } from '../../services/private-images.js'
const state = reactive(initialTextListState()), reasons = ref({}), codes = Object.keys(TEXT_REASONS), labels = Object.values(TEXT_REASONS)
const flow = createTextListFlow({ state, token: operatorToken, operator: true, newKey: imageRequestKey,
  confirm: (row, decision) => new Promise(resolve => uni.showModal({ title: decision === 'APPROVE' ? '确认完成隐私与内容审核' : '确认驳回', content: decision === 'APPROVE' ? '请确认已人工检查姓名、地址、联系信息、车辆标识及其他隐私，并核对内容是否适合公开。批准将公开车主授权的确切文字，不能代改。' : '按所选固定理由驳回，车主修改后须重新授权和审核。', success: r => resolve(!!r.confirm), fail: () => resolve(false) })) })
let visible = false
function clear() { flow.reset(); reasons.value = {} }
function login() { clear(); clearOperatorSession(); uni.redirectTo({ url: '/pages/operator/login' }) }
function act(row, decision) { const reason = decision === 'APPROVE' ? null : reasons.value[row.review_id]; if (decision === 'REJECT' && !reason) return; flow.moderate(row, decision, reason) }
onShow(() => { visible = true; if (!operatorToken()) { login(); return } flow.load() })
onHide(() => { visible = false; clear() })
onUnload(() => { visible = false; clear() })
watch(() => operatorSession.accessToken, () => { clear(); if (visible && operatorToken()) flow.load() }, { flush: 'sync' })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">公开评价文字审核</text>
    <text class="reservation-copy">只查看车主独立授权的脱敏文本，不查看原私人评价或图片。规则不能覆盖所有语义隐私，批准前必须人工检查；文字不能代改。</text>
    <text v-if="!operatorSession.canReview">当前账号没有审核权限。</text>
    <view v-if="state.value">
      <text v-if="!state.value.items.length">当前没有待审文字。</text>
      <view v-for="row in state.value.items" :key="row.review_id" class="reservation-panel">
        <text class="public-text">{{ row.public_text }}</text>
        <text v-if="!row.can_approve">来源资格已失效，当前不能批准；可驳回。</text>
        <button :disabled="state.busy || !operatorSession.canReview || !row.can_approve" @tap="act(row,'APPROVE')">已人工检查，批准公开</button>
        <picker :range="labels" :disabled="state.busy" @change="reasons[row.review_id]=codes[Number($event.detail.value)]"><view>{{ TEXT_REASONS[reasons[row.review_id]] || '请选择驳回理由' }} ›</view></picker>
        <button :disabled="state.busy || !operatorSession.canReview || !reasons[row.review_id]" @tap="act(row,'REJECT')">按理由驳回 / 原请求重试</button>
      </view>
      <button v-if="state.value.items.length < state.value.total" :disabled="state.busy" @tap="flow.load(true)">加载下一页</button>
    </view>
    <text v-if="state.busy">正在核对待审文字…</text>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" @tap="login">重新登录运营账号</button>
    <button :disabled="state.busy" @tap="flow.load(false)">刷新待审列表</button>
    <button @tap="uni.navigateBack()">返回运营审核</button>
  </view>
</template>
<style scoped>.public-text{white-space:pre-wrap;overflow-wrap:anywhere}picker{padding:20rpx;border:1px solid #e5e6eb}</style>
