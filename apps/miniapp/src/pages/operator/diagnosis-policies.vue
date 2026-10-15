<script setup>
import {reactive,ref,watch} from 'vue'
import {onShow,onHide,onUnload} from '@dcloudio/uni-app'
import {operatorSession,operatorToken,clearOperatorSession} from '../../services/operator-session.js'
import {initialDiagnosisPolicyState,createDiagnosisPolicyFlow} from '../../services/invite-diagnosis.js'
import {imageRequestKey} from '../../services/private-images.js'
import {couponUtcTime,couponLocalTime} from '../../services/coupons.js'
const state=reactive(initialDiagnosisPolicyState()),editing=ref(0),form=reactive({count:'',note:'',fromDate:'',fromTime:'',toDate:'',toTime:'',revision:0})
const allowed=()=>operatorSession.canInvite,flow=createDiagnosisPolicyFlow({state,token:operatorToken,allowed,newKey:imageRequestKey,onWritten:clearForm,confirm:()=>new Promise(resolve=>uni.showModal({title:'确认解锁政策',content:'按北京时间当月有效新客核对，政策停用、到期或跨月即重新判断。配置不开放留言服务。',success:r=>resolve(r.confirm),fail:()=>resolve(false)}))})
function clearForm(){editing.value=0;Object.assign(form,{count:'',note:'',fromDate:'',fromTime:'',toDate:'',toTime:'',revision:0})}
let visible=false
watch(()=>[operatorSession.accessToken,operatorSession.canInvite],()=>{flow.reset();clearForm();if(visible&&operatorToken()&&allowed())flow.load()},{flush:'sync'})
watch(()=>state.kind,k=>{if(['unauthorized','forbidden'].includes(k))clearForm()})
onShow(()=>{visible=true;if(operatorToken()&&allowed())flow.load()});onHide(()=>{visible=false;flow.reset();clearForm()});onUnload(()=>{visible=false;flow.reset();clearForm()})
function edit(r){editing.value=r.id;form.count=String(r.required_count);form.note=r.source_note;form.revision=r.revision;[form.fromDate,form.fromTime]=couponLocalTime(r.valid_from).split(' ');[form.toDate,form.toTime]=couponLocalTime(r.valid_to).split(' ')}
function save(){const a=couponUtcTime(form.fromDate,form.fromTime),b=couponUtcTime(form.toDate,form.toTime),n=Number(form.count);if(!/^[1-9]\d{0,6}$/.test(form.count)||n>1000000||!a||!b||Date.parse(a)>=Date.parse(b)||[...form.note.trim()].length<2){state.message='请核对人数、来源和生效时间。';return}flow.save(editing.value?'update':'create',editing.value,{required_count:n,source_note:form.note.trim(),valid_from:a,valid_to:b,...(editing.value?{expected_revision:form.revision}:{})})}
function status(r,s){flow.save('status',r.id,{expected_revision:r.revision,status:s})}
function login(){clearOperatorSession();uni.navigateTo({url:'/pages/operator/login'})}
</script>
<template><view class="reservation-page"><text class="reservation-title">人工诊断邀请条件</text><text>仅配置非实时诊断资格；每月重新计算，不产生永久授权。</text><button v-if="!operatorToken()" @tap="login">运营登录</button><text v-else-if="!allowed()">当前账号没有独立邀请规则权限。</text><template v-else>
 <view class="reservation-panel"><text>{{editing?'修改草稿':'新建草稿'}}</text><input v-model="form.count" type="number" :disabled="state.busy||state.retry" placeholder="当月有效新客人数（1–1000000）"/><input v-model="form.note" maxlength="256" :disabled="state.busy||state.retry" placeholder="规则来源说明"/><text>北京时间生效日期与时间</text><input v-model="form.fromDate" :disabled="state.busy||state.retry" placeholder="YYYY-MM-DD"/><input v-model="form.fromTime" :disabled="state.busy||state.retry" placeholder="HH:mm"/><text>北京时间截止日期与时间</text><input v-model="form.toDate" :disabled="state.busy||state.retry" placeholder="YYYY-MM-DD"/><input v-model="form.toTime" :disabled="state.busy||state.retry" placeholder="HH:mm"/><button :disabled="state.busy||state.retry" @tap="save">保存草稿</button><button :disabled="state.busy||state.retry" @tap="clearForm">新建另一政策</button></view>
 <view v-for="r in state.rows" :key="r.id" class="reservation-panel"><text>当月 {{r.required_count}} 人 · {{r.status}} · 版本 {{r.revision}}</text><text>{{r.source_note}}</text><text>{{couponLocalTime(r.valid_from)}} 至 {{couponLocalTime(r.valid_to)}}</text><button v-if="r.status==='DRAFT'" :disabled="state.busy||state.retry" @tap="edit(r)">修改</button><button v-if="r.status==='DRAFT'" :disabled="state.busy||state.retry" @tap="status(r,'PUBLISHED')">发布冻结</button><button v-if="r.status==='PUBLISHED'" :disabled="state.busy||state.retry" @tap="status(r,'STOPPED')">停止使用</button></view>
 <text v-if="state.message">{{state.message}}</text><button v-if="state.kind==='unauthorized'" @tap="login">重新登录</button><button v-if="state.retry" :disabled="state.busy" @tap="flow.retry">重试原操作</button><button :disabled="state.busy" @tap="flow.load()">刷新</button><button v-if="state.page>1" :disabled="state.busy" @tap="flow.load(state.page-1)">上一页</button><button v-if="state.page*20<state.total" :disabled="state.busy" @tap="flow.load(state.page+1)">下一页</button>
</template></view></template>
