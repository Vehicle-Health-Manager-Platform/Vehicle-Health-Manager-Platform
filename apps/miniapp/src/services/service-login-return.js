import {questionRoute,questionQueueRoute} from './community-questions.js'
import {experienceReferenceOptions} from './experience-detail.js'
import {publicQaRoute} from './public-community-questions.js'
import {publicImageRoute} from './public-images.js'
import {knowledgeRoute} from './licensed-knowledge.js'
const id=n=>/^[1-9]\d*$/.test(String(n||''))&&Number.isSafeInteger(Number(n))
// Only the immediately preceding registered business page can be resumed.
export function serviceLoginReturn(pages,role,permissions={}) {
 if(!Array.isArray(pages)||pages.length<2)return false
 const p=pages[pages.length-2],route=p?.route,q=p?.options||p?.$page?.options||{}
 if(typeof route!=='string')return false
 const actual=role==='technician'?'tech':role
 if(route==='pages/community/knowledge')return actual==='owner'&&knowledgeRoute(q)!==null
 if(route==='pages/operator/knowledge')return actual==='admin'&&permissions.canKnowledge===true&&Object.keys(q).length===0
 if(route==='pages/community/public-images'){const r=publicImageRoute(q);return Boolean(r&&(r.mode==='review'?actual==='admin'&&permissions.canReview===true:actual==='owner'))}
 if(route==='pages/community/public-questions'){const r=publicQaRoute(q);return Boolean(r&&(r.mode==='review'?actual==='admin'&&permissions.canReview===true:actual==='owner'))}
 const owner=new Set(['pages/order/work-evidence','pages/order/archive-grants','pages/order/detail'])
 if(actual==='owner'&&route==='pages/community/questions')return Object.keys(q).length===0
 if(route==='pages/community/question'){const target=questionRoute(q);return Boolean(target&&target.role===actual&&(actual==='owner'||actual==='admin'&&permissions.canCommunityAnswer===true))}
 if(actual==='admin'&&route==='pages/operator/question-service')return permissions.canCommunityManage===true&&Object.keys(q).length===0
 if(actual==='admin'&&route==='pages/operator/questions'){const scope=questionQueueRoute(q);return scope!==null&&(scope==='queue'?permissions.canCommunityManage===true:Object.keys(q).length===0?(permissions.canCommunityAnswer===true||permissions.canCommunityManage===true):permissions.canCommunityAnswer===true)}
 if(actual==='owner'&&route==='pages/community/experiences')return experienceReferenceOptions(q).valid
 if(actual==='owner'&&route==='pages/points/exchange')return Object.keys(q).length===0
 if(actual==='owner'&&route==='pages/points/index')return true
 if(actual==='owner'&&(route==='pages/diagnosis/mine'||owner.has(route)&&id(q.id)))return true
 if(['merchant','tech','owner'].includes(actual)&&route==='pages/workspace/notifications')return q.role===actual
 if(['merchant','tech'].includes(actual)&&['pages/workspace/tasks','pages/workspace/context'].includes(route))return q.role===actual&&(route.endsWith('/tasks')||id(q.id))
 if(actual==='tech'&&['pages/technician/work','pages/technician/order-detail'].includes(route))return id(q.id)
 if(actual==='merchant'&&['pages/merchant/order-detail','pages/merchant/protection'].includes(route))return id(q.id)
 if(route==='pages/diagnosis/detail')return id(q.id)&&q.role===actual&&(actual==='owner'||actual==='admin'&&permissions.canDiagnose===true)
 if(route==='pages/diagnosis/notifications')return q.role===actual&&(actual==='owner'||actual==='admin'&&(permissions.canDiagnose||permissions.canDiagnoseManage)===true)
 if(actual==='merchant'&&route==='pages/merchant/customer-relation')return Object.keys(q).length===1&&id(q.id)
 if(actual==='merchant'&&['pages/merchant/assessment','pages/merchant/business-dashboard','pages/merchant/customers'].includes(route))return Object.keys(q).length===0
 if(actual!=='admin')return false
 if(route==='pages/operator/experience-curations')return permissions.canCommunityCurate===true&&Object.keys(q).length===0
 if(route==='pages/operator/price-policies')return permissions.canPriceMonitor===true&&Object.keys(q).length===0
 if(route==='pages/operator/price-comparisons')return permissions.canPriceMonitor===true&&Object.keys(q).length===0
 if(route==='pages/operator/price-comparison')return permissions.canPriceMonitor===true&&Object.keys(q).length===1&&id(q.id)
 if(route==='pages/operator/business-dashboard')return permissions.canAnalytics===true&&Object.keys(q).length===0
 if(['pages/operator/assessment-policies','pages/operator/monthly-assessment'].includes(route))return permissions.canAssessment===true&&Object.keys(q).length===0
 if(['pages/operator/points-policies','pages/operator/points-exchange'].includes(route))return permissions.canPoints===true
 if(route==='pages/operator/diagnosis-service')return permissions.canDiagnoseManage===true
 if(route==='pages/operator/diagnosis-cases')return q.scope==='queue'?permissions.canDiagnoseManage===true:(permissions.canDiagnose||permissions.canDiagnoseManage)===true
 return false
}
export function returnAfterOperatorLogin({pages,permissions,runtime,landing}) {
 if(serviceLoginReturn(pages,'admin',permissions))runtime.navigateBack({fail:()=>runtime.redirectTo({url:landing})})
 else runtime.redirectTo({url:landing})
}
