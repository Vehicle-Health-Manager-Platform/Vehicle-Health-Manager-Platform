<script setup>
import RoleLanding from '../../components/RoleLanding.vue'
</script>

<template>
  <RoleLanding role="owner" title="车主工作入口" description="围绕自己的车辆管理档案、预约服务并追踪订单。" :steps="['微信授权与手机号绑定', '录入车辆和健康档案', '查看服务、预约与订单']" />
</template>
