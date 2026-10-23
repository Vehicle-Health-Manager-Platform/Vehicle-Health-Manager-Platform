// Explicit opt-in: isolated Devtools build, synthetic in-memory sessions, real HTTP.
const fs=require('node:fs'),assert=require('node:assert/strict'),path=require('node:path')
const {connectDevtools,waitForPage}=require('../apps/miniapp/test/helpers/devtools-rpc.cjs')
const {elements,waitElement,tapButton}=require('../apps/miniapp/test/helpers/native-elements.cjs')
const report={executed:true,passed:false,checks:[],session_cleared:false,
  scope:'WeChat Devtools native page with synthetic sessions and real isolated HTTP; picker change events assisted by RPC; no phone, official login, payment or channel statement acceptance'}
const pass=name=>report.checks.push({name,passed:true})
let client
async function main(){
  assert.equal(process.env.AUTOCARE_FINANCE_NATIVE_QA,'1','Explicit isolated acceptance required')
  const sessions=JSON.parse(fs.readFileSync(process.argv[2],'utf8'))
  const config=JSON.parse(fs.readFileSync(path.resolve(__dirname,'../.cache/r0-native/mp-weixin/project.config.json'),'utf8'))
  assert.equal(config.compileType,'miniprogram')
  client=await connectDevtools()
  const rpc=client.rpc
  const actor=async(canAnalytics=true,role='operator')=>rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'operator',token:sessions[role+'_token'],actorId:role==='operator'?1:2,canAnalytics}]})
  const open=async target=>{await rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/'+target}]});return waitForPage(rpc,target)}
  const finance=()=>open('pages/operator/finance')
  const change=async(p,index,value)=>{
    const pickers=await elements(rpc,p,'picker');assert.equal(pickers.length,2)
    await rpc('Element.triggerEvent',{pageId:p.pageId,elementId:pickers[index].elementId,type:'change',detail:{value}})
  }
  const result=async p=>{await tapButton(rpc,p,'查询所选资金记录');return waitElement(rpc,p,'view[data-testid="finance-results"]')}
  await actor();let p=await finance()
  await waitElement(rpc,p,'view[data-testid="finance-selection"]')
  assert.equal((await elements(rpc,p,'view[data-testid="finance-results"]')).length,0)
  pass('native entry displays date/channel selection without stale totals')
  await change(p,0,'2026-10-20');let totals=await result(p)
  assert.ok(totals.text.includes('0.03')&&totals.text.includes('0.05')&&totals.text.includes('-0.02'))
  await waitElement(rpc,p,'text',e=>e.text.includes('不代表真实扣款'))
  await waitElement(rpc,p,'view[data-testid="finance-channel-unknown"]')
  pass('native LOCAL_TEST totals match exact cents and retain test/unknown statement notices')
  await change(p,1,'1')
  await waitElement(rpc,p,'view[data-testid="finance-selection"]',e=>e.text.includes('正式微信渠道记录'))
  assert.equal((await elements(rpc,p,'view[data-testid="finance-results"]')).length,0)
  pass('native channel change clears prior totals before querying')
  totals=await result(p);assert.ok(totals.text.includes('12.34'))
  pass('native formal-channel synthetic records stay separate from test totals')
  await change(p,0,'2026-10-21');totals=await result(p)
  assert.ok(totals.text.includes('未知')&&totals.text.includes('不完整来源'))
  pass('native incomplete sources show unknown totals')
  await open('pages/operator/review');p=await finance()
  await waitElement(rpc,p,'view[data-testid="finance-selection"]')
  assert.equal((await elements(rpc,p,'view[data-testid="finance-results"]')).length,0)
  pass('native leave/reenter clears previous amounts')
  await actor(false,'review_operator');p=await finance()
  await waitElement(rpc,p,'view[data-testid="finance-forbidden"]')
  assert.equal((await elements(rpc,p,'view[data-testid="finance-selection"]')).length,0)
  pass('native review-only identity has no finance query form')
  await actor(true,'review_operator');p=await finance()
  await change(p,0,'2026-10-20');await tapButton(rpc,p,'查询所选资金记录')
  await waitElement(rpc,p,'view[data-testid="finance-error"]')
  assert.equal((await elements(rpc,p,'view[data-testid="finance-results"]')).length,0)
  pass('server denies real review-only session even with synthetic client analytics flag')
  report.passed=true
}
main().catch(()=>{report.error='Native finance assertion/connection failed; private session data omitted'}).finally(async()=>{
  if(client){try{await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'clear'}]});report.session_cleared=true}catch{report.passed=false}client.close()}
  fs.writeFileSync(process.argv[3],JSON.stringify(report,null,2)+'\n')
  if(!report.passed||!report.session_cleared)process.exitCode=1
})
