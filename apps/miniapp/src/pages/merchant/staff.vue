<script setup>
import { reactive, ref, watch } from 'vue'
import { onHide, onShow, onUnload } from '@dcloudio/uni-app'
import { clearMerchantSession, merchantSession } from '../../services/merchant-session.js'
import { STAFF_ROLES, merchantStaffApi, staffRoleLabel, staffStatusLabel } from '../../services/merchant-staff.js'
import { merchantProfileApi } from '../../services/merchant-profile.js'
import { createStaffActionFlow, createStaffCreateFlow, createStaffListFlow,
  initialStaffActionState, initialStaffCreateState, initialStaffListState } from '../../services/staff-flow.js'
import { imageRequestKey } from '../../services/private-images.js'

const list = reactive(initialStaffListState())
const form = reactive(initialStaffCreateState())
const actions = reactive(initialStaffActionState())
// canManage 来自服务端的资料读接口（can_edit = 当前身份是否店长）。前端不猜角色：
// 店员看得到本店员工列表，但看不到新增/启停/员工码入口。
const canManage = ref(false)
const editing = ref(false)
let visible = false

const token = () => merchantSession.accessToken
const staff = createStaffListFlow({ state: list, token,
  fetchPage: (actor, page, role) => merchantStaffApi.list(actor, role, page) })
const create = createStaffCreateFlow({ state: form, api: merchantStaffApi, token, newKey: imageRequestKey })
const mutate = createStaffActionFlow({ state: actions, api: merchantStaffApi, token, newKey: imageRequestKey })

// 每条异步返回都要重新核对身份与页面可见性：切了账号或离开页面后，旧响应必须作废。
async function loadPermission() {
  canManage.value = false
  const actor = token()
  if (!actor) return
  try {
    const profile = await merchantProfileApi.read(actor)
    if (actor === token()) canManage.value = profile.can_edit === true
  } catch { /* 读不到资料就按只读处理，不放开任何管理入口 */ }
}

function load() { if (token()) { staff.load(); loadPermission() } }
function reset() { staff.reset(); create.reset(); actions.reset(); canManage.value = false; editing.value = false }

watch(token, () => { reset(); if (visible && token()) load() }, { flush: 'sync' })
onShow(() => { visible = true; load() })
onHide(() => { visible = false; staff.suspend(); create.suspend(); actions.suspend() })
onUnload(() => { visible = false; reset() })

// 新增成功与启停/员工码成功都要刷新列表：状态、员工码与绑定标记都由服务端决定。
watch(() => form.saved, result => { if (result && visible) staff.load() })
async function act(action, target) { if (await action(target)) staff.load() }

function login() { clearMerchantSession(); uni.navigateTo({ url: '/pages/merchant/index' }) }
function start() { create.reset(); editing.value = true }
function done() { create.reset(); editing.value = false }
function openProfile() { uni.navigateTo({ url: '/pages/merchant/profile' }) }
function openOrders() { uni.navigateTo({ url: '/pages/merchant/orders' }) }
function openProjects() { uni.navigateTo({ url: '/pages/merchant/projects' }) }
</script>

