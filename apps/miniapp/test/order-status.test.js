import test from 'node:test'
import assert from 'node:assert/strict'
import { ORDER_ACTIONS, ORDER_STATES, actionLabel, allowedAction, allowedActions, closeReasonLabel, stateLabel } from '../src/services/order-status.js'

test('状态与动作取值逐字对齐后端且无重复', () => {
  assert.deepEqual(ORDER_STATES, ['PENDING_PAYMENT', 'PAID', 'RECEIVED', 'IN_SERVICE', 'PENDING_VERIFY', 'COMPLETED', 'CLOSED', 'DISPUTED'])
  assert.deepEqual(ORDER_ACTIONS, ['RECEIVE', 'START_SERVICE', 'FINISH_SERVICE', 'COMPLETE'])
  assert.equal(new Set(ORDER_STATES).size, ORDER_STATES.length)
  assert.equal(new Set(ORDER_ACTIONS).size, ORDER_ACTIONS.length)
})

test('每个已声明取值都有中文标签，未知取值不伪装成已知状态', () => {
  for (const value of ORDER_STATES) assert.notEqual(stateLabel(value), '状态未提供', value)
  for (const value of ORDER_ACTIONS) assert.notEqual(actionLabel(value), '未知操作', value)
  assert.equal(stateLabel('PAID'), '已支付待接车')
  assert.equal(stateLabel('PENDING_VERIFY'), '待核销')
  assert.equal(stateLabel('CANCELLED'), '状态未提供')
  assert.equal(stateLabel(undefined), '状态未提供')
  assert.equal(actionLabel('RECEIVE'), '确认接车')
  assert.equal(actionLabel('PAY'), '未知操作')
  assert.equal(closeReasonLabel('OWNER_CANCELLED'), '车主取消')
  assert.equal(closeReasonLabel('PAYMENT_EXPIRED'), '未支付到期')
  assert.equal(closeReasonLabel('OTHER'), '其他原因')
  assert.equal(closeReasonLabel(null), '')
})

test('allowed_actions 只接受已声明的动作与目标状态', () => {
  assert.equal(allowedAction({ action: 'RECEIVE', to_status: 'RECEIVED' }), true)
  assert.equal(allowedAction({ action: 'COMPLETE', to_status: 'COMPLETED' }), true)
  assert.equal(allowedActions([]), true)
  assert.equal(allowedActions([{ action: 'RECEIVE', to_status: 'RECEIVED' }, { action: 'START_SERVICE', to_status: 'IN_SERVICE' }]), true)
  for (const value of [{ action: 'PAY', to_status: 'PAID' }, { action: 'RECEIVE', to_status: 'CANCELLED' }, { action: 'RECEIVE' }, { to_status: 'RECEIVED' }, null, undefined, 'RECEIVE', 1, []])
    assert.equal(allowedAction(value), false, JSON.stringify(value))
  for (const value of [null, undefined, 'RECEIVE', {}, [{ action: 'PAY', to_status: 'PAID' }]])
    assert.equal(allowedActions(value), false, JSON.stringify(value))
})
