<script setup>
defineProps({
  id: { type: String, required: true },
  label: { type: String, required: true },
  modelValue: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  hint: { type: String, default: '' },
  error: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
})
const emit = defineEmits(['update:modelValue'])
</script>

<template>
  <div class="base-field" :class="{ 'has-error': error, 'is-disabled': disabled }">
    <label :for="id">{{ label }}</label>
    <input :id="id" :value="modelValue" :placeholder="placeholder" :disabled="disabled"
      :aria-invalid="Boolean(error)" :aria-describedby="error || hint ? `${id}-message` : undefined"
      @input="emit('update:modelValue', $event.target.value)">
    <small v-if="error || hint" :id="`${id}-message`">{{ error || hint }}</small>
  </div>
</template>
