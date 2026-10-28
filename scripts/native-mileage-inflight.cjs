// Acceptance-only delay of actual backend response bytes. No recognition substitute.
const assert=require('node:assert/strict'),crypto=require('node:crypto')
const {waitForPage}=require('../apps/miniapp/test/helpers/devtools-rpc.cjs')
const {elements,waitElement,tapElement}=require('../apps/miniapp/test/helpers/native-elements.cjs')
const delay=ms=>new Promise(r=>setTimeout(r,ms))
class ResponseGate{
 constructor(){this.active=null;this.history=[]}
 arm(name){assert.equal(this.active,null);this.active={name,received:false,release:null}}
 async accept({mode,status,bytes}){
  if(mode!=='recognition'||!this.active)return
  const active=this.active;assert.equal(status,200);assert.equal(JSON.parse(bytes).data.recognition.fields[0].value,'123456')
  active.received=true;active.sha256=crypto.createHash('sha256').update(bytes).digest('hex')
  await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Response gate deadline exceeded')),12000);active.release=()=>{clearTimeout(timer);resolve()}})
  assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'),active.sha256)
  this.history.push({name:active.name,status,real_response_held:true,response_bytes_unchanged:true})
  this.active=null
 }
 async received(){const end=Date.now()+10000;while(!this.active?.received){assert.ok(Date.now()<end,'Real response missing');await delay(50)}}
 release(){this.active?.release?.()}
}
async function runInFlight({rpc,mocks,proxy,gate,ready,local,checkpoint}){
 const rows=[]
 for(const mode of ['cancel','hide','leave','actor','context']){
  await rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'owner',token:ready.token,vehicle:992}]})
  await rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/pages/archive/record-add?vehicle_id=992'}]})
  let p=await waitForPage(rpc,'pages/archive/record-add')
  const element=selector=>waitElement(rpc,p,selector),tap=async selector=>tapElement(rpc,p,await element(selector))
  const change=async(selector,value)=>{const e=await element(selector);await rpc('Element.triggerEvent',{pageId:p.pageId,elementId:e.elementId,type:'change',detail:{value}})}
  const size=Buffer.from(ready.image,'base64').length
  await mocks.mock('chooseMedia',{tempFiles:[{tempFilePath:local,size,fileType:'image'}],type:'image',errMsg:'chooseMedia:ok'})
  await mocks.mock('chooseImage',{tempFilePaths:[local],tempFiles:[{path:local,size}],errMsg:'chooseImage:ok'})
  try{await tapElement(rpc,p,await waitElement(rpc,p,'button',e=>e.text?.trim()==='选择并上传图片'));await waitElement(rpc,p,'text',e=>e.text?.includes('图片已上传，保存档案后才会归档'))}
  finally{await mocks.restore('chooseMedia');await mocks.restore('chooseImage')}
  await change('picker[data-testid="mileage-source"]','0');await change('switch[data-testid="mileage-consent"]',true)
  gate.arm(mode);await tap('button[data-testid="mileage-recognize"]');await gate.received()
  await waitElement(rpc,p,'button[data-testid="mileage-recognize"]',e=>e.text?.includes('正在识别'))
  if(mode==='cancel')await tap('button[data-testid="candidate-cancel"]')
  if(mode==='hide')await rpc('App.callWxMethod',{method:'navigateTo',args:[{url:'/pages/archive/record-add?vehicle_id=991'}]})
  if(mode==='leave')await rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/pages/archive/record-add?vehicle_id=992'}]})
  if(mode==='actor')await rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'owner',token:ready.other_token,vehicle:992}]})
  // Forced event verifies the context fence; picker is disabled for ordinary
  // interaction while recognizing. This is explicitly assisted, not a phone gesture.
  if(mode==='context'){
   const picker=(await elements(rpc,p,'picker')).find(e=>e.text?.trim()==='保养');assert.ok(picker)
   await rpc('Element.triggerEvent',{pageId:p.pageId,elementId:picker.elementId,type:'change',detail:{value:'1'}})
  }
  gate.release();const until=Date.now()+5000;while(proxy.isBusy()){assert.ok(Date.now()<until,'Proxy did not release');await delay(50)}
  if(mode==='hide')await rpc('App.callWxMethod',{method:'navigateBack',args:[{}]})
  p=await waitForPage(rpc,'pages/archive/record-add');await delay(250)
  assert.equal((await elements(rpc,p,'button[data-testid="candidate-review"]')).length,0,'Late candidate appeared after '+mode)
  assert.equal((await elements(rpc,p,'button[data-testid="candidate-confirm"]')).length,0)
  const input=await waitElement(rpc,p,'input[data-testid="archive-mileage"]')
  const value=await rpc('Element.getProperties',{pageId:p.pageId,elementId:input.elementId,names:['value']});assert.equal(value.properties[0],'','Late mileage appeared')
  assert.equal(proxy.counts.archive,0);await checkpoint('inflight-'+mode)
  rows.push({name:mode,passed:true,no_late_candidate:true,no_form_write:true,no_archive_request:true})
 }
 assert.equal(gate.history.length,5)
 return {passed:true,cases:rows,transport:gate.history,context_event_assisted:true,hide_via_navigation:true}
}
module.exports={ResponseGate,runInFlight}
