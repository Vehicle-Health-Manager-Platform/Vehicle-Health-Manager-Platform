<script setup>
import {reactive,ref,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import BusinessDashboardView from '../../components/BusinessDashboardView.vue'
import {merchantSession,clearMerchantSession} from '../../services/merchant-session.js'
import {createBusinessDashboardApi,currentBusinessMonth} from '../../services/business-dashboard.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../../services/async-diagnosis.js'
const state=reactive(initialDiagnosisState()),month=ref(currentBusinessMonth()),api=createBusinessDashboardApi(),token=()=>merchantSession.accessToken
const flow=createAsyncDiagnosisFlow({state,token,scope:()=>`merchant-business:${token()}`,load:t=>api.dashboard(t,'merchant',null,month.value)});let visible=false
function reset(){flow.reset();month.value=currentBusinessMonth()}
function login(){clearMerchantSession();uni.navigateTo({url:'/pages/merchant/index'})}
watch(token,()=>{reset();if(visible&&token())flow.load()},{flush:'sync'})
onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;reset()});onUnload(()=>{visible=false;reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">本店经营看板</text><text>仅店长只读本店系统记录。员工可进入本人归因页。</text><button v-if="!token()" @tap="login">登录本店店长账号</button><template v-else><input v-model="month" maxlength="7" :disabled="state.busy" placeholder="北京时间月份 YYYY-MM"/><button :disabled="state.busy" @tap="flow.load()">读取月份</button><view v-if="state.busy" class="reservation-panel" aria-busy="true"><text role="status">正在核验本店经营来源…</text></view><text v-if="state.message" role="status">{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录店长账号</button><BusinessDashboardView v-if="state.data" :value="state.data"/></template><button @tap="uni.navigateTo({url:'/pages/merchant/assessment'})">月结历史与本人归因</button><button @tap="uni.navigateTo({url:'/pages/merchant/index'})">门店工作入口</button></view></template>
