import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {validPointsEntry,validPointsFlow,createPointsApi} from '../src/services/points-ledger.js'
const at='2026-10-16T00:00:00Z'
const debit=()=>({id:2,delta:-5,balance_after:2,created_at:at,exchange_id:1,catalog_id:1,catalog_revision:2,coupon_id:1})
const credit=()=>({id:1,delta:7,balance_after:7,created_at:at,task_type:'CHECK_IN',period_key:'2026-10-16',policy_id:1,policy_revision:2})
const page=()=>({items:[debit(),credit()],total:2,page:1,page_size:20})
test('verified flow accepts exact sourced credit and debit together without rewriting credit shape',()=>{
 assert.equal(validPointsFlow(page(),1),true);assert.equal(validPointsEntry(debit()),true);assert.equal(validPointsEntry({...debit(),balance_after:0}),true)
 for(const fields of [{delta:0},{delta:5},{delta:-1000001},{exchange_id:null},{catalog_revision:0},{user_id:2},{task_type:'CHECK_IN'},{balance_after:-1},{balance_after:Number.MAX_SAFE_INTEGER},{source_hash:'private'}])assert.equal(validPointsEntry({...debit(),...fields}),false,JSON.stringify(fields))
 assert.equal(validPointsEntry({...credit(),delta:-7}),false);assert.equal(validPointsEntry({...credit(),exchange_id:1}),false);assert.equal(validPointsFlow({...page(),items:[debit(),{...debit()}]},1),false)
})
test('flow API rejects unknown debit evidence rather than treating it as a successful empty list',async()=>{
 let data=page();const api=createPointsApi({baseUrl:'https://example.test',runtime:()=>({request:r=>r.success({statusCode:200,data:{code:0,data}})})});assert.deepEqual(await api.flow('owner-token',1),page());data={...page(),items:[{...debit(),catalog_revision:0},credit()]};await assert.rejects(api.flow('owner-token',1),e=>e.kind==='protocol')
})
test('existing owner point page shows real signed changes and separates redemption from task source',()=>{
 const source=readFileSync(new URL('../src/pages/points/index.vue',import.meta.url),'utf8');assert.ok(source.includes("r.delta<0?'兑换券':POINT_TASKS[r.task_type]"));assert.ok(source.includes("r.delta>0?'+':''"));assert.ok(source.includes('r.delta<0?r.catalog_id:r.policy_id'));assert.ok(source.includes('并扣除成功兑换的积分'));assert.equal(source.includes('+{{r.delta}}'),false)
})
