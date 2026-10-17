<script setup>
import {RECORD_TYPES,FIELD_LABELS} from '../services/service-workspace.js'
defineProps({record:{type:Object,required:true},busy:{type:Boolean,default:false}})
defineEmits(['preview'])
const value=n=>Array.isArray(n)?n.join('、'):String(n)
</script>
<template><view><text class="reservation-heading">{{RECORD_TYPES[record.archive_type]}} · {{record.title||'未填写标题'}}</text><text>{{record.recorded_date||'日期未知'}}<template v-if="record.mileage!==null"> · {{record.mileage}} km</template></text><text v-if="record.notes">{{record.notes}}</text><text v-for="(v,k) in record.fields" :key="k">{{FIELD_LABELS[k]}}：{{value(v)}}</text><button v-for="(f,i) in record.files" :key="f.file_id" :disabled="busy||!f.available" @tap="$emit('preview',f.file_id)">图片 {{i+1}} · {{f.available?'查看':'当前不可用'}}</button></view></template>
