// Loaded only by the opt-in loopback harness. Never a production login route.
const fs = require('node:fs')
const path = require('node:path')
const { randomUUID, randomBytes, createHmac } = require('node:crypto')
module.exports = function merchantFixtures(sql, root) {
  const issued = new Set()
  function prepare() {
    for (const suffix of ['A','B']) {
      const id = suffix === 'A' ? 9101201 : 9101202
      const name = `本地合成测试商家${suffix}`, account = `local-quotes-merchant-${suffix}`
      const conflicts = sql(`SELECT (SELECT COUNT(*) FROM merchant WHERE id=${id} AND NOT COALESCE(name='${name}' AND address='合成地址${suffix}' AND merchant_type=2 AND status=1 AND is_deleted=0,0))+(SELECT COUNT(*) FROM staff_account WHERE (id=${id} OR account='${account}') AND NOT COALESCE(id=${id} AND account='${account}' AND merchant_id=${id} AND role='MERCHANT' AND status='ACTIVE' AND is_deleted=0,0));`)
      if(conflicts !== '0') throw new Error('Synthetic merchant fixture conflict')
      sql(`START TRANSACTION; INSERT INTO merchant(id,merchant_type,name,address,status) SELECT ${id},2,'${name}','合成地址${suffix}',1 WHERE NOT EXISTS(SELECT 1 FROM merchant WHERE id=${id}); INSERT INTO staff_account(id,merchant_id,role,account) SELECT ${id},${id},'MERCHANT','${account}' WHERE NOT EXISTS(SELECT 1 FROM staff_account WHERE id=${id}); COMMIT;`)
    }
    for(const migration of ['V006__merchant_project_versions.sql','V007__reservation_orders.sql','V008__payment_foundation.sql'])sql(fs.readFileSync(path.join(root,'docs/sql/migrations',migration),'utf8'))
  }
  function session(store) {
    if(!['A','B'].includes(store)) throw new Error('Fixed synthetic store required')
    const id=store==='A'?9101201:9101202, jti=randomUUID(), now=Math.floor(Date.now()/1000)
    const env=fs.readFileSync(path.join(root,'.env.auth-backend.local'),'utf8')
    const secret=env.split(/\r?\n/).find(line=>line.startsWith('JWT_SECRET='))?.slice(11)
    if(!secret || secret.length<32) throw new Error('Private local JWT configuration required')
    const claims={iss:'vehicle-health-manager',sub:String(id),subject_type:'staff_account',role:'MERCHANT',merchant_id:id,app_id:'merchant-account',jti,iat:now,exp:now+600}
    const encode=value=>Buffer.from(JSON.stringify(value)).toString('base64url')
    const input=`${encode({alg:'HS256',typ:'JWT'})}.${encode(claims)}`
    const token=`${input}.${createHmac('sha256',secret).update(input).digest('base64url')}`
    sql(`INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,merchant_id,refresh_hash,expires_at) VALUES('${jti}','staff_account',${id},'MERCHANT','merchant-account',${id},'${randomBytes(32).toString('hex')}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 10 MINUTE));`)
    issued.add(jti)
    return {access_token:token,expires_in:600,user:{role:'merchant',merchant_id:id}}
  }
  function evidence() {
    const values=sql("SELECT (SELECT COUNT(*) FROM merchant_project WHERE merchant_id IN (9101201,9101202)),(SELECT COUNT(*) FROM merchant_project_version v JOIN merchant_project q ON q.id=v.merchant_project_id WHERE q.merchant_id IN (9101201,9101202)),(SELECT COUNT(*) FROM audit_log WHERE actor_type='staff_account' AND actor_id IN (9101201,9101202) AND action='MERCHANT_PROJECT_SAVE');").split('\t').map(Number)
    return {quotes:values[0],versions:values[1],audits:values[2]}
  }
  function revoke() {
    for(const jti of issued) sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id='${jti}' AND subject_type='staff_account' AND subject_id IN (9101201,9101202);`)
    issued.clear()
  }
  return {prepare,session,evidence,revoke}
}
