import {validRefundDetail,refundStatusLabel} from './order-refunds.js'
import {displayTime} from './reservations.js'
import {stateLabel} from './order-status.js'

const statuses={
  REQUESTED:{tone:'warn',copy:'等待店长确认，订单仍按原流程执行。'},
  REJECTED:{tone:'muted',copy:'店长未同意本次取消；订单仍按当前进度执行，可核对原因后重新申请。'},
  WITHDRAWN:{tone:'muted',copy:'本次申请已撤回，订单仍按当前进度执行。'},
  PENDING:{tone:'warn',copy:'双方已同意取消，订单已关闭；退款仍在处理中，尚未确认到账。'},
  FAILED:{tone:'risk',copy:'订单保持关闭；供应商报告退款失败，需继续核对，不能视为资金已退回。'},
  SUCCEEDED:{tone:'ok',copy:'退款记录为成功，请结合原支付渠道核对；测试记录不代表真实资金退回。'},
}
const positive=n=>Number.isSafeInteger(n)&&n>0
const text=v=>typeof v==='string'?v.trim():''
const noteOk=v=>text(v).length>0&&text(v).length<=256
const money=v=>typeof v==='string'&&/^(0|[1-9]\d{0,7})\.\d{2}$/.test(v)?v:'—'
const time=v=>typeof v==='string'&&Number.isFinite(Date.parse(v))?displayTime(v):'未提供'

export function refundStageView(detail){
  if(!detail)return {stage:'loading',title:'退款记录尚未加载',tone:'muted',copy:'请先读取当前订单与退款记录。',changed:false}
  if(!validRefundDetail(detail))return {stage:'unknown',title:'退款信息需要重新核对',tone:'risk',copy:'状态或记录不完整，请刷新；核对前不开放操作。',changed:false}
  const r=detail.refund
  if(!r)return {stage:'empty',title:'尚无退款申请',tone:'muted',copy:detail.can_request?'可填写原因申请取消并全额退款，提交后需等待店长确认。':'未支付订单请回订单详情取消；已完成订单不开放本流程。',changed:false}
  const changed=r.status==='REQUESTED'&&detail.order_status!==r.from_status
  return {stage:r.status,title:refundStatusLabel(r.status),...statuses[r.status],changed,
    ...(changed?{copy:'订单状态已变化，请车主撤回后重新申请；店长不能接受旧申请，仍可说明原因并拒绝。'}:{})}
}

export function refundFactsView(detail){
  const r=detail?.refund
  return {orderCopy:`订单 ${positive(detail?.order_id)?detail.order_id:'未提供'}`,statusLabel:stateLabel(detail?.order_status),
    amountCopy:`全额 ¥${money(r?.amount)}`,reason:r?.reason||'',note:r?.decision_note||'',
    ownerTime:r?time(r.owner_consented_at):'',merchantTime:r?.merchant_consented_at?time(r.merchant_consented_at):'',
    refundedTime:r?.refunded_at?time(r.refunded_at):'',
    testCopy:r?.test_mode===true?'隔离测试退款，未真实退回资金，不可作为退款凭据。':''}
}

