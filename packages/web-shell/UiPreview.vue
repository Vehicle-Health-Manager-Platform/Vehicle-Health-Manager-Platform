<script setup>
import { ref } from 'vue'
import UiState from './UiState.vue'
import BaseField from './BaseField.vue'

const plate = ref('')
const states = ['loading', 'empty', 'error', 'forbidden', 'offline']
</script>

<template>
  <div class="ui-preview">
    <div class="status-rail" aria-label="车辆状态示意">
      <span class="status-rail-label">车况提示</span>
      <span class="rail-segment urgent"></span>
      <span class="rail-segment warning"></span>
      <span class="rail-segment normal"></span>
      <span class="rail-segment info"></span>
      <span class="rail-segment pending"></span>
    </div>
    <h2>页面状态</h2>
    <div class="state-grid">
      <div v-for="state in states" :key="state" class="preview-cell">
        <span class="preview-label">{{ state }}</span>
        <UiState :state="state" />
      </div>
    </div>
    <h2>表单状态</h2>
    <div class="form-grid">
      <BaseField id="plate-default" v-model="plate" label="车牌号码" placeholder="请输入车牌号码" hint="用于匹配车辆资料" />
      <BaseField id="plate-error" label="车牌号码" model-value="AB" error="请填写完整车牌号码" />
      <BaseField id="plate-disabled" label="车牌号码" model-value="待确认" disabled />
    </div>
  </div>
</template>
