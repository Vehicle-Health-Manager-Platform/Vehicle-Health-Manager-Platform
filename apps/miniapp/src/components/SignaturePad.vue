<script setup>
import { getCurrentInstance, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { createSignatureStroke, exportSignature } from '../services/signature-pad.js'
const props = defineProps({ disabled: Boolean, active: Boolean, epoch: Number })
const emit = defineEmits(['signed', 'clear', 'busy'])
const instance = getCurrentInstance(), message = ref(''), ready = ref(false), exporting = ref(false)
let pad, context, rect, generation = 0, live = true
function clear() { if (!live || !props.active || props.disabled || exporting.value) return; generation++; pad?.clear(); message.value = ''; emit('clear', props.epoch) }
async function initialize() {
  const version = generation
  await nextTick()
  uni.createSelectorQuery().in(instance.proxy).select('#quality-signature').boundingClientRect(value => {
    if (!live || version !== generation) return
    if (!value?.width || !value?.height) { message.value = '签名板尚未就绪，请重试'; return }
    rect = value; context = uni.createCanvasContext('quality-signature', instance.proxy)
    pad = createSignatureStroke(context, rect.width, rect.height); pad.clear(); ready.value = true
  }).exec()
}
onMounted(initialize)
onBeforeUnmount(() => { live = false; generation++; pad?.end() })
watch(() => props.epoch, () => { generation++; exporting.value = false; pad?.clear() }, { flush: 'sync' })
watch(() => props.active, active => { generation++; exporting.value = false; pad?.end(); if (!active) pad?.clear(); else if (!ready.value) initialize() })
function point(event) {
  const p = event.touches?.[0] || event.changedTouches?.[0] || event
  if (Number.isFinite(p?.x) && Number.isFinite(p?.y)) return { x: p.x, y: p.y }
  if (Number.isFinite(p?.offsetX) && Number.isFinite(p?.offsetY)) return { x: p.offsetX, y: p.offsetY }
  return { x: p?.clientX - rect?.left, y: p?.clientY - rect?.top }
}
function start(e) { if (live && props.active && ready.value && !props.disabled && !exporting.value) { emit('clear', props.epoch); pad.start(point(e)) } }
function move(e) { if (props.active && ready.value && !props.disabled && !exporting.value) pad.move(point(e)) }
function end() { pad?.end() }
async function save() {
  if (!live || !props.active || props.disabled || exporting.value || !ready.value) return
  if (!pad.hasInk()) { message.value = '请在签名板内手写签名，空白或单点不能提交'; return }
  const version = generation, epoch = props.epoch; exporting.value = true; message.value = ''; emit('busy', true, epoch)
  // Flush pending strokes before exporting. Never accept a callback from a hidden/replaced pad.
  const current = () => live && version === generation && props.active && epoch === props.epoch
  try { const file = await exportSignature({ context, runtime: uni, scope: instance.proxy, current }); if (current()) emit('signed', file, epoch) }
  catch (error) { if (current()) message.value = error.message }
  finally { if (current()) { exporting.value = false; emit('busy', false, epoch) } }
}
</script>
<template>
  <view class="signature-panel">
    <text>请手写本人质检签名。签字确认实际施工与质检记录，提交后不可修改。</text>
    <canvas id="quality-signature" canvas-id="quality-signature" class="signature-canvas" :disable-scroll="true"
      @touchstart.stop.prevent="start" @touchmove.stop.prevent="move" @touchend="end" @touchcancel="end"
      @mousedown="start" @mousemove="move" @mouseup="end" @mouseleave="end" />
    <text v-if="message" role="status">{{ message }}</text>
    <button v-if="!ready" @tap="initialize">重新加载签名板</button>
    <button :disabled="disabled || exporting" @tap="clear">清除重签</button>
    <button :disabled="disabled || exporting || !ready" :loading="exporting" @tap="save">生成并上传签名图片</button>
  </view>
</template>
<style scoped>
.signature-panel{display:flex;flex-direction:column;gap:12rpx}.signature-canvas{width:100%;height:180px;border:1px solid #c9cdd4;border-radius:8px;background:#fff;touch-action:none}
</style>
