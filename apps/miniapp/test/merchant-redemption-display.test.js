import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  COUPON_CODE_LENGTH, REDEEM_CODE_LENGTH, couponCooldown, couponFailureView, couponFormView,
  couponPreviewView, couponRecordsCopy, couponSavedView, couponSubmitView, redeemCodeView,
  redeemOrderFacts, redeemQueryFailureView, redeemReceiptView, redeemStageView, redeemSubmitView,
} from '../src/services/merchant-redemption-display.js'

const read = path => readFileSync(resolve(import.meta.dirname, '..', path), 'utf8')
const readPage = name => read(`src/pages/merchant/${name}`)
const CODE32 = 'abcdef0123456789abcdef0123456789'
const goodForm = { coupon: '12', revision: '3', code: CODE32 }

test('核销码只收数字，填满六位才可提交', () => {
  assert.equal(REDEEM_CODE_LENGTH, 6)
  const empty = redeemCodeView('')
  assert.equal(empty.complete, false); assert.equal(empty.missing, 6)
  assert.equal(empty.tone, 'muted'); assert.match(empty.hint, /还需 6 位/)
  // 非数字一律丢弃，超长截断：粘贴"12 34-56"不能变成 8 位。
  const mixed = redeemCodeView('12 34-56')
  assert.equal(mixed.digits, '123456'); assert.equal(mixed.complete, true)
  assert.equal(mixed.tone, 'ok'); assert.match(mixed.hint, /核对车主出示的六位数字/)
  assert.equal(redeemCodeView('12345').missing, 1)
  assert.equal(redeemCodeView(undefined).digits, '')
  assert.equal(redeemCodeView(123456).digits, '123456', '数字入参也要归一化成字符串')
  assert.equal(redeemCodeView('999999999').digits, '999999')
})

test('核销阶段：顺序即规则，读到凭据之前不给验码框', () => {
  assert.equal(redeemStageView(null, { loaded: true, redemption: null }).stage, 'no-order')

  // 只要有凭据（读取结果或提交回执）就是已核销，页面只读。
  const redeemed = redeemStageView({ status: 'PENDING_VERIFY' }, { loaded: true, redemption: { redeemed_at: '2026-10-20T02:00:00Z', test_mode: false } })
  assert.equal(redeemed.stage, 'redeemed'); assert.equal(redeemed.tone, 'ok')
  assert.match(redeemed.copy, /只读/)
  // 凭据优先于异常：已核销的单不该因为事后出现异常又把验码框放出来。
  assert.equal(redeemStageView({ status: 'DISPUTED', has_payment_exception: true }, { loaded: true, redemption: {} }).stage, 'redeemed')

  const loading = redeemStageView({ status: 'PENDING_VERIFY' }, { loaded: false, redemption: null })
  assert.equal(loading.stage, 'loading'); assert.equal(loading.tone, 'muted')
  assert.match(loading.copy, /不显示验码框/)
  // 未传 loaded（旧调用面）也算没读到，宁可多等一秒也不能对已核销订单重复提交。
  assert.equal(redeemStageView({ status: 'PENDING_VERIFY' }, {}).stage, 'loading')

  const history = redeemStageView({ status: 'COMPLETED' }, { loaded: true, redemption: null })
  assert.equal(history.stage, 'history-no-record'); assert.equal(history.tone, 'warn')
  assert.match(history.copy, /人工核对收款凭据/)

  const blocked = redeemStageView({ status: 'PENDING_VERIFY', has_payment_exception: true }, { loaded: true, redemption: null })
  assert.equal(blocked.stage, 'blocked'); assert.equal(blocked.tone, 'risk')
  assert.match(blocked.copy, /付款记录/)

  const notNow = redeemStageView({ status: 'IN_SERVICE' }, { loaded: true, redemption: null })
  assert.equal(notNow.stage, 'not-now'); assert.equal(notNow.tone, 'muted')
  assert.match(notNow.copy, /接车、防护、派工、报工/)

  const ready = redeemStageView({ status: 'PENDING_VERIFY' }, { loaded: true, redemption: null })
  assert.equal(ready.stage, 'ready'); assert.equal(ready.tone, 'warn')
  assert.match(ready.copy, /六位核销码/)

  // 未知/缺失状态落到"不能核销"，绝不当成可提交。
  assert.equal(redeemStageView({ status: 'WAT' }, { loaded: true, redemption: null }).stage, 'not-now')
  assert.equal(redeemStageView({}, { loaded: true, redemption: null }).stage, 'not-now')

  for (const stage of ['no-order', 'redeemed', 'loading', 'history-no-record', 'blocked', 'not-now', 'ready']) {
    const view = stage === 'no-order' ? redeemStageView(null, { loaded: true })
      : stage === 'redeemed' ? redeemStageView({ status: 'PENDING_VERIFY' }, { loaded: true, redemption: {} })
        : stage === 'loading' ? redeemStageView({ status: 'PENDING_VERIFY' }, { loaded: false })
          : stage === 'history-no-record' ? redeemStageView({ status: 'COMPLETED' }, { loaded: true })
            : stage === 'blocked' ? redeemStageView({ status: 'PENDING_VERIFY', has_payment_exception: true }, { loaded: true })
              : stage === 'not-now' ? redeemStageView({ status: 'IN_SERVICE' }, { loaded: true })
                : redeemStageView({ status: 'PENDING_VERIFY' }, { loaded: true })
    assert.equal(view.stage, stage)
    assert.ok(view.title.length >= 2 && view.copy.length > 8, `${stage} 的标题与说明不能为空`)
    assert.ok(['ok', 'warn', 'risk', 'muted'].includes(view.tone))
  }
})

