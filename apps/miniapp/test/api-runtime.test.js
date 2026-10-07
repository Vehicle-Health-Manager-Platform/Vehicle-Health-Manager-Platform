import test from 'node:test'
import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import { apiRuntime } from '../src/services/api-runtime.js'

// 回归护栏：service 层通过运行时调用原生能力，这些方法一旦在传输层被漏掉，
// 真实运行时会以「不是函数」失败，而注入假 runtime 的单元测试照样全绿。
// 这里用**真实装配**加一个假 uni 覆盖，确保类似缺口能被 CI 拦住。

function withUni(fake, run) {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'uni')
  globalThis.uni = fake
  try {
    return run()
  } finally {
    if (original) Object.defineProperty(globalThis, 'uni', original)
    else delete globalThis.uni
  }
}

const REQUIRED = ['chooseMedia', 'chooseImage', 'uploadFile', 'previewImage', 'openSetting', 'request', 'login']

test('运行时暴露取图、文件与请求所需的全部方法', () => {
  for (const name of REQUIRED) assert.equal(typeof apiRuntime[name], 'function', `运行时缺少 ${name}`)
  for (const name of ['initCloud', 'supports', 'environment', 'getFileSystemManager'])
    assert.equal(typeof apiRuntime[name], 'function', `运行时缺少 ${name}`)
})

test('原生方法透传并保持回调语义', () => {
  const seen = []
  withUni(
    {
      chooseMedia: options => { seen.push('chooseMedia'); options.success({ tempFiles: [{ tempFilePath: 'a.jpg', size: 10 }] }) },
      chooseImage: options => { seen.push('chooseImage'); options.success({ tempFiles: [{ path: 'a.jpg', size: 10 }] }) },
      uploadFile: options => { seen.push('uploadFile'); options.success({ statusCode: 200, data: '{}' }); return { abort() {} } },
      previewImage: options => { seen.push('previewImage'); options.success() },
      openSetting: options => { seen.push('openSetting'); options.success({ authSetting: {} }) },
      request: options => { seen.push('request'); options.success({ statusCode: 200, data: {} }) },
      login: options => { seen.push('login'); options.success({ code: 'offline' }) },
      getSystemInfoSync: () => ({ platform: 'devtools' }),
      getFileSystemManager: () => ({ getFileInfo() {} }),
    },
    () => {
      let chosen
      apiRuntime.chooseMedia({ sourceType: ['camera'], success: value => { chosen = value } })
      apiRuntime.chooseImage({ sourceType: ['camera'], success() {} })
      apiRuntime.uploadFile({ url: '/api/file/upload', success() {} })
      apiRuntime.previewImage({ current: 'https://offline.invalid/one', success() {} })
      apiRuntime.openSetting({ success() {} })
      apiRuntime.request({ url: '/api/vehicles', success() {} })
      apiRuntime.login({ success() {} })
      // 选择结果必须原样返回给调用方，否则上层拿不到图片路径。
      assert.equal(chosen.tempFiles[0].tempFilePath, 'a.jpg')
      assert.equal(apiRuntime.supports('chooseMedia'), true)
      assert.equal(apiRuntime.supports('chooseVideo'), false)
      assert.equal(apiRuntime.environment(), 'devtools')
      assert.notEqual(apiRuntime.getFileSystemManager(), undefined)
    },
  )
  assert.deepEqual(seen, ['chooseMedia', 'chooseImage', 'uploadFile', 'previewImage', 'openSetting', 'request', 'login'])
})

test('service 源码里实际调用的运行时方法都已暴露', async () => {
  // 手写清单会随代码演进而漏项，这里直接扫描调用点，让「加了调用忘了加透传」当场失败。
  const directory = new URL('../src/services/', import.meta.url)
  const called = new Set()
  for (const entry of await readdir(directory)) {
    if (!entry.endsWith('.js')) continue
    const text = await readFile(new URL(entry, directory), 'utf8')
    for (const match of text.matchAll(/runtime\(\)\s*\.\s*(\w+)/g)) called.add(match[1])
  }
  assert.ok(called.has('request') && called.has('login'),
    `未扫描到 runtime().request / runtime().login，正则可能已失效（命中 ${[...called].join(',')}）`)
  for (const name of called) {
    assert.equal(typeof apiRuntime[name], 'function', `service 调用了 runtime().${name}，但运行时没有暴露它`)
  }
})

test('底层缺少能力时走 fail 回调而不是抛错', () => {
  withUni({}, () => {
    let message = ''
    apiRuntime.chooseMedia({ fail: error => { message = error.errMsg } })
    assert.match(message, /chooseMedia:fail/)
    assert.equal(apiRuntime.supports('chooseMedia'), false)
    assert.equal(apiRuntime.environment(), 'device')
    assert.equal(apiRuntime.getFileSystemManager(), undefined)
  })
})

test('没有运行时全局对象时降级为 fail 回调与真机判定', () => {
  // Node 离线测试与构建期就是这个状态：连 uni 都不存在。
  delete globalThis.uni
  let message = ''
  apiRuntime.chooseImage({ fail: error => { message = error.errMsg } })
  assert.match(message, /chooseImage:fail/)
  assert.equal(apiRuntime.supports('chooseImage'), false)
  assert.equal(apiRuntime.environment(), 'device')
  assert.equal(apiRuntime.getFileSystemManager(), undefined)
})
