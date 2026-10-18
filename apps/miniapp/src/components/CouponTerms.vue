<script setup>
import { couponStatus,couponAvailability,couponLocalTime } from '../services/coupons.js'
defineProps({row:{type:Object,required:true},mine:{type:Boolean,default:false}})
function date(value){return `${couponLocalTime(value)}（北京时间）`}
</script>
<template>
 <view class="coupon-terms">
  <text class="reservation-heading">{{ row.terms?.name || '历史券' }}</text>
  <template v-if="row.terms">
   <text>{{ row.terms.type==='SERVICE'?'指定项目服务兑换券':`满 ¥${row.terms.min_spend} 减 ¥${row.terms.amount}` }}</text>
   <text>仅限发行门店 {{ row.merchant_id }} · 标准项目 {{ row.terms.project_id }}</text>
   <text>生效：{{ date(row.terms.valid_from) }}</text><text>截止：{{ date(row.terms.valid_to) }}</text>
   <text>由发行门店承担优惠 · 每人每个模板限领一次</text>
  </template>
  <text>{{ couponStatus[row.status] || row.status }}{{ mine?` · ${couponAvailability[row.availability]}`:` · 剩余 ${row.stock_left} 张` }}</text>
  <text v-if="mine&&!row.source_known">该券缺少可验证发行记录，请联系发行门店核对。</text>
 </view>
</template>
<style scoped>.coupon-terms{display:flex;flex-direction:column;gap:14rpx;font-size:26rpx;line-height:38rpx;color:#454a41}</style>
