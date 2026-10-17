import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'
import {validPriceSource,validPricePolicy} from './price-policies.js'
import {ServiceError,serviceFailure} from './service-catalog.js'
export const COMPARISON_STATES={UNKNOWN:'未知',WITHIN_RANGE:'在已确认区间内',BELOW_RANGE:'偏低，需核对',ABOVE_RANGE:'偏高，需核对'}
export const COMPARISON_REASONS={VERIFIED:'当前来源及发布依据已核验',SOURCE_UNAVAILABLE:'当前门店、项目或报价不可用',SOURCE_UNKNOWN:'当前报价版本或原保存凭证不完整',POLICY_MISSING:'尚无来源依据',POLICY_NOT_PUBLISHED:'仅有未发布草稿',POLICY_STOPPED:'依据已停止',POLICY_SOURCE_UNKNOWN:'依据或原发布凭证无法核验',NOT_STARTED:'依据尚未生效',EXPIRED:'依据已到截止时间',SOURCE_CHANGED:'报价版本或适用来源已变化，需重新确认'}
const stamp=n=>typeof n==='string'&&/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(n)&&Number.isFinite(Date.parse(n))&&new Date(n).toISOString().replace('.000','')===n
const canonical=n=>Array.isArray(n)?n.map(canonical):n&&typeof n==='object'?Object.fromEntries(Object.keys(n).sort().map(k=>[k,canonical(n[k])])):n
const same=(a,b)=>JSON.stringify(canonical(a))===JSON.stringify(canonical(b))
const cents=n=>Number(n.replace('.','')) // values are validated positive prices, max 9,999,999,999 cents
export function validPriceComparison(n,observed){
 if(!stamp(observed)||!exact(n,['quote_id','state','reason','review_required','current','policy'])||!safeId(n.quote_id)||!Object.hasOwn(COMPARISON_STATES,n.state)||!Object.hasOwn(COMPARISON_REASONS,n.reason)||typeof n.review_required!=='boolean'||n.current!==null&&(!validPriceSource(n.current)||n.current.quote_id!==n.quote_id)||n.policy!==null&&(!validPricePolicy(n.policy)||n.policy.quote_id!==n.quote_id||n.policy.created_at>observed||n.policy.published_at!==null&&n.policy.published_at>observed||n.policy.stopped_at!==null&&n.policy.stopped_at>observed))return false
 const c=n.current,p=n.policy
 if(n.state==='UNKNOWN'){
  if(n.review_required||n.reason==='VERIFIED')return false
  if(n.reason==='SOURCE_UNAVAILABLE')return c===null
  if(!c)return false
  if(n.reason==='SOURCE_UNKNOWN')return c.state==='UNKNOWN'
  if(c.state!=='READY')return false
  if(n.reason==='POLICY_MISSING')return p===null
  if(n.reason==='POLICY_SOURCE_UNKNOWN')return p===null||p.status==='PUBLISHED'
  if(!p)return false
  if(n.reason==='POLICY_NOT_PUBLISHED')return p.status==='DRAFT'
  if(n.reason==='POLICY_STOPPED')return p.status==='STOPPED'
  if(p.status!=='PUBLISHED')return false
  if(n.reason==='NOT_STARTED')return observed<p.valid_from
  if(n.reason==='EXPIRED')return observed>=p.valid_to
  return n.reason==='SOURCE_CHANGED'&&observed>=p.valid_from&&observed<p.valid_to&&(c.source_hash!==p.source_hash||!same(c.source,p.source_snapshot))
 }
 if(n.reason!=='VERIFIED'||c?.state!=='READY'||p?.status!=='PUBLISHED'||observed<p.valid_from||observed>=p.valid_to||c.source_hash!==p.source_hash||!same(c.source,p.source_snapshot))return false
 const price=cents(c.source.price),lo=cents(p.lower_price),hi=cents(p.upper_price),expected=price<lo?'BELOW_RANGE':price>hi?'ABOVE_RANGE':'WITHIN_RANGE'
 return n.state===expected&&n.review_required===(expected!=='WITHIN_RANGE')
}
export function validPriceComparisonPage(n,p=1){return Boolean(exact(n,['observed_at','comparisons'])&&stamp(n.observed_at)&&exact(n.comparisons,['items','total','page','page_size'])&&Number.isSafeInteger(n.comparisons.total)&&n.comparisons.total>=0&&n.comparisons.page===p&&n.comparisons.page_size===20&&Array.isArray(n.comparisons.items)&&n.comparisons.items.length===Math.min(20,Math.max(0,n.comparisons.total-(p-1)*20))&&n.comparisons.items.every(v=>validPriceComparison(v,n.observed_at))&&new Set(n.comparisons.items.map(v=>v.quote_id)).size===n.comparisons.items.length)}
export function validPriceComparisonDetail(n,id){return Boolean(exact(n,['observed_at','comparison'])&&validPriceComparison(n.comparison,n.observed_at)&&n.comparison.quote_id===id)}
export function createPriceComparisonApi({runtime=()=>apiRuntime,baseUrl=apiOrigin}={}){
 function call(t,path,check){if(!t)throw new ServiceError('unauthorized','请登录当前运营身份');if(!baseUrl)throw new ServiceError('unconfigured','价格比较尚未配置');return new Promise((resolve,reject)=>{try{runtime().request({url:baseUrl.replace(/\/$/,'')+path,method:'GET',timeout:15000,header:{Authorization:`Bearer ${t}`},success:({statusCode,data})=>{if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',503:'unavailable'})[statusCode]||'server','价格比较暂不可读取，请核对身份后刷新'));if(data?.code!==0||!check(data.data))return reject(new ServiceError('protocol','价格比较响应异常，请重新读取'));resolve(data.data)},fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})}
 return {list(t,p=1){if(!Number.isInteger(p)||p<1||p>1000000)throw new ServiceError('invalid','分页无效');return call(t,`/api/admin/price-monitor/comparisons?page=${p}&page_size=20`,n=>validPriceComparisonPage(n,p))},detail(t,id){if(!safeId(id))throw new ServiceError('invalid','报价编号无效');return call(t,`/api/admin/price-monitor/comparisons/${id}`,n=>validPriceComparisonDetail(n,id))}}
}
