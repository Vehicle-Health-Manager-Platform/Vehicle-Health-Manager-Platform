import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {refundStageView,refundFactsView,refundActionsView,refundActionBody,refundConfirmationView,refundFailureView,refundWriteResultView,createRefundConfirmationFlow} from '../src/services/order-refund-display.js'
import {createReservationWriteFlow,initialReservationWriteState,ReservationError} from '../src/services/reservations.js'

const stamp='2026-10-21T00:00:00Z'
const record={refund_id:1,refund_no:'R'+'a'.repeat(32),payment_id:2,amount:'12.34',currency:'CNY',status:'REQUESTED',revision:1,from_status:'PAID',reason:'取消',decision_note:null,owner_consented_at:stamp,merchant_consented_at:null,refunded_at:null,test_mode:true}
const detail=(status='REQUESTED')=>({order_id:3,order_status:['PENDING','FAILED','SUCCEEDED'].includes(status)?'CLOSED':'PAID',can_request:false,refund:{...record,status,merchant_consented_at:['PENDING','FAILED','SUCCEEDED'].includes(status)?stamp:null,refunded_at:status==='SUCCEEDED'?stamp:null}})
const empty=()=>({order_id:3,order_status:'PAID',can_request:true,refund:null})
const read={loaded:true,busy:false,message:''}
const actions=(d,role='merchant',form={},write={},other={})=>refundActionsView(d,role,form,read,write,other.confirming,other.query)

test('六状态保持订单与资金语义，无申请和异常记录分别处理',()=>{
  assert.equal(refundStageView(null).stage,'loading')
  assert.equal(refundStageView(empty()).stage,'empty')
  for(const status of ['REQUESTED','REJECTED','WITHDRAWN','PENDING','FAILED','SUCCEEDED'])assert.equal(refundStageView(detail(status)).stage,status)
  assert.match(refundStageView(detail('REQUESTED')).copy,/订单仍按原流程/)
  assert.match(refundStageView(detail('PENDING')).copy,/尚未确认到账/)
  assert.match(refundStageView(detail('FAILED')).copy,/不能视为资金已退回/)
  assert.match(refundStageView(detail('SUCCEEDED')).copy,/测试记录不代表真实资金/)
  for(const bad of [{},{...detail(),refund:{...record,status:'WAT'}},{...detail('PENDING'),order_status:'PAID'}]){
    assert.equal(refundStageView(bad).stage,'unknown')
    assert.equal(actions(bad).acceptReady,false)
  }
})

test('测试退款金额保持两位字符串，大额不转浮点，坏日期不会令页面崩溃',()=>{
  const v=refundFactsView(detail('SUCCEEDED'))
  assert.equal(v.amountCopy,'全额 ¥12.34');assert.match(v.testCopy,/未真实退回资金/)
  assert.match(v.refundedTime,/北京时间/)
  assert.equal(refundFactsView({...detail(),refund:{...record,amount:'99999999.99'}}).amountCopy,'全额 ¥99999999.99')
  for(const amount of [12.34,'12.3','NaN',null])assert.equal(refundFactsView({refund:{amount}}).amountCopy,'全额 ¥—')
  assert.equal(refundFactsView({refund:{owner_consented_at:'bad',refunded_at:'bad'}}).refundedTime,'未提供')
  assert.equal(refundFactsView({...detail(),refund:{...record,test_mode:false}}).testCopy,'')
})

test('车主可申请撤回，店长可决定与核对；店员技师及未知角色无写动作',()=>{
  assert.equal(actions(empty(),'owner',{reason:'取消'}).requestReady,true)
  assert.equal(actions(detail(),'owner').withdrawReady,true)
  assert.equal(actions(detail(),'owner',{note:'拒绝'}).rejectReady,false)
  assert.equal(actions(detail()).acceptReady,true)
  assert.equal(actions(detail('PENDING')).reconcileReady,true)
  assert.equal(actions(detail('PENDING'),'owner').reconcileReady,false)
  for(const role of ['staff','technician','',null]){
    const v=actions(detail(),role,{note:'拒绝'})
    for(const key of ['requestReady','withdrawReady','acceptReady','rejectReady','reconcileReady'])assert.equal(v[key],false)
  }
})

