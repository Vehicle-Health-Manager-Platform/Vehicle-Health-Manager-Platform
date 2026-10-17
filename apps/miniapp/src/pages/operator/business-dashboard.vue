<script setup>
import {reactive,ref,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import BusinessDashboardView from '../../components/BusinessDashboardView.vue'
import {operatorSession,operatorToken,clearOperatorSession} from '../../services/operator-session.js'
import {createBusinessDashboardApi,currentBusinessMonth} from '../../services/business-dashboard.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../../services/async-diagnosis.js'
const state=reactive(initialDiagnosisState()),selected=ref(null),month=ref(currentBusinessMonth()),api=createBusinessDashboardApi(),allowed=()=>operatorSession.canAnalytics
const flow=createAsyncDiagnosisFlow({state,token:operatorToken,allowed,scope:()=>`business:${operatorSession.actorId}`,load:async(t,p)=>{const [merchants,dashboard]=await Promise.all([api.merchants(t,p),selected.value?api.dashboard(t,'admin',selected.value.id,month.value):null]);return {merchants,dashboard}}});let visible=false
function reset(){flow.reset();selected.value=null;month.value=currentBusinessMonth()}
function choose(m){if(state.busy)return;selected.value={...m};flow.load(state.page)}
function login(){clearOperatorSession();uni.navigateTo({url:'/pages/operator/login'})}
watch(()=>[operatorSession.accessToken,allowed()],()=>{reset();if(visible&&operatorToken()&&allowed())flow.load()},{flush:'sync'})
onShow(()=>{visible=true;if(operatorToken()&&allowed())flow.load()});onHide(()=>{visible=false;reset()});onUnload(()=>{visible=false;reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">门店经营看板</text><text>只读系统记录，不执行月结、支付或退款。</text><button v-if="!operatorToken()" @tap="login">运营登录</button><text v-else-if="!allowed()">当前账号没有独立经营看板权限。</text><template v-else><input v-model="month" :disabled="state.busy" maxlength="7" placeholder="北京时间月份 YYYY-MM"/><button :disabled="state.busy" @tap="flow.load(state.page)">读取当前选择</button><view v-if="state.busy" class="reservation-panel" aria-busy="true"><text role="status">正在核验经营来源…</text></view><text v-if="state.message" role="status">{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录</button><template v-if="state.data"><view class="reservation-panel"><text class="reservation-heading">有效门店</text><text v-if="!state.data.merchants.items.length">没有可读取的有效门店。</text><view v-for="m in state.data.merchants.items" :key="m.id"><text>{{m.name}} · {{m.address}}</text><button :disabled="state.busy" @tap="choose(m)">读取此门店</button></view><button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">上一页门店</button><button v-if="state.page*20<state.data.merchants.total" :disabled="state.busy" @tap="flow.load(state.page+1)">下一页门店</button></view><BusinessDashboardView v-if="state.data.dashboard" :value="state.data.dashboard"/><text v-else>请选择门店后读取经营记录。</text></template></template><button v-if="operatorSession.canAssessment" @tap="uni.navigateTo({url:'/pages/operator/monthly-assessment'})">月结历史与显式复算</button><button @tap="uni.navigateTo({url:'/pages/operator/review'})">运营工作区</button></view></template>
