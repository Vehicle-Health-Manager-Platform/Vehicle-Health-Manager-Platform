<script setup>
import {reactive,ref,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {operatorSession,operatorToken,clearOperatorSession} from '../../services/operator-session.js'
import {initialDiagnosisState,createAsyncDiagnosisFlow} from '../../services/async-diagnosis.js'
import {createPointExchangeCatalogApi,validPointExchangeBody} from '../../services/point-exchange-catalog.js'
import {createPointExchangeRecovery} from '../../services/point-exchange-recovery.js'
import {couponLocalTime,couponUtcTime} from '../../services/coupons.js'
import {imageRequestKey} from '../../services/private-images.js'
const state=reactive(initialDiagnosisState()),editing=ref(0),selected=ref(null),sourcePage=ref(1),fresh=()=>({cost:'',limit:'',note:'',fromDate:'',fromTime:'',toDate:'',toTime:'',revision:0}),form=reactive(fresh()),api=createPointExchangeCatalogApi()
const allowed=()=>operatorSession.canPoints,recovery=createPointExchangeRecovery({actor:()=>operatorSession.actorId})
const blocked=()=>state.busy||state.retry||state.kind==='storage'
const flow=createAsyncDiagnosisFlow({state,token:operatorToken,allowed,scope:()=> 'point-exchange-catalog',recovery,newKey:imageRequestKey,load:async(t,p)=>{const [catalog,sources]=await Promise.all([api.list(t,p),api.sources(t,sourcePage.value)]);return {...catalog,sources}},write:api.write,onWritten:clearForm,confirm:action=>new Promise(resolve=>uni.showModal({title:action==='discard-local'?'清理本机原请求':'确认积分兑换目录',content:action==='discard-local'?'请核对刚读取的服务器列表。只清理本机原请求，不删除服务器配置，不自动重发。':`请核对页面中的券来源、积分价、限额和时间。发布后冻结；每人限额按同一券模板终身累计，停止或换版不清零。成功兑换不支持自行撤销返分。本次仅保存目录，不扣积分、不发券。原请求保存在本机用于断线恢复。`,success:r=>resolve(r.confirm),fail:()=>resolve(false)}))})
let visible=false
function clearForm(){editing.value=0;selected.value=null;Object.assign(form,fresh())}
function choose(p){if(blocked())return;selected.value=JSON.parse(JSON.stringify(p))}
function edit(p){if(blocked())return;editing.value=p.id;selected.value={coupon_id:p.coupon_id,publication_revision:p.publication_revision,pool_revision:p.pool_revision,terms:p.terms};Object.assign(form,{cost:String(p.points_cost),limit:String(p.per_owner_limit),note:p.source_note,revision:p.revision});[form.fromDate,form.fromTime]=couponLocalTime(p.valid_from).split(' ');[form.toDate,form.toTime]=couponLocalTime(p.valid_to).split(' ')}
function save(){const source=selected.value,from=couponUtcTime(form.fromDate,form.fromTime),to=couponUtcTime(form.toDate,form.toTime);if(!source||!from||!to||!/^\d+$/.test(form.cost)||!/^\d+$/.test(form.limit)){state.message='请选择真实精选券，并填写整数积分价、每人限额、来源及时间。';return}const action=editing.value?'update':'create',body={coupon_id:source.coupon_id,publication_revision:source.publication_revision,pool_revision:source.pool_revision,points_cost:Number(form.cost),per_owner_limit:Number(form.limit),source_note:form.note.trim(),valid_from:from,valid_to:to,...(editing.value?{expected_revision:form.revision}:{})};if(!validPointExchangeBody(body,action)||Date.parse(from)<Date.parse(source.terms.valid_from)||Date.parse(to)>Date.parse(source.terms.valid_to)){state.message='积分价和限额须为 1–1000000，来源可核对，目录时间须在原券有效期内。';return}flow.save(action,editing.value,body)}
function sourceNext(delta){if(blocked())return;sourcePage.value+=delta;flow.load(state.page)}
function login(){clearOperatorSession();uni.navigateTo({url:'/pages/operator/login'})}
function reset(){flow.reset();clearForm();sourcePage.value=1}
watch(()=>[operatorSession.accessToken,allowed()],()=>{reset();if(visible&&operatorToken()&&allowed())flow.load()},{flush:'sync'})
watch(()=>state.kind,k=>{if(['unauthorized','forbidden','missing'].includes(k))clearForm()})
onShow(()=>{visible=true;if(operatorToken()&&allowed())flow.load()});onHide(()=>{visible=false;reset()});onUnload(()=>{visible=false;reset()})
</script>
<template>
 <view class="reservation-page">
  <text class="reservation-title">积分兑换目录</text><text>配置服务券或金额券的兑换条件。发布后条款冻结；目录配置本身不扣分发券，实际兑换以车主当前资格和提交结果为准。</text>
  <button v-if="!operatorToken()" @tap="login">运营登录</button><text v-else-if="!allowed()">当前账号没有积分管理权限。</text>
  <template v-else>
   <view v-if="state.data" class="reservation-panel"><text>已发布精选券来源</text><text v-if="!state.data.sources.items.length">暂无可配置来源，请由券管理人员先发布并精选真实券。</text>
    <view v-for="s in state.data.sources.items" :key="s.coupon_id"><text>{{s.terms.name}} · #{{s.coupon_id}} · 门店 #{{s.merchant_id}} · {{s.terms.type==='SERVICE'?'服务券':'金额券'}} · 库存 {{s.stock_left}}</text><text>{{couponLocalTime(s.terms.valid_from)}} 至 {{couponLocalTime(s.terms.valid_to)}} · 发行版本 {{s.publication_revision}} · 精选版本 {{s.pool_revision}}</text><button :disabled="blocked()" @tap="choose(s)">选用此来源</button></view>
    <button v-if="sourcePage>1" :disabled="blocked()" @tap="sourceNext(-1)">上一页来源</button><button v-if="sourcePage*20<state.data.sources.total" :disabled="blocked()" @tap="sourceNext(1)">下一页来源</button>
   </view>
   <view class="reservation-panel"><text>{{editing?'修改兑换草稿':'新建兑换草稿'}}</text><text v-if="selected">已选 {{selected.terms.name}} · #{{selected.coupon_id}} · 发行 {{selected.publication_revision}} · 精选 {{selected.pool_revision}}</text><text v-else>请先选择精选券来源。</text>
    <input v-model="form.cost" type="number" maxlength="7" :disabled="blocked()" placeholder="积分价 1–1000000"/><input v-model="form.limit" type="number" maxlength="7" :disabled="blocked()" placeholder="每人同模板终身限兑 1–1000000"/><input v-model="form.note" maxlength="256" :disabled="blocked()" placeholder="来源说明 2–256 字"/>
    <text>北京时间生效 / 截止（须在原券有效期内）</text><input v-model="form.fromDate" :disabled="blocked()" placeholder="YYYY-MM-DD"/><input v-model="form.fromTime" :disabled="blocked()" placeholder="HH:mm"/><input v-model="form.toDate" :disabled="blocked()" placeholder="YYYY-MM-DD"/><input v-model="form.toTime" :disabled="blocked()" placeholder="HH:mm"/>
    <button :disabled="blocked()||!selected" @tap="save">保存兑换草稿</button><button :disabled="blocked()" @tap="clearForm">新建另一草稿</button>
   </view>
   <text v-if="state.message">{{state.message}}</text><button v-if="['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录</button><button v-if="state.retry" :disabled="state.busy" @tap="flow.retry">重试原请求</button><button v-if="state.kind==='storage'" :disabled="state.busy" @tap="flow.discardLocal">核对服务器并清理本机原请求</button><button :disabled="state.busy" @tap="flow.load(state.page)">刷新目录及来源</button>
   <template v-if="state.data"><text v-if="!state.data.items.length">尚未配置兑换目录，无默认积分价格。</text><view v-for="p in state.data.items" :key="p.id" class="reservation-panel"><text>目录 #{{p.id}} · {{p.terms.name}} · 券 #{{p.coupon_id}} · {{p.status}} · 版本 {{p.revision}}</text><text>{{p.points_cost}} 积分 · 每人同模板终身限兑 {{p.per_owner_limit}} 次，跨版本累计</text><text>{{p.source_note}}</text><text>{{couponLocalTime(p.valid_from)}} 至 {{couponLocalTime(p.valid_to)}}</text>
    <button v-if="p.status==='DRAFT'" :disabled="blocked()" @tap="edit(p)">修改草稿</button><button v-if="p.status==='DRAFT'" :disabled="blocked()" @tap="flow.save('status',p.id,{expected_revision:p.revision,status:'PUBLISHED'})">发布并冻结</button><button v-if="p.status==='PUBLISHED'" :disabled="blocked()" @tap="flow.save('status',p.id,{expected_revision:p.revision,status:'STOPPED'})">停止目录</button>
   </view><button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">上一页目录</button><button v-if="state.page*20<state.data.total" :disabled="state.busy" @tap="flow.load(state.page+1)">下一页目录</button></template>
  </template><button @tap="uni.navigateTo({url:'/pages/operator/review'})">运营工作区</button>
 </view>
</template>
