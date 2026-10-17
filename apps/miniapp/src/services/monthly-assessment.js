import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {exact,safeId} from './experience-publication.js'
import {validAssessmentMerchant,validAssessmentPolicy,assessmentMonthUtc} from './assessment-policies.js'
import {ServiceError,serviceFailure} from './service-catalog.js'
export const METRIC_LABELS={acquisition:'有效拉新',coupon:'实际券使用',process:'施工过程'}
export const FACT_STATES={INCLUDED:'计入',INCOMPLETE:'证据不齐全',EXCLUDED:'剔除',UNKNOWN:'来源未知'}
export const EVIDENCE_STATES={PRESENT:'齐全',MISSING:'缺项',UNKNOWN:'来源未知'}
export const ASSESSMENT_REASONS={POLICY_UNKNOWN:'没有可证明覆盖整月的政策',NO_VALID_DENOMINATOR:'没有有效分母',SOURCE_UNKNOWN:'来源尚不可核验',VERIFIED:'来源已核验',TEST_SOURCE:'测试来源',NOT_QUALIFIED:'未达有效新客资格',INVITE_SOURCE_UNKNOWN:'邀请资格来源未知',VERIFIED_QUALIFICATION:'有效新客资格',COMPLETION_SOURCE_UNKNOWN:'完成凭证来源未知',EVIDENCE_SOURCE_UNKNOWN:'过程证据来源未知',REQUIRED_EVIDENCE_MISSING:'缺少必需证据',VERIFIED_COMPLETION:'可信完成工单',ORDER_SOURCE_UNKNOWN:'订单来源未知',CASH_SOURCE_UNKNOWN:'金额券来源未知',FULL_REFUND_ORIGINAL_MONTH:'全额退款，原使用月剔除',VERIFIED_CASH_USE:'已完成订单用券',COMPLETION_RECEIPT_MISSING:'缺少可信完成凭证',VERIFIED_SERVICE_USE:'独立服务券核销',SERVICE_SOURCE_UNKNOWN:'服务券来源未知'}
const count=n=>Number.isSafeInteger(n)&&n>=0&&n<Number.MAX_SAFE_INTEGER
const id=n=>safeId(n)&&n<Number.MAX_SAFE_INTEGER
const hash=n=>typeof n==='string'&&/^[a-f0-9]{64}$/.test(n)
const stamp=n=>typeof n==='string'&&/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(n)&&Number.isFinite(Date.parse(n))&&new Date(n).toISOString().replace('.000','')===n
export const validAssessmentMonth=m=>typeof m==='string'&&/^[1-9]\d{3}-(?:0[1-9]|1[0-2])$/.test(m)
export function nextAssessmentMonth(m){if(!validAssessmentMonth(m)||m==='9999-12')return '';const [y,n]=m.split('-').map(Number);return `${n===12?y+1:y}-${String(n===12?1:n+1).padStart(2,'0')}`}
export function defaultAssessmentMonth(now=Date.now()){const d=new Date(now+8*3600000),y=d.getUTCFullYear(),m=d.getUTCMonth();return `${m===0?y-1:y}-${String(m===0?12:m).padStart(2,'0')}`}
const cents=n=>typeof n==='string'&&/^(?:(?:0|[1-9]\d?)\.\d{2}|100\.00)$/.test(n)?Math.round(Number(n)*100):null
const ratio=(a,b,w)=>Math.floor((2*Math.min(a,b)*w*100+b)/(2*b))
export function validFrozenAssessmentPolicy(n){if(!n||!Object.hasOwn(n,'stopped_at'))return false;const {stopped_at,...policy}=n;return validAssessmentPolicy(policy)&&policy.status!=='DRAFT'&&(policy.status==='STOPPED'?stamp(stopped_at):stopped_at===null)}
const coversMonth=(p,m)=>validFrozenAssessmentPolicy(p)&&p.valid_from<=assessmentMonthUtc(m)&&p.valid_to>=assessmentMonthUtc(nextAssessmentMonth(m))&&p.published_at<=assessmentMonthUtc(m)&&(p.status!=='STOPPED'||p.stopped_at>=assessmentMonthUtc(nextAssessmentMonth(m)))
function validSummary(n,policy){if(!exact(n,['state','metrics','total_score','grade','fact_count'])||!count(n.fact_count)||!exact(n.metrics,Object.keys(METRIC_LABELS)))return false;let total=0,known=true,minimumFacts=0
 for(const [k,w] of [['acquisition',40],['coupon',40],['process',20]]){const m=n.metrics[k];if(!exact(m,['numerator','denominator','state','score','reason'])||!count(m.numerator)||!count(m.denominator)||m.numerator>1000000||m.denominator>1000000||!['KNOWN','UNKNOWN'].includes(m.state)||!['POLICY_UNKNOWN','NO_VALID_DENOMINATOR','SOURCE_UNKNOWN','VERIFIED'].includes(m.reason)||k==='process'&&m.numerator>m.denominator)return false
  if(policy===null){if(m.state!=='UNKNOWN'||m.reason!=='POLICY_UNKNOWN'||m.denominator!==0||m.numerator!==0)return false}else if(k!=='process'&&m.denominator!==policy[k==='acquisition'?'acquisition_target':'coupon_target'])return false
  minimumFacts+=k==='process'?m.denominator:m.numerator
  if(m.state==='KNOWN'){if(!policy||!m.denominator||m.reason!=='VERIFIED'||cents(m.score)!==ratio(m.numerator,m.denominator,w))return false;total+=cents(m.score)}else{known=false;if(m.score!==null||m.reason==='VERIFIED'||m.reason==='NO_VALID_DENOMINATOR'&&m.denominator!==0)return false}
 }
 if(minimumFacts>n.fact_count)return false
 if(!known)return n.state==='UNKNOWN'&&n.total_score===null&&n.grade===null
 if(n.state!=='CALCULATED'||cents(n.total_score)!==total||!exact(n.grade,['code','label']))return false
 const grade=policy.grade_thresholds.find(g=>total>=cents(g.min_score));return Boolean(grade&&n.grade.code===grade.code&&n.grade.label===grade.label)
}
export function validAssessmentSnapshot(n){if(!exact(n,['id','merchant_id','period_month','revision','facts_hash','calculation_version','timezone','policy','summary','captured_from','captured_to'])||!id(n.id)||!id(n.merchant_id)||!id(n.revision)||!validAssessmentMonth(n.period_month)||!nextAssessmentMonth(n.period_month)||!hash(n.facts_hash)||n.calculation_version!=='R8B_CALC_V1'||n.timezone!=='Asia/Shanghai'||!stamp(n.captured_from)||!stamp(n.captured_to)||Date.parse(n.captured_to)<Date.parse(n.captured_from)||Date.parse(n.captured_from)<Date.parse(assessmentMonthUtc(nextAssessmentMonth(n.period_month))))return false
 if(n.policy!==null&&(!validFrozenAssessmentPolicy(n.policy)||n.policy.merchant_id!==n.merchant_id||n.policy.valid_from>assessmentMonthUtc(n.period_month)||n.policy.valid_to<assessmentMonthUtc(nextAssessmentMonth(n.period_month))||n.policy.published_at>assessmentMonthUtc(n.period_month)||n.policy.status==='STOPPED'&&n.policy.stopped_at<assessmentMonthUtc(nextAssessmentMonth(n.period_month))))return false
 return validSummary(n.summary,n.policy)
}
export function validAssessmentContext(n,admin){if(!exact(n,['merchant','period_month','timezone','server_now','month_end','revision','latest','policy','state','can_settle'])||!validAssessmentMerchant(n.merchant)||!validAssessmentMonth(n.period_month)||!nextAssessmentMonth(n.period_month)||n.timezone!=='Asia/Shanghai'||!stamp(n.server_now)||n.month_end!==assessmentMonthUtc(nextAssessmentMonth(n.period_month))||!count(n.revision)||!(n.policy===null||coversMonth(n.policy,n.period_month)&&n.policy.merchant_id===n.merchant.id))return false
 const ended=n.server_now>=n.month_end;return n.state===(!ended?'MONTH_OPEN':n.policy===null?'POLICY_UNKNOWN':'READY')&&n.can_settle===Boolean(admin&&ended)&&(n.revision===0?n.latest===null:validAssessmentSnapshot(n.latest)&&n.latest.revision===n.revision&&n.latest.period_month===n.period_month&&n.latest.merchant_id===n.merchant.id)
}
export function validAssessmentFact(n){return Boolean(exact(n,['dimension','source_type','source_id','occurred_at','staff_id','state','reason','evidence','proof_hash'])&&['ACQUISITION','COUPON','PROCESS'].includes(n.dimension)&&['INVITE','ORDER','CASH_ORDER','UNPROVEN_ORDER','SERVICE_REDEMPTION'].includes(n.source_type)&&typeof n.source_id==='string'&&/^(?:[1-9]\d{0,15}|[a-f0-9-]{36})$/.test(n.source_id)&&(n.occurred_at===null||stamp(n.occurred_at))&&(n.staff_id===null||id(n.staff_id))&&Object.hasOwn(FACT_STATES,n.state)&&Object.hasOwn(ASSESSMENT_REASONS,n.reason)&&hash(n.proof_hash)&&n.evidence&&typeof n.evidence==='object'&&!Array.isArray(n.evidence)&&Object.entries(n.evidence).every(([k,v])=>k==='_proof_hash'?hash(v):k==='full_refund'?typeof v==='boolean':['CHECK_IN_CONFIRMED','PROTECTION_COMPLETE','REPORT_SUBMITTED','QUALITY_SIGNED'].includes(k)&&Object.hasOwn(EVIDENCE_STATES,v)))}
export function validStaffAssessment(n){if(!exact(n,['role','staff_id','merchant_id','period_month','timezone','state','revision','captured_from','captured_to','attribution'])||!['MERCHANT','STAFF'].includes(n.role)||!id(n.staff_id)||!id(n.merchant_id)||!validAssessmentMonth(n.period_month)||!nextAssessmentMonth(n.period_month)||n.timezone!=='Asia/Shanghai'||!count(n.revision)||!exact(n.attribution,['included','excluded','unknown'])||!Object.values(n.attribution).every(count))return false
 return n.revision===0?n.state==='NO_SNAPSHOT'&&n.captured_from===null&&n.captured_to===null&&Object.values(n.attribution).every(v=>v===0):n.state==='FROZEN'&&stamp(n.captured_from)&&stamp(n.captured_to)&&n.captured_from<=n.captured_to&&n.captured_from>=assessmentMonthUtc(nextAssessmentMonth(n.period_month))
}
export const validMonthlyAssessmentRequest=b=>Boolean(exact(b,['merchant_id','period_month','expected_revision'])&&id(b.merchant_id)&&validAssessmentMonth(b.period_month)&&nextAssessmentMonth(b.period_month)&&count(b.expected_revision))
export function assessmentSettlementRequest(context,shop,month){if(!validAssessmentContext(context,true)||!context.can_settle||context.merchant.id!==shop||context.period_month!==month)throw new ServiceError('invalid','请先读取所选门店及月份的最新结算状态');return {merchant_id:shop,period_month:month,expected_revision:context.revision}}
function paged(n,p,check){return exact(n,['items','total','page','page_size'])&&n.page===p&&n.page_size===20&&count(n.total)&&Array.isArray(n.items)&&n.items.length===Math.min(20,Math.max(0,n.total-(p-1)*20))&&n.items.every(check)}
export function createMonthlyAssessmentApi({runtime=()=>apiRuntime,baseUrl=apiOrigin}={}){
 function call(token,path,check,body,key){if(!token)throw new ServiceError('unauthorized','请登录当前身份');if(!baseUrl)throw new ServiceError('unconfigured','月结服务尚未配置');return new Promise((resolve,reject)=>{try{runtime().request({url:baseUrl.replace(/\/$/,'')+path,method:body?'POST':'GET',data:body,timeout:15000,header:{Authorization:`Bearer ${token}`,'content-type':'application/json',...(key?{'Idempotency-Key':key}:{})},success:({statusCode,data})=>{if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',409:'conflict',503:'unavailable'})[statusCode]||'server','月结请求未完成，请刷新或重试原请求'));if(data?.code!==0||!check(data.data))return reject(new ServiceError('protocol','月结响应异常，请保留原请求核对'));resolve(data.data)},fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})}
 function role(r){if(!['admin','merchant'].includes(r))throw new ServiceError('invalid','月结身份范围无效');return `/api/${r}/assessment`}
 function page(p){if(!id(p)||p>1000000)throw new ServiceError('invalid','分页无效')}
 function month(m){if(!validAssessmentMonth(m)||!nextAssessmentMonth(m))throw new ServiceError('invalid','请选择有效的北京时间月份')}
 function shop(r,n){if(r==='admin'&&!id(n)||r==='merchant'&&n!==null)throw new ServiceError('invalid','门店范围无效')}
 function selector(r,n,m){role(r);shop(r,n);month(m);return `period_month=${m}`+(r==='admin'?`&merchant_id=${n}`:'')}
 return {
  context(t,r,n,m){const q=selector(r,n,m);return call(t,`${role(r)}/month?${q}`,v=>validAssessmentContext(v,r==='admin')&&v.period_month===m&&(r!=='admin'||v.merchant.id===n))},
  list(t,p=1){page(p);return call(t,'/api/admin/assessment/snapshots?page='+p+'&page_size=20',n=>paged(n,p,validAssessmentSnapshot)&&new Set(n.items.map(v=>v.id)).size===n.items.length)},
  history(t,r,n,m,p=1){const q=selector(r,n,m);page(p);return call(t,`${role(r)}/history?${q}&page=${p}&page_size=20`,v=>paged(v,p,x=>validAssessmentSnapshot(x)&&x.period_month===m&&(r!=='admin'||x.merchant_id===n))&&new Set(v.items.map(x=>x.revision)).size===v.items.length&&v.items.every((x,i)=>i===0||x.revision<v.items[i-1].revision))},
  detail(t,r,n){if(!id(n))throw new ServiceError('invalid','快照编号无效');return call(t,`${role(r)}/snapshots/${n}`,v=>validAssessmentSnapshot(v)&&v.id===n)},
  facts(t,r,n,p=1){if(!id(n))throw new ServiceError('invalid','快照编号无效');page(p);return call(t,`${role(r)}/snapshots/${n}/facts?page=${p}&page_size=20`,v=>paged(v,p,validAssessmentFact)&&new Set(v.items.map(x=>`${x.dimension}:${x.source_type}:${x.source_id}`)).size===v.items.length)},
  staff(t,m){month(m);return call(t,`/api/merchant/assessment/staff?period_month=${m}`,v=>validStaffAssessment(v)&&v.period_month===m)},
  write(t,action,n,b,key){if(action!=='settle'||n!==0||!validMonthlyAssessmentRequest(b)||typeof key!=='string'||!/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(key))throw new ServiceError('invalid','月结原请求无效');return call(t,'/api/admin/assessment/snapshots',v=>validAssessmentSnapshot(v)&&v.merchant_id===b.merchant_id&&v.period_month===b.period_month&&v.revision===b.expected_revision+1,b,key)}
 }
}
