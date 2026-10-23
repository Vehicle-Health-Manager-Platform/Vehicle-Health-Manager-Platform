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

test('image mode admits only authenticated PNG fixture and fixed image upload route',async t=>{
  const work=fs.mkdtempSync(path.join(os.tmpdir(),'native-image-proxy-'))
  const proxy=await createProxy({kind:'image',token:'eyJsynthetic.token',port:12345,container:'synthetic-container',work,containerWork:'/workspace/.cache/native-material-'+'b'.repeat(32),image:Buffer.from('synthetic-png').toString('base64'),listenPort:0})
  t.after(async()=>{await proxy.close();fs.rmdirSync(work)})
  const base='http://127.0.0.1:'+proxy.server.address().port
  assert.equal(proxy.server.address().address,'127.0.0.1')
  assert.equal((await fetch(base+'/api/input-materials/ARCHIVE/991/upload',{method:'POST'})).status,404)
  assert.equal((await fetch(base+'/__native-fixture.pdf')).status,404)
  assert.equal((await fetch(base+'/__native-fixture.png')).status,401)
  const response=await fetch(base+'/__native-fixture.png',{headers:{Authorization:'Bearer eyJsynthetic.token'}})
  assert.equal(response.headers.get('content-type'),'image/png');assert.equal(await response.text(),'synthetic-png')
  assert.equal((await fetch(base+'/api/file/upload',{method:'POST'})).status,401)
  const headers={Authorization:'Bearer eyJsynthetic.token','Content-Type':'multipart/form-data; boundary=synthetic','Idempotency-Key':'00000000-0000-4000-8000-000000000001'}
  assert.equal((await fetch(base+'/api/file/upload',{method:'POST',headers:{...headers,'X-Material-Kind':'pdf'}})).status,400)
  assert.equal((await fetch(base+'/api/file/upload',{method:'POST',headers:{...headers,'Idempotency-Key':'-'.repeat(36)}})).status,400)
  assert.equal(proxy.requests,0);assert.deepEqual(fs.readdirSync(work),[])
})

test('native proxy rejects unknown upload mode before listening',async()=>{
  await assert.rejects(()=>createProxy({kind:'arbitrary'}),/Invalid proxy mode/)
})
