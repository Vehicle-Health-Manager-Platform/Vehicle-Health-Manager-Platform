<script setup>
import {reactive,ref,computed,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {merchantSession,clearMerchantSession} from '../../services/merchant-session.js'
import {technicianSession,clearTechnicianSession} from '../../services/technician-session.js'
import {initialWorkspaceState,createWorkspaceFlow,TASK_BUCKETS,TASK_LABELS} from '../../services/service-workspace.js'
import {displayTime} from '../../services/reservations.js'
const role=ref(''),state=reactive(initialWorkspaceState()),valid=computed(()=>['merchant','tech'].includes(role.value))
const token=()=>role.value==='tech'?technicianSession.accessToken:role.value==='merchant'?merchantSession.accessToken:''
const flow=createWorkspaceFlow({state,kind:'bench',token,role:()=>role.value});let visible=false
function login(){if(role.value==='tech')clearTechnicianSession();else clearMerchantSession();uni.navigateTo({url:role.value==='tech'?'/pages/technician/index':'/pages/merchant/index'})}
function open(id){uni.navigateTo({url:role.value==='tech'?`/pages/technician/order-detail?id=${id}`:`/pages/merchant/order-detail?id=${id}`})}
watch(()=>[token(),role.value],()=>{flow.reset();if(visible&&token())flow.load()},{flush:'sync'})
onLoad(q=>{role.value=['merchant','tech'].includes(q?.role)?q.role:''});onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">{{role==='tech'?'我的技师工作台':'本店服务工作台'}}</text><text v-if="!valid">工作台入口身份无效，请返回角色入口。</text><template v-else><button v-if="!token()" @tap="login">登录当前身份</button><text>按当前订单与派工事实聚合。进入订单后仍需重新核对操作条件。</text><view v-if="state.data" class="reservation-panel"><button :disabled="state.busy" @tap="flow.load(1,'')">全部进行中任务</button><button v-for="b in TASK_BUCKETS" :key="b" :disabled="state.busy" @tap="flow.load(1,b)">{{TASK_LABELS[b]}} · {{state.data.counts[b]}}</button></view><text v-if="state.busy">正在读取当前任务…</text><text v-if="state.message">{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录</button><button :disabled="state.busy" @tap="flow.load(state.page)">刷新</button><template v-if="state.data"><text v-if="!state.data.tasks.items.length">当前分类暂无任务。</text><view v-for="t in state.data.tasks.items" :key="t.order_id" class="reservation-panel"><text>{{t.project_name||'项目名称未提供'}} · {{TASK_LABELS[t.bucket]}}</text><text v-if="t.starts_at">{{displayTime(t.starts_at)}}</text><button @tap="open(t.order_id)">进入本单</button></view><button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">上一页</button><button v-if="state.page*20<state.data.tasks.total" :disabled="state.busy" @tap="flow.load(state.page+1)">下一页</button></template></template></view></template>
