import {ServiceError} from './service-catalog.js'
// These loaders are used by the real pages; reject mixed versions from separate read requests.
function compatible(context,history){if(context&&history.total!==context.revision)throw new ServiceError('protocol','月结版本在读取期间变化，请刷新核对')}
async function detail(api,t,role,id,p){if(!id)return {detail:null,facts:null};const [value,facts]=await Promise.all([api.detail(t,role,id),api.facts(t,role,id,p)]);if(facts.total!==value.summary.fact_count)throw new ServiceError('protocol','快照事实数量不符，请刷新核对');return {detail:value,facts}}
export async function loadAdminMonthlyAssessment({api,merchantsApi,token,page=1,merchantPage=1,merchant=null,month,detailId=0,factPage=1}){
 const context=merchant?api.context(token,'admin',merchant.id,month).catch(e=>{if(e.kind==='missing')return null;throw e}):Promise.resolve(null)
 const [merchants,records,current,selected]=await Promise.all([merchantsApi.merchants(token,merchantPage),merchant?api.history(token,'admin',merchant.id,month,page):api.list(token,page),context,detail(api,token,'admin',detailId,factPage)])
 compatible(current,records);if(selected.detail&&merchant&&(selected.detail.merchant_id!==merchant.id||selected.detail.period_month!==month))throw new ServiceError('protocol','快照与所选门店月份不符')
 return {...records,merchants,context:current,...selected,selection_error:Boolean(merchant&&!current)}
}
export async function loadMerchantMonthlyAssessment({api,token,month,page=1,detailId=0,factPage=1}){
 const staff=await api.staff(token,month);if(staff.role==='STAFF')return {staff,context:null,items:[],total:0,page:1,page_size:20,detail:null,facts:null}
 const [context,history,selected]=await Promise.all([api.context(token,'merchant',null,month),api.history(token,'merchant',null,month,page),detail(api,token,'merchant',detailId,factPage)])
 compatible(context,history);if(context.merchant.id!==staff.merchant_id||context.revision!==staff.revision||history.items.some(r=>r.merchant_id!==staff.merchant_id)||selected.detail&&(selected.detail.merchant_id!==staff.merchant_id||selected.detail.period_month!==month))throw new ServiceError('protocol','当前身份或快照版本已变化，请刷新核对')
 return {...history,staff,context,...selected}
}
