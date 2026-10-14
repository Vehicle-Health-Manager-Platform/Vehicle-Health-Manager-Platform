<script setup>
import {getCurrentInstance,nextTick,onMounted,onBeforeUnmount,ref,watch} from 'vue'
import qrcode from 'qrcode-generator'
import {serviceCouponQr} from '../services/coupon-transactions.js'
const props=defineProps({authorization:{type:Object,required:true}}),instance=getCurrentInstance().proxy,message=ref('')
let mounted=false,epoch=0
async function draw(){const g=++epoch;if(!mounted)return;await nextTick();if(g!==epoch)return;try{const qr=qrcode(0,'M');qr.addData(serviceCouponQr(props.authorization),'Byte');qr.make();const size=qr.getModuleCount(),unit=Math.floor(240/(size+8)),offset=Math.floor((240-size*unit)/2),canvas=uni.createCanvasContext('service-coupon-qr',instance);canvas.setFillStyle('#fff');canvas.fillRect(0,0,240,240);canvas.setFillStyle('#000');for(let y=0;y<size;y++)for(let x=0;x<size;x++)if(qr.isDark(y,x))canvas.fillRect(offset+x*unit,offset+y*unit,unit,unit);canvas.draw();message.value=''}catch{message.value='授权二维码未生成，可向本店提供下方授权信息'}}
watch(()=>props.authorization,draw,{deep:true});onMounted(()=>{mounted=true;draw()});onBeforeUnmount(()=>{mounted=false;epoch++})
</script>
<template><view class="service-code"><canvas canvas-id="service-coupon-qr" id="service-coupon-qr" class="service-code-canvas"/><text v-if="message">{{ message }}</text></view></template>
<style scoped>.service-code{display:flex;flex-direction:column;align-items:center;gap:16rpx}.service-code-canvas{width:240px;height:240px;background:#fff}</style>
