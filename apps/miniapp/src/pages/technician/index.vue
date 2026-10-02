<script setup>
import RoleLanding from '../../components/RoleLanding.vue'
</script>

<template>
  <RoleLanding role="technician" title="技师工作入口" description="接收工单，留下施工和质检记录。" :steps="['微信授权与首次工号绑定', '接单和施工拍照', '方案、配件与质检签字']" />
</template>
