import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {ServiceError,serviceFailure} from './service-catalog.js'

export function createGovernedTransport({baseUrl=apiOrigin,runtime=()=>apiRuntime}={}){
 return function call(token,path,check,{body,key,method='GET',binary=false}={}){
  if(!token)throw new ServiceError('unauthorized','请登录当前身份')
  if(!baseUrl)throw new ServiceError('unconfigured','服务尚未配置')
  return new Promise((resolve,reject)=>{try{runtime().request({url:baseUrl.replace(/\/$/,'')+path,method,data:body,timeout:20000,...(binary?{responseType:'arraybuffer'}:{}),header:{Authorization:`Bearer ${token}`,...(body?{'Content-Type':'application/json'}:{}),...(key?{'Idempotency-Key':key}:{})},success:r=>{
   if(!Number.isInteger(r.statusCode)||r.statusCode<200||r.statusCode>=300)return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',409:'conflict',422:'rejected',503:'unavailable'})[r.statusCode]||'server','操作未完成，请核对当前身份、来源和版本'))
   const value=binary?r.data:r.data?.data
   if((!binary&&r.data?.code!==0)||!check(value))return reject(new ServiceError('protocol','服务响应异常，请重新核验'))
   resolve(value)
  },fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}})
 }
}
