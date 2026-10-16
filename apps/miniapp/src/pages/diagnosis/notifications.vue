<script setup>
import {reactive,ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {operatorSession,operatorToken,clearOperatorSession} from '../../services/operator-session.js'
import {initialNotificationState,createNotificationFlow} from '../../services/notifications.js'
import {createDiagnosisNotificationApi,DIAGNOSIS_NOTICE_LABELS,diagnosisNotificationUrl} from '../../services/diagnosis-notifications.js'
import {CASE_STATES} from '../../services/async-diagnosis.js'
import {imageRequestKey} from '../../services/private-images.js'
import {displayTime} from '../../services/reservations.js'
const role=ref(''),state=reactive(initialNotificationState()),allowed=()=>role.value==='owner'||role.value==='admin'&&(operatorSession.canDiagnose||operatorSession.canDiagnoseManage),token=()=>!allowed()?'':role.value==='owner'?ownerSession.accessToken:operatorToken()
const flow=createNotificationFlow({state,token,role:()=>role.value,api:createDiagnosisNotificationApi(),newKey:imageRequestKey});let visible=false
function login(){if(role.value==='owner'){clearOwnerSession();uni.navigateTo({url:'/pages/owner/index'})}else if(role.value==='admin'){clearOperatorSession();uni.navigateTo({url:'/pages/operator/login'})}}
function open(n){if(!token()||state.busy||!state.data?.notifications.items.some(i=>i.notification_id===n.notification_id))return;const url=diagnosisNotificationUrl(n,role.value);if(url)uni.navigateTo({url})}
watch(()=>[token(),role.value,allowed(),operatorSession.canDiagnose,operatorSession.canDiagnoseManage],()=>{flow.reset();if(visible&&token())flow.load()},{flush:'sync'})
onLoad(q=>{role.value=['owner','admin'].includes(q?.role)?q.role:''});onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">诊断站内消息</text><text>仅显示当前有权查看的诊断事件。管理提示只进入队列，正文需再次核对当前分配与处理权限。</text><text v-if="!allowed()">当前身份无诊断消息权限，请返回诊断工作区。</text><button v-if="role&&!token()" @tap="login">登录当前身份</button><text v-if="state.busy">正在核对诊断消息…</text><text v-if="state.message">{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录</button><button v-if="state.retry" :disabled="state.busy" @tap="flow.retry">重试原已读操作</button><button :disabled="state.busy||!token()" @tap="flow.load(state.page)">刷新</button><template v-if="state.data"><text>未读 {{state.data.unread_count}} 条</text><text v-if="!state.data.notifications.items.length">暂无当前可见的诊断消息。</text><view v-for="n in state.data.notifications.items" :key="n.notification_id" class="reservation-panel"><text>{{DIAGNOSIS_NOTICE_LABELS[n.kind]}}</text><text>诊断记录 {{n.case_id}} · 当前{{CASE_STATES[n.case_status]}}</text><text>{{displayTime(n.occurred_at)}} · {{n.read_at?'已读':'未读'}}</text><button v-if="!n.read_at" :disabled="state.busy||state.retry" @tap="flow.read(n.notification_id)">标为已读</button><button :disabled="state.busy" @tap="open(n)">{{n.target==='QUEUE'?'进入管理队列':'按当前权限查看记录'}}</button></view><button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">上一页</button><button v-if="state.page*20<state.data.notifications.total" :disabled="state.busy" @tap="flow.load(state.page+1)">下一页</button></template></view></template>
