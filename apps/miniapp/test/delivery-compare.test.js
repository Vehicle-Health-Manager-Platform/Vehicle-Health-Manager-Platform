import test from 'node:test'
import assert from 'node:assert/strict'
import {validDeliveryCompare,MISSING_CODES,createDeliveryCompareApi} from '../src/services/delivery-compare.js'
import {deliveryPageView,deliveryResultView,deliveryBaselineView,deliveryWorkView,deliveryMissingView,deliveryFailureView} from '../src/services/delivery-compare-display.js'

const ready=()=>({
  order_id:301,order_status:'PENDING_VERIFY',result:'READY',deliverable:true,
  baseline:{pickup_check_id:301,mileage:120,mileage_delta:0,fuel_level:'HALF',damage_status:'NONE',damage_count:0,
    photo_count:7,photo_slots:['DASHBOARD','FRONT','INTERIOR','LEFT','REAR','RIGHT','ROOF'],owner_confirm:1,confirm_at:'2026-10-20T02:30:00Z'},
  work:{protection:{submitted:true,item_count:2,photo:true},
    report:{submitted:true,status:'SIGNED',no_fault_parts:true,no_parts:true,signed_at:'2026-10-20T07:30:00Z',
      process_photos:1,fault_photos:0,finish_photos:1,signature_photos:1}},
  missing:[],
})
const incomplete=()=>{const n=ready();n.result='INCOMPLETE';n.deliverable=false;n.missing=['OWNER_CONFIRM','PROTECTION_PHOTO','FINISH','FAULT'];n.baseline.owner_confirm=0;n.baseline.confirm_at=null;n.work.protection={submitted:false,item_count:0,photo:false};n.work.report.finish_photos=0;n.work.report.no_fault_parts=false;return n}

test('schema accepts a complete or incomplete projection and rejects contradictions',()=>{
  assert.ok(validDeliveryCompare(ready()));assert.ok(validDeliveryCompare(incomplete()))
  const edits=[
    n=>n.staff_id=12,
    n=>n.result='ALMOST',
    n=>n.missing=['UNKNOWN_CODE'],
    n=>n.missing=['FINISH','FINISH'],
    n=>{n.result='READY';n.missing=['FINISH']},
    n=>n.result='INCOMPLETE',
    n=>n.deliverable='yes',
    n=>n.baseline.mileage='120',
    n=>n.baseline.mileage_delta=1.5,
    n=>n.baseline.fuel_level='FULL_TANK',
    n=>n.baseline.photo_count=8,
    n=>n.baseline.photo_slots.push('FUEL'),
    n=>n.baseline.photo_slots=['FRONT','FRONT'],
    n=>n.baseline.owner_confirm=9,
    n=>n.baseline.confirm_at='2026-10-20 02:30:00',
    n=>n.baseline.damage_count=-1,
    n=>n.work.protection.submitted=false,
    n=>n.work.protection.item_count=99,
    n=>n.work.report.status='DONE',
    n=>n.work.report.submitted=false,
    n=>n.work.report.signed_at=null,
    n=>n.work.report.signature_photos=2,
    n=>n.work.report.process_photos=10,
    n=>n.work.report.extra=1,
    n=>delete n.work,
  ]
  for(const change of edits){const n=ready();change(n);assert.equal(validDeliveryCompare(n),false,`edit ${change}`)}
  const missing=incomplete();missing.missing=MISSING_CODES;assert.ok(validDeliveryCompare(missing))
})

