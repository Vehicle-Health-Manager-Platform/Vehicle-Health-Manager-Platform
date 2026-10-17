<script setup>
import { reactive, ref, watch } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import VehicleList from '../../components/VehicleList.vue'
import { ownerSession, clearOwnerSession } from '../../services/owner-session.js'
import { selectedOwnerVehicle, selectOwnerVehicle } from '../../services/owner-vehicle-selection.js'
import { publicationApi, initialPublicationState, createPublicationFlow } from '../../services/experience-publication.js'
import {createExperienceDetailApi,experienceReferenceOptions,experienceReferenceRoute,validExperienceReference,parseExperienceReference} from '../../services/experience-detail.js'
import {createCurationApi} from '../../services/experience-curations.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../../services/async-diagnosis.js'
import {safeId} from '../../services/experience-publication.js'
const vehicle = selectedOwnerVehicle, state = reactive(initialPublicationState())
const flow = createPublicationFlow({ state, api: publicationApi, token: () => ownerSession.accessToken, vehicle: () => vehicle.value?.vehicle_id })
const reference=ref(''),routeValid=ref(true),detail=reactive(initialDiagnosisState()),detailApi=createExperienceDetailApi()
const referenceDraft=ref(''),importMessage=ref(''),curated=reactive(initialDiagnosisState()),curationApi=createCurationApi()
const curatedFlow=createAsyncDiagnosisFlow({state:curated,token:()=>ownerSession.accessToken,allowed:()=>routeValid.value&&safeId(vehicle.value?.vehicle_id),scope:()=>`curated:${ownerSession.actorId}:${vehicle.value?.vehicle_id}:${vehicle.value?.model_id}`,load:t=>curationApi.curated(t,vehicle.value.vehicle_id,vehicle.value.model_id||null)})
const detailAllowed=()=>routeValid.value&&safeId(vehicle.value?.vehicle_id)&&safeId(vehicle.value?.model_id)&&validExperienceReference(reference.value)
const detailFlow=createAsyncDiagnosisFlow({state:detail,token:()=>ownerSession.accessToken,allowed:detailAllowed,scope:()=>`${ownerSession.actorId}:${vehicle.value?.vehicle_id}:${vehicle.value?.model_id}:${reference.value}`,load:t=>detailApi.detail(t,vehicle.value.vehicle_id,reference.value,vehicle.value.model_id)})
let visible = false
function start() { if(!routeValid.value)return;flow.resume(); flow.load();detailFlow.load();curatedFlow.load() }
function refresh(){detailFlow.reset();curatedFlow.reset();start()}
function open(card){if(state.busy||detail.busy||!ownerSession.accessToken||!state.rows.includes(card))return;if(reference.value===card.experience_id){detailFlow.reset();detailFlow.load()}else reference.value=card.experience_id}
function copyReference(){if(detail.busy||!detailAllowed()||!ownerSession.accessToken||!detail.data)return;const url=experienceReferenceRoute(detail.data.experience.experience_id);if(url)uni.setClipboardData({data:url})}
function openCurated(row){if(curated.busy||detail.busy||!ownerSession.accessToken||!curated.data?.items.includes(row))return;if(reference.value===row.experience.experience_id){detailFlow.reset();detailFlow.load()}else reference.value=row.experience.experience_id}
function importReference(){if(detail.busy||curated.busy||state.busy)return;const id=parseExperienceReference(referenceDraft.value);if(!id){importMessage.value='请粘贴完整的应用内经验引用入口';return}referenceDraft.value='';importMessage.value='';const wasInvalid=!routeValid.value;routeValid.value=true;if(reference.value===id){detailFlow.reset();detailFlow.load()}else reference.value=id;if(wasInvalid){flow.resume();flow.load();curatedFlow.load()}}
function login() { clearOwnerSession(); uni.navigateTo({ url: '/pages/owner/index' }) }
watch(() => [ownerSession.accessToken,vehicle.value?.vehicle_id,vehicle.value?.model_id], () => { flow.reset();detailFlow.reset();curatedFlow.reset();referenceDraft.value='';importMessage.value=''; if (visible) start() }, { flush: 'sync' })
watch(reference,()=>{detailFlow.reset();if(visible)detailFlow.load()},{flush:'sync'})
watch(()=>detail.kind,kind=>{if(['unauthorized','forbidden'].includes(kind)){flow.reset();curatedFlow.reset()}else if(kind==='missing'){state.rows=state.rows.filter(c=>c.experience_id!==reference.value);if(curated.data)curatedFlow.reset()}},{flush:'sync'})
watch(()=>state.kind,kind=>{if(['unauthorized','forbidden','missing'].includes(kind)){detailFlow.reset();curatedFlow.reset();state.rows=[];state.loaded=false;state.cursor=null}},{flush:'sync'})
watch(()=>curated.kind,kind=>{if(['unauthorized','forbidden','missing'].includes(kind)){detailFlow.reset();flow.reset()}},{flush:'sync'})
onLoad(q=>{const parsed=experienceReferenceOptions(q||{});routeValid.value=parsed.valid;reference.value=parsed.reference})
function hide(){visible=false;flow.reset();detailFlow.reset();curatedFlow.reset();referenceDraft.value='';importMessage.value=''}
onShow(() => { visible = true; start() }); onHide(hide); onUnload(hide)
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">同款施工经验</text>
    <button @tap="uni.navigateTo({url:'/pages/community/questions'})">我的私有车辆问答（非实时）</button>
    <text class="reservation-copy">同一车型、车主授权且审核通过的施工摘要。只展示工时与配件种类，不含照片、施工原文和个人身份。</text>
    <button v-if="!ownerSession.accessToken" @tap="login">前往车主登录</button>
    <text v-if="!routeValid">经验引用入口无效，请从当前获批摘要重新进入。</text>
    <view class="reservation-panel"><text class="reservation-heading">打开收到的经验引用</text><input v-model="referenceDraft" maxlength="256" :disabled="detail.busy||state.busy||curated.busy" placeholder="手动粘贴应用内经验引用入口"/><button :disabled="detail.busy||state.busy||curated.busy" @tap="importReference">核验粘贴的引用</button><text v-if="importMessage" role="status">{{importMessage}}</text></view>
    <VehicleList :selected-id="vehicle?.vehicle_id || 0" @select="selectOwnerVehicle" />
    <text v-if="vehicle && !vehicle.model_id">当前车辆缺少车型信息，暂不能匹配同款经验。</text>
    <view v-if="vehicle" class="reservation-panel"><text class="reservation-heading">运营精选（最多20条）</text><text>由运营按来源、排序和有效期整理；同款经验仅供参考。</text><text v-if="curated.busy" role="status">正在核验运营精选…</text><text v-if="curated.message" role="status">{{curated.message}}</text><template v-if="curated.data"><text v-if="curated.data.state==='UNKNOWN'">当前没有可核验的有效精选。</text><text v-else-if="curated.data.state==='UNAVAILABLE'">经验展示暂未开启。</text><view v-for="row in curated.data.items" :key="row.curation_id"><text>{{row.experience.summary.recorded_month}} · 工时 {{row.experience.summary.work_minutes}} 分钟 · 配件种类 {{row.experience.summary.part_kinds}}</text><button :disabled="curated.busy||detail.busy" @tap="openCurated(row)">核验精选经验引用</button></view></template></view>
    <view v-if="routeValid && reference" class="reservation-panel">
      <text class="reservation-heading">引用的当前有效详情</text>
      <text>引用入口只指向获批摘要；登录并选择本人同款车辆后，每次重新核验授权与来源。</text>
      <text v-if="detail.busy" role="status">正在核验此经验…</text>
      <text v-if="detail.message" role="status">{{detail.message}}</text>
      <text v-if="!vehicle">请选择本人车辆以读取引用。</text>
      <template v-if="detail.data">
        <text>{{detail.data.experience.title}} · {{detail.data.experience.summary.recorded_month}}</text>
        <text>实际工时 {{detail.data.experience.summary.work_minutes}} 分钟 · 配件种类 {{detail.data.experience.summary.part_kinds}} · {{detail.data.experience.summary.no_parts?'未使用配件':'使用配件'}}</text>
        <text>获批时间 {{detail.data.experience.published_at.slice(0,10)}}；同款施工摘要不能证明本车故障或必做项目。</text>
        <button :disabled="detail.busy" @tap="copyReference">复制应用内引用入口</button>
      </template>
      <button v-if="detailAllowed() && ownerSession.accessToken" :disabled="detail.busy" @tap="detailFlow.load()">重新核验此引用</button>
      <button v-if="['unauthorized','forbidden'].includes(detail.kind)" @tap="login">重新登录车主账号</button>
    </view>
    <text v-if="state.busy" role="status">正在查询同款经验…</text>
    <text v-else-if="state.loaded && !state.rows.length">暂无可展示的同款摘要。车主撤回或来源变化的记录会隐藏。</text>
    <view v-for="card in state.rows" :key="card.experience_id" class="reservation-panel" data-testid="public-experience">
      <text class="reservation-heading">{{ card.title }}</text>
      <text>{{ card.summary.recorded_month }} · 实际工时 {{ card.summary.work_minutes }} 分钟</text>
      <text>{{ card.summary.no_parts ? '本次未使用配件' : `使用 ${card.summary.part_kinds} 种配件` }}</text>
      <text>已授权并通过审核 · {{ card.published_at.slice(0,10) }}</text>
      <button :disabled="state.busy || detail.busy" @tap="open(card)">查看当前有效详情</button>
    </view>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录车主账号</button>
    <button v-if="vehicle" :disabled="state.busy || detail.busy || curated.busy" @tap="refresh">刷新同款经验与引用</button>
    <button v-if="state.cursor !== null" :disabled="state.busy" @tap="flow.load(true)">继续查询</button>
    <button @tap="uni.navigateTo({url:'/pages/community/my-cards'})">管理我的经验授权</button>
  </view>
</template>
