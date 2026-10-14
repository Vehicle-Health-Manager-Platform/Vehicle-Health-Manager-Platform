<script setup>
defineProps({summary:{type:Object,required:true}})
const names={RESERVED:'待支付，券已冻结',CONSUMED:'已付款，券已消费',RELEASED:'未付款关闭，券已释放',RETURNED:'全额退款已确认，原券已返还',UNKNOWN:'历史用券事实待核对'}
</script>
<template><view class="reservation-panel"><text class="reservation-heading">订单用券</text><text>{{ names[summary.state] }}</text><template v-if="summary.source_known"><text>{{ summary.name }}</text><text>原价 ¥{{ summary.original_amount }} · 券优惠 ¥{{ summary.discount_amount }} · 现金实付 ¥{{ summary.cash_amount }}</text><text>每单一券，不叠加；全退返原券，不延长有效期。</text><text v-if="summary.state==='CONSUMED'">已付款权益保留，不因券后续到期或停止新领取而撤销。</text></template><text v-else>缺少可信用券合同，不据历史字段推断优惠或免佣。</text></view></template>
