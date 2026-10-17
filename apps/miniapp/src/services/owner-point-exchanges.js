import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'
import {validPointExchangeCatalog} from './point-exchange-catalog.js'
import {ServiceError,serviceFailure} from './service-catalog.js'
import {createAsyncDiagnosisFlow} from './async-diagnosis.js'
export const EXCHANGE_STATES={ELIGIBLE:'可兑换',NOT_STARTED:'尚未开始',EXPIRED:'兑换已到期',SOURCE_UNKNOWN:'券来源待核对',SOURCE_UNAVAILABLE:'券来源暂不可用',SOURCE_CHANGED:'券来源已变化，请重新核对',OUT_OF_STOCK:'券库存不足',LIMIT_REACHED:'同模板兑换次数已达限额',INSUFFICIENT_POINTS:'已核验积分不足',EXCHANGE_UNAVAILABLE:'当前兑换资格尚未确认'}
const count=n=>Number.isSafeInteger(n)&&n>=0
const stamp=n=>typeof n==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(n)&&Number.isFinite(Date.parse(n))&&new Date(n).toISOString().slice(0,19)===n.slice(0,19)
const uuid=n=>typeof n==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(n)
const ident=n=>safeId(n)&&n<Number.MAX_SAFE_INTEGER
const merchant=n=>exact(n,['id','name','address'])&&ident(n.id)&&typeof n.name==='string'&&n.name.length>0&&typeof n.address==='string'
const project=n=>exact(n,['id','name','service_content'])&&ident(n.id)&&typeof n.name==='string'&&n.name.length>0&&typeof n.service_content==='string'
export const validOwnerExchangeBody=b=>Boolean(exact(b,['catalog_id','catalog_revision'])&&ident(b.catalog_id)&&ident(b.catalog_revision))
export function validExchangeReceipt(n){return Boolean(exact(n,['exchange_id','user_coupon_id','catalog_id','catalog_revision','coupon_id','publication_revision','points_spent','balance_after','exchanged_at','status','rule_version','catalog'])&&['exchange_id','user_coupon_id','catalog_id','catalog_revision','coupon_id','publication_revision'].every(k=>ident(n[k]))&&count(n.balance_after)&&Number.isSafeInteger(n.balance_after+n.points_spent)&&stamp(n.exchanged_at)&&n.status==='SUCCESS'&&n.rule_version==='R8A_EXCHANGE_V1'&&validPointExchangeCatalog(n.catalog)&&n.catalog.status==='PUBLISHED'&&n.catalog_id===n.catalog.id&&n.catalog_revision===n.catalog.revision&&n.coupon_id===n.catalog.coupon_id&&n.publication_revision===n.catalog.publication_revision&&n.points_spent===n.catalog.points_cost&&Date.parse(n.exchanged_at)>=Date.parse(n.catalog.valid_from)&&Date.parse(n.exchanged_at)<Date.parse(n.catalog.valid_to))}
function page(n,p,check,id){return exact(n,['items','total','page','page_size'])&&count(n.total)&&n.page===p&&n.page_size===20&&Array.isArray(n.items)&&n.items.length===Math.min(20,Math.max(0,n.total-(p-1)*20))&&n.items.every(check)&&new Set(n.items.map(id)).size===n.items.length}
function validOffer(n,context){if(!exact(n,['catalog','merchant','project','stock_left','exchanged_count','remaining_limit','eligibility'])||!validPointExchangeCatalog(n.catalog)||n.catalog.status!=='PUBLISHED'||!(n.merchant===null||merchant(n.merchant))||!(n.project===null||project(n.project)&&n.project.id===n.catalog.terms.project_id)||!((n.merchant===null)===(n.project===null))||!(n.stock_left===null||count(n.stock_left)&&n.stock_left<=n.catalog.terms.total_quantity)||!count(n.exchanged_count)||n.remaining_limit!==Math.max(0,n.catalog.per_owner_limit-n.exchanged_count)||!Object.hasOwn(EXCHANGE_STATES,n.eligibility))return false
 const from=Date.parse(n.catalog.valid_from),to=Date.parse(n.catalog.valid_to),at=Date.parse(context.read_at)
 if(n.eligibility==='NOT_STARTED')return at<from||at<Date.parse(n.catalog.terms.valid_from)
 if(n.eligibility==='EXPIRED')return at>=to||at>=Date.parse(n.catalog.terms.valid_to)
 if(at<from||at>=to)return false
 if(n.eligibility==='ELIGIBLE')return context.exchange_available&&n.merchant!==null&&n.project!==null&&n.stock_left>0&&n.remaining_limit>0&&context.balance>=n.catalog.points_cost
 if(n.eligibility==='INSUFFICIENT_POINTS')return context.balance<n.catalog.points_cost
 if(n.eligibility==='OUT_OF_STOCK')return n.stock_left===0||n.stock_left===null
 if(n.eligibility==='LIMIT_REACHED')return n.remaining_limit===0
 if(n.eligibility==='EXCHANGE_UNAVAILABLE')return context.exchange_available===false
 return true
}
export function validOwnerExchangeCatalog(n,p){if(!exact(n,['items','total','page','page_size','balance','legacy_state','exchange_available','read_at'])||!count(n.balance)||!['NONE','REVIEW_REQUIRED'].includes(n.legacy_state)||typeof n.exchange_available!=='boolean'||!stamp(n.read_at))return false;const list=Object.fromEntries(['items','total','page','page_size'].map(k=>[k,n[k]]));return page(list,p,r=>validOffer(r,n),r=>r.catalog.id)}
export function validExchangeHistory(n,p){return Boolean(page(n,p,validExchangeReceipt,r=>r.exchange_id)&&new Set(n.items.map(r=>r.user_coupon_id)).size===n.items.length)}
export function createOwnerPointExchangeApi({runtime=()=>apiRuntime,baseUrl=apiOrigin}={}){
 function call(token,path,check,body,key){if(!token)throw new ServiceError('unauthorized','请登录车主账号');if(!baseUrl)throw new ServiceError('unconfigured','积分兑换服务尚未配置');return new Promise((resolve,reject)=>{try{runtime().request({url:baseUrl.replace(/\/$/,'')+path,method:body?'POST':'GET',data:body,timeout:15000,header:{Authorization:`Bearer ${token}`,'content-type':'application/json',...(key?{'Idempotency-Key':key}:{})},success:({statusCode,data})=>{if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',409:'conflict',503:'unavailable'})[statusCode]||'server','兑换请求未完成，请重新核对或重试原请求'));if(data?.code!==0||!check(data.data))return reject(new ServiceError('protocol','兑换响应异常，请保留原请求重新核对'));resolve(data.data)},fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})}
 function pagination(p){if(!ident(p)||p>1000000)throw new ServiceError('invalid','分页无效')}
 return {catalog(token,p=1){pagination(p);return call(token,`/api/point/exchange-catalog?page=${p}&page_size=20`,n=>validOwnerExchangeCatalog(n,p))},history(token,p=1){pagination(p);return call(token,`/api/point/exchanges?page=${p}&page_size=20`,n=>validExchangeHistory(n,p))},exchange(token,b,key){if(!validOwnerExchangeBody(b)||!uuid(key))throw new ServiceError('invalid','兑换目录、修订或原请求编号无效');return call(token,'/api/point/exchange',n=>validExchangeReceipt(n)&&n.catalog_id===b.catalog_id&&n.catalog_revision===b.catalog_revision,b,key)}}
}
export function createOwnerExchangeRecovery({runtime=()=>apiRuntime,actor}={}){
 const copy=v=>JSON.parse(JSON.stringify(v)),same=(a,b)=>JSON.stringify(a)===JSON.stringify(b)
 function key(){if(!ident(actor()))throw Error('当前车主账号尚未确认');return `autocare:point-exchange:v1:${actor()}`}
 const valid=p=>exact(p,['action','id','body','key'])&&p.action==='exchange'&&p.id===0&&validOwnerExchangeBody(p.body)&&uuid(p.key)
 function read(){const v=runtime().getStorageSync(key());if(!v)return {draft:null,pending:null};if(!exact(v,['version','pending'])||v.version!==1||!valid(v.pending))throw Error('本机兑换原请求损坏，请核对服务器后清理');return {draft:null,pending:copy(v.pending)}}
 function complete(expected){const saved=read().pending;if(saved&&!same(saved,expected))throw Error('本机兑换原请求已被其他页面更新');runtime().removeStorageSync(key())}
 return {read,savePending(p){if(!valid(p))throw Error('兑换原请求无效');const saved=read().pending;if(saved&&!same(saved,p))throw Error('已有未决兑换请求，请先核对');runtime().setStorageSync(key(),{version:1,pending:copy(p)})},complete,clearPending:complete,clear(){runtime().removeStorageSync(key())},marker(){return JSON.stringify(runtime().getStorageSync(key())||null)},discard(marker){if(JSON.stringify(runtime().getStorageSync(key())||null)!==marker)throw Error('兑换原请求已被其他页面更新');runtime().removeStorageSync(key())}}
}
export function prepareOwnerExchange(row,context){if(!context||!validOwnerExchangeCatalog(context,context.page)||!context.exchange_available||row?.eligibility!=='ELIGIBLE'||!context.items.includes(row))throw new ServiceError('invalid','请重新读取可兑换的服务器报价');return {quote:JSON.parse(JSON.stringify({...row,balance:context.balance})),body:{catalog_id:row.catalog.id,catalog_revision:row.catalog.revision}}}
export function createOwnerExchangeFlow({state,token,actor,historyPage=()=>1,api=createOwnerPointExchangeApi(),recovery=createOwnerExchangeRecovery({actor}),newKey,confirm,onWritten}={}){return createAsyncDiagnosisFlow({state,token,scope:()=>`owner-point-exchange:${actor()}`,allowed:()=>ident(actor()),recovery,newKey,confirm,onWritten,load:async(t,p)=>{const [offers,history]=await Promise.all([api.catalog(t,p),api.history(t,historyPage())]);return {offers,history}},write:(t,a,id,b,key)=>api.exchange(t,b,key)})}
