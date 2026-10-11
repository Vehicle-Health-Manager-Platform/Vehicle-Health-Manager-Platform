// Opt-in synthetic identities, real HTTP/MySQL. Never evidence of real WeChat/SMS login.
const fs = require('node:fs'), { randomUUID, createHash } = require('node:crypto'), f = require('./local_service_work_fixtures.cjs')
const { isDeepStrictEqual } = require('node:util')
const { sql, docker, check } = f
const ORIGIN = 'http://127.0.0.1:18081', CONTAINER = 'vehicle-r1c-catalog-backend'
const OPERATOR = 9209101, OWNER = 9209102, SHOP = 9209103, MANAGER = 9209104
const PREFIX = '合成R1c-' + randomUUID().slice(0, 8)
const made = { projects: [], brands: [], series: [], models: [] }, tables = { projects: 'standard_project', brands: 'brand', series: 'series', models: 'model' }
let vehicle, order, slot, quote
async function call(method, path, token, body, key) {
  const headers = token ? { Authorization: 'Bearer ' + token } : {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (key) headers['Idempotency-Key'] = key
  const response = await fetch(ORIGIN + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(30000) })
  return { status: response.status, json: await response.json() }
}
function expect(name, response, status = 200) { check(name, response.status === status, 'HTTP=' + response.status); if (response.status !== status) throw Error(name + ' failed'); return response.json.data }
const fields = row => { const body = { ...row }; delete body.id; return body }
function clean() {
  if (order) { sql(`DELETE FROM order_status_transition WHERE order_id=${order}; DELETE FROM payment WHERE order_id=${order}; DELETE FROM \`order\` WHERE id=${order}`) }
  if (slot) sql(`DELETE FROM appointment_slot WHERE id=${slot}`)
  if (quote) sql(`DELETE FROM merchant_project_version WHERE merchant_project_id=${quote}; DELETE FROM merchant_project WHERE id=${quote}`)
  if (vehicle) sql(`DELETE FROM vehicle WHERE id=${vehicle} AND user_id=${OWNER}`)
  for (const kind of ['models', 'series', 'brands', 'projects']) if (made[kind].length) sql(`DELETE FROM ${tables[kind]} WHERE id IN (${made[kind].join(',')})`)
  sql(`DELETE FROM idempotency_record WHERE actor_type='operator_account' AND actor_id=${OPERATOR}; DELETE FROM idempotency_record WHERE actor_type='user' AND actor_id=${OWNER}; DELETE FROM idempotency_record WHERE actor_type='staff_account' AND actor_id=${MANAGER};
    DELETE FROM audit_log WHERE (actor_type='operator_account' AND actor_id=${OPERATOR}) OR (actor_type='user' AND actor_id=${OWNER}) OR (actor_type='staff_account' AND actor_id=${MANAGER});
    DELETE FROM auth_session WHERE (subject_type='operator_account' AND subject_id=${OPERATOR}) OR (subject_type='user' AND subject_id=${OWNER}) OR (subject_type='staff_account' AND subject_id=${MANAGER});
    DELETE FROM operator_account WHERE id=${OPERATOR} AND account='synthetic-r1c-catalog'; DELETE FROM user WHERE id=${OWNER} AND openid='synthetic-r1c-owner'; DELETE FROM staff_account WHERE id=${MANAGER} AND account='synthetic-r1c-manager'; DELETE FROM merchant WHERE id=${SHOP} AND name='合成R1c验收门店';`)
}
async function main() {
  f.verifyIsolation('r1b-staff')
  const inspected = JSON.parse(docker(['inspect', CONTAINER]))[0]
  if (inspected.Config.Image !== 'vehicle-auth/backend:r1c-catalog' || inspected.HostConfig.PortBindings['8080/tcp']?.[0]?.HostIp !== '127.0.0.1' || inspected.HostConfig.PortBindings['8080/tcp']?.[0]?.HostPort !== '18081' || !inspected.NetworkSettings.Networks['vehicle-auth-local_default']) throw Error('Only exact isolated R1c sidecar allowed')
  if (f.envVar(CONTAINER, 'CATALOG_GOVERNANCE_ENABLED') !== 'true') throw Error('R1c feature not enabled in isolated sidecar')
  if (docker(['exec', CONTAINER, 'sha256sum', '/app/app.jar']).split(/\s/)[0] !== createHash('sha256').update(fs.readFileSync('backend/target/platform-backend-0.1.0-SNAPSHOT.jar')).digest('hex')) throw Error('Runtime JAR mismatch')
  check('隔离端口镜像与JAR一致', true)
  if (sql(`SELECT (SELECT COUNT(*) FROM operator_account WHERE id=${OPERATOR})+(SELECT COUNT(*) FROM user WHERE id=${OWNER})+(SELECT COUNT(*) FROM merchant WHERE id=${SHOP})+(SELECT COUNT(*) FROM staff_account WHERE id=${MANAGER})`) !== '0') throw Error('Synthetic ID collision; refusing writes')
  sql(`INSERT INTO operator_account(id,account,password_hash,phone,can_catalog) VALUES(${OPERATOR},'synthetic-r1c-catalog','not-a-login-password','13800009901',1);
    INSERT INTO user(id,openid,status) VALUES(${OWNER},'synthetic-r1c-owner',1);
    INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(${SHOP},2,'合成R1c验收门店','合成验收地址',1);
    INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(${MANAGER},${SHOP},'MERCHANT','synthetic-r1c-manager','ACTIVE');`)
  try {
    const secret = f.envVar(CONTAINER, 'JWT_SECRET'), app = f.envVar(CONTAINER, 'WECHAT_APP_ID')
    const op = f.mint(secret, app, { type: 'operator_account', role: 'OPERATOR', subject: OPERATOR, appId: 'operator-account' })
    const owner = f.mint(secret, app, { type: 'user', role: 'OWNER', subject: OWNER })
    const manager = f.mint(secret, app, { type: 'staff_account', role: 'MERCHANT', subject: MANAGER, appId: 'merchant-account', merchantId: SHOP })
    expect('匿名拒绝', await call('GET', '/api/admin/catalog/brands'), 401)
    expect('车主不能维护目录', await call('GET', '/api/admin/catalog/brands', owner.token), 403)
    expect('店长不能维护目录', await call('GET', '/api/admin/catalog/brands', manager.token), 403)
    expect('未知目录拒绝', await call('GET', '/api/admin/catalog/unknown', op.token), 400)
    expect('重复查询参数拒绝', await call('GET', '/api/admin/catalog/brands?page=1&page=2', op.token), 400)
    const create = async (kind, body) => { const r = expect(kind + '真实创建', await call('POST', '/api/admin/catalog/' + kind, op.token, body, randomUUID())); made[kind].push(r.id); check(kind + '默认未发布且单层信封', r.status === 0 && r.revision === 1 && !r.data); return r }
    const update = async (kind, row, status) => expect(kind + '真实更新', await call('PUT', `/api/admin/catalog/${kind}/${row.id}`, op.token, { ...fields(row), status }, randomUUID()))
    const common = { sort_order: 0, source_note: '合法合成测试来源，非生产车型' }
    let brand = await create('brands', { ...common, name: PREFIX + '品牌' })
    let series = await create('series', { ...common, name: PREFIX + '车系', brand_id: brand.id })
    expect('未发布上级阻断子项发布', await call('PUT', `/api/admin/catalog/series/${series.id}`, op.token, { ...fields(series), status: 1 }, randomUUID()), 409)
    brand = await update('brands', brand, 1); series = await update('series', series, 1)
    let model = await create('models', { ...common, series_id: series.id, year: '2026', config_name: PREFIX + '配置', power_type: 'FUEL' })
    model = await update('models', model, 1)
    const created = expect('真实录车', await call('POST', '/api/vehicle/add', owner.token, { add_type: 4, model_id: model.id, current_mileage: 10, plate_no: '', vin: '' }, randomUUID())); vehicle = created.vehicle_id
    expect('已引用品牌不能改名', await call('PUT', `/api/admin/catalog/brands/${brand.id}`, op.token, { ...fields(brand), name: PREFIX + '改写' }, randomUUID()), 409)
    model = await update('models', model, 0)
    check('停用车型仍回显既有车辆', expect('车辆列表', await call('GET', '/api/vehicle/list', owner.token)).list.some(v => v.vehicle_id === vehicle))
    check('新录车目录隐藏停用车型', !expect('录车目录', await call('GET', '/api/model/list?series_id=' + series.id, owner.token)).list.some(v => v.id === model.id))
    expect('停用车型不能再次录车', await call('POST', '/api/vehicle/add', owner.token, { add_type: 4, model_id: model.id, current_mileage: 10, plate_no: '', vin: '' }, randomUUID()), 404)
    let project = await create('projects', { ...common, project_name: PREFIX + '项目', category: 1, service_content: '合成内容', quality_standard: '合成质检', base_price_low: '10.00', base_price_high: '20.00' })
    const path = '/api/admin/catalog/projects/' + project.id, key = randomUUID(), body = { ...fields(project), status: 1 }
    const first = await call('PUT', path, op.token, body, key); project = expect('发布项目', first)
    const replay = await call('PUT', path, op.token, body, key); check('幂等返回原单层信封', replay.status === first.status && isDeepStrictEqual(first.json, replay.json))
    expect('旧revision拒绝', await call('PUT', path, op.token, body, randomUUID()), 409)
    const quoted = expect('店长标准选品报价', await call('POST', '/api/merchant/projects', manager.token, { standard_project_id: project.id, price: '15.00', status: 1 }, randomUUID())); quote = quoted.merchant_project_id
    const start = Math.ceil((Date.now() + 3600000) / 60000) * 60000, end = start + 1800000
    const slotResult = expect('发布预约时段', await call('POST', '/api/merchant/slots', manager.token, { standard_project_id: project.id, starts_at: new Date(start).toISOString(), ends_at: new Date(end).toISOString(), capacity: 2 }, randomUUID())); slot = slotResult.slot_id
    const booked = expect('真实预约订单', await call('POST', '/api/order/create', owner.token, { merchant_project_id: quote, quote_version_id: quoted.version_id, vehicle_id: vehicle, slot_id: slot }, randomUUID())); order = booked.order_id
    const before = expect('订单快照', await call('GET', '/api/order/' + order, owner.token)).project_snapshot
    project = await update('projects', project, 0)
    expect('停用项目阻断新下单', await call('POST', '/api/order/create', owner.token, { merchant_project_id: quote, quote_version_id: quoted.version_id, vehicle_id: vehicle, slot_id: slot }, randomUUID()), 404)
    const after = expect('历史订单仍可查看', await call('GET', '/api/order/' + order, owner.token)).project_snapshot
    check('停用不重写订单快照', isDeepStrictEqual(before, after))
    expect('停用标准允许门店下架原报价', await call('POST', '/api/merchant/projects', manager.token, { standard_project_id: project.id, price: '15.00', status: 0 }, randomUUID()))
    sql(`UPDATE operator_account SET can_catalog=0 WHERE id=${OPERATOR}`)
    expect('权限撤销即时拒绝', await call('GET', '/api/admin/catalog/brands', op.token), 403)
    expect('撤销后幂等重放也拒绝', await call('PUT', path, op.token, body, key), 403)
    check('真实目录动作有审计', Number(sql(`SELECT COUNT(*) FROM audit_log WHERE actor_type='operator_account' AND actor_id=${OPERATOR} AND action='CATALOG_SAVE'`)) >= 9)
  } finally { clean() }
  check('本阶段身份与目录合成数据零残留', sql(`SELECT (SELECT COUNT(*) FROM operator_account WHERE id=${OPERATOR})+(SELECT COUNT(*) FROM user WHERE id=${OWNER})+(SELECT COUNT(*) FROM staff_account WHERE id=${MANAGER})+(SELECT COUNT(*) FROM merchant WHERE id=${SHOP})
    +(SELECT COUNT(*) FROM brand WHERE name LIKE '${PREFIX}%')+(SELECT COUNT(*) FROM series WHERE name LIKE '${PREFIX}%')+(SELECT COUNT(*) FROM model WHERE config_name LIKE '${PREFIX}%')+(SELECT COUNT(*) FROM standard_project WHERE project_name LIKE '${PREFIX}%')
    +(SELECT COUNT(*) FROM vehicle WHERE user_id=${OWNER})+(SELECT COUNT(*) FROM \`order\` WHERE user_id=${OWNER})+(SELECT COUNT(*) FROM appointment_slot WHERE merchant_id=${SHOP})+(SELECT COUNT(*) FROM merchant_project WHERE merchant_id=${SHOP})`) === '0')
  fs.writeFileSync('.cache/r1c-catalog-acceptance.json', JSON.stringify({ date: '2026-10-11', checks: f.results, formalIdentityAccepted: false }, null, 2))
  if (f.results.some(result => !result.ok)) throw Error('R1c acceptance has failed checks')
}
main().catch(e => { console.error(e.message); process.exitCode = 1 })