test('订单事实：金额按字符串透传，绝不 Number 化', () => {
  const facts = redeemOrderFacts({
    order_no: 'VHM20261020001', amount_due: '88.50', status: 'PENDING_VERIFY',
    project_snapshot: { project_name: '小保养' },
  })
  assert.equal(facts.amountCopy, '应付 ¥88.50', '0.1+0.2 那一类浮点误差不能出现在金额上')
  assert.equal(facts.project, '小保养')
  assert.equal(facts.orderNoCopy, '订单号 VHM20261020001')
  assert.ok(facts.statusLabel.length > 0)
  assert.equal(facts.testModeCopy, '')
  assert.equal(facts.exceptionCopy, '')

  // 大额不丢精度，也不被四舍五入。
  assert.equal(redeemOrderFacts({ amount_due: '999999.99' }).amountCopy, '应付 ¥999999.99')
  // 数字入参才格式化；非法值退化成占位符而不是 NaN。
  assert.equal(redeemOrderFacts({ amount_due: 88 }).amountCopy, '应付 ¥88.00')
  assert.equal(redeemOrderFacts({ amount_due: '88.5' }).amountCopy, '应付 ¥—')
  assert.equal(redeemOrderFacts({}).amountCopy, '应付 ¥—')
  assert.equal(redeemOrderFacts({}).project, '历史订单')
  assert.equal(redeemOrderFacts({}).orderNoCopy, '订单号 未提供')
  assert.equal(redeemOrderFacts(undefined).amountCopy, '应付 ¥—')
})

test('测试核销必须自己说出来，不能伪装成真实收款', () => {
  const testFacts = redeemOrderFacts({ payment_summary: { test_mode: true }, amount_due: '0.01' })
  assert.equal(testFacts.testModeCopy, '测试核销，未真实扣款，不可作为收款凭据')
  const exception = redeemOrderFacts({ has_payment_exception: true })
  assert.match(exception.exceptionCopy, /付款存在异常，暂不可核销/)
  assert.equal(redeemOrderFacts({ has_payment_exception: false }).exceptionCopy, '')
  // 只认严格 true，字符串 "true" 不算。
  assert.equal(redeemOrderFacts({ payment_summary: { test_mode: 'true' } }).testModeCopy, '')
  assert.equal(redeemOrderFacts({ has_payment_exception: 'true' }).exceptionCopy, '')
})

