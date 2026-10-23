import {apiOrigin} from './api-config.js'
import {apiRuntime} from './api-runtime.js'
import {exact} from './experience-publication.js'
import {businessCashDifference} from './business-dashboard.js'
import {ServiceError,serviceFailure} from './service-catalog.js'

export const FINANCE_CHANNELS = Object.freeze(['LOCAL_TEST','WECHAT'])
export const FINANCE_CHANNEL_LABELS = Object.freeze(['测试资金记录','正式微信渠道记录'])
export const currentFinanceDate = (now=Date.now()) => new Date(now+8*3600000).toISOString().slice(0,10)
export function validFinanceDate(value) {
  if(typeof value!=='string'||!/^20\d{2}-\d{2}-\d{2}$/.test(value))return false
  const parsed=Date.parse(`${value}T00:00:00Z`)
  return Number.isFinite(parsed)&&new Date(parsed).toISOString().slice(0,10)===value
}
const stamp=value=>typeof value==='string'&&/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(value)&&Number.isFinite(Date.parse(value))&&new Date(value).toISOString().replace('.000','')===value
const money=value=>typeof value==='string'&&/^(0|[1-9]\d{0,11})\.\d{2}$/.test(value)
const count=value=>Number.isSafeInteger(value)&&value>=0&&value<=10000
function validTotal(total,unclassified) {
  if(!exact(total,['state','success_count','anomaly_count','other_count','amount'])||!['success_count','anomaly_count','other_count'].every(k=>count(total[k])))return false
  const known=total.anomaly_count===0&&unclassified===0
  return total.state===(known?'KNOWN':'UNKNOWN')&&(known?money(total.amount)&&(total.success_count===0?total.amount==='0.00':total.amount!=='0.00'):total.amount===null)
}
export function validLocalFinance(value,date,channel) {
  if(!exact(value,['date','channel','test_mode','timezone','currency','source_version','window_from','window_to','observed_at','payments','refunds','unclassified_count','local_net','channel_statement'])||!validFinanceDate(date)||value.date!==date||!FINANCE_CHANNELS.includes(channel)||value.channel!==channel||value.test_mode!==(channel==='LOCAL_TEST')||value.timezone!=='Asia/Shanghai'||value.currency!=='CNY'||value.source_version!=='R9_LOCAL_FINANCE_V1'||!stamp(value.observed_at)||!count(value.unclassified_count))return false
  const from=Date.parse(`${date}T00:00:00+08:00`),next=from+86400000,now=Date.parse(value.observed_at)
  if(now<from||value.window_from!==new Date(from).toISOString().replace('.000','')||value.window_to!==new Date(Math.min(next,now)).toISOString().replace('.000',''))return false
  if(!validTotal(value.payments,value.unclassified_count)||!validTotal(value.refunds,value.unclassified_count))return false
  const totalCount=value.unclassified_count+[value.payments,value.refunds].reduce((sum,v)=>sum+v.success_count+v.anomaly_count+v.other_count,0)
  if(totalCount>10000||!exact(value.local_net,['state','amount'])||!exact(value.channel_statement,['state','net_amount','difference'])||value.channel_statement.state!=='UNAVAILABLE'||value.channel_statement.net_amount!==null||value.channel_statement.difference!==null)return false
  const known=value.payments.state==='KNOWN'&&value.refunds.state==='KNOWN'
  return value.local_net.state===(known?'KNOWN':'UNKNOWN')&&(known?value.local_net.amount===businessCashDifference(value.payments.amount,value.refunds.amount):value.local_net.amount===null)
}
export function createLocalFinanceApi({baseUrl=apiOrigin,runtime=()=>apiRuntime}={}) {
  return {report(token,date,channel) {
    if(!token)return Promise.reject(new ServiceError('unauthorized','请先登录运营账号'))
    if(!validFinanceDate(date)||!FINANCE_CHANNELS.includes(channel))return Promise.reject(new ServiceError('invalid','请核对日期和资金渠道'))
    if(!baseUrl)return Promise.reject(new ServiceError('unconfigured','资金记录服务尚未配置'))
    return new Promise((resolve,reject)=>{
      try{runtime().request({url:`${baseUrl.replace(/\/$/,'')}/api/admin/reconciliation?date=${date}&channel=${channel}`,method:'GET',timeout:15000,header:{Authorization:`Bearer ${token}`},
        success:({statusCode,data})=>{
          if(!Number.isInteger(statusCode)||statusCode<200||statusCode>=300)return reject(new ServiceError(({400:'invalid',401:'unauthorized',403:'forbidden',409:'conflict',503:'unavailable'})[statusCode]||'server',statusCode===409?'日期尚不可查询或单日记录过多，请核对日期或联系管理员':'资金记录暂不可读取，请核对权限后重试'))
          if(data?.code!==0||!validLocalFinance(data.data,date,channel))return reject(new ServiceError('protocol','资金记录响应不一致，请重新查询'))
          resolve(data.data)
        },fail:()=>reject(serviceFailure())})}catch{reject(serviceFailure())}
    })
  }}
}
export const initialFinanceState=()=>({busy:false,data:null,message:'',kind:''})
export function createLocalFinanceFlow({state,token,allowed,selection,api}) {
  let epoch=0
  function reset(){epoch++;Object.assign(state,initialFinanceState())}
  async function load() {
    if(state.busy||!token()||!allowed())return
    const current=++epoch,identity=token(),chosen={...selection()}
    const matches=()=>current===epoch&&identity===token()&&allowed()&&chosen.date===selection().date&&chosen.channel===selection().channel
    Object.assign(state,{busy:true,data:null,message:'',kind:''})
    try{const value=await api.report(identity,chosen.date,chosen.channel);if(matches())state.data=value}
    catch(error){if(matches()){state.kind=error.kind||'server';state.message=error.message||'查询失败，请重试'}}
    finally{if(matches())state.busy=false}
  }
  return {reset,load}
}
export function financeView(value) {
  const amount=v=>v===null?'未知':`${v} 元`
  const localTime=v=>new Date(Date.parse(v)+8*3600000).toISOString().slice(0,19).replace('T',' ')
  return {
    channel:value.test_mode?'测试资金记录':'正式微信渠道记录',
    notice:value.test_mode?'本页为测试资金，不代表真实扣款或收款。':'本页为系统内正式渠道记录，尚未与渠道账单核对。',
    payments:amount(value.payments.amount),refunds:amount(value.refunds.amount),net:amount(value.local_net.amount),
    anomalies:value.payments.anomaly_count+value.refunds.anomaly_count+value.unclassified_count,
    pending:value.payments.other_count+value.refunds.other_count,
    from:localTime(value.window_from),to:localTime(value.window_to),observed:localTime(value.observed_at),
  }
}
