// Explicit local-only fixture permission is enforced by the existing fixture module.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict')
const f = require('./local_service_work_fixtures.cjs')
const { connectDevtools, waitForPage } = require('../apps/miniapp/test/helpers/devtools-rpc.cjs')
const { elements, waitElement, tapButton } = require('../apps/miniapp/test/helpers/native-elements.cjs')
const fixtureFile = path.resolve('.cache/a7-publication-ui.json')
const output = path.resolve('test-results/wechat-native-publication.json')
const report = { completed: false, startedAt: new Date().toISOString(), checks: [], scope: 'synthetic identity bridge; real native UI and HTTP; not SMS, payment or device acceptance' }
const pass = name => { report.checks.push({ name, passed: true }); console.log(`PASS ${name}`) }
let fixture, validated = false, client
const expirations = []
async function page(url, expected = url) {
  await client.rpc('App.callWxMethod', { method: 'reLaunch', args: [{ url: '/' + url }] })
  return waitForPage(client.rpc, expected)
}
async function actor(role) {
  await page('pages/index/index')
  const opts = role === 'operator' ? { role, token: fixture.operatorToken } : role === 'reader' ? { role: 'owner', token: fixture.reader.token, vehicle: fixture.readerVehicle } : { role: 'owner', token: fixture.owner.token, vehicle: fixture.vehicle }
  const { result } = await client.rpc('App.callWxMethod', { method: '__autocareNativeAcceptance', args: [opts] })
  assert.equal(result.ready, true)
}
async function state(expected, currentPage) {
  const p = currentPage || await page('pages/community/my-cards')
  await waitElement(client.rpc, p, '[data-testid="experience-state"]', element => element.text === expected)
  return p
}
async function main() {
  f.verifyIsolation('a7-publication')
  fixture = JSON.parse(fs.readFileSync(fixtureFile, 'utf8'))
  assert.equal(fixture.order, 9207641); assert.equal(fixture.vehicle, 9207590); assert.equal(fixture.readerVehicle, 9207591)
  assert.ok(Number.isSafeInteger(fixture.card) && fixture.card > 0)
  assert.ok(Number.isSafeInteger(fixture.operatorId) && fixture.operatorId > 0)
  for (const [session,subject,type,role] of [[fixture.owner,9207590,'user','OWNER'],[fixture.reader,9207591,'user','OWNER'],[{token:fixture.operatorToken},fixture.operatorId,'operator_account','OPERATOR']]) {
    const claims=JSON.parse(Buffer.from(session.token.split('.')[1],'base64url').toString())
    assert.equal(String(claims.sub),String(subject));assert.equal(claims.subject_type,type);assert.equal(claims.role,role)
    if(session.jti){assert.equal(claims.jti,session.jti);assert.match(session.jti,/^[0-9a-f-]{36}$/i)}
    expirations.push(claims.exp)
  }
  assert.equal(f.sql(`SELECT COUNT(*) FROM experience_card WHERE id=${Number(fixture.card)} AND order_id=9207641 AND vehicle_id=9207590 AND user_id=9207590`), '1')
  assert.equal(f.sql(`SELECT COUNT(*) FROM operator_account WHERE id=${fixture.operatorId} AND account LIKE 'pub-%' AND can_review=1 AND status='ACTIVE'`), '1')
  assert.equal(f.docker(['exec',f.BACKEND,'sha256sum','/app/app.jar']).split(/\s/)[0], require('node:crypto').createHash('sha256').update(fs.readFileSync('backend/target/platform-backend-0.1.0-SNAPSHOT.jar')).digest('hex'))
  validated = true; pass('isolated source, operator and runtime JAR verified')
  assert.ok(expirations.every(exp=>exp>Date.now()/1000+120),'Fresh synthetic sessions required')
  client = await connectDevtools({ endpoint: 'ws://127.0.0.1:9422', timeoutMs: 15000 })
  await actor('owner'); let p = await state('审核通过 · 已展示'); pass('owner reads approved synthetic source in native UI')
  await tapButton(client.rpc,p,'撤回授权'); await state('已撤回 · 未授权',p); pass('native owner withdraws existing approval')
  p = await state('已撤回 · 未授权')
  const checkbox = await waitElement(client.rpc,p,'checkbox')
  await client.rpc('Element.tap',{pageId:p.pageId,elementId:checkbox.elementId})
  const group = await waitElement(client.rpc,p,'checkbox-group')
  await client.rpc('Element.triggerEvent',{pageId:p.pageId,elementId:group.elementId,type:'change',detail:{value:['agree']}})
  await tapButton(client.rpc,p,'重新授权送审'); await state('已授权 · 待审核，尚未公开',p); pass('native checkbox consent submits for review')
  await actor('operator'); p = await page('pages/operator/review')
  assert.equal(f.sql("SELECT COUNT(*) FROM experience_card WHERE status='PENDING_REVIEW'"),'1','Only the verified synthetic card may be pending during this run')
  await tapButton(client.rpc,p,'批准同款摘要展示');
  await waitElement(client.rpc,p,'text',element=>element.text==='已批准；车主可随时撤回授权')
  assert.equal(f.sql(`SELECT status FROM experience_card WHERE id=${Number(fixture.card)}`),'PUBLISHED'); pass('native operator approves and database changes')
  await actor('reader'); p = await page('pages/community/experiences')
  await waitElement(client.rpc,p,'[data-testid="public-experience"]'); pass('different same-model owner sees approved native summary')
  await actor('owner'); p = await state('审核通过 · 已展示')
  await tapButton(client.rpc,p,'撤回授权'); await state('已撤回 · 未授权',p); pass('native owner withdraws after publication')
  await actor('reader'); p = await page('pages/community/experiences')
  await waitElement(client.rpc,p,'text',element => element.text === '暂无可展示的同款摘要。车主撤回或来源变化的记录会隐藏。')
  assert.equal((await elements(client.rpc,p,'[data-testid="public-experience"]')).length,0); pass('fresh native same-model query hides withdrawn source')
  await actor('owner'); p = await state('已撤回 · 未授权')
  const consent = await waitElement(client.rpc,p,'checkbox-group')
  await client.rpc('Element.triggerEvent',{pageId:p.pageId,elementId:consent.elementId,type:'change',detail:{value:['agree']}})
  await tapButton(client.rpc,p,'重新授权送审'); await state('已授权 · 待审核，尚未公开',p)
  await actor('operator'); p = await page('pages/operator/review')
  assert.equal(f.sql("SELECT COUNT(*) FROM experience_card WHERE status='PENDING_REVIEW'"),'1')
  const reason = await waitElement(client.rpc,p,'picker')
  await client.rpc('Element.triggerEvent',{pageId:p.pageId,elementId:reason.elementId,type:'change',detail:{value:'1'}})
  await tapButton(client.rpc,p,'按所选理由驳回')
  await waitElement(client.rpc,p,'text',element=>element.text==='已驳回；车主可重新明确授权送审')
  assert.equal(f.sql(`SELECT status FROM experience_card WHERE id=${Number(fixture.card)}`),'REJECTED'); pass('native operator rejects with selected reason')
  await actor('owner'); p = await state('审核未通过 · 尚未公开')
  await waitElement(client.rpc,p,'text',element=>element.text.includes('不适合经验展示')); pass('native owner reads rejection and reason')
  await tapButton(client.rpc,p,'撤回授权'); await state('已撤回 · 未授权',p)
  await page('pages/index/index')
  await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'clear'}]})
  p=await page('pages/operator/review','pages/operator/login')
  assert.equal(p.path,'pages/operator/login'); pass('cleared bridge restores independent operator login protection')
  report.completed=true
}
main().catch(error=>{report.failure=error.message;console.error(error.message);process.exitCode=1}).finally(async()=>{
  if(client){
    try {await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'clear'}]})}
    catch {report.clientCleared=false;process.exitCode=1}
    finally {client.close()}
  }
  try {
    if(validated){
      // Only the verified synthetic card/source and this run's short sessions are affected.
      try {
        const withdrawn = await f.api('POST',`/api/experience-cards/${fixture.card}/withdraw`,fixture.owner.token,{key:f.randomUUID(),body:{}})
        assert.equal(withdrawn.status,200)
        assert.equal(f.sql(`SELECT status FROM experience_card WHERE id=${Number(fixture.card)}`),'WITHDRAWN')
      } finally {
        for(const session of [fixture.owner,fixture.reader]){
        assert.match(session.jti,/^[0-9a-f-]{36}$/i)
        f.sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id='${session.jti}' AND subject_type='user' AND subject_id IN (9207590,9207591)`)
        }
        f.sql(`UPDATE operator_account SET status='DISABLED' WHERE id=${fixture.operatorId} AND account LIKE 'pub-%'; UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE subject_type='operator_account' AND subject_id=${fixture.operatorId}`)
        fs.unlinkSync(fixtureFile)
      }
      report.cleaned=report.clientCleared!==false
    }
  } catch {report.cleaned=false;process.exitCode=1;console.error('Native fixture cleanup failed')}
  finally {client?.close()}
  report.finishedAt=new Date().toISOString();fs.mkdirSync(path.dirname(output),{recursive:true});fs.writeFileSync(output,JSON.stringify(report,null,2))
  console.log(`Native publication ${report.completed&&report.cleaned?'COMPLETE':'INCOMPLETE'}: ${report.checks.length} passed`)
})
