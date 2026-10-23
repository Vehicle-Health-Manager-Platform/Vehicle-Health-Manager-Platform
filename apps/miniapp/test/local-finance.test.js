import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {validFinanceDate,validLocalFinance,currentFinanceDate,createLocalFinanceApi,initialFinanceState,createLocalFinanceFlow,financeView} from '../src/services/local-finance.js'
import {operatorEntryGroups} from '../src/services/public-review-display.js'

const copy=v=>JSON.parse(JSON.stringify(v))
const total=(amount='0.00',success_count=0)=>({state:'KNOWN',success_count,anomaly_count:0,other_count:0,amount})
const value=()=>({date:'2026-10-23',channel:'LOCAL_TEST',test_mode:true,timezone:'Asia/Shanghai',currency:'CNY',source_version:'R9_LOCAL_FINANCE_V1',window_from:'2026-10-22T16:00:00Z',window_to:'2026-10-23T16:00:00Z',observed_at:'2026-10-24T04:00:00Z',payments:total('0.03',2),refunds:total('0.05',1),unclassified_count:0,local_net:{state:'KNOWN',amount:'-0.02'},channel_statement:{state:'UNAVAILABLE',net_amount:null,difference:null}})
const valid=v=>validLocalFinance(v,'2026-10-23','LOCAL_TEST')

