import test from 'node:test'
import assert from 'node:assert/strict'
import {serviceLoginReturn,returnAfterOperatorLogin} from '../src/services/service-login-return.js'
const stack=(route,options={})=>[{route,options},{route:'pages/owner/index'}]
test('only the previous exact business route and matching role/valid id can return',()=>{
 for(const [route,options,role] of [['pages/order/work-evidence',{id:'1'},'owner'],['pages/order/archive-grants',{id:'2'},'owner'],['pages/workspace/tasks',{role:'tech'},'technician'],['pages/workspace/context',{role:'merchant',id:'1'},'merchant'],['pages/technician/work',{id:'12'},'technician'],['pages/diagnosis/notifications',{role:'owner'},'owner']])assert.equal(serviceLoginReturn(stack(route,options),role),true)
 for(const [route,options,role] of [['https://invalid',{},'owner'],['pages/order/work-evidence',{id:'1e2'},'owner'],['pages/technician/work',{id:'9007199254740992'},'technician'],['pages/workspace/tasks',{role:'tech'},'owner'],['pages/diagnosis/detail',{role:'admin',id:'1'},'owner'],['pages/owner/index',{return_url:'/pages/diagnosis/detail'},'owner']])assert.equal(serviceLoginReturn(stack(route,options),role),false)
 assert.equal(serviceLoginReturn([{route:'pages/diagnosis/mine'}],'owner'),false)
})
test('operator resume checks current separate permissions without promoting queue to body',()=>{
 const manage={canDiagnose:false,canDiagnoseManage:true},handle={canDiagnose:true,canDiagnoseManage:false}
 assert.equal(serviceLoginReturn(stack('pages/diagnosis/detail',{role:'admin',id:'1'}),'admin',manage),false);assert.equal(serviceLoginReturn(stack('pages/diagnosis/detail',{role:'admin',id:'1'}),'admin',handle),true)
 assert.equal(serviceLoginReturn(stack('pages/operator/diagnosis-cases',{scope:'queue'}),'admin',handle),false);assert.equal(serviceLoginReturn(stack('pages/operator/diagnosis-cases',{scope:'queue'}),'admin',manage),true)
 assert.equal(serviceLoginReturn(stack('pages/operator/diagnosis-service'),'admin',handle),false);assert.equal(serviceLoginReturn(stack('pages/diagnosis/notifications',{role:'admin'}),'admin',manage),true)
})
test('operator return uses navigateBack and falls back to fixed landing if unavailable',()=>{
 const calls=[],runtime={navigateBack:o=>{calls.push('back');o.fail()},redirectTo:o=>calls.push(o.url)};returnAfterOperatorLogin({pages:stack('pages/operator/diagnosis-service'),permissions:{canDiagnoseManage:true},runtime,landing:'/pages/operator/diagnosis-service'});assert.deepEqual(calls,['back','/pages/operator/diagnosis-service']);calls.length=0;returnAfterOperatorLogin({pages:stack('https://invalid'),permissions:{canDiagnoseManage:true},runtime,landing:'/pages/operator/diagnosis-service'});assert.deepEqual(calls,['/pages/operator/diagnosis-service'])
})
