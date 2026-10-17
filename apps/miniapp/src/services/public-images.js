import {createGovernedTransport} from './governed-content-api.js'
import {exact,safeId} from './experience-publication.js'
import {createScopedIntentRecovery,intentUuid} from './scoped-intent-recovery.js'
import {TEXT_REASONS} from './public-review-texts.js'
import {ServiceError} from './service-catalog.js'
export const PUBLIC_IMAGE_CONSENT='R8C_IMAGE_V1'
const states=['PENDING','APPROVED','REJECTED','WITHDRAWN'],hash=n=>typeof n==='string'&&/^[a-f0-9]{64}$/.test(n)
const stamp=n=>typeof n==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(n)&&Number.isFinite(Date.parse(n))
const dimensions=n=>safeId(n.width)&&safeId(n.height)&&n.width<=4096&&n.height<=4096&&n.width*n.height<=6000000
const receipt=n=>exact(n,['application_id','revision','status'])&&safeId(n.application_id)&&safeId(n.revision)&&states.includes(n.status)
const source=n=>exact(n,['archive_id','source_file_id'])&&safeId(n.archive_id)&&safeId(n.source_file_id)
function row(n,own){return exact(n,['application_id','revision','status','preview_hash','experience_id','model_id','width','height','created_at','reason_code','source_available',...(own?['public_id','visible']:[])])&&receipt({application_id:n.application_id,revision:n.revision,status:n.status})&&hash(n.preview_hash)&&intentUuid(n.experience_id)&&safeId(n.model_id)&&dimensions(n)&&stamp(n.created_at)&&(n.reason_code===null||Object.hasOwn(TEXT_REASONS,n.reason_code))&&typeof n.source_available==='boolean'&&(own?(n.public_id===null||intentUuid(n.public_id))&&typeof n.visible==='boolean'&&(!n.visible||n.status==='APPROVED'&&intentUuid(n.public_id)):n.status==='PENDING')}
const page=(n,p,check)=>exact(n,['items','total','page','page_size'])&&Number.isSafeInteger(n.total)&&n.total>=0&&n.page===p&&n.page_size===20&&Array.isArray(n.items)&&n.items.length===Math.min(20,Math.max(0,n.total-(p-1)*20))&&n.items.every(check)
export function validImageSelection(b){return exact(b,['experience_id','archive_id','source_file_id','copy_file_id'])&&intentUuid(b.experience_id)&&safeId(b.archive_id)&&safeId(b.source_file_id)&&safeId(b.copy_file_id)&&b.source_file_id!==b.copy_file_id}
export function validPublicImageBody(b,a){
 if(a==='submit')return exact(b,['experience_id','archive_id','source_file_id','copy_file_id','preview_hash','consent_version','consent'])&&validImageSelection({experience_id:b.experience_id,archive_id:b.archive_id,source_file_id:b.source_file_id,copy_file_id:b.copy_file_id})&&hash(b.preview_hash)&&b.consent_version===PUBLIC_IMAGE_CONSENT&&b.consent===true
 if(a==='withdraw')return exact(b,['expected_revision'])&&safeId(b.expected_revision)
 return a==='moderate'&&exact(b,['expected_revision','decision','reason_code'])&&safeId(b.expected_revision)&&(b.decision==='APPROVE'?b.reason_code===null:b.decision==='REJECT'&&Object.hasOwn(TEXT_REASONS,b.reason_code))
}
export function publicImageRoute(q){if(exact(q,['mode'])&&['owner','review'].includes(q.mode))return {mode:q.mode,reference:''};if(exact(q,['mode','experience_id'])&&['owner','public'].includes(q.mode)&&intentUuid(q.experience_id))return {mode:q.mode,reference:q.experience_id};return null}
export function createPublicImageApi(options={}){
 const call=createGovernedTransport(options),id=n=>{if(!safeId(n))throw new ServiceError('invalid','编号无效')},pg=p=>{if(!safeId(p)||p>1000000)throw new ServiceError('invalid','页码无效')}
 const png=n=>exact(n,['png_base64'])&&typeof n.png_base64==='string'&&n.png_base64.length<=2796204&&/^iVBORw0KGgo[A-Za-z0-9+/]*={0,2}$/.test(n.png_base64)
 return {
  sources(t,ref,p=1){pg(p);if(!intentUuid(ref))throw new ServiceError('invalid','请选择已获批的本人经验引用');return call(t,`/api/owner/community/image-sources?experience_id=${ref}&page=${p}&page_size=20`,n=>page(n,p,source))},
  own(t,p=1){pg(p);return call(t,`/api/owner/community/images?page=${p}&page_size=20`,n=>page(n,p,r=>row(r,true)))},
  pending(t,p=1){pg(p);return call(t,`/api/admin/community/images?page=${p}&page_size=20`,n=>page(n,p,r=>row(r,false)))},
  preview(t,b){if(!validImageSelection(b))throw new ServiceError('invalid','请选择不同的原图和脱敏副本');return call(t,'/api/owner/community/images/preview',n=>exact(n,['preview_hash','png_base64','width','height','model_id'])&&hash(n.preview_hash)&&dimensions(n)&&safeId(n.model_id)&&typeof n.png_base64==='string'&&n.png_base64.length<=2796204&&/^iVBORw0KGgo[A-Za-z0-9+/]*={0,2}$/.test(n.png_base64),{method:'POST',body:b})},
  write(t,a,n,b,key){if(!intentUuid(key)||!validPublicImageBody(b,a)||(a==='submit'?n!==0:!safeId(n)))throw new ServiceError('invalid','图片授权操作无效');const path=a==='submit'?'/api/owner/community/images':`/api/${a==='withdraw'?'owner':'admin'}/community/images/${n}/${a}`;return call(t,path,r=>receipt(r)&&(a==='submit'?r.revision===1&&r.status==='PENDING':r.application_id===n&&r.revision===b.expected_revision+1&&r.status===(a==='withdraw'?'WITHDRAWN':b.decision==='APPROVE'?'APPROVED':'REJECTED')),{body:b,key,method:'POST'})},
  previewBytes(t,role,n){id(n);if(!['owner','admin'].includes(role))throw new ServiceError('invalid','身份无效');return call(t,`/api/${role}/community/images/${n}/preview?format=base64`,png)},
  list(t,v,ref){id(v);if(!intentUuid(ref))throw new ServiceError('invalid','公开经验引用无效');return call(t,`/api/community/images?vehicle_id=${v}&experience_id=${ref}`,n=>exact(n,['vehicle_id','experience_id','items'])&&n.vehicle_id===v&&n.experience_id===ref&&Array.isArray(n.items)&&n.items.length<=20&&n.items.every(r=>exact(r,['public_id','width','height','published_at'])&&intentUuid(r.public_id)&&dimensions(r)&&stamp(r.published_at))&&new Set(n.items.map(r=>r.public_id)).size===n.items.length)},
  bytes(t,v,ref){id(v);if(!intentUuid(ref))throw new ServiceError('invalid','公开图片引用无效');return call(t,`/api/community/images/${ref}?vehicle_id=${v}&format=base64`,png)},
 }
}
export const createPublicImageRecovery=options=>createScopedIntentRecovery({...options,namespace:'public-images',permanent:v=>v.action==='submit',validate:(v,r,s)=>validPublicImageBody(v.body,v.action)&&(v.action==='submit'?v.id===0:safeId(v.id))&&(r==='admin'?s==='review'&&v.action==='moderate':s==='owner'&&['submit','withdraw'].includes(v.action))})
