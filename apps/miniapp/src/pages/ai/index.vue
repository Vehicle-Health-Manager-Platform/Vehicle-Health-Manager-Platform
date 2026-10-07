<script setup>
import { reactive, ref, computed, watch } from 'vue'
import { onHide, onUnload } from '@dcloudio/uni-app'
import OwnerTabShell from '../../components/OwnerTabShell.vue'
import VehicleList from '../../components/VehicleList.vue'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { selectedOwnerVehicle, selectOwnerVehicle } from '../../services/owner-vehicle-selection.js'
import { aiChatApi, MAX_QUESTION_CHARS } from '../../services/ai-chat.js'
import { createAiChatFlow, initialAiChatState } from '../../services/ai-chat-flow.js'

const state = reactive(initialAiChatState())
const draft = ref('')
const flow = createAiChatFlow({
  state,
  api: aiChatApi,
  token: () => ownerSession.accessToken,
  vehicleId: () => selectedOwnerVehicle.value?.vehicle_id ?? null,
})
const suggestions = ['刹车有异响，可能是什么原因？', '这辆车下次保养应该做什么？', '夏天开空调油耗变高正常吗？']
const currentVehicle = computed(() => selectedOwnerVehicle.value?.model_name || '')
const lastMessageId = computed(() => (state.messages.length ? `message-${state.messages.length - 1}` : ''))

// 换车或换身份会让上一辆车的回答过期，直接开一段新对话。
watch(() => ownerSession.accessToken, flow.reset, { flush: 'sync' })
watch(() => selectedOwnerVehicle.value?.vehicle_id, () => { if (state.messages.length) flow.reset() }, { flush: 'sync' })
onHide(flow.suspend)
onUnload(flow.reset)

function select(item) { selectOwnerVehicle(item) }
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
function archive() { uni.switchTab({ url: '/pages/archive/index' }) }
function send() {
  if (state.busy || !draft.value.trim()) return flow.send(draft.value)
  const question = draft.value
  draft.value = ''
  return flow.send(question)
}
function ask(text) { draft.value = text; return send() }
</script>

<template>
  <OwnerTabShell label="AI" title="和 AI 管家聊聊" description="结合爱车档案了解问题与服务方案。" next-action="AI 管家正在接入。" business-ready>
    <template #content>
      <view class="context">
        <text v-if="currentVehicle" class="context-title" data-testid="ai-vehicle">当前爱车：{{ currentVehicle }}</text>
        <text v-else class="context-title">尚未选择爱车</text>
        <text class="context-copy">{{ currentVehicle ? '回答会参考这辆车的档案记录。' : '未选车时按通用用车知识回答，录入档案后建议会更贴合车辆。' }}</text>
        <button v-if="!currentVehicle" class="link" @tap="archive">去录入车辆档案</button>
      </view>

      <VehicleList v-if="!state.messages.length" :selected-id="selectedOwnerVehicle?.vehicle_id || 0" @select="select" />

      <scroll-view v-if="state.messages.length" class="thread" scroll-y :scroll-into-view="lastMessageId" scroll-with-animation>
        <view v-for="(message, index) in state.messages" :key="index" :id="`message-${index}`"
          :class="['bubble', message.role === 'user' ? 'mine' : 'theirs']" :data-testid="`ai-message-${message.role}`">
          <text class="body">{{ message.content }}</text>
          <text v-if="message.role === 'assistant' && message.grounded" class="tag">结合爱车档案</text>
          <text v-if="message.role === 'assistant' && !message.grounded" class="tag muted">通用知识回答</text>
        </view>
      </scroll-view>

      <view v-if="!state.messages.length" class="starters">
        <text class="section">可以这样问</text>
        <button v-for="text in suggestions" :key="text" class="starter" :disabled="state.busy" @tap="ask(text)">{{ text }}</button>
      </view>

      <view v-if="state.busy" class="notice" role="status">AI 管家正在思考…</view>
      <view v-if="state.message" class="panel" role="status">
        <text class="error">{{ state.message }}</text>
        <button v-if="['unauthorized','forbidden'].includes(state.failureKind)" class="action" @tap="login">重新登录</button>
        <button v-else-if="state.retryable" class="action" :disabled="state.busy" @tap="flow.retry">重试这次提问</button>
      </view>
      <text v-if="state.message && !state.retryable && ['unauthorized','forbidden'].includes(state.failureKind)" class="note">
        AI 对话需要车主身份，登录后可继续提问。
      </text>
      <text v-if="state.failureKind === 'unavailable' || state.failureKind === 'limited'" class="note">
        AI 暂时联系不上时，可以先在「档案」查看养护记录，或稍后再发起提问。
      </text>

      <view class="composer">
        <textarea v-model="draft" class="input" :maxlength="MAX_QUESTION_CHARS" :disabled="state.busy || !ownerSession.accessToken"
          placeholder="描述车辆的现象或想问的问题" auto-height />
        <view class="composer-foot">
          <text class="counter">{{ draft.length }}/{{ MAX_QUESTION_CHARS }}</text>
          <button class="send" :disabled="state.busy || !draft.trim()" data-testid="ai-send" @tap="send">发送</button>
        </view>
      </view>
      <text class="note">AI 建议仅供参考，不能替代到店检查；涉及制动、转向、轮胎等安全问题时请尽快到店。</text>
    </template>
  </OwnerTabShell>
