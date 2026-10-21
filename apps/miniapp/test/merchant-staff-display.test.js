import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  STAFF_FILTERS, staffActionView, staffCreateFormView, staffEmptyView, staffFilterRows, staffIssuedView,
  staffListFailureView, staffPagingView, staffPermissionView, staffRowActions, staffRowView,
} from '../src/services/merchant-staff-display.js'

const page = readFileSync(resolve(import.meta.dirname, '../src/pages/merchant/staff.vue'), 'utf8')

const staff = (over = {}) => ({
  staff_id: 11, account: 'S00011', role: 'STAFF', display_name: '李四', phone_masked: '138****8888',
  status: 'ACTIVE', employee_code_issued: false, wechat_bound: false, created_at: '2026-10-01T00:00:00Z', ...over,
})

test('筛选：三段顺序固定，选中态跟着当前角色', () => {
  assert.deepEqual(STAFF_FILTERS.map(r => [r.role, r.label]), [['', '全部'], ['STAFF', '店员'], ['TECHNICIAN', '技师']])
  assert.deepEqual(staffFilterRows('').map(r => r.selected), [true, false, false])
  assert.deepEqual(staffFilterRows('TECHNICIAN').map(r => r.selected), [false, false, true])
  // 未知/缺失按"全部"处理，而不是三个都不选中。
  assert.deepEqual(staffFilterRows('WAT').map(r => r.selected), [true, false, false])
  assert.deepEqual(staffFilterRows(undefined).map(r => r.selected), [true, false, false])
})

test('员工行：三段事实与状态语气', () => {
  const active = staffRowView(staff())
  assert.equal(active.titleCopy, '李四 · 店员')
  assert.equal(active.accountCopy, '账号 S00011 · 已启用')
  assert.equal(active.detailCopy, '手机 138****8888 · 员工码未签发 · 微信未绑定')
  assert.equal(active.tone, 'ok'); assert.equal(active.isTechnician, false)
  assert.equal(active.disabledCopy, ''); assert.equal(active.codeIssued, false)

  const technician = staffRowView(staff({ role: 'TECHNICIAN', employee_code_issued: true, wechat_bound: true }))
  assert.equal(technician.titleCopy, '李四 · 技师')
  assert.equal(technician.detailCopy, '手机 138****8888 · 员工码已签发 · 微信已绑定')
  assert.equal(technician.isTechnician, true); assert.equal(technician.codeIssued, true)

  // 停用提示必须按角色说准：技师会连带撤销微信绑定，店员不会。
  const disabledTech = staffRowView(staff({ role: 'TECHNICIAN', status: 'DISABLED' }))
  assert.equal(disabledTech.tone, 'muted')
  assert.match(disabledTech.disabledCopy, /原登录会话立即失效/)
  assert.match(disabledTech.disabledCopy, /微信绑定同时撤销/)
  assert.doesNotMatch(staffRowView(staff({ status: 'DISABLED' })).disabledCopy, /微信绑定同时撤销/)

  // 缺失字段一律退化。
  const bare = staffRowView({})
  assert.equal(bare.titleCopy, '姓名未提供 · 角色未提供')
  assert.equal(bare.accountCopy, '账号 未提供 · 状态未提供')
  assert.equal(bare.detailCopy, '未登记手机号 · 员工码未签发 · 微信未绑定')
  assert.equal(bare.tone, 'warn')
  assert.equal(staffRowView(undefined).staffId, 0)
})

test('行内动作：店员只有启停，技师才有员工码两态', () => {
  assert.deepEqual(staffRowActions(staff()).map(a => [a.key, a.label]), [['toggle', '停用']])
  assert.deepEqual(staffRowActions(staff({ status: 'DISABLED' })).map(a => a.label), ['启用'])
  assert.deepEqual(staffRowActions(staff({ role: 'TECHNICIAN' })).map(a => [a.key, a.label]), [
    ['toggle', '停用'], ['issue', '签发员工码'],
  ])
  const issued = staffRowActions(staff({ role: 'TECHNICIAN', employee_code_issued: true }))
  assert.deepEqual(issued.map(a => [a.key, a.label]), [
    ['toggle', '停用'], ['issue', '重新签发员工码'], ['revoke', '撤销员工码'],
  ])

  // loading 只落在"这一行 + 这个动作"上，别的行不该转圈。
  const busy = staffRowActions(staff({ role: 'TECHNICIAN', employee_code_issued: true }), { busyId: 11, action: 'issue' })
  assert.deepEqual(busy.map(a => a.loading), [false, true, false])
  const notMine = staffRowActions(staff({ role: 'TECHNICIAN', employee_code_issued: true }), { busyId: 11, action: 'revoke' })
  assert.deepEqual(notMine.map(a => a.loading), [false, false, true])
  const otherRow = staffRowActions(staff({ role: 'TECHNICIAN' }), { busyId: 99, action: 'issue' })
  assert.deepEqual(otherRow.map(a => a.loading), [false, false])
  // 任一动作进行中，本行所有按钮都要 disabled，避免并发改同一员工。
  assert.deepEqual(otherRow.map(a => a.disabled), [true, true])
  assert.deepEqual(staffRowActions(staff()).map(a => a.disabled), [false])
})

