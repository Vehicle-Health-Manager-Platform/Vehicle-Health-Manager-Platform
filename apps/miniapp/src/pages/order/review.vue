<script setup>
import { computed, reactive, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { imageApi, imageRequestKey } from '../../services/private-images.js'
import { initialWorkImageState, createWorkImageFlow } from '../../services/work-image-flow.js'
import { initialReservationReadState, createReservationReadFlow, displayTime } from '../../services/reservations.js'
import { orderReviewsApi, reviewBody, REVIEW_REASONS, initialOrderReviewState, createOrderReviewFlow } from '../../services/order-reviews.js'

const read = reactive(initialReservationReadState()), write = reactive(initialOrderReviewState()), photos = reactive(initialWorkImageState()), form = reactive({ rating: 0, content: '' })
let order = 0, visible = false, generation = 0
const token = () => ownerSession.accessToken
const result = computed(() => write.saved?.review || read.value?.review)
const locked = computed(() => !read.value?.can_submit || !!result.value || write.busy || photos.busy)
const body = () => {
  if (photos.files.some(f => !f.fileId)) throw Error('请先完成所选图片的安全上传，或移除失败图片')
  return reviewBody(order, form.rating, form.content, photos.files.map(f => f.fileId))
}
const canSubmit = computed(() => { if (locked.value) return false; try { body(); return true } catch { return false } })
const loader = createReservationReadFlow({ state: read, token, request: t => orderReviewsApi.detail(t, order) })
const imageFlow = createWorkImageFlow({ state: photos, api: { ...imageApi, choose: t => imageApi.choose(t, 'mixed') }, token, disabled: () => write.busy || !read.value?.can_submit || !!result.value })
const writer = createOrderReviewFlow({ state: write, token, body, api: orderReviewsApi, newKey: imageRequestKey,
  confirm: () => new Promise(resolve => uni.showModal({ title: '确认提交本人评价', content: '提交后不可修改。目前仅本人可查看，不会公开或计入商家评分。', success: r => resolve(!!r.confirm), fail: () => resolve(false) })),
  onConflict: () => loader.load() })
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function choose() { if (photos.files.length < 3 && !locked.value) imageFlow.choose('REVIEW') }
async function previewSaved(file) {
  const version = generation, actor = token(); if (!visible || read.busy || write.busy) return
  try { const signed = await imageApi.access(actor, file); if (visible && version === generation && actor === token()) await imageApi.preview(signed.url) }
  catch { if (visible && version === generation && actor === token()) read.message = '图片已不可预览或访问暂不可用；评价文字仍保留，请稍后重试' }
}
function clear() { generation++; loader.reset(); writer.reset(); imageFlow.reset(); form.rating = 0; form.content = '' }
function start() { if (token()) { writer.resume(); imageFlow.resume(); loader.load() } }
watch(token, () => { clear(); if (visible) start() }, { flush: 'sync' })
onLoad(q => { order = Number(q?.id) || 0 })
onShow(() => { visible = true; start() })
onHide(() => { visible = false; clear() })
onUnload(() => { visible = false; clear() })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">本人订单评价</text>
    <text class="reservation-copy">订单 #{{ order }} · 评价仅本人可查看</text>
    <button v-if="!ownerSession.accessToken" @tap="login">前往车主登录</button>
    <text v-if="read.busy">正在核对评价资格…</text>
    <view v-if="read.message" class="reservation-panel"><text role="status">{{ read.message }}</text><button v-if="['unauthorized','forbidden'].includes(read.failureKind)" @tap="login">重新登录</button><button v-else :disabled="write.busy || photos.busy" @tap="loader.load">刷新本人评价</button></view>
    <view v-if="read.value" class="reservation-panel" data-testid="owner-review">
      <text v-if="read.value.test_mode || result?.test_mode" class="review-notice">测试订单评价：未真实扣款，不公开、不计入商家评分。</text>
      <template v-if="result">
        <text class="reservation-heading" data-testid="review-saved">本人评价已保存 · {{ result.rating }} / 5 分</text>
        <text class="review-content">{{ result.content }}</text>
        <text>提交时间 {{ displayTime(result.submitted_at) }}</text>
        <text>提交后不可修改。目前仅本人可查看。</text>
        <button v-for="(file,index) in result.photo_file_ids" :key="file" @tap="previewSaved(file)">查看评价图片 {{ index + 1 }}</button>
      </template>
      <template v-else-if="read.value.can_submit">
        <text class="reservation-heading">本次服务总体评分（必选）</text>
        <view class="rating-options"><button v-for="score in 5" :key="score" :disabled="locked" :class="{selected:form.rating===score}" :aria-pressed="form.rating===score" :data-testid="`review-rating-${score}`" @tap="form.rating=score">{{score}} 分</button></view>
        <text>评价说明（1–500 字）</text>
        <textarea v-model="form.content" class="reservation-field review-input" :disabled="locked" maxlength="1000" placeholder="记录服务体验或维修后的实际情况" data-testid="review-content" />
        <text>{{ [...form.content.trim()].length }} / 500 字</text>
        <text>图片可选，最多 3 张；请勿上传证件、车牌或其他个人敏感信息。</text>
        <view v-for="(entry,index) in photos.files" :key="entry.key" class="review-photo">
          <image v-if="entry.localPath" :src="entry.localPath" mode="aspectFit" class="review-thumb" />
          <text>图片 {{ index+1 }} · {{ entry.message }}</text>
          <button v-if="entry.fileId" :disabled="locked" @tap="imageFlow.preview(entry.fileId)">预览本人图片</button>
          <button v-else :disabled="locked" @tap="imageFlow.upload(entry)">原图重试上传</button>
          <button :disabled="locked" @tap="imageFlow.remove(entry)">移除图片</button>
        </view>
        <button :disabled="locked || photos.files.length>=3" @tap="choose">选择或拍摄图片 · {{photos.files.length}}/3</button>
        <text v-if="photos.message" role="status">{{photos.message}}</text>
        <text>提交后不可修改。目前仅本人可查看，不会公开。</text>
        <button class="reservation-primary" :disabled="!canSubmit" :loading="write.busy" data-testid="review-submit" @tap="writer.submit">提交本人评价</button>
      </template>
      <template v-else><text>{{ REVIEW_REASONS[read.value.unavailable_reason] || '当前不能评价，请刷新核对' }}</text><button @tap="loader.load">重新核对资格</button></template>
      <text v-if="write.message" role="status">{{write.message}}</text>
      <button v-if="['unauthorized','forbidden'].includes(write.failureKind)" @tap="login">重新登录</button>
    </view>
    <button @tap="uni.navigateBack({fail:()=>uni.redirectTo({url:'/pages/order/list'})})">返回订单</button>
  </view>
</template>

<style scoped>
.rating-options{display:flex;gap:10rpx;flex-wrap:wrap}.rating-options button{margin:0;padding:0 18rpx;min-width:82rpx}.rating-options .selected{background:#00b42a;color:white}.review-input{box-sizing:border-box;width:100%;min-height:220rpx;margin-top:0}.review-content{white-space:pre-wrap;overflow-wrap:anywhere}.review-notice{padding:18rpx;background:#fff7e6;color:#805300;border-radius:12rpx}.review-photo{display:flex;flex-direction:column;gap:10rpx;border-bottom:1px solid #e5e6eb;padding:16rpx 0}.review-thumb{width:100%;height:220rpx}
</style>
