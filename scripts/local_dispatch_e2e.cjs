// Opt-in A5.5 local end-to-end acceptance: real backend container + real isolated MySQL.
// Synthetic owner/merchant/technician identities only. Sessions are a documented test
// bridge (minted with the local JWT secret and written to auth_session), NOT real
// WeChat/SMS login and not evidence of real-device acceptance.
//
// Usage: node scripts/local_dispatch_e2e.cjs --allow-local-test-writes
const path = require('node:path')
const { spawnSync } = require('node:child_process')
const { randomUUID, randomBytes, createHmac } = require('node:crypto')

if (!process.argv.includes('--allow-local-test-writes')) {
  console.error('Requires --allow-local-test-writes; only the isolated local database is supported.')
  process.exit(2)
}

const MYSQL = 'vehicle-auth-local-mysql-1'
const BACKEND = 'vehicle-auth-local-backend'
const ORIGIN = 'http://127.0.0.1:18080'
const MYSQL_CMD = 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE" --batch --skip-column-names'

// Synthetic identifiers, far from any real or fixture range.
const SHOP_A = 9201201, SHOP_B = 9201202
const MERCHANT_STAFF_A = 9201201, MERCHANT_STAFF_B = 9201202
const TECH_A = 9201231, TECH_B = 9201232, TECH_FOREIGN = 9201233
const BIND_A = 9201331, BIND_B = 9201332, BIND_FOREIGN = 9201333
const OWNER = 9201290, VEHICLE = 9201290, SLOT = 9201301
const ORDER_POSITIVE = 9201401, ORDER_NO_CHECKIN = 9201402, ORDER_NO_CONFIRM = 9201403, ORDER_DISPUTE = 9201404
const PROJECT_SNAPSHOT = JSON.stringify({ project_name: '合成保养', service_content: 'synthetic', phone: 'private', vin: 'private' })
const APPOINTMENT_SNAPSHOT = JSON.stringify({ slot_id: SLOT, starts_at: '2026-10-08T02:00:00Z', verify_code: 'private' })

