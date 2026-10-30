import {VehicleError} from './vehicles.js'

export const VIN_PROVIDER = import.meta.env?.VITE_VIN_PROVIDER === 'LOCAL_LICENSED' ? 'LOCAL_LICENSED' : 'NHTSA_VPIC'
export const VIN_DISCLOSURE = VIN_PROVIDER === 'LOCAL_LICENSED'
 ? '同意由平台本地规则查询VIN及可选年款，不向NHTSA发送。仅覆盖已配置并校验授权的规则；合成测试来源会明确标识，候选需核对，不会自动登记车辆。'
 : '同意将VIN及可选年款发送给美国NHTSA，用于本次车型候选查询。其数据以美国市场登记为主，中国车辆可能无结果或不完整；候选需自行核对，不会自动登记车辆。'
export const vinSourceLabel = candidate => candidate.source === 'LOCAL_SYNTHETIC' ? `合成测试规则（非真实识别）· ${candidate.source_revision}` : candidate.source === 'LOCAL_LICENSED' ? `本地授权规则 · ${candidate.source_revision}` : 'NHTSA（美国市场登记）'
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
export function vinInput(raw,year='') {
 if(typeof raw!=='string'||raw.length>32||!/^[A-HJ-NPR-Za-hj-npr-z0-9]{17}$/.test(raw.trim()))throw new VehicleError('invalid','请输入17位VIN，不含I、O、Q')
 if(typeof year!=='string'||year!==''&&!/^(19[89][0-9]|20[0-9]{2}|2100)$/.test(year))throw new VehicleError('invalid','年款可留空，或填写1980至2100的四位整数')
 return {vin:raw.trim().toUpperCase(),year:year===''?null:Number(year)}
}
export function vinResponse(value,vin,year) {
 const fail=()=>{throw new VehicleError('protocol','VIN服务响应异常，请手动确认车型')}
 const local=['LOCAL_LICENSED','LOCAL_SYNTHETIC'].includes(value?.source)
 if(!exact(value,['vin','model_year_hint','status','source','coverage','requires_confirmation','fields',...(local?['source_revision','synthetic','catalog_candidates']:[])])||value.vin!==vin||value.model_year_hint!==year
  ||(!local&&value.source!=='NHTSA_VPIC')||value.coverage!==(local?'PINNED_LOCAL_RULES_ONLY':'US_MARKET_REGISTRATION_ONLY')||value.requires_confirmation!==true
  ||!['CANDIDATE','PARTIAL','NO_MATCH'].includes(value.status)||!value.fields||typeof value.fields!=='object'||Array.isArray(value.fields))fail()
 const fields=value.fields,keys=Object.keys(fields),allowed=['make','model','model_year','trim','vehicle_type']
 if(keys.some(k=>!allowed.includes(k)||typeof fields[k]!=='string'||!fields[k].trim()||fields[k].length>160||/[\u0000-\u001f\u007f-\u009f]/.test(fields[k])||Array.from(fields[k]).some(c=>c.codePointAt(0)>=0xd800&&c.codePointAt(0)<=0xdfff)))fail()
 if(Object.hasOwn(fields,'model_year')&&(!/^(19[89][0-9]|20[0-9]{2}|2100)$/.test(fields.model_year)||year!==null&&String(year)!==fields.model_year))fail()
 const complete=['make','model','model_year'].every(k=>Object.hasOwn(fields,k))
 if(value.status==='NO_MATCH'&&keys.length||value.status==='CANDIDATE'&&!complete||value.status==='PARTIAL'&&complete)fail()
 if(local){
  if(typeof value.source_revision!=='string'||!/^[A-Za-z0-9_.-]{1,64}$/.test(value.source_revision)||value.synthetic!==(value.source==='LOCAL_SYNTHETIC')||!Array.isArray(value.catalog_candidates)||value.catalog_candidates.length>1)fail()
  for(const c of value.catalog_candidates){
   if(value.status!=='CANDIDATE'||!exact(c,['model_id','series_id','brand_id','brand_name','series_name','year','config_name'])||['model_id','series_id','brand_id'].some(k=>!Number.isSafeInteger(c[k])||c[k]<1)||c.brand_name!==fields.make||c.series_name!==fields.model||c.year!==fields.model_year||c.config_name!==fields.trim)fail()
  }
 }
 return Object.freeze({...value,fields:Object.freeze({...fields}),...(local?{catalog_candidates:Object.freeze(value.catalog_candidates.map(c=>Object.freeze({...c})))}:{})})
}
export const vinSummary = candidate => Object.entries(candidate.fields).map(([k,v])=>`${{make:'品牌',model:'车系',model_year:'年款',trim:'配置',vehicle_type:'车辆类型'}[k]}：${v}`).join('；')
