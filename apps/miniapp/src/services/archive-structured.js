import { apiOrigin } from './api-config.js'
import { apiRuntime } from './api-runtime.js'
export class ArchiveStructuredError extends Error { constructor(kind,message) {super(message);this.kind=kind} }
export const structuredTypes={1:'保养资料',3:'保险资料',7:'年检资料'}
export const structuredFields={
  1:[{key:'provider_name',label:'服务单位',kind:'text',max:100},{key:'service_items',label:'保养项目（每行一项）',kind:'items',max:1620},{key:'performed_mileage',label:'本次保养里程（公里）',kind:'number',max:10}],
  3:[{key:'insurer_name',label:'保险公司',kind:'text',max:100},{key:'policy_number',label:'保单号（私有）',kind:'text',max:64},{key:'coverage_type',label:'险种',kind:'enum',values:['COMPULSORY','COMMERCIAL','COMBINED','OTHER'],labels:['交强险','商业险','交强险与商业险','其他']},{key:'coverage_start',label:'保障开始日',kind:'date'},{key:'coverage_end',label:'保障结束日',kind:'date'}],
  7:[{key:'inspection_station',label:'检验机构',kind:'text',max:100},{key:'inspection_result',label:'资料所载检验结果',kind:'enum',values:['PASSED','FAILED','UNKNOWN'],labels:['通过（本人填写）','未通过（本人填写）','未知']},{key:'valid_until',label:'资料所载有效日期',kind:'date'}],
}
const id=v=>Number.isSafeInteger(v)&&v>0
const revision=v=>Number.isInteger(v)&&v>=0&&v<=2147483647
const exact=(r,names)=>r&&typeof r==='object'&&!Array.isArray(r)&&Object.keys(r).length===names.length&&names.every(n=>Object.hasOwn(r,n))
const date=v=>typeof v==='string'&&/^\d{4}-\d{2}-\d{2}$/.test(v)&&Number(v.slice(0,4))>=1000&&Number.isFinite(new Date(v+'T00:00:00Z').getTime())&&new Date(v+'T00:00:00Z').toISOString().slice(0,10)===v
const text=(v,max)=>typeof v==='string'&&v.trim()===v&&v.length>0&&[...v].length<=max&&!/[\u0000-\u001f\u007f-\u009f]/.test(v)
export function validStructuredFields(type,fields) {
  const definitions=structuredFields[type]
  if(!definitions||!fields||typeof fields!=='object'||Array.isArray(fields))return false
  for(const [key,value] of Object.entries(fields)) {
    const def=definitions.find(f=>f.key===key);if(!def)return false
    if(def.kind==='text'&&!text(value,def.max))return false
    if(def.kind==='date'&&!date(value))return false
    if(def.kind==='enum'&&!def.values.includes(value))return false
    if(def.kind==='number'&&(!revision(value)))return false
    if(def.kind==='items'&&(!Array.isArray(value)||!value.length||value.length>20||new Set(value).size!==value.length||value.some(v=>!text(v,80))))return false
  }
  return !fields.coverage_start||!fields.coverage_end||fields.coverage_start<=fields.coverage_end
}
export function structuredForm(context) {
  return Object.fromEntries(structuredFields[context.archive_type].map(def=>[def.key,context.fields[def.key]===undefined?'':def.kind==='items'?context.fields[def.key].join('\n'):String(context.fields[def.key])]))
}
export function structuredBody(context,form,clear=false) {
  if(!context||!revision(context.revision)||context.revision>=2147483647||!structuredFields[context.archive_type])throw new ArchiveStructuredError('invalid','请刷新资料')
  const fields={}
  if(!clear) for(const def of structuredFields[context.archive_type]) {
    const raw=String(form[def.key]??'').trim();if(!raw)continue
    if(def.kind==='number') {if(!/^\d+$/.test(raw))throw new ArchiveStructuredError('invalid','请填写整数里程');fields[def.key]=Number(raw)}
    else fields[def.key]=def.kind==='items'?raw.split('\n').map(s=>s.trim()).filter(Boolean):raw
  }
  if(!validStructuredFields(context.archive_type,fields))throw new ArchiveStructuredError('invalid','请检查本类字段、日期顺序和长度')
  if(!clear&&!Object.keys(fields).length)throw new ArchiveStructuredError('invalid','请填写至少一项；移除资料请使用清空按钮')
  return {expected_revision:context.revision,fields}
}
export function canReturnToStructured(pages) {return Array.isArray(pages)&&pages.length>1&&pages[pages.length-2]?.route==='pages/archive/structured'}
export function createArchiveStructuredApi({ baseUrl, runtime }) {
  const endpoint = (baseUrl || '').replace(/\/$/, '')
  function request(token, path, method, body, key, validate) {
    if (!token) throw new ArchiveStructuredError('unauthorized', '请先登录车主账号')
    if (!endpoint) throw new ArchiveStructuredError('unconfigured', '资料服务尚未配置')
    return new Promise((resolve, reject) => {
      const fail = (kind, message) => reject(new ArchiveStructuredError(kind, message))
      try {
        runtime().request({ url: `${endpoint}${path}`, method, data: body, timeout: 15000,
          header: { Authorization: `Bearer ${token}`, ...(key ? { 'Idempotency-Key': key } : {}) },
          success: ({ statusCode, data }) => {
            const errors = { 400: ['invalid', '请检查本类资料字段和版本'], 401: ['unauthorized', '登录已失效，请重新登录'],
              403: ['forbidden', '请使用车主账号'], 404: ['missing', '车辆或来源档案不可用，请重新选择'],
              409: ['conflict', '资料已变化，请刷新并复核'], 503: ['unavailable', '资料服务暂不可用，请稍后重试'] }
            if (errors[statusCode]) return fail(...errors[statusCode])
            if (!Number.isInteger(statusCode) || statusCode < 200 || statusCode >= 300) return fail('server', '资料请求未成功，请重试')
            try { if (data?.code !== 0 || !validate(data.data)) return fail('protocol', '资料服务响应异常，请重试') }
            catch { return fail('protocol', '资料服务响应异常，请重试') }
            resolve(data.data)
          }, fail: () => fail('network', '无法连接资料服务，请检查网络后重试'),
        })
      } catch { fail('network', '无法连接资料服务，请稍后重试') }
    })
  }
  return {
    context(token,archive,vehicle) {
      if(!id(archive)||!id(vehicle))throw new ArchiveStructuredError('invalid','来源参数无效')
      return request(token,`/api/archive/${archive}/structured`,'GET',undefined,undefined,data=>
        exact(data,['vehicle_id','archive_id','archive_type','recorded_date','revision','fields','status'])&&data.vehicle_id===vehicle&&data.archive_id===archive
        &&(data.recorded_date===null||date(data.recorded_date))&&revision(data.revision)&&validStructuredFields(data.archive_type,data.fields)
        &&data.status===(Object.keys(data.fields).length?'OWNER_ENTERED_UNVERIFIED':'NOT_SET')&&(data.revision>0||Object.keys(data.fields).length===0))
    },
    save(token,context,body,key) {
      if(!id(context?.archive_id)||!id(context?.vehicle_id)||!/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/i.test(key))throw new ArchiveStructuredError('invalid','保存信息无效')
      return request(token,`/api/archive/${context.archive_id}/structured`,'PUT',body,key,data=>exact(data,['vehicle_id','archive_id','archive_type','revision','status'])
        &&data.vehicle_id===context.vehicle_id&&data.archive_id===context.archive_id&&data.archive_type===context.archive_type&&data.revision===body.expected_revision+1
        &&data.status===(Object.keys(body.fields).length?'OWNER_ENTERED_UNVERIFIED':'NOT_SET'))
    },
  }
}
export const archiveStructuredApi=createArchiveStructuredApi({baseUrl:apiOrigin,runtime:()=>apiRuntime})
