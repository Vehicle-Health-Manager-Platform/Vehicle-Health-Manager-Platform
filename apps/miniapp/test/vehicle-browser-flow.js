// gstack browse eval on local H5 only. Isolated test responses; never imported by app.
return await (async () => {
  const checks = [], original = { login: uni.login, request: uni.request }
  let stage = 'login'
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
  const text = () => document.body.textContent
  const button = label => [...document.querySelectorAll('uni-button')].find(el => el.textContent.trim() === label)
  const click = async label => { await sleep(350); const el = button(label); if (!el) throw new Error('Missing button: ' + label); el.click() }
  const assert = (condition, label) => { if (!condition) throw new Error(label); checks.push(label) }
  const wait = async condition => { for (let i = 0; i < 100; i++) { if (condition()) return; await sleep(60) } throw new Error('Page state timeout at ' + stage + ': ' + location.href + ' ' + text().slice(-500)) }
  const paged = (list, size) => ({ list, page: 1, page_size: size, total: list.length })
  const calls = []; let empty = true, addStatus = 503, vehicles = [], listStatus = 200
  uni.login = options => options.success({ code: 'offline-vehicle-code' })
  uni.request = options => {
    let data, statusCode = 200
    if (options.url.includes('/api/auth/')) data = { access_token: 'offline-vehicle-owner', user: { role: 'owner' } }
    else if (options.url.includes('/api/vehicle/list')) { data = paged(vehicles, 20); statusCode = listStatus }
    else if (options.url.includes('/api/brand/list')) data = paged(empty ? [] : [{ id: 1, name: '测试品牌' }], 100)
    else if (options.url.includes('/api/series/list')) data = paged([{ id: 2, name: '测试车系' }], 100)
    else if (options.url.includes('/api/model/list')) data = paged([{ id: 3, year: '2025', config_name: '测试配置' }], 100)
    else if (options.url.includes('/api/vehicle/add')) {
      calls.push({ key: options.header['Idempotency-Key'], body: { ...options.data } }); statusCode = addStatus
      data = { vehicle_id: 7, model_name: '测试品牌 测试车系 2025 测试配置', need_archive: true }
      if (statusCode === 200) vehicles = [{ vehicle_id: 7, model_id: 3, model_name: data.model_name,
        current_mileage: options.data.current_mileage, plate_no_masked: '粤B****5', vin_masked: '' }]
    } else throw new Error('Unexpected test route')
    setTimeout(() => options.success({ statusCode, data: { code: statusCode === 200 ? 0 : statusCode * 100, data, message: 'private backend detail' } }), 80)
  }
  async function pick(index) {
    const el = document.querySelectorAll('uni-picker')[index]
    el.click(); await sleep(100)
    const confirm = [...document.querySelectorAll('.uni-picker-action-confirm')].find(item => item.closest('.uni-picker-toggle') && item.getClientRects().length > 0)
    if (!confirm) throw new Error('Picker did not open')
    confirm.click(); await sleep(350)
  }
  function input(index, value) {
    const el = document.querySelectorAll('uni-input input')[index]; el.value = value; el.dispatchEvent(new Event('input', { bubbles: true }))
  }
  try {
    await uni.navigateTo({ url: '/pages/owner/index' }); await wait(() => button('微信登录')); await click('微信登录'); await wait(() => button('进入车主首页'))
    stage = 'empty-list'; await uni.switchTab({ url: '/pages/archive/index' }); await wait(() => text().includes('还没有车辆'))
    assert(Boolean(button('手动添加')), 'owner empty list offers manual entry')
    stage = 'empty-catalog'; await sleep(350); await click('手动添加'); await wait(() => text().includes('暂无可选车型'))
    assert(button('保存车辆').getAttribute('disabled') === 'true', 'empty catalog disables save')
    empty = false; await uni.switchTab({ url: '/pages/archive/index' }); await wait(() => button('手动添加')); await sleep(350); await click('手动添加')
    await wait(() => { const picker = document.querySelector('uni-picker'); return picker && picker.getAttribute('disabled') !== 'true' })
    await pick(0); await pick(1); await pick(2); await pick(3)
    assert(text().includes('测试配置') && button('保存车辆').getAttribute('disabled') !== 'true', 'four native H5 picker steps select an existing model')
    stage = 'invalid-mileage'; input(0, '-1'); await click('保存车辆'); await wait(() => text().includes('非负整数'))
    assert(calls.length === 0, 'invalid mileage never submits')
    stage = 'first-save'; input(0, '32000'); input(1, '粤b12345'); await click('保存车辆'); await wait(() => text().includes('正在处理'))
    assert(button('保存车辆').getAttribute('disabled') === 'true', 'save disabled during request')
    await wait(() => text().includes('车辆服务暂不可用'))
    assert(!text().includes('private backend detail'), 'upstream failure hidden')
    await click('保存车辆'); await wait(() => calls.length === 2); await wait(() => text().includes('车辆服务暂不可用'))
    assert(calls[0].key === calls[1].key, 'failed write retries same key')
    input(0, '32001'); addStatus = 200; await click('保存车辆'); await wait(() => button('返回查看车辆'))
    assert(calls[2].key !== calls[1].key && calls[2].body.plate_no === '粤B12345', 'edited payload renews key and normalizes plate')
    await click('返回查看车辆'); await wait(() => text().includes('当前里程 32001 km'))
    assert(text().includes('粤B****5') && !text().includes('粤B12345'), 'saved vehicle reloads with masked identifier')
    stage = 'list-retry'; listStatus = 503; await uni.switchTab({ url: '/pages/home/index' }); await sleep(120); await uni.switchTab({ url: '/pages/archive/index' }); await wait(() => button('重试加载'))
    listStatus = 200; await click('重试加载'); await wait(() => text().includes('当前里程 32001 km'))
    assert(!button('重试加载'), 'list failure recovers through visible retry')
    listStatus = 401; await uni.switchTab({ url: '/pages/home/index' }); await sleep(120); await uni.switchTab({ url: '/pages/archive/index' }); await wait(() => button('重新登录'))
    await click('重新登录'); await wait(() => button('微信登录')); await uni.switchTab({ url: '/pages/archive/index' }); await wait(() => text().includes('请先验证车主身份'))
    assert(!text().includes('当前里程 32001'), 're-login clears old owner list')
    // Screenshot scene uses only explicit test data.
    listStatus = 200; await uni.navigateTo({ url: '/pages/owner/index' }); await wait(() => button('微信登录')); await click('微信登录'); await wait(() => button('进入车主首页'))
    await uni.switchTab({ url: '/pages/archive/index' }); await wait(() => text().includes('当前里程 32001 km'))
    return { passed: checks.length, checks, evidence: 'Actual H5 UI and native H5 pickers with test-only uni responses; no private backend or real WeChat identity' }
  } finally { Object.assign(uni, original) }
})()
