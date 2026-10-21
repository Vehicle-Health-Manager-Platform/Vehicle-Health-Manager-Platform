<script setup>
import { reactive, ref, computed, watch } from 'vue'
import { onHide, onShow, onUnload } from '@dcloudio/uni-app'
import { clearMerchantSession, merchantSession } from '../../services/merchant-session.js'
import { STAFF_ROLES, merchantStaffApi } from '../../services/merchant-staff.js'
import { merchantProfileApi } from '../../services/merchant-profile.js'
import { createStaffActionFlow, createStaffCreateFlow, createStaffListFlow,
  initialStaffActionState, initialStaffCreateState, initialStaffListState } from '../../services/staff-flow.js'
import { imageRequestKey } from '../../services/private-images.js'
import {
  staffActionView, staffCreateFormView, staffEmptyView, staffFilterRows, staffIssuedView,
  staffListFailureView, staffPagingView, staffPermissionView, staffRowActions, staffRowView,
} from '../../services/merchant-staff-display.js'
import { toneClass } from '../../services/ui-tone.js'

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

// 权限、表单缺口、行内动作、空态、分页与失败口径都来自映射；模板不再拼判断与文案。
const permission = computed(() => staffPermissionView(canManage.value))
const formView = computed(() => staffCreateFormView(form))
const filters = computed(() => staffFilterRows(list.role))
const emptyView = computed(() => staffEmptyView(list, permission.value.canManage))
const failureView = computed(() => staffListFailureView(list))
const actionView = computed(() => staffActionView(actions))
const pagingView = computed(() => staffPagingView(list))
const rowsView = computed(() => list.items.map(item => ({
  item,
  view: staffRowView(item),
  actions: staffRowActions(item, { busyId: actions.busyId, action: actions.action }),
  issued: staffIssuedView(actions.issued, item.staff_id),
})))

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
function runAction(row, key) {
  if (key === 'toggle') return act(mutate.toggle, row.item)
  if (key === 'issue') return act(mutate.issue, row.item)
  if (key === 'revoke') return act(mutate.revoke, row.item)
  return undefined
}
</script>

