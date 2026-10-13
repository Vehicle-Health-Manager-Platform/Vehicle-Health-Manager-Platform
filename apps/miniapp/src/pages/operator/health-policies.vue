<script setup>
import { reactive,ref,watch } from 'vue'
import { onHide,onUnload } from '@dcloudio/uni-app'
import { operatorSession,operatorToken,clearOperatorSession } from '../../services/operator-session.js'
import { createHealthPolicyFlow,initialHealthPolicyState,validHealthRule } from '../../services/health-dashboard.js'
import { healthMetrics,healthLabels,healthUnits,unitLabels,provenanceValues,provenanceLabels } from '../../services/health-observations.js'
import { imageRequestKey } from '../../services/private-images.js'
const model=ref(''),metric=ref('OIL'),state=reactive(initialHealthPolicyState())
const blank=()=>({applicability:'APPLICABLE',source_reference:'',source_checked_on:'',valid_until:'',source_confirmed:false,measurement_name:'',unit:'',method:'',provenance:'',max_age_days:'',weight:'',bands:'',action_project_id:'',action_score_below:''}),form=reactive(blank())
const flow=createHealthPolicyFlow({state,token:operatorToken,selection:()=>({model:Number(model.value),metric:metric.value}),newKey:imageRequestKey,confirm:(s,b)=>new Promise(resolve=>uni.showModal({title:b.rule===null?'撤销来源规则':'确认来源规则发布',content:b.rule===null?'撤销后对应参考分与规则建议未知；版本及审计保留。':`车型 #${s.model} / ${healthLabels[s.metric]}\n来源：${b.rule.source_reference}\n确认资料、适用车型、测量口径、有效期与阈值均已人工核对。本平台不自动核验来源真实性，不得把示例值作为技术阈值。`,success:r=>resolve(!!r.confirm),fail:()=>resolve(false)}))})
watch(()=>[model.value,metric.value],()=>{flow.reset();Object.assign(form,blank())},{flush:'sync'})
watch(()=>state.value,value=>{Object.assign(form,blank());if(value?.rule){const r=value.rule;for(const k of Object.keys(form))form[k]=k==='source_confirmed'?r[k]:r[k]===null?'':k==='bands'?r.bands.map(b=>(b.upper_bound===null?'∞':b.upper_bound)+','+b.score).join('\n'):String(r[k])}})
watch(()=>JSON.stringify({...form,source_confirmed:undefined}),()=>{if(!state.busy&&form.source_confirmed)form.source_confirmed=false},{flush:'sync'})
function reset(){flow.reset();model.value='';metric.value='OIL';Object.assign(form,blank())};onHide(reset);onUnload(reset);watch(()=>operatorSession.accessToken,reset,{flush:'sync'})
function build(){const applicable=form.applicability==='APPLICABLE',rule={applicability:form.applicability,source_reference:form.source_reference.trim(),source_checked_on:form.source_checked_on,valid_until:form.valid_until,source_confirmed:form.source_confirmed,measurement_name:applicable?form.measurement_name.trim():null,unit:applicable?form.unit:null,method:applicable?form.method.trim():null,provenance:applicable?form.provenance:null,max_age_days:applicable?Number(form.max_age_days):0,weight:applicable?Number(form.weight):0,bands:[],action_project_id:applicable&&form.action_project_id?Number(form.action_project_id):null,action_score_below:applicable&&form.action_project_id?Number(form.action_score_below):null};if(applicable){rule.bands=form.bands.split('\n').map(s=>s.trim()).filter(Boolean).map(s=>{const cells=s.split(',').map(x=>x.trim());if(cells.length!==2||!/^\d+$/.test(cells[1]))throw Error('请按每行“上限,分数”填写；最后一行用∞作为上限');return{upper_bound:cells[0]==='∞'?null:cells[0],score:Number(cells[1])}})}if(!validHealthRule(rule,metric.value))throw Error('请填写完整来源、确认状态、日期、匹配口径和有序阈值；不适用无需测量评分字段');return rule}
function save(){try{flow.save(build())}catch(e){state.message=e.message;state.kind='invalid'}}
function login(){reset();clearOperatorSession();uni.navigateTo({url:'/pages/operator/login'})}
</script>
<template><view class="reservation-page"><text class="reservation-title">精确车型健康来源规则</text><text class="reservation-copy">需目录维护权限；无规则默认未知。必须核对资料来源、精确车型、测量口径、有效期和评分阈值，不内置生产技术值。</text>
 <text v-if="!operatorSession.canCatalog">当前账号没有目录维护权限。</text><view v-else class="reservation-panel">
  <text>精确车型编号（目录维护页可查看）</text><input v-model="model" type="number" class="reservation-field" :disabled="state.busy" />
  <picker :range="healthMetrics.map(m=>healthLabels[m])" :disabled="state.busy" @change="metric=healthMetrics[Number($event.detail.value)]"><view class="reservation-field">{{healthLabels[metric]}} · 选择指标</view></picker>
  <button :disabled="state.busy" @tap="flow.load()">读取当前版本与规则</button>
  <template v-if="state.value"><text>当前版本 {{state.value.version}} · {{state.value.rule?'存在来源规则':'未配置/已撤销'}}</text>
   <button :disabled="state.busy" @tap="form.applicability='APPLICABLE'">{{form.applicability==='APPLICABLE'?'已选 · ':''}}本车型适用</button><button :disabled="state.busy" @tap="form.applicability='NOT_APPLICABLE'">{{form.applicability==='NOT_APPLICABLE'?'已选 · ':''}}本车型不适用</button>
   <text>资料名称、位置及精确适用车型（1–256字）</text><textarea v-model="form.source_reference" :disabled="state.busy" maxlength="512" class="reservation-field" />
   <text>来源人工核对日期</text><picker mode="date" :value="form.source_checked_on" :disabled="state.busy" @change="form.source_checked_on=$event.detail.value"><view class="reservation-field">{{form.source_checked_on||'选择日期'}}</view></picker>
   <text>来源规则有效截止日</text><picker mode="date" :value="form.valid_until" :disabled="state.busy" @change="form.valid_until=$event.detail.value"><view class="reservation-field">{{form.valid_until||'选择日期'}}</view></picker>
   <template v-if="form.applicability==='APPLICABLE'">
    <text>读数名称（须与本人来源一致）</text><input v-model="form.measurement_name" :disabled="state.busy" class="reservation-field" />
    <picker :range="healthUnits[metric].map(u=>unitLabels[u])" :disabled="state.busy" @change="form.unit=healthUnits[metric][Number($event.detail.value)]"><view class="reservation-field">{{unitLabels[form.unit]||'选择单位'}}</view></picker>
    <text>测量方法/资料位置（须与本人来源完全一致）</text><input v-model="form.method" :disabled="state.busy" class="reservation-field" />
    <picker :range="provenanceLabels" :disabled="state.busy" @change="form.provenance=provenanceValues[Number($event.detail.value)]"><view class="reservation-field">{{provenanceLabels[provenanceValues.indexOf(form.provenance)]||'选择测量/抄录口径'}}</view></picker>
    <text>读数有效天数（1–3650，需来自核对规则）</text><input v-model="form.max_age_days" type="number" :disabled="state.busy" class="reservation-field" />
    <text>参考分权重（1–100）</text><input v-model="form.weight" type="number" :disabled="state.busy" class="reservation-field" />
    <text>评分分段：每行“上限,分数”；上限严格递增且含本上限，末行用“∞,分数”；分数0–100，阈值须有来源。</text><textarea v-model="form.bands" :disabled="state.busy" maxlength="2000" class="reservation-field" />
    <text>可选规则项目编号；需同时维护项目—车型适用性为适用，且该项目当前发布。</text><input v-model="form.action_project_id" type="number" :disabled="state.busy" class="reservation-field" />
    <text>参考分严格低于此值才触发规则必做候选（1–100；无项目则留空）</text><input v-model="form.action_score_below" type="number" :disabled="state.busy" class="reservation-field" />
   </template>
   <text>我已人工核对该来源及全部配置；编辑后需再次勾选</text><switch :checked="form.source_confirmed" :disabled="state.busy" @change="form.source_confirmed=$event.detail.value" />
   <button :disabled="state.busy" @tap="save">核对并确认发布</button><button :disabled="state.busy||!state.value.rule" @tap="flow.save(null)">撤销当前规则</button>
  </template>
 </view><text v-if="state.message" role="status">{{state.message}}</text><button v-if="!operatorSession.accessToken||['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录运营账号</button><button @tap="uni.navigateBack()">返回标准库</button>
</view></template>
