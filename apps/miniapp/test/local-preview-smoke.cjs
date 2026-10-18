// Run only against the separate .cache/local-preview Devtools project.
const assert=require('node:assert/strict')
const fs=require('node:fs')
const path=require('node:path')
const {connectDevtools,waitForPage}=require('./helpers/devtools-rpc.cjs')
const {waitElement,tapElement}=require('./helpers/native-elements.cjs')
const root=path.resolve(__dirname,'../../..'),folder=path.join(root,'test-results/local-preview')
async function main(){
 fs.mkdirSync(folder,{recursive:true})
 const client=await connectDevtools({timeoutMs:15000}),rpc=client.rpc
 const checks=[]
 try{
  let status
  const deadline=Date.now()+20000
  do{try{status=await rpc('App.callWxMethod',{method:'__autocareLocalPreviewStatus',args:[{}]})}catch{}if(status?.result?.mode==='READ_ONLY_SYNTHETIC')break;await new Promise(r=>setTimeout(r,250))}while(Date.now()<deadline)
  assert.equal(status.result?.mode,'READ_ONLY_SYNTHETIC','Refusing to run against an ordinary app')
  async function open(url){await rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/'+url}]});const p=await waitForPage(rpc,url.split('?')[0],{timeoutMs:15000});await new Promise(r=>setTimeout(r,300));return p}
  async function screenshot(name){const image=await rpc('App.captureScreenshot');assert.equal(typeof image.data,'string');fs.writeFileSync(path.join(folder,name+'.png'),Buffer.from(image.data,'base64'))}
  let page=await open('pages/index/index')
  const owner=await waitElement(rpc,page,'.role-card',n=>n.text.includes('车主端'))
  await tapElement(rpc,page,owner)
  await waitForPage(rpc,'pages/home/index',{timeoutMs:15000})
  await new Promise(r=>setTimeout(r,700));await screenshot('owner-home');checks.push('owner card opens home without login')
  for(const route of ['pages/service/index','pages/ai/index','pages/archive/index','pages/mine/index']){await open(route);checks.push(route)}
  for(const [route,selector,needle] of [
   ['pages/merchant/orders','text','DEMO-'],
   ['pages/merchant/order-detail?id=3','text','演示 · 轮胎养护'],
   ['pages/merchant/projects','text','演示 · 机油机滤保养'],
   ['pages/merchant/staff','text','演示技师'],
   ['pages/workspace/tasks?role=merchant','text','演示 ·'],
   ['pages/technician/orders','text','DEMO-20261018-003'],
   ['pages/technician/order-detail?id=3','text','DEMO-20261018-003'],
   ['pages/technician/work?id=3','text','演示 · 轮胎养护'],
  ]){
   page=await open(route)
   // Merchant list displays project names; order numbers are visible in details.
   const expected=route==='pages/merchant/orders'?'演示 · 机油机滤保养':needle
   await waitElement(rpc,page,selector,n=>n.text.includes(expected))
   checks.push(route)
   if(route==='pages/merchant/orders')await screenshot('merchant-orders')
   if(route==='pages/technician/orders')await screenshot('technician-orders')
   if(route==='pages/technician/work?id=3')await screenshot('technician-work')
  }
  const diagnostic=(await rpc('App.callWxMethod',{method:'__autocareLocalPreviewStatus',args:[{}]})).result
  const failures=diagnostic.calls.filter(n=>n.statusCode!==200)
  assert.deepEqual(failures,[],'A core preview page requested an unsupported sample')
  page=await open('pages/index/index')
  await waitElement(rpc,page,'.role-card',n=>n.text.includes('商家端'))
  await screenshot('role-entry')
  fs.writeFileSync(path.join(folder,'smoke.json'),JSON.stringify({mode:diagnostic.mode,checks,calls:diagnostic.calls},null,2)+'\n')
  console.log(JSON.stringify({passed:checks.length,requests:diagnostic.calls.length,screenshots:folder,finalPage:'pages/index/index'}))
 }finally{client.close()}
}
main().catch(e=>{console.error(e.stack);process.exitCode=1})
