// Opt-in isolated native UI acceptance. Synthetic sessions; actual UI, HTTP and MySQL.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const { createHash, createHmac } = require('node:crypto')
const f = require('./local_service_work_fixtures.cjs')
const { connectDevtools, waitForPage } = require('../apps/miniapp/test/helpers/devtools-rpc.cjs')
const { elements, waitElement, tapButton, tapElement } = require('../apps/miniapp/test/helpers/native-elements.cjs')
const ids = { owner:9301290, vehicle:9301290, shop:9301201, otherShop:9301202, tech:9301231, otherTech:9301232, binding:9301331, otherBinding:9301332, slot:9301301 }
const report = { completed:false, startedAt:new Date().toISOString(), checks:[], scope:'synthetic identity; real native page buttons and HTTP; showModal system result is mocked (native modal click pending); LOCAL_TEST is not real payment; uploads are HTTP preparation', nativeModalClickAccepted:false }
const sessions = []; let actors, order, client, modalMocked=false
const pass = name => { report.checks.push({name,passed:true}); console.log(`PASS ${name}`) }
const delay = ms => new Promise(resolve=>setTimeout(resolve,ms))
function status() { return f.sql(`SELECT status FROM \`order\` WHERE id=${order}`) }
function assignments() { return f.sql(`SELECT COUNT(*) FROM technician_assignment WHERE order_id=${order}`) }
async function page(url) {
  await client.rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/'+url}]})
  return waitForPage(client.rpc,url.split('?')[0])
}
async function actor(name) {
  await page('pages/index/index')
  const {role,session}=actors[name]
  const {result}=await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role,token:session.token,...(role==='owner'?{vehicle:ids.vehicle}:{})}]})
  assert.equal(result.ready,true)
}
async function modal(p,label,confirm) {
  report.step=`${p.path}: ${confirm?'confirm':'cancel'} ${label}`
  // Official SDK system-API mock only; page methods/data and HTTP remain real.
  await client.rpc('App.mockWxMethod',{method:'showModal',result:{confirm,cancel:!confirm,errMsg:'showModal:ok'}})
  modalMocked=true
  try {
    await tapButton(client.rpc,p,label); await delay(150)
  } finally {await client.rpc('App.mockWxMethod',{method:'showModal'});modalMocked=false}
}
async function expectHttp(method,target,token,options) {
  const response=await f.api(method,target,token,options)
  assert.equal(response.status,200,`Fixture HTTP failed at ${target}`)
  assert.equal(f.codeOf(response),0)
  return f.dataOf(response)
}
async function prepare() {
  f.verifyIsolation('a7-publication')
  assert.equal(f.docker(['exec',f.BACKEND,'sha256sum','/app/app.jar']).split(/\s/)[0],createHash('sha256').update(fs.readFileSync('backend/target/platform-backend-0.1.0-SNAPSHOT.jar')).digest('hex'))
  assert.equal(f.envVar(f.BACKEND,'SPRING_PROFILES_ACTIVE'),'local-payment-test')
  assert.equal(f.envVar(f.BACKEND,'PAYMENT_LOCAL_TEST_ENABLED'),'true')
  const app=f.envVar(f.BACKEND,'WECHAT_APP_ID'), secret=f.envVar(f.BACKEND,'JWT_SECRET')
  assert.match(app,/^[a-zA-Z0-9-]{1,64}$/)
  // Refuse collisions; never reset old A6/A7 orders, files, sessions or audit records.
  assert.equal(f.sql(`SELECT
    (SELECT COUNT(*) FROM merchant WHERE id IN (${ids.shop},${ids.otherShop}) AND name NOT IN ('原生合成派工店A','原生合成派工店B'))+
    (SELECT COUNT(*) FROM staff_account WHERE id IN (${ids.shop},${ids.otherShop},${ids.tech},${ids.otherTech}) AND account NOT IN ('native-r0-shop-a','native-r0-shop-b','原生合成技师甲','原生合成技师乙'))+
    (SELECT COUNT(*) FROM user WHERE id=${ids.owner} AND openid<>'native-r0-owner')+
    (SELECT COUNT(*) FROM vehicle WHERE id=${ids.vehicle} AND (user_id<>${ids.owner} OR plate_no<>'合成原生R0'))+
    (SELECT COUNT(*) FROM appointment_slot WHERE id=${ids.slot} AND merchant_id<>${ids.shop})+
    (SELECT COUNT(*) FROM staff_wechat_identity WHERE id IN (${ids.binding},${ids.otherBinding}) AND openid NOT IN ('native-r0-tech-a','native-r0-tech-b'));`),'0','Synthetic identity collision')
  f.sql(`START TRANSACTION;
    INSERT IGNORE INTO merchant(id,merchant_type,name,address,status) VALUES(${ids.shop},2,'原生合成派工店A','合成地址',1),(${ids.otherShop},2,'原生合成派工店B','合成地址',1);
    INSERT IGNORE INTO staff_account(id,merchant_id,role,account,status) VALUES
    (${ids.shop},${ids.shop},'MERCHANT','native-r0-shop-a','ACTIVE'),(${ids.otherShop},${ids.otherShop},'MERCHANT','native-r0-shop-b','ACTIVE'),
    (${ids.tech},${ids.shop},'TECHNICIAN','原生合成技师甲','ACTIVE'),(${ids.otherTech},${ids.shop},'TECHNICIAN','原生合成技师乙','ACTIVE');
    INSERT IGNORE INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES(${ids.binding},'${app}','native-r0-tech-a',${ids.tech}),(${ids.otherBinding},'${app}','native-r0-tech-b',${ids.otherTech});
    INSERT IGNORE INTO user(id,openid,status) VALUES(${ids.owner},'native-r0-owner',1);
    INSERT IGNORE INTO vehicle(id,user_id,plate_no,model_id) VALUES(${ids.vehicle},${ids.owner},'合成原生R0',9100601);
    INSERT IGNORE INTO appointment_slot(id,merchant_id,project_id,starts_at,ends_at,capacity) VALUES(${ids.slot},${ids.shop},1,DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 HOUR),100);
    COMMIT;`)
  assert.equal(f.sql(`SELECT COUNT(*) FROM staff_account WHERE id IN (${ids.shop},${ids.otherShop},${ids.tech},${ids.otherTech}) AND status='ACTIVE' AND is_deleted=0 AND merchant_id=IF(id=${ids.otherShop},${ids.otherShop},${ids.shop}) AND role=IF(id IN (${ids.tech},${ids.otherTech}),'TECHNICIAN','MERCHANT')`),'4')
  assert.equal(f.sql(`SELECT COUNT(*) FROM staff_wechat_identity WHERE (id=${ids.binding} AND staff_account_id=${ids.tech} OR id=${ids.otherBinding} AND staff_account_id=${ids.otherTech}) AND app_id='${app}'`),'2')
  pass('isolated runtime, payment profile and synthetic identity ownership verified')
  actors={}
  for(const [name,role,spec] of [
    ['owner','owner',{type:'user',role:'OWNER',subject:ids.owner}],
    ['shop','merchant',{type:'staff_account',role:'MERCHANT',subject:ids.shop,appId:'merchant-account',merchantId:ids.shop}],
    ['otherShop','merchant',{type:'staff_account',role:'MERCHANT',subject:ids.otherShop,appId:'merchant-account',merchantId:ids.otherShop}],
    ['tech','technician',{type:'staff_account',role:'TECHNICIAN',subject:ids.tech,appId:app,merchantId:ids.shop,bindingId:ids.binding}],
    ['otherTech','technician',{type:'staff_account',role:'TECHNICIAN',subject:ids.otherTech,appId:app,merchantId:ids.shop,bindingId:ids.otherBinding}],
  ]) { const session=f.mint(secret,app,spec); sessions.push({session,spec}); actors[name]={role,session} }
  const number='native-r0-'+f.randomUUID().slice(0,8)
  order=Number(f.sql(`INSERT INTO \`order\`(order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,verify_code,appointment_at,expires_at,project_snapshot,appointment_snapshot)
    VALUES('${number}',${ids.owner},${ids.vehicle},${ids.shop},128,128,'PENDING_PAYMENT',${ids.slot},'246813',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE),JSON_OBJECT('project_name','原生合成保养','service_content','原生派工验收'),JSON_OBJECT('slot_id',${ids.slot},'starts_at',DATE_FORMAT(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),'%Y-%m-%dT%H:%i:%sZ'),'ends_at',DATE_FORMAT(DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 HOUR),'%Y-%m-%dT%H:%i:%sZ')));SELECT LAST_INSERT_ID();`))
  assert.ok(Number.isSafeInteger(order)&&order>0)
  const paid=await expectHttp('POST','/api/payments/create',actors.owner.session.token,{key:f.randomUUID(),body:{order_id:order,channel:'LOCAL_TEST'}})
  assert.equal(paid.test_mode,true)
  const raw=JSON.stringify({event_id:f.randomUUID(),payment_id:paid.payment_id,channel_payment_no:'native_r0_'+f.randomUUID().replaceAll('-',''),status:'SUCCEEDED',amount:'128.00',currency:'CNY',order_no:number,occurred_at:new Date().toISOString()})
  const timestamp=String(Math.floor(Date.now()/1000)),nonce=f.randomUUID(),signature=createHmac('sha256',f.envVar(f.BACKEND,'PAYMENT_LOCAL_TEST_SECRET')).update(timestamp+'\n'+nonce+'\n'+raw+'\n').digest('hex')
  const callback=await fetch('http://127.0.0.1:18080/api/payments/callback/LOCAL_TEST',{method:'POST',headers:{'Content-Type':'application/json','X-Test-Timestamp':timestamp,'X-Test-Nonce':nonce,'X-Test-Signature':signature},body:raw,signal:AbortSignal.timeout(35000)})
  assert.equal(callback.status,200); assert.equal(status(),'PAID'); pass('real LOCAL_TEST payment preparation; no real deduction')
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aSkcAAAAASUVORK5CYII=','base64'),photos={}
  for(const slot of ['FRONT','REAR','LEFT','RIGHT','ROOF','DASHBOARD','INTERIOR']){
    const form=new FormData();form.append('file',new Blob([png],{type:'image/png'}),'native-synthetic.png')
    const response=await fetch('http://127.0.0.1:18080/api/merchant/files/upload',{method:'POST',headers:{Authorization:'Bearer '+actors.shop.session.token,'Idempotency-Key':f.randomUUID()},body:form,signal:AbortSignal.timeout(85000)})
    assert.equal(response.status,200,'Scanned fixture upload failed'); photos[slot]=(await response.json()).data.file_id
  }
  await expectHttp('POST','/api/check/pickup/submit',actors.shop.session.token,{key:f.randomUUID(),body:{order_id:order,appointment_code:'246813',photos,mileage:52000,fuel_level:'HALF',damage_status:'NONE',damages:[]}})
  assert.equal(status(),'RECEIVED'); pass('real scanned seven-image pickup preparation; not native upload acceptance')
}
async function main() {
  await prepare(); client=await connectDevtools({endpoint:'ws://127.0.0.1:9422',timeoutMs:15000})
  await actor('shop'); let p=await page(`pages/merchant/order-detail?id=${order}`)
  await waitElement(client.rpc,p,'text',e=>e.text==='测试支付，未真实扣款，不可作为收款凭据'); pass('native merchant detail shows explicit test payment')
  await waitElement(client.rpc,p,'text',e=>e.text.startsWith('已完成接车检查；车主确认后可派工')); pass('native received state does not claim owner already confirmed')
  report.step='merchant detail dispatch navigation'
  await tapButton(client.rpc,p,'派工给本店技师'); p=await waitForPage(client.rpc,'pages/merchant/dispatch')
  await waitElement(client.rpc,p,`[data-testid="candidate-${ids.tech}"]`)
  const candidate=await waitElement(client.rpc,p,`[data-testid="candidate-${ids.tech}"] button`)
  await tapElement(client.rpc,p,candidate)
  await waitElement(client.rpc,p,'[data-testid="candidate-selected"]')
  await modal(p,'派工给「原生合成技师甲」',true)
  await waitElement(client.rpc,p,'[data-testid="dispatch-message"]',e=>e.text.includes('车主尚未确认'))
  assert.equal(assignments(),'0'); pass('native assignment before owner confirmation rejected without database write')
  await actor('owner'); p=await page(`pages/check/pickup-detail?id=${order}`)
  await waitElement(client.rpc,p,'[data-testid="pickup-sheet"]')
  await modal(p,'确认接车单',false); assert.equal(f.sql(`SELECT owner_confirm FROM pickup_check WHERE order_id=${order}`),'0'); pass('system cancel callback preserves native pickup undecided state')
  await modal(p,'确认接车单',true)
  await waitElement(client.rpc,p,'text',e=>e.text.includes('确认接车单')&&e.text.includes('车主已于'))
  assert.equal(f.sql(`SELECT owner_confirm FROM pickup_check WHERE order_id=${order}`),'1'); pass('native owner confirmation persisted')
  await actor('shop'); p=await page(`pages/merchant/dispatch?id=${order}`)
  const target=await waitElement(client.rpc,p,`[data-testid="candidate-${ids.tech}"] button`)
  await tapElement(client.rpc,p,target)
  await waitElement(client.rpc,p,'[data-testid="candidate-selected"]')
  await modal(p,'派工给「原生合成技师甲」',false); assert.equal(assignments(),'0'); pass('system cancel callback creates no native assignment')
  await modal(p,'派工给「原生合成技师甲」',true)
  await waitElement(client.rpc,p,'[data-testid="dispatch-existing-status"]',e=>e.text==='待接单')
  assert.equal(f.sql(`SELECT technician_id FROM technician_assignment WHERE order_id=${order}`),String(ids.tech)); pass('native merchant assignment targets the selected bound technician')
  await actor('otherShop'); p=await page(`pages/merchant/order-detail?id=${order}`)
  await waitElement(client.rpc,p,'text',e=>e.text.includes('不存在'))
  assert.equal((await elements(client.rpc,p,'[data-testid="merchant-order-detail"]')).length,0); pass('native foreign merchant cannot read target order')
  await actor('otherTech'); p=await page(`pages/technician/order-detail?id=${order}`)
  await waitElement(client.rpc,p,'text',e=>e.text==='派工或工单资源不存在')
  assert.equal((await elements(client.rpc,p,'[data-testid="technician-order-detail"]')).length,0); pass('native unassigned technician cannot read target work order')
  await actor('tech'); p=await page('pages/technician/orders')
  const row=await waitElement(client.rpc,p,`[data-testid="technician-order-${order}"] button`)
  await tapElement(client.rpc,p,row); p=await waitForPage(client.rpc,'pages/technician/order-detail')
  await waitElement(client.rpc,p,'[data-testid="technician-accept"]'); pass('native assigned technician finds own work order and can accept')
  await modal(p,'接单并开始施工',false); assert.equal(status(),'RECEIVED'); pass('system cancel callback leaves native work order received')
  await modal(p,'接单并开始施工',true)
  await waitElement(client.rpc,p,'[data-testid="technician-detail-status"]',e=>e.text==='订单状态 施工中')
  assert.equal(status(),'IN_SERVICE'); assert.equal(f.sql(`SELECT status FROM technician_assignment WHERE order_id=${order}`),'ACCEPTED'); pass('native technician acceptance enters service in database')
  await tapButton(client.rpc,p,'刷新工单')
  await waitElement(client.rpc,p,'[data-testid="technician-detail-assignment"]',e=>e.text==='已接单'); pass('native refreshed work order remains accepted')
  await actor('shop'); p=await page(`pages/merchant/order-detail?id=${order}`)
  await waitElement(client.rpc,p,'[data-testid="merchant-order-status"]',e=>e.text==='施工中'); pass('native merchant sees actual service progress')
  await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'clear'}]})
  p=await page('pages/technician/orders'); await waitElement(client.rpc,p,'button',e=>e.text==='前往技师登录'); pass('cleared identities restore native technician login requirement')
  report.completed=true
}
module.exports={nativeFixtureSessions:sessions,prepareNativeFixture:async()=>{await prepare();return {actors,order,ids,sessions,checks:[...report.checks]}}}
if(require.main===module)main().catch(error=>{report.failure=error.message;console.error(error.message);process.exitCode=1}).finally(async()=>{
  let cleaned=true
  if(client){
    if(modalMocked)try{await client.rpc('App.mockWxMethod',{method:'showModal'})}catch{cleaned=false;process.exitCode=1}
    try{await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'clear'}]})}catch{cleaned=false;process.exitCode=1}
    finally{client.close()}
  }
  try {
    for(const {session,spec} of sessions){assert.match(session.jti,/^[0-9a-f-]{36}$/i);f.sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id='${session.jti}' AND subject_type='${spec.type}' AND subject_id=${spec.subject}`)}
    if(sessions.length)assert.equal(f.sql(`SELECT COUNT(*) FROM auth_session WHERE id IN (${sessions.map(({session})=>"'"+session.jti+"'").join(',')}) AND revoked_at IS NULL`),'0')
  } catch {cleaned=false;process.exitCode=1;console.error('Synthetic session cleanup failed')}
  report.cleaned=cleaned;report.finishedAt=new Date().toISOString()
  const output=path.resolve('test-results/wechat-native-dispatch.json');fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(report,null,2))
  console.log(`Native dispatch ${report.completed&&cleaned?'COMPLETE':'INCOMPLETE'}: ${report.checks.length} passed`)
})
