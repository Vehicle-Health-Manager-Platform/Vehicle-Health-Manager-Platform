import test from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { nativeAcceptancePlugin } from '../../../scripts/native-acceptance-plugin.mjs'

const valid = { R0_NATIVE_TEST_BUILD: '1', UNI_OUTPUT_DIR: fileURLToPath(new URL('../../../.cache/r0-native/mp-weixin', import.meta.url)), VITE_API_BASE_URL: 'http://127.0.0.1:18080' }
test('normal build never installs the native identity bridge', () => {
  assert.equal(nativeAcceptancePlugin({}), null)
  assert.equal(nativeAcceptancePlugin({ R0_NATIVE_TEST_BUILD: '0' }), null)
})
test('native bridge rejects production output, remote backend and cloud transport', () => {
  for (const env of [{...valid,UNI_OUTPUT_DIR:''},{...valid,VITE_API_BASE_URL:'https://example.com'},{...valid,VITE_WECHAT_CLOUD_ENV_ID:'cloud'}]) {
    assert.throws(() => nativeAcceptancePlugin(env), /isolated output/)
  }
})
test('only the test build entry imports the bridge and no credentials are baked in', () => {
  const plugin = nativeAcceptancePlugin(valid)
  assert.equal(plugin.transform('unchanged','/other/main.js'), undefined)
  assert.match(plugin.transform('original','/project/apps/miniapp/src/main.js'), /virtual:autocare-native-acceptance/)
  const bridge = plugin.load(plugin.resolveId('virtual:autocare-native-acceptance'))
  assert.match(bridge, /platform!==.*devtools/)
  assert.match(bridge, /options.token/)
  assert.doesNotMatch(bridge, /JWT_SECRET|localStorage|setStorage|console\./)
})
