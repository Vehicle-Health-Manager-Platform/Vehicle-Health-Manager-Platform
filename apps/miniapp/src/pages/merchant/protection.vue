<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { workApi, protectionBody } from '../../services/service-work.js'
import { createWorkImageFlow, initialWorkImageState } from '../../services/work-image-flow.js'
import { merchantImageApi as images, imageRequestKey } from '../../services/private-images.js'
import { createReservationReadFlow, createReservationWriteFlow, initialReservationReadState, initialReservationWriteState } from '../../services/reservations.js'
import { confirmStillHolds } from '../../services/technician-dispatch.js'
import { stateLabel } from '../../services/order-status.js'
import {
  protectionExistingFacts, protectionItemRows, protectionPhotoStatus, protectionReadFailureView,
  protectionReportView, protectionSelectionState, protectionStageView, protectionSubmitView, protectionWriteView,
} from '../../services/merchant-protection-display.js'
import { toneClass } from '../../services/ui-tone.js'
import WorkPhotos from '../../components/WorkPhotos.vue'
const read = reactive(initialReservationReadState()), write = reactive(initialReservationWriteState()), photos = reactive(initialWorkImageState())
const selected = ref([]), modal = ref(false)
let order = 0, visible = false, generation = 0
const token = () => merchantSession.accessToken
const flow = createReservationReadFlow({ state: read, token, request: actor => workApi.detail(actor, 'merchant', order) })
const disabled = computed(() => photos.busy || write.busy || modal.value || !!write.saved || !!read.value?.protection)
const photoFlow = createWorkImageFlow({ state: photos, api: images, token, disabled: () => write.busy || modal.value || !!write.saved || !!read.value?.protection })
const body = () => protectionBody(order, selected.value, photos.files.find(p => p.kind === 'PROTECTION')?.fileId)
const save = createReservationWriteFlow({ state: write, token, newKey: imageRequestKey, body, request: (actor, b, key) => workApi.protect(actor, b, key), onConflict: () => flow.load() })
// 阶段、勾选、提交前置与报工记录都来自映射，模板不再写四层 v-if 链与内联标签表。
const stageView = computed(() => protectionStageView(read.value))
const orderStatus = computed(() => stateLabel(read.value?.order_status))
const itemRows = computed(() => protectionItemRows(selected.value))
const selection = computed(() => protectionSelectionState(selected.value, photos.files))
const submitView = computed(() => protectionSubmitView(selected.value, photos.files, { busy: disabled.value, saved: !!write.saved }))
const existing = computed(() => protectionExistingFacts(read.value?.protection))
const report = computed(() => protectionReportView(read.value?.report))
const readFailure = computed(() => protectionReadFailureView(read))
const photoStatus = computed(() => protectionPhotoStatus(photos))
const writeView = computed(() => protectionWriteView(write))
function clear() { generation++; modal.value = false; selected.value = []; photoFlow.reset(); flow.reset(); save.reset() }
function login() { clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
function submit() {
  if (disabled.value || !['RECEIVED', 'IN_SERVICE'].includes(read.value?.order_status)) return
  let opened; try { opened = { actor: token(), target: JSON.stringify(body()) } } catch (e) { write.message = e.message; return }
  const version = generation; modal.value = true
  uni.showModal({ title: '提交施工防护', content: '确认防护照片同时拍到座椅套与方向盘套，勾选内容与实际一致？提交后不可修改。', success: result => {
    if (!result.confirm || version !== generation) return
    let target; try { target = JSON.stringify(body()) } catch { return }
    if (confirmStillHolds(opened, { visible, token: token(), target })) save.save()
  }, complete: () => { if (version === generation) modal.value = false } })
}
const preview = file => photoFlow.preview(file, actor => workApi.access(actor, 'merchant', order, file))
watch(() => write.saved, value => { if (value && visible) flow.load() })
watch(token, () => { clear(); if (visible && token()) { photoFlow.resume(); flow.load() } }, { flush: 'sync' })
onLoad(q => { order = Number(q?.id) || 0 })
onShow(() => { visible = true; photoFlow.resume(); if (token()) flow.load() })
onHide(() => { visible = false; generation++; modal.value = false; photoFlow.suspend(); flow.suspend(); save.suspend() })
onUnload(() => { visible = false; clear() })
</script>
<template>
  <view class="reservation-page" data-testid="protection-form">
    <button v-if="writeView.retry" class="reservation-primary" :disabled="write.busy" @tap="save.retry">{{ writeView.retryLabel }}</button>

    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">门店工作台</text>
      <text class="ux-title">施工防护</text>
      <text class="ux-lead">座椅套、方向盘套必选，一张照片须同时拍到两者。提交后不可修改，技师报工时仍会重新校验防护与车主确认。</text>
    </view>

    <view v-if="!merchantSession.accessToken" class="ux-card ux-in ux-r2" data-testid="protection-login">
      <text class="ux-card-title">需要门店账号</text>
      <text class="ux-card-note">施工防护只能由接单门店提交，技师不能代填。</text>
      <button class="reservation-primary" @tap="login">前往门店登录</button>
    </view>

    <template v-else>
      <text v-if="read.busy" class="status-line" role="status">正在加载施工记录…</text>

      <view v-if="readFailure.message" class="ux-card ux-in ux-r2" role="status" data-testid="protection-read-error">
        <view class="head">
          <text class="ux-card-title">{{ readFailure.title }}</text>
          <view class="ux-badge" :class="toneClass(readFailure.tone)"><text>{{ readFailure.canRelogin ? '需重新登录' : '可重试' }}</text></view>
        </view>
        <text class="ux-card-note">{{ readFailure.message }}</text>
        <button v-if="readFailure.canRelogin" class="reservation-primary" @tap="login">重新登录</button>
        <button v-else @tap="flow.load">重试加载</button>
      </view>

      <template v-if="read.value">
        <view class="ux-card ux-in ux-r2" data-testid="protection-stage">
          <view class="head">
            <text class="ux-card-title">{{ stageView.title }}</text>
            <view class="ux-badge" :class="toneClass(stageView.tone)"><text>{{ orderStatus }}</text></view>
          </view>
          <text class="ux-card-note">{{ stageView.copy }}</text>
        </view>

        <view v-if="stageView.stage === 'existing'" class="ux-card ux-in ux-r3" data-testid="protection-existing">
          <view class="head">
            <text class="ux-card-title">已提交的防护内容</text>
            <view class="ux-badge" :class="toneClass('ok')"><text>{{ existing.itemLabels.length }} 项</text></view>
          </view>
          <view class="chips">
            <view v-for="label in existing.itemLabels" :key="label" class="chip"><text>{{ label }}</text></view>
          </view>
          <text class="ux-card-note">{{ existing.uploadedCopy }}</text>
          <button v-if="existing.canPreview" :disabled="photos.busy" @tap="preview(existing.photoFileId)">查看防护照片</button>
          <text class="ux-card-note">{{ existing.note }}</text>
        </view>

        <template v-if="stageView.stage === 'ready'">
          <view class="ux-card ux-in ux-r3" data-testid="protection-items">
            <view class="head">
              <text class="ux-card-title">防护项与照片</text>
              <view class="ux-badge" :class="toneClass(selection.ready ? 'ok' : 'warn')"><text>{{ selection.ready ? '可提交' : '待补全' }}</text></view>
            </view>
            <text class="ux-card-note">座椅套、方向盘套必选；一次性脚垫与翼子板布按实际勾选。</text>
            <checkbox-group class="checks" @change="selected = $event.detail.value">
              <label v-for="row in itemRows" :key="row.key" class="check">
                <checkbox :value="row.key" :checked="row.checked" :disabled="disabled" />
                <text>{{ row.actionLabel }}</text>
              </label>
            </checkbox-group>
            <view class="ux-divider"></view>
            <WorkPhotos :files="photos.files" kind="PROTECTION" label="防护照片" :limit="1" :disabled="disabled" @choose="photoFlow.choose" @remove="photoFlow.remove" @retry="photoFlow.upload" @preview="photoFlow.preview" />
            <text v-if="selection.blockedCopy" class="risk-line">{{ selection.blockedCopy }}</text>
            <text v-else class="ux-card-note">已勾选 {{ selection.selectedLabels.join('、') }}，防护照片已上传。</text>
          </view>

          <view class="ux-card ux-in ux-r3" data-testid="protection-submit">
            <text class="ux-card-note">{{ submitView.note }}</text>
            <text v-if="submitView.blockedCopy" class="risk-line">{{ submitView.blockedCopy }}</text>
            <button class="reservation-primary" :disabled="!submitView.ready" :loading="write.busy" @tap="submit">{{ submitView.label }}</button>
          </view>
        </template>

        <view v-if="report.exists" class="ux-card ux-in ux-r3" data-testid="protection-report">
          <view class="head">
            <text class="ux-card-title">施工报工</text>
            <view class="ux-badge" :class="toneClass(report.tone)"><text>{{ report.statusLabel }}</text></view>
          </view>
          <text class="ux-card-note">{{ report.submittedCopy }}</text>
          <view v-for="group in report.groups" :key="group.key" class="report-group">
            <text class="ux-card-note">{{ group.label }} · {{ group.files.length }} 张</text>
            <button v-for="(file, index) in group.files" :key="file" :disabled="photos.busy" @tap="preview(file)">查看{{ group.label }} {{ index + 1 }}</button>
          </view>
          <text class="ux-card-note">{{ report.planCopy }}</text>
          <text class="ux-card-note">{{ report.faultCopy }}</text>
          <text v-if="report.noFaultPartsCopy" class="ux-card-note">{{ report.noFaultPartsCopy }}</text>
          <text v-for="(line, index) in report.partsLines" :key="index" class="ux-card-note">{{ line }}</text>
          <text v-if="report.noPartsCopy" class="ux-card-note">{{ report.noPartsCopy }}</text>
          <text class="ux-card-note">{{ report.hoursCopy }}</text>
          <template v-if="report.signed">
            <text class="ux-card-note">{{ report.signedCopy }}</text>
            <button v-if="report.signatureFileId" :disabled="photos.busy" @tap="preview(report.signatureFileId)">查看质检签名</button>
          </template>
          <text v-else class="ux-card-note">报工已提交，等待本人技师质检签字。</text>
        </view>

        <text v-if="photoStatus.message" class="risk-line" role="status">{{ photoStatus.message }}</text>
        <button v-if="photoStatus.needsPermission" @tap="images.authorize">开启相机权限</button>
        <text v-if="writeView.message" class="risk-line" role="status">{{ writeView.message }}</text>
        <button v-if="writeView.canRelogin" @tap="login">重新登录</button>

        <view class="footer-actions">
          <button :disabled="photos.busy || write.busy" @tap="flow.load">刷新施工记录</button>
          <button @tap="uni.navigateTo({ url: `/pages/merchant/dispatch?id=${order}` })">查看派工</button>
        </view>
      </template>
    </template>

    <button class="footer-back" @tap="uni.navigateBack()">返回本店订单</button>
  </view>
</template>

<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.chips { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 14rpx; }
.chip { padding: 8rpx 20rpx; background: #efede6; border-radius: 999rpx; color: #454a41; font-size: 24rpx; }
.checks { display: flex; flex-direction: column; gap: 16rpx; margin-top: 16rpx; }
.check { display: flex; align-items: center; gap: 12rpx; color: #454a41; font-size: 26rpx; }
.report-group { margin-top: 12rpx; }
.footer-actions { display: flex; gap: 12rpx; margin-top: 20rpx; }
.footer-actions button { flex: 1; }
.footer-back { margin-top: 24rpx; }
.risk-line { display: block; margin-top: 12rpx; color: #b3261e; font-size: 25rpx; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