<template>
  <view class="reservation-page" data-testid="staff-page">
    <view class="ux-hero ux-in">
      <text class="ux-eyebrow">门店端</text>
      <text class="ux-title">本店员工与员工码</text>
      <text class="ux-lead">店长可新增店员与技师、启停账号并签发一次性员工码；技师用员工码绑定微信后，此码立即失效。</text>
    </view>

    <view v-if="!merchantSession.accessToken" class="ux-card ux-in ux-r2" data-testid="staff-login">
      <text class="ux-card-title">需要门店账号</text>
      <text class="ux-card-note">员工与员工码只能由本店账号维护。</text>
      <button class="reservation-primary" @tap="login">前往门店登录</button>
    </view>

    <template v-else>
      <view class="ux-card ux-in ux-r2">
        <view class="links">
          <button @tap="openProfile">门店资料</button>
          <button @tap="openOrders">本店订单</button>
          <button @tap="openProjects">本店服务与报价</button>
        </view>
        <text v-if="!permission.canManage" class="ux-card-note" data-testid="staff-readonly-notice">{{ permission.readOnlyNotice }}</text>
        <button v-if="permission.canManage" class="reservation-primary" :disabled="form.busy" data-testid="staff-create" @tap="start">新增店员或技师</button>
      </view>

      <view v-if="editing && permission.canManage" class="ux-card ux-in ux-r3" data-testid="staff-form">
        <view class="head">
          <text class="ux-card-title">新增员工</text>
          <view class="ux-badge" :class="toneClass(formView.ready ? 'ok' : 'muted')"><text>{{ formView.ready ? '信息齐全' : '待填写' }}</text></view>
        </view>
        <picker :range="STAFF_ROLES" :value="STAFF_ROLES.indexOf(formView.role)" :disabled="formView.locked" data-testid="staff-role" @change="form.role = STAFF_ROLES[Number($event.detail.value)]">
          <view class="field">身份：{{ formView.roleLabel }} ›</view>
        </picker>
        <input class="field" :value="form.displayName" :disabled="formView.locked" maxlength="32" placeholder="姓名（2–32 字）" data-testid="staff-display-name" @input="form.displayName = $event.detail.value" />
        <input class="field" :value="form.phone" :disabled="formView.locked" type="number" maxlength="11" :placeholder="formView.phonePlaceholder" data-testid="staff-phone" @input="form.phone = $event.detail.value" />
        <input class="field" :value="form.password" :disabled="formView.locked" password maxlength="64" placeholder="初始密码（8–64 位，含字母与数字）" data-testid="staff-password" @input="form.password = $event.detail.value" />
        <text class="ux-card-note">{{ formView.passwordNotice }}</text>
        <text v-if="formView.hint" class="ux-card-note" data-testid="staff-form-hint">{{ formView.hint }}</text>
        <text v-if="formView.message" :class="formView.messageTone === 'ok' ? 'ok-line' : 'risk-line'" role="status" data-testid="staff-form-message">{{ formView.message }}</text>
        <button v-if="!form.saved" class="reservation-primary" :disabled="!formView.canSubmit" :loading="form.busy" data-testid="staff-save" @tap="create.save">{{ formView.submitLabel }}</button>
        <button :disabled="form.busy" data-testid="staff-close" @tap="done">{{ formView.closeLabel }}</button>
      </view>

      <view class="ux-card ux-in ux-r3">
        <view class="filters">
          <button v-for="row in filters" :key="row.role" :class="row.selected ? 'selected' : ''" @tap="staff.filter(row.role)">{{ row.label }}</button>
        </view>
        <text v-if="list.busy" class="status-line" role="status">正在加载本店员工…</text>
        <view v-if="failureView.visible" class="error-block" role="status">
          <text class="risk-line" data-testid="staff-list-message">{{ failureView.message }}</text>
          <button v-if="failureView.canRelogin" class="reservation-primary" @tap="login">重新登录</button>
          <button v-else :disabled="list.busy" @tap="staff.retry">{{ failureView.retryLabel }}</button>
        </view>
        <view v-if="actionView.visible" class="error-block" role="status">
          <text :class="actionView.tone === 'ok' ? 'ok-line' : 'risk-line'" data-testid="staff-action-message">{{ actionView.message }}</text>
          <button v-if="actionView.canRelogin" class="reservation-primary" @tap="login">重新登录</button>
        </view>
        <view v-if="emptyView.visible" class="ux-empty" data-testid="staff-empty">
          <text class="ux-empty-copy">{{ emptyView.copy }}</text>
        </view>

        <view v-for="row in rowsView" :key="row.item.staff_id" class="record" :data-testid="`staff-row-${row.item.staff_id}`">
          <view class="head">
            <text class="ux-card-title">{{ row.view.titleCopy }}</text>
            <view class="ux-badge" :class="toneClass(row.view.tone)"><text>{{ row.view.codeIssued ? '已签发' : '未签发' }}</text></view>
          </view>
          <text class="ux-card-note">{{ row.view.accountCopy }}</text>
          <text class="ux-card-note">{{ row.view.detailCopy }}</text>
          <view v-if="permission.canManage" class="row-actions">
            <button v-for="action in row.actions" :key="action.key" :disabled="action.disabled" :loading="action.loading" @tap="runAction(row, action.key)">{{ action.label }}</button>
          </view>
          <view v-if="row.issued.visible" class="issued" data-testid="staff-issued-code">
            <text class="issued-label">{{ row.issued.label }}</text>
            <text class="issued-code">{{ row.issued.code }}</text>
            <text class="ux-card-note">{{ row.issued.hint }}</text>
          </view>
          <text v-if="row.view.disabledCopy" class="risk-line">{{ row.view.disabledCopy }}</text>
        </view>

        <button v-if="pagingView.visible" :disabled="pagingView.disabled" @tap="staff.load(true)">{{ pagingView.label }}</button>
      </view>
    </template>
  </view>
</template>

<style scoped>
.head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.field { padding: 22rpx; background: #efede6; border-radius: 12rpx; margin-top: 20rpx; font-size: 28rpx; }
.links { display: flex; flex-wrap: wrap; gap: 8rpx; }
.links button { font-size: 24rpx; }
.filters { display: flex; flex-wrap: wrap; gap: 8rpx; }
.filters button { font-size: 24rpx; }
.filters .selected { background: #0b6b43; color: #fcfbf8; }
.row-actions { display: flex; flex-wrap: wrap; gap: 8rpx; margin-top: 12rpx; }
.row-actions button { font-size: 24rpx; }
.record { display: flex; flex-direction: column; gap: 8rpx; padding: 20rpx 0; border-top: 1rpx solid #e0ded6; }
.error-block { display: flex; flex-direction: column; gap: 10rpx; margin-top: 14rpx; }
.issued { margin-top: 16rpx; padding: 24rpx; border-radius: 16rpx; background: #f7ecdf; }
.issued-label { display: block; color: #9a4700; font-size: 23rpx; }
.issued-code { display: block; margin-top: 10rpx; font-size: 36rpx; font-weight: 700; color: #1a1d18; word-break: break-all; }
.risk-line { display: block; margin-top: 12rpx; color: #b3261e; font-size: 25rpx; }
.ok-line { display: block; margin-top: 12rpx; color: #074d30; font-size: 25rpx; }
.status-line { display: block; margin-top: 20rpx; color: #63685e; font-size: 26rpx; }
</style>
