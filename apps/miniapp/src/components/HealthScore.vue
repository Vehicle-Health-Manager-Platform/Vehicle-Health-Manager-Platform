<script setup>
import { watch,onMounted,nextTick,getCurrentInstance } from 'vue'
const props=defineProps({score:{type:Number,default:null}}),instance=getCurrentInstance(),id='health-score-'+instance.uid
async function draw(){await nextTick();const ctx=uni.createCanvasContext(id,instance.proxy);ctx.clearRect(0,0,108,108);ctx.setLineWidth(8);ctx.setStrokeStyle('#e5e6eb');ctx.beginPath();ctx.arc(54,54,46,0,Math.PI*2);ctx.stroke();if(props.score!==null){ctx.setStrokeStyle('#2478d4');ctx.beginPath();ctx.arc(54,54,46,-Math.PI/2,-Math.PI/2+Math.PI*2*props.score/100);ctx.stroke()}ctx.draw()}
onMounted(draw);watch(()=>props.score,draw)
</script>
<template><view class="score-ring"><canvas :id="id" :canvas-id="id" style="width:108px;height:108px" :aria-label="score===null?'参考分未知':'来源规则参考分'+score"/><view class="score-text"><text class="score">{{score===null?'未知':score}}</text><text>规则参考分</text></view></view></template>
<style scoped>.score-ring{position:relative;width:108px;height:108px;flex-shrink:0}.score-text{position:absolute;inset:0;display:flex;align-items:center;justify-content:center;flex-direction:column;font-size:22rpx;pointer-events:none}.score{font-size:44rpx;font-weight:650}</style>
