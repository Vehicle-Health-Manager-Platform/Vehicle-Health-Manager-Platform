import test from 'node:test'
import assert from 'node:assert/strict'
import {createRequire} from 'node:module'
const {ResponseGate}=createRequire(import.meta.url)('../../../scripts/native-mileage-inflight.cjs')
const bytes=()=>Buffer.from(JSON.stringify({data:{recognition:{fields:[{value:'123456'}]}}}))

test('inflight gate holds only actual recognition bytes and records release without mutation',async()=>{
 const gate=new ResponseGate(),original=bytes();gate.arm('cancel')
 await gate.accept({mode:'upload',status:200,bytes:Buffer.from('receipt')});assert.equal(gate.active.received,false)
 const held=gate.accept({mode:'recognition',status:200,bytes:original});await gate.received()
 assert.equal(gate.history.length,0);gate.release();await held
 assert.deepEqual(original,bytes());assert.equal(gate.active,null)
 assert.deepEqual(gate.history,[{name:'cancel',status:200,real_response_held:true,response_bytes_unchanged:true}])
})

test('inflight gate rejects error responses, wrong mileage and overlapped controls',async()=>{
 for(const response of [{mode:'recognition',status:503,bytes:bytes()},{mode:'recognition',status:200,bytes:Buffer.from('{"data":{"recognition":{"fields":[{"value":"wrong"}]}}}')}]){
  const gate=new ResponseGate();gate.arm('actor');assert.throws(()=>gate.arm('leave'))
  await assert.rejects(gate.accept(response));assert.deepEqual(gate.history,[])
 }
})