/** 每个动作单独列出可用性与原因；角色和状态之外，读取/核对/未决写入均会阻断新动作。 */
export function refundActionsView(detail,role,form={},read={},write={},confirming=false,query={}){
  const trusted=validRefundDetail(detail),r=detail?.refund
  const available=trusted&&read.loaded===true&&!read.busy&&!read.message
  const locked=write.busy===true||write.retry===true||confirming===true||query.busy===true
  const canRequest=available&&role==='owner'&&detail.can_request===true
  const canWithdraw=available&&role==='owner'&&r?.status==='REQUESTED'
  const canDecide=available&&role==='merchant'&&r?.status==='REQUESTED'
  const canReconcile=available&&role==='merchant'&&['PENDING','FAILED','SUCCEEDED'].includes(r?.status)
  const reasonReady=noteOk(form.reason),noteReady=noteOk(form.note),optionalNoteOk=text(form.note).length<=256
  const changed=canDecide&&detail.order_status!==r.from_status
  const lockCopy=write.retry?'上次操作结果未确定，请先重试原请求。':write.busy?'正在提交退款操作…':confirming?'请先完成本次确认。':query.busy?'正在核对退款结果…':!available?'请先重新读取并核对订单与退款记录。':''
  return {
    canRequest,canWithdraw,canDecide,canReconcile,
    requestReady:canRequest&&!locked&&reasonReady,
    withdrawReady:canWithdraw&&!locked,
    acceptReady:canDecide&&!locked&&!changed&&optionalNoteOk,
    rejectReady:canDecide&&!locked&&noteReady,
    reconcileReady:canReconcile&&!locked,
    formDisabled:locked,
    requestHint:lockCopy||(!reasonReady?'请填写1至256字取消与全额退款原因。':'提交仅发起申请，店长同意后才关闭订单并处理退款。'),
    decisionHint:lockCopy||(changed?'订单状态已变化，不能接受旧申请；仍可填写原因拒绝。':!optionalNoteOk?'处理说明最多256字。':!noteReady?'同意时说明可不填；拒绝前须填写1至256字原因。':'已填写处理说明，请核对后选择同意或拒绝。'),
    lockCopy,
  }
}

export function refundActionBody(action,detail,role,form,read,write,confirming=false,query){
  const v=refundActionsView(detail,role,form,read,write,confirming,query)
  const ready={REQUEST:v.requestReady,WITHDRAW:v.withdrawReady,ACCEPT:v.acceptReady,REJECT:v.rejectReady}[action]
  if(!ready)return null
  return {action,order_id:detail.order_id,revision:detail.refund?.revision,
    reason:text(form.reason),note:text(form.note)}
}

export function refundConfirmationView(action){
  return {
    REQUEST:{title:'申请取消并全额退款',content:'提交即表示你同意全额退款取消。等待店长确认期间订单仍按原流程执行；本阶段不支持部分退款。'},
    WITHDRAW:{title:'撤回取消申请',content:'确认撤回本次申请？撤回后订单继续按原流程执行。'},
    ACCEPT:{title:'同意取消并全额退款',content:'确认后订单关闭，施工与核销停止；资金仍需供应商处理，不可撤回。'},
    REJECT:{title:'拒绝取消申请',content:'确认提交所填拒绝原因？本次申请被拒绝后，订单继续按当前进度执行。'},
  }[action]||null
}

export function refundFailureView(read={},write={},query={}){
  const failed=[read,write,query].filter(s=>s.message&&s.failureKind)
  return {canRelogin:failed.some(s=>['unauthorized','forbidden'].includes(s.failureKind)),
    readMessage:read.message||'',writeMessage:write.failureKind?write.message:'',queryMessage:query.message||'',
    retryOriginal:write.retry===true}
}

export function refundWriteResultView(write={}){
  if(!write.saved)return {exists:false,title:'',copy:'',tone:'muted'}
  const stage=refundStageView(write.saved)
  return {exists:true,title:'操作已保存',copy:stage.copy,tone:stage.tone}
}

/** capture含当前会话/路由/详情/正文，仅保存在本次弹窗闭包内，不持久化或打印。 */
export function createRefundConfirmationFlow({state,capture,confirm,dispatch,onChanged=()=>{}}){
  let epoch=0
  function cancel(){epoch++;state.busy=false}
  async function run(action){
    if(state.busy||!refundConfirmationView(action))return false
    const snapshot=capture(action)
    if(!snapshot)return false
    const frozen=JSON.stringify(snapshot),current=++epoch
    state.busy=true
    try{
      if(await confirm(refundConfirmationView(action))!==true)return false
      if(current!==epoch)return false
      if(JSON.stringify(capture(action))!==frozen){onChanged('确认期间账号、订单或填写内容已变化，请重新核对后操作。');return false}
      await dispatch(JSON.parse(frozen).body)
      return true
    }catch{if(current===epoch)onChanged('确认或提交未完成，请核对当前记录及原请求状态。');return false}
    finally{if(current===epoch)state.busy=false}
  }
  return {run,cancel}
}
