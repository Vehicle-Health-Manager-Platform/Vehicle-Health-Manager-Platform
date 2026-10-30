import {createGovernedTransport} from './governed-content-api.js'
import {exact,safeId} from './experience-publication.js'
import {createScopedIntentRecovery,intentUuid} from './scoped-intent-recovery.js'
import {ServiceError} from './service-catalog.js'
const LEGACY_KNOWLEDGE_FIELDS=['title','publisher','document_version','source_reference','license_reference','license_confirmed','model_id','valid_from','valid_to','search_allowed','excerpt_allowed','ai_allowed','body']
export const EMBEDDING_POLICY='KNOWLEDGE_EMBEDDING_V1'
export const KNOWLEDGE_FIELDS=[...LEGACY_KNOWLEDGE_FIELDS,'embedding_allowed','embedding_policy_version']
const payloadFields=n=>n&&typeof n==='object'&&('embedding_allowed' in n||'embedding_policy_version' in n)?KNOWLEDGE_FIELDS:LEGACY_KNOWLEDGE_FIELDS

const stamp=n=>typeof n==='string'&&/^[1-9][0-9]{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(n)&&Number.isFinite(Date.parse(n))&&new Date(n).toISOString().replace('.000','')===n
const text=(n,max,lines=false)=>typeof n==='string'&&n===n.trim()&&[...n].length>=1&&[...n].length<=max&&!(lines?/[\u0000-\u0009\u000b-\u001f\u007f\p{Cf}\p{Cs}]/u:/[\p{Cc}\p{Cf}\p{Cs}]/u).test(n)
const hash=n=>typeof n==='string'&&/^[a-f0-9]{64}$/.test(n)
export function validKnowledgePayload(n){return Boolean(exact(n,payloadFields(n))&&(!('embedding_allowed' in n||'embedding_policy_version' in n)||typeof n.embedding_allowed==='boolean'&&n.embedding_policy_version===EMBEDDING_POLICY)&&text(n.title,120)&&text(n.publisher,120)&&text(n.document_version,80)&&text(n.source_reference,500)&&text(n.license_reference,500)&&text(n.body,10000,true)&&safeId(n.model_id)&&stamp(n.valid_from)&&stamp(n.valid_to)&&Date.parse(n.valid_from)<Date.parse(n.valid_to)&&['license_confirmed','search_allowed','excerpt_allowed','ai_allowed'].every(k=>typeof n[k]==='boolean'))}
export function validKnowledgeBody(b,a){if(a==='status')return exact(b,['expected_revision','status'])&&safeId(b.expected_revision)&&['PUBLISHED','STOPPED'].includes(b.status);if(!['create','update'].includes(a)||!exact(b,[...payloadFields(b),...(a==='update'?['expected_revision']:[])]))return false;const p=Object.fromEntries(payloadFields(b).map(k=>[k,b[k]]));return validKnowledgePayload(p)&&(a==='create'||safeId(b.expected_revision))}
export const embeddingStateLabel=n=>({DISABLED:'索引已关闭',NOT_ALLOWED:'当前资料未获准索引',NOT_QUEUED:'等待发现',QUEUED:'等待生成',RUNNING:'正在生成',SUCCEEDED:'索引已生成',FAILED:'生成失败',CANCELLED:'索引已取消'})[n]||'索引状态未知'
export function validKnowledgeEmbedding(n){
 if(!exact(n,['state','error_code','profile_hash','updated_at']))return false
 if(n.state==='DISABLED')return n.error_code===null&&n.profile_hash===null&&n.updated_at===null
 if(!hash(n.profile_hash))return false
 if(['NOT_ALLOWED','NOT_QUEUED'].includes(n.state))return n.error_code===null&&n.updated_at===null
 if(!stamp(n.updated_at))return false
 if(['QUEUED','RUNNING','SUCCEEDED'].includes(n.state))return n.error_code===null
 return n.state==='FAILED'?['WORKER_LOST','UNAVAILABLE','INVALID_VECTOR','LIMIT_EXCEEDED'].includes(n.error_code):n.state==='CANCELLED'&&['SOURCE_CHANGED','FEATURE_DISABLED'].includes(n.error_code)
}
export const validKnowledgeRow=n=>Boolean(exact(n,['id','revision','status','source_hash','public_id','payload','created_at','published_at',...(n&&Object.hasOwn(n,'embedding')?['embedding']:[])])&&(!Object.hasOwn(n,'embedding')||validKnowledgeEmbedding(n.embedding))&&safeId(n.id)&&safeId(n.revision)&&['DRAFT','PUBLISHED','STOPPED'].includes(n.status)&&hash(n.source_hash)&&(n.public_id===null||intentUuid(n.public_id))&&validKnowledgePayload(n.payload)&&stamp(n.created_at)&&(n.published_at===null||stamp(n.published_at))&&(n.status!=='PUBLISHED'||intentUuid(n.public_id)&&stamp(n.published_at)))
const publicFields=['reference','revision','model_id','title','publisher','document_version','source_reference','valid_from','valid_to','excerpt','ai_allowed']
const chunkFields=['chunk','start_offset','end_offset']
const hasChunk=n=>n&&chunkFields.some(k=>Object.hasOwn(n,k))
const chunkId=n=>Number.isInteger(n)&&n>=0&&n<=63
const snippet=(n,max)=>typeof n==='string'&&n.trim().length>0&&[...n].length<=max&&!/[\p{Cc}\p{Cf}\p{Cs}]/u.test(n.replace(/\n/g,''))
export function validKnowledgeReference(n,model,detail=false){
 const positioned=hasChunk(n)
 return Boolean(exact(n,[...publicFields,...(detail?['body']:[]),...(positioned?chunkFields:[])])&&intentUuid(n.reference)&&safeId(n.revision)&&n.model_id===model&&safeId(model)&&text(n.title,120)&&text(n.publisher,120)&&text(n.document_version,80)&&text(n.source_reference,500)&&stamp(n.valid_from)&&stamp(n.valid_to)&&Date.parse(n.valid_from)<Date.parse(n.valid_to)&&snippet(n.excerpt,positioned?256:800)&&typeof n.ai_allowed==='boolean'&&(!detail||snippet(n.body,positioned?256:800))&&(!positioned||chunkId(n.chunk)&&Number.isSafeInteger(n.start_offset)&&n.start_offset>=0&&Number.isSafeInteger(n.end_offset)&&n.end_offset>n.start_offset&&n.end_offset<=20000&&n.end_offset-n.start_offset===n.excerpt.length&&(!detail||n.body===n.excerpt)))
}
const retrievalMeta=n=>n&&['VECTOR','KEYWORD'].includes(n.method)&&(!Object.hasOwn(n,'fallback_reason')||n.method==='KEYWORD'&&['INDEX_NOT_READY','VECTOR_UNAVAILABLE','QUERY_LIMIT','CURSOR_PAGING'].includes(n.fallback_reason))
const resultKeys=(n,keys)=>[...keys,...(n&&Object.hasOwn(n,'fallback_reason')?['fallback_reason']:[])]
export const knowledgeRetrievalLabel=n=>n?.method==='VECTOR'?'本次按向量相似度排序（最多5份），仅供资料线索，不代表诊断或内容已核实':n?.fallback_reason?({INDEX_NOT_READY:'向量资料尚未全部就绪，本次使用关键词',VECTOR_UNAVAILABLE:'向量服务暂不可用，本次使用关键词',QUERY_LIMIT:'问题超出向量查询格式或长度，本次使用关键词',CURSOR_PAGING:'本次继续关键词分页'})[n.fallback_reason]||'':'本次使用关键词检索'
export function knowledgeRoute(q){if(exact(q,[]))return {reference:''};if(exact(q,['reference'])&&intentUuid(q.reference))return {reference:q.reference};if(exact(q,['reference','chunk'])&&intentUuid(q.reference)&&typeof q.chunk==='string'&&/^(0|[1-9][0-9]?)$/.test(q.chunk)&&chunkId(Number(q.chunk)))return {reference:q.reference,chunk:Number(q.chunk)};return null}
export const knowledgeReference=(id,chunk=null)=>intentUuid(id)&&(chunk===null||chunkId(chunk))?`/pages/community/knowledge?reference=${id}${chunk===null?'':`&chunk=${chunk}`}`:''
export function createKnowledgeApi(options={}){
 const call=createGovernedTransport(options)
 const id=n=>{if(!safeId(n))throw new ServiceError('invalid','请选择本人车辆或有效资料')}
 return {
  models(t,p=1){id(p);if(p>1000000)throw new ServiceError('invalid','车型页码无效');return call(t,`/api/admin/knowledge/models?page=${p}&page_size=20`,n=>exact(n,['items','total','page','page_size'])&&Number.isSafeInteger(n.total)&&n.total>=0&&n.page===p&&n.page_size===20&&Array.isArray(n.items)&&n.items.length===Math.min(20,Math.max(0,n.total-(p-1)*20))&&n.items.every(r=>exact(r,['model_id','brand','series','year','config_name'])&&safeId(r.model_id)&&['brand','series','year','config_name'].every(k=>r[k]===null||typeof r[k]==='string')))},
  admin(t,p=1){id(p);if(p>1000000)throw new ServiceError('invalid','页码无效');return call(t,`/api/admin/knowledge/documents?page=${p}&page_size=20`,n=>exact(n,['items','total','page','page_size'])&&Number.isSafeInteger(n.total)&&n.total>=0&&n.page===p&&n.page_size===20&&Array.isArray(n.items)&&n.items.length===Math.min(20,Math.max(0,n.total-(p-1)*20))&&n.items.every(validKnowledgeRow)&&new Set(n.items.map(r=>r.id)).size===n.items.length)},
  write(t,a,n,b,key){if(!intentUuid(key)||!validKnowledgeBody(b,a)||(a==='create'?n!==0:!safeId(n)))throw new ServiceError('invalid','资料配置无效');const path=`/api/admin/knowledge/documents${a==='create'?'':`/${n}`}${a==='status'?'/status':''}`;return call(t,path,r=>validKnowledgeRow(r)&&(a==='create'?r.revision===1&&r.status==='DRAFT':r.id===n&&r.revision===b.expected_revision+1&&r.status===(a==='status'?b.status:'DRAFT'))&&Object.keys(b).filter(k=>!['expected_revision','status'].includes(k)).every(k=>r.payload[k]===b[k]),{body:b,key,method:a==='update'?'PUT':'POST'})},
  search(t,v,model,q,cursor=null){id(v);id(model);if(!text(q,80)||cursor!==null&&!safeId(cursor))throw new ServiceError('invalid','请输入1至80字关键词');return call(t,`/api/knowledge/search?vehicle_id=${v}&q=${encodeURIComponent(q)}${cursor===null?'':`&cursor=${cursor}`}`,n=>exact(n,resultKeys(n,['vehicle_id','state','method','items','next_cursor']))&&n.vehicle_id===v&&retrievalMeta(n)&&['AVAILABLE','UNKNOWN','NO_MATCH','UNAVAILABLE'].includes(n.state)&&Array.isArray(n.items)&&n.items.length<=(n.method==='VECTOR'?5:20)&&n.items.every(r=>validKnowledgeReference(r,model)&&Boolean(hasChunk(r))===(n.method==='VECTOR'))&&new Set(n.items.map(r=>r.reference)).size===n.items.length&&(n.state==='AVAILABLE'?n.items.length>0:n.items.length===0)&&(n.method==='VECTOR'?cursor===null&&n.state==='AVAILABLE'&&n.next_cursor===null:n.next_cursor===null||safeId(n.next_cursor)&&(cursor===null||n.next_cursor<cursor)))},
  detail(t,v,model,ref,chunk=null){id(v);id(model);if(!intentUuid(ref)||chunk!==null&&!chunkId(chunk))throw new ServiceError('invalid','资料引用无效');return call(t,`/api/knowledge/documents/${ref}?vehicle_id=${v}${chunk===null?'':`&chunk=${chunk}`}`,n=>exact(n,['vehicle_id','document'])&&n.vehicle_id===v&&validKnowledgeReference(n.document,model,true)&&n.document.reference===ref&&(chunk===null?!hasChunk(n.document):n.document.chunk===chunk))},
  answer(t,v,model,question){id(v);id(model);if(!text(question,1000,true))throw new ServiceError('invalid','请输入1至1000字资料问题');return call(t,'/api/knowledge/answer',n=>exact(n,resultKeys(n,['vehicle_id','reply','model','provided_sources','method']))&&n.vehicle_id===v&&text(n.reply,20000,true)&&typeof n.model==='string'&&n.model.length>0&&retrievalMeta(n)&&n.fallback_reason!=='CURSOR_PAGING'&&Array.isArray(n.provided_sources)&&n.provided_sources.length>0&&n.provided_sources.length<=3&&n.provided_sources.every(r=>validKnowledgeReference(r,model)&&r.ai_allowed&&Boolean(hasChunk(r))===(n.method==='VECTOR'))&&new Set(n.provided_sources.map(r=>r.reference)).size===n.provided_sources.length,{body:{vehicle_id:v,question},method:'POST'})},
 }
}
export const createKnowledgeRecovery=options=>createScopedIntentRecovery({...options,namespace:'licensed-knowledge',permanent:v=>v.action==='create',validate:(v,r,s)=>r==='admin'&&s==='documents'&&validKnowledgeBody(v.body,v.action)&&(v.action==='create'?v.id===0:safeId(v.id))})
