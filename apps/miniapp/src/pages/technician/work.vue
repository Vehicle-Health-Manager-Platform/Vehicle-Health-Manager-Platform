<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { technicianSession, clearTechnicianSession } from '../../services/technician-session.js'
import { dispatchApi, confirmStillHolds } from '../../services/technician-dispatch.js'
import { PHOTO_GROUPS, PHOTO_LABELS, reportBody, workApi, WorkError } from '../../services/service-work.js'
import { createWorkImageFlow, initialWorkImageState } from '../../services/work-image-flow.js'
import { technicianImageApi as images, imageRequestKey } from '../../services/private-images.js'
import { createReservationReadFlow, createReservationWriteFlow, initialReservationReadState, initialReservationWriteState, displayTime } from '../../services/reservations.js'
import { stateLabel } from '../../services/order-status.js'
import WorkPhotos from '../../components/WorkPhotos.vue'
import SignaturePad from '../../components/SignaturePad.vue'
const fresh = () => ({ plan: '', analysis: '', minutes: '', noFaultParts: false, noParts: false, parts: [] })
const form = reactive(fresh()), read = reactive(initialReservationReadState()), reportWrite = reactive(initialReservationWriteState()), signWrite = reactive(initialReservationWriteState()), photos = reactive(initialWorkImageState())
const visible = ref(false), epoch = ref(0), modal = ref(false), signatureBusy = ref(false)
let order = 0
const token = () => technicianSession.accessToken
const flow = createReservationReadFlow({ state: read, token, request: async actor => {
  const [work, job] = await Promise.all([workApi.detail(actor, 'tech', order), dispatchApi.order(actor, order)])
  return { work, job }
} })
const work = computed(() => read.value?.work), job = computed(() => read.value?.job)
const canWork = computed(() => job.value?.assignment_status === 'ACCEPTED' && work.value?.order_status === 'IN_SERVICE' && job.value.order_status === work.value.order_status)
const locked = computed(() => photos.busy || reportWrite.busy || signWrite.busy || modal.value || signatureBusy.value || !canWork.value)
const photoFlow = createWorkImageFlow({ state: photos, api: images, token, disabled: () => reportWrite.busy || signWrite.busy || modal.value || !!signWrite.saved || !canWork.value })
const group = kind => photos.files.filter(f => f.kind === kind)
const fields = { PROCESS: 'process_photos', FAULT: 'fault_part_photos', FINISH: 'finish_photos' }
const signBody = () => {
  const file = group('SIGNATURE')[0]?.fileId
  if (!Number.isSafeInteger(file) || file <= 0) throw new WorkError('invalid', '请手写本人签名并完成签名图片上传')
  return { order_id: order, signature_file_id: file }
}
const reportSave = createReservationWriteFlow({ state: reportWrite, token, newKey: imageRequestKey, body: () => reportBody(order, form, photos.files), request: (actor, b, key) => workApi.submit(actor, b, key), onConflict: () => flow.load() })
const signSave = createReservationWriteFlow({ state: signWrite, token, newKey: imageRequestKey, body: signBody, request: (actor, b, key) => workApi.sign(actor, b.order_id, b.signature_file_id, key), onConflict: () => flow.load() })
function clear() { epoch.value++; modal.value = false; signatureBusy.value = false; Object.assign(form, fresh()); photoFlow.reset(); flow.reset(); reportSave.reset(); signSave.reset() }
function login() { clearTechnicianSession(); uni.navigateTo({ url: '/pages/technician/index' }) }
function addPart() { if (!locked.value && !form.noParts && form.parts.length < 20) form.parts.push({ name: '', model: '', brand: '', quantity: '' }) }
function confirm(action) {
  if (locked.value || (action === 'report' ? work.value?.report || reportWrite.saved : work.value?.report?.status !== 'SUBMITTED' || signWrite.saved)) return
  const body = action === 'report' ? () => reportBody(order, form, photos.files) : signBody
  const write = action === 'report' ? reportWrite : signWrite, save = action === 'report' ? reportSave : signSave
  let opened; try { opened = { actor: token(), target: JSON.stringify(body()) } } catch (error) { write.message = error.message; return }
  const version = epoch.value; modal.value = true
  uni.showModal({ title: action === 'report' ? '提交完整施工报工' : '质检签字并送核销',
    content: action === 'report' ? '确认照片、方案、故障分析、配件与工时符合实际施工？提交后不可修改，随后需质检签字。' : '确认已完成质检并由本人签名？提交后不可修改，订单将进入待核销。',
    success: result => {
      if (!result.confirm || version !== epoch.value || !canWork.value || (action === 'report' ? work.value?.report : work.value?.report?.status !== 'SUBMITTED')) return
      let target; try { target = JSON.stringify(body()) } catch { return }
      if (confirmStillHolds(opened, { visible: visible.value, token: token(), target })) save.save()
    }, complete: () => { if (version === epoch.value) modal.value = false },
  })
}
function signed(file, version) { if (version === epoch.value && visible.value && canWork.value && work.value?.report?.status === 'SUBMITTED') { signatureBusy.value = false; photoFlow.signature(file) } }
function signingBusy(busy, version) { if (version === epoch.value) signatureBusy.value = busy }
function clearSignature(version) { if (version === epoch.value) { const entry = group('SIGNATURE')[0]; if (entry) photoFlow.remove(entry) } }
const preview = file => photoFlow.preview(file, actor => workApi.access(actor, 'tech', order, file))
watch(() => reportWrite.saved, value => { if (value && visible.value) flow.load() })
watch(() => signWrite.saved, value => { if (value && visible.value) flow.load() })
watch(token, () => { clear(); if (visible.value && token()) { photoFlow.resume(); flow.load() } }, { flush: 'sync' })
onLoad(q => { order = Number(q?.id) || 0 })
onShow(() => { visible.value = true; photoFlow.resume(); if (token()) flow.load() })
onHide(() => { visible.value = false; epoch.value++; modal.value = false; signatureBusy.value = false; photoFlow.suspend(); flow.suspend(); reportSave.suspend(); signSave.suspend() })
onUnload(() => { visible.value = false; clear() })
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">施工报工与质检</text>
    <button v-if="!technicianSession.accessToken" @tap="login">前往技师登录</button>
    <text v-if="read.busy">正在加载本人工单…</text>
    <view v-if="read.message" class="reservation-panel"><text role="status">{{ read.message }}</text><text v-if="['unauthorized','forbidden'].includes(read.failureKind)" data-testid="technician-session-action">账号可能已被店长停用，或微信绑定已被撤销。请向店长索取新的员工码，然后重新绑定。</text><button v-if="['unauthorized','forbidden'].includes(read.failureKind)" @tap="login">重新绑定员工码</button><button v-else @tap="flow.load">重试加载</button></view>
    <view v-if="work && job" class="reservation-panel">
      <text class="reservation-heading">{{ job.project_snapshot?.project_name || '历史工单' }}</text>
      <text>工单号 {{ job.order_no }} · {{ stateLabel(work.order_status) }}</text>
      <text v-if="work.order_status==='DISPUTED'">订单有未解决争议，报工与签字已暂停。请商家处理并等待车主复核。</text>
      <view v-if="work.protection"><text>施工防护已提交 · {{ displayTime(work.protection.uploaded_at) }}</text><button :disabled="photos.busy" @tap="preview(work.protection.photo_file_id)">核对防护照片</button></view>
      <text v-else>请先由商家完成施工防护拍照。无防护照片不能报工。</text>
      <template v-if="!work.report && work.protection && canWork">
        <WorkPhotos v-for="kind in PHOTO_GROUPS.filter(k=>k!=='FAULT'||!form.noFaultParts)" :key="kind" :files="group(kind)" :kind="kind" :label="PHOTO_LABELS[kind]" :disabled="locked || !!reportWrite.saved" @choose="photoFlow.choose" @remove="photoFlow.remove" @retry="photoFlow.upload" @preview="photoFlow.preview" />
        <text>施工照、完工照各至少一张，完工照应包含施工部位与车牌；有故障件时上传故障件照。每类最多 9 张。</text>
        <checkbox-group @change="form.noFaultParts=$event.detail.value.includes('none')"><label><checkbox value="none" :checked="form.noFaultParts" :disabled="locked||!!reportWrite.saved||group('FAULT').length>0" />本次无故障件（如已有照片，请先移除再声明）</label></checkbox-group>
        <text>本次施工内容 / 维修方案</text><textarea v-model="form.plan" maxlength="2000" :disabled="locked||!!reportWrite.saved" placeholder="如实填写，1–2000 字" />
        <text>故障原因分析</text><textarea v-model="form.analysis" maxlength="2000" :disabled="locked||!!reportWrite.saved" placeholder="无故障件时也请明确说明，1–2000 字" />
        <text class="reservation-heading">配件记录 · {{ form.parts.length }}/20</text>
        <view v-for="(part,index) in form.parts" :key="index" class="part-block">
          <input v-model="part.name" maxlength="100" :disabled="locked||!!reportWrite.saved" placeholder="配件名称" /><input v-model="part.model" maxlength="100" :disabled="locked||!!reportWrite.saved" placeholder="型号" /><input v-model="part.brand" maxlength="100" :disabled="locked||!!reportWrite.saved" placeholder="品牌" /><input v-model="part.quantity" type="number" maxlength="3" :disabled="locked||!!reportWrite.saved" placeholder="数量 1–999" /><button :disabled="locked||!!reportWrite.saved" @tap="form.parts.splice(index,1)">移除此配件</button>
        </view>
        <button :disabled="locked||!!reportWrite.saved||form.noParts||form.parts.length>=20" @tap="addPart">添加实际使用配件</button>
        <checkbox-group @change="form.noParts=$event.detail.value.includes('none')"><label><checkbox value="none" :checked="form.noParts" :disabled="locked||!!reportWrite.saved||form.parts.length>0" />本次未使用配件</label></checkbox-group>
        <text>实际工时（分钟）</text><input v-model="form.minutes" type="number" maxlength="4" :disabled="locked||!!reportWrite.saved" placeholder="整数分钟，1–1440" />
        <button :disabled="locked||!!reportWrite.saved" :loading="reportWrite.busy" @tap="confirm('report')">确认提交完整报工</button>
      </template>
      <text v-else-if="!work.report && !canWork && work.order_status!=='DISPUTED'">需本人接单且订单处于施工中，才能提交报工。请返回工单确认接单，或刷新订单状态。</text>
      <template v-if="work.report">
        <text class="reservation-heading">已提交报工 · {{ displayTime(work.report.submitted_at) }}</text>
        <view v-for="kind in PHOTO_GROUPS" :key="kind"><text>{{ PHOTO_LABELS[kind] }} · {{ work.report[fields[kind]].length }} 张</text><button v-for="(file,index) in work.report[fields[kind]]" :key="file" :disabled="photos.busy" @tap="preview(file)">查看{{ PHOTO_LABELS[kind] }} {{ index+1 }}</button></view>
        <text v-if="work.report.no_fault_parts">本次声明无故障件</text>
        <text>施工方案：{{ work.report.repair_plan }}</text><text>故障分析：{{ work.report.fault_analysis }}</text>
        <text v-for="(part,index) in work.report.parts_used" :key="index">{{ part.name }} · {{ part.model }} · {{ part.brand }} × {{ part.quantity }}</text><text v-if="work.report.no_parts">本次声明未使用配件</text>
        <text>实际工时 {{ work.report.work_hours }} 分钟</text>
        <template v-if="work.report.status==='SUBMITTED' && canWork">
          <SignaturePad :disabled="locked" :active="visible" :epoch="epoch" @signed="signed" @clear="clearSignature" @busy="signingBusy" />
          <view v-for="entry in group('SIGNATURE')" :key="entry.key"><image :src="entry.localPath" class="signature-preview" mode="aspectFit" /><text>{{ entry.message }}</text><button v-if="entry.candidate && !entry.fileId" :disabled="locked" @tap="photoFlow.upload(entry)">原签名图片重试上传</button><button v-if="entry.fileId" :disabled="locked" @tap="photoFlow.preview(entry.fileId)">核对已上传签名</button></view>
          <button :disabled="locked || !!signWrite.saved || !group('SIGNATURE')[0]?.fileId" :loading="signWrite.busy" @tap="confirm('sign')">确认本人质检签字，送待核销</button>
        </template>
        <template v-else-if="work.report.status==='SIGNED'"><text>已完成质检签字 · {{ displayTime(work.report.signed_at) }}</text><button :disabled="photos.busy" @tap="preview(work.report.signature_file_id)">查看本人质检签名</button><text>施工记录已锁定，当前订单状态为 {{ stateLabel(work.order_status) }}。</text></template>
      </template>
      <text v-if="photos.message" role="status">{{ photos.message }}</text><button v-if="photos.failureKind==='permission'" @tap="images.authorize">开启相机权限</button>
      <text v-if="reportWrite.message" role="status">{{ reportWrite.message }}</text><text v-if="signWrite.message" role="status">{{ signWrite.message }}</text>
      <button v-if="[reportWrite.failureKind,signWrite.failureKind].some(k=>['unauthorized','forbidden'].includes(k))" @tap="login">重新绑定员工码</button>
      <button :disabled="photos.busy||reportWrite.busy||signWrite.busy||modal" @tap="flow.load">刷新施工记录</button>
    </view>
    <button @tap="uni.navigateBack()">返回本人工单</button>
  </view>
</template>

<style scoped>.part-block{display:flex;flex-direction:column;gap:12rpx;padding:16rpx;background:#f2f3f5;border-radius:8rpx}input,textarea{padding:16rpx;border:1px solid #c9cdd4;border-radius:8rpx}textarea{width:auto;min-height:180rpx}.signature-preview{width:100%;height:180px;background:#fff;border:1px solid #c9cdd4}</style>
