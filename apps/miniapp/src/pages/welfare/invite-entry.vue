<script setup>
import {ref,computed} from 'vue'
import {onLoad} from '@dcloudio/uni-app'
import {ownerSession} from '../../services/owner-session.js'
import {validInvitationCode,setInvitationContext,invitationContext} from '../../services/invitation-context.js'
const code=ref(''),message=ref(''),valid=computed(()=>validInvitationCode(code.value))
onLoad(q=>{if(q.code!==undefined){if(validInvitationCode(q.code))code.value=q.code;else message.value='邀请码格式无效，请向分享者重新获取。'}})
function login(){if(ownerSession.accessToken){message.value='已登录账号不能补填或更换首次注册归因。';return}if(!valid.value)return;if(!setInvitationContext(code.value)){message.value='已有登录尝试正在恢复，请继续使用原邀请码。';return}message.value='本次首次注册将提交邀请码；是否有效由服务端判断。';uni.navigateTo({url:'/pages/owner/index'})}
</script>
<template><view class="reservation-page"><text class="reservation-title">通过好友邀请码注册</text><text>仅尚未注册的新用户有效；已有账号登录不会重新归因。资格确认需要服务端身份和手机号证据。</text><input v-model="code" maxlength="32" placeholder="32 位好友邀请码" :disabled="Boolean(ownerSession.accessToken)" /><text v-if="ownerSession.accessToken">当前已登录，请查看本人首次注册结果，不会补算邀请。</text><button v-else :disabled="!valid" @tap="login">携带此邀请码登录</button><text v-if="invitationContext()">已保存本次邀请来源；登录重试沿用原码。</text><text v-if="message" role="status">{{message}}</text><button v-if="ownerSession.accessToken" @tap="uni.navigateTo({url:'/pages/welfare/invite'})">查看本人邀请与注册结果</button></view></template>
