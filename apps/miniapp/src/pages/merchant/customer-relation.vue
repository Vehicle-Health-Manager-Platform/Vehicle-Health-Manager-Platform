<script setup>
import {reactive,ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {merchantSession,clearMerchantSession} from '../../services/merchant-session.js'
import {createCustomerRelationsApi,RELATION_SOURCES,customerRelationOrderRoute} from '../../services/customer-relations.js'
import {safeId} from '../../services/experience-publication.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../../services/async-diagnosis.js'
import {displayTime} from '../../services/reservations.js'
import {stateLabel} from '../../services/order-status.js'
const state=reactive(initialDiagnosisState()),id=ref(0),api=createCustomerRelationsApi(),token=()=>merchantSession.accessToken
const flow=createAsyncDiagnosisFlow({state,token,allowed:()=>safeId(id.value),scope:()=>`customer:${id.value}`,load:(t,p)=>api.detail(t,id.value,p)});let visible=false
function login(){clearMerchantSession();uni.navigateTo({url:'/pages/merchant/index'})}
function open(row,context=false){if(state.busy)return;const url=customerRelationOrderRoute(state.data,row.order_id,context);if(url)uni.navigateTo({url})}
watch(()=>[token(),id.value],()=>{flow.reset();if(visible&&token()&&safeId(id.value))flow.load()},{flush:'sync'})
onLoad(q=>{id.value=q&&Object.keys(q).length===1&&/^[1-9]\d*$/.test(String(q.id))&&safeId(Number(q.id))?Number(q.id):0});onShow(()=>{visible=true;if(token()&&safeId(id.value))flow.load()});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">本店订单关系</text><text>历史档案须由车主在本人订单逐条授权。撤销、源改动或工单失效后不再允许新读取。</text><text v-if="!id">本店服务关系入口无效。</text><button v-else-if="!token()" @tap="login">登录本店店长账号</button><template v-else><button :disabled="state.busy" @tap="flow.load(state.page)">刷新本店关系</button><text v-if="state.busy" role="status">正在重新核对本店范围…</text><text v-if="state.message" role="status">{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录店长账号</button><template v-if="state.data"><text>本店关系 {{state.data.relationship.relationship_order_id}} · {{RELATION_SOURCES[state.data.relationship.identity_source]}}</text><text>读取时点 {{displayTime(state.data.observed_at)}}。只显示当前有效账号、车辆归属及本店付后/完成状态订单记录。</text><view v-for="r in state.data.orders.items" :key="r.order_id" class="reservation-panel"><text class="reservation-heading">本店订单 {{r.order_id}} · {{stateLabel(r.order_status)}}</text><text>{{r.project_name||'项目未知'}}</text><text>{{r.vehicle.brand||'品牌未知'}} {{r.vehicle.series||'车系未知'}} {{r.vehicle.year||''}} {{r.vehicle.config_name||''}}</text><text>下单 {{displayTime(r.created_at)}}</text><text>核验接车 {{r.service_at?displayTime(r.service_at):'未知'}}</text><button :disabled="state.busy" @tap="open(r)">读取本店订单</button><button v-if="r.service_context_available" :disabled="state.busy" @tap="open(r,true)">重新核验本单已授权服务资料</button><text v-else>当前工单不提供历史档案读取入口。</text></view><button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">上一页订单</button><button v-if="state.page*20<state.data.orders.total" :disabled="state.busy" @tap="flow.load(state.page+1)">下一页订单</button></template></template><button @tap="uni.navigateTo({url:'/pages/merchant/customers'})">本店服务关系列表</button></view></template>
