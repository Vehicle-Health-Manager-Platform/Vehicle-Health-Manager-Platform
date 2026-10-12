import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
export class HealthObservationError extends Error {constructor(kind,message){super(message);this.kind=kind}}
export const healthMetrics=['OIL','BRAKE_PAD','TIRE','TRACTION_BATTERY','COOLANT','AUX_BATTERY']
export const healthLabels={OIL:'机油',BRAKE_PAD:'刹车片',TIRE:'轮胎',TRACTION_BATTERY:'动力电池',COOLANT:'冷却液',AUX_BATTERY:'低压电瓶'}
export const healthUnits={OIL:['KM','PERCENT'],BRAKE_PAD:['MM'],TIRE:['MM','KPA'],TRACTION_BATTERY:['PERCENT','V'],COOLANT:['CELSIUS'],AUX_BATTERY:['V','PERCENT']}
export const unitLabels={KM:'公里',PERCENT:'%（原始读数）',MM:'毫米',KPA:'千帕',V:'伏特',CELSIUS:'摄氏度'}
export const provenanceValues=['OWNER_MEASURED','OWNER_TRANSCRIBED']
export const provenanceLabels=['本人测量','本人抄录资料']
export const observationStatus={NOT_SET:'未录入（读数未知）',RECORDED_UNVERIFIED:'本人提供，未核验',SOURCE_UNAVAILABLE:'来源失效，读数不可用'}
const id=v=>Number.isSafeInteger(v)&&v>0
const revision=v=>Number.isInteger(v)&&v>=0&&v<=2147483647
const exact=(r,keys)=>r&&typeof r==='object'&&!Array.isArray(r)&&Object.keys(r).length===keys.length&&keys.every(k=>Object.hasOwn(r,k))
const date=v=>typeof v==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(v)&&Number(v.slice(0,4))>=1000&&Number.isFinite(new Date(v+'T00:00:00Z').getTime())&&new Date(v+'T00:00:00Z').toISOString().slice(0,10)===v
const text=(v,max)=>typeof v==='string'&&v.trim()===v&&v.length>0&&[...v].length<=max&&!/[\u0000-\u001f\u007f-\u009f]/.test(v)
const empty=m=>m&&typeof m==='object'&&!Array.isArray(m)&&!Object.keys(m).length
export function validMeasurement(metric,m,today){
  if(!healthMetrics.includes(metric)||!exact(m,['measurement_name','value','unit','measured_on','method','provenance']))return false
  if(!text(m.measurement_name,80)||!text(m.method,120)||!healthUnits[metric].includes(m.unit)||!provenanceValues.includes(m.provenance)
    ||!date(m.measured_on)||m.measured_on>today||typeof m.value!=='string'||! /^-?(0|[1-9][0-9]{0,8})(\.[0-9]{1,3})?$/.test(m.value))return false
  return (m.unit==='CELSIUS'||Number(m.value)>=0)&&(m.unit!=='PERCENT'||Number(m.value)<=100)
}
export function validObservation(row,today){
  if(!exact(row,['metric','revision','archive_id','recorded_date','measurement','status'])||!healthMetrics.includes(row.metric)||!revision(row.revision))return false
  if(row.status==='NOT_SET'||row.status==='SOURCE_UNAVAILABLE')return (row.status==='NOT_SET'||row.revision>0)&&row.archive_id===null&&row.recorded_date===null&&empty(row.measurement)
  return row.status==='RECORDED_UNVERIFIED'&&row.revision>0&&id(row.archive_id)&&(row.recorded_date===null||date(row.recorded_date))&&validMeasurement(row.metric,row.measurement,today)
}
export function observationForm(context){return {measurement_name:'',value:'',unit:'',measured_on:'',method:'',provenance:'',...(context.observation.archive_id===context.archive_id?context.observation.measurement:{})}}
export function observationBody(context,form,clear=false,operation=clear?'CLEAR':'RECORD'){
  if(!context||!healthMetrics.includes(context.metric)||!revision(context.observation?.revision)||context.observation.revision>=2147483647)throw new HealthObservationError('invalid','请先刷新来源读数')
  const measurement=clear?{}:Object.fromEntries(['measurement_name','value','unit','measured_on','method','provenance'].map(k=>[k,String(form[k]??'').trim()]))
  if(!clear&&!validMeasurement(context.metric,measurement,context.today))throw new HealthObservationError('invalid','请填写本类完整读数、单位、来源说明及有效日期')
  if(!['RECORD','CORRECT','CLEAR'].includes(operation)||(operation==='CLEAR')!==clear)throw new HealthObservationError('invalid','读数操作无效')
  if(operation==='CORRECT'&&(context.observation.status!=='RECORDED_UNVERIFIED'||context.observation.archive_id!==context.archive_id))throw new HealthObservationError('invalid','请先读取同来源的当前读数再修正')
  return {...(operation==='CORRECT'?{operation}:{}),metric:context.metric,expected_revision:context.observation.revision,measurement,
    replace_source:!clear&&context.observation.status!=='NOT_SET'&&context.observation.archive_id!==context.archive_id}
}
export function canReturnToHealth(pages){return Array.isArray(pages)&&pages.length>1&&['pages/archive/health','pages/archive/health-observation','pages/archive/health-history'].includes(pages[pages.length-2]?.route)}
export function createHealthObservationApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function request(token, path, method, body, key, validate) {
    if (!token) throw new HealthObservationError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new HealthObservationError('unconfigured', '读数服务尚未配置')
    return new Promise((resolve, reject) => {
      const fail = (kind, message) => reject(new HealthObservationError(kind, message))
      try {
        runtime().request({ url: `${endpoint}${path}`, method, data: body, timeout: 15000,
          header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
          success: ({ statusCode, data }) => {
            const errors = { 400: ['invalid', '请检查本类读数字段和版本'], 401: ['unauthorized', '登录已失效，请重新登录'],
              403: ['forbidden', '请使用车主账号'], 404: ['missing', '车辆或来源档案不可用，请重新选择'],
              409: ['conflict', '读数已变化，请刷新并复核'], 503: ['unavailable', '读数服务暂不可用，请稍后重试'] }
            if (errors[statusCode]) return fail(...errors[statusCode])
            if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300) return fail('server', '读数请求未成功，请重试')
            try { if (data?.code !== 0 || !validate(data.data)) return fail('protocol', '读数服务响应异常，请重试') }
            catch { return fail('protocol', '读数服务响应异常，请重试') }
            resolve(data.data)
          }, fail: () => fail('network', '无法连接读数服务，请检查网络后重试'),
        })
      } catch { fail('network', '无法连接读数服务，请稍后重试') }
    })
  }
  return {
    list(token,vehicle){
      if(!id(vehicle))throw new HealthObservationError('invalid','车辆参数无效')
      return request(token,`/api/vehicle/health-observations?vehicle_id=${vehicle}`,'GET',undefined,undefined,data=>exact(data,['vehicle_id','today','list'])&&data.vehicle_id===vehicle&&date(data.today)
        &&Array.isArray(data.list)&&data.list.length===6&&data.list.every((row,i)=>row.metric===healthMetrics[i]&&validObservation(row,data.today)))
    },
    history(token,vehicle,metric,before=null,limit=20){
      if(!id(vehicle)||!healthMetrics.includes(metric)||(before!==null&&(!revision(before)||before<1))||!Number.isInteger(limit)||limit<1||limit>50)throw new HealthObservationError('invalid','历史分页参数无效')
      return request(token,`/api/vehicle/health-observation-history?vehicle_id=${vehicle}&metric=${metric}&limit=${limit}${before===null?'':`&before_revision=${before}`}`,'GET',undefined,undefined,data=>validHistoryPage(data,vehicle,metric,before,limit))
    },
    context(token,archive,vehicle,metric){
      if(!id(archive)||!id(vehicle)||!healthMetrics.includes(metric))throw new HealthObservationError('invalid','来源或指标参数无效')
      return request(token,`/api/archive/${archive}/health-observation?metric=${metric}`,'GET',undefined,undefined,data=>exact(data,['vehicle_id','archive_id','archive_type','recorded_date','metric','today','observation'])
        &&data.vehicle_id===vehicle&&data.archive_id===archive&&[1,2].includes(data.archive_type)&&(data.recorded_date===null||date(data.recorded_date))&&data.metric===metric&&date(data.today)
        &&data.observation?.metric===metric&&validObservation(data.observation,data.today))
    },
    save(token,context,body,key){
      if(!id(context?.archive_id)||!id(context?.vehicle_id)||!/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(key))throw new HealthObservationError('invalid','保存信息无效')
      return request(token,`/api/archive/${context.archive_id}/health-observation`,'PUT',body,key,data=>exact(data,['vehicle_id','archive_id','metric','revision','status'])
        &&data.vehicle_id===context.vehicle_id&&data.archive_id===context.archive_id&&data.metric===body.metric&&data.revision===body.expected_revision+1
        &&data.status===(empty(body.measurement)?'NOT_SET':'RECORDED_UNVERIFIED'))
    },
  }
}
export const healthObservationApi=createHealthObservationApi({baseUrl:apiOrigin,runtime:()=>apiRuntime})