test('核销凭据：测试核销与真实核销的标题必须不同', () => {
  const testReceipt = redeemReceiptView({ redeemed_at: '2026-10-20T02:00:00Z', test_mode: true })
  assert.equal(testReceipt.exists, true)
  assert.equal(testReceipt.title, '测试核销已完成')
  assert.equal(testReceipt.tone, 'warn')
  assert.match(testReceipt.timeCopy, /^核销时间 \S+/)
  assert.equal(testReceipt.testModeCopy, '未真实扣款，不可作为收款凭据')

  const real = redeemReceiptView({ redeemed_at: '2026-10-20T02:00:00Z', test_mode: false })
  assert.equal(real.title, '已核销'); assert.equal(real.tone, 'ok'); assert.equal(real.testModeCopy, '')

  // 提交回执（validRedemption 形状）与读取结果（redemption 行）字段同为 {redeemed_at, test_mode}。
  assert.equal(redeemReceiptView({ order_id: 7, order_status: 'COMPLETED', redeemed_at: '2026-10-20T02:00:00Z', test_mode: true, changed: true }).title, '测试核销已完成')

  for (const empty of [null, undefined, '', 0]) {
    const view = redeemReceiptView(empty)
    assert.equal(view.exists, false); assert.equal(view.title, ''); assert.equal(view.tone, 'muted')
  }
})

test('提交按钮：码不齐、冷却中、提交中分别说清楚', () => {
  const partial = redeemSubmitView('123', { busy: false }, 0)
  assert.equal(partial.ready, false); assert.equal(partial.label, '验码并核销'); assert.match(partial.hint, /还需 3 位/)

  const ready = redeemSubmitView('123456', { busy: false }, 0)
  assert.equal(ready.ready, true); assert.equal(ready.label, '验码并核销')

  const cooling = redeemSubmitView('123456', { busy: false }, 5.2)
  assert.equal(cooling.ready, false); assert.equal(cooling.cooldown, 6); assert.equal(cooling.label, '请等待 6 秒')
  assert.match(cooling.hint, /6 秒后再试/)

  const busy = redeemSubmitView('123456', { busy: true }, 0)
  assert.equal(busy.ready, false); assert.equal(busy.hint, '正在提交核销…')

  // 负数/NaN 冷却不能把按钮锁死。
  assert.equal(redeemSubmitView('123456', { busy: false }, -3).ready, true)
  assert.equal(redeemSubmitView('123456', { busy: false }, NaN).cooldown, 0)
  assert.equal(redeemSubmitView('123456', undefined, undefined).ready, true)
})

test('读取失败：越权给重新登录，其余给重试', () => {
  const unauthorized = redeemQueryFailureView({ failureKind: 'unauthorized', message: '登录已失效' }, {})
  assert.equal(unauthorized.canRelogin, true); assert.equal(unauthorized.message, '登录已失效')
  assert.equal(unauthorized.tone, 'risk')
  // 订单成功、凭据越权也要能重新登录。
  assert.equal(redeemQueryFailureView({ message: '' }, { failureKind: 'forbidden', message: '当前身份无权核销本店订单' }).canRelogin, true)
  const transient = redeemQueryFailureView({ failureKind: 'network', message: '网络问题' }, {})
  assert.equal(transient.canRelogin, false); assert.equal(transient.message, '网络问题')
  const empty = redeemQueryFailureView({}, {})
  assert.equal(empty.message, ''); assert.equal(empty.canRelogin, false)
})

test('服务券表单：三格各自校验，缺什么一次列全', () => {
  assert.equal(COUPON_CODE_LENGTH, 32)
  const empty = couponFormView({})
  assert.equal(empty.ready, false)
  assert.deepEqual(empty.missing, ['本人券编号', '授权版本', '32 位授权码'])
  assert.match(empty.hint, /还需填写：本人券编号、授权版本、32 位授权码/)

  const ok = couponFormView(goodForm)
  assert.equal(ok.ready, true); assert.deepEqual(ok.missing, [])
  assert.equal(ok.codeProgress, `32 / 32`)
  assert.match(ok.hint, /可以核对本店服务券与授权/)

  // 授权版本 0 表示"尚未授权"，不能当成已填。
  assert.equal(couponFormView({ ...goodForm, revision: '0' }).ready, false)
  assert.equal(couponFormView({ ...goodForm, coupon: '0' }).ready, false)
  // 编号不接受前导零、小数、超长。
  for (const coupon of ['01', '1.5', '12345678901234567', '', '-1']) {
    assert.equal(couponFormView({ ...goodForm, coupon }).couponOk, false, `coupon=${coupon} 应无效`)
  }
  // 授权码只认 32 位小写十六进制：大写、31/33 位、含非 hex 字符都不行。
  for (const code of [CODE32.toUpperCase(), CODE32.slice(0, 31), CODE32 + 'a', CODE32.replace('a', 'g'), '']) {
    assert.equal(couponFormView({ ...goodForm, code }).codeOk, false, `code=${code} 应无效`)
  }
  assert.equal(couponFormView({ ...goodForm, code: CODE32.slice(0, 5) }).codeProgress, '5 / 32')
  // 数字入参归一化，不抛错。
  assert.equal(couponFormView({ coupon: 12, revision: 3, code: CODE32 }).ready, true)
  assert.equal(couponFormView(undefined).ready, false)
})

