<script setup>
import {METRIC_LABELS,ASSESSMENT_REASONS,FACT_STATES,EVIDENCE_STATES} from '../services/monthly-assessment.js'
import {ASSESSMENT_EVIDENCE} from '../services/assessment-policies.js'
import {displayTime} from '../services/reservations.js'
defineProps({value:{type:Object,required:true},facts:{type:Object,default:null}})
const sourceLabels={INVITE:'新客归因',ORDER:'已完成工单',CASH_ORDER:'金额券使用',UNPROVEN_ORDER:'完成来源待核对',SERVICE_REDEMPTION:'独立服务券核销'}
</script>
<template><view class="reservation-panel">
 <text class="reservation-heading">{{value.period_month}} · 修订 {{value.revision}}</text>
 <text>冻结采集 {{displayTime(value.captured_from)}} 至 {{displayTime(value.captured_to)}}，历史快照不表示当前实时成绩。</text>
 <text v-if="value.summary.state==='CALCULATED'">总分 {{value.summary.total_score}} · {{value.summary.grade.label}}</text><text v-else>总分与等级：未知</text>
 <view v-for="(label,k) in METRIC_LABELS" :key="k"><text>{{label}}：{{value.summary.metrics[k].state==='KNOWN'?value.summary.metrics[k].score+' 分':'未知'}} · {{value.summary.metrics[k].numerator}} / {{value.summary.metrics[k].denominator}}</text><text>{{ASSESSMENT_REASONS[value.summary.metrics[k].reason]}}</text></view>
 <text>固定权重：拉新40%／券40%／过程20%。未知维度中的次数是待核对候选，不作为完整经营事实。</text>
 <template v-if="value.policy"><text>政策来源：{{value.policy.source_note}}</text><text>拉新目标 {{value.policy.acquisition_target}} · 券目标 {{value.policy.coupon_target}}</text><text v-for="g in value.policy.grade_thresholds" :key="g.code">{{g.label}}：至少 {{g.min_score}} 分</text></template><text v-else>没有可证明覆盖整月的政策。</text>
 <template v-if="facts"><text class="reservation-heading">本修订依据（{{facts.total}}项）</text><text v-if="!facts.total">没有冻结的来源事实。</text><view v-for="f in facts.items" :key="`${f.dimension}:${f.source_type}:${f.source_id}`"><text>{{sourceLabels[f.source_type]}} · {{FACT_STATES[f.state]}} · {{ASSESSMENT_REASONS[f.reason]}}</text><text>来源 {{f.source_id}}{{f.staff_id?' · 归因员工 '+f.staff_id:''}}{{f.occurred_at?' · '+displayTime(f.occurred_at):' · 发生时间待核对'}}</text><template v-for="(v,k) in f.evidence" :key="k"><text v-if="ASSESSMENT_EVIDENCE[k]">{{ASSESSMENT_EVIDENCE[k]}}：{{EVIDENCE_STATES[v]}}</text><text v-if="k==='full_refund'&&v">全额退款，已从原使用月剔除券次数。</text></template></view></template>
</view></template>
