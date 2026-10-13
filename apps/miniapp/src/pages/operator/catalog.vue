<script setup>
import { reactive, ref, computed, watch } from 'vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import { operatorSession, operatorToken, clearOperatorSession } from '../../services/operator-session.js'
import { catalogKinds, catalogBody, catalogApi } from '../../services/catalog-governance.js'
import { createCatalogFlow, initialCatalogState } from '../../services/catalog-flow.js'
import { imageRequestKey } from '../../services/private-images.js'
const kinds = Object.keys(catalogKinds), labels = kinds.map(k => catalogKinds[k].label), selected = ref('projects')
const config = computed(() => catalogKinds[selected.value]), state = reactive(initialCatalogState()), form = reactive({}), editing = ref(null)
const parents = ref([]), parentPage = ref(0), parentTotal = ref(0), parentBusy = ref(false)
const flow = createCatalogFlow({ state, api: catalogApi, token: operatorToken, selectedKind: () => selected.value, newKey: imageRequestKey })
let parentEpoch = 0, visible = false
function blank() { editing.value = null; Object.keys(form).forEach(k => delete form[k]); for (const key of Object.keys(config.value.fields)) form[key] = ''; form.sort_order = '0'; form.source_note = ''; if (config.value.parentKey) form[config.value.parentKey] = '' }
function clear() { parentEpoch++; parentBusy.value = false; parents.value = []; parentPage.value = 0; parentTotal.value = 0; flow.reset(); blank() }
function login() { clearOperatorSession(); uni.redirectTo({ url: '/pages/operator/login' }) }
function switchKind(e) { selected.value = kinds[Number(e.detail.value)]; clear(); flow.load(); loadParents() }
function edit(row) { editing.value = { ...row }; Object.assign(form, row); state.message = '正在编辑；发布前请核对来源和上级目录' }
async function loadParents(more = false) {
  if (!config.value.parent || parentBusy.value) return
  const version = parentEpoch, actor = operatorToken(), kind = config.value.parent, page = more ? parentPage.value + 1 : 1
  parentBusy.value = true
  try { const result = await catalogApi.list(actor, kind, page)
    if (version !== parentEpoch || actor !== operatorToken()) return
    parents.value = more ? [...parents.value, ...result.items] : result.items; parentPage.value = page; parentTotal.value = result.total
  } catch (e) { if (version === parentEpoch && actor === operatorToken()) { state.message = e.message; state.kind = e.kind } }
  finally { if (version === parentEpoch && actor === operatorToken()) parentBusy.value = false }
}
function selectParent(e) { const row = parents.value[Number(e.detail.value)]; if (row) form[config.value.parentKey] = row.id }
const parentLabels = computed(() => parents.value.map(row => `${row.name} · ${row.status === 1 ? '已发布' : '未发布'} (#${row.id})`))
const parentLabel = computed(() => parents.value.find(row => row.id === Number(form[config.value.parentKey]))?.name || (form[config.value.parentKey] ? `上级 #${form[config.value.parentKey]}` : '请选择上级目录'))
async function save(status = null) {
  try { const body = catalogBody(selected.value, form, editing.value); if (editing.value && status !== null) body.status = status
    const result = await flow.save(body, editing.value?.id ?? null); if (result) { edit(result); await flow.load() }
  } catch (e) { state.message = e.message; state.kind = e.kind }
}
watch(() => operatorSession.accessToken, () => { clear(); if (visible && operatorToken()) { flow.load(); loadParents() } }, { flush: 'sync' })
onShow(() => { visible = true; if (!operatorToken()) { login(); return } clear(); flow.load(); loadParents() })
onHide(() => { visible = false; clear() }); onUnload(() => { visible = false; clear() })
blank()
</script>