test('finance date is strict and uses Beijing day independent of host timezone',()=>{
  for(const v of ['2026-02-30','2026-2-01','1999-12-31','2100-01-01',null])assert.equal(validFinanceDate(v),false)
  assert.equal(validFinanceDate('2024-02-29'),true)
  assert.equal(currentFinanceDate(Date.parse('2026-10-22T16:00:00Z')),'2026-10-23')
})
test('finance contract accepts signed exact net and current-day clipped interval',()=>{
  assert.equal(valid(value()),true)
  const v=value();v.observed_at='2026-10-23T04:00:00Z';v.window_to=v.observed_at;assert.equal(valid(v),true)
  v.payments=total();v.refunds=total();v.local_net.amount='0.00';assert.equal(valid(v),true)
})
test('finance refuses identity drift, fake balanced channel totals and contradictory unknown amounts',()=>{
  const changes=[v=>v.channel='WECHAT',v=>v.date='2026-10-22',v=>v.test_mode=false,v=>v.currency='USD',v=>v.source_version='old',v=>v.window_from='2026-10-23T00:00:00Z',v=>v.window_to=v.observed_at,v=>v.local_net.amount='-0.01',v=>v.channel_statement.difference='0.00',v=>v.channel_statement.net_amount='0.03',v=>v.payments.amount=0.03,v=>v.payments.amount='01.00',v=>v.payments.anomaly_count=1,v=>v.payments.success_count=0,v=>v.payments.other_count=-1,v=>v.unclassified_count=1,v=>v.payments.amount='1e4',v=>v.extra='private',v=>v.refunds.extra=true,v=>v.payments.success_count=10001,v=>v.observed_at='2026-10-22T15:59:59Z']
  for(const change of changes){const v=value();change(v);assert.equal(valid(v),false,change.toString())}
})
test('finance propagates anomalous source to unknown totals without hiding unaffected refund total',()=>{
  const v=value();Object.assign(v.payments,{state:'UNKNOWN',anomaly_count:1,amount:null});v.local_net={state:'UNKNOWN',amount:null};assert.equal(valid(v),true)
  v.unclassified_count=1;assert.equal(valid(v),false)
  Object.assign(v.refunds,{state:'UNKNOWN',amount:null});assert.equal(valid(v),true)
})
test('finance API binds selection, sends authenticated GET and rejects mismatched success',async()=>{
  const calls=[];let response=value()
  const api=createLocalFinanceApi({baseUrl:'https://example.test/',runtime:()=>({request:o=>{calls.push(o);o.success({statusCode:200,data:{code:0,data:response}})}})})
  assert.equal((await api.report('session','2026-10-23','LOCAL_TEST')).date,'2026-10-23')
  assert.equal(calls[0].url,'https://example.test/api/admin/reconciliation?date=2026-10-23&channel=LOCAL_TEST')
  assert.equal(calls[0].method,'GET');assert.equal(calls[0].header.Authorization,'Bearer session');assert.equal(calls[0].data,undefined)
  response=copy(response);response.channel='WECHAT';await assert.rejects(api.report('session','2026-10-23','LOCAL_TEST'),{kind:'protocol'})
  await assert.rejects(api.report('','2026-10-23','LOCAL_TEST'),{kind:'unauthorized'})
  await assert.rejects(api.report('session','2026-02-30','LOCAL_TEST'),{kind:'invalid'})
})
test('finance API maps denial, unavailable and network failure without retaining response',async()=>{
  for(const [status,kind] of [[400,'invalid'],[401,'unauthorized'],[403,'forbidden'],[409,'conflict'],[503,'unavailable']]){
    const api=createLocalFinanceApi({baseUrl:'https://example.test',runtime:()=>({request:o=>o.success({statusCode:status,data:{private:'not shown'}})})})
    await assert.rejects(api.report('t','2026-10-23','LOCAL_TEST'),{kind})
  }
  const api=createLocalFinanceApi({baseUrl:'https://example.test',runtime:()=>({request:o=>o.fail()})})
  await assert.rejects(api.report('t','2026-10-23','LOCAL_TEST'))
})
test('changed selection, hidden page and replaced identity suppress late finance responses',async()=>{
  for(const action of ['selection','hidden','identity','permission']){
    let resolve,token='first',allowed=true,selection={date:'2026-10-23',channel:'LOCAL_TEST'}
    const state=initialFinanceState(),flow=createLocalFinanceFlow({state,token:()=>token,allowed:()=>allowed,selection:()=>selection,api:{report:()=>new Promise(r=>resolve=r)}})
    const pending=flow.load();assert.equal(state.busy,true)
    if(action==='selection')selection={...selection,channel:'WECHAT'}
    if(action==='identity')token='second'
    if(action==='permission')allowed=false
    flow.reset();resolve(value());await pending;assert.equal(state.data,null);assert.equal(state.busy,false)
  }
})
test('finance failed refresh clears old totals and can retry the same selection',async()=>{
  let fail=false;const state=initialFinanceState(),flow=createLocalFinanceFlow({state,token:()=> 't',allowed:()=>true,selection:()=>({date:'2026-10-23',channel:'LOCAL_TEST'}),api:{report:async()=>{if(fail)throw Object.assign(new Error('失败'),{kind:'unavailable'});return value()}}})
  await flow.load();assert.ok(state.data);fail=true;await flow.load();assert.equal(state.data,null);assert.equal(state.kind,'unavailable');fail=false;await flow.load();assert.ok(state.data)
})
test('finance display names test funds, formats Beijing time, and never claims reconciliation',()=>{
  const view=financeView(value());assert.equal(view.net,'-0.02 元');assert.equal(view.from,'2026-10-23 00:00:00');assert.match(view.notice,/测试资金/)
  const v=value();v.local_net.amount=null;assert.equal(financeView(v).net,'未知')
  v.test_mode=false;assert.match(financeView(v).notice,/尚未与渠道账单核对/)
})
test('finance is a native registered operator page with permission-filtered entry',()=>{
  const pages=JSON.parse(readFileSync(new URL('../src/pages.json',import.meta.url),'utf8')).pages
  assert.equal(pages[0].path,'pages/index/index');assert.equal(pages.filter(p=>p.path==='pages/operator/finance').length,1)
  const entries=canAnalytics=>operatorEntryGroups({canAnalytics}).flatMap(v=>v.entries)
  assert.equal(entries(false).some(e=>e.key==='finance'),false);assert.equal(entries(true).find(e=>e.key==='finance').url,'/pages/operator/finance')
  const page=readFileSync(new URL('../src/pages/operator/finance.vue',import.meta.url),'utf8')
  assert.match(page,/<picker mode="date"/);assert.match(page,/onHide/);assert.match(page,/onUnload/);assert.match(page,/flush:'sync'/)
})
