<script setup>
import {ref,watch} from 'vue'
import OwnerTabShell from '../../components/OwnerTabShell.vue'
import ModelComparisonPicker from '../../components/ModelComparisonPicker.vue'
import {ownerSession} from '../../services/owner-session.js'
const first=ref(null),second=ref(null),rows=[['品牌','brand'],['车系','series'],['年款','year'],['配置名称','config']]
watch(()=>ownerSession.accessToken,()=>{first.value=null;second.value=null},{flush:'sync'})
</script>
<template>
 <OwnerTabShell label="新车选购" title="车型配置对比" description="选择两个车型，对照当前目录中的品牌、车系、年款和配置名称。" next-action="先完成两侧车型选择。" :business-ready="true">
  <template #content><view class="pickers"><ModelComparisonPicker label="车型一" @select="first=$event"/><ModelComparisonPicker label="车型二" @select="second=$event"/></view>
   <view class="comparison"><text class="heading">目录字段对照</text><text v-if="!first||!second" class="note" role="status">请完成两个车型的选择后查看对照。</text>
    <template v-else><text v-if="first.id===second.id" class="note">当前选择了相同车型，可修改其中一侧。</text><view v-for="row in rows" :key="row[1]" class="row"><text class="label">{{row[0]}}</text><text class="value">{{first[row[1]]}}</text><text class="value">{{second[row[1]]}}</text></view></template>
    <text class="note">来源：平台当前在用车型目录。此页不提供购车成交价、估值或未入库的性能参数，请以车辆官方资料及合作方正式条款为准。</text>
   </view>
  </template>
 </OwnerTabShell>
</template>
<style scoped>
.pickers{display:flex;flex-direction:column;gap:24rpx}.comparison{margin-top:28rpx;padding:28rpx;background:#fcfbf8;border:1rpx solid #d6d3c9;border-radius:20rpx}.heading{font-size:31rpx;font-weight:650;color:#1a1d18}.row{display:flex;gap:12rpx;border-bottom:1rpx solid #d6d3c9;padding:24rpx 0;font-size:25rpx;line-height:1.7}.label{width:100rpx;flex-shrink:0;color:#63685e}.value{flex:1;min-width:0;overflow-wrap:anywhere;color:#1a1d18}.note{display:block;margin-top:22rpx;color:#63685e;font-size:24rpx;line-height:1.7}
</style>
