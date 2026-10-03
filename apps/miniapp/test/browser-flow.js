// Run only with gstack browse eval on the local H5 dev server, configured with
// VITE_API_BASE_URL. This file is never imported by the application.
return await (async () => {
  const checks = []
  const assert = (condition, name) => { if (!condition) throw new Error(name); checks.push(name) }
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
  const waitFor = async (condition) => {
    for (let i = 0; i < 100; i++) { if (condition()) return; await sleep(50) }
    throw new Error('Timed out waiting for page state')
  }
  const text = () => document.body.textContent
  const button = (label) => [...document.querySelectorAll('uni-button')].find((el) => el.textContent.trim() === label)
  const click = (label) => { const el = button(label); if (!el) throw new Error(`Missing button: ${label}`); el.click() }
  const field = (placeholder) => [...document.querySelectorAll('uni-input')]
    .find((el) => el.querySelector('.uni-input-placeholder')?.textContent === placeholder)?.querySelector('input')
  const input = (placeholder, value) => {
    const el = field(placeholder)
    if (!el) throw new Error(`Missing input: ${placeholder}`)
    el.value = value
    el.dispatchEvent(new Event('input', { bubbles: true }))
  }
  const navigate = async (role) => {
    uni.navigateTo({ url: `/pages/${role}/index` })
    await waitFor(() => location.hash.includes(`/pages/${role}/index`) && button(role === 'merchant' ? '获取短信验证码' : '微信登录'))
  }
  const original = { login: uni.login, request: uni.request }
  let next = { statusCode: 200, data: { code: 0, data: { access_token: 'offline-ui-token', user: { role: 'owner' } } } }
  const requests = []
  uni.login = (options) => options.success({ code: 'offline-ui-code' })
  uni.request = (options) => {
    requests.push({ url: options.url, header: options.header })
    const reply = next
    setTimeout(() => reply.errMsg ? options.fail(reply) : options.success(reply), 250)
  }
  const success = (data) => { next = { statusCode: 200, data: { code: 0, data } } }
  const fail = (statusCode, message = '') => { next = { statusCode, data: { message } } }
  try {
    await navigate('owner')
    assert(text().includes('车主工作入口'), 'owner page initializes')
    click('微信登录')
    await waitFor(() => text().includes('正在验证微信身份'))
    assert(button('微信登录').getAttribute('disabled') === 'true', 'login disabled while loading')
    await waitFor(() => button('进入车主首页'))
    assert(text().includes('车主登录成功'), 'owner login success')
    click('进入车主首页')
    await waitFor(() => location.hash.includes('/pages/home/index') && text().includes('业务内容即将接入'))
    assert(!text().includes('请先验证车主身份'), 'owner tabs share session')
    for (const tab of ['home', 'service', 'ai', 'archive', 'mine']) {
      uni.switchTab({ url: `/pages/${tab}/index` })
      await waitFor(() => location.hash.includes(`/pages/${tab}/index`) && text().includes('业务内容即将接入'))
      assert(text().includes('尚无可展示的业务数据'), `${tab} accurately shows pending business integration`)
    }
    click('账号与退出登录')
    await waitFor(() => button('退出登录'))
    next = { errMsg: 'request:fail network' }
    click('退出登录')
    await waitFor(() => text().includes('无法连接服务'))
    assert(Boolean(button('退出登录')), 'failed logout retains session')
    success({ revoked: true })
    click('重试本次操作')
    await waitFor(() => text().includes('已安全退出登录'))
    assert(!button('退出登录'), 'successful logout clears session')

    await navigate('merchant')
    input('商家账号', 'offline-account')
    input('密码', 'offline-password')
    fail(503, '短信服务尚未配置')
    await sleep(50)
    click('获取短信验证码')
    await waitFor(() => text().includes('短信服务尚未开通'))
    assert(!text().includes('验证码已发送'), 'SMS 503 never reports sent')
    success({ sent: true, expires_in: 300 })
    click('重试本次操作')
    await waitFor(() => text().includes('验证码已发送'))
    input('六位短信验证码', '123')
    await sleep(50)
    assert(button('登录商家端').getAttribute('disabled') === 'true', 'short SMS code disables login')
    input('六位短信验证码', '123456')
    fail(401)
    await sleep(50)
    click('登录商家端')
    await waitFor(() => text().includes('身份验证失败'))
    assert(!button('退出登录'), 'rejected merchant login creates no session')
    fail(429)
    click('登录商家端')
    await waitFor(() => text().includes('操作过于频繁'))
    assert(!button('重试本次操作'), 'rate limit asks user to wait')
    success({ access_token: 'offline-ui-token', user: { role: 'merchant' } })
    click('登录商家端')
    await waitFor(() => text().includes('商家登录成功'))
    assert(!field('密码'), 'signed-in merchant hides credentials')
    success({ revoked: true })
    click('退出登录')
    await waitFor(() => button('登录商家端'))
    assert(field('密码').value === '', 'password cleared after success')
    assert(field('六位短信验证码').value === '', 'SMS code cleared after success')

    await navigate('technician')
    success({ status: 'BIND_REQUIRED', binding_token: 'offline-binding-token' })
    click('微信登录')
    await waitFor(() => button('绑定技师身份'))
    assert(text().includes('商家发放的员工码'), 'technician awaiting binding')
    input('商家发放的员工码', 'offline-employee-code')
    fail(403)
    await sleep(50)
    click('绑定技师身份')
    await waitFor(() => text().includes('请重新微信登录'))
    assert(!button('绑定技师身份'), 'forbidden binding requires new login')
    success({ status: 'BIND_REQUIRED', binding_token: 'fresh-offline-binding-token' })
    click('微信登录')
    await waitFor(() => button('绑定技师身份'))
    input('商家发放的员工码', 'offline-employee-code')
    success({ access_token: 'offline-ui-token', user: { role: 'technician' } })
    await sleep(50)
    click('绑定技师身份')
    await waitFor(() => text().includes('技师身份绑定成功'))
    assert(!button('绑定技师身份'), 'bound technician hides employee code')
    assert(requests.some((r) => r.url.endsWith('/technician/bind') && r.header.Authorization === 'Bearer fresh-offline-binding-token'), 'binding request uses binding credential')
    success({ revoked: true })
    click('退出登录')
    await waitFor(() => text().includes('已安全退出登录'))
    return { passed: checks.length, checks, evidence: 'H5 with test-only runtime responses, not live WeChat or SMS' }
  } finally {
    uni.login = original.login
    uni.request = original.request
  }
})()