</template>

<style scoped>
.context { display: flex; flex-direction: column; padding: 28rpx 32rpx; margin-bottom: 24rpx; background: #fff; border-radius: 24rpx; }
.context-title { color: #1d2129; font-size: 30rpx; font-weight: 650; }
.context-copy { margin-top: 14rpx; color: #4e5969; font-size: 25rpx; line-height: 38rpx; }
.link { align-self: flex-start; margin: 20rpx 0 0; padding: 0 24rpx; font-size: 25rpx; background: #eef8f0; color: #008f24; border-radius: 14rpx; }
.thread { max-height: 720rpx; margin-bottom: 24rpx; }
.bubble { display: flex; flex-direction: column; padding: 26rpx 28rpx; margin-bottom: 20rpx; border-radius: 20rpx; }
.mine { margin-left: 96rpx; background: #00b42a; }
.mine .body { color: #fff; }
.theirs { margin-right: 96rpx; background: #fff; }
.theirs .body { color: #1d2129; }
.body { font-size: 28rpx; line-height: 44rpx; white-space: pre-wrap; }
.tag { align-self: flex-start; margin-top: 16rpx; padding: 0 16rpx; font-size: 21rpx; line-height: 40rpx; color: #008f24; background: #eef8f0; border-radius: 10rpx; }
.tag.muted { color: #86909c; background: #f2f3f5; }
.starters { display: flex; flex-direction: column; margin-bottom: 24rpx; }
.section { color: #4e5969; font-size: 24rpx; margin-bottom: 16rpx; }
.starter { margin: 0 0 16rpx; padding: 24rpx 28rpx; text-align: left; font-size: 26rpx; line-height: 38rpx; color: #1d2129; background: #fff; border-radius: 18rpx; }
.notice { padding: 20rpx 0; color: #4e5969; font-size: 26rpx; }
.panel { display: flex; flex-direction: column; padding: 28rpx; margin-bottom: 20rpx; background: #fff; border-radius: 24rpx; }
.error { color: #b42318; font-size: 26rpx; line-height: 40rpx; }
.note { display: block; margin: 16rpx 0 0; color: #86909c; font-size: 23rpx; line-height: 36rpx; }
.composer { display: flex; flex-direction: column; padding: 28rpx 32rpx; background: #fff; border-radius: 24rpx; }
.input { width: 100%; min-height: 110rpx; font-size: 28rpx; line-height: 42rpx; color: #1d2129; }
.composer-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 20rpx; }
.counter { color: #86909c; font-size: 23rpx; }
button { margin: 0; }
button::after { border: 0; }
.send { padding: 0 44rpx; min-height: 76rpx; line-height: 76rpx; font-size: 28rpx; background: #00b42a; color: #fff; border-radius: 16rpx; }
.send[disabled] { background: #f2f3f5; color: #86909c; }
.action { align-self: flex-start; margin: 22rpx 0 0; padding: 0 28rpx; min-height: 76rpx; line-height: 76rpx; font-size: 27rpx; background: #eef8f0; color: #008f24; border-radius: 14rpx; }
</style>