test('API is an authenticated read-only POST bound to one order with no idempotency key',async()=>{
  let call
  const api=createDeliveryCompareApi({runtime:()=>({request:r=>{call=r;r.success({statusCode:200,data:{code:0,data:ready()}})}})})
  assert.deepEqual(await api.compare('owner-token',301),ready())
  assert.equal(call.method,'POST');assert.ok(call.url.endsWith('/api/check/delivery/compare'))
  assert.deepEqual(call.data,{order_id:301});assert.equal(call.header.Authorization,'Bearer owner-token')
  assert.equal(Object.keys(call.header).some(k=>k.toLowerCase()==='idempotency-key'),false)
  await assert.rejects(api.compare('owner-token',302),e=>e.kind==='protocol')
  assert.throws(()=>api.compare('',301),e=>e.kind==='unauthorized')
  assert.throws(()=>api.compare('owner-token',0),e=>e.kind==='invalid')
  await assert.rejects(createDeliveryCompareApi({runtime:()=>({request:r=>r.success({statusCode:200,data:{code:0,data:{...ready(),result:'INCOMPLETE'}}})})}).compare('t',301),e=>e.kind==='protocol')
})

test('status codes map to stable kinds so the page can decide what to show',async()=>{
  const fail=statusCode=>createDeliveryCompareApi({runtime:()=>({request:r=>r.success({statusCode,data:{code:statusCode*100}})})}).compare('t',301)
  await assert.rejects(fail(409),e=>e.kind==='conflict')
  await assert.rejects(fail(401),e=>e.kind==='unauthorized')
  await assert.rejects(fail(403),e=>e.kind==='forbidden')
  await assert.rejects(fail(404),e=>e.kind==='missing')
  await assert.rejects(fail(400),e=>e.kind==='invalid')
  await assert.rejects(fail(503),e=>e.kind==='unavailable')
  await assert.rejects(createDeliveryCompareApi({runtime:()=>({request:r=>r.fail()})}).compare('t',301),e=>e.kind==='network')
})

test('result view explains deliverable, completed and incomplete cases differently',()=>{
  const readyView=deliveryResultView(ready())
  assert.equal(readyView.label,'证据齐备');assert.equal(readyView.tone,'ok');assert.equal(readyView.deliverable,true)
  assert.match(readyView.deliverableCopy,/核销/)
  const done=ready();done.order_status='COMPLETED';done.deliverable=false
  assert.match(deliveryResultView(done).deliverableCopy,/不再重复交付/)
  const short=incomplete()
  assert.equal(deliveryResultView(short).label,'仍有缺口');assert.equal(deliveryResultView(short).tone,'warn')
  assert.match(deliveryResultView(short).deliverableCopy,/补齐/)
  const readyNotDeliverable=ready();readyNotDeliverable.deliverable=false
  assert.match(deliveryResultView(readyNotDeliverable).deliverableCopy,/待核销/)
})

test('baseline view turns counts and codes into readable facts',()=>{
  const view=deliveryBaselineView(ready())
  assert.equal(view.mileageCopy,'接车里程 120 km');assert.equal(view.deltaCopy,'与接车基线一致');assert.equal(view.deltaTone,'muted')
  assert.equal(view.fuelCopy,'接车油量 ½');assert.equal(view.damageCopy,'接车时记录无损伤')
  assert.match(view.photoCopy,/接车照片 7 张/);assert.match(view.photoCopy,/仪表盘/);assert.match(view.photoCopy,/车顶/)
  assert.equal(view.confirmCopy,'车主已确认接车');assert.match(view.confirmAtCopy,/确认时间/)
  const later=ready();later.baseline.mileage_delta=12;later.baseline.damage_status='PRESENT';later.baseline.damage_count=2;later.baseline.owner_confirm=2;later.baseline.confirm_at=null
  assert.equal(deliveryBaselineView(later).deltaCopy,'比接车基线多 12 km')
  assert.equal(deliveryBaselineView(later).damageCopy,'接车时记录 2 处损伤')
  assert.equal(deliveryBaselineView(later).confirmCopy,'车主已提出异议')
  const rolled=ready();rolled.baseline.mileage_delta=-5
  assert.equal(deliveryBaselineView(rolled).deltaTone,'risk');assert.match(deliveryBaselineView(rolled).deltaCopy,/少 5 km/)
})