test('一次性员工码：只在本行刚签发时出现', () => {
  const view = staffIssuedView({ staffId: 11, code: 'EMP-8F2K' }, 11)
  assert.equal(view.visible, true)
  assert.equal(view.label, '一次性员工码（只显示这一次）')
  assert.equal(view.code, 'EMP-8F2K')
  assert.match(view.hint, /刷新或离开页面后不再显示/)
  assert.equal(staffIssuedView({ staffId: 11, code: 'EMP-8F2K' }, 12).visible, false, '别的行不能显示这一行的码')
  assert.equal(staffIssuedView({ staffId: 11, code: '' }, 11).visible, false)
  assert.equal(staffIssuedView(null, 11).visible, false)
  assert.equal(staffIssuedView(undefined, undefined).code, '')
})

test('新增表单：店员必须填手机号，密码要含字母与数字', () => {
  const empty = staffCreateFormView({ role: 'STAFF' })
  assert.equal(empty.ready, false); assert.equal(empty.canSubmit, false)
  assert.deepEqual(empty.missing, ['姓名（2–32 字）', '本人手机号（店员必填，用于短信登录）', '初始密码（8–64 位，含字母与数字）'])
  assert.match(empty.hint, /^还需填写：/)
  assert.equal(empty.phonePlaceholder, '本人手机号（店员必填）')
  assert.equal(empty.phoneRequired, true)

  const good = staffCreateFormView({ role: 'STAFF', displayName: '李四', phone: '13800008888', password: 'abc12345' })
  assert.equal(good.ready, true); assert.equal(good.canSubmit, true); assert.equal(good.hint, '')
  assert.equal(good.submitLabel, '创建员工'); assert.equal(good.closeLabel, '取消')
  assert.match(good.passwordNotice, /密码只在本次请求里出现/)

  // 技师不强制手机号，但填了就得合法。
  const technician = staffCreateFormView({ role: 'TECHNICIAN', displayName: '王五', phone: '', password: 'abc12345' })
  assert.equal(technician.ready, true)
  assert.equal(technician.phonePlaceholder, '手机号（可选）')
  assert.ok(staffCreateFormView({ role: 'TECHNICIAN', displayName: '王五', phone: '12345', password: 'abc12345' })
    .missing.some(m => m.startsWith('手机号')))

  // 密码：太短、纯数字、纯字母都不行。
  for (const password of ['abc1234', '12345678', 'abcdefgh', '']) {
    assert.ok(staffCreateFormView({ role: 'STAFF', displayName: '李四', phone: '13800008888', password })
      .missing.some(m => m.startsWith('初始密码')), `密码 ${password} 应无效`)
  }
  // 姓名长度边界：1 字不行，32 字可以。
  assert.ok(staffCreateFormView({ role: 'STAFF', displayName: '李', phone: '13800008888', password: 'abc12345' }).missing.some(m => m.startsWith('姓名')))
  assert.equal(staffCreateFormView({ role: 'STAFF', displayName: '王'.repeat(32), phone: '13800008888', password: 'abc12345' }).ready, true)

  // 已创建：全部锁住，就地给结果，按钮改成"返回本店员工"。
  const saved = staffCreateFormView({ role: 'STAFF', displayName: '李四', phone: '13800008888', password: '', saved: { staff_id: 11 }, message: '已创建店员「李四」，账号 S00011' })
  assert.equal(saved.locked, true); assert.equal(saved.canSubmit, false); assert.equal(saved.ready, false)
  assert.equal(saved.closeLabel, '返回本店员工'); assert.equal(saved.messageTone, 'ok')
  assert.equal(staffCreateFormView({ message: '姓名需为 2–32 个字符' }).messageTone, 'risk')
  assert.equal(staffCreateFormView(undefined).roleLabel, '店员')
})

test('列表失败：越权只给重新登录', () => {
  assert.equal(staffListFailureView({}).visible, false)
  const denied = staffListFailureView({ failureKind: 'unauthorized', message: '门店登录已失效，请重新登录' })
  assert.equal(denied.visible, true); assert.equal(denied.canRelogin, true)
  assert.equal(denied.retryLabel, '重试加载'); assert.equal(denied.tone, 'risk')
  assert.equal(staffListFailureView({ failureKind: 'network', message: '网络问题' }).canRelogin, false)
  // 服务端的 403 是 forbidden；写错字段名不该放出重新登录。
  assert.equal(staffListFailureView({ kind: 'forbidden', message: 'x' }).canRelogin, false)
})