function docker(args, input) {
  const result = spawnSync('docker', args, { input, encoding: 'utf8', windowsHide: true })
  if (result.error) throw new Error('Local database operation failed: ' + result.error.message)
  if (result.status !== 0) throw new Error('Local database operation failed: ' + (result.stderr || '').trim())
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
function verifyIsolation() {
  const project = docker(['inspect', MYSQL, '--format', '{{index .Config.Labels "com.docker.compose.project"}}'])
  if (project !== 'vehicle-auth-local') throw new Error('Refusing non-test Docker project: ' + project)
  const image = docker(['inspect', BACKEND, '--format', '{{.Config.Image}}'])
  const columns = sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND (table_name='technician_assignment' AND column_name IN ('assigned_by','accepted_at') OR table_name='pickup_check' AND column_name='dispute_reason');")
  if (columns !== '3') throw new Error('A5 requires V011 and V012 applied to the isolated database (found ' + columns + '/3 columns)')
  const disputes = sql("SELECT (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('order_dispute','order_dispute_record')), (SELECT column_comment FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='pickup_check' AND column_name='owner_confirm');").split('	')
  if (disputes[0] !== '2' || !disputes[1].includes('3')) throw new Error('A5.6 requires V013 applied to the isolated database (found ' + disputes[0] + '/2 tables)')
  return image
}
function prepare(appId) {
  sql(`
SET @synthetic_orders='${[ORDER_POSITIVE, ORDER_NO_CHECKIN, ORDER_NO_CONFIRM, ORDER_DISPUTE].join(',')}';
DELETE FROM audit_log WHERE resource_type='technician_assignment' AND resource_id IN (SELECT id FROM technician_assignment WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE}));
DELETE FROM audit_log WHERE resource_type='pickup_check' AND resource_id IN (SELECT id FROM pickup_check WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE}));
DELETE FROM order_dispute_record WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM order_dispute WHERE order_id IN (${ORDER_POSITIVE},${ORDER_NO_CHECKIN},${ORDER_NO_CONFIRM},${ORDER_DISPUTE});
DELETE FROM audit_log WHERE action LIKE 'ORDER_DISPUTE%';
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
 (${MERCHANT_STAFF_A},${SHOP_A},'MERCHANT','local-dispatch-shop-a','ACTIVE'),
 (${MERCHANT_STAFF_B},${SHOP_B},'MERCHANT','local-dispatch-shop-b','ACTIVE'),
 (${TECH_A},${SHOP_A},'TECHNICIAN','合成技师甲','ACTIVE'),
 (${TECH_B},${SHOP_A},'TECHNICIAN','合成技师乙','ACTIVE'),
 (${TECH_FOREIGN},${SHOP_B},'TECHNICIAN','合成他店技师','ACTIVE');
INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES
 (${BIND_A},'${appId}','local-dispatch-openid-a',${TECH_A}),
 (${BIND_B},'${appId}','local-dispatch-openid-b',${TECH_B}),
 (${BIND_FOREIGN},'${appId}','local-dispatch-openid-f',${TECH_FOREIGN});
INSERT INTO \`user\`(id,openid,status) VALUES(${OWNER},'local-dispatch-owner',1);
INSERT INTO vehicle(id,user_id,plate_no,model_id) VALUES(${VEHICLE},${OWNER},'合成牌A5',9100601);
INSERT INTO appointment_slot(id,merchant_id,project_id,starts_at,ends_at,capacity) VALUES(${SLOT},${SHOP_A},1,UTC_TIMESTAMP(),DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),10);
INSERT INTO \`order\`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,check_in_completed_at,owner_confirmed_at,verify_code,project_snapshot,appointment_snapshot)
 VALUES(${ORDER_POSITIVE},'synthetic-a5-positive',${OWNER},${VEHICLE},${SHOP_A},128.00,128.00,'RECEIVED',${SLOT},UTC_TIMESTAMP(),NULL,'246813',
        CAST('${PROJECT_SNAPSHOT}' AS JSON),CAST('${APPOINTMENT_SNAPSHOT}' AS JSON));
INSERT INTO \`order\`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,check_in_completed_at,owner_confirmed_at,verify_code,project_snapshot,appointment_snapshot)
 VALUES(${ORDER_NO_CHECKIN},'synthetic-a5-no-checkin',${OWNER},${VEHICLE},${SHOP_A},128.00,128.00,'RECEIVED',${SLOT},NULL,NULL,'246814',
        CAST('${PROJECT_SNAPSHOT}' AS JSON),CAST('${APPOINTMENT_SNAPSHOT}' AS JSON));
INSERT INTO \`order\`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,check_in_completed_at,owner_confirmed_at,verify_code,project_snapshot,appointment_snapshot)
 VALUES(${ORDER_NO_CONFIRM},'synthetic-a5-no-confirm',${OWNER},${VEHICLE},${SHOP_A},128.00,128.00,'RECEIVED',${SLOT},UTC_TIMESTAMP(),NULL,'246815',
        CAST('${PROJECT_SNAPSHOT}' AS JSON),CAST('${APPOINTMENT_SNAPSHOT}' AS JSON));
INSERT INTO pickup_check(order_id,merchant_id,staff_id,owner_confirm,mileage,mileage_source,mileage_baseline,fuel_level,damage_status,arrived_at)
 VALUES(${ORDER_POSITIVE},${SHOP_A},${MERCHANT_STAFF_A},0,52000,'MANUAL',CAST('{"source":"VEHICLE","mileage":51000}' AS JSON),'HALF','NONE',UTC_TIMESTAMP());
INSERT INTO pickup_check(order_id,merchant_id,staff_id,owner_confirm,mileage,mileage_source,mileage_baseline,fuel_level,damage_status,arrived_at)
 VALUES(${ORDER_NO_CONFIRM},${SHOP_A},${MERCHANT_STAFF_A},0,52000,'MANUAL',CAST('{"source":"VEHICLE","mileage":51000}' AS JSON),'HALF','NONE',UTC_TIMESTAMP());
INSERT INTO \`order\`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,slot_id,check_in_completed_at,owner_confirmed_at,verify_code,project_snapshot,appointment_snapshot)
 VALUES(${ORDER_DISPUTE},'synthetic-a5-dispute',${OWNER},${VEHICLE},${SHOP_A},128.00,128.00,'RECEIVED',${SLOT},UTC_TIMESTAMP(),NULL,'246816',
        CAST('${PROJECT_SNAPSHOT}' AS JSON),CAST('${APPOINTMENT_SNAPSHOT}' AS JSON));
INSERT INTO pickup_check(order_id,merchant_id,staff_id,owner_confirm,mileage,mileage_source,mileage_baseline,fuel_level,damage_status,arrived_at)
 VALUES(${ORDER_DISPUTE},${SHOP_A},${MERCHANT_STAFF_A},0,52000,'MANUAL',CAST('{"source":"VEHICLE","mileage":51000}' AS JSON),'HALF','NONE',UTC_TIMESTAMP());
COMMIT;
`)
}

// ---- Synthetic sessions (documented test bridge) --------------------------------
function mint(secret, appId, spec) {
  const jti = randomUUID(), now = Math.floor(Date.now() / 1000)
  const claims = { iss: 'vehicle-health-manager', sub: String(spec.subject), subject_type: spec.type, role: spec.role, jti, iat: now, exp: now + 900 }
  if (spec.appId) claims.app_id = spec.appId
  if (spec.merchantId) claims.merchant_id = spec.merchantId
  if (spec.bindingId) claims.binding_id = spec.bindingId
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url')
  const input = `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode(claims)}`
  const token = `${input}.${createHmac('sha256', secret).update(input).digest('base64url')}`
  sql(`INSERT INTO auth_session(id,subject_type,subject_id,role,app_id,binding_id,merchant_id,refresh_hash,expires_at) VALUES('${jti}','${spec.type}',${spec.subject},'${spec.role}','${spec.appId || 'local-dispatch'}',${spec.bindingId || 'NULL'},${spec.merchantId || 'NULL'},'${randomBytes(32).toString('hex')}',DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE));`)
  return { token, jti }
}

// ---- HTTP ------------------------------------------------------------------------
async function api(method, target, token, options = {}) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  if (options.key) headers['Idempotency-Key'] = options.key
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  const response = await fetch(ORIGIN + target, { method, headers, body: options.body === undefined ? undefined : JSON.stringify(options.body), redirect: 'manual' })
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

async function main() {
  const appId = envVar(BACKEND, 'WECHAT_APP_ID')
  const secret = envVar(BACKEND, 'JWT_SECRET')
  if (secret.length < 32) throw new Error('Local JWT configuration is required')
  const image = verifyIsolation()
  console.log(`Local dispatch acceptance against ${image}; synthetic identities only; credentials withheld`)

  const health = await fetch(ORIGIN + '/actuator/health').then(r => r.json()).catch(() => null)
  if (!health || health.status !== 'UP') throw new Error('Local backend is not healthy at ' + ORIGIN)

  prepare(appId)
  const owner = mint(secret, appId, { type: 'user', role: 'OWNER', subject: OWNER })
  const shopA = mint(secret, appId, { type: 'staff_account', role: 'MERCHANT', subject: MERCHANT_STAFF_A, appId: 'merchant-account', merchantId: SHOP_A })
  const shopB = mint(secret, appId, { type: 'staff_account', role: 'MERCHANT', subject: MERCHANT_STAFF_B, appId: 'merchant-account', merchantId: SHOP_B })
  const techA = mint(secret, appId, { type: 'staff_account', role: 'TECHNICIAN', subject: TECH_A, appId, merchantId: SHOP_A, bindingId: BIND_A })
  const techB = mint(secret, appId, { type: 'staff_account', role: 'TECHNICIAN', subject: TECH_B, appId, merchantId: SHOP_A, bindingId: BIND_B })
  const techF = mint(secret, appId, { type: 'staff_account', role: 'TECHNICIAN', subject: TECH_FOREIGN, appId, merchantId: SHOP_B, bindingId: BIND_FOREIGN })

  // Real A4 confirmation closes the precondition gap before A5 dispatch.
  const confirm = await api('POST', '/api/check/pickup/confirm', owner.token, { key: randomUUID(), body: { order_id: ORDER_POSITIVE, decision: 'CONFIRM' } })
  check('A4 车主确认真实接口返回 200', confirm.status === 200, `status=${confirm.status} ${confirm.text}`)
  check('A4 确认响应 no-store', confirm.noStore)
  const confirmedRow = sql(`SELECT owner_confirm, confirm_at IS NOT NULL FROM pickup_check WHERE order_id=${ORDER_POSITIVE};`).split('\t')
  const orderConfirmed = sql(`SELECT owner_confirmed_at IS NOT NULL FROM \`order\` WHERE id=${ORDER_POSITIVE};`)
  check('A4 确认写库（owner_confirm=1 且 confirm_at、owner_confirmed_at 有值）', confirmedRow[0] === '1' && confirmedRow[1] === '1' && orderConfirmed === '1', JSON.stringify({ confirmedRow, orderConfirmed }))

  // Precondition negatives on the real backend.
  const noCheckin = await api('POST', `/api/merchant/orders/${ORDER_NO_CHECKIN}/assign`, shopA.token, { key: randomUUID(), body: { technician_id: TECH_A } })
  check('缺接车检查派工 409/43001', noCheckin.status === 409 && codeOf(noCheckin) === 43001, `status=${noCheckin.status} code=${codeOf(noCheckin)}`)
  const noConfirm = await api('POST', `/api/merchant/orders/${ORDER_NO_CONFIRM}/assign`, shopA.token, { key: randomUUID(), body: { technician_id: TECH_A } })
  check('车主未确认派工 409/43003', noConfirm.status === 409 && codeOf(noConfirm) === 43003, `status=${noConfirm.status} code=${codeOf(noConfirm)}`)

  // Candidate scope.
  const candidates = await api('GET', '/api/merchant/technicians?page=1&page_size=20', shopA.token)
  const labels = (dataOf(candidates).items || []).map(row => row.label)
  check('本店候选返回 200 且含甲/乙、不含他店技师', candidates.status === 200 && labels.includes('合成技师甲') && labels.includes('合成技师乙') && !labels.includes('合成他店技师'), JSON.stringify(labels))
  check('候选响应 no-store', candidates.noStore)
  const foreignCandidates = await api('GET', '/api/merchant/technicians?page=1&page_size=20', shopB.token)
  const foreignLabels = (dataOf(foreignCandidates).items || []).map(row => row.label)
  check('他店候选只见本店技师', foreignCandidates.status === 200 && foreignLabels.includes('合成他店技师') && !foreignLabels.includes('合成技师甲'), JSON.stringify(foreignLabels))

  // Role separation using the real JWT validation chain.
  check('技师 JWT 访问商家候选 403', (await api('GET', '/api/merchant/technicians', techA.token)).status === 403)
  check('车主 JWT 访问技师工单 403', (await api('GET', '/api/tech/orders', owner.token)).status === 403)
  check('商家 JWT 访问技师工单 403', (await api('GET', '/api/tech/orders', shopA.token)).status === 403)
  check('无令牌访问技师工单 401', (await api('GET', '/api/tech/orders', null)).status === 401)

  // First dispatch.
  const assignKey = randomUUID()
  const assign = await api('POST', `/api/merchant/orders/${ORDER_POSITIVE}/assign`, shopA.token, { key: assignKey, body: { technician_id: TECH_A } })
  const assignData = dataOf(assign)
  check('首次派工 200 且 changed=true、ASSIGNED/RECEIVED', assign.status === 200 && assignData.changed === true && assignData.assignment_status === 'ASSIGNED' && assignData.order_status === 'RECEIVED', JSON.stringify(assignData))
  check('派工响应 no-store', assign.noStore)
  const orderAfterAssign = sql(`SELECT status, assigned_at IS NOT NULL FROM \`order\` WHERE id=${ORDER_POSITIVE};`).split('\t')
  check('派工后订单仍 RECEIVED 且写 assigned_at', orderAfterAssign[0] === 'RECEIVED' && orderAfterAssign[1] === '1', JSON.stringify(orderAfterAssign))

  const merchantDetail = await api('GET', `/api/merchant/orders/${ORDER_POSITIVE}/assignment`, shopA.token)
  check('商家派工详情 200 且显示技师甲', merchantDetail.status === 200 && dataOf(merchantDetail).assignment?.technician_label === '合成技师甲', JSON.stringify(dataOf(merchantDetail)))
  check('他店商家读本店派工 404', (await api('GET', `/api/merchant/orders/${ORDER_POSITIVE}/assignment`, shopB.token)).status === 404)

  const listA = await api('GET', '/api/tech/orders?page=1&page_size=20', techA.token)
  check('技师甲工单列表 200 且含目标单', listA.status === 200 && (dataOf(listA).items || []).some(row => row.order_id === ORDER_POSITIVE), JSON.stringify(dataOf(listA)))

  const detailA = await api('GET', `/api/tech/orders/${ORDER_POSITIVE}`, techA.token)
  const projection = detailA.text
  const leaks = ['user_id', 'vehicle_id', 'phone', 'vin', 'plate_no', 'verify_code', 'private', 'payment_summary', 'technician_id'].filter(field => projection.includes(field))
  check('技师工单详情 200 且 can_accept=true', detailA.status === 200 && dataOf(detailA).can_accept === true, JSON.stringify(dataOf(detailA)))
  check('技师详情 no-store', detailA.noStore)
  check('技师投影不含车主/车辆/核销/私有字段', leaks.length === 0, leaks.join(','))

  const detailB = await api('GET', `/api/tech/orders/${ORDER_POSITIVE}`, techB.token)
  const listB = await api('GET', '/api/tech/orders?page=1&page_size=20', techB.token)
  check('另一本店技师详情 404', detailB.status === 404, `status=${detailB.status}`)
  check('另一本店技师列表为空', listB.status === 200 && Number(dataOf(listB).total) === 0, JSON.stringify(dataOf(listB)))
  check('他店技师详情 404', (await api('GET', `/api/tech/orders/${ORDER_POSITIVE}`, techF.token)).status === 404)

  // Idempotency and duplicate semantics.
  const assignReplay = await api('POST', `/api/merchant/orders/${ORDER_POSITIVE}/assign`, shopA.token, { key: assignKey, body: { technician_id: TECH_A } })
  check('同键同体重放返回原成功快照', assignReplay.status === 200 && deepEqual(assignReplay.json, assign.json), `status=${assignReplay.status} first=${assign.text} replay=${assignReplay.text}`)
  const reassignSameTech = await api('POST', `/api/merchant/orders/${ORDER_POSITIVE}/assign`, shopA.token, { key: randomUUID(), body: { technician_id: TECH_A } })
  check('新键向同一技师重复派工返回已有派工 changed=false', reassignSameTech.status === 200 && dataOf(reassignSameTech).changed === false, `status=${reassignSameTech.status} ${reassignSameTech.text}`)
  const reassign = await api('POST', `/api/merchant/orders/${ORDER_POSITIVE}/assign`, shopA.token, { key: randomUUID(), body: { technician_id: TECH_B } })
  check('新键换技师派工 409/40905', reassign.status === 409 && codeOf(reassign) === 40905, `status=${reassign.status} code=${codeOf(reassign)}`)

  // Acceptance.
  const acceptKey = randomUUID()
  const accept = await api('POST', `/api/tech/orders/${ORDER_POSITIVE}/accept`, techA.token, { key: acceptKey, body: {} })
  const acceptData = dataOf(accept)
  check('本人接单 200 且 ACCEPTED/IN_SERVICE', accept.status === 200 && acceptData.changed === true && acceptData.assignment_status === 'ACCEPTED' && acceptData.order_status === 'IN_SERVICE', JSON.stringify(acceptData))
  check('接单响应 no-store', accept.noStore)
  const acceptReplay = await api('POST', `/api/tech/orders/${ORDER_POSITIVE}/accept`, techA.token, { key: acceptKey, body: {} })
  check('同键重放接单返回原成功快照', acceptReplay.status === 200 && deepEqual(acceptReplay.json, accept.json), `status=${acceptReplay.status} first=${accept.text} replay=${acceptReplay.text}`)
  const acceptNewKey = await api('POST', `/api/tech/orders/${ORDER_POSITIVE}/accept`, techA.token, { key: randomUUID(), body: {} })
  check('新键重复接单 changed=false', acceptNewKey.status === 200 && dataOf(acceptNewKey).changed === false, JSON.stringify(dataOf(acceptNewKey)))
  check('商家 JWT 不能接单 403', (await api('POST', `/api/tech/orders/${ORDER_POSITIVE}/accept`, shopA.token, { key: randomUUID(), body: {} })).status === 403)

  // Database evidence.
  const evidence = sql(`SELECT
 (SELECT COUNT(*) FROM technician_assignment WHERE order_id=${ORDER_POSITIVE}),
 (SELECT status FROM \`order\` WHERE id=${ORDER_POSITIVE}),
 (SELECT COUNT(*) FROM order_status_transition WHERE order_id=${ORDER_POSITIVE}),
 (SELECT COUNT(*) FROM audit_log WHERE resource_type='technician_assignment'),
 (SELECT COUNT(*) FROM audit_log WHERE resource_type='order' AND resource_id=${ORDER_POSITIVE} AND action='ORDER_TECH_ACCEPT'),
 (SELECT assigned_at IS NOT NULL FROM \`order\` WHERE id=${ORDER_POSITIVE}),
 (SELECT accepted_at IS NOT NULL FROM technician_assignment WHERE order_id=${ORDER_POSITIVE}),
 (SELECT COUNT(*) FROM repair_protection WHERE order_id=${ORDER_POSITIVE});`).split('\t')
  check('一条派工、订单 IN_SERVICE、一次迁移、派工+接单各一次审计', evidence[0] === '1' && evidence[1] === 'IN_SERVICE' && evidence[2] === '1' && evidence[3] === '1' && evidence[4] === '1', JSON.stringify(evidence))
  check('assigned_at 与 accepted_at 均已写入', evidence[5] === '1' && evidence[6] === '1', JSON.stringify(evidence))
  check('缺防护不阻断 A5（repair_protection 为 0）', evidence[7] === '0', JSON.stringify(evidence))
  const detailAfter = await api('GET', `/api/tech/orders/${ORDER_POSITIVE}`, techA.token)
  check('接单后 can_accept=false（IN_SERVICE 不等于施工完成）', dataOf(detailAfter).can_accept === false, JSON.stringify(dataOf(detailAfter)))

  // ---- A5.6 dispute record, owner review and the resume of the disputed order ----------
  // The owner raises a dispute on the real A4 confirm endpoint; the dispute row must exist.
  const raiseKey = randomUUID()
  const raise = await api('POST', '/api/check/pickup/confirm', owner.token, { key: raiseKey, body: { order_id: ORDER_DISPUTE, decision: 'DISPUTE', reason: '合成异议：交付前发现漆面问题' } })
  check('车主提出异议 200', raise.status === 200, `status=${raise.status} ${raise.text}`)
  check('异议响应 no-store', raise.noStore)
  const sheetDispute = sql(`SELECT owner_confirm FROM pickup_check WHERE order_id=${ORDER_DISPUTE};`)
  const orderDispute = sql(`SELECT status FROM \`order\` WHERE id=${ORDER_DISPUTE};`)
  const disputeRow = sql(`SELECT status, from_status, reason, opened_by IS NOT NULL FROM order_dispute WHERE order_id=${ORDER_DISPUTE};`).split('\t')
  check('异议写库：争议单 OPEN/RECEIVED、接车单 owner_confirm=2、订单 DISPUTED',
    disputeRow[0] === 'OPEN' && disputeRow[1] === 'RECEIVED' && disputeRow[3] === '1' && sheetDispute === '2' && orderDispute === 'DISPUTED',
    JSON.stringify({ disputeRow, sheetDispute, orderDispute }))
  const raiseReplay = await api('POST', '/api/check/pickup/confirm', owner.token, { key: raiseKey, body: { order_id: ORDER_DISPUTE, decision: 'DISPUTE', reason: '合成异议：交付前发现漆面问题' } })
  check('异议同键重放不新建第二条争议单', raiseReplay.status === 200 && Number(sql(`SELECT COUNT(*) FROM order_dispute WHERE order_id=${ORDER_DISPUTE};`)) === 1, `status=${raiseReplay.status} count=${sql(`SELECT COUNT(*) FROM order_dispute WHERE order_id=${ORDER_DISPUTE};`)}`)

  // An open dispute blocks both dispatch and acceptance with the dedicated code.
  const assignDisputed = await api('POST', `/api/merchant/orders/${ORDER_DISPUTE}/assign`, shopA.token, { key: randomUUID(), body: { technician_id: TECH_A } })
  check('争议未解决时派工 409/43007', assignDisputed.status === 409 && codeOf(assignDisputed) === 43007, `status=${assignDisputed.status} code=${codeOf(assignDisputed)}`)
  const acceptDisputed = await api('POST', `/api/tech/orders/${ORDER_DISPUTE}/accept`, techA.token, { key: randomUUID(), body: {} })
  check('争议未解决时接单被拒（404/409）', acceptDisputed.status === 404 || acceptDisputed.status === 409, `status=${acceptDisputed.status} code=${codeOf(acceptDisputed)}`)

  // R4: the owner cannot accept a resolution the merchant has not explained yet.
  const earlyReview = await api('POST', '/api/check/pickup/dispute/review', owner.token, { key: randomUUID(), body: { order_id: ORDER_DISPUTE, decision: 'ACCEPT' } })
  check('商家未提交处理记录时复核 409/43008', earlyReview.status === 409 && codeOf(earlyReview) === 43008, `status=${earlyReview.status} code=${codeOf(earlyReview)}`)

  // Role separation on the new endpoints, validated by the real JWT chain.
  check('商家 JWT 不能复核争议 403', (await api('POST', '/api/check/pickup/dispute/review', shopA.token, { key: randomUUID(), body: { order_id: ORDER_DISPUTE, decision: 'ACCEPT', note: '越权' } })).status === 403)
  check('技师 JWT 不能复核争议 403', (await api('POST', '/api/check/pickup/dispute/review', techA.token, { key: randomUUID(), body: { order_id: ORDER_DISPUTE, decision: 'ACCEPT', note: '越权' } })).status === 403)
  check('车主 JWT 不能提交商家处理记录 403', (await api('POST', `/api/merchant/orders/${ORDER_DISPUTE}/dispute/handle`, owner.token, { key: randomUUID(), body: { note: '越权' } })).status === 403)
  const foreignHandle = await api('POST', `/api/merchant/orders/${ORDER_DISPUTE}/dispute/handle`, shopB.token, { key: randomUUID(), body: { note: '越权' } })
  check('他店商家不能提交处理记录 404', foreignHandle.status === 404, `status=${foreignHandle.status}`)
  const blankHandle = await api('POST', `/api/merchant/orders/${ORDER_DISPUTE}/dispute/handle`, shopA.token, { key: randomUUID(), body: { note: '   ' } })
  check('空说明的处理记录 400', blankHandle.status === 400, `status=${blankHandle.status}`)

  // Merchant handling records append, replay by key, and never overwrite history.
  const handleKey = randomUUID()
  const handle = await api('POST', `/api/merchant/orders/${ORDER_DISPUTE}/dispute/handle`, shopA.token, { key: handleKey, body: { note: '已安排返工并重新质检' } })
  const handleData = dataOf(handle)
  check('商家提交处理记录 200 且 record_count=1/HANDLE/OPEN',
    handle.status === 200 && handleData.record_count === 1 && handleData.last_action === 'HANDLE' && handleData.dispute_status === 'OPEN' && handleData.order_status === 'DISPUTED' && handleData.changed === true,
    JSON.stringify(handleData))
  check('处理记录响应 no-store', handle.noStore)
  check('处理记录响应不含任何身份 ID', !/actor_id|staff_id|owner_id/.test(handle.text), handle.text)
  const handleReplay = await api('POST', `/api/merchant/orders/${ORDER_DISPUTE}/dispute/handle`, shopA.token, { key: handleKey, body: { note: '已安排返工并重新质检' } })
  check('同键同体重放处理记录返回原成功快照', handleReplay.status === 200 && deepEqual(handleReplay.json, handle.json), `status=${handleReplay.status} first=${handle.text} replay=${handleReplay.text}`)
  const handle2 = await api('POST', `/api/merchant/orders/${ORDER_DISPUTE}/dispute/handle`, shopA.token, { key: randomUUID(), body: { note: '补充：更换配件后复检通过' } })
  check('新键追加第二条处理记录 record_count=2', handle2.status === 200 && dataOf(handle2).record_count === 2 && dataOf(handle2).last_action === 'HANDLE', JSON.stringify(dataOf(handle2)))

  // The pickup sheet projects the timeline without any actor identity and exposes can_review.
  const disputeDetail = await api('GET', `/api/check/pickup/${ORDER_DISPUTE}`, owner.token)
  const disputeView = (dataOf(disputeDetail).dispute) || {}
  const timeline = disputeView.records || []
  check('车主读接车单 200 且争议时间线两条 HANDLE', disputeDetail.status === 200 && disputeView.status === 'OPEN' && disputeView.from_status === 'RECEIVED' && timeline.length === 2 && timeline.every(item => item.action === 'HANDLE') && disputeView.can_review === true, JSON.stringify(disputeView))
  check('接车单投影不含车主/商家员工身份 ID', !disputeDetail.text.includes(String(OWNER)) && !disputeDetail.text.includes(String(MERCHANT_STAFF_A)), 'identity leak')
  check('争议时间线只投影动作/说明/时间', timeline.every(item => Object.keys(item).sort().join(',') === 'action,created_at,note'), JSON.stringify(timeline[0] || {}))

  // Owner rejects first: the dispute stays open and the order stays blocked.
  const reject = await api('POST', '/api/check/pickup/dispute/review', owner.token, { key: randomUUID(), body: { order_id: ORDER_DISPUTE, decision: 'REJECT', note: '仍未说明返工照片' } })
  check('车主不接受复核 200 且保持 OPEN/DISPUTED', reject.status === 200 && dataOf(reject).dispute_status === 'OPEN' && dataOf(reject).order_status === 'DISPUTED' && dataOf(reject).record_count === 3 && dataOf(reject).changed === true, JSON.stringify(dataOf(reject)))
  const stillBlocked = await api('POST', `/api/merchant/orders/${ORDER_DISPUTE}/assign`, shopA.token, { key: randomUUID(), body: { technician_id: TECH_A } })
  check('不接受后仍阻断派工 409/43007', stillBlocked.status === 409 && codeOf(stillBlocked) === 43007, `status=${stillBlocked.status} code=${codeOf(stillBlocked)}`)

  // Owner accepts: the order resumes its pre-dispute status and the sheet records the outcome.
  const acceptKey2 = randomUUID()
  const acceptReview = await api('POST', '/api/check/pickup/dispute/review', owner.token, { key: acceptKey2, body: { order_id: ORDER_DISPUTE, decision: 'ACCEPT', note: '已确认处理结果' } })
  const acceptReviewData = dataOf(acceptReview)
  check('车主接受复核 200 且恢复 RECEIVED/RESOLVED/owner_confirm=3',
    acceptReview.status === 200 && acceptReviewData.dispute_status === 'RESOLVED' && acceptReviewData.order_status === 'RECEIVED' && acceptReviewData.owner_confirm === 3 && acceptReviewData.last_action === 'ACCEPT' && acceptReviewData.changed === true,
    JSON.stringify(acceptReviewData))
  check('复核响应 no-store', acceptReview.noStore)
  const resumeEvidence = sql(`SELECT
   (SELECT status FROM order_dispute WHERE order_id=${ORDER_DISPUTE}),
   (SELECT resolved_at IS NOT NULL AND resolved_by IS NOT NULL FROM order_dispute WHERE order_id=${ORDER_DISPUTE}),
   (SELECT owner_confirm FROM pickup_check WHERE order_id=${ORDER_DISPUTE}),
   (SELECT status FROM \`order\` WHERE id=${ORDER_DISPUTE}),
   (SELECT COUNT(*) FROM order_status_transition WHERE order_id=${ORDER_DISPUTE} AND action='ORDER_DISPUTE_RESOLVE'),
   (SELECT owner_confirmed_at IS NOT NULL FROM \`order\` WHERE id=${ORDER_DISPUTE});`).split('\t')
  check('恢复写库：RESOLVED+时间/人、owner_confirm=3、订单 RECEIVED、一次 ORDER_DISPUTE_RESOLVE 迁移',
    resumeEvidence[0] === 'RESOLVED' && resumeEvidence[1] === '1' && resumeEvidence[2] === '3' && resumeEvidence[3] === 'RECEIVED' && resumeEvidence[4] === '1' && resumeEvidence[5] === '1',
    JSON.stringify(resumeEvidence))
  const acceptReplay2 = await api('POST', '/api/check/pickup/dispute/review', owner.token, { key: acceptKey2, body: { order_id: ORDER_DISPUTE, decision: 'ACCEPT', note: '已确认处理结果' } })
  check('同键重放复核返回原成功快照', acceptReplay2.status === 200 && deepEqual(acceptReplay2.json, acceptReview.json), `status=${acceptReplay2.status} first=${acceptReview.text} replay=${acceptReplay2.text}`)
  const reviewAfterResolved = await api('POST', '/api/check/pickup/dispute/review', owner.token, { key: randomUUID(), body: { order_id: ORDER_DISPUTE, decision: 'ACCEPT', note: '重复复核' } })
  check('已解决后再复核 409/40905', reviewAfterResolved.status === 409 && codeOf(reviewAfterResolved) === 40905, `status=${reviewAfterResolved.status} code=${codeOf(reviewAfterResolved)}`)

  // The resumed order completes the dispatch chain it was blocked from.
  const resumeAssign = await api('POST', `/api/merchant/orders/${ORDER_DISPUTE}/assign`, shopA.token, { key: randomUUID(), body: { technician_id: TECH_B } })
  check('恢复后可正常派工 200/ASSIGNED', resumeAssign.status === 200 && dataOf(resumeAssign).assignment_status === 'ASSIGNED' && dataOf(resumeAssign).order_status === 'RECEIVED', JSON.stringify(dataOf(resumeAssign)))
  const resumeAccept = await api('POST', `/api/tech/orders/${ORDER_DISPUTE}/accept`, techB.token, { key: randomUUID(), body: {} })
  check('恢复后技师可正常接单 200/IN_SERVICE', resumeAccept.status === 200 && dataOf(resumeAccept).order_status === 'IN_SERVICE', JSON.stringify(dataOf(resumeAccept)))
  const disputeAudit = sql(`SELECT
   (SELECT COUNT(*) FROM audit_log WHERE action='ORDER_DISPUTE_HANDLE' AND resource_type='order_dispute_record'),
   (SELECT COUNT(*) FROM audit_log WHERE action='ORDER_DISPUTE_REJECT'),
   (SELECT COUNT(*) FROM audit_log WHERE action='ORDER_DISPUTE_RESOLVE'),
   (SELECT COUNT(*) FROM order_dispute_record WHERE order_id=${ORDER_DISPUTE});`).split('\t')
  check('两次处理/一次不接受/一次接受的审计与记录齐备', Number(disputeAudit[0]) === 2 && disputeAudit[1] === '1' && disputeAudit[2] === '1' && disputeAudit[3] === '4', JSON.stringify(disputeAudit))
  const disputeIdempotent = sql(`SELECT COUNT(*) FROM idempotency_record WHERE request_path LIKE '%dispute%' AND response_body NOT LIKE '%actor_id%';`)
  // 两次处理、一次不接受、一次接受各一条；异议本身走 /api/check/pickup/confirm，不计入本口径。
  check('争议幂等记录的响应载荷不含身份字段且至少 4 条', Number(disputeIdempotent) >= 4, `count=${disputeIdempotent}`)
  // Projection filtering keeps HTTP clean; stored payloads are checked separately.
  const disputePayloadLeak = sql(`SELECT COUNT(*) FROM audit_log WHERE action LIKE 'ORDER_DISPUTE%' AND (CAST(before_state AS CHAR) LIKE '%actor_id%' OR CAST(after_state AS CHAR) LIKE '%actor_id%');`)
  check('争议审计的落库前后快照不含任何身份字段', disputePayloadLeak === '0', `count=${disputePayloadLeak}`)

  // Revoke synthetic sessions; leave synthetic data for manual review.
  sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id IN ('${owner.jti}','${shopA.jti}','${shopB.jti}','${techA.jti}','${techB.jti}','${techF.jti}');`)
  const revoked = await api('GET', '/api/tech/orders', techA.token)
  check('撤销合成会话后旧令牌 401', revoked.status === 401, `status=${revoked.status}`)

  const passed = results.filter(r => r.ok).length
  console.log(`\npassed: ${passed} / ${results.length}`)
  if (passed !== results.length) process.exitCode = 1
}

main().catch(error => {
  console.error('Local dispatch acceptance failed: ' + error.message)
  process.exitCode = 1
})
