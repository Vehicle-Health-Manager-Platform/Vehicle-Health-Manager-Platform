// Opt-in A6 local fixture module: real backend container + real isolated MySQL.
// Synthetic owner/merchant/technician identities only. Sessions are a documented test
// bridge (minted with the local JWT secret and written to auth_session), NOT real
// WeChat/SMS login and not evidence of real-device acceptance.
//
// Usage: node scripts/local_service_work_fixtures.cjs --allow-local-test-writes
const { spawnSync } = require('node:child_process')
const { randomUUID, randomBytes, createHmac, createHash } = require('node:crypto')

if (!process.argv.includes('--allow-local-test-writes')) {
  console.error('Requires --allow-local-test-writes; only the isolated local database is supported.')
  process.exit(2)
}

const MYSQL = 'vehicle-auth-local-mysql-1'
const BACKEND = 'vehicle-auth-local-backend'
const ORIGIN = 'http://127.0.0.1:18080'
const MYSQL_CMD = 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE" --batch --skip-column-names'

// Synthetic identifiers, far from any real or fixture range.
const SHOP_A = 9206201, SHOP_B = 9206202
const MERCHANT_STAFF_A = 9206201, MERCHANT_STAFF_B = 9206202
const TECH_A = 9206231, TECH_B = 9206232, TECH_FOREIGN = 9206233
const BIND_A = 9206331, BIND_B = 9206332, BIND_FOREIGN = 9206333
const OWNER = 9206290, VEHICLE = 9206290, SLOT = 9206301
const ORDER_POSITIVE = 9206401, ORDER_NO_CHECKIN = 9206402, ORDER_NO_CONFIRM = 9206403, ORDER_DISPUTE = 9206404
const PROJECT_SNAPSHOT = JSON.stringify({ project_name: '合成保养', service_content: 'synthetic', phone: 'private', vin: 'private' })
const APPOINTMENT_SNAPSHOT = JSON.stringify({ slot_id: SLOT, starts_at: '2026-10-08T02:00:00Z', verify_code: 'private' })

function docker(args, input) {
  const result = spawnSync('docker', args, { input, encoding: 'utf8', windowsHide: true })
  if (result.error) throw new Error('Local database operation failed: ' + result.error.message)
  if (result.status !== 0) throw new Error('Isolated local database operation failed (details withheld)'
    + (process.env.LOCAL_E2E_VERBOSE === '1' ? ': ' + String(result.stderr || '').trim().slice(-400) : ''))
  return (result.stdout || '').trim()
}
function sql(query) {
  return docker(['exec', '-i', MYSQL, 'sh', '-c', MYSQL_CMD], query)
}
function envVar(container, name) {
  const dump = docker(['inspect', container, '--format', '{{range .Config.Env}}{{println .}}{{end}}'])
  const line = dump.split(/\r?\n/).find(entry => entry.startsWith(name + '='))
  if (!line) throw new Error('Missing configuration: ' + name)
  return line.slice(name.length + 1)
}

