import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
import { ServiceError,serviceFailure } from './service-catalog.js'
const id=v=>Number.isSafeInteger(v)&&v>0
const exact=(r,keys)=>Boolean(r&&typeof r==='object'&&!Array.isArray(r)&&Object.keys(r).length===keys.length&&keys.every(k=>Object.hasOwn(r,k)))
const text=v=>typeof v==='string'&&v.trim()===v&&v.length>0&&!/[\u0000-\u001f\u007f-\u009f]/.test(v)
export function nativeRegion(detail){
  if(!Array.isArray(detail?.value)||detail.value.length!==3||!detail.value.every(text)||!Array.isArray(detail.code)||detail.code.length!==3||typeof detail.code[2]!=='string'||!/^\d{6}$/.test(detail.code[2]))
    throw new ServiceError('invalid','未取得完整省市区，请重新选择或查看全部门店')
  return {code:detail.code[2],names:[...detail.value]}
}
export function validStorefront(result,merchant,project,page,size=20){
  if(!exact(result,['merchant','offers']))return false
  const shop=result.merchant,offers=result.offers
  if(!exact(shop,['merchant_id','name','address','merchant_type','region_code'])||shop.merchant_id!==merchant||!text(shop.name)||!text(shop.address)||!Number.isInteger(shop.merchant_type)||shop.merchant_type<1||shop.merchant_type>6
    ||shop.region_code!==null&&(typeof shop.region_code!=='string'||!/^\d{6}$/.test(shop.region_code)))return false
  if(!exact(offers,['items','total','page','page_size'])||offers.page!==page||offers.page_size!==size||!Number.isSafeInteger(offers.total)||offers.total<0||!Array.isArray(offers.items)||offers.items.length>size||offers.total<offers.items.length)return false
  return new Set(offers.items.map(r=>r.merchant_project_id)).size===offers.items.length&&offers.items.every(r=>exact(r,['merchant_project_id','standard_project_id','project_name','category','price','version_id','version'])
    &&id(r.merchant_project_id)&&id(r.standard_project_id)&&(!project||r.standard_project_id===project)&&text(r.project_name)&&Number.isInteger(r.category)&&r.category>=1&&r.category<=6
    &&typeof r.price==='string'&&/^(0|[1-9]\d{0,7})\.\d{2}$/.test(r.price)&&Number(r.price)>0&&id(r.version_id)&&id(r.version))
}
export function createMerchantDiscoveryApi({baseUrl,runtime}){
  const endpoint=(baseUrl||'').replace(/\/$/,'')
  return {detail(token,merchant,project=0,page=1){
    if(!token)throw new ServiceError('unauthorized','请先登录车主账号')
    if(!endpoint)throw new ServiceError('unconfigured','门店详情服务尚未配置')
    if(!id(merchant)||project!==0&&!id(project)||!id(page)||page>1000000)throw new ServiceError('invalid','门店或项目参数无效')
    return new Promise((resolve,reject)=>{
      const fail=(kind,message)=>reject(new ServiceError(kind,message))
      try{runtime().request({url:`${endpoint}/api/service/merchant/${merchant}?page=${page}&page_size=20${project?`&project_id=${project}`:''}`,method:'GET',timeout:15000,header:{Authorization:`Bearer ${token}`},
        success:({statusCode,data})=>{
          const failures={400:['invalid','门店查询条件无效，请重新选择'],401:['unauthorized','登录已失效，请重新登录'],403:['forbidden','请使用车主账号'],404:['missing','门店或项目已不可用，请重新选择'],503:['unavailable','门店详情暂不可用，请稍后重试']}
          if(failures[statusCode])return fail(...failures[statusCode])
          if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return fail('server','门店查询未成功，请重试')
          try{if(data?.code!==0||!validStorefront(data.data,merchant,project,page))return fail('protocol','门店详情响应异常，请重试')}
          catch{return fail('protocol','门店详情响应异常，请重试')}
          resolve(data.data)
        },fail:()=>reject(serviceFailure()),
      })}catch{reject(serviceFailure())}
    })
  }}
}
export const merchantDiscoveryApi=createMerchantDiscoveryApi({baseUrl:apiOrigin,runtime:()=>apiRuntime})

export function canReturnToDiscovery(pages){return Array.isArray(pages)&&pages.length>1&&['pages/service/detail','pages/service/merchant','pages/order/book'].includes(pages[pages.length-2]?.route)}