test('work view states protection, report state and each evidence count',()=>{
  const view=deliveryWorkView(ready())
  assert.equal(view.protectionCopy,'施工防护已记录 2 项，含防护照片')
  assert.equal(view.reportCopy,'已质检签字');assert.match(view.signedAtCopy,/质检签字/)
  assert.match(view.evidenceCopy,/过程照 1 张/);assert.match(view.evidenceCopy,/已声明无故障件/);assert.match(view.evidenceCopy,/完工照 1 张/);assert.match(view.evidenceCopy,/签字 1 张/)
  assert.match(view.spareCopy,/未使用配件/)
  const short=deliveryWorkView(incomplete())
  assert.equal(short.protectionCopy,'施工防护尚未记录');assert.equal(short.reportCopy,'已质检签字')
  assert.match(short.evidenceCopy,/完工照 0 张/);assert.match(short.evidenceCopy,/故障件 0 张/)
  const noPhoto=ready();noPhoto.work.protection={submitted:true,item_count:3,photo:false}
  assert.match(deliveryWorkView(noPhoto).protectionCopy,/缺少防护照片/)
})

test('missing view lists every code and never shows a bare code',()=>{
  assert.equal(deliveryMissingView(ready()).copy,'证据齐备，没有缺口。');assert.equal(deliveryMissingView(ready()).empty,true)
  const view=deliveryMissingView(incomplete())
  assert.equal(view.count,4);assert.equal(view.empty,false);assert.equal(view.tone,'warn')
  for(const code of incomplete().missing)assert.equal(view.labels.includes(code),false)
  assert.match(view.copy,/车主尚未确认接车单/);assert.match(view.copy,/缺少完工照片/);assert.match(view.copy,/未声明无故障件/)
  assert.deepEqual(deliveryMissingView({}).labels,[]);assert.equal(deliveryMissingView({}).copy,'证据齐备，没有缺口。')
})

test('failure view routes a missing baseline to the pickup sheet and auth errors to login',()=>{
  const conflict=deliveryFailureView({failureKind:'conflict',message:'接车检查还没完成，暂时无法对比'})
  assert.equal(conflict.conflict,true);assert.equal(conflict.title,'缺少接车检查基线');assert.equal(conflict.canLogin,false)
  const auth=deliveryFailureView({failureKind:'unauthorized',message:'登录已失效，请重新登录'})
  assert.equal(auth.canLogin,true);assert.equal(auth.conflict,false)
  const offline=deliveryFailureView({failureKind:'network',message:'网络异常'})
  assert.equal(offline.retryLabel,'重试加载');assert.equal(offline.canLogin,false)
  assert.equal(deliveryFailureView(undefined).message,'')
})

test('merchant and owner read the same projection with different wording',()=>{
  const owner=deliveryPageView('owner'),merchant=deliveryPageView('merchant')
  assert.equal(owner.title,'取车对比');assert.equal(merchant.title,'取车对比（核销前核对）')
  assert.match(merchant.hint,/再决定是否核销/);assert.equal(owner.hint.includes('核销'),false)
  assert.match(deliveryResultView(ready(),'merchant').deliverableCopy,/核销前无需再补/)
  assert.match(deliveryResultView(ready()).deliverableCopy,/商家核销完成交付/)
  assert.match(deliveryResultView(incomplete(),'merchant').deliverableCopy,/核销前请先按缺口补齐/)
  assert.match(deliveryResultView(incomplete()).deliverableCopy,/补齐后商家才能核销交付/)
  const readyNotDeliverable=ready();readyNotDeliverable.deliverable=false
  assert.match(deliveryResultView(readyNotDeliverable,'merchant').deliverableCopy,/暂不能核销/)
  // 未知角色按车主口径，绝不因为参数写错就说成商家视角。
  assert.equal(deliveryResultView(ready(),'nobody').deliverableCopy,deliveryResultView(ready()).deliverableCopy)
  assert.equal(deliveryPageView('nobody').title,'取车对比')
})
