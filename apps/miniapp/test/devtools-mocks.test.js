import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const { createWxMockScope } = createRequire(import.meta.url)('./helpers/devtools-mocks.cjs')

test('cleanup never restores untouched or unsuccessfully mocked native APIs', async () => {
  const calls=[]
  const scope=createWxMockScope(async (method,params)=>{calls.push(params);if(params.result && params.method==='missing')throw new Error('unsupported')})
  await scope.restore('chooseImage')
  assert.deepEqual(calls,[])
  await assert.rejects(scope.mock('missing',{value:true}),/unsupported/)
  await scope.restore('missing')
  assert.equal(calls.length,1)
  await scope.mock('showModal',{confirm:true})
  await scope.restore('showModal')
  await scope.restore('showModal')
  assert.equal(calls.filter(c=>c.method==='showModal' && !Object.hasOwn(c,'result')).length,1)
  assert.deepEqual(scope.methods,[])
})

test('failed native API restore remains tracked for a cleanup retry', async () => {
  let failures=1
  const scope=createWxMockScope(async (_,params)=>{if(!Object.hasOwn(params,'result') && failures-->0)throw new Error('connection')})
  await scope.mock('chooseMedia',{tempFiles:[]})
  await assert.rejects(scope.restore('chooseMedia'),/connection/)
  assert.deepEqual(scope.methods,['chooseMedia'])
  await scope.restore('chooseMedia')
  assert.deepEqual(scope.methods,[])
})
