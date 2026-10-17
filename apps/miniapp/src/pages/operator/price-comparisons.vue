<script setup>
import {reactive,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {operatorSession,operatorToken,clearOperatorSession} from '../../services/operator-session.js'
import {createPriceComparisonApi} from '../../services/price-comparisons.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../../services/async-diagnosis.js'
import PriceComparisonResult from '../../components/PriceComparisonResult.vue'
const state=reactive(initialDiagnosisState()),api=createPriceComparisonApi(),allowed=()=>operatorSession.canPriceMonitor
const flow=createAsyncDiagnosisFlow({state,token:operatorToken,allowed,scope:()=>`price-comparisons:${operatorSession.actorId}`,load:(t,p)=>api.list(t,p)});let visible=false
function login(){clearOperatorSession();uni.navigateTo({url:'/pages/operator/login'})}
function open(row){if(!state.busy&&state.data?.comparisons.items.some(v=>v.quote_id===row.quote_id))uni.navigateTo({url:`/pages/operator/price-comparison?id=${row.quote_id}`})}
watch(()=>[operatorSession.accessToken,allowed()],()=>{flow.reset();if(visible&&operatorToken()&&allowed())flow.load()},{flush:'sync'})
onShow(()=>{visible=true;if(operatorToken()&&allowed())flow.load()});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">只读价格比较</text><text>依据已确认的具体报价版本及含边界区间比较。未知不代表正常，偏离只提示需核对。</text><button v-if="!operatorToken()" @tap="login">运营登录</button><text v-else-if="!allowed()">当前账号没有独立价格监控权限。</text><template v-else><button :disabled="state.busy" @tap="flow.load(state.page)">重新核验当前页</button><text v-if="state.busy" role="status">正在同一时点核验来源与依据…</text><text v-if="state.message" role="status">{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录</button><template v-if="state.data"><text v-if="!state.data.comparisons.items.length">没有当前报价或仍发布的待核对依据。</text><view v-for="r in state.data.comparisons.items" :key="r.quote_id"><PriceComparisonResult :value="r" :observed="state.data.observed_at"/><button :disabled="state.busy" @tap="open(r)">单独重新读取此报价比较</button></view><button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">上一页</button><button v-if="state.page*20<state.data.comparisons.total" :disabled="state.busy" @tap="flow.load(state.page+1)">下一页</button></template></template><button v-if="allowed()" @tap="uni.navigateTo({url:'/pages/operator/price-policies'})">配置与停止来源依据</button><button @tap="uni.navigateTo({url:'/pages/operator/review'})">运营工作区</button></view></template>
