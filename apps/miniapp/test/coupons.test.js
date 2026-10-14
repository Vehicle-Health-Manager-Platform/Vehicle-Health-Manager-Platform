import test from 'node:test'
import assert from 'node:assert/strict'
import {createCouponsApi,validCouponTerms,validCoupon,validMyCoupon,initialCouponState,createCouponFlow,couponUtcTime,couponLocalTime} from '../src/services/coupons.js'
import {ServiceError} from '../src/services/service-catalog.js'
import {createPublicationApi} from '../src/services/experience-publication.js'
import {operatorSession,setOperatorSession,clearOperatorSession} from '../src/services/operator-session.js'
const key='12345678-1234-1234-1234-123456789abc'
const terms=()=>({name:'合成券',type:'DISCOUNT',project_id:1,amount:'5.00',min_spend:'10.00',total_quantity:2,valid_from:'2026-10-14T00:00:00Z',valid_to:'2026-10-15T00:00:00Z'})
const row=()=>({coupon_id:1,merchant_id:2,terms:terms(),revision:2,status:'PUBLISHED',stock_left:2,publication_revision:2,pool_selected:true,pool_revision:1})
const mine=()=>({user_coupon_id:3,coupon_id:1,merchant_id:2,source_known:true,terms:terms(),status:'AVAILABLE',availability:'AVAILABLE',publication_revision:2})
const page=(items,n=1)=>({items,total:items.length,page:n,page_size:20})
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
function runtime(result,statusCode=200){const calls=[];return{calls,request:r=>{calls.push(r);r.success({statusCode,data:{code:0,data:result}})}}}
test('terms reject inferred zero cash, float money, impossible dates and unknown fields',()=>{
 assert.equal(validCouponTerms(terms()),true)
 for(const patch of [{amount:5},{amount:'0.00'},{min_spend:'1.00'},{total_quantity:0},{total_quantity:2147483648},{valid_to:'2026-02-30T00:00:00Z'},{name:' bad '},{type:'SERVICE'},{extra:1}])assert.equal(Boolean(validCouponTerms({...terms(),...patch})),false)
 assert.equal(validCouponTerms({...terms(),type:'SERVICE',amount:'0.00',min_spend:'0.00'}),true)
})
test('published evidence and mine legacy must be internally consistent',()=>{
 assert.equal(validCoupon(row()),true);assert.equal(validMyCoupon(mine()),true)
 assert.equal(validCoupon({...row(),publication_revision:null}),false)
 assert.equal(validCoupon({...row(),stock_left:3}),false)
 assert.equal(validMyCoupon({...mine(),source_known:false}),false)
 assert.equal(validMyCoupon({...mine(),source_known:false,terms:null,publication_revision:null,availability:'UNKNOWN'}),true)
 assert.equal(validMyCoupon({...mine(),availability:'UNKNOWN'}),false)
})
test('Beijing validity dates convert without host timezone or impossible calendar rollover',()=>{
 assert.equal(couponUtcTime('2026-10-14','08:30'),'2026-10-14T00:30:00Z')
 assert.equal(couponLocalTime('2026-10-13T23:30:00Z'),'2026-10-14 07:30')
 assert.equal(couponUtcTime('2026-02-30','08:00'),'');assert.equal(couponUtcTime('2026-10-14','24:00'),'')
})
test('owner offers bind shop, page and authorization; GET has no idempotency',async()=>{
 const rt=runtime(page([row()])),api=createCouponsApi({baseUrl:'https://fixture',runtime:()=>rt})
 await api.list('owner','offers',2,1);assert.match(rt.calls[0].url,/merchant_id=2$/)
 assert.equal(rt.calls[0].header.Authorization,'Bearer owner');assert.equal(rt.calls[0].header['Idempotency-Key'],undefined)
 await assert.rejects(api.list('owner','offers',3,1),e=>e.kind==='protocol')
 assert.throws(()=>api.list('owner','mine',null,0),e=>e.kind==='invalid')
})
test('duplicate rows and wrong page rejected; legacy unknown can display',async()=>{
 for(const result of [page([row(),row()]),{...page([row()]),page:2},{...page([row()]),items:[{...row(),revision:0}]}]){const rt=runtime(result);await assert.rejects(createCouponsApi({runtime:()=>rt}).list('a','merchant',null,1),e=>e.kind==='protocol')}
 const unknown={...mine(),source_known:false,terms:null,publication_revision:null,availability:'UNKNOWN'}
 await createCouponsApi({runtime:()=>runtime(page([unknown]))}).list('a','mine',null,1)
})
test('claim exact evidence and write headers; response must bind original coupon',async()=>{
 const rt=runtime({user_coupon_id:3,coupon_id:1,publication_revision:2}),api=createCouponsApi({runtime:()=>rt}),r=row(),body={coupon_id:1,publication_revision:2}
 await api.write('a','claim',r,body,key);assert.equal(rt.calls[0].method,'POST');assert.equal(rt.calls[0].header['Idempotency-Key'],key);assert.deepEqual(rt.calls[0].data,body)
 assert.throws(()=>api.write('a','claim',r,{...body,user_id:3},key),e=>e.kind==='invalid')
 await assert.rejects(createCouponsApi({runtime:()=>runtime({user_coupon_id:3,coupon_id:2,publication_revision:2})}).write('a','claim',r,body,key),e=>e.kind==='protocol')
})
test('pool permission failure mapped and publication-state management checked',async()=>{
 const api=createCouponsApi({runtime:()=>runtime({},403)})
 await assert.rejects(api.list('a','operator',null,1),e=>e.kind==='forbidden')
 assert.throws(()=>api.write('a','status',row(),{expected_revision:2,status:'PUBLISHED'},key),e=>e.kind==='invalid')
})
test('ambiguous claim retries frozen body and UUID; re-read after success',async()=>{
 const state=initialCouponState(),calls=[];let count=0
 const api={list:async()=>page([]),write:async(...args)=>{calls.push(args);if(count++===0)throw new ServiceError('network','lost')}}
 const flow=createCouponFlow({state,token:()=> 'a',mode:'offers',newKey:()=>key,api})
 const r=row(),b={coupon_id:1,publication_revision:2};await flow.write('claim',r,b)
 assert.equal(state.retryWrite,true);r.terms.name='changed';b.coupon_id=999
 await flow.load();await flow.write('claim',row(),{coupon_id:1,publication_revision:2});assert.equal(calls.length,1)
 await flow.retry();assert.equal(calls.length,2);assert.deepEqual(calls[0],calls[1]);assert.equal(calls[1][3].coupon_id,1);assert.equal(calls[1][2].terms.name,'合成券');assert.equal(state.completed,1);assert.equal(state.retryWrite,false);assert.deepEqual(state.rows,[])
})
test('account changed during request or hidden reset drops late private rows',async()=>{
 for(const hidden of [true,false]){const state=initialCouponState(),d=deferred();let token='a';const flow=createCouponFlow({state,token:()=>token,mode:'mine',api:{list:()=>d.promise}});const p=flow.load();if(hidden)flow.reset();else{token='b';flow.reset()}d.resolve(page([mine()]));await p;assert.deepEqual(state.rows,[]);assert.equal(state.loaded,false);assert.equal(state.busy,false)}
})
test('modal cannot send after hidden or account changed',async()=>{
 const state=initialCouponState(),d=deferred();let count=0;const flow=createCouponFlow({state,token:()=> 'a',mode:'merchant',newKey:()=>key,confirm:()=>d.promise,api:{write:async()=>count++}})
 const p=flow.write('create',null,{terms:terms()});flow.reset();d.resolve(true);await p;assert.equal(count,0);assert.equal(state.retryWrite,false)
})
test('write reply cannot populate other account and permission errors drop private state',async()=>{
 const state=initialCouponState(),d=deferred();let token='a';const flow=createCouponFlow({state,token:()=>token,mode:'offers',newKey:()=>key,api:{write:()=>d.promise,list:async()=>page([row()])}})
 const p=flow.write('claim',row(),{coupon_id:1,publication_revision:2});await Promise.resolve();token='b';flow.reset();d.resolve({});await p;assert.equal(state.completed,0);assert.deepEqual(state.rows,[])
 const denied=createCouponFlow({state,token:()=>token,mode:'operator',newKey:()=>key,api:{write:async()=>{throw new ServiceError('forbidden','denied')}}});state.rows=[row()];await denied.write('pool',row(),{expected_pool_revision:1,selected:false});assert.deepEqual(state.rows,[]);assert.equal(state.retryWrite,false)
})
test('successful write with failed refresh is completed and cannot silently recreate draft',async()=>{
 const state=initialCouponState();let writes=0;const flow=createCouponFlow({state,token:()=> 'a',mode:'merchant',newKey:()=>key,api:{write:async()=>{writes++},list:async()=>{throw new ServiceError('network','read failed')}}})
 await flow.write('create',null,{terms:terms()});assert.equal(state.completed,1);assert.equal(state.retryWrite,false);await flow.retry();assert.equal(writes,1)
})
test('confirmation cancellation makes no write or stock change',async()=>{const state=initialCouponState();let writes=0;const flow=createCouponFlow({state,token:()=> 'a',mode:'offers',newKey:()=>key,confirm:async()=>false,api:{write:async()=>writes++}});await flow.write('claim',row(),{coupon_id:1,publication_revision:2});assert.equal(writes,0);assert.equal(state.busy,false)})
test('new operator login carries independent boolean coupon capability',async()=>{const login={access_token:'synthetic',token_type:'Bearer',expires_in:900,user:{id:1,role:'operator',can_review:false,can_onboard:false,can_catalog:false,can_coupon:true}};const api=createPublicationApi({baseUrl:'https://fixture',runtime:()=>runtime(login)});const value=await api.login('synthetic-operator','synthetic-password','123456');setOperatorSession(value);assert.equal(operatorSession.canCoupon,true);assert.equal(operatorSession.canCatalog,false);clearOperatorSession();assert.equal(operatorSession.canCoupon,false);const bad={...login,user:{...login.user,can_coupon:'true'}};await assert.rejects(createPublicationApi({baseUrl:'https://fixture',runtime:()=>runtime(bad)}).login('synthetic-operator','synthetic-password','123456'),e=>e.kind==='protocol')})
test('older identity response never implicitly grants coupon permission',()=>{setOperatorSession({access_token:'old',expires_in:900,user:{can_review:true,can_onboard:true,can_catalog:true}});assert.equal(operatorSession.canCoupon,false);clearOperatorSession()})