test('订单变化后不能接受旧申请，仍可拒绝及由车主撤回',()=>{
  const d={...detail(),order_status:'IN_SERVICE'}
  assert.equal(refundStageView(d).changed,true)
  assert.match(refundStageView(d).copy,/撤回后重新申请/)
  assert.equal(actions(d,'merchant',{note:'状态已变化'}).acceptReady,false)
  assert.equal(actions(d,'merchant',{note:'状态已变化'}).rejectReady,true)
  assert.equal(actions(d,'owner').withdrawReady,true)
  assert.equal(refundActionBody('ACCEPT',d,'merchant',{},read,{}),null)
})

test('原因和说明明确256字边界，拒绝不能提交空白原因',()=>{
  for(const reason of ['', '   ', 'x'.repeat(257)])assert.equal(actions(empty(),'owner',{reason}).requestReady,false)
  assert.equal(actions(empty(),'owner',{reason:'x'.repeat(256)}).requestReady,true)
  assert.equal(actions(detail(),'merchant',{note:'   '}).acceptReady,true)
  assert.equal(actions(detail(),'merchant',{note:'   '}).rejectReady,false)
  assert.equal(actions(detail(),'merchant',{note:'x'.repeat(256)}).rejectReady,true)
  assert.equal(actions(detail(),'merchant',{note:'x'.repeat(257)}).acceptReady,false)
  assert.match(actions(detail()).decisionHint,/拒绝前须填写/)
})

test('读失败、刷新中、未决写入、弹窗与核对均阻止新动作',()=>{
  for(const input of [{loaded:false},{...read,busy:true},{...read,message:'失败'}])assert.equal(refundActionsView(detail(),'merchant',{},input).acceptReady,false)
  for(const write of [{busy:true},{retry:true}])assert.equal(actions(detail(),'merchant',{},write).acceptReady,false)
  assert.match(actions(detail(),'merchant',{}, {retry:true}).lockCopy,/重试原请求/)
  assert.equal(actions(detail(),'merchant',{}, {},{confirming:true}).acceptReady,false)
  assert.equal(actions(detail('FAILED'),'merchant',{}, {},{query:{busy:true}}).reconcileReady,false)
})

test('动作正文只冻结当前版本和填写内容，未知动作不开放',()=>{
  assert.deepEqual(refundActionBody('REJECT',detail(),'merchant',{note:' 原因 '},read,{}),{action:'REJECT',order_id:3,revision:1,reason:'',note:'原因'})
  assert.equal(refundActionBody('UNKNOWN',detail(),'merchant',{},read,{}),null)
  assert.match(refundConfirmationView('ACCEPT').content,/不可撤回/)
  assert.match(refundConfirmationView('REQUEST').content,/订单仍按原流程/)
  assert.equal(refundConfirmationView('WAT'),null)
})

test('错误区分重新登录、原请求重试与最近提交回执，不用保存宣称到账',()=>{
  assert.equal(refundFailureView({}, {retry:true,message:'未确定',failureKind:'network'}).retryOriginal,true)
  assert.equal(refundFailureView({}, {},{message:'无权',failureKind:'forbidden'}).canRelogin,true)
  assert.equal(refundFailureView({}, {message:'操作已保存'}).writeMessage,'')
  assert.equal(refundWriteResultView({}).exists,false)
  assert.match(refundWriteResultView({saved:detail('PENDING')}).copy,/尚未确认到账/)
})

function modalFixture(){
  const state={busy:false},sent=[],messages=[]
  let context={actor:'owner-token',role:'owner',orderId:3,detail:empty(),form:{reason:'原原因'},body:{action:'REQUEST',order_id:3,reason:'原原因'}},finish
  const flow=createRefundConfirmationFlow({state,capture:()=>context,confirm:()=>new Promise(r=>{finish=r}),dispatch:async body=>sent.push(body),onChanged:m=>messages.push(m)})
  return {flow,state,sent,messages,get context(){return context},set context(v){context=v},finish:ok=>finish(ok)}
}

test('确认只提交冻结正文，重复点击与取消不会产生新请求',async()=>{
  const f=modalFixture(),first=f.flow.run('REQUEST')
  assert.equal(f.state.busy,true);assert.equal(await f.flow.run('REQUEST'),false)
  f.finish(true);assert.equal(await first,true);assert.deepEqual(f.sent,[{action:'REQUEST',order_id:3,reason:'原原因'}]);assert.equal(f.state.busy,false)
  const second=f.flow.run('REQUEST');f.finish(false);assert.equal(await second,false);assert.equal(f.sent.length,1)
})

