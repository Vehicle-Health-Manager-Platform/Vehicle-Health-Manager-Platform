import test from 'node:test'
import assert from 'node:assert/strict'
import {NOTICE_LABELS,validNotifications,createNotificationApi,initialNotificationState,createNotificationFlow} from '../src/services/notifications.js'
const key='00000000-0000-4000-8000-000000000001',at='2026-10-15T08:00:00.123456789Z'
const data=()=>({unread_count:1,notifications:{items:[{notification_id:1,kind:'ORDER_ASSIGN',order_id:2,order_no:'synthetic',order_status:'RECEIVED',occurred_at:at,read_at:null}],total:1,page:1,page_size:20}})
const fail=kind=>Object.assign(new Error(kind),{kind})
const apiOf=n=>createNotificationApi({runtime:()=>({request:r=>r.success({statusCode:200,data:{code:0,data:n}})})})
test('new refund and dispute events use strict minimal order projection and distinct acceptance text',()=>{
 for(const kind of ['ORDER_CLOSED','REFUND_REQUESTED','REFUND_WITHDRAWN','REFUND_REJECTED','REFUND_ACCEPTED','REFUND_SUCCEEDED','REFUND_FAILED','DISPUTE_OPENED','DISPUTE_HANDLED','DISPUTE_REJECTED','DISPUTE_RESOLVED']){const n=data();n.notifications.items[0].kind=kind;n.notifications.items[0].order_status='CLOSED';assert.ok(validNotifications(n),kind);n.notifications.items[0].reason='private';assert.equal(validNotifications(n),false)}
 assert.match(NOTICE_LABELS.REFUND_ACCEPTED,/等待渠道确认/);assert.ok(!NOTICE_LABELS.REFUND_ACCEPTED.includes('成功'));assert.match(NOTICE_LABELS.REFUND_SUCCEEDED,/渠道已确认/)
})
function setup(extra={}){const state=initialNotificationState(),api={list:async()=>data(),read:async()=>{},...extra.api},flow=createNotificationFlow({state,token:()=> 'a',role:()=> 'owner',newKey:()=>key,...extra,api});return {state,flow}}
test('notification projection rejects identities, payloads, forged kinds and inaccurate pages',()=>{
 assert.ok(validNotifications(data()));for(const edit of [n=>n.recipient_id=99,n=>n.notifications.items[0].payload={phone:'private'},n=>n.notifications.items[0].kind='CUSTOM',n=>n.unread_count=2,n=>n.notifications.items.push(n.notifications.items[0]),n=>n.notifications.items[0].occurred_at='2026-02-30T08:00:00Z']){const n=data();edit(n);assert.equal(validNotifications(n),false)}
})
test('three role endpoints use authenticated GET and strict empty PUT with matching receipt',async()=>{
 for(const role of ['owner','merchant','tech']){let call;const api=createNotificationApi({runtime:()=>({request:r=>{call=r;r.success({statusCode:200,data:{code:0,data:r.method==='GET'?data():{notification_id:1,read_at:at}}})}})});await api.list('t',role);assert.equal(call.method,'GET');assert.ok(call.url.includes(`/api/${role}/notifications?`));assert.equal(call.header.Authorization,'Bearer t');await api.read('t',role,1,key);assert.equal(call.method,'PUT');assert.deepEqual(call.data,{});assert.equal(call.header['Idempotency-Key'],key)}
 await assert.rejects(apiOf({notification_id:2,read_at:at}).read('t','owner',1,key),e=>e.kind==='protocol');assert.throws(()=>apiOf(data()).list('t','other'));assert.throws(()=>apiOf(data()).read('t','owner',1,'bad'));assert.throws(()=>apiOf(data()).list('','owner'))
})
test('late responses after role/account switches and hiding never refill inbox',async()=>{
 for(const change of ['account','role','hide']){let t='a',r='owner',resolve;const {state,flow}=setup({token:()=>t,role:()=>r,api:{list:()=>new Promise(done=>resolve=done)}});const work=flow.load();if(change==='account')t='b';if(change==='role')r='merchant';flow.reset();resolve(data());await work;assert.equal(state.data,null);assert.equal(state.busy,false)}
})
test('read is limited to a visible unread item and duplicate taps do not write twice',async()=>{
 let resolve,calls=0;const {state,flow}=setup({api:{read:()=>{calls++;return new Promise(done=>resolve=done)}}});await flow.load();await flow.read(99);assert.equal(calls,0);const work=flow.read(1);await flow.read(1);assert.equal(calls,1);resolve();await work;state.data.notifications.items[0].read_at=at;await flow.read(1);assert.equal(calls,1)
})
test('unknown read retains original id and key across hide until retry resolves',async()=>{
 const calls=[];const {state,flow}=setup({api:{read:async(...args)=>{calls.push(args);if(calls.length===1)throw fail('network')}}});await flow.load();await flow.read(1);assert.equal(state.retry,true);assert.equal(state.data,null);flow.reset();await flow.load();await flow.retry();assert.deepEqual(calls[0],calls[1]);assert.equal(state.retry,false)
})
test('account switch discards unresolved read and late success cannot mark new account',async()=>{
 let t='a',resolve,calls=0;const {state,flow}=setup({token:()=>t,api:{read:()=>{calls++;return new Promise(done=>resolve=done)}}});await flow.load();const work=flow.read(1);t='b';flow.reset();resolve();await work;await flow.retry();assert.equal(calls,1);assert.equal(state.data,null)
})
test('revoked access and definitive failures clear unread content and pending retry',async()=>{
 for(const kind of ['unauthorized','forbidden','missing','conflict']){const {state,flow}=setup({api:{read:async()=>{throw fail(kind)}}});await flow.load();await flow.read(1);assert.equal(state.data,null);assert.equal(state.retry,false);assert.equal(state.kind,kind)}
})
test('failed reread removes old list and unread count',async()=>{
 let broken=false;const {state,flow}=setup({api:{list:async()=>{if(broken)throw fail('network');return data()}}});await flow.load();assert.equal(state.data.unread_count,1);broken=true;await flow.load();assert.equal(state.data,null)
})
