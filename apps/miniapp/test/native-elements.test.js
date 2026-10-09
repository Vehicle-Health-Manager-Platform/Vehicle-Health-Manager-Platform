import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { tapButton } = createRequire(import.meta.url)('./helpers/native-elements.cjs')

test('native button matching ignores template edge whitespace and preserves meaningful inner spaces', async () => {
  const calls = []
  const rpc = async (method, params) => {
    calls.push({ method, params })
    if (method === 'Page.getElements') return { elements: params.selector === 'button' ? [{elementId:'other'}, {elementId:'target'}] : [] }
    if (method === 'Element.getDOMProperties') return { properties: [params.elementId === 'target' ? '\n 选择  技师 \n' : '选择 技师'] }
    if (method === 'Element.getOffset') return { top: 1200, left: 20 }
    return {}
  }
  await tapButton(rpc, {pageId:'native-page'}, '选择  技师')
  assert.deepEqual(calls.filter(c => c.method === 'Element.tap').map(c => c.params.elementId), ['target'])
  assert.ok(calls.some(c => c.method === 'App.callWxMethod' && c.params.method === 'pageScrollTo' && c.params.args[0].scrollTop > 0))
})