test('预览卡：已核销不是"不可核销"', () => {
  const ready = couponPreviewView({
    user_coupon_id: 9, merchant_id: 3, project_id: 4, project_name: '精洗',
    terms: { name: '精洗服务券' }, authorization_revision: 2,
    authorization_expires_at: '2026-10-25T00:00:00Z', redemption: null, can_redeem: true,
  })
  assert.equal(ready.exists, true); assert.equal(ready.canRedeem, true); assert.equal(ready.tone, 'warn')
  assert.equal(ready.name, '精洗服务券'); assert.equal(ready.blockedCopy, ''); assert.equal(ready.redeemedCopy, '')
  assert.match(ready.projectCopy, /精洗 · 标准项目 4/)
  assert.match(ready.issuerCopy, /发行门店 3/)

  const blocked = couponPreviewView({ can_redeem: false, terms: {}, project_id: 4, project_name: '精洗', merchant_id: 3 })
  assert.equal(blocked.canRedeem, false); assert.equal(blocked.tone, 'risk')
  assert.match(blocked.blockedCopy, /发行门店、标准项目与授权有效期/)

  const redeemed = couponPreviewView({
    can_redeem: false, terms: { name: '精洗服务券' }, project_id: 4, project_name: '精洗', merchant_id: 3,
    redemption: { redemption_id: 11, redeemed_at: '2026-10-20T02:00:00Z', commission_amount: '0.00' },
  })
  assert.equal(redeemed.canRedeem, false)
  assert.equal(redeemed.tone, 'ok', '已核销用 ok 而不是 risk')
  assert.equal(redeemed.blockedCopy, '')
  assert.match(redeemed.redeemedCopy, /已于 .* 核销，凭据见下方/)

  const none = couponPreviewView(null)
  assert.equal(none.exists, false); assert.equal(none.canRedeem, false); assert.equal(none.tone, 'muted')
  assert.equal(couponPreviewView(undefined).name, '')
})

test('已核销凭据必须写明佣金 0.00 与"不是收款"', () => {
  const saved = couponSavedView({
    redemption_id: 11, user_coupon_id: 9, merchant_id: 3, project_id: 4,
    terms: { name: '精洗服务券' }, redeemed_at: '2026-10-20T02:00:00Z', commission_amount: '0.00',
  })
  assert.equal(saved.exists, true); assert.equal(saved.title, '服务券已核销'); assert.equal(saved.tone, 'ok')
  assert.equal(saved.lines.length, 3)
  assert.match(saved.lines[0], /精洗服务券/)
  assert.match(saved.lines[1], /核销凭据 11 · 佣金 ¥0\.00/)
  assert.equal(saved.lines[2], '此凭据不表示微信或现金收款。')
  assert.equal(couponSavedView(null).exists, false)
  assert.equal(couponSavedView(undefined).lines.length, 0)
})

test('核销记录：空态与页脚计数', () => {
  const empty = couponRecordsCopy({ items: [], total: 0 })
  assert.equal(empty.empty, true); assert.equal(empty.footer, '')
  assert.equal(empty.emptyTitle, '暂无核销记录'); assert.match(empty.emptyCopy, /倒序/)
  assert.equal(couponRecordsCopy(undefined).empty, true, '列表未初始化时按空态处理')

  const rows = couponRecordsCopy({ items: [{ redemption_id: 1 }, { redemption_id: 2 }], total: 9 })
  assert.equal(rows.empty, false); assert.equal(rows.emptyTitle, '')
  assert.equal(rows.footer, '本店核销记录 共 9 条 · 已加载 2 条')
  // total 缺失也不产生 NaN。
  assert.match(couponRecordsCopy({ items: [{ redemption_id: 1 }] }).footer, /共 0 条 · 已加载 1 条/)
})

