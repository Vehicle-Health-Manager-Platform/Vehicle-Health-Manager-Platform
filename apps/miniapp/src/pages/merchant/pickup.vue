<script setup>
import { reactive, ref, computed, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { merchantSession, clearMerchantSession } from '../../services/merchant-session.js'
import { pickupApi, pickupBody, PICKUP_SLOTS, PICKUP_LABELS, FUEL_VALUES, FUEL_LABELS } from '../../services/pickup.js'
import { merchantImageApi as images, imageRequestKey, DEVTOOLS_NOTICE } from '../../services/private-images.js'
import { createReservationReadFlow, initialReservationReadState, createReservationWriteFlow, initialReservationWriteState, displayTime } from '../../services/reservations.js'
const fresh = () => ({code:'',mileage:'',mileageReason:'',arrivalReason:'',fuel:'',damageStatus:'',damages:[],photos:Object.fromEntries(PICKUP_SLOTS.map(slot=>[slot,{candidate:null,key:'',fileId:0,url:'',message:''}]))})
const form=reactive(fresh()), read=reactive(initialReservationReadState()), write=reactive(initialReservationWriteState()), imageBusy=ref(false), message=ref(''), permission=ref(false)
let order=0, generation=0, visible=false
const token=()=>merchantSession.accessToken
const flow=createReservationReadFlow({state:read,token,request:t=>pickupApi.context(t,order)})
const save=createReservationWriteFlow({state:write,token,newKey:imageRequestKey,body:()=>pickupBody(order,form),request:(t,b,k)=>pickupApi.submit(t,b,k)})
const complete=computed(()=>PICKUP_SLOTS.every(slot=>form.photos[slot].fileId>0))
const disabled=computed(()=>imageBusy.value||write.busy||!!write.saved)
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
  if(disabled.value||form.damageStatus!=='PRESENT'||form.damages.length>=20)return
  const point=event.changedTouches?.[0]||event.touches?.[0]||event.detail
  const x=point?.clientX??point?.x,y=point?.clientY??point?.y,version=generation,fileId=form.photos[slot].fileId
  uni.createSelectorQuery().select(`.pickup-photo-${slot}`).boundingClientRect(rect=>{
    if(version!==generation||fileId!==form.photos[slot].fileId||form.damages.length>=20||disabled.value||!rect||!Number.isFinite(x)||!Number.isFinite(y)||!rect.width||!rect.height)return
    form.damages.push({photo_slot:slot,x:Math.max(0,Math.min(1,(x-rect.left)/rect.width)),y:Math.max(0,Math.min(1,(y-rect.top)/rect.height)),note:''})
  }).exec()
}
function submit(){
  try{pickupBody(order,form)}catch(error){write.message=error.message;return}
  const version=generation,actor=token();uni.showModal({title:'提交接车单',content:'确认检查信息完整？提交后不可修改，车主可以在订单中查看，车主确认功能随后接入。',success:r=>{if(r.confirm&&visible&&version===generation&&actor===token())save.save()}})
}
function sheet(){uni.redirectTo({url:`/pages/check/pickup-detail?id=${order}&role=merchant`})}
</script>
<template>
  <view class="reservation-page" data-testid="pickup-form">
    <text class="reservation-title">接车检查</text>
    <button v-if="!merchantSession.accessToken" @tap="login">前往商家登录</button>
    <text v-if="read.busy">正在加载订单…</text>
    <view v-if="read.message" class="reservation-panel"><text>{{read.message}}</text><button @tap="flow.load">重试加载</button><button v-if="read.failureKind==='conflict'" @tap="sheet">查看已提交接车单</button></view>
    <view v-if="read.value" class="reservation-panel">
      <text class="reservation-heading">{{read.value.order.project_snapshot?.project_name}}</text>
      <text>预约 {{displayTime(read.value.order.appointment_snapshot?.starts_at)}}</text>
      <text>比较里程 {{read.value.mileage_baseline.mileage}} km（{{read.value.mileage_baseline.source==='ARCHIVE'?'最近档案':'车辆里程'}}）</text>
      <text>逐项拍摄或选择真实照片，七张全部上传后提交。</text>
      <view v-for="slot in PICKUP_SLOTS" :key="slot" class="photo-block" :data-testid="`pickup-slot-${slot}`">
        <text class="reservation-heading">{{PICKUP_LABELS[slot]}}</text>
        <view v-if="form.photos[slot].url" class="photo-canvas">
          <image :class="`pickup-photo-${slot}`" :src="form.photos[slot].url" mode="widthFix" @tap="mark(slot,$event)" />
          <view v-for="(damage,index) in form.damages.filter(d=>d.photo_slot===slot)" :key="index" class="damage-dot" :style="{left:`${damage.x*100}%`,top:`${damage.y*100}%`}">{{index+1}}</view>
        </view>
        <text>{{form.photos[slot].message}}</text>
        <button :disabled="disabled" @tap="choose(slot)">{{form.photos[slot].fileId?'替换图片':'拍照 / 选图并上传'}}</button>
        <button v-if="form.photos[slot].candidate" :disabled="disabled" @tap="upload(slot)">原图重试上传</button>
        <button v-if="form.photos[slot].fileId" :disabled="disabled" @tap="preview(slot)">预览 / 刷新图片</button>
      </view>
      <text>手填仪表盘里程（km）</text><input v-model="form.mileage" type="number" maxlength="7" :disabled="disabled" placeholder="0–9999999" />
      <text v-if="form.mileage!==''">与当前基线差值 {{Number(form.mileage)-read.value.mileage_baseline.mileage}} km；提交时重新比对</text>
      <text>里程低于基线时填写原因</text><textarea v-model="form.mileageReason" maxlength="200" :disabled="disabled" placeholder="里程回退原因" />
      <text>油量</text><picker :disabled="disabled" :range="FUEL_LABELS" @change="form.fuel=FUEL_VALUES[Number($event.detail.value)]"><view class="picker-value">{{FUEL_LABELS[FUEL_VALUES.indexOf(form.fuel)]||'请选择油量'}}</view></picker>
      <text>损伤情况（必须明确选择）</text><view><button :disabled="disabled" @tap="form.damageStatus='NONE';form.damages=[]">{{form.damageStatus==='NONE'?'已选：':'选择'}}无损伤</button><button :disabled="disabled" @tap="form.damageStatus='PRESENT'">{{form.damageStatus==='PRESENT'?'已选：':'选择'}}有损伤</button></view>
      <text v-if="form.damageStatus==='PRESENT'">点击上方照片标注损伤位置，再填写说明，最多 20 处。替换照片会清除该照片标注。</text>
      <view v-for="(damage,index) in form.damages" :key="index"><text>{{PICKUP_LABELS[damage.photo_slot]}} · 标注 {{index+1}}</text><input v-model="damage.note" maxlength="200" :disabled="disabled" placeholder="损伤说明（必填）" /><button :disabled="disabled" @tap="form.damages.splice(index,1)">删除标注</button></view>
      <text>到店与预约时间偏差超过 2 小时需填写原因</text><textarea v-model="form.arrivalReason" maxlength="200" :disabled="disabled" placeholder="提前或延迟到店原因" />
      <text>车主提供的六位预约码</text><input v-model="form.code" type="number" maxlength="6" :disabled="disabled" placeholder="保留前导零" />
      <text v-if="message" role="status">{{message}}</text><button v-if="permission" @tap="images.authorize">去开启相机 / 相册权限</button>
      <text v-if="write.message" role="status">{{write.message}}</text><button v-if="['unauthorized','forbidden'].includes(write.failureKind)" @tap="login">重新登录</button>
      <button v-if="write.saved" @tap="sheet">查看接车单</button><button v-else :disabled="disabled||!complete" :loading="write.busy" @tap="submit">提交完整接车单</button>
      <text>提交后仍需车主确认和派工，本步不能开始施工。</text>
    </view>
  </view>
</template>
<style src="../../styles/reservations.css"></style>
<style scoped>
.photo-block{display:flex;flex-direction:column;gap:12rpx;padding:20rpx 0;border-bottom:1px solid #e5e6eb}.photo-canvas{position:relative}.photo-canvas image{display:block;width:100%}.damage-dot{position:absolute;transform:translate(-50%,-50%);width:36rpx;height:36rpx;border-radius:50%;background:#d33;color:white;text-align:center;pointer-events:none}.picker-value,input,textarea{padding:16rpx;background:#f2f3f5;border-radius:8rpx}textarea{width:auto}
</style>
