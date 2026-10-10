// Local-only native pages and real uploads. System picker/modal results are explicit test assistance.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const f=require('./local_service_work_fixtures.cjs')
const {prepareNativeFixture,nativeFixtureSessions}=require('./native-dispatch-e2e.cjs')
const {connectDevtools,waitForPage}=require('../apps/miniapp/test/helpers/devtools-rpc.cjs')
const {elements,waitElement,tapButton}=require('../apps/miniapp/test/helpers/native-elements.cjs')
const {createWxMockScope}=require('../apps/miniapp/test/helpers/devtools-mocks.cjs')
const report={completed:false,startedAt:new Date().toISOString(),checks:[],scope:'synthetic identities, picker/modal results assisted; real native upload, canvas export and HTTP/database; not physical camera, human signature or real payment',nativePickerClickAccepted:false,nativeModalClickAccepted:false}
const pass=name=>{report.checks.push({name,passed:true});console.log('PASS '+name)}
const PNG='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aSkcAAAAASUVORK5CYII='
let fixture,client,localFile,sourceName,mockScope,pickers=[]
async function mock(method,result){await mockScope.mock(method,result)}
async function restore(method){await mockScope.restore(method)}
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms))
async function page(url){await client.rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/'+url}]});return waitForPage(client.rpc,url.split('?')[0])}
async function actor(name){await page('pages/index/index');const {role,session}=fixture.actors[name];await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role,token:session.token}]})}
async function decide(p,label,confirm=true){
  report.step=label
  await mock('showModal',{confirm,cancel:!confirm,errMsg:'showModal:ok'})
  try{await tapButton(client.rpc,p,label);await delay(200)}finally{await restore('showModal')}
}
async function choose(p,label){
  report.step=label
  const completeCount=async()=> (await elements(client.rpc,p,'text')).filter(e=>/^照片 \d+ · 上传完成$/.test(e.text)).length
  const before=await completeCount()
  for(const method of pickers)await mock(method,method==='chooseMedia'?{tempFiles:[{tempFilePath:localFile.path,size:localFile.size,fileType:'image'}],type:'image',errMsg:'chooseMedia:ok'}:{tempFiles:[{path:localFile.path,size:localFile.size}],tempFilePaths:[localFile.path],errMsg:'chooseImage:ok'})
  try{
    await tapButton(client.rpc,p,label)
    const deadline=Date.now()+85000
    while(await completeCount()!==before+1){assert.ok(Date.now()<deadline,'Native image upload did not finish');await delay(200)}
  }
  finally{for(const method of pickers)await restore(method)}
}
async function change(p,index,value){const groups=await elements(client.rpc,p,'checkbox-group');assert.ok(groups[index]);await client.rpc('Element.triggerEvent',{pageId:p.pageId,elementId:groups[index].elementId,type:'change',detail:{value}})}
async function input(p,selector,index,value){const fields=await elements(client.rpc,p,selector);assert.ok(fields[index]);await client.rpc('Element.callFunction',{pageId:p.pageId,elementId:fields[index].elementId,functionName:selector+'.input',args:[value]})}
async function main(){
  fixture=await prepareNativeFixture();report.checks.push(...fixture.checks)
  const {order,actors}=fixture
  for(const [target,token,body]of [
    ['/api/check/pickup/confirm',actors.owner.session.token,{order_id:order,decision:'CONFIRM'}],
    [`/api/merchant/orders/${order}/assign`,actors.shop.session.token,{technician_id:fixture.ids.tech}],
    [`/api/tech/orders/${order}/accept`,actors.tech.session.token,{}],
  ]){const r=await f.api('POST',target,token,{key:f.randomUUID(),body});assert.equal(r.status,200)}
  pass('real HTTP owner confirmation, dispatch and acceptance preparation')
  client=await connectDevtools({endpoint:'ws://127.0.0.1:9422',timeoutMs:20000})
  mockScope=createWxMockScope(client.rpc)
  pickers=(await client.rpc('App.callFunction',{functionDeclaration:`function(){return ['chooseMedia','chooseImage'].filter(name=>typeof wx[name]==='function')}`,args:[]})).result
  assert.ok(pickers.length>0);report.pickerApis=pickers
  sourceName='native-r0-work-'+f.randomUUID()+'.png'
  const {result}=await client.rpc('App.callFunction',{functionDeclaration:`function(name,png){const p=wx.env.USER_DATA_PATH+'/'+name;const file=wx.getFileSystemManager();file.writeFileSync(p,png,'base64');const stat=file.statSync(p);return {path:p,size:stat.size}}`,args:[sourceName,PNG]})
  localFile=result;assert.ok(localFile.size>0);pass('real WeChat filesystem synthetic PNG prepared')
  await actor('tech');let p=await page(`pages/technician/work?id=${order}`)
  await waitElement(client.rpc,p,'text',e=>e.text==='请先由商家完成施工防护拍照。无防护照片不能报工。')
  assert.equal((await elements(client.rpc,p,'button')).filter(e=>e.text?.trim()==='确认提交完整报工').length,0);pass('native technician cannot report without protection')
  await actor('shop');p=await page(`pages/merchant/protection?id=${order}`)
  await waitElement(client.rpc,p,'checkbox-group');await change(p,0,['SEAT_COVER','STEERING_COVER'])
  await choose(p,'拍摄防护照片');pass('native merchant uploads actual PNG through scanner')
  await decide(p,'确认提交施工防护',false);assert.equal(f.sql(`SELECT COUNT(*) FROM repair_protection WHERE order_id=${order}`),'0');pass('cancel callback does not save protection')
  await decide(p,'确认提交施工防护');await waitElement(client.rpc,p,'text',e=>e.text==='已提交施工防护')
  const protection=Number(f.sql(`SELECT file_id FROM service_evidence_file WHERE order_id=${order} AND kind='PROTECTION'`))
  assert.equal(f.sql(`SELECT COUNT(*) FROM file_object WHERE id=${protection} AND owner_type='staff_account' AND owner_id=${fixture.ids.shop} AND scan_status='CLEAN' AND content_type='image/png'`),'1');pass('native protection committed with clean merchant-owned evidence')
  await actor('tech');p=await page(`pages/technician/work?id=${order}`)
  await waitElement(client.rpc,p,'text',e=>e.text.startsWith('施工防护已提交'))
  await choose(p,'拍摄施工过程照');await choose(p,'拍摄完工照');pass('native technician uploads process and finish evidence')
  await change(p,0,['none']);await change(p,1,['none'])
  await input(p,'textarea',0,'合成原生验收：保养完成并复检');await input(p,'textarea',1,'合成原生验收：本次无故障件');await input(p,'input',0,'30')
  await decide(p,'确认提交完整报工',false);assert.equal(f.sql(`SELECT COUNT(*) FROM technician_report WHERE order_id=${order}`),'0');pass('cancel callback does not save report')
  await decide(p,'确认提交完整报工');await waitElement(client.rpc,p,'text',e=>e.text.startsWith('已提交报工'))
  assert.equal(f.sql(`SELECT status FROM technician_report WHERE order_id=${order}`),'0');pass('native complete report committed')
  await waitElement(client.rpc,p,'canvas')
  await tapButton(client.rpc,p,'生成并上传签名图片')
  await waitElement(client.rpc,p,'text',e=>e.text==='请在签名板内手写签名，空白或单点不能提交');pass('native empty canvas rejected')
  const canvas=await waitElement(client.rpc,p,'canvas'),target={pageId:p.pageId,elementId:canvas.elementId}
  const offset=await client.rpc('Element.getOffset',target)
  await client.rpc('App.callWxMethod',{method:'pageScrollTo',args:[{scrollTop:Math.max(0,offset.top-120),duration:0}]});await delay(200)
  const current=await client.rpc('Element.getOffset',target)
  const touch=(x,y)=>({identifier:0,clientX:current.left+x,clientY:120+y,pageX:current.left+x,pageY:current.top+y})
  await client.rpc('Element.touchstart',{...target,touches:[touch(20,30)]})
  for(const [x,y]of [[50,80],[90,25],[130,90],[170,40],[210,100]])await client.rpc('Element.touchmove',{...target,touches:[touch(x,y)]})
  await client.rpc('Element.touchend',{...target,touches:[],changeTouches:[touch(210,100)]})
  await tapButton(client.rpc,p,'生成并上传签名图片')
  await waitElement(client.rpc,p,'button',e=>e.text?.trim()==='核对已上传签名');pass('real native canvas stroke exports and uploads PNG')
  await decide(p,'确认本人质检签字，送待核销',false);assert.equal(f.sql(`SELECT status FROM technician_report WHERE order_id=${order}`),'0');pass('cancel callback does not sign report')
  await decide(p,'确认本人质检签字，送待核销');await waitElement(client.rpc,p,'text',e=>e.text.startsWith('已完成质检签字'))
  assert.equal(f.sql(`SELECT status FROM \`order\` WHERE id=${order}`),'PENDING_VERIFY')
  const signature=Number(f.sql(`SELECT file_id FROM service_evidence_file WHERE order_id=${order} AND kind='SIGNATURE'`))
  assert.equal(f.sql(`SELECT COUNT(*) FROM file_object WHERE id=${signature} AND owner_id=${fixture.ids.tech} AND owner_type='staff_account' AND scan_status='CLEAN' AND content_type='image/png'`),'1');pass('native signed report enters pending verification with clean PNG')
  assert.ok(Number(f.sql(`SELECT size_bytes FROM file_object WHERE id=${signature}`))>localFile.size);pass('exported signature PNG differs from one-pixel picker fixture')
  const access=await f.api('GET',`/api/tech/orders/${order}/work/files/${signature}/access`,actors.tech.session.token)
  assert.equal(access.status,200);pass('assigned technician receives private signature access')
  for(const [name,role]of [['otherShop','merchant'],['otherTech','tech']]){
    const denied=await f.api('GET',`/api/${role}/orders/${order}/work/files/${signature}/access`,actors[name].session.token)
    assert.equal(denied.status,404);pass(`${name} cannot access private service signature`)
  }
  await actor('shop');p=await page(`pages/merchant/protection?id=${order}`)
  await waitElement(client.rpc,p,'text',e=>e.text.startsWith('已质检签字'));pass('native merchant reads locked signed service report')
  report.completed=true
}
main().catch(error=>{report.failure=error.message;console.error(error.message);process.exitCode=1}).finally(async()=>{
  let cleaned=true
  if(client){
    for(const method of mockScope?.methods||[])try{await restore(method)}catch{cleaned=false}
    if(sourceName)try{await client.rpc('App.callFunction',{functionDeclaration:`function(name,png){if(!/^native-r0-work-[0-9a-f-]{36}\\.png$/.test(name))throw new Error('Unexpected test file');const f=wx.getFileSystemManager(),root=wx.env.USER_DATA_PATH;if(!f.readdirSync(root).includes(name))return false;const p=root+'/'+name;if(f.readFileSync(p,'base64')!==png)throw new Error('Test file content changed');f.unlinkSync(p);return true}`,args:[sourceName,PNG]})}catch{cleaned=false}
    try{await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'clear'}]})}catch{cleaned=false}finally{client.close()}
  }
  try{for(const {session,spec}of nativeFixtureSessions)f.sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id='${session.jti}' AND subject_type='${spec.type}' AND subject_id=${spec.subject}`)
    if(nativeFixtureSessions.length)assert.equal(f.sql(`SELECT COUNT(*) FROM auth_session WHERE id IN (${nativeFixtureSessions.map(({session})=>"'"+session.jti+"'").join(',')}) AND revoked_at IS NULL`),'0')
  }catch{cleaned=false;console.error('Synthetic session cleanup failed')}
  if(!cleaned)process.exitCode=1
  report.cleaned=cleaned;report.finishedAt=new Date().toISOString();const output=path.resolve('test-results/wechat-native-work-files.json');fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(report,null,2))
  console.log(`Native work/files ${report.completed&&cleaned?'COMPLETE':'INCOMPLETE'}: ${report.checks.length} passed`)
})
