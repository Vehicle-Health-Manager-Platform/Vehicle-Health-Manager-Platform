import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {exact,safeId,validPublic} from './experience-publication.js'
import {ServiceError,serviceFailure} from './service-catalog.js'

export const validExperienceReference=n=>typeof n==='string'&&/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/.test(n)
export const experienceReferenceRoute=id=>validExperienceReference(id)?`/pages/community/experiences?experience_id=${id}`:''
export function parseExperienceReference(raw){if(typeof raw!=='string'||raw.length>256)return '';const value=raw.trim();if(validExperienceReference(value))return value;const prefix='/pages/community/experiences?experience_id=';const id=value.startsWith(prefix)?value.slice(prefix.length):'';return validExperienceReference(id)&&experienceReferenceRoute(id)===value?id:''}
export function experienceReferenceOptions(q){if(exact(q,[]))return {valid:true,reference:''};return exact(q,['experience_id'])&&validExperienceReference(q.experience_id)?{valid:true,reference:q.experience_id}:{valid:false,reference:''}}
const stamp=n=>typeof n==='string'&&/^[1-9]\d{3}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(n)&&Number.isFinite(Date.parse(n))&&new Date(n).toISOString().replace('.000','')===n
export function validExperienceDetail(n,vehicle,id,model){return Boolean(exact(n,['vehicle_id','experience'])&&n.vehicle_id===vehicle&&safeId(vehicle)&&validExperienceReference(id)&&safeId(model)&&validPublic(n.experience)&&n.experience.experience_id===id&&n.experience.model_id===model&&stamp(n.experience.published_at))}
export function createExperienceDetailApi({baseUrl=apiOrigin,runtime=()=>apiRuntime}={}){
 return {detail(token,vehicle,id,model){
  if(!token)throw new ServiceError('unauthorized','请先登录车主账号')
  if(!safeId(vehicle)||!safeId(model)||!validExperienceReference(id))throw new ServiceError('invalid','请选择本人有效车型及经验引用')
  if(!baseUrl)throw new ServiceError('unconfigured','同款经验服务尚未配置')
  return new Promise((resolve,reject)=>{try{runtime().request({url:baseUrl.replace(/\/$/,'')+`/api/community/experiences/${id}?vehicle_id=${vehicle}`,method:'GET',timeout:15000,header:{Authorization:`Bearer ${token}`},success:({statusCode,data})=>{
   if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',503:'unavailable'})[statusCode]||'server',statusCode===404?'该经验已撤回、来源失效或不适用于当前车辆，请重新选择':'同款经验暂不可读取，请核对身份后重试'))
   if(data?.code!==0||!validExperienceDetail(data.data,vehicle,id,model))return reject(new ServiceError('protocol','经验引用响应异常，请刷新车辆与来源'))
   resolve(data.data)
  },fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})
 }}
}
