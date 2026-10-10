// gstack /browse eval against the opt-in loopback harness. Business reads reach
// the real backend/MySQL; only merchant SMS login is a labelled local bridge.
return await (async () => {
  if (location.origin !== 'http://127.0.0.1:4317') throw new Error('Loopback harness required')
  const original = uni.request, checks = []
  let merchant = '', stage = 'login', failNextList = false
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
  const text = () => document.body.innerText
  const assert = (condition, label) => { if (!condition) throw new Error(label); checks.push(label) }
  const wait = async (condition, label) => { for (let i = 0; i < 120; i++) { if (condition()) return; await sleep(100) } throw new Error(`Timeout: ${label}`) }
  const button = label => [...document.querySelectorAll('uni-button')].find(element => element.textContent.trim() === label && element.getClientRects().length && !element.hasAttribute('disabled'))
  const click = async label => { await wait(() => button(label), label); button(label).click(); await sleep(150) }
  const helper = async (path, body = {}) => { const response = await fetch('/__local/' + path, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Local-Business': '1' }, body: JSON.stringify(body) }); if (!response.ok) throw new Error('Local helper unavailable'); return response.json() }
  const api = async (path, token = merchant) => { const response = await fetch(path, { headers: token ? { Authorization: token } : {} }); return { status: response.status, body: await response.json() } }
  const inputs = () => [...document.querySelectorAll('uni-input input')].filter(element => element.getClientRects().length)
  const input = (element, value) => { element.value = value; element.dispatchEvent(new Event('input', { bubbles: true })) }
  uni.request = options => {
    if (options.url.endsWith('/api/auth/merchant/login')) {
      helper('merchant', { store: 'A' }).then(data => { merchant = 'Bearer ' + data.access_token; options.success?.({ statusCode: 200, data: { code: 0, data } }) }).catch(() => options.fail?.({ errMsg: 'Synthetic merchant unavailable' }))
      return { abort() {} }
    }
    if (!options.url.startsWith('http://127.0.0.1:18080/')) throw new Error('Unexpected backend')
    if (failNextList && options.url.includes('/api/merchant/orders?')) { failNextList = false; queueMicrotask(() => options.fail?.({ errMsg: 'Local one-shot network failure' })); return { abort() {} } }
    return original({ ...options, url: options.url.replace('http://127.0.0.1:18080', location.origin) })
  }
  try {
    await wait(() => inputs().length === 3, 'merchant form')
    ;['local-quotes-merchant-A', 'synthetic-password', '123456'].forEach((value, index) => input(inputs()[index], value))
    await click('登录门店端')
    await wait(() => text().includes('门店登录成功'), 'synthetic merchant session')
    stage = 'orders'
    await click('查看本店订单')
    await wait(() => text().includes('本店订单'), 'merchant order page')
    const list = await api('/api/merchant/orders?page=1&page_size=20')
    assert(list.status === 200 && list.body.data.total > 0, '本店真实订单分页与计数')
    const first = list.body.data.items[0]
    await wait(() => document.querySelector(`[data-testid="merchant-order-${first.order_id}"]`), 'real order row')
    assert(text().includes(first.project_snapshot?.project_name || '历史订单'), '列表展示真实下单快照')
    assert(!JSON.stringify(first).includes('vehicle_id') && !JSON.stringify(first).includes('user_id'), '列表不返回客户身份和车辆ID')
    const paid = await api('/api/merchant/orders?status=PAID&page=1&page_size=20')
    await click('已支付')
    await wait(() => text().includes('已支付') && (paid.body.data.total === 0 || document.querySelector(`[data-testid="merchant-order-${paid.body.data.items[0].order_id}"]`)), 'paid filter')
    assert(paid.status === 200, '状态筛选来自真实后端')
    await click('全部')
    await wait(() => document.querySelector(`[data-testid="merchant-order-${first.order_id}"]`), 'restore all orders')
    const starts = first.appointment_snapshot?.starts_at
    if (starts) {
      const day = new Date(Date.parse(starts) + 8 * 3600000).toISOString().slice(0, 10)
      const dated = await api('/api/merchant/orders?date=' + day)
      assert(dated.status === 200 && dated.body.data.items.some(row => row.order_id === first.order_id), '北京时间预约日期匹配真实订单')
      const picker = document.querySelector('uni-picker input[type=date]')
      if (picker) { picker.value = day; picker.dispatchEvent(new Event('change', { bubbles: true })); await wait(() => text().includes('预约日期：' + day), 'date picker'); assert(!!document.querySelector(`[data-testid="merchant-order-${first.order_id}"]`), '页面日期筛选展示真实订单'); await click('清除日期') }
    }
    stage = 'detail'
    document.querySelector(`[data-testid="merchant-order-${first.order_id}"] uni-button`).click()
    await wait(() => document.querySelector('[data-testid="merchant-order-detail"]'), 'real merchant detail')
    const detail = await api('/api/merchant/orders/' + first.order_id)
    assert(detail.status === 200 && detail.body.data.order_id === first.order_id, '本店详情与下单价快照')
    assert(text().includes(first.order_no) && text().includes(first.amount_due), '详情页面展示真实订单号和金额')
    assert(typeof detail.body.data.has_payment_exception === 'boolean', '整单付款异常标记来自真实服务端')
    assert(!JSON.stringify(detail.body.data).includes('vehicle_id') && !JSON.stringify(detail.body.data).includes('vin'), '详情不返回车辆敏感字段')
    stage = 'retry'
    failNextList = true
    await click('返回本店订单')
    await wait(() => button('重试本店订单'), 'network retry action')
    await click('重试本店订单')
    await wait(() => document.querySelector(`[data-testid="merchant-order-${first.order_id}"]`), 'real data after retry')
    assert(text().includes(first.project_snapshot?.project_name || '历史订单') && !text().includes('无法连接服务'), '页面断网后重试恢复真实本店订单')
    let flagged = list.body.data.items.find(row => row.has_payment_exception)
    for (let page = 2; !flagged && (page - 1) * 20 < list.body.data.total; page++) {
      const more = await api('/api/merchant/orders?page=' + page + '&page_size=20')
      flagged = more.body.data.items.find(row => row.has_payment_exception)
    }
    if (flagged) {
      uni.navigateTo({ url: `/pages/merchant/order-detail?id=${flagged.order_id}` })
      await wait(() => document.querySelector('[data-testid="merchant-order-detail"]') && text().includes('本订单存在付款异常，待核对'), 'whole-order payment warning')
      assert(text().includes('本订单存在付款异常，待核对'), '历史付款异常在商家详情可见')
      if (flagged.payment_summary?.test_mode) assert(text().includes('测试支付，未真实扣款，不可作为收款凭据'), '测试支付不作为收款凭据')
    }
    const other = await helper('merchant', { store: 'B' })
    const otherList = await api('/api/merchant/orders', 'Bearer ' + other.access_token)
    assert(otherList.status === 200 && !otherList.body.data.items.some(row => row.order_id === first.order_id), '另一店列表不包含本店订单')
    assert((await api('/api/merchant/orders/' + first.order_id, 'Bearer ' + other.access_token)).status === 404, '另一店详情统一404')
    assert((await api('/api/merchant/orders', '')).status === 401, '匿名列表拒绝')
    assert((await api('/api/merchant/orders?merchant_id=2')).status === 400, '拒绝客户端指定商家ID')
    return { passed: checks.length, checks, boundary: 'Real H5/backend/MySQL; synthetic isolated merchant SMS session; no real debit or device acceptance' }
  } catch (error) { return { failed: true, stage, reason: error.message, passed: checks.length, checks } }
  finally { uni.request = original; await helper('revoke-merchants').catch(() => {}) }
})()
