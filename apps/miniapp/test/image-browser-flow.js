// gstack browse eval on the configured local H5 dev server only. Never imported by app.
return await (async () => {
  const checks = [], original = Object.fromEntries(['login', 'request', 'chooseImage', 'chooseMedia', 'uploadFile', 'previewImage'].map(name => [name, uni[name]]))
  const assert = (condition, label) => { if (!condition) throw new Error(label); checks.push(label) }
  const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
  const button = label => [...document.querySelectorAll('uni-button')].find(el => el.textContent.trim() === label)
  const click = label => { if (!button(label)) throw new Error('Missing button: ' + label); button(label).click() }
  const wait = async condition => { for (let i = 0; i < 100; i++) { if (condition()) return; await sleep(60) } throw new Error('Page state timeout') }
  const text = () => document.body.textContent
  const image = { path: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aSkcAAAAASUVORK5CYII=', size: 100 }
  const calls = [], previews = []; let selection = null, uploadStatus = 503, accessStatus = 200, expired = false
  uni.login = options => options.success({ code: 'offline-ui-code' })
  uni.chooseImage = options => selection ? options.success({ tempFiles: [selection] }) : options.fail({ errMsg: 'chooseImage:fail cancel' })
  // 页面优先使用官方推荐的 chooseMedia，仅在不支持时回退 chooseImage；两条都覆盖，
  // 否则联调脚本会假设失效（H5 下 uni.chooseMedia 一旦存在，chooseImage 覆盖就不再生效）。
  uni.chooseMedia = uni.chooseImage
  uni.uploadFile = options => {
    calls.push({ key: options.header['Idempotency-Key'], path: options.filePath })
    const timer = setTimeout(() => options.success({ statusCode: uploadStatus, data: JSON.stringify({ code: uploadStatus === 200 ? 0 : uploadStatus * 100,
      data: uploadStatus === 200 ? { file_id: 9, content_type: 'image/png', size_bytes: 100 } : null, message: 'private backend message' }) }), 180)
    return { abort() { clearTimeout(timer) } }
  }
  let accessCount = 0
  uni.request = options => {
    const access = options.url.includes('/api/file/')
    if (access) accessCount++
    setTimeout(() => options.success({ statusCode: access ? accessStatus : 200, data: { code: access && accessStatus !== 200 ? accessStatus * 100 : 0,
      data: access ? { url: 'https://offline.invalid/image?signature=test', expires_at: new Date(expired ? 0 : Date.now() + 120000).toISOString() }
        : { access_token: 'offline-image-owner', user: { role: 'owner' } } } }), 80)
    return { abort() {} }
  }
  uni.previewImage = options => { previews.push(options.current); options.success() }
  try {
    uni.navigateTo({ url: '/pages/owner/index' }); await wait(() => button('微信登录')); click('微信登录'); await wait(() => button('进入车主首页'))
    uni.switchTab({ url: '/pages/archive/index' }); await wait(() => button('选择图片'))
    assert(text().includes('车辆档案录入尚未开放'), 'archive scope is explicit')
    click('选择图片'); await wait(() => text().includes('已取消选图')); assert(!button('上传图片'), 'cancel leaves empty state')
    selection = { ...image, size: 10 * 1024 * 1024 + 1 }; click('选择图片'); await wait(() => text().includes('图片超过 10 MiB'))
    assert(!button('上传图片'), 'oversized choice does not become uploadable')
    selection = image; click('选择图片'); await wait(() => button('上传图片')); click('上传图片')
    await wait(() => text().includes('正在上传并检查')); assert(button('重新选择图片').getAttribute('disabled') === 'true', 'replacement disabled during upload')
    await wait(() => button('使用原图片重试')); assert(!text().includes('private backend'), 'failure text hides backend response')
    uploadStatus = 429; click('使用原图片重试'); await wait(() => text().includes('图片操作过于频繁'))
    uploadStatus = 200; click('使用原图片重试'); await wait(() => button('预览已上传图片'))
    assert(calls.length === 3 && calls.every(call => call.key === calls[0].key), '503 and 429 retries use the original key')
    assert(text().includes('档案尚未创建'), 'upload never claims archive creation')
    click('预览已上传图片'); await wait(() => previews.length === 1); await wait(() => !text().includes('正在获取'))
    click('预览已上传图片'); await wait(() => previews.length === 2); assert(accessCount === 2, 'each preview obtains a new signature')
    await wait(() => !text().includes('正在获取')); selection = null; click('重新选择图片'); await wait(() => text().includes('已取消选图'))
    assert(Boolean(button('预览已上传图片')), 'cancelling replacement retains uploaded image')
    selection = image; click('重新选择图片'); await wait(() => button('上传图片')); click('上传图片'); await wait(() => button('预览已上传图片'))
    assert(calls.at(-1).key !== calls[0].key, 'replacement uses a new key')
    expired = true; click('预览已上传图片'); await wait(() => button('重试图片预览')); assert(previews.length === 2, 'expired signature is never previewed')
    expired = false; click('重试图片预览'); await wait(() => previews.length === 3); await wait(() => !text().includes('正在获取'))
    accessStatus = 401; click('预览已上传图片'); await wait(() => button('重新登录车主账号'))
    assert(button('预览已上传图片').getAttribute('disabled') === 'true', '401 prevents another preview until login')
    click('重新登录车主账号'); await wait(() => button('微信登录'))
    uni.switchTab({ url: '/pages/archive/index' }); await wait(() => text().includes('请先验证车主身份'))
    assert(!button('预览已上传图片'), 'explicit re-login clears old owner image UI')
    // Leave a test-only authenticated screenshot scene; native/API methods are restored below.
    uni.navigateTo({ url: '/pages/owner/index' }); await wait(() => button('微信登录')); click('微信登录'); await wait(() => button('进入车主首页'))
    uni.switchTab({ url: '/pages/archive/index' }); await wait(() => button('选择图片')); selection = image; accessStatus = 200
    click('选择图片'); await wait(() => button('上传图片')); click('上传图片'); await wait(() => button('预览已上传图片'))
    return { passed: checks.length, checks, evidence: 'Actual H5 UI with test-only uni responses; no private backend or WeChat authorization' }
  } finally { for (const [name, method] of Object.entries(original)) uni[name] = method }
})()
