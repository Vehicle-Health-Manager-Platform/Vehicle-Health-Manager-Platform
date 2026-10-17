<script setup>
import {reactive,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import CouponTerms from '../../components/CouponTerms.vue'
import {operatorSession,operatorToken,clearOperatorSession} from '../../services/operator-session.js'
import {initialCouponState,createCouponFlow} from '../../services/coupons.js'
import {imageRequestKey} from '../../services/private-images.js'
const state=reactive(initialCouponState()),flow=createCouponFlow({state,token:operatorToken,mode:'operator',newKey:imageRequestKey,confirm:({body})=>new Promise(resolve=>uni.showModal({title:'确认券池调整',content:body.selected?'将该券加入车主精选券池？权益和库存由发行门店承担。':'移出精选券池？已领券权益保持不变。',success:r=>resolve(r.confirm),fail:()=>resolve(false)}))})
let visible=false
watch(()=>operatorSession.accessToken,()=>{flow.reset();if(visible&&operatorToken()&&operatorSession.canCoupon)flow.load(1)},{flush:'sync'})
onShow(()=>{visible=true;if(operatorToken()&&operatorSession.canCoupon)flow.load(1)});onHide(()=>{visible=false;flow.reset()});onUnload(()=>{visible=false;flow.reset()})
function toggle(row){flow.write('pool',row,{expected_pool_revision:row.pool_revision,selected:!row.pool_selected})}
function login(){flow.reset();clearOperatorSession();uni.navigateTo({url:'/pages/operator/login'})}
</script>
<template><view class="reservation-page"><text class="reservation-title">运营券池</text><text>选择精选曝光，不修改商户权益或库存。</text>
 <button v-if="!operatorToken()" @tap="uni.navigateTo({url:'/pages/operator/login'})">运营登录</button><text v-else-if="!operatorSession.canCoupon">当前账号没有券池管理权限。</text>
 <template v-else><text v-if="state.busy">正在处理…</text><text v-else-if="state.loaded&&!state.rows.length">暂无已发布券。</text>
 <view v-for="row in state.rows" :key="row.coupon_id" class="reservation-panel"><CouponTerms :row="row"/><text>{{ row.pool_selected?'已选入精选池':'未选入精选池' }}</text><button :disabled="state.busy||state.retryWrite||!row.pool_selected&&row.status!=='PUBLISHED'" @tap="toggle(row)">{{ row.pool_selected?'移出精选池':'选入精选池' }}</button></view>
 <text v-if="state.message" role="status">{{ state.message }}</text><button v-if="state.kind==='unauthorized'" @tap="login">重新登录运营账号</button><button v-if="state.retryWrite" :disabled="state.busy" @tap="flow.retry">重试原券池操作</button><button :disabled="state.busy||state.retryWrite" @tap="flow.load(1)">刷新券池</button>
 <button v-if="state.page>1" :disabled="state.busy||state.retryWrite" @tap="flow.load(state.page-1)">上一页</button><button v-if="state.page*20<state.total" :disabled="state.busy||state.retryWrite" @tap="flow.load(state.page+1)">下一页</button></template>
</view></template>
