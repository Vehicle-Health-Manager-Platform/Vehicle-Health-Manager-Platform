<script setup>
import {reactive,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {merchantSession,clearMerchantSession} from '../../services/merchant-session.js'
import {createCustomerRelationsApi,RELATION_SOURCES} from '../../services/customer-relations.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../../services/async-diagnosis.js'
import {displayTime} from '../../services/reservations.js'
const state=reactive(initialDiagnosisState()),api=createCustomerRelationsApi(),token=()=>merchantSession.accessToken
const flow=createAsyncDiagnosisFlow({state,token,scope:()=>`customers:${token()}`,load:(t,p)=>api.list(t,p)});let visible=false
function login(){clearMerchantSession();uni.navigateTo({url:'/pages/merchant/index'})}
function open(row){if(!state.busy&&state.data?.relationships.items.some(v=>v.relationship_order_id===row.relationship_order_id))uni.navigateTo({url:`/pages/merchant/customer-relation?id=${row.relationship_order_id}`})}
watch(token,()=>{flow.reset();if(visible&&token())flow.load()},{flush:'sync'})
onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">本店服务关系</text><text>仅店长查看本店订单关系。身份来源不代表有效新客业绩或支付核验，不提供联系方式。</text><button v-if="!token()" @tap="login">登录本店店长账号</button><template v-else><button :disabled="state.busy" @tap="flow.load()">刷新服务关系</button><text v-if="state.busy" role="status">正在核对本店服务关系…</text><text v-if="state.message" role="status">{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录店长账号</button><template v-if="state.data"><text>读取时点 {{displayTime(state.data.observed_at)}}</text><text v-if="!state.data.relationships.items.length">当前没有可读取的本店服务关系。</text><view v-for="r in state.data.relationships.items" :key="r.relationship_order_id" class="reservation-panel"><text class="reservation-heading">本店关系 · {{r.relationship_order_id}}</text><text>{{RELATION_SOURCES[r.identity_source]}} · 本店服务订单 {{r.order_count}} 笔</text><text>最近订单车型：{{r.latest_vehicle.brand||'未知品牌'}} {{r.latest_vehicle.series||'未知车系'}} {{r.latest_vehicle.year||''}} {{r.latest_vehicle.config_name||''}}</text><text>最近下单：{{displayTime(r.last_order_at)}}</text><text>最近核验接车：{{r.last_service_at?displayTime(r.last_service_at):'未知'}}</text><button :disabled="state.busy" @tap="open(r)">查看本店订单关系</button></view><button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">上一页</button><button v-if="state.page*20<state.data.relationships.total" :disabled="state.busy" @tap="flow.load(state.page+1)">下一页</button></template></template><button @tap="uni.navigateTo({url:'/pages/merchant/index'})">门店工作入口</button></view></template>