// ---- Fixtures -------------------------------------------------------------------
function verifyIsolation(stage = 'a6') {
  if(!['a6','a7','a7-review','a7-archive','a7-card','a7-publication','r1a-onboarding','r1b-staff'].includes(stage))throw Error('Unsupported local stage');
  const project = docker(['inspect', MYSQL, '--format', '{{index .Config.Labels "com.docker.compose.project"}}'])
  if (project !== 'vehicle-auth-local') throw new Error('Refusing non-test Docker project: ' + project)
  const backend = JSON.parse(docker(['inspect', BACKEND]))[0];
  const image = backend.Config.Image;
  if(backend.HostConfig.PortBindings['8080/tcp']?.[0]?.HostIp!=='127.0.0.1'||backend.HostConfig.PortBindings['8080/tcp']?.[0]?.HostPort!=='18080'||!backend.NetworkSettings.Networks['vehicle-auth-local_default'])throw Error('Only isolated loopback backend/network is supported');
  const columns = sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND (table_name='technician_assignment' AND column_name IN ('assigned_by','accepted_at') OR table_name='pickup_check' AND column_name='dispute_reason');")
  if (columns !== '3') throw new Error('A5 requires V011 and V012 applied to the isolated database (found ' + columns + '/3 columns)')
  const disputes = sql("SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('order_dispute','order_dispute_record')), (SELECT column_comment FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='owner_confirm');").split('	')
  if (disputes[0] !== '2' || !disputes[1].includes('3')) throw new Error('A5.6 requires V013 applied to the isolated database (found ' + disputes[0] + '/2 tables)')
  if(!image.startsWith(stage==='r1b-staff'?'vehicle-auth/backend:r1b-staff':stage==='r1a-onboarding'?'vehicle-auth/backend:r1a-onboarding':stage==='a7-publication'?'vehicle-auth/backend:a7-experience-publication':stage==='a7-card'?'vehicle-auth/backend:a7-experience-card':stage==='a7-archive'?'vehicle-auth/backend:a7-service-archive':stage==='a7-review'?'vehicle-auth/backend:a7-owner-review':stage==='a7'?'vehicle-auth/backend:a7-redeem':'vehicle-auth/backend:a6-service'))throw Error('Expected isolated stage image');
  const tables=sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE();");
  const additions=sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('service_evidence_file','service_report_submission');");
  if(tables!==(stage==='r1b-staff'?'63':stage==='r1a-onboarding'?'63':stage==='a7-publication'?'61':stage==='a7-card'?'59':stage==='a7-archive'?'58':stage==='a7-review'?'57':stage==='a7'?'55':'54')||additions!=='2')throw Error('Expected isolated stage schema');
  // V021 只扩列不加表，所以表数校验挡不住「库停在 V020」——必须逐列核对。
  if(stage==='r1b-staff' && (image!=='vehicle-auth/backend:r1b-staff' || sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='staff_account' AND column_name IN ('display_name','created_by')")!=='2'))throw Error('Exact R1b image and V021 required');
  if(stage==='r1a-onboarding' && (image!=='vehicle-auth/backend:r1a-onboarding' || sql("SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('merchant_application_review','merchant_region_category_quota'))+(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='operator_account' AND column_name='can_onboard')+(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='merchant_application' AND column_name='revision')")!=='4'))throw Error('Exact R1a image and V020 required');
  if(stage==='a7-publication' && (image!=='vehicle-auth/backend:a7-experience-publication' || sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('experience_card','experience_card_moderation','operator_account','service_archive_job');")!=='4'))throw Error('Exact publication image and V019 required');
  if(stage==='a7-card' && (image!=='vehicle-auth/backend:a7-experience-card' || sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('experience_card','service_archive_job');")!=='2'))throw Error('Exact private-card image and V018 required');
  if(stage==='a7-archive' && (image!=='vehicle-auth/backend:a7-service-archive' || sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='service_archive_job';")!=='1'))throw Error('Exact archive image and V017 required');
  if(stage==='a7' && image!=='vehicle-auth/backend:a7-redeem')throw Error('Exact A7 test image required');
  if(stage==='a7' && sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='order_redemption';")!=='1')throw Error('V015 required');
  if(stage==='a7-review' && (image!=='vehicle-auth/backend:a7-owner-review' || sql("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('order_review','order_review_file');")!=='2'))throw Error('Exact owner-review image and V016 required');
  return image
}
function prepare(appId) {
  if(sql("SELECT COUNT(*) FROM `order` WHERE id IN (9206401,9206402,9206403,9206404) AND order_no NOT LIKE 'synthetic-a6-%';")!=='0')throw Error('Synthetic order collision');
  if(sql("SELECT COUNT(*) FROM staff_account WHERE id IN (9206201,9206202,9206231,9206232,9206233) AND account NOT IN ('local-a6-shop-a','local-a6-shop-b','A6合成技师甲','A6合成技师乙','A6合成他店技师');")!=='0')throw Error('Synthetic staff collision');
  if(sql("SELECT (SELECT COUNT(*) FROM merchant WHERE id IN (9206201,9206202) AND name NOT LIKE '本地合成派工商家%')+(SELECT COUNT(*) FROM user WHERE id=9206290 AND openid<>'local-a6-owner')+(SELECT COUNT(*) FROM vehicle WHERE id=9206290 AND (user_id<>9206290 OR plate_no<>'合成牌A6'))+(SELECT COUNT(*) FROM appointment_slot WHERE id=9206301 AND merchant_id<>9206201)+(SELECT COUNT(*) FROM staff_wechat_identity WHERE id IN (9206331,9206332,9206333) AND openid NOT LIKE 'local-a6-openid-%');")!=='0')throw Error('Synthetic fixture collision');
  sql(`
DELETE FROM audit_log WHERE resource_type='technician_assignment' AND resource_id IN (SELECT id FROM technician_assignment WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE}));
DELETE FROM audit_log WHERE resource_type='pickup_check' AND resource_id IN (SELECT id FROM pickup_check WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE}));
DELETE FROM order_dispute_record WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM order_dispute WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM audit_log WHERE resource_type='repair_protection' AND resource_id IN (SELECT id FROM repair_protection WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE}));
DELETE FROM audit_log WHERE resource_type='technician_report' AND resource_id IN (SELECT id FROM technician_report WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE}));
DELETE FROM service_evidence_file WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM service_report_submission WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM repair_protection WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM technician_report WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM technician_assignment WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM audit_log WHERE resource_type='order' AND resource_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM order_status_transition WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM idempotency_record WHERE (actor_type='user' AND actor_id=${OWNER}) OR (actor_type='staff_account' AND actor_id IN (${MERCHANT_STAFF_A},${TECH_A},${TECH_B},${TECH_FOREIGN}));
DELETE FROM pickup_check WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM \`order\` WHERE id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM appointment_slot WHERE id=${SLOT};
DELETE FROM auth_session WHERE subject_id IN (${OWNER},${MERCHANT_STAFF_A},${MERCHANT_STAFF_B},${TECH_A},${TECH_B},${TECH_FOREIGN});
DELETE FROM staff_wechat_identity WHERE id IN (${BIND_A},${BIND_B},${BIND_FOREIGN});
DELETE FROM staff_account WHERE id IN (${MERCHANT_STAFF_A},${MERCHANT_STAFF_B},${TECH_A},${TECH_B},${TECH_FOREIGN});
DELETE FROM merchant WHERE id IN (${SHOP_A},${SHOP_B});
DELETE FROM vehicle WHERE id=${VEHICLE};
DELETE FROM \`user\` WHERE id=${OWNER};

START TRANSACTION;
INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(${SHOP_A},2,'本地合成派工商家A','合成地址A',1),(${SHOP_B},2,'本地合成派工商家B','合成地址B',1);
INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES
 (${MERCHANT_STAFF_A},${SHOP_A},'MERCHANT','local-a6-shop-a','ACTIVE'),
 (${MERCHANT_STAFF_B},${SHOP_B},'MERCHANT','local-a6-shop-b','ACTIVE'),
 (${TECH_A},${SHOP_A},'TECHNICIAN','A6合成技师甲','ACTIVE'),
 (${TECH_B},${SHOP_A},'TECHNICIAN','A6合成技师乙','ACTIVE'),
 (${TECH_FOREIGN},${SHOP_B},'TECHNICIAN','A6合成他店技师','ACTIVE');
INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES
 (${BIND_A},'${appId}','local-a6-openid-a',${TECH_A}),
 (${BIND_B},'${appId}','local-a6-openid-b',${TECH_B}),
 (${BIND_FOREIGN},'${appId}','local-a6-openid-f',${TECH_FOREIGN});
INSERT INTO \`user\`(id,openid,status) VALUES(${OWNER},'local-a6-owner',1);
INSERT INTO vehicle(id,user_id,plate_no,model_id) VALUES(${VEHICLE},${OWNER},'合成牌A6',9100601);
INSERT INTO appointment_slot(id,merchant_id,project_id,starts_at,ends_at,capacity) VALUES(${SLOT},${SHOP_A},1,UTC_TIMESTAMP(),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),10);
INSERT INTO \`order\`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,check_in_completed_at,owner_confirmed_at,verify_code,project_snapshot,appointment_snapshot)
 VALUES(${ORDER_POSITIVE},'synthetic-a6-positive',${OWNER},${VEHICLE},${SHOP_A},128.00,128.00,'RECEIVED',${SLOT},UTC_TIMESTAMP(),NULL,'246813',
        CAST('${PROJECT_SNAPSHOT}' AS JSON),CAST('${APPOINTMENT_SNAPSHOT}' AS JSON));
INSERT INTO \`order\`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,check_in_completed_at,owner_confirmed_at,verify_code,project_snapshot,appointment_snapshot)
 VALUES(${ORDER_NO_CHECKIN},'synthetic-a6-no-checkin',${OWNER},${VEHICLE},${SHOP_A},128.00,128.00,'RECEIVED',${SLOT},NULL,NULL,'246814',
        CAST('${PROJECT_SNAPSHOT}' AS JSON),CAST('${APPOINTMENT_SNAPSHOT}' AS JSON));
INSERT INTO \`order\`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,check_in_completed_at,owner_confirmed_at,verify_code,project_snapshot,appointment_snapshot)
 VALUES(${ORDER_NO_CONFIRM},'synthetic-a6-no-confirm',${OWNER},${VEHICLE},${SHOP_A},128.00,128.00,'RECEIVED',${SLOT},UTC_TIMESTAMP(),NULL,'246815',
        CAST('${PROJECT_SNAPSHOT}' AS JSON),CAST('${APPOINTMENT_SNAPSHOT}' AS JSON));
INSERT INTO pickup_check(order_id,merchant_id,staff_id,owner_confirm,mileage,mileage_source,mileage_baseline,fuel_level,damage_status,arrived_at)
 VALUES(${ORDER_POSITIVE},${SHOP_A},${MERCHANT_STAFF_A},0,52000,'MANUAL',CAST('{"source":"VEHICLE","mileage":51000}' AS JSON),'HALF','NONE',UTC_TIMESTAMP());
INSERT INTO pickup_check(order_id,merchant_id,staff_id,owner_confirm,mileage,mileage_source,mileage_baseline,fuel_level,damage_status,arrived_at)
 VALUES(${ORDER_NO_CONFIRM},${SHOP_A},${MERCHANT_STAFF_A},0,52000,'MANUAL',CAST('{"source":"VEHICLE","mileage":51000}' AS JSON),'HALF','NONE',UTC_TIMESTAMP());
INSERT INTO \`order\`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,check_in_completed_at,owner_confirmed_at,verify_code,project_snapshot,appointment_snapshot)
 VALUES(${ORDER_DISPUTE},'synthetic-a6-dispute',${OWNER},${VEHICLE},${SHOP_A},128.00,128.00,'RECEIVED',${SLOT},UTC_TIMESTAMP(),NULL,'246816',
        CAST('${PROJECT_SNAPSHOT}' AS JSON),CAST('${APPOINTMENT_SNAPSHOT}' AS JSON));
INSERT INTO pickup_check(order_id,merchant_id,staff_id,owner_confirm,mileage,mileage_source,mileage_baseline,fuel_level,damage_status,arrived_at)
 VALUES(${ORDER_DISPUTE},${SHOP_A},${MERCHANT_STAFF_A},0,52000,'MANUAL',CAST('{"source":"VEHICLE","mileage":51000}' AS JSON),'HALF','NONE',UTC_TIMESTAMP());
COMMIT;
`)
}

// ---- Synthetic sessions (documented test bridge) --------------------------------
function jwt(secret, claims) {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url')
  const input = `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode(claims)}`
  return `${input}.${createHmac('sha256', secret).update(input).digest('base64url')}`
}
const sha256 = value => createHash('sha256').update(value).digest('hex')
function mint(secret, appId, spec) {
  const jti = randomUUID(), now = Math.floor(Date.now() / 1000)
  const claims = { iss: 'vehicle-health-manager', sub: String(spec.subject), subject_type: spec.type, role: spec.role, jti, iat: now, exp: now + 900 }
  if (spec.appId) claims.app_id = spec.appId
  if (spec.merchantId) claims.merchant_id = spec.merchantId
  if (spec.bindingId) claims.binding_id = spec.bindingId
  const token = jwt(secret, claims)
  // 会话行存的是 refresh 令牌的 sha256（hex）；给定时才写真实哈希，于是 refresh 可以真的走通。
  const stored = spec.refreshToken ? sha256(spec.refreshToken) : randomBytes(32).toString('hex')
  sql(`INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,binding_id,merchant_id,refresh_hash,expires_at) VALUES('${jti}','${spec.type}',${spec.subject},'${spec.role}','${spec.appId || 'local-a6'}',${spec.bindingId || 'NULL'},${spec.merchantId || 'NULL'},'${stored}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE));`)
  return { token, jti, refreshToken: spec.refreshToken || null }
}
/** 微信绑定凭证：与 AuthTokens.binding 同形（role=BIND、subject_type=wechat_binding）。 */
function bindToken(secret, appId, openid) {
  const now = Math.floor(Date.now() / 1000)
  return jwt(secret, { iss: 'vehicle-health-manager', sub: openid, role: 'BIND', subject_type: 'wechat_binding', iat: now, exp: now + 300, app_id: appId })
}

// ---- HTTP ------------------------------------------------------------------------
async function api(method, target, token, options = {}) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (options.key) headers['Idempotency-Key'] = options.key
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(ORIGIN + target, { method, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body), redirect: 'manual', signal:AbortSignal.timeout(35000) })
  const text = await response.text()
  let json = null
  try { json = JSON.parse(text) } catch { /* non-JSON error pages stay as text */ }
  return { status: response.status, noStore: response.headers.get('cache-control') === 'no-store', json, text }
}

const results = []
function check(name, condition, detail) {
  results.push({ name, ok: !!condition, detail: condition ? '' : (detail || 'assertion failed') })
  console.log(`${condition ? 'ok  ' : 'FAIL'}  ${name}${condition ? '' : ' -> ' + (detail || '')}`)
}
// MySQL JSON columns normalize key order, so compare parsed bodies by value.
function deepEqual(a, b) {
  if (a === b) return true
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return false
  if (Array.isArray(a) !== Array.isArray(b)) return false
  const ka = Object.keys(a), kb = Object.keys(b)
  if (ka.length !== kb.length) return false
  return ka.every(key => Object.prototype.hasOwnProperty.call(b, key) && deepEqual(a[key], b[key]))
}
function codeOf(response) { return response.json && typeof response.json.code === 'number' ? response.json.code : null }
function dataOf(response) { return response.json && response.json.data ? response.json.data : {} }


async function setup(){
 verifyIsolation(); const appId=envVar(BACKEND,'WECHAT_APP_ID'),secret=envVar(BACKEND,'JWT_SECRET'); prepare(appId);
 const owner=mint(secret,appId,{type:'user',role:'OWNER',subject:OWNER});
 const shopA=mint(secret,appId,{type:'staff_account',role:'MERCHANT',subject:MERCHANT_STAFF_A,appId:'merchant-account',merchantId:SHOP_A});
 const shopB=mint(secret,appId,{type:'staff_account',role:'MERCHANT',subject:MERCHANT_STAFF_B,appId:'merchant-account',merchantId:SHOP_B});
 const techA=mint(secret,appId,{type:'staff_account',role:'TECHNICIAN',subject:TECH_A,appId,merchantId:SHOP_A,bindingId:BIND_A});
 const techB=mint(secret,appId,{type:'staff_account',role:'TECHNICIAN',subject:TECH_B,appId,merchantId:SHOP_A,bindingId:BIND_B});
 const techF=mint(secret,appId,{type:'staff_account',role:'TECHNICIAN',subject:TECH_FOREIGN,appId,merchantId:SHOP_B,bindingId:BIND_FOREIGN});
 try {
 const confirm=await api('POST','/api/check/pickup/confirm',owner.token,{key:randomUUID(),body:{order_id:ORDER_POSITIVE,decision:'CONFIRM'}});
 if(confirm.status!==200)throw Error('Real owner confirmation failed '+confirm.status);
 const assigned=await api('POST',`/api/merchant/orders/${ORDER_POSITIVE}/assign`,shopA.token,{key:randomUUID(),body:{technician_id:TECH_A}});
 if(assigned.status!==200)throw Error('Real assignment failed '+assigned.status);
 const accepted=await api('POST',`/api/tech/orders/${ORDER_POSITIVE}/accept`,techA.token,{key:randomUUID(),body:{}});
 if(accepted.status!==200)throw Error('Real acceptance failed '+accepted.status);
 return {owner,shopA,shopB,techA,techB,techF,order:ORDER_POSITIVE};
 } catch(error) {sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id IN (${[owner,shopA,shopB,techA,techB,techF].map(v=>"'"+v.jti+"'").join(',')});`); throw error;}
}
module.exports={prepare,mint,bindToken,jwt,sha256,envVar,BACKEND,verifyIsolation,setup,sql,api,check,dataOf,codeOf,results,deepEqual,docker,ORDER_POSITIVE,ORDER_NO_CHECKIN,ORDER_NO_CONFIRM,ORDER_DISPUTE,TECH_A,BIND_A,randomUUID};
if(require.main===module)setup().then(s=>{require('node:fs').writeFileSync('.cache/a6-ui-sessions.json',JSON.stringify(s));console.log('Synthetic A6 sessions prepared; real confirmation/dispatch/accept passed; credentials withheld')}).catch(e=>{console.error(e.message);process.exitCode=1});
