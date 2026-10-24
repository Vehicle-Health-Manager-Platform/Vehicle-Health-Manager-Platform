import test from 'node:test'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
import path from 'node:path'
import fs from 'node:fs'
import crypto from 'node:crypto'
import {spawnSync} from 'node:child_process'
const require=createRequire(import.meta.url),{admitted,createMileageProxy}=require('../../../scripts/native-mileage-proxy.cjs')
const json={'content-type':'application/json'},key='123e4567-e89b-42d3-a456-426614174000'
const recognition={vehicle_id:992,file_id:17,consent:true,consent_version:'MILEAGE_OCR_V1'}
const body=value=>Buffer.from(JSON.stringify(value))
test('mileage proxy requires exact selected owner source and explicit consent',()=>{
 assert.equal(admitted('/api/vehicle/mileage-recognition',json,body(recognition),17),true)
 for(const patch of [{vehicle_id:991},{file_id:18},{consent:false},{consent_version:'old'},{extra:true}])assert.equal(admitted('/api/vehicle/mileage-recognition',json,body({...recognition,...patch}),17),false)
 for(const value of [null,[],7,'text'])assert.equal(admitted('/api/vehicle/mileage-recognition',json,body(value),17),false)
 assert.equal(admitted('/api/vehicle/mileage-recognition',json,body(recognition),0),false)
 assert.equal(admitted('/api/vehicle/mileage-recognition?owner_id=992',json,body(recognition),17),false)
})
test('archive proxy never corrects wrong mileage, title, file or idempotency key',()=>{
 const data={vehicle_id:992,archive_type:1,recorded_date:'2026-10-24',mileage:123456,title:'synthetic-mileage-native',notes:'',file_ids:[17]},headers={...json,'idempotency-key':key}
 assert.equal(admitted('/api/archive/add',headers,body(data),17),true)
 for(const patch of [{mileage:1},{file_ids:[18]},{title:'other'},{extra:true},{vehicle_id:991}])assert.equal(admitted('/api/archive/add',headers,body({...data,...patch}),17),false)
 assert.equal(admitted('/api/archive/add',json,body(data),17),false)
})
test('upload proxy restricts multipart and rejects header injection',()=>{
 assert.equal(admitted('/api/file/upload',{'content-type':'multipart/form-data; boundary=abc','idempotency-key':key},Buffer.from('test'),0),true)
 assert.equal(admitted('/api/file/upload',{'content-type':'multipart/form-data; boundary=abc\nheader=x','idempotency-key':key},Buffer.from('test'),0),false)
})
test('loopback proxy rejects wrong actor and route without invoking Docker',async()=>{
 const root=path.resolve('../..'),run='a'.repeat(32),token='eyJsynthetic'
 const proxy=await createMileageProxy({work:path.join(root,'.cache','real-mileage-'+run),run,token,port:12345,image:Buffer.from('fixture').toString('base64'),listenPort:0})
 try{
  const url='http://127.0.0.1:'+proxy.server.address().port,headers={Authorization:'Bearer '+token}
  assert.equal((await fetch(url+'/__mileage-fixture.png')).status,401)
  assert.equal(await (await fetch(url+'/__mileage-fixture.png',{headers})).text(),'fixture')
  assert.equal((await fetch(url+'/other',{headers})).status,404)
  assert.equal((await fetch(url+'/api/vehicle/mileage-recognition',{method:'POST',headers:{...headers,...json},body:'null'})).status,400)
  assert.deepEqual(proxy.counts,{upload:0,recognition:0,archive:0})
 }finally{await proxy.close()}
})
test('native runner rejects missing opt-in and unowned path without writing reports',()=>{
 const root=path.resolve('../..'),cache=path.join(root,'.cache'),id=crypto.randomUUID().replaceAll('-','')
 fs.mkdirSync(cache,{recursive:true})
 for(const [name,flags] of [['real-mileage-'+id,[]],['invalid-native-'+id,['--allow-isolated-mileage']]]){
  const directory=path.join(cache,name);fs.mkdirSync(directory)
  try{
   const result=spawnSync(process.execPath,[path.join(root,'scripts/native-mileage-e2e.cjs'),directory,...flags],{cwd:root,windowsHide:true,timeout:5000})
   assert.equal(result.status,1);assert.deepEqual(fs.readdirSync(directory),[])
  }finally{assert.equal(path.dirname(directory),cache);fs.rmSync(directory,{recursive:true,force:true})}
 }
})
