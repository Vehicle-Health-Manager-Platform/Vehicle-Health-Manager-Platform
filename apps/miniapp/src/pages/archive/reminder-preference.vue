<script setup>
import { reactive,watch } from 'vue'
import { onLoad,onShow,onHide,onUnload } from '@dcloudio/uni-app'
import { ownerSession,clearOwnerSession } from '../../services/owner-session.js'
import { createHealthDashboardFlow,initialHealthDashboardState } from '../../services/health-dashboard.js'
import { aiServicePlanApi } from '../../services/ai-service-plan.js'
import { imageRequestKey } from '../../services/private-images.js'
const state=reactive(initialHealthDashboardState()),form=reactive({maintenance_days:'0',maintenance_km:'0',insurance_days:'0',inspection_days:'0',maintenance_project_id:null}),catalog=reactive({items:[],message:''})
let vehicle=0,visible=false,catalogGeneration=0
const flow=createHealthDashboardFlow({state,token:()=>ownerSession.accessToken,vehicle:()=>vehicle,newKey:imageRequestKey,confirm:()=>new Promise(resolve=>uni.showModal({title:'确认本人提前提醒偏好',content:'这些天数和里程是你的提前提示偏好，不代表车辆技术阈值；0表示只在本人目标到达后提示。保养项目仅是你选择的服务入口。',success:r=>resolve(!!r.confirm),fail:()=>resolve(false)}))})
watch(()=>state.value,value=>{if(value)for(const k of Object.keys(form))form[k]=k==='maintenance_project_id'?value.preference[k]:String(value.preference[k])})
async function load(){flow.load();const g=++catalogGeneration,actor=ownerSession.accessToken;catalog.items=[];catalog.message='';try{const r=await aiServicePlanApi.catalog(actor);if(g===catalogGeneration&&visible&&actor===ownerSession.accessToken)catalog.items=r.items}catch{if(g===catalogGeneration&&visible)catalog.message='服务目录暂不可用，可保留或清除原服务选择后重试'}}
function clear(){flow.reset();catalogGeneration++;catalog.items=[];catalog.message='';for(const k of Object.keys(form))form[k]=k==='maintenance_project_id'?null:'0'}
onLoad(q=>{vehicle=Number(q.vehicle_id)});onShow(()=>{visible=true;load()});onHide(()=>{visible=false;clear()});onUnload(clear);watch(()=>ownerSession.accessToken,()=>{clear();if(visible)load()},{flush:'sync'})
function save(){if(!state.value)return;const body={expected_revision:state.value.preference.revision,maintenance_project_id:form.maintenance_project_id};for(const k of ['maintenance_days','maintenance_km','insurance_days','inspection_days']){if(!/^\d+$/.test(form[k])){state.message='请填写非负整数，0表示不提前';return}body[k]=Number(form[k])}flow.preference(body)}
function login(){clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
</script>
<template><view class="reservation-page"><text class="reservation-title">本人提前提醒偏好</text><text class="reservation-copy">依照你已设置的保养、保险、年检目标计算；来源缺失或已关闭时，不产生提醒。日期最多提前365天，保养最多提前100000公里。</text>
 <view v-if="state.value" class="reservation-panel"><text>{{state.value.vehicle.model_name||'有效车型未知'}} · 偏好版本 {{state.value.preference.revision}}</text>
  <text>保养提前天数</text><input v-model="form.maintenance_days" :disabled="state.busy" type="number" class="reservation-field" />
  <text>保养提前里程（公里）</text><input v-model="form.maintenance_km" :disabled="state.busy" type="number" class="reservation-field" />
  <text>保险提前天数</text><input v-model="form.insurance_days" :disabled="state.busy" type="number" class="reservation-field" />
  <text>年检提前天数</text><input v-model="form.inspection_days" :disabled="state.busy" type="number" class="reservation-field" />
  <text class="reservation-heading">可选：本人保养目标对应的项目</text><text>只列前40个当前有效项目；也可以保留全部服务入口。该选择不代表技术必做。</text>
  <button :disabled="state.busy" @tap="form.maintenance_project_id=null">{{form.maintenance_project_id===null?'已选 · ':''}}查看全部服务</button>
  <button v-for="p in catalog.items" :key="p.project_id" :disabled="state.busy" @tap="form.maintenance_project_id=p.project_id">{{form.maintenance_project_id===p.project_id?'已选 · ':''}}{{p.project_name}}</button>
  <text v-if="form.maintenance_project_id&&!catalog.items.some(p=>p.project_id===form.maintenance_project_id)">原选择不在当前前40项中；保存时仍会复核是否有效，也可清除重选。</text>
  <button :disabled="state.busy" @tap="save">核对并保存本人偏好</button>
 </view><text v-if="catalog.message">{{catalog.message}}</text><text v-if="state.message" role="status">{{state.message}}</text><button :disabled="state.busy" @tap="load">重新读取</button><button v-if="!ownerSession.accessToken||['unauthorized','forbidden'].includes(state.kind)" @tap="login">登录车主账号</button><button @tap="uni.navigateBack()">返回</button>
</view></template>
