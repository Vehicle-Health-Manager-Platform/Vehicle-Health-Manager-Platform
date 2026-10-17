<script setup>
import {reactive,ref,watch} from 'vue'
import {onLoad,onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {operatorSession,operatorToken,clearOperatorSession} from '../../services/operator-session.js'
import {safeId} from '../../services/experience-publication.js'
import {createPriceComparisonApi} from '../../services/price-comparisons.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../../services/async-diagnosis.js'
import PriceComparisonResult from '../../components/PriceComparisonResult.vue'
const state=reactive(initialDiagnosisState()),id=ref(0),api=createPriceComparisonApi(),allowed=()=>operatorSession.canPriceMonitor&&safeId(id.value)
const flow=createAsyncDiagnosisFlow({state,token:operatorToken,allowed,scope:()=>`price-comparison:${operatorSession.actorId}:${id.value}`,load:t=>api.detail(t,id.value)});let visible=false
function login(){clearOperatorSession();uni.navigateTo({url:'/pages/operator/login'})}
watch(()=>[operatorSession.accessToken,allowed(),id.value],()=>{flow.reset();if(visible&&operatorToken()&&allowed())flow.load()},{flush:'sync'})
onLoad(q=>{id.value=q&&Object.keys(q).length===1&&/^[1-9]\d*$/.test(String(q.id))&&safeId(Number(q.id))?Number(q.id):0});onShow(()=>{visible=true;if(operatorToken()&&allowed())flow.load()});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
</script>
<template><view class="reservation-page"><text class="reservation-title">报价比较详情</text><text v-if="!id">报价入口无效。</text><button v-else-if="!operatorToken()" @tap="login">运营登录</button><text v-else-if="!allowed()">当前账号没有独立价格监控权限。</text><template v-else><button :disabled="state.busy" @tap="flow.load()">重新核验当前来源与依据</button><text v-if="state.busy" role="status">正在读取当前比较…</text><text v-if="state.message" role="status">{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录</button><PriceComparisonResult v-if="state.data" :value="state.data.comparison" :observed="state.data.observed_at"/></template><button @tap="uni.navigateTo({url:'/pages/operator/price-comparisons'})">价格比较列表</button><button v-if="operatorSession.canPriceMonitor" @tap="uni.navigateTo({url:'/pages/operator/price-policies'})">配置与停止来源依据</button></view></template>
