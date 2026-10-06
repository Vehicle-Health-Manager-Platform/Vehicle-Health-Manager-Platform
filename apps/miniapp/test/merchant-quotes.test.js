import test from 'node:test'
import assert from 'node:assert/strict'
import { quoteBody,createQuotesApi } from '../src/services/merchant-quotes.js'
import { ServiceError } from '../src/services/service-catalog.js'
import { initialQuoteListState,createQuoteListFlow,initialQuoteFormState,createQuoteFormFlow } from '../src/services/quote-flow.js'
import { merchantSession,clearMerchantSession } from '../src/services/merchant-session.js'
import { useRoleIdentity } from '../src/services/role-identity.js'
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
const row=price=>({merchant_project_id:1,version_id:1,version:1,price,merchant_id:1,merchant_name:'测试店',address:'合成地址'})
const page=items=>({items,total:items.length,page:1,page_size:20})
const key='11111111-1111-4111-8111-111111111111'
test('strict positive decimal quote body',()=>{
  for(const price of ['0.00','1','1.1','1.001','-1.00','1e2','01.00','100000000.00',1.00])assert.throws(()=>quoteBody({projectId:1,price,status:1}))
  for(const price of ['0.01','99999999.99'])assert.equal(quoteBody({projectId:1,price,status:0}).price,price)
})
test('authenticated quote read and write preserve price and idempotency',async()=>{
  let request,response={statusCode:200,data:{code:0,data:page([row('1.00')])}}
  const api=createQuotesApi({baseUrl:'http://test/',runtime:()=>({request:options=>{request=options;options.success(response)}})})
  await api.ownerList('owner',1,'price_desc');assert.match(request.url,/sort=price_desc/);assert.equal(request.header.Authorization,'Bearer owner')
  response.data.data={...row('1.00'),status:1};await api.save('merchant',{standard_project_id:1,price:'1.00',status:1},key)
  assert.equal(request.header['Idempotency-Key'],key);assert.equal(request.data.price,'1.00')
  response.data.data.price='2.00';await assert.rejects(api.save('merchant',{price:'1.00',status:1},key),{kind:'protocol'})
  response.statusCode=403;await assert.rejects(api.ownList('owner'),{kind:'forbidden'})
})
test('ambiguous save uses original key, changed body changes key, taps serialized',async()=>{
  const state={...initialQuoteFormState(),projectId:1,price:'1.00',status:1},calls=[]
  let n=0,pending=deferred()
  const flow=createQuoteFormFlow({state,token:()=> 'merchant',newKey:()=>String(++n),api:{save:(_token,body,key)=>{calls.push({body,key});return pending.promise}}})
  let saving=flow.save();await flow.save();assert.equal(calls.length,1)
  pending.reject(new ServiceError('network','重试'));await saving
  pending=deferred();saving=flow.save();pending.reject(new ServiceError('network','重试'));await saving
  assert.equal(calls[0].key,calls[1].key)
  state.price='2.00';pending=deferred();saving=flow.save();pending.resolve({price:'2.00'});await saving
  assert.notEqual(calls[1].key,calls[2].key);assert.equal(state.message,'报价已保存')
})
test('account change and hidden editor discard late response',async()=>{
  const state={...initialQuoteFormState(),projectId:1,price:'1.00'},pending=deferred();let actor='one'
  const flow=createQuoteFormFlow({state,token:()=>actor,newKey:()=>key,api:{save:()=>pending.promise}})
  const saving=flow.save();actor='two';flow.reset();pending.resolve(row('1.00'));await saving
  assert.equal(state.saved,null);assert.equal(state.projectId,0)
})
test('sort and pagination retry discard stale rows',async()=>{
  const state=initialQuoteListState(),a=deferred(),b=deferred()
  const flow=createQuoteListFlow({state,token:()=> 'owner',fetchPage:(_t,_p,sort)=>sort==='price_asc'?a.promise:b.promise})
  const first=flow.load(),second=flow.sort('price_desc');b.resolve(page([row('2.00')]));await second;a.resolve(page([row('1.00')]));await first
  assert.equal(state.items[0].price,'2.00')
  const calls=[];let fail=true
  const more=createQuoteListFlow({state,token:()=> 'owner',fetchPage:async(_t,n)=>{calls.push(n);if(fail)throw new ServiceError('network','重试');return{items:[],total:2,page:n,page_size:20}}})
  state.total=2;await more.load(true);assert.equal(state.items.length,1);fail=false;await more.retry();assert.deepEqual(calls,[2,2])
})
test('merchant login session persists across pages and logout clears it',async()=>{
  clearMerchantSession()
  const api={requestMerchantLogin:async()=>({access_token:'synthetic',user:{role:'MERCHANT'}}),logoutWechat:async()=>({revoked:true})}
  const login=useRoleIdentity('merchant',api);login.merchantAccount.value='test';login.merchantPassword.value='test';login.smsCode.value='123456'
  await login.tryMerchantLogin();assert.equal(merchantSession.accessToken,'synthetic')
  const reopened=useRoleIdentity('merchant',api);assert.equal(reopened.accessToken.value,'synthetic');await reopened.tryLogout();assert.equal(merchantSession.accessToken,'')
})
