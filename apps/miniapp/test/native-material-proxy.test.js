import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import {createRequire} from 'node:module'
const {createProxy}=createRequire(import.meta.url)('../../../scripts/native-material-proxy.cjs')
test('native material proxy binds loopback and rejects unrelated routes identities and headers',async t=>{
  const work=fs.mkdtempSync(path.join(os.tmpdir(),'native-material-proxy-'))
  const proxy=await createProxy({token:'eyJsynthetic.token',port:12345,container:'synthetic-container',work,containerWork:'/workspace/.cache/native-material-'+'a'.repeat(32),pdf:Buffer.from('%PDF-synthetic').toString('base64'),listenPort:0})
  t.after(async()=>{await proxy.close();fs.rmdirSync(work)})
  assert.equal(proxy.server.address().address,'127.0.0.1')
  const base='http://127.0.0.1:'+proxy.server.address().port
  assert.equal((await fetch(base+'/other')).status,404)
  assert.equal((await fetch(base+'/api/input-materials/ARCHIVE/991/upload',{method:'POST'})).status,401)
  assert.equal((await fetch(base+'/api/input-materials/ARCHIVE/991/upload',{method:'POST',headers:{Authorization:'Bearer eyJsynthetic.token'}})).status,400)
  assert.equal((await fetch(base+'/__native-fixture.pdf')).status,401)
  const fixture=await fetch(base+'/__native-fixture.pdf',{headers:{Authorization:'Bearer eyJsynthetic.token'}})
  assert.equal(await fixture.text(),'%PDF-synthetic')
  assert.equal(proxy.requests,0)
  assert.deepEqual(fs.readdirSync(work),[])
})
test('native proxy rejects malformed container fixture before listening',async()=>{
  await assert.rejects(()=>createProxy({token:'invalid',port:12345,container:'x;bad',containerWork:'/tmp'}),/Invalid proxy fixture/)
})