test('冷却按秒向上取整，过期立即解除', () => {
  assert.deepEqual(couponCooldown(1000, 1000), { active: false, seconds: 0, label: '' })
  assert.equal(couponCooldown(1000, 4200).seconds, 4)
  assert.equal(couponCooldown(1000, 4200).label, '请等待 4 秒再核对')
  assert.equal(couponCooldown(1000, 1001).seconds, 1, '不足一秒也要提示 1 秒')
  assert.equal(couponCooldown(1000, 500).active, false)
  assert.equal(couponCooldown(1000, undefined).active, false)
})

test('服务券失败口径：只有越权给重新登录，结果不确定时保留原请求', () => {
  assert.equal(couponFailureView({ kind: 'unauthorized', message: '登录已失效' }).canRelogin, true)
  assert.equal(couponFailureView({ kind: 'forbidden', message: '当前账号无权操作' }).canRelogin, true)
  // coupon-transactions 用 kind 而不是 failureKind；写错字段会让重新登录按钮永远不出现。
  assert.equal(couponFailureView({ failureKind: 'unauthorized', message: 'x' }).canRelogin, false)
  const uncertain = couponFailureView({ kind: 'network', message: '网络问题', uncertain: true })
  assert.equal(uncertain.uncertain, true); assert.equal(uncertain.canRelogin, false)
  assert.equal(uncertain.tone, 'risk'); assert.equal(uncertain.message, '网络问题')
  assert.equal(couponFailureView({}).uncertain, false)
})

test('两个按钮的可用性：三格齐才能核对，can_redeem 才能核销', () => {
  const empty = couponSubmitView({ busy: false, preview: null, waitUntil: 0 }, 1000, {})
  assert.equal(empty.canPreview, false); assert.equal(empty.canRedeem, false)
  assert.equal(empty.previewLabel, '核对本店服务券与授权')
  assert.equal(empty.redeemLabel, '确认交付并核销服务券')
  assert.equal(empty.retryLabel, '重试原核销请求')

  const previewable = couponSubmitView({ busy: false, preview: null, waitUntil: 0 }, 1000, goodForm)
  assert.equal(previewable.canPreview, true)
  assert.equal(previewable.canRedeem, false, '预览前不能核销')

  const reversible = couponSubmitView({ busy: false, preview: { can_redeem: true }, waitUntil: 0 }, 1000, goodForm)
  assert.equal(reversible.canRedeem, true)
  // can_redeem 缺失或为真值字符串都不算可核销。
  assert.equal(couponSubmitView({ busy: false, preview: {}, waitUntil: 0 }, 1000, goodForm).canRedeem, false)
  assert.equal(couponSubmitView({ busy: false, preview: { can_redeem: 'true' }, waitUntil: 0 }, 1000, goodForm).canRedeem, false)

  // 提交中、冷却中、结果不确定：两个按钮都必须锁住。
  for (const state of [{ busy: true }, { waitUntil: 9000 }, { uncertain: true }]) {
    const locked = couponSubmitView({ busy: false, preview: { can_redeem: true }, waitUntil: 0, ...state }, 1000, goodForm)
    assert.equal(locked.canPreview, false, `${JSON.stringify(state)} 应锁住核对`)
    assert.equal(locked.canRedeem, false, `${JSON.stringify(state)} 应锁住核销`)
  }
  assert.equal(couponSubmitView({ busy: false, uncertain: true, waitUntil: 0 }, 1000, goodForm).form.ready, true, '表单进度不受锁影响')
  assert.equal(couponSubmitView({ busy: false, preview: { can_redeem: true }, waitUntil: 1599 }, 1000, goodForm).cooldown.seconds, 1)
})

