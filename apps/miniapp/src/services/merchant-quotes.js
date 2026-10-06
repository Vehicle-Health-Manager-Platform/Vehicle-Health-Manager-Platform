import { ServiceError, serviceFailure } from './service-catalog.js'
export { serviceFailure as quoteFailure }
const id = value => Number.isSafeInteger(value) && value > 0
const money = value => typeof value === 'string' && /^(0|[1-9]\d{0,7})\.\d{2}$/.test(value) && Number(value) > 0
export function quoteBody(state) {
  if (!id(state.projectId) || !money(state.price) || ![0,1].includes(state.status)) throw new ServiceError('invalid', '请选择项目，填写两位小数正数报价和上下架状态')
  return { standard_project_id: state.projectId, price: state.price, status: state.status }
}
const version = item => id(item?.merchant_project_id) && id(item.version_id) && id(item.version) && money(item.price)
const page = (result,n,size,row) => Array.isArray(result?.items) && result.items.length <= size && result.items.every(row)
  && Number.isSafeInteger(result.total) && result.total >= result.items.length && result.page === n && result.page_size === size
export function createQuotesApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/,'')
  function request(token,path,validate,body,key) {
    if (!token) throw new ServiceError('unauthorized','请先登录对应账号')
    if (!endpoint) throw new ServiceError('unconfigured','报价服务尚未配置')
    return new Promise((resolve,reject) => {
      try { runtime().request({ url:endpoint+path,method:body?'POST':'GET',data:body,timeout:15000,
        header:{Authorization:`Bearer ${token}`,...(key?{'Idempotency-Key':key}:{})},
        success:({statusCode,data})=>{
          if(statusCode<200 || statusCode>=300) {
            const kinds={400:'invalid',401:'unauthorized',403:'forbidden',404:'missing',503:'unavailable'}
            const messages={400:'报价信息无效，请检查后重试',401:'登录已失效，请重新登录',403:'当前身份无权操作',404:'项目或报价已不可用',503:'报价服务暂不可用，请稍后重试'}
            return reject(new ServiceError(kinds[statusCode]||'server',messages[statusCode]||'报价请求未成功，请稍后重试'))
          }
          if(!Number.isInteger(statusCode) || data?.code!==0 || !validate(data.data))return reject(new ServiceError('protocol','报价响应异常，请重试'))
          resolve(data.data)
        },fail:()=>reject(serviceFailure()),
      }) } catch { reject(serviceFailure()) }
    })
  }
  return {
    ownerList(token,project,sort='price_asc',n=1) {
      if(!id(project) || !['price_asc','price_desc'].includes(sort) || !id(n) || n>1000000)throw new ServiceError('invalid','项目或排序无效')
      return request(token,`/api/service/project/${project}/merchants?sort=${sort}&page=${n}&page_size=20`,result=>page(result,n,20,item=>version(item)
        && id(item.merchant_id) && typeof item.merchant_name==='string' && typeof item.address==='string'))
    },
    ownList(token,n=1) {return request(token,`/api/merchant/projects?page=${n}&page_size=20`,result=>page(result,n,20,item=>version(item)
      && id(item.standard_project_id) && typeof item.project_name==='string' && [0,1].includes(item.status) && typeof item.available==='boolean'))},
    standards(token,n=1) {return request(token,`/api/merchant/standard-projects?page=${n}&page_size=20`,result=>page(result,n,20,item=>id(item.id) && typeof item.project_name==='string'))},
    save(token,body,key) {
      if(!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(key))throw new ServiceError('invalid','提交信息无效')
      return request(token,'/api/merchant/projects',item=>version(item) && item.price===body.price && item.status===body.status,body,key)
    },
  }
}
export const quotesApi = createQuotesApi({ baseUrl:import.meta.env?.VITE_API_BASE_URL,runtime:()=>uni })
