<script setup>
import { reactive, ref, watch } from 'vue'
import { onHide, onUnload } from '@dcloudio/uni-app'
import { operatorSession, operatorToken, clearOperatorSession } from '../../services/operator-session.js'
import { imageRequestKey } from '../../services/private-images.js'
import { createApplicabilityFlow, initialApplicabilityState, applicabilityLabels } from '../../services/vehicle-plan-evidence.js'
const project = ref(''), model = ref(''), verdict = ref('APPLICABLE'), reference = ref(''), state = reactive(initialApplicabilityState())
const flow = createApplicabilityFlow({ state, token: operatorToken, selection: () => ({ project: Number(project.value), model: Number(model.value) }), newKey: imageRequestKey,
  confirm: (s, b) => new Promise(resolve => uni.showModal({ title: b.verdict==='WITHDRAW' ? '确认撤销规则' : '确认发布车型适用性', content: `项目 #${s.project} / 精确车型 #${s.model}\n${b.verdict==='WITHDRAW' ? '撤销后适用性为未知；保留规则版本与审计历史。' : `${applicabilityLabels[b.verdict]}\n来源：${b.source_reference}\n确认来源适用于该车型；该声明不证明技术必做。`}`, success: r => resolve(!!r.confirm), fail: () => resolve(false) })) })
function reset() { flow.reset(); project.value=''; model.value=''; verdict.value='APPLICABLE'; reference.value='' }
watch(() => [project.value, model.value], () => { flow.reset(); reference.value='' }, { flush: 'sync' })
watch(() => operatorSession.accessToken, reset, { flush: 'sync' })
watch(() => state.value, value => { if (value?.rule) { verdict.value=value.rule.verdict; reference.value=value.rule.source_reference } })
onHide(reset); onUnload(reset)
function login() { reset(); clearOperatorSession(); uni.navigateTo({ url:'/pages/operator/login' }) }
</script>
<template>
  <view class="reservation-page">
    <text class="reservation-title">项目—车型适用性规则</text>
    <text class="reservation-copy">需目录维护权限。规则仅适用于指定精确车型，必须记录来源；不按动力类型推断，不证明技术必做。来源内容需运营人员核对。</text>
    <text v-if="!operatorSession.canCatalog">当前账号没有目录维护权限。</text>
    <view v-else class="reservation-panel">
      <text>已发布标准项目编号</text><input v-model="project" :disabled="state.busy" type="number" class="reservation-field" />
      <text>已发布精确车型编号（非品牌/车系编号）</text><input v-model="model" :disabled="state.busy" type="number" class="reservation-field" />
      <button :disabled="state.busy" @tap="flow.load()">读取当前规则与版本</button>
      <template v-if="state.value">
        <text>当前版本 {{ state.value.version }} · {{ state.value.rule ? applicabilityLabels[state.value.rule.verdict] : applicabilityLabels.UNKNOWN }}</text>
        <text v-if="state.value.rule">当前来源：{{ state.value.rule.source_reference }}</text>
        <button :disabled="state.busy" @tap="verdict='APPLICABLE'">{{ verdict==='APPLICABLE' ? '已选 · ' : '' }}适用</button>
        <button :disabled="state.busy" @tap="verdict='NOT_APPLICABLE'">{{ verdict==='NOT_APPLICABLE' ? '已选 · ' : '' }}不适用</button>
        <text>来源引用（1–256字，说明资料名称、位置及适用车型）</text><textarea v-model="reference" :disabled="state.busy" maxlength="512" class="reservation-field" />
        <button :disabled="state.busy || !reference.trim()" @tap="flow.save(verdict,reference)">核对来源并确认发布</button>
        <button :disabled="state.busy || !state.value.rule" @tap="flow.save('WITHDRAW',null)">确认撤销当前规则</button>
      </template>
    </view>
    <text v-if="state.message" role="status">{{ state.message }}</text>
    <button v-if="!operatorSession.accessToken || ['unauthorized','forbidden'].includes(state.kind)" @tap="login">重新登录运营账号</button>
    <button @tap="uni.navigateBack()">返回标准库维护</button>
  </view>
</template>
