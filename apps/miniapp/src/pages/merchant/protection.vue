<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { PROTECTION_ITEMS, PROTECTION_LABELS, protectionBody, workApi } from '../../services/service-work.js'
import { createWorkImageFlow, initialWorkImageState } from '../../services/work-image-flow.js'
import { merchantImageApi as images, imageRequestKey } from '../../services/private-images.js'
import { createReservationReadFlow, createReservationWriteFlow, initialReservationReadState, initialReservationWriteState, displayTime } from '../../services/reservations.js'
import { confirmStillHolds } from '../../services/technician-dispatch.js'
import { stateLabel } from '../../services/order-status.js'
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
  <view class="reservation-page">
    <text class="reservation-title">施工防护</text>
    <button v-if="!merchantSession.accessToken" @tap="login">前往商家登录</button>
    <text v-if="read.busy">正在加载施工记录…</text>
    <view v-if="read.message" class="reservation-panel"><text role="status">{{ read.message }}</text><button @tap="flow.load">重试加载</button><button v-if="['unauthorized','forbidden'].includes(read.failureKind)" @tap="login">重新登录</button></view>
    <view v-if="read.value" class="reservation-panel">
      <text>订单状态 {{ stateLabel(read.value.order_status) }}</text>
      <template v-if="read.value.protection">
        <text class="reservation-heading">已提交施工防护</text>
        <text v-for="item in read.value.protection.items" :key="item">{{ PROTECTION_LABELS[PROTECTION_ITEMS.indexOf(item)] }}</text>
        <text>提交时间 {{ displayTime(read.value.protection.uploaded_at) }}</text>
        <button :disabled="photos.busy" @tap="preview(read.value.protection.photo_file_id)">查看防护照片</button>
        <text>防护记录不可修改。技师报工时仍会重新校验防护与车主确认。</text>
      </template>
      <template v-else-if="['RECEIVED','IN_SERVICE'].includes(read.value.order_status)">
        <text>请按标准完成防护。座椅套、方向盘套必选；一张照片须同时拍到两者。</text>
        <checkbox-group @change="selected=$event.detail.value"><label v-for="(item,index) in PROTECTION_ITEMS" :key="item"><checkbox :value="item" :checked="selected.includes(item)" :disabled="disabled" />{{ PROTECTION_LABELS[index] }}{{ index<2?'（必选）':'' }}</label></checkbox-group>
        <WorkPhotos :files="photos.files" kind="PROTECTION" label="防护照片" :limit="1" :disabled="disabled" @choose="photoFlow.choose" @remove="photoFlow.remove" @retry="photoFlow.upload" @preview="photoFlow.preview" />
        <text>未上传防护照片，技师无法报工。车主尚未确认或有未解决争议时，提交会被拒绝。</text>
        <button :disabled="disabled" :loading="write.busy" @tap="submit">确认提交施工防护</button>
      </template>
      <text v-else-if="read.value.order_status==='DISPUTED'">订单存在争议，请在接车单处理并等待车主复核。</text>
      <text v-else>当前订单状态不能新增防护记录。</text>
      <view v-if="read.value.report" class="work-record">
        <text class="reservation-heading">已提交施工报工 · {{ displayTime(read.value.report.submitted_at) }}</text>
        <view v-for="field in ['process_photos','fault_part_photos','finish_photos']" :key="field"><text>{{ {process_photos:'施工照',fault_part_photos:'故障件照',finish_photos:'完工照'}[field] }}</text><button v-for="(file,index) in read.value.report[field]" :key="file" :disabled="photos.busy" @tap="preview(file)">查看照片 {{ index+1 }}</button></view>
        <text>施工方案：{{ read.value.report.repair_plan }}</text><text>故障分析：{{ read.value.report.fault_analysis }}</text><text v-if="read.value.report.no_fault_parts">本次声明无故障件</text>
        <text v-for="(part,index) in read.value.report.parts_used" :key="index">{{ part.name }} · {{ part.model }} · {{ part.brand }} × {{ part.quantity }}</text><text v-if="read.value.report.no_parts">本次声明未使用配件</text><text>实际工时 {{ read.value.report.work_hours }} 分钟</text>
        <text v-if="read.value.report.status==='SUBMITTED'">报工已提交，等待本人技师质检签字。</text>
        <template v-else><text>已质检签字 · {{ displayTime(read.value.report.signed_at) }}</text><button :disabled="photos.busy" @tap="preview(read.value.report.signature_file_id)">查看质检签名</button></template>
      </view>
      <text v-if="photos.message" role="status">{{ photos.message }}</text><button v-if="photos.failureKind==='permission'" @tap="images.authorize">开启相机权限</button>
      <text v-if="write.message" role="status">{{ write.message }}</text><button v-if="['unauthorized','forbidden'].includes(write.failureKind)" @tap="login">重新登录</button>
      <button :disabled="photos.busy || write.busy" @tap="flow.load">刷新施工记录</button>
      <button @tap="uni.navigateTo({url:`/pages/merchant/dispatch?id=${order}`})">查看派工</button>
    </view>
    <button @tap="uni.navigateBack()">返回本店订单</button>
  </view>
</template>
<style src="../../styles/reservations.css"></style>
<style scoped>checkbox-group{display:flex;flex-direction:column;gap:16rpx}</style>
