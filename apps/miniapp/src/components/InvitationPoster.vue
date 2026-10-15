<script setup>
import {reactive,watch,getCurrentInstance,onBeforeUnmount} from 'vue'
import {onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession} from '../services/owner-session.js'
import {merchantSession} from '../services/merchant-session.js'
import {initialPosterState,createPosterFlow,drawInvitationPoster} from '../services/invitation-poster.js'
const props=defineProps({code:{type:String,default:''},staff:{type:Boolean,default:false}})
const state=reactive(initialPosterState()),instance=getCurrentInstance(),canvasId=`invitePoster${instance.uid}`,owned=new Set()
const identity=()=>props.staff?merchantSession.accessToken:ownerSession.accessToken
function cleanup(p){if(!owned.delete(p))return;try{const fs=uni.getFileSystemManager?.();if(fs&&/^(wxfile:\/\/|https?:\/\/(?:usr|tmp)\/|\/)/.test(p))fs.unlink({filePath:p,fail:()=>{}})}catch{}}
const flow=createPosterFlow({state,identity,code:()=>props.code,draw:async c=>{const p=await drawInvitationPoster({code:c,staff:props.staff,canvasId,component:instance.proxy});owned.add(p);return p},cleanup,save:p=>new Promise((resolve,reject)=>{if(typeof uni.saveImageToPhotosAlbum!=='function')return reject(new Error('当前环境请预览图片后保存'));uni.saveImageToPhotosAlbum({filePath:p,success:resolve,fail:()=>reject(new Error('相册保存未完成，可预览图片或在系统设置中允许保存'))})})})
watch(()=>[props.code,identity()],flow.reset,{flush:'sync'});onHide(flow.reset);onUnload(flow.reset);onBeforeUnmount(flow.reset)
</script>
<template><view class="reservation-panel"><text>本地邀请海报</text><text>只展示随机推广码和输入方法。首次注册前输入邀请码，奖励以当时生效规则为准。</text><canvas :canvas-id="canvasId" :id="canvasId" style="width:640px;height:800px;position:fixed;left:-10000px;top:0;pointer-events:none"/><button :disabled="state.busy||!code" @tap="flow.generate">生成海报</button><template v-if="state.path"><button :disabled="state.busy" @tap="flow.preview">预览海报</button><button :disabled="state.busy" @tap="flow.save">保存到相册</button></template><view v-if="state.previewing"><image :src="state.path" mode="widthFix" style="width:100%"/><button @tap="flow.closePreview">收起预览</button></view><text v-if="state.message">{{state.message}}</text></view></template>
