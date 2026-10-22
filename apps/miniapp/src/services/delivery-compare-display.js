/** 取车对比的纯展示映射：结论、基线事实、完工事实与缺口文案都从这里出。
 * 只吃后端投影（data 载荷），不吃读取流程的 state——流程里的载荷在 state.value。
 * 这里不判定"能不能交付"：结论与缺口都来自服务端，页面只负责把它说清楚。 */
import {displayTime} from './reservations.js'
import {stateLabel} from './order-status.js'
import {PICKUP_LABELS,FUEL_VALUES,FUEL_LABELS} from './pickup.js'
import {MISSING_LABELS,RESULT_LABELS,REPORT_STATE_LABELS,OWNER_CONFIRM_LABELS} from './delivery-compare.js'

const num=(v,fallback=0)=>Number.isFinite(v)?v:fallback
const say=(v,fallback)=>typeof v==='string'&&v.trim()?v:fallback

const fuelLabel=value=>{const index=FUEL_VALUES.indexOf(value);return index>=0?FUEL_LABELS[index]:'未记录'}

/** 结论徽标与一句话解释；可交付时说明"取车时由商家核销完成"。 */
export function deliveryResultView(data){
  const ready=data?.result==='READY'
  const deliverable=data?.deliverable===true
  return {
    label:RESULT_LABELS[data?.result]||'结果未提供',
    tone:ready?'ok':'warn',
    statusCopy:`订单当前状态：${stateLabel(data?.order_status)}`,
    deliverable,
    deliverableCopy:deliverable
      ? '证据齐备，到店取车时由商家核销完成交付。'
      : data?.order_status==='COMPLETED'
        ? '订单已完成，这里只做证据回看，不再重复交付。'
        : ready
          ? '证据已齐备，但订单还不在待核销阶段，暂不能交付。'
          : '证据还不齐，补齐后商家才能核销交付。',
  }
}

/** 接车基线：里程与差值、油量、损伤、照片张数与槽位、车主确认态。 */
export function deliveryBaselineView(data){
  const b=data?.baseline||{}
  const slots=(Array.isArray(b.photo_slots)?b.photo_slots:[]).map(slot=>PICKUP_LABELS[slot]||slot)
  const delta=num(b.mileage_delta)
  return {
    mileageCopy:`接车里程 ${num(b.mileage)} km`,
    deltaCopy:delta===0?'与接车基线一致':delta>0?`比接车基线多 ${delta} km`:`比接车基线少 ${Math.abs(delta)} km，请核对`,
    deltaTone:delta<0?'risk':delta===0?'muted':'ok',
    fuelCopy:`接车油量 ${fuelLabel(b.fuel_level)}`,
    damageCopy:b.damage_status==='PRESENT'?`接车时记录 ${num(b.damage_count)} 处损伤`:'接车时记录无损伤',
    photoCopy:`接车照片 ${num(b.photo_count)} 张${slots.length?`：${slots.join('、')}`:''}`,
    confirmCopy:OWNER_CONFIRM_LABELS[b.owner_confirm]||'接车确认态未提供',
    confirmAtCopy:b.confirm_at?`确认时间 ${displayTime(b.confirm_at)}`:'',
  }
}

/** 完工证据：防护、报工状态与四类照片计数。计数为 0 时直接说明缺哪类。 */
export function deliveryWorkView(data){
  const w=data?.work||{}
  const p=w.protection||{}
  const r=w.report||{}
  return {
    protectionCopy:p.submitted
      ? `施工防护已记录 ${num(p.item_count)} 项${p.photo?'，含防护照片':'，但缺少防护照片'}`
      : '施工防护尚未记录',
    reportCopy:REPORT_STATE_LABELS[r.status]||'报工状态未提供',
    signedAtCopy:r.signed_at?`质检签字 ${displayTime(r.signed_at)}`:'',
    evidenceCopy:`过程照 ${num(r.process_photos)} 张 · 故障件 ${r.no_fault_parts?'已声明无故障件':`${num(r.fault_photos)} 张`} · 完工照 ${num(r.finish_photos)} 张 · 签字 ${num(r.signature_photos)} 张`,
    spareCopy:r.no_parts?'本次明确记录未使用配件。':'',
  }
}

/** 缺口清单：每一条都要说人话，不能只丢编码。 */
export function deliveryMissingView(data){
  const missing=Array.isArray(data?.missing)?data.missing:[]
  return {
    count:missing.length,
    empty:missing.length===0,
    labels:missing.map(code=>MISSING_LABELS[code]||code),
    copy:missing.length?`还差 ${missing.length} 项：${missing.map(code=>MISSING_LABELS[code]||code).join('；')}`:'证据齐备，没有缺口。',
    tone:missing.length?'warn':'ok',
  }
}

/** 读取失败：接车单缺失（conflict）要引导去看接车单，其余给重试或重新登录。 */
export function deliveryFailureView(read){
  const kind=say(read?.failureKind,'')
  return {
    message:say(read?.message,''),
    conflict:kind==='conflict',
    title:kind==='conflict'?'缺少接车检查基线':'取车对比未能加载',
    retryLabel:'重试加载',
    canLogin:kind==='unauthorized'||kind==='forbidden',
  }
}
