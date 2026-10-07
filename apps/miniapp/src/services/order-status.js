// 履约状态的展示与校验常量。取值必须与后端 OrderStatus 逐字一致；
// 前端只负责展示与校验，不参与状态决策。
export const ORDER_STATES = ['PENDING_PAYMENT', 'PAID', 'RECEIVED', 'IN_SERVICE', 'PENDING_VERIFY', 'COMPLETED', 'CLOSED', 'DISPUTED']
export const ORDER_ACTIONS = ['RECEIVE', 'START_SERVICE', 'FINISH_SERVICE', 'COMPLETE']

const STATE_LABELS = {
  PENDING_PAYMENT: '待支付', PAID: '已支付待接车', RECEIVED: '已接车待确认', IN_SERVICE: '施工中',
  PENDING_VERIFY: '待核销', COMPLETED: '已完成', CLOSED: '已关闭', DISPUTED: '争议中',
}
const ACTION_LABELS = { RECEIVE: '确认接车', START_SERVICE: '开始施工', FINISH_SERVICE: '完工送核销', COMPLETE: '确认完成' }
const CLOSE_REASONS = { OWNER_CANCELLED: '车主取消', PAYMENT_EXPIRED: '未支付到期' }

export const stateLabel = value => STATE_LABELS[value] || '状态未提供'
export const actionLabel = value => ACTION_LABELS[value] || '未知操作'
export const closeReasonLabel = value => (value ? CLOSE_REASONS[value] || '其他原因' : '')
/** 后端投影里的 allowed_actions 单项形状。 */
export const allowedAction = value => value !== null && typeof value === 'object' && !Array.isArray(value)
  && ORDER_ACTIONS.includes(value.action) && ORDER_STATES.includes(value.to_status)
export const allowedActions = value => Array.isArray(value) && value.every(allowedAction)
