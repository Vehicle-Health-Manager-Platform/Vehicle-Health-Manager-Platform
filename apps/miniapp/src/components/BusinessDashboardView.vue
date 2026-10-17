<script setup>
import {BUSINESS_METRICS,BUSINESS_MONEY} from '../services/business-dashboard.js'
import {displayTime} from '../services/reservations.js'
defineProps({value:{type:Object,required:true}})
</script>
<template><view>
 <view class="reservation-panel"><text class="reservation-heading">{{value.merchant.name}} · {{value.period_month}}</text><text>北京时间 {{displayTime(value.window_from)}} 至 {{displayTime(value.window_to)}}（截止不含）</text><text>读取时点 {{displayTime(value.observed_at)}}。仅反映系统内可归属本店的记录，正式与测试分别展示。</text></view>
 <view v-for="(label,key) in BUSINESS_METRICS" :key="key" class="reservation-panel"><text class="reservation-heading">{{label}}</text><view v-for="b in ['formal','test']" :key="b"><text class="reservation-copy">{{b==='formal'?'正式来源':'测试来源'}}：{{value.metrics[key][b].state==='UNKNOWN'?'未知':BUSINESS_MONEY.has(key)?'¥'+value.metrics[key][b].amount:value.metrics[key][b].count+' 项'}}</text><text class="reservation-copy">已核验候选 {{value.metrics[key][b].count}} · 待核对 {{value.metrics[key][b].unknown_count}} · 剔除 {{value.metrics[key][b].excluded_count}}</text></view><text v-if="value.metrics[key].unclassified_count">另有 {{value.metrics[key].unclassified_count}} 项无法区分来源，当前不展示完整金额或业绩。</text><text v-if="key==='new_customers'">待资格和测试归因事件列入剔除，不计有效新客。</text><text v-if="key==='cash_coupon_uses'">可信全退及返券后，从原使用月剔除；这里是读取时点的有效使用次数。</text></view>
 <view class="reservation-panel"><text class="reservation-heading">本月系统记录收退款差额</text><text v-for="b in ['formal','test']" :key="b">{{b==='formal'?'正式来源':'测试来源'}}：{{value.cash_difference[b].state==='KNOWN'?'¥'+value.cash_difference[b].amount:'未知'}}</text><text>当月成功收款减当月成功退款，可为负数。不代表已结资金、余额或利润；佣金应计也不代表已收费。</text></view>
 <view class="reservation-panel"><text class="reservation-heading">独立冻结月结</text><text v-if="!value.assessment">暂无该月冻结结果，不能把经营统计当作考核分数。</text><template v-else><text>修订 {{value.assessment.revision}} · {{value.assessment.state==='CALCULATED'?value.assessment.total_score+' 分 · '+value.assessment.grade.label:'总分与等级未知'}}</text><text>冻结采集 {{displayTime(value.assessment.captured_from)}} 至 {{displayTime(value.assessment.captured_to)}}。不会随看板刷新自动重算。</text></template></view>
</view></template>