export const historyOperationLabels={RECORD:'新增测量',CORRECT:'修正当前读数',CLEAR:'清空当前值（保留历史）',MIGRATED_SNAPSHOT:'迁移时当前快照（此前履历未知）'}
export function validHistoryPage(data,vehicle,metric,before=null,limit=20){
  if(!exact(data,['vehicle_id','metric','today','list','has_more','next_before_revision'])||data.vehicle_id!==vehicle||data.metric!==metric||!date(data.today)||!Array.isArray(data.list)||data.list.length>limit||typeof data.has_more!=='boolean')return false
  let previous=before===null?2147483648:before
  for(const row of data.list){
    if(!exact(row,['metric','revision','archive_id','recorded_date','measurement','status','operation','supersedes_revision','saved_at','corrected']))return false
    const projection=Object.fromEntries(['metric','revision','archive_id','recorded_date','measurement','status'].map(k=>[k,row[k]]))
    if(!validObservation(projection,data.today)||row.metric!==metric||row.revision<1||row.revision>=previous||!Object.hasOwn(historyOperationLabels,row.operation)||typeof row.corrected!=='boolean'
      ||typeof row.saved_at!=='string'||!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(row.saved_at)||!Number.isFinite(Date.parse(row.saved_at)))return false
    if(row.operation==='CORRECT'?(row.supersedes_revision!==row.revision-1||row.status==='NOT_SET'):row.supersedes_revision!==null)return false
    if(row.operation==='CLEAR'&&(row.status!=='NOT_SET'||row.corrected)||row.operation==='RECORD'&&row.status==='NOT_SET')return false
    previous=row.revision
  }
  return data.has_more?data.list.length===limit&&data.next_before_revision===previous:data.next_before_revision===null
}