<template>
  <view class="page">
    <text class="eyebrow">门店端</text>
    <text class="title">本店员工与员工码</text>
    <view v-if="!merchantSession.accessToken" class="panel">
      <text>请先登录门店账号。</text>
      <button @tap="login">前往门店登录</button>
    </view>
    <template v-else>
      <view class="links">
        <button @tap="openProfile">门店资料</button>
        <button @tap="openOrders">本店订单</button>
        <button @tap="openProjects">本店服务与报价</button>
      </view>
      <text v-if="!canManage" class="copy" data-testid="staff-readonly-notice">
        只有店长可以新增、启停员工或签发员工码；本页对店员只读。
      </text>
      <button v-if="canManage" class="primary" :disabled="form.busy" @tap="start">新增店员或技师</button>
      <view v-if="editing" class="panel" data-testid="staff-form">
        <text class="heading">新增员工</text>
        <picker :range="STAFF_ROLES" :value="STAFF_ROLES.indexOf(form.role)" :disabled="form.busy || !!form.saved"
          @change="form.role = STAFF_ROLES[Number($event.detail.value)]">
          <view class="field">身份：{{ staffRoleLabel(form.role) }}</view>
        </picker>
        <input v-model="form.displayName" class="field" :disabled="form.busy || !!form.saved" maxlength="32" placeholder="姓名（2–32 字）" />
        <input v-model="form.phone" class="field" :disabled="form.busy || !!form.saved" type="number" maxlength="11"
          :placeholder="form.role === 'STAFF' ? '本人手机号（店员必填）' : '手机号（可选）'" />
        <input v-model="form.password" class="field" :disabled="form.busy || !!form.saved" password maxlength="64" placeholder="初始密码（8–64 位，含字母与数字）" />
        <text class="copy">账号由系统生成；短信登录需要本人手机号，因此店员必须填写。密码只在本次请求里出现，不会回显、不写日志。</text>
        <text v-if="form.message" :class="form.saved ? 'success' : 'error'" role="status" data-testid="staff-form-message">{{ form.message }}</text>
        <button v-if="!form.saved" class="primary" :disabled="form.busy" :loading="form.busy" @tap="create.save">创建员工</button>
        <button :disabled="form.busy" @tap="done">{{ form.saved ? '返回本店员工' : '取消' }}</button>
      </view>

      <view class="filters">
        <button :class="{ selected: list.role === '' }" @tap="staff.filter('')">全部</button>
        <button :class="{ selected: list.role === 'STAFF' }" @tap="staff.filter('STAFF')">店员</button>
        <button :class="{ selected: list.role === 'TECHNICIAN' }" @tap="staff.filter('TECHNICIAN')">技师</button>
      </view>
      <text v-if="list.busy" class="copy" role="status">正在加载本店员工…</text>
      <view v-if="list.message" class="panel" role="status">
        <text class="error" data-testid="staff-list-message">{{ list.message }}</text>
        <button v-if="['unauthorized', 'forbidden'].includes(list.failureKind)" @tap="login">重新登录</button>
        <button v-else :disabled="list.busy" @tap="staff.retry">重试加载</button>
      </view>
      <view v-if="actions.message" class="panel" role="status">
        <text data-testid="staff-action-message">{{ actions.message }}</text>
      </view>
      <view v-if="list.loaded && !list.items.length && !list.busy" class="panel" data-testid="staff-empty">
        本店还没有店员或技师；{{ canManage ? '可先新增。' : '请联系店长添加。' }}
      </view>
      <view v-for="item in list.items" :key="item.staff_id" class="panel" :data-testid="`staff-row-${item.staff_id}`">
        <text class="heading">{{ item.display_name }} · {{ staffRoleLabel(item.role) }}</text>
        <text class="copy">账号 {{ item.account }} · {{ staffStatusLabel(item.status) }}</text>
        <text class="copy">
          {{ item.phone_masked ? `手机 ${item.phone_masked}` : '未登记手机号' }}
          · 员工码{{ item.employee_code_issued ? '已签发' : '未签发' }}
          · 微信{{ item.wechat_bound ? '已绑定' : '未绑定' }}
        </text>
        <view v-if="canManage" class="row-actions">
          <button :disabled="!!actions.busyId"
            :loading="actions.busyId === item.staff_id && ['disable', 'enable'].includes(actions.action)"
            @tap="act(mutate.toggle, item)">{{ item.status === 'ACTIVE' ? '停用' : '启用' }}</button>
          <template v-if="item.role === 'TECHNICIAN'">
            <button :disabled="!!actions.busyId" :loading="actions.busyId === item.staff_id && actions.action === 'issue'"
              @tap="act(mutate.issue, item)">{{ item.employee_code_issued ? '重新签发员工码' : '签发员工码' }}</button>
            <button v-if="item.employee_code_issued" :disabled="!!actions.busyId"
              :loading="actions.busyId === item.staff_id && actions.action === 'revoke'"
              @tap="act(mutate.revoke, item)">撤销员工码</button>
          </template>
        </view>
        <view v-if="actions.issued && actions.issued.staffId === item.staff_id" class="issued" data-testid="staff-issued-code">
          <text class="issued-label">一次性员工码（只显示这一次）</text>
          <text class="issued-code">{{ actions.issued.code }}</text>
          <text class="copy">请当面转达给技师；技师绑定后此码立即失效，刷新或离开页面后不再显示。</text>
        </view>
        <text v-if="item.status === 'DISABLED'" class="copy warning">已停用：原登录会话立即失效{{ item.role === 'TECHNICIAN' ? '，微信绑定同时撤销' : '' }}。</text>
      </view>
      <button v-if="list.items.length < list.total" :disabled="list.busy" @tap="staff.load(true)">加载更多员工</button>
    </template>
  </view>
</template>

<style scoped>
.page { min-height:100vh;box-sizing:border-box;padding:52rpx 32rpx 96rpx;background:#f2f3f5; }.eyebrow { display:block;color:#008f24;font-size:23rpx; }.title { display:block;font-size:42rpx;font-weight:700;margin:14rpx 0 28rpx;color:#1d2129; }
.panel { display:flex;flex-direction:column;background:white;padding:30rpx;border-radius:24rpx;margin:24rpx 0; }.heading { font-size:30rpx;font-weight:600;color:#1d2129; }.copy { font-size:25rpx;color:#4e5969;margin-top:16rpx;line-height:38rpx; }.error { color:#b42318;margin-top:16rpx; }.success { color:#008f24;margin-top:16rpx; }.warning { color:#b42318; }
.field { padding:22rpx;background:#f2f3f5;border-radius:12rpx;margin-top:20rpx;font-size:28rpx; }.links { display:flex;flex-wrap:wrap;gap:8rpx; }.links button { font-size:24rpx; }
.filters { display:flex;flex-wrap:wrap;gap:8rpx;margin-top:16rpx; }.filters button { font-size:24rpx;margin:8rpx 0; }.filters .selected { background:#00b42a;color:white; }
.row-actions { display:flex;flex-wrap:wrap;gap:8rpx; }.row-actions button { font-size:24rpx; }
.issued { margin-top:20rpx;padding:24rpx;border-radius:16rpx;background:#fff7e6; }.issued-label { display:block;color:#8a5300;font-size:23rpx; }.issued-code { display:block;margin-top:10rpx;font-size:36rpx;font-weight:700;color:#1d2129;word-break:break-all; }
button { margin:22rpx 0 0;background:#eef8f0;color:#008f24;font-size:27rpx;border-radius:14rpx; }button::after { border:0; }.primary { background:#00b42a;color:white; }
</style>
