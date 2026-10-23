import test from 'node:test'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import vm from 'node:vm'
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
test('synthetic operator analytics capability requires an explicit boolean in Devtools', () => {
  const plugin=nativeAcceptancePlugin(valid)
  const code=plugin.load(plugin.resolveId('virtual:autocare-native-acceptance')).replace(/^import .*;$/gm,'')
  let session
  const clear=()=>{}
  const context={wx:{getSystemInfoSync:()=>({platform:'devtools'})},setOperatorSession:value=>{session=value},
    clearOwnerSession:clear,clearMerchantSession:clear,clearTechnicianSession:clear,clearOperatorSession:clear,clearOwnerVehicle:clear}
  vm.runInNewContext(code,context)
  for(const capability of [undefined,false,'true',true]) {
    context.wx.__autocareNativeAcceptance({role:'operator',token:'synthetic',actorId:2,canAnalytics:capability})
    assert.equal(session.user.id,2)
    assert.equal(session.user.can_analytics,capability===true)
  }
  context.wx.getSystemInfoSync=()=>({platform:'ios'})
  assert.throws(()=>context.wx.__autocareNativeAcceptance({role:'operator',token:'synthetic',canAnalytics:true}),/Devtools/)
})
