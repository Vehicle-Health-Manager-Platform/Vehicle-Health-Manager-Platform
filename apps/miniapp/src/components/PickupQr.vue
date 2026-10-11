<script setup>
import { getCurrentInstance, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { pickupQrMatrix } from '../services/pickup-scan.js'
const props = defineProps({ orderId: { type: Number, required: true } })
const instance = getCurrentInstance().proxy
const message = ref('')
let mounted = false, generation = 0
async function draw() {
  const version = ++generation
  if (!mounted) return
  await nextTick()
  if (version !== generation) return
  try {
    const matrix = pickupQrMatrix(props.orderId), size = matrix.length
    const unit = Math.floor(240 / (size + 8)), offset = Math.floor((240 - size * unit) / 2)
    const canvas = uni.createCanvasContext('pickup-qr', instance)
    canvas.setFillStyle('#ffffff'); canvas.fillRect(0, 0, 240, 240)
    canvas.setFillStyle('#000000')
    matrix.forEach((row, y) => row.forEach((dark, x) => { if (dark) canvas.fillRect(offset + x * unit, offset + y * unit, unit, unit) }))
    canvas.draw(); message.value = ''
  } catch { message.value = '接车二维码未生成，请刷新订单或向商家提供预约码' }
}
watch(() => props.orderId, draw)
onMounted(() => { mounted = true; draw() })
onBeforeUnmount(() => { mounted = false; generation++ })
</script>
<template><view class="pickup-qr"><text>接车二维码</text><canvas canvas-id="pickup-qr" id="pickup-qr" class="pickup-qr-canvas" /><text v-if="message" role="status">{{ message }}</text><text>请让本店工作人员扫码，再提供六位预约码。</text></view></template>
<style scoped>.pickup-qr{display:flex;flex-direction:column;align-items:center;gap:16rpx}.pickup-qr-canvas{width:240px;height:240px;background:#fff}</style>
