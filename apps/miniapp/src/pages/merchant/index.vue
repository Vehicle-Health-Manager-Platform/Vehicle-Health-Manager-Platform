<script setup>
import RoleLanding from '../../components/RoleLanding.vue'
</script>

<template>
  <RoleLanding role="merchant" title="商家工作入口" description="从接车到派工，记录每一步交付。" :steps="['账号密码与短信验证', '订单接车和车主确认', '派工、核销与经营管理']" />
</template>
