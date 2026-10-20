<script setup>
import { reactive, ref, computed, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { pickupApi, pickupBody, PICKUP_SLOTS, FUEL_VALUES, FUEL_LABELS } from '../../services/pickup.js'
import {
  canMarkDamage, damageRowLabel, photoSlotView, pickupDamageView, pickupFuelView, pickupMileageView,
  pickupOrderFacts, pickupPhotoProgress, pickupReadFailureView, pickupSubmitView, pickupWriteView,
} from '../../services/pickup-display.js'
import { merchantImageApi as images, imageRequestKey, DEVTOOLS_NOTICE } from '../../services/private-images.js'
import { createReservationReadFlow, initialReservationReadState, createReservationWriteFlow, initialReservationWriteState } from '../../services/reservations.js'
import { toneClass } from '../../services/ui-tone.js'

const fresh = () => ({code:'',mileage:'',mileageReason:'',arrivalReason:'',fuel:'',damageStatus:'',damages:[],photos:Object.fromEntries(PICKUP_SLOTS.map(slot=>[slot,{candidate:null,key:'',fileId:0,url:'',message:''}]))})
const form=reactive(fresh()), read=reactive(initialReservationReadState()), write=reactive(initialReservationWriteState()), imageBusy=ref(false), message=ref(''), permission=ref(false)
let order=0, generation=0, visible=false
const token=()=>merchantSession.accessToken
const flow=createReservationReadFlow({state:read,token,request:t=>pickupApi.context(t,order)})
const save=createReservationWriteFlow({state:write,token,newKey:imageRequestKey,body:()=>pickupBody(order,form),request:(t,b,k)=>pickupApi.submit(t,b,k)})
const disabled=computed(()=>imageBusy.value||write.busy||!!write.saved)
// 展示全部来自映射：三态照片、进度、里程差值、损伤额度与提交前置都不在模板里算。
const progress=computed(()=>pickupPhotoProgress(form))
const facts=computed(()=>pickupOrderFacts(read.value))
const mileage=computed(()=>pickupMileageView(form.mileage,read.value?.mileage_baseline?.mileage))
const fuel=computed(()=>pickupFuelView(form.fuel))
const damage=computed(()=>pickupDamageView(form))
const readFailure=computed(()=>pickupReadFailureView(read))
const writeView=computed(()=>pickupWriteView(write))
const submitView=computed(()=>pickupSubmitView(form,{busy:disabled.value,saved:!!write.saved}))
const slots=computed(()=>PICKUP_SLOTS.map(slot=>{
  const photo=form.photos[slot]
  return {
    slot, photo, view: photoSlotView(slot, photo),
    marks: form.damages.map((item,index)=>({x:item.x,y:item.y,no:index+1,slot:item.photo_slot})).filter(dot=>dot.slot===slot),
  }
}))
function clear(){generation++;imageBusy.value=false;message.value='';permission.value=false;Object.assign(form,fresh());flow.reset();save.reset()}
watch(token,()=>{clear();if(visible&&token())flow.load()},{flush:'sync'})
onLoad(q=>{order=Number(q?.id)||0});onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;generation++;imageBusy.value=false;flow.suspend();save.suspend()});onUnload(()=>{visible=false;clear()})
function login(){clearMerchantSession();uni.navigateTo({url:'/pages/merchant/index'})}
async function upload(slot){
  const file=form.photos[slot];if(!file.candidate||disabled.value)return
  const version=generation, actor=token();imageBusy.value=true;file.message='正在上传…'
  try{const result=await images.upload(actor,file.candidate,file.key);if(version!==generation||actor!==token())return;file.fileId=result.file_id;file.candidate=null;file.message='上传成功';const access=await images.access(actor,file.fileId);if(version===generation&&actor===token())file.url=access.url}
  catch(error){if(version===generation&&actor===token()){file.message=error?.message||'上传未成功，请使用原图重试';permission.value=error?.kind==='permission'}}
  finally{if(version===generation)imageBusy.value=false}
}
async function choose(slot){
  if(disabled.value)return;const version=generation,actor=token();imageBusy.value=true;message.value=''
  try{const file=await images.choose(actor,'mixed');if(version!==generation||actor!==token()||!file)return;form.photos[slot]={candidate:file,key:imageRequestKey(),fileId:0,url:'',message:'已选择'};form.damages=form.damages.filter(d=>d.photo_slot!==slot);message.value=file.degraded?DEVTOOLS_NOTICE:''}
  catch(error){if(version===generation&&actor===token()){message.value=error?.message||'无法取图，请重试';permission.value=error?.kind==='permission'}}
  finally{if(version===generation)imageBusy.value=false}
  if(version===generation&&actor===token()&&form.photos[slot].candidate)await upload(slot)
}
async function preview(slot){const version=generation,actor=token();try{const result=await images.access(actor,form.photos[slot].fileId);if(version===generation&&actor===token()){form.photos[slot].url=result.url;await images.preview(result.url)}}catch(error){if(version===generation)message.value=error?.message||'预览未打开，请重试'}}
function mark(slot,event){
  if(disabled.value||!canMarkDamage(slot,form,form.photos[slot]))return
  const point=event.changedTouches?.[0]||event.touches?.[0]||event.detail
  const x=point?.clientX??point?.x,y=point?.clientY??point?.y,version=generation,fileId=form.photos[slot].fileId
  uni.createSelectorQuery().select(`.pickup-photo-${slot}`).boundingClientRect(rect=>{
    if(version!==generation||fileId!==form.photos[slot].fileId||disabled.value||!rect||!Number.isFinite(x)||!Number.isFinite(y)||!rect.width||!rect.height)return
    if(!canMarkDamage(slot,form,form.photos[slot]))return
    form.damages.push({photo_slot:slot,x:Math.max(0,Math.min(1,(x-rect.left)/rect.width)),y:Math.max(0,Math.min(1,(y-rect.top)/rect.height)),note:''})
  }).exec()
}
function submit(){
  try{pickupBody(order,form)}catch(error){write.message=error.message;return}
  const version=generation,actor=token();uni.showModal({title:'提交接车单',content:'确认检查信息完整？提交后不可修改，车主可以在订单中查看，仍需车主确认后才能派工。',success:r=>{if(r.confirm&&visible&&version===generation&&actor===token())save.save()}})
}
function sheet(){uni.redirectTo({url:`/pages/check/pickup-detail?id=${order}&role=merchant`})}
</script>
<template>
  <view class="reservation-page" data-testid="pickup-form">
    <button v-if="writeView.retry" class="reservation-primary" :disabled="write.busy" @tap="save.retry">{{ writeView.retryLabel }}</button>

    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">门店工作台</text>
      <text class="ux-title">接车检查</text>
      <text class="ux-lead">八张独立照片、油量与损伤逐项确认。提交后仍需车主确认与派工，本步不能开始施工。</text>
    </view>

    <view v-if="!merchantSession.accessToken" class="ux-card ux-in ux-r2" data-testid="pickup-login">
      <text class="ux-card-title">需要门店账号</text>
      <text class="ux-card-note">接车单只能由接单门店提交。</text>
      <button class="reservation-primary" @tap="login">前往门店登录</button>
    </view>

    <template v-else>
      <text v-if="read.busy" class="status-line" role="status">正在加载订单…</text>

      <view v-if="readFailure.message" class="ux-card ux-in ux-r2" role="status" data-testid="pickup-read-error">
        <view class="head">
          <text class="ux-card-title">{{ readFailure.title }}</text>
          <view class="ux-badge" :class="toneClass(readFailure.tone)"><text>{{ readFailure.conflict ? '已提交' : '可重试' }}</text></view>
        </view>
        <text class="ux-card-note">{{ readFailure.message }}</text>
        <button @tap="flow.load">{{ readFailure.retryLabel }}</button>
        <button v-if="readFailure.conflict" @tap="sheet">查看已提交接车单</button>
      </view>

      <template v-if="read.value">
        <view class="ux-card ux-in ux-r2" data-testid="pickup-order">
          <view class="head">
            <text class="ux-card-title">{{ facts.project }}</text>
            <view class="ux-badge" :class="toneClass(progress.complete ? 'ok' : 'warn')"><text>{{ progress.copy }}</text></view>
          </view>
          <text class="ux-card-note">{{ facts.appointmentCopy }}</text>
          <text class="ux-card-note">{{ facts.baseline.copy }}</text>
          <view class="ux-divider"></view>
          <text class="ux-card-note">{{ facts.guide }}</text>
          <text v-if="progress.blockedCopy" class="ux-card-note risk-line">{{ progress.blockedCopy }}</text>
        </view>

        <view v-for="row in slots" :key="row.slot" class="ux-card ux-in ux-r3" :data-testid="`pickup-slot-${row.slot}`">
          <view class="head">
            <text class="ux-card-title">{{ row.view.label }}</text>
            <view class="ux-badge" :class="toneClass(row.view.tone)"><text>{{ row.view.statusLabel }}</text></view>
          </view>
          <view v-if="row.photo.url" class="photo-canvas">
            <image :class="`pickup-photo-${row.slot}`" :src="row.photo.url" mode="widthFix" @tap="mark(row.slot, $event)" />
            <view v-for="dot in row.marks" :key="dot.no" class="damage-dot" :style="{ left: `${dot.x * 100}%`, top: `${dot.y * 100}%` }">{{ dot.no }}</view>
          </view>
          <text v-if="row.view.message" class="ux-card-note">{{ row.view.message }}</text>
          <button :disabled="disabled" @tap="choose(row.slot)">{{ row.view.actionLabel }}</button>
          <button v-if="row.view.canRetryUpload" :disabled="disabled" @tap="upload(row.slot)">原图重试上传</button>
          <button v-if="row.view.canPreview" :disabled="disabled" @tap="preview(row.slot)">预览 / 刷新图片</button>
        </view>

        <view class="ux-card ux-in ux-r3" data-testid="pickup-mileage">
          <view class="head">
            <text class="ux-card-title">仪表盘里程</text>
            <view class="ux-badge" :class="toneClass(mileage.tone)"><text>{{ mileage.needsReason ? '低于基线' : mileage.valid ? '已填写' : '待填写' }}</text></view>
          </view>
          <text class="ux-card-note">{{ facts.baseline.copy }}</text>
          <input v-model="form.mileage" class="field" type="number" maxlength="7" :disabled="disabled" placeholder="0–9999999" />
          <text v-if="mileage.deltaCopy" class="ux-card-note">{{ mileage.deltaCopy }}</text>
          <text class="ux-card-note">{{ mileage.hint }}</text>
          <text class="label">里程低于基线时填写原因</text>
          <textarea v-model="form.mileageReason" class="field area" maxlength="200" :disabled="disabled" placeholder="里程回退原因" />
        </view>

        <view class="ux-card ux-in ux-r3" data-testid="pickup-fuel">
          <view class="head">
            <text class="ux-card-title">油量与损伤</text>
            <view class="ux-badge" :class="toneClass(damage.tone)"><text>{{ damage.statusLabel }}</text></view>
          </view>
          <text class="label">油量</text>
          <picker :disabled="disabled" :value="fuel.index" :range="FUEL_LABELS" @change="form.fuel = FUEL_VALUES[Number($event.detail.value)]">
            <view class="picker-value">{{ fuel.label }}</view>
          </picker>
          <text class="label">损伤情况（必须明确选择）</text>
          <view class="row-2">
            <button :class="{ on: form.damageStatus === 'NONE' }" :disabled="disabled" @tap="form.damageStatus = 'NONE'; form.damages = []">无损伤</button>
            <button :class="{ on: form.damageStatus === 'PRESENT' }" :disabled="disabled" @tap="form.damageStatus = 'PRESENT'">有损伤</button>
          </view>
          <text class="ux-card-note">{{ damage.copy }}</text>
          <view v-for="(row, index) in form.damages" :key="index" class="damage-row">
            <text class="ux-card-note">{{ damageRowLabel(row, index) }}</text>
            <input v-model="row.note" class="field" maxlength="200" :disabled="disabled" placeholder="损伤说明（必填）" />
            <button :disabled="disabled" @tap="form.damages.splice(index, 1)">删除标注</button>
          </view>
        </view>

        <view class="ux-card ux-in ux-r3" data-testid="pickup-arrival">
          <text class="ux-card-title">到店与授权</text>
          <text class="ux-card-note">到店与预约时间偏差超过 2 小时需填写原因。</text>
          <textarea v-model="form.arrivalReason" class="field area" maxlength="200" :disabled="disabled" placeholder="提前或延迟到店原因" />
          <text class="label">车主提供的六位预约码</text>
          <input v-model="form.code" class="field" type="number" maxlength="6" :disabled="disabled" placeholder="保留前导零" />
        </view>

        <view class="ux-card ux-in ux-r3" data-testid="pickup-submit">
          <text v-if="message" class="risk-line" role="status">{{ message }}</text>
          <button v-if="permission" @tap="images.authorize">去开启相机 / 相册权限</button>
          <text v-if="writeView.message" class="risk-line" role="status">{{ writeView.message }}</text>
          <button v-if="writeView.canRelogin" @tap="login">重新登录</button>
          <text v-if="!submitView.ready && submitView.disabledCopy" class="ux-card-note">{{ submitView.disabledCopy }}</text>
          <text class="ux-card-note">{{ submitView.note }}</text>
          <button v-if="submitView.saved" class="reservation-primary" @tap="sheet">{{ writeView.savedLabel }}</button>
          <button v-else class="reservation-primary" :disabled="!submitView.ready" :loading="write.busy" @tap="submit">{{ submitView.label }}</button>
        </view>
      </template>
    </template>
  </view>
