import { watch } from 'vue'
import { ownerSession } from './owner-session.js'
export function createSymptomHandoff({token,now=Date.now}){let pending=null;function clear(){pending=null}return{clear,route(question){if(!token()||typeof question!=='string'||![...question.trim()].length||[...question.trim()].length>300)return null;pending={actor:token(),question:question.trim(),at:now()};return '/pages/ai/service-plan'},take(){const p=pending;clear();return p&&p.actor===token()&&now()>=p.at&&now()-p.at<=900000?p.question:null}}}
export const symptomHandoff=createSymptomHandoff({token:()=>ownerSession.accessToken})
watch(()=>ownerSession.accessToken,symptomHandoff.clear,{flush:'sync'})
