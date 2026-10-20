<script setup>
import {reactive,ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {initialOwnerEvidenceState,createOwnerEvidenceFlow,EVIDENCE_LABELS,EVENT_LABELS} from '../../services/owner-work-evidence.js'
import {displayTime} from '../../services/reservations.js'
import {stateLabel} from '../../services/order-status.js'
import {PICKUP_LABELS} from '../../services/pickup.js'
const state=reactive(initialOwnerEvidenceState()),order=ref(0),before=ref(0),after=ref(0),token=()=>ownerSession.accessToken
const flow=createOwnerEvidenceFlow({state,token,order:()=>order.value});let visible=false
watch(()=>state.data,()=>{before.value=0;after.value=0})
function imageFailed(){flow.closePreview();state.message='图片加载失败，请刷新证据后重试。'}
function login(){clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}
watch(()=>[token(),order.value],()=>{flow.reset();if(visible&&token())flow.load()},{flush:'sync'})
onLoad(q=>{order.value=/^[1-9]\d*$/.test(String(q?.id||''))?Number(q.id):0});onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">本人订单施工证据</text><text>按原始记录分类查看。施工前接车照与完工照可能角度不同，缺失照片不会自动补齐。</text><button v-if="!token()" @tap="login">车主登录</button><text v-if="state.busy">正在核对施工证据…</text><text v-if="state.message" role="status">{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录</button><button :disabled="state.busy" @tap="flow.load">刷新证据</button>
 <template v-if="state.data"><view class="reservation-panel"><text>可手动选择一张施工前接车照和一张施工后完工照；选择表示对比意图，不代表照片角度相同。</text><button :disabled="state.photoBusy||!before||!after" @tap="flow.compare(before,after)">查看所选前后对比</button></view><view class="reservation-panel"><text>当前订单：{{stateLabel(state.data.order_status)}}</text><text v-for="t in state.data.timeline" :key="t.event">{{displayTime(t.occurred_at)}} · {{EVENT_LABELS[t.event]}}</text><text v-if="!state.data.timeline.length">历史进度时间未提供。</text></view>
 <view v-for="g in state.data.groups" :key="g.kind" class="reservation-panel"><text class="reservation-heading">{{EVIDENCE_LABELS[g.kind]}}</text><text v-if="g.state==='MISSING'">尚无该类已记录证据。</text><text v-else-if="g.state==='UNKNOWN'">该类历史来源不完整或记录不可用，不能确认证据。</text><template v-else><text v-if="!g.photos.length">{{g.kind==='FAULT'&&state.data.report?.no_fault_parts?'本次报工明确记录无故障件。':'记录未提供可查看照片。'}}</text><view v-for="(p,i) in g.photos" :key="p.file_id"><button :disabled="state.photoBusy||!p.available" @tap="flow.preview(p.file_id)">{{p.slot?PICKUP_LABELS[p.slot]:'照片 '+(i+1)}}{{p.available?' · 查看':' · 当前不可用'}}</button><button v-if="p.available&&g.kind==='PICKUP'" :disabled="state.photoBusy" @tap="before=p.file_id">{{before===p.file_id?'已选为施工前':'选作施工前'}}</button><button v-if="p.available&&g.kind==='FINISH'" :disabled="state.photoBusy" @tap="after=p.file_id">{{after===p.file_id?'已选为施工后':'选作施工后'}}</button></view></template></view>
 <view v-if="state.data.report" class="reservation-panel"><text class="reservation-heading">{{state.data.report_state==='SIGNED'?'报工已完成质检签字':'已报工，尚未完成质检签字'}}</text><text>报工：{{displayTime(state.data.report.submitted_at)}}</text><text v-if="state.data.report.signed_at">签字：{{displayTime(state.data.report.signed_at)}}</text><text>维修方案：{{state.data.report.repair_plan}}</text><text>故障分析：{{state.data.report.fault_analysis}}</text><text>工时 {{state.data.report.work_minutes}} 分钟</text><text v-if="state.data.report.no_parts">本次明确记录未使用配件。</text><text v-for="(p,i) in state.data.report.parts_used" :key="i">{{p.name}} · {{p.model}} · {{p.brand}} × {{p.quantity}}</text></view>
 </template><view v-if="state.preview" class="evidence-preview"><text>图片预览 · 到期后可重新打开</text><scroll-view scroll-y class="evidence-preview-scroll"><view v-for="(p,i) in state.preview.images" :key="p.url"><text v-if="state.preview.images.length===2">{{i===0?'施工前（接车记录）':'施工后（完工记录）'}}</text><image :src="p.url" mode="widthFix" style="width:100%" @error="imageFailed"/></view></scroll-view><button @tap="flow.closePreview">关闭图片</button></view>
</view></template>

<style scoped>
.evidence-preview{position:fixed;top:0;right:0;bottom:0;left:0;z-index:9999;background:#fcfbf8;padding:24rpx;display:flex;flex-direction:column;gap:16rpx}.evidence-preview-scroll{flex:1;height:0;min-height:0}
</style>