test('弹窗期间账号、角色、路由、退款版本、状态或正文变化均不提交',async()=>{
  const changes=[f=>{f.context.actor='other'},f=>{f.context.role='merchant'},f=>{f.context.orderId=4},f=>{f.context.detail.refund={revision:2}},f=>{f.context.detail.order_status='CLOSED'},f=>{f.context.form.reason='新原因'},f=>{f.context=null}]
  for(const change of changes){
    const f=modalFixture(),pending=f.flow.run('REQUEST');change(f);f.finish(true)
    assert.equal(await pending,false);assert.equal(f.sent.length,0);assert.equal(f.messages.length,1)
  }
})

test('页面隐藏后即使账号与详情相同，旧弹窗也不能在返回页面后提交',async()=>{
  const f=modalFixture(),old=f.flow.run('REQUEST');f.flow.cancel()
  const finishOld=f.finish
  // cancel即使上下文原样恢复也改变epoch；旧确认不能写入。
  finishOld(true);assert.equal(await old,false);assert.equal(f.sent.length,0)
  const next=f.flow.run('REQUEST');f.finish(true);assert.equal(await next,true);assert.equal(f.sent.length,1)
})

test('旧弹窗回调不能清除新弹窗的忙碌状态',async()=>{
  const state={busy:false},callbacks=[],sent=[]
  const flow=createRefundConfirmationFlow({state,capture:()=>({body:{action:'REQUEST'}}),confirm:()=>new Promise(r=>callbacks.push(r)),dispatch:async b=>sent.push(b)})
  const old=flow.run('REQUEST');flow.cancel();const current=flow.run('REQUEST')
  callbacks[0](true);await old;assert.equal(state.busy,true);assert.equal(sent.length,0)
  callbacks[1](true);await current;assert.equal(state.busy,false);assert.equal(sent.length,1)
})

test('确认失败不提交且释放锁，当前无资格时不打开弹窗',async()=>{
  let opens=0;const state={busy:false},messages=[]
  const flow=createRefundConfirmationFlow({state,capture:()=>null,confirm:async()=>{opens++;return true},dispatch:()=>assert.fail('不应提交')})
  assert.equal(await flow.run('REQUEST'),false);assert.equal(opens,0)
  const broken=createRefundConfirmationFlow({state,capture:()=>({body:{}}),confirm:async()=>{throw new Error('native private error')},dispatch:()=>assert.fail('不应提交'),onChanged:m=>messages.push(m)})
  assert.equal(await broken.run('ACCEPT'),false);assert.equal(state.busy,false);assert.equal(messages.length,1);assert.ok(!messages[0].includes('private'))
})

test('结果未知继续用原正文和原键，当前表单变化不触发第二个退款操作',async()=>{
  const write=initialReservationWriteState(),sent=[];let body,keys=0,reason='原原因'
  const save=createReservationWriteFlow({state:write,token:()=> 'owner',body:()=>body,newKey:()=>String(++keys),request:async(t,b,key)=>{sent.push({b,key});throw new ReservationError('network','未确定')}})
  const state={busy:false},flow=createRefundConfirmationFlow({state,
    capture:()=>write.retry?null:{actor:'owner',body:{action:'REQUEST',reason}},confirm:async()=>true,
    dispatch:async b=>{body=b;await save.save()}})
  await flow.run('REQUEST');reason='新原因';assert.equal(await flow.run('REQUEST'),false)
  await save.retry();assert.equal(keys,1);assert.equal(sent.length,2);assert.deepEqual(sent[0],sent[1]);assert.equal(sent[1].b.reason,'原原因')
})

test('共用页保留生命周期撤销和原请求重试，使用展示映射而非模板决定退款状态',()=>{
  const page=readFileSync(new URL('../src/pages/order/refund.vue',import.meta.url),'utf8')
  for(const marker of ['refund-request-form','refund-decision-form','refund-original-retry','refund-test-warning','refund-query-result'])assert.ok(page.includes(marker))
  assert.match(page,/onHide\([\s\S]*?confirmFlow\.cancel\(\)/)
  assert.match(page,/@tap="save.retry"/)
  assert.match(page,/state\.value=v;state\.loaded=true;flow\.load\(\)/)
  assert.ok(!page.includes("refund.status==='REQUESTED'"))
})
