// Opt-in local WeChat completion acceptance. Business requests and uploads stay real.
const fs=require('node:fs'),assert=require('node:assert/strict')
const f=require('./local_service_work_fixtures.cjs')
const {runNativeWorkFiles,cleanupNativeWorkFiles}=require('./native-work-files-e2e.cjs')
const {nativeFixtureSessions}=require('./native-dispatch-e2e.cjs')
const {elements,waitElement,tapButton}=require('../apps/miniapp/test/helpers/native-elements.cjs')
const {waitForPage}=require('../apps/miniapp/test/helpers/devtools-rpc.cjs')
const report={completed:false,startedAt:new Date().toISOString(),checks:[],nativePickerClickAccepted:false,nativeModalClickAccepted:false,scope:'synthetic sessions and assisted system results; real native business, upload, database and archive worker; no real deduction or device acceptance'}
const pass=(name,kind='native')=>{report.checks.push({name,kind,passed:true});console.log('PASS '+name)}
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms))
let work
async function query(method,target,token,status){const r=await f.api(method,target,token);assert.equal(r.status,status);return f.dataOf(r)}
async function main(){
  f.verifyIsolation('a7-publication')
  assert.equal(f.envVar(f.BACKEND,'SERVICE_ARCHIVE_ENABLED'),'true','Isolated archive consumer must already be enabled')
  work=await runNativeWorkFiles()
  report.preparation={stage:'R0.1b2b',completed:work.report.completed,passed:work.report.checks.length,scope:work.report.scope}
  const {fixture,client,page,actor,decide,input,localFile,mockScope}=work,{order,actors,ids}=fixture
  const other=9301291
  assert.equal(f.sql(`SELECT COUNT(*) FROM user WHERE id=${other} AND openid<>'native-r0-completion-other'`),'0','Other-owner identity collision')
  f.sql(`INSERT IGNORE INTO user(id,openid,status) VALUES(${other},'native-r0-completion-other',1)`)
  assert.equal(f.sql(`SELECT COUNT(*) FROM user WHERE id=${other} AND status=1 AND is_deleted=0`),'1')
  const spec={type:'user',role:'OWNER',subject:other},session=f.mint(f.envVar(f.BACKEND,'JWT_SECRET'),f.envVar(f.BACKEND,'WECHAT_APP_ID'),spec)
  nativeFixtureSessions.push({session,spec});actors.otherOwner={role:'owner',session}
  await actor('owner');let p=await page(`pages/order/review?id=${order}`)
  await waitElement(client.rpc,p,'text',e=>e.text==='订单尚未完成核销，暂不可评价')
  assert.equal((await elements(client.rpc,p,'button')).filter(e=>e.text?.trim()==='提交本人评价').length,0);pass('owner native review blocked before redemption')
  p=await page(`pages/order/detail?id=${order}`)
  const codeText=await waitElement(client.rpc,p,'text',e=>/^核销码 \d{6}$/.test(e.text)),code=codeText.text.slice(-6)
  assert.equal(code,f.sql(`SELECT verify_code FROM \`order\` WHERE id=${order}`));pass('owner native detail supplies actual six-digit redemption code')
  await waitElement(client.rpc,p,'text',e=>e.text==='测试支付，未真实扣款，不可作为收款凭据');pass('owner native detail preserves test payment warning')
  await actor('otherShop');p=await page(`pages/merchant/redeem?id=${order}`)
  await waitElement(client.rpc,p,'text',e=>e.text.includes('订单不存在')||e.text.includes('非本店'))
  assert.equal((await elements(client.rpc,p,'input')).length,0);pass('foreign shop native redemption page exposes no code form')
  await actor('shop');p=await page(`pages/merchant/redeem?id=${order}`)
  await waitElement(client.rpc,p,'input')
  await waitElement(client.rpc,p,'text',e=>e.text==='测试核销，未真实扣款，不可作为收款凭据');pass('merchant native redemption keeps test warning')
  await input(p,'input',0,code==='000000'?'000001':'000000');await decide(p,'验码并核销')
  await waitElement(client.rpc,p,'text',e=>e.text==='核销码无效，请向车主核对')
  assert.equal(f.sql(`SELECT COUNT(*) FROM order_redemption WHERE order_id=${order}`),'0');pass('native incorrect code rejected without redemption')
  await input(p,'input',0,code);await decide(p,'验码并核销',false)
  assert.equal(f.sql(`SELECT COUNT(*) FROM order_redemption WHERE order_id=${order}`),'0');pass('cancel callback leaves redemption unwritten')
  await decide(p,'验码并核销')
  await waitElement(client.rpc,p,'text',e=>e.text==='测试核销已完成')
  assert.equal(f.sql(`SELECT status FROM \`order\` WHERE id=${order}`),'COMPLETED')
  assert.equal(f.sql(`SELECT COUNT(*) FROM order_redemption WHERE order_id=${order} AND merchant_id=${ids.shop} AND test_mode=1`),'1');pass('native redemption completes order with unique trusted test receipt')
  p=await page(`pages/merchant/redeem?id=${order}`)
  await waitElement(client.rpc,p,'text',e=>e.text==='测试核销已完成')
  assert.equal((await elements(client.rpc,p,'input')).length,0);pass('native redemption reload is read-only')
  await actor('owner');p=await page(`pages/order/detail?id=${order}`)
  await tapButton(client.rpc,p,'本人订单评价 / 查看记录');p=await waitForPage(client.rpc,'pages/order/review')
  await waitElement(client.rpc,p,'text',e=>e.text==='本次服务总体评分（必选）');pass('owner navigates from completed native order to eligible review')
  await waitElement(client.rpc,p,'text',e=>e.text==='测试订单评价：未真实扣款，不公开、不计入商家评分。');pass('native review preserves private and test-only warning')
  await tapButton(client.rpc,p,'5 分');await input(p,'textarea',0,'合成原生闭环验收：沟通清楚，保养后复检正常。')
  const pickers=work.report.pickerApis
  for(const method of pickers)await mockScope.mock(method,method==='chooseMedia'?{tempFiles:[{tempFilePath:localFile.path,size:localFile.size,fileType:'image'}],type:'image',errMsg:'chooseMedia:ok'}:{tempFiles:[{path:localFile.path,size:localFile.size}],tempFilePaths:[localFile.path],errMsg:'chooseImage:ok'})
  try{await tapButton(client.rpc,p,'选择或拍摄图片 · 0/3');await waitElement(client.rpc,p,'text',e=>e.text==='图片 1 · 上传完成')}
  finally{for(const method of pickers)await mockScope.restore(method)}
  pass('owner native review uploads actual synthetic PNG through scanner')
  await decide(p,'提交本人评价',false)
  assert.equal(f.sql(`SELECT COUNT(*) FROM order_review WHERE order_id=${order}`),'0')
  assert.equal(f.sql(`SELECT COUNT(*) FROM service_archive_job WHERE order_id=${order}`),'0');pass('cancel callback creates neither review nor archive job')
  await decide(p,'提交本人评价')
  await waitElement(client.rpc,p,'text',e=>e.text==='本人评价已保存 · 5 / 5 分')
  assert.equal(f.sql(`SELECT COUNT(*) FROM order_review WHERE order_id=${order} AND user_id=${ids.owner} AND rating=5 AND test_mode=1`),'1')
  const review=Number(f.sql(`SELECT id FROM order_review WHERE order_id=${order}`)),photo=Number(f.sql(`SELECT file_id FROM order_review_file WHERE review_id=${review}`))
  assert.equal(f.sql(`SELECT COUNT(*) FROM file_object WHERE id=${photo} AND owner_type='user' AND owner_id=${ids.owner} AND scan_status='CLEAN'`),'1');pass('native review saves unique owner feedback and clean private photo')
  p=await page(`pages/order/review?id=${order}`)
  await waitElement(client.rpc,p,'text',e=>e.text==='本人评价已保存 · 5 / 5 分')
  assert.equal((await elements(client.rpc,p,'textarea')).length,0)
  await waitElement(client.rpc,p,'button',e=>e.text?.trim()==='查看评价图片 1');pass('native review reload retains immutable content and private photo entry')
  const deadline=Date.now()+30000
  while(f.sql(`SELECT COUNT(*) FROM service_archive_job WHERE order_id=${order} AND status='DONE' AND archive_id IS NOT NULL`)!=='1'){assert.ok(Date.now()<deadline,'Real archive worker did not complete');await delay(500)}
  const archive=Number(f.sql(`SELECT archive_id FROM service_archive_job WHERE order_id=${order}`))
  assert.equal(f.sql(`SELECT COUNT(*) FROM audit_log WHERE action='SERVICE_ARCHIVE_CREATE' AND resource_id=${archive}`),'1');pass('real background worker creates one archive and audit','database')
  const listed=await query('GET',`/api/archive/list?vehicle_id=${ids.vehicle}`,actors.owner.session.token,200)
  const record=listed.list.find(a=>a.archive_id===archive)
  assert.ok(record);assert.equal(record.source.order_id,order);assert.equal(record.source.review_id,review);assert.equal(record.work_minutes,30);assert.equal(record.test_mode,true);assert.equal(record.mileage,null)
  assert.equal(record.no_parts,true);assert.ok(record.file_ids.length>=2)
  const signature=Number(f.sql(`SELECT file_id FROM service_evidence_file WHERE order_id=${order} AND kind='SIGNATURE'`))
  assert.ok(!record.file_ids.includes(signature));pass('archive source and real work metadata exclude signature evidence','http')
  p=await page('pages/archive/index')
  await waitElement(client.rpc,p,'text',e=>e.text==='测试施工记录，未真实扣款')
  await waitElement(client.rpc,p,'text',e=>e.text==='实际工时 30 分钟')
  await waitElement(client.rpc,p,'text',e=>e.text==='本次未使用配件')
  await waitElement(client.rpc,p,'button',e=>e.text?.trim()==='查看施工图片 1');pass('native archive displays test work minutes no-parts and photo entries')
  // Latest archive is first, matching the server's list order; verify the clicked order number.
  assert.equal(listed.list[0].archive_id,archive)
  await tapButton(client.rpc,p,'查看来源订单');p=await waitForPage(client.rpc,'pages/order/detail')
  const orderNo=f.sql(`SELECT order_no FROM \`order\` WHERE id=${order}`)
  await waitElement(client.rpc,p,'text',e=>e.text==='订单号 '+orderNo)
  await waitElement(client.rpc,p,'button',e=>e.text?.trim()==='本人订单评价 / 查看记录');pass('native archive source link opens the actual completed order')
  const filePath=`/api/archive/${archive}/files/${record.file_ids[0]}/access`
  const access=await query('GET',filePath,actors.owner.session.token,200)
  assert.ok(access.url&&Date.parse(access.expires_at)>Date.now());pass('owner obtains short-lived private archive access without claiming rendering','http')
  for(const [name,target,token,status]of [
    ['other owner review',`/api/order/${order}/review`,session.token,404],
    ['merchant review',`/api/order/${order}/review`,actors.shop.session.token,403],
    ['other owner archive',`/api/archive/list?vehicle_id=${ids.vehicle}`,session.token,404],
    ['other owner archive photo',filePath,session.token,404],
    ['anonymous archive photo',filePath,null,401]
  ]){await query('GET',target,token,status);pass(name+' denied','http')}
  for(const [name,token,status]of [['merchant',actors.shop.session.token,403],['other owner',session.token,404]]){
    const denied=await f.api('POST','/api/order/review',token,{key:f.randomUUID(),body:{order_id:order,rating:5,content:'合成越权请求，预期拒绝',photo_file_ids:[]}})
    assert.equal(denied.status,status);pass(name+' cannot submit feedback for owner','http')
  }
  assert.equal(f.sql(`SELECT COUNT(*) FROM order_review WHERE order_id=${order}`),'1')
  await actor('otherOwner');p=await page(`pages/order/review?id=${order}`)
  await waitElement(client.rpc,p,'text',e=>e.text==='订单不存在或非本人订单')
  assert.equal((await elements(client.rpc,p,'text')).filter(e=>e.text?.startsWith('本人评价已保存')).length,0);pass('other owner native page does not expose saved feedback')
  await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'clear'}]})
  p=await page(`pages/order/review?id=${order}`)
  await waitElement(client.rpc,p,'button',e=>e.text?.trim()==='前往车主登录')
  assert.equal((await elements(client.rpc,p,'text')).filter(e=>e.text?.startsWith('本人评价已保存')).length,0);pass('cleared native identity requires login and clears private feedback')
  report.completed=true
  return {work,archive,review,photo,record,report}
}
async function cleanupNativeCompletion(){
  report.cleaned=await cleanupNativeWorkFiles()
  report.finishedAt=new Date().toISOString();fs.mkdirSync('test-results',{recursive:true});fs.writeFileSync('test-results/wechat-native-completion.json',JSON.stringify(report,null,2))
  console.log(`Native completion ${report.completed&&report.cleaned?'COMPLETE':'INCOMPLETE'}: ${report.checks.length} passed (prior work preparation recorded separately)`)
  return report.cleaned
}
module.exports={runNativeCompletion:main,cleanupNativeCompletion}
if(require.main===module)main().catch(e=>{report.failure=e.message;console.error(e.message);process.exitCode=1}).finally(cleanupNativeCompletion)