<template>
  <view class="reservation-page">
    <text class="reservation-title">标准库维护</text>
    <button v-if="operatorSession.canCatalog" @tap="uni.navigateTo({url:'/pages/operator/project-applicability'})">维护项目—车型适用性规则</button>
    <button v-if="operatorSession.canCatalog" @tap="uni.navigateTo({url:'/pages/operator/health-policies'})">维护精确车型健康来源规则</button>
    <text class="reservation-copy">仅具有目录权限的运营账号可维护。新建默认未发布，停用影响新选择，历史订单和车辆引用保留。</text>
    <text v-if="!operatorSession.canCatalog">当前账号未配置目录权限，请由管理员授权。</text>
    <picker :range="labels" :disabled="state.writing" @change="switchKind"><view class="reservation-panel">{{config.label}} · 切换目录 ›</view></picker>
    <text v-if="state.busy">正在加载…</text><text v-else-if="state.loaded && !state.rows.length">暂无目录项。</text>
    <view v-for="row in state.rows" :key="row.id" class="reservation-panel">
      <text class="reservation-heading">{{row.project_name || row.name || `${row.year} ${row.config_name}`}}</text>
      <text>{{row.status === 1 ? '已发布' : '未发布/已停用'}} · 排序 {{row.sort_order}}</text>
      <text>{{row.source_note || '历史数据尚未补充来源说明'}}</text>
      <button :disabled="state.busy || state.writing" @tap="edit(row)">编辑与发布状态</button>
    </view>
    <button :disabled="state.busy || state.writing" @tap="flow.load()">刷新目录</button>
    <button v-if="state.loaded && state.page*20 < state.total" :disabled="state.busy || state.writing" @tap="flow.load(true)">加载下一页</button>
    <view v-if="operatorSession.canCatalog" class="reservation-panel">
      <text class="reservation-heading">{{editing ? `编辑 #${editing.id}` : '新增目录项（未发布）'}}</text>
      <button :disabled="state.writing" @tap="blank">新建另一项</button>
      <template v-if="config.parentKey">
        <text>上级目录（创建后不可变更）</text>
        <picker :range="parentLabels" :disabled="!!editing || state.writing || parentBusy" @change="selectParent"><view>{{parentLabel}} ›</view></picker>
        <button v-if="parentPage*20 < parentTotal" :disabled="parentBusy || state.writing" @tap="loadParents(true)">加载更多上级目录</button>
        <button :disabled="parentBusy || state.writing" @tap="loadParents()">刷新上级目录</button>
      </template>
      <view v-for="(label,field) in config.fields" :key="field" class="field">
        <text>{{label}}</text><textarea v-if="['service_content','quality_standard'].includes(field)" v-model="form[field]" :disabled="state.writing" maxlength="2000"/>
        <input v-else v-model="form[field]" :disabled="state.writing" maxlength="128" :placeholder="label"/>
      </view>
      <text>排序（0–9999，数字越小越靠前）</text><input v-model="form.sort_order" type="number" :disabled="state.writing"/>
      <text>数据来源说明（必填，不填写身份密钥）</text><textarea v-model="form.source_note" :disabled="state.writing" maxlength="256"/>
      <button class="reservation-primary" :loading="state.writing" :disabled="state.busy || state.writing" @tap="save()">{{editing ? '保存修改' : '保存未发布项'}}</button>
      <template v-if="editing"><button :disabled="state.busy || state.writing" @tap="save(editing.status === 1 ? 0 : 1)">{{editing.status === 1 ? '保存并停用' : '保存并发布'}}</button></template>
    </view>
    <text v-if="state.message" role="status">{{state.message}}</text>
    <button v-if="state.kind === 'unauthorized'" @tap="login">重新登录</button>
    <button @tap="uni.navigateBack()">返回运营入口</button>
  </view>
</template>
<style scoped>
.field{display:flex;flex-direction:column;gap:12rpx;margin-top:24rpx}
input,textarea{box-sizing:border-box;width:100%;background:#f7f8fa;border:1rpx solid #e5e6eb;border-radius:16rpx;padding:24rpx;margin:12rpx 0;font-size:26rpx}
input{height:88rpx}textarea{min-height:160rpx}
</style>
