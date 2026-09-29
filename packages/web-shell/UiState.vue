<script setup>
defineProps({
  state: { type: String, required: true, validator: value => ['loading', 'empty', 'error', 'forbidden', 'offline'].includes(value) },
  title: { type: String, default: '' },
  detail: { type: String, default: '' },
})
const emit = defineEmits(['retry'])

const defaults = {
  empty: ['这里还没有记录', '添加第一条记录后，就能在这里查看。'],
  error: ['内容暂时无法加载', '请检查连接后重新加载。'],
  forbidden: ['无法查看这项内容', '请使用有权限的账号登录。'],
  offline: ['网络连接已断开', '恢复网络后重新加载。'],
}
</script>

<template>
  <div v-if="state === 'loading'" class="ui-state ui-state-loading" role="status" aria-label="正在加载">
    <span class="skeleton short"></span><span class="skeleton"></span><span class="skeleton"></span>
  </div>
  <div v-else class="ui-state" role="status">
    <span class="state-marker" :data-state="state" aria-hidden="true"></span>
    <strong>{{ title || defaults[state][0] }}</strong>
    <p>{{ detail || defaults[state][1] }}</p>
    <button v-if="state === 'error' || state === 'offline'" type="button" @click="emit('retry')">重新加载</button>
  </div>
</template>
