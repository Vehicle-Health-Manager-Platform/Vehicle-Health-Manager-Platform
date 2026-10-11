import test from 'node:test'
import assert from 'node:assert/strict'
import {createCommissionsApi,validCommissionEntry,validCommissionPage,initialCommissionListState,createCommissionListFlow,commissionReasonLabel,commissionKindLabel} from '../src/services/commissions.js'
const policy={state:'CONFIGURED',rate_percent:'3.00',effective_from:'2026-01-31T15:59:59Z',waived_until:'2026-04-30T15:59:59Z',rule_version:'R2C_V1'}
const totals={accrued:'0.30',reversed:'0.00',net:'0.30',pending_count:0}
const item={entry_id:1,order_id:3,payment_id:2,refund_id:null,kind:'ACCRUAL',base_amount:'10.00',rate_percent:'3.00',exemption:'NONE',amount:'0.30',currency:'CNY',occurred_at:'2026-05-01T00:00:00Z',test_mode:true,rule_version:'R2C_V1',reference_entry_id:null,booked_at:'2026-04-30T15:59:59Z',waived_until:policy.waived_until}
const page=(n=1,size=20,total=1,items=[item])=>({merchant_id:1,policy,summary:{real:{...totals,accrued:'0.00',net:'0.00'},test:totals},page:n,page_size:size,total,items})
test('ledger contract keeps unknown and no-charge separate',()=>{
 assert.equal(validCommissionEntry(item),true);assert.equal(validCommissionEntry({...item,kind:'WAIT_REVIEW',exemption:'WAIT_POLICY',amount:null,rate_percent:null,waived_until:null}),true)
 assert.equal(validCommissionEntry({...item,kind:'REFUND_NO_CHARGE',refund_id:5,amount:'0.00',exemption:'REFUND_BEFORE_COMPLETION'}),true)
 for(const changed of [{amount:null},{amount:0.3},{amount:'-0.30'},{currency:'USD'},{kind:'SETTLED'},{test_mode:1},{user_id:2},{rule_version:'OTHER'},{exemption:'NEW_MERCHANT'},{rate_percent:'90.00'}])assert.equal(validCommissionEntry({...item,...changed}),false)
})
test('refund reversal binds original entry and negative full fee',()=>{
 const v={...item,kind:'REFUND_REVERSAL',refund_id:4,reference_entry_id:2,amount:'-0.30'};assert.equal(validCommissionEntry(v),true)
 for(const changed of [{amount:'0.30'},{reference_entry_id:null},{refund_id:null}])assert.equal(validCommissionEntry({...v,...changed}),false)
 assert.equal(validCommissionEntry({...v,amount:'0.00',exemption:'NEW_MERCHANT'}),true)
})
test('page requires exact fields policy and test split, no fabricated zero policy',()=>{
 assert.equal(validCommissionPage(page(),1,20),true);const unknown={state:'UNCONFIGURED',rate_percent:null,effective_from:null,waived_until:null,rule_version:null};assert.equal(validCommissionPage({...page(),policy:unknown},1,20),true)
 for(const changed of [{private_token:'x'},{page:2},{page_size:50},{items:[item,item]},{summary:{real:totals}},{policy:{...unknown,rate_percent:'0.00'}}])assert.equal(validCommissionPage({...page(),...changed},1,20),false)
})
test('manager read sends scoped GET only, never ledger money',async()=>{
 let sent;const api=createCommissionsApi({baseUrl:'http://test',runtime:()=>({request:o=>{sent=o;o.success({statusCode:200,data:{code:0,data:page()}})}})})
 await api.list('manager');assert.equal(sent.method,'GET');assert.equal(sent.url,'http://test/api/merchant/commissions?page=1&page_size=20');assert.deepEqual(sent.header,{Authorization:'Bearer manager'});assert.equal(sent.data,undefined)
 assert.throws(()=>api.list(''),e=>e.kind==='unauthorized');assert.throws(()=>api.list('manager',0),e=>e.kind==='invalid');assert.throws(()=>api.list('manager',1,51),e=>e.kind==='invalid')
})
test('wrong page and unknown financial fields are protocol errors',async()=>{
 let response=page(2);const api=createCommissionsApi({baseUrl:'http://test',runtime:()=>({request:o=>o.success({statusCode:200,data:{code:0,data:response}})})});await assert.rejects(api.list('manager'),e=>e.kind==='protocol');response={...page(),items:[{...item,settled:true}]};await assert.rejects(api.list('manager'),e=>e.kind==='protocol')
})
test('role and database errors use safe local messages',async()=>{
 for(const [status,kind] of [[401,'unauthorized'],[403,'forbidden'],[503,'unavailable']]){const api=createCommissionsApi({baseUrl:'http://test',runtime:()=>({request:o=>o.success({statusCode:status,data:{message:'private SQL secret'}})})});await assert.rejects(api.list('manager'),e=>e.kind===kind&&!e.message.includes('private'))}
})
test('failed next page preserves rows and retries same page',async()=>{
 let fail=false;const requests=[],state=initialCommissionListState();const flow=createCommissionListFlow({state,token:()=> 'manager',size:1,request:async(t,n,s)=>{requests.push(n);if(fail)throw {kind:'network',message:'retry'};return page(n,s,3,[{...item,entry_id:n}])}})
 await flow.load();fail=true;await flow.next();assert.equal(state.items.length,1);assert.equal(state.page,1);fail=false;await flow.next();assert.deepEqual(requests,[1,2,2]);assert.equal(state.items.length,2)
})
test('identity change and hidden page discard late financial results',async()=>{
 let actor='manager',complete;const state=initialCommissionListState(),flow=createCommissionListFlow({state,token:()=>actor,request:()=>new Promise(r=>{complete=r})});const first=flow.load();actor='other';flow.reset();complete(page());await first;assert.equal(state.summary,null);assert.deepEqual(state.items,[]);const next=flow.load();flow.reset();complete(page());await next;assert.equal(state.policy,null);assert.equal(state.merchantId,0)
})
test('pagination cannot mix a different merchant into visible rows',async()=>{
 const state=initialCommissionListState();const flow=createCommissionListFlow({state,token:()=> 'manager',size:1,request:async(t,n,s)=>({...page(n,s,2,[{...item,entry_id:n}]),merchant_id:n})});await flow.load();await flow.next();assert.equal(state.items.length,1);assert.equal(state.merchantId,1);assert.equal(state.failureKind,'protocol')
})
test('readable ledger labels never claim settlement',()=>{assert.match(commissionKindLabel('ACCRUAL'),/应计/);assert.match(commissionKindLabel('WAIT_REVIEW'),/未确定/);assert.match(commissionReasonLabel('WAIT_LEGACY'),/无下单快照/);assert.match(commissionReasonLabel('NEW_MERCHANT'),/免佣/)})
