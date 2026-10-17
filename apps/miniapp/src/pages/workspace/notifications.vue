<script setup>
import {reactive,ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {ownerSession,clearOwnerSession} from '../../services/owner-session.js'
import {merchantSession,clearMerchantSession} from '../../services/merchant-session.js'
import {technicianSession,clearTechnicianSession} from '../../services/technician-session.js'
import {NOTICE_LABELS,initialNotificationState,createNotificationFlow} from '../../services/notifications.js'
import {imageRequestKey} from '../../services/private-images.js'
import {displayTime} from '../../services/reservations.js'
import {stateLabel} from '../../services/order-status.js'
const role=ref(''),state=reactive(initialNotificationState()),token=()=>({owner:ownerSession,merchant:merchantSession,tech:technicianSession})[role.value]?.accessToken||''
const flow=createNotificationFlow({state,token,role:()=>role.value,newKey:imageRequestKey});let visible=false
function login(){if(role.value==='owner')clearOwnerSession();else if(role.value==='merchant')clearMerchantSession();else if(role.value==='tech')clearTechnicianSession();if(role.value)uni.navigateTo({url:role.value==='tech'?'/pages/technician/index':`/pages/${role.value}/index`})}
function open(id){const page=role.value==='owner'?'order/detail':role.value==='merchant'?'merchant/order-detail':'technician/order-detail';uni.navigateTo({url:`/pages/${page}?id=${id}`})}
watch(()=>[token(),role.value],()=>{flow.reset();if(visible&&token())flow.load()},{flush:'sync'})
onLoad(q=>{role.value=['owner','merchant','tech'].includes(q?.role)?q.role:''});onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">站内消息</text><text>显示当前身份有权查看的订单事件。门店账号共享本店消息及已读状态。</text><text v-if="!role">消息入口身份无效，请返回角色首页。</text><button v-if="role&&!token()" @tap="login">登录当前身份</button><text v-if="state.busy">正在核对消息…</text><text v-if="state.message">{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录</button><button v-if="state.retry" :disabled="state.busy" @tap="flow.retry">重试原已读操作</button><button :disabled="state.busy" @tap="flow.load(state.page)">刷新</button><template v-if="state.data"><text>未读 {{state.data.unread_count}} 条</text><text v-if="!state.data.notifications.items.length">暂无当前可见的站内消息。</text><view v-for="n in state.data.notifications.items" :key="n.notification_id" class="reservation-panel"><text>{{NOTICE_LABELS[n.kind]}}</text><text>订单 {{n.order_no}} · 当前{{stateLabel(n.order_status)}}</text><text>{{displayTime(n.occurred_at)}} · {{n.read_at?'已读':'未读'}}</text><button v-if="!n.read_at" :disabled="state.busy||state.retry" @tap="flow.read(n.notification_id)">标为已读</button><button :disabled="state.busy" @tap="open(n.order_id)">查看订单</button></view><button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">上一页</button><button v-if="state.page*20<state.data.notifications.total" :disabled="state.busy" @tap="flow.load(state.page+1)">下一页</button></template></view></template>
