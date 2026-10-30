// Only deployment-owned service keys resolve URLs. Never accept a destination from a route query.
export const lifeGroups=Object.freeze({
 benefits:{title:'专属优惠',description:'查看平台可领取的优惠，按需选择合作服务。',items:[
  {title:'平台精选券',hint:'真实券池，以领取页规则及有效期为准',url:'/pages/coupon/offers'},
  {title:'我的优惠券',hint:'查看金额券、服务券及使用条件',url:'/pages/coupon/mine'},
  {title:'加油优惠',partner:'fuel'},{title:'充电优惠',partner:'charging'},{title:'洗车优惠',partner:'wash'},{title:'车险服务',partner:'insurance'}]},
 used:{title:'二手车服务',description:'整理本人车辆资料，了解估值、卖车与过户服务。',items:[
  {title:'整理车辆档案',hint:'核对里程与养护记录；不自动对外共享',url:'/pages/archive/index',tab:true},
  {title:'车辆估值',partner:'valuation'},{title:'我要卖车',partner:'sell'},{title:'过户服务',partner:'transfer'}]},
 new:{title:'新车选购',description:'对照在用车型目录，比较品牌、车系、年款与配置名称。',items:[
  {title:'车型配置与对比',hint:'比较目录字段，不提供虚构成交价或性能参数',url:'/pages/mine/model-compare'},
  {title:'登记我的新车',hint:'确认车型、VIN与当前里程',url:'/pages/vehicle/entry'},
  {title:'新车保险',partner:'newInsurance'}]},
})
const partnerNames={fuel:'加油优惠',charging:'充电优惠',wash:'洗车优惠',insurance:'车险服务',valuation:'车辆估值',sell:'卖车服务',transfer:'过户服务',newInsurance:'新车保险'}
export function partnerService(key,raw=import.meta.env?.VITE_CAR_LIFE_PARTNERS||'{}'){
 if(!Object.hasOwn(partnerNames,key))return null
 const closed={key,title:partnerNames[key],available:false,message:'合作服务暂未开通，可继续使用平台车辆档案与服务功能。'}
 try{
  const c=JSON.parse(raw)[key]
  // Exact public HTTPS page, no credentials/query/hash/IP/local host. Owner data is never appended.
  if(!c||typeof c.url!=='string'||!/^https:\/\/[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\.[a-z]{2,}(?:\/[A-Za-z0-9_./~%-]*)?$/i.test(c.url)||c.url.includes('..')||typeof c.provider!=='string'||!c.provider.trim()||c.provider.length>80||/[\u0000-\u001f\u007f]/.test(c.provider))return closed
  return {...closed,available:true,url:c.url,provider:c.provider,host:c.url.split('/')[2],message:'将打开第三方服务，由合作方提供报价、交易与售后。平台不会携带你的登录凭据、车牌、VIN或档案。'}
 }catch{return closed}
}