test('核销页使用映射，并保住端到端依赖的文案与锚点', () => {
  const page = readPage('redeem.vue')
  for (const fn of ['redeemStageView', 'redeemOrderFacts', 'redeemReceiptView', 'redeemQueryFailureView', 'redeemSubmitView']) {
    assert.match(page, new RegExp(`${fn}\\(`), `redeem.vue 必须使用 ${fn}`)
  }
  assert.match(page, /toneClass\(/)
  assert.match(page, /ux-hero/); assert.match(page, /ux-card/)
  for (const id of ['redeem-form', 'redeem-order', 'redeem-stage', 'redemption-receipt', 'redeem-form-card', 'redeem-code', 'redeem-submit', 'redeem-test-warning', 'redeem-error', 'redeem-login']) {
    assert.match(page, new RegExp(`data-testid="${id}"`), `缺少锚点 ${id}`)
  }
  // 端到端脚本按按钮文案 tap、按提示断言，文案不能改写。
  assert.match(page, /submitView\.label/, '提交按钮文案必须来自映射（"验码并核销" / "请等待 N 秒"）')
  assert.match(page, /facts\.testModeCopy/, '测试核销提示必须来自映射')
  assert.match(page, /receiptView\.title/, '凭据标题必须来自映射（"测试核销已完成"）')
  // 凭据行要把"读取结果"和"提交回执"合并，否则提交成功后规则会退回可编辑态。
  assert.match(page, /receipt\.value\?\.redemption \|\| state\.saved/)
  // 仍由 redemptionBody + 幂等流把关，页面不自己拼请求。
  assert.match(page, /createRedemptionFlow\(/)
  assert.match(page, /newKey: imageRequestKey/)
  // 旧模板的内联校验与三层 else-if 不应残留。
  assert.doesNotMatch(page, /\^\[0-9\]\{6\}\$/)
  assert.doesNotMatch(page, /v-else-if="detail\.value\.status/)
  assert.doesNotMatch(page, /remaining>0 \? `请等待/)
  // 只读态不能出输入框：整页只有验码卡一个 input，且它在 stage==='ready' 的卡片内。
  assert.equal(page.split('<input').length - 1, 1, 'read-only 态下不应有第二个输入框')
  assert.match(page, /v-if="stageView\.stage === 'ready'"[\s\S]{0,900}?<input/)
})

test('服务券页使用映射，并保住扫码与记录契约', () => {
  const page = readPage('coupon-redeem.vue')
  for (const fn of ['couponSubmitView', 'couponPreviewView', 'couponSavedView', 'couponFailureView', 'couponRecordsCopy']) {
    assert.match(page, new RegExp(`${fn}\\(`), `coupon-redeem.vue 必须使用 ${fn}`)
  }
  assert.match(page, /toneClass\(/)
  assert.match(page, /ux-hero/); assert.match(page, /ux-card/)
  for (const id of ['coupon-redeem-form', 'coupon-form', 'coupon-scan', 'coupon-id', 'coupon-revision', 'coupon-code', 'coupon-preview', 'coupon-preview-card', 'coupon-redeem', 'coupon-saved', 'coupon-error', 'coupon-records', 'coupon-empty', 'coupon-retry']) {
    assert.match(page, new RegExp(`data-testid="${id}"`), `缺少锚点 ${id}`)
  }
  // 扫码解析与失败降级都不能绕开既有服务。
  assert.match(page, /parseServiceCouponQr\(/)
  assert.match(page, /apiRuntime\.scanCode\(/)
  assert.match(page, /createMerchantCouponFlow\(/)
  assert.match(page, /newKey: imageRequestKey/)
  assert.match(page, /couponTransactionsApi\.serviceRecords/, '记录列表必须走服务券核销记录接口')
  // 按钮文案与锁定都来自映射，不在模板里硬编码。
  assert.match(page, /submitView\.previewLabel/)
  assert.match(page, /submitView\.redeemLabel/)
  assert.match(page, /submitView\.retryLabel/)
  assert.doesNotMatch(page, /确认交付并核销服务券/)
  assert.doesNotMatch(page, /:disabled="state\.busy \|\| Date\.now\(\)/)
  // 已核销态要能和"不可核销"区分开。
  assert.match(page, /previewView\.redeemedCopy/)
  assert.match(page, /previewView\.tone === 'ok' \? '已核销'/)
})

test('常量与既有校验器同源，避免两处各写一份', () => {
  // 六位码在 order-redemption 里就是 /^[0-9]{6}$/。
  assert.match(read('src/services/order-redemption.js'), /\^\[0-9\]\{6\}\$/)
  assert.equal(REDEEM_CODE_LENGTH, 6)
  // 32 位授权码在 coupon-transactions 里就是 /^[a-f0-9]{32}$/。
  assert.match(read('src/services/coupon-transactions.js'), /\^\[a-f0-9\]\{32\}\$/)
  assert.equal(COUPON_CODE_LENGTH, 32)
  // 页面不再自己数字符，长度只从映射来。
  for (const name of ['redeem.vue', 'coupon-redeem.vue']) {
    assert.doesNotMatch(readPage(name), /\^\[0-9\]\{6\}\$|\^\[a-f0-9\]\{32\}\$/, `${name} 不应重复定义码格式`)
  }
})
