const id=n=>/^[1-9]\d*$/.test(String(n||''))&&Number.isSafeInteger(Number(n))
// Only the immediately preceding registered business page can be resumed.
export function serviceLoginReturn(pages,role,permissions={}) {
 if(!Array.isArray(pages)||pages.length<2)return false
 const p=pages[pages.length-2],route=p?.route,q=p?.options||p?.$page?.options||{}
 if(typeof route!=='string')return false
 const actual=role==='technician'?'tech':role
 const owner=new Set(['pages/order/work-evidence','pages/order/archive-grants','pages/order/detail'])
 if(actual==='owner'&&(route==='pages/diagnosis/mine'||owner.has(route)&&id(q.id)))return true
 if(['merchant','tech','owner'].includes(actual)&&route==='pages/workspace/notifications')return q.role===actual
 if(['merchant','tech'].includes(actual)&&['pages/workspace/tasks','pages/workspace/context'].includes(route))return q.role===actual&&(route.endsWith('/tasks')||id(q.id))
 if(actual==='tech'&&['pages/technician/work','pages/technician/order-detail'].includes(route))return id(q.id)
 if(actual==='merchant'&&['pages/merchant/order-detail','pages/merchant/protection'].includes(route))return id(q.id)
 if(route==='pages/diagnosis/detail')return id(q.id)&&q.role===actual&&(actual==='owner'||actual==='admin'&&permissions.canDiagnose===true)
 if(route==='pages/diagnosis/notifications')return q.role===actual&&(actual==='owner'||actual==='admin'&&(permissions.canDiagnose||permissions.canDiagnoseManage)===true)
 if(actual!=='admin')return false
 if(route==='pages/operator/diagnosis-service')return permissions.canDiagnoseManage===true
 if(route==='pages/operator/diagnosis-cases')return q.scope==='queue'?permissions.canDiagnoseManage===true:(permissions.canDiagnose||permissions.canDiagnoseManage)===true
 return false
}
export function returnAfterOperatorLogin({pages,permissions,runtime,landing}) {
 if(serviceLoginReturn(pages,'admin',permissions))runtime.navigateBack({fail:()=>runtime.redirectTo({url:landing})})
 else runtime.redirectTo({url:landing})
}