</template>

<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.label { display: block; margin-top: 20rpx; color: #63685e; font-size: 24rpx; letter-spacing: 1rpx; }
.field { margin-top: 14rpx; padding: 22rpx; border: 1rpx solid #e0ded6; border-radius: 12rpx; font-size: 26rpx; color: #454a41; background: #efede6; }
.area { width: auto; min-height: 140rpx; }
.row-2 { display: flex; gap: 12rpx; margin-top: 14rpx; }
.row-2 button { flex: 1; min-height: 68rpx; font-size: 26rpx; color: #454a41; background: #efede6; border: 1rpx solid transparent; border-radius: 999rpx; }
.row-2 button.on { color: #fcfbf8; background: #0b6b43; border-color: #0b6b43; }
.picker-value { margin-top: 14rpx; padding: 22rpx; background: #efede6; border-radius: 12rpx; font-size: 26rpx; color: #454a41; }
.photo-canvas { position: relative; margin-top: 14rpx; }
.photo-canvas image { display: block; width: 100%; border-radius: 12rpx; }
.damage-dot { position: absolute; transform: translate(-50%, -50%); width: 36rpx; height: 36rpx; border-radius: 50%; background: #b3261e; color: #fcfbf8; font-size: 20rpx; line-height: 36rpx; text-align: center; pointer-events: none; }
.damage-row { margin-top: 16rpx; padding-top: 16rpx; border-top: 1rpx solid #e0ded6; }
.risk-line { display: block; margin-top: 12rpx; color: #b3261e; font-size: 25rpx; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
