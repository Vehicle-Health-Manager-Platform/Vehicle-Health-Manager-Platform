<script setup>
import {reactive,ref,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import AssessmentResult from '../../components/AssessmentResult.vue'
import {merchantSession,clearMerchantSession} from '../../services/merchant-session.js'
import {createMonthlyAssessmentApi,defaultAssessmentMonth} from '../../services/monthly-assessment.js'
import {loadMerchantMonthlyAssessment} from '../../services/monthly-assessment-pages.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../../services/async-diagnosis.js'
const state=reactive(initialDiagnosisState()),month=ref(defaultAssessmentMonth()),detailId=ref(0),factPage=ref(1),api=createMonthlyAssessmentApi(),token=()=>merchantSession.accessToken
const flow=createAsyncDiagnosisFlow({state,token,scope:()=>`merchant-assessment:${token()}`,load:(t,p)=>loadMerchantMonthlyAssessment({api,token:t,month:month.value,page:p,detailId:detailId.value,factPage:factPage.value})});let visible=false
function reset(){flow.reset();month.value=defaultAssessmentMonth();detailId.value=0;factPage.value=1}
function refresh(){if(state.busy)return;detailId.value=0;factPage.value=1;flow.load()}
function show(r){if(state.busy)return;detailId.value=r.id;factPage.value=1;flow.load(state.page)}
function factNext(d){if(state.busy)return;factPage.value+=d;flow.load(state.page)}
function login(){clearMerchantSession();uni.navigateTo({url:'/pages/merchant/index'})}
watch(token,()=>{reset();if(visible&&token())flow.load()},{flush:'sync'})
onShow(()=>{visible=true;if(token())flow.load()});onHide(()=>{visible=false;reset()});onUnload(()=>{visible=false;reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">本店月度考核</text><text>店长查看本店冻结修订；员工仅查看本人新客归因计数。历史快照不表示实时成绩。</text><button v-if="!token()" @tap="login">登录门店账号</button><template v-else><input v-model="month" maxlength="7" :disabled="state.busy" placeholder="北京时间月份 YYYY-MM"/><button :disabled="state.busy" @tap="refresh">读取月份</button><view v-if="state.busy" class="reservation-panel" aria-busy="true"><text role="status">正在读取本月冻结结果…</text></view><text v-if="state.message" role="status">{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录</button>
 <template v-if="state.data"><view class="reservation-panel"><text class="reservation-heading">本人归因 · {{state.data.staff.period_month}}</text><text v-if="state.data.staff.state==='NO_SNAPSHOT'">尚无月结快照；当前不显示正式拉新成绩。</text><template v-else><text>修订 {{state.data.staff.revision}} · 有效 {{state.data.staff.attribution.included}} · 剔除 {{state.data.staff.attribution.excluded}} · 来源未知 {{state.data.staff.attribution.unknown}}</text></template></view>
 <template v-if="state.data.context"><text>{{state.data.context.merchant.name}} · {{state.data.context.period_month}} · 当前修订 {{state.data.context.revision}}</text><text v-if="!state.data.items.length">本月尚无冻结结果，成绩未知。</text><AssessmentResult v-if="state.data.context.latest&&!state.data.detail" :value="state.data.context.latest"/><view v-for="r in state.data.items" :key="r.id" class="reservation-panel"><text>{{r.period_month}} · 修订 {{r.revision}} · {{r.summary.state==='CALCULATED'?r.summary.total_score+' 分':'未知'}}</text><button :disabled="state.busy" @tap="show(r)">查看本修订及依据</button></view><button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">上一页修订</button><button v-if="state.page*20<state.data.total" :disabled="state.busy" @tap="flow.load(state.page+1)">下一页修订</button><AssessmentResult v-if="state.data.detail" :value="state.data.detail" :facts="state.data.facts"/><button v-if="state.data.facts&&factPage>1" :disabled="state.busy" @tap="factNext(-1)">上一页依据</button><button v-if="state.data.facts&&factPage*20<state.data.facts.total" :disabled="state.busy" @tap="factNext(1)">下一页依据</button></template></template>
 </template><button @tap="uni.navigateTo({url:'/pages/merchant/index'})">门店工作入口</button></view></template>
