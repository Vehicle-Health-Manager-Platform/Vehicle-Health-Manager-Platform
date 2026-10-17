import test from 'node:test'
import assert from 'node:assert/strict'
import {watch} from 'vue'
import {validOperatorUser} from '../src/services/experience-publication.js'
import {operatorSession,setOperatorSession,clearOperatorSession} from '../src/services/operator-session.js'
const oldBits=['can_review','can_onboard','can_catalog','can_coupon','can_invite','can_diagnose','can_diagnose_manage','can_points','can_assessment','can_analytics','can_price_monitor','can_community_curate']
const user=()=>({id:1,role:'operator',...Object.fromEntries([...oldBits,'can_community_answer','can_community_manage'].map(k=>[k,false]))})
test('current 16-field operator login preserves known older shapes and rejects partial or invented authority',()=>{
 const n=user();assert.equal(validOperatorUser(n),true)
 for(const bit of ['can_community_answer','can_community_manage']){const wrong=user();delete wrong[bit];assert.equal(validOperatorUser(wrong),false)}
 for(const value of [1,'true',null]){const wrong=user();wrong.can_community_answer=value;assert.equal(validOperatorUser(wrong),false)}
 assert.equal(validOperatorUser({...n,can_community_public:true}),false)
 delete n.can_community_answer;delete n.can_community_manage;assert.equal(validOperatorUser(n),true)
})
test('independent community answer and management capabilities publish before the access token',()=>{
 const n=user();n.can_community_answer=true;n.can_community_manage=false
 let observed;const stop=watch(()=>operatorSession.accessToken,t=>{if(t==='private-qa')observed=[operatorSession.canCommunityAnswer,operatorSession.canCommunityManage,operatorSession.canCommunityCurate,operatorSession.canDiagnose]},{flush:'sync'})
 try{setOperatorSession({access_token:'private-qa',expires_in:900,user:n});assert.deepEqual(observed,[true,false,false,false])}finally{stop();clearOperatorSession()}
})
test('older login and logout clear both new privileges without deriving them from diagnosis or curation',()=>{
 const n=user();n.can_community_manage=true;n.can_community_answer=true;setOperatorSession({access_token:'new',expires_in:900,user:n});assert.equal(operatorSession.canCommunityManage,true)
 delete n.can_community_answer;delete n.can_community_manage;n.can_diagnose=true;n.can_community_curate=true
 setOperatorSession({access_token:'old',expires_in:900,user:n});assert.equal(operatorSession.canCommunityAnswer,false);assert.equal(operatorSession.canCommunityManage,false)
 clearOperatorSession();assert.equal(operatorSession.canCommunityAnswer,false);assert.equal(operatorSession.canCommunityManage,false)
})