test('空态与只读提示按店长身份分岔', () => {
  const loaded = { loaded: true, busy: false, items: [] }
  const manager = staffEmptyView(loaded, true)
  assert.equal(manager.visible, true); assert.match(manager.copy, /可先新增/)
  const clerk = staffEmptyView(loaded, false)
  assert.equal(clerk.visible, true); assert.match(clerk.copy, /请联系店长添加/)
  assert.equal(staffEmptyView({ loaded: false, items: [] }, true).visible, false)
  assert.equal(staffEmptyView({ loaded: true, busy: true, items: [] }, true).visible, false)
  assert.equal(staffEmptyView({ loaded: true, items: [staff()] }, true).visible, false)
  assert.equal(staffEmptyView(undefined, true).visible, false)

  assert.equal(staffPermissionView(true).readOnlyNotice, '')
  assert.equal(staffPermissionView(true).canManage, true)
  assert.match(staffPermissionView(false).readOnlyNotice, /只有店长/)
  assert.equal(staffPermissionView(undefined).canManage, false, '没读到权限就按只读处理')
})

test('分页与操作结果', () => {
  assert.equal(staffPagingView({ items: [staff()], total: 45 }).visible, true)
  assert.equal(staffPagingView({ items: [staff()], total: 45 }).disabled, false)
  assert.equal(staffPagingView({ items: [staff()], total: 1 }).visible, false)
  assert.equal(staffPagingView({ items: [], total: 0 }).visible, false, '空列表不该出现"加载更多"')
  assert.equal(staffPagingView({ items: [staff()], total: 45, busy: true }).disabled, true)
  assert.equal(staffPagingView({}).label, '加载更多员工')

  const ok = staffActionView({ message: '员工已停用；原登录会话立即失效', failureKind: '' })
  assert.equal(ok.visible, true); assert.equal(ok.tone, 'ok'); assert.equal(ok.canRelogin, false)
  const denied = staffActionView({ message: '只有店长可以维护本店员工与员工码', failureKind: 'forbidden' })
  assert.equal(denied.tone, 'risk'); assert.equal(denied.canRelogin, true)
  assert.equal(staffActionView({ busyId: 11 }).busy, true)
  assert.equal(staffActionView({}).visible, false)
})

test('员工页使用映射，并保住权限与锚点契约', () => {
  for (const fn of ['staffPermissionView', 'staffCreateFormView', 'staffFilterRows', 'staffEmptyView', 'staffListFailureView', 'staffActionView', 'staffPagingView', 'staffRowView', 'staffRowActions', 'staffIssuedView']) {
    assert.match(page, new RegExp(`${fn}\\(`), `staff.vue 必须使用 ${fn}`)
  }
  assert.match(page, /toneClass\(/)
  assert.match(page, /ux-hero/); assert.match(page, /ux-card/)
  // 既有锚点一个都不能丢，新增的也必须在。
  for (const id of ['staff-page', 'staff-login', 'staff-readonly-notice', 'staff-create', 'staff-form', 'staff-form-hint', 'staff-form-message', 'staff-role', 'staff-display-name', 'staff-phone', 'staff-password', 'staff-save', 'staff-close', 'staff-list-message', 'staff-action-message', 'staff-empty', 'staff-issued-code']) {
    assert.match(page, new RegExp(`data-testid="${id}"`), `缺少锚点 ${id}`)
  }
  // 行级锚点是绑定形式（`staff-row-${item.staff_id}`），编译后会被压成 {{...}}，只能源码断言。
  assert.match(page, /:data-testid="`staff-row-\$\{row\.item\.staff_id\}`"/)
  // 权限仍来自服务端 can_edit，前端不猜角色。
  assert.match(page, /merchantProfileApi\.read\(/)
  assert.match(page, /profile\.can_edit === true/)
  assert.match(page, /createStaffListFlow\(/)
  assert.match(page, /createStaffCreateFlow\(/)
  assert.match(page, /createStaffActionFlow\(/)
  assert.match(page, /newKey: imageRequestKey/)
  // 旧模板的内联判断与文案不应残留。
  assert.doesNotMatch(page, /\['unauthorized', 'forbidden'\]\.includes\(list\.failureKind\)/)
  assert.doesNotMatch(page, /item\.status === 'ACTIVE' \? '停用' : '启用'/)
  assert.doesNotMatch(page, /item\.employee_code_issued \? '重新签发员工码' : '签发员工码'/)
  assert.doesNotMatch(page, /\{\{ canManage \? '可先新增。' : '请联系店长添加。' \}\}/)
  // 员工码仍是"轮换语义、不带幂等键"的既有服务，页面不得自己发请求。
  assert.doesNotMatch(page, /apiRuntime\.request\(/)
})
