<script setup>
import { ref, watch, nextTick, onMounted, getCurrentInstance } from 'vue'
import { unitLabels } from '../services/health-observations.js'
import { healthTrendPlot } from '../services/health-trend-plot.js'
const props=defineProps({ trend:{type:Object,required:true}, metric:{type:String,required:true} })
const canvasId='health-trend-'+props.metric.toLowerCase(), width=ref(280)
const scope=getCurrentInstance()?.proxy
async function draw(){await nextTick();if(props.trend.status!=='COMPARABLE_UNVERIFIED'||props.trend.points.length<2)return
 const points=props.trend.points,plot=healthTrendPlot(points,width.value);if(!plot)return;const ctx=uni.createCanvasContext(canvasId,scope),{low,high,left,right,top,bottom}=plot
 ctx.clearRect(0,0,width.value,160);ctx.setStrokeStyle('#c9cdd4');ctx.setLineWidth(1);ctx.beginPath();ctx.moveTo(left,top);ctx.lineTo(left,bottom);ctx.lineTo(right,bottom);ctx.stroke();ctx.setFillStyle('#4e5969');ctx.setFontSize(10);ctx.fillText(String(high),0,top+4);ctx.fillText(String(low),0,bottom);ctx.fillText(points[0].measured_on.slice(5),left,150);ctx.fillText(points.at(-1).measured_on.slice(5),right-38,150)
 ctx.setStrokeStyle('#2478d4');ctx.setLineWidth(2);ctx.beginPath();plot.points.forEach(({x,y},i)=>{if(i===0)ctx.moveTo(x,y);else ctx.lineTo(x,y)});ctx.stroke();plot.points.forEach(({x,y})=>{ctx.beginPath();ctx.setFillStyle('#2478d4');ctx.arc(x,y,2.5,0,Math.PI*2);ctx.fill()});ctx.draw()
}
onMounted(()=>{try{const w=uni.getSystemInfoSync().windowWidth;if(Number.isFinite(w)&&w>0)width.value=Math.max(220,Math.min(420,w-80))}catch{}draw()})
watch(()=>props.trend,draw,{deep:true})
</script>
<template><view class="trend">
 <template v-if="trend.status==='COMPARABLE_UNVERIFIED'"><text>同口径原始读数曲线（本人提供，尚未核验）</text><canvas :canvas-id="canvasId" :id="canvasId" :style="{width:width+'px',height:'160px'}" aria-label="同单位方法的原始读数变化" /><text>首末差值 {{trend.delta}} {{unitLabels[trend.points[0].unit]}}；变化不等于健康改善或恶化。</text></template>
 <text v-else>{{trend.status==='INSUFFICIENT'?'可比记录少于2条，暂不能绘制趋势':'来源/规则/读数不可比，趋势未知'}}</text>
 <text v-if="trend.truncated">仅筛选最近50条履历，更多原始记录请查看历史。</text>
 <text v-for="p in trend.points" :key="p.revision">{{p.measured_on}} · {{p.value}} {{unitLabels[p.unit]}} · 来源 #{{p.archive_id}} / 版本 {{p.revision}}</text>
</view></template>
<style scoped>.trend{display:flex;flex-direction:column;gap:14rpx;color:#4e5969;font-size:24rpx;line-height:36rpx;margin-top:20rpx}canvas{max-width:100%}</style>
