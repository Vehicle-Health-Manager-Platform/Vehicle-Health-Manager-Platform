// Opt-in real HTTP acceptance against the named isolated loopback stack only.
// Credentials and all identities are synthetic; this does not prove real WeChat login/payment.
const fs=require('node:fs'),{createHash}=require('node:crypto'),f=require('./local_service_work_fixtures.cjs');
const {sql,api,check,dataOf,codeOf,docker,randomUUID}=f;
const order=f.ORDER_POSITIVE,vehicle=9206290,otherId=9207491;
let sessions=[],validated=false,keep=false;
function expect(name,r,status=200){check(name,r.status===status,`HTTP=${r.status},code=${codeOf(r)}`);if(r.status!==status)throw Error('Unexpected response at '+name);return dataOf(r);}
async function waitCard(token){for(let n=0;n<50;n++){const r=await api('GET',`/api/experience-cards?vehicle_id=${vehicle}`,token);if(r.status!==200)throw Error('Card query failed');const c=dataOf(r).items.find(c=>c.order_id===order);if(c)return c;await new Promise(r=>setTimeout(r,500));}throw Error('Card task did not complete');}
async function main(){
 f.verifyIsolation('a7-card');check('隔离镜像与V018/59表',true);
 const sha=createHash('sha256').update(fs.readFileSync('backend/target/platform-backend-0.1.0-SNAPSHOT.jar')).digest('hex');
 const matches=docker(['exec',f.BACKEND,'sha256sum','/app/app.jar']).split(/\s/)[0]===sha;check('运行JAR与当前代码一致',matches);if(!matches)throw Error('Runtime mismatch; no fixture writes');
 if(f.envVar(f.BACKEND,'EXPERIENCE_CARD_ENABLED')!=='true')throw Error('Card generator not enabled in isolated runtime');
 if(sql(`SELECT COUNT(*) FROM \`order\` o JOIN user u ON u.id=o.user_id JOIN vehicle v ON v.id=o.vehicle_id JOIN order_redemption r ON r.order_id=o.id WHERE o.id=${order} AND o.order_no LIKE 'synthetic-a6-%' AND u.openid='local-a6-owner' AND v.user_id=${vehicle} AND o.status='COMPLETED' AND r.test_mode=1`)!=='1')throw Error('Previously verified synthetic redemption required');
 if(sql(`SELECT COUNT(*) FROM user WHERE id=${otherId} AND openid<>'synthetic-a7-card-other'`)!=='0')throw Error('Synthetic identity collision');
 if(sql(`SELECT COUNT(*) FROM technician_report WHERE order_id=${order} AND status=1 AND signed_at IS NOT NULL AND is_deleted=0`)!=='1')throw Error('Immutable signed synthetic work required');
 validated=true;
 const secret=f.envVar(f.BACKEND,'JWT_SECRET'),app=f.envVar(f.BACKEND,'WECHAT_APP_ID');
 const owner=f.mint(secret,app,{type:'user',role:'OWNER',subject:vehicle}); sessions.push(owner);
 sql(`INSERT INTO user(id,openid,status) VALUES(${otherId},'synthetic-a7-card-other',1) ON DUPLICATE KEY UPDATE status=1;`);
 const other=f.mint(secret,app,{type:'user',role:'OWNER',subject:otherId});sessions.push(other);
 const merchant=f.mint(secret,app,{type:'staff_account',role:'MERCHANT',subject:9206201,merchantId:9206201,appId:'merchant-account'});sessions.push(merchant);
 // Reset this explicitly verified synthetic order's outputs only, keeping work and redemption.
 sql(`DELETE FROM experience_card WHERE order_id=${order}; DELETE af FROM vehicle_archive_file af JOIN service_archive_job j ON j.archive_id=af.archive_id WHERE j.order_id=${order}; DELETE a FROM vehicle_archive a JOIN service_archive_job j ON j.archive_id=a.id WHERE j.order_id=${order}; DELETE FROM service_archive_job WHERE order_id=${order}; DELETE rf FROM order_review_file rf JOIN order_review r ON r.id=rf.review_id WHERE r.order_id=${order}; DELETE FROM order_review WHERE order_id=${order};`);
 const body={order_id:order,rating:5,content:'合成经验卡片验收：车牌电话与身份文字保持私有',photo_file_ids:[]},key=randomUUID();
 sql(`UPDATE technician_report SET is_deleted=1 WHERE order_id=${order};`);
 expect('来源暂缺仍保存本人评价',await api('POST','/api/order/review',owner.token,{key,body}));
 await new Promise(r=>setTimeout(r,1600));
 check('来源暂缺不生成卡片',sql(`SELECT COUNT(*) FROM experience_card WHERE order_id=${order}`)==='0');
 check('来源暂缺任务可恢复',sql(`SELECT status FROM service_archive_job WHERE order_id=${order}`)==='PENDING');
 sql(`UPDATE technician_report SET is_deleted=0 WHERE order_id=${order}; UPDATE service_archive_job SET next_attempt_at=UTC_TIMESTAMP() WHERE order_id=${order};`);
 const card=await waitCard(owner.token),path=`/api/experience-cards/${card.card_id}`;
 check('恢复后只生成一张卡片',sql(`SELECT COUNT(*) FROM experience_card WHERE order_id=${order}`)==='1');
 check('默认私有未授权',card.status==='DRAFT'&&card.consent_version===null&&card.consented_at===null&&card.revision===0);
 check('测试来源明确标记',card.test_mode===true);
 check('摘要仅五项结构化事实',JSON.stringify(Object.keys(card.summary).sort())===JSON.stringify(['version','work_minutes','part_kinds','no_parts','recorded_month'].sort())&&card.summary.work_minutes>0);
 const json=JSON.stringify(card);check('不带原文照片签名身份',!['车牌电话','photo','file_id','signature','notes','user_id','object_key','repair_plan'].some(s=>json.includes(s)));
 const read=await api('GET',`/api/experience-cards?vehicle_id=${vehicle}`,owner.token);expect('本人列表可读',read);check('列表不缓存',read.noStore);
 expect('同键评价重放不重复生成',await api('POST','/api/order/review',owner.token,{key,body}));
 check('归档和卡片一一对应',sql(`SELECT COUNT(*) FROM experience_card c JOIN service_archive_job j ON j.archive_id=c.archive_id AND j.order_id=c.order_id AND j.status='DONE' WHERE c.order_id=${order}`)==='1');
 expect('其他车主读取拒绝',await api('GET',`/api/experience-cards?vehicle_id=${vehicle}`,other.token),404);
 expect('商家读取拒绝',await api('GET',`/api/experience-cards?vehicle_id=${vehicle}`,merchant.token),403);
 expect('匿名读取拒绝',await api('GET',`/api/experience-cards?vehicle_id=${vehicle}`),401);
 expect('多余身份参数拒绝',await api('GET',`/api/experience-cards?vehicle_id=${vehicle}&user_id=${otherId}`,owner.token),400);
 expect('重复车辆参数拒绝',await api('GET',`/api/experience-cards?vehicle_id=${vehicle}&vehicle_id=${vehicle}`,owner.token),400);
 expect('分页上限拒绝',await api('GET',`/api/experience-cards?vehicle_id=${vehicle}&page_size=51`,owner.token),400);
 const declaration={agree:true,consent_version:'experience-v1'};
 const blocked=await api('POST',path+'/consent',owner.token,{key:randomUUID(),body:declaration});expect('测试不能授权送审',blocked,409);check('测试阻断稳定错误码',codeOf(blocked)===45001&&blocked.noStore);
 expect('其他车主不能撤回',await api('POST',path+'/withdraw',other.token,{key:randomUUID(),body:{}}),404);
 expect('未明确声明拒绝',await api('POST',path+'/consent',owner.token,{key:randomUUID(),body:{agree:false,consent_version:'experience-v1'}}),400);
 expect('未知声明版本拒绝',await api('POST',path+'/consent',owner.token,{key:randomUUID(),body:{agree:true,consent_version:'old'}}),400);
 const withdrawal=randomUUID();const withdrawn=expect('本人撤回草稿',await api('POST',path+'/withdraw',owner.token,{key:withdrawal,body:{}})).card;
 check('撤回清除授权且保留来源',withdrawn.status==='WITHDRAWN'&&withdrawn.revision===1&&withdrawn.consented_at===null&&withdrawn.withdrawn_at&&withdrawn.order_id===order);
 expect('同键撤回重放',await api('POST',path+'/withdraw',owner.token,{key:withdrawal,body:{}}));
 expect('新键重复撤回不再改变',await api('POST',path+'/withdraw',owner.token,{key:randomUUID(),body:{}}));
 check('只增加一次撤回审计',sql(`SELECT COUNT(*) FROM audit_log WHERE resource_type='experience_card' AND resource_id=${card.card_id} AND action='EXPERIENCE_CARD_WITHDRAW'`)==='1');
 check('不写任何公开社区卡片',sql(`SELECT COUNT(*) FROM community_content WHERE order_id=${order}`)==='0');
 sql(`UPDATE vehicle SET user_id=${otherId} WHERE id=${vehicle};`);
 expect('车辆转移后原车主不能读',await api('GET',`/api/experience-cards?vehicle_id=${vehicle}`,owner.token),404);
 check('新车主看不到前车主经验',expect('新车主自己的列表',await api('GET',`/api/experience-cards?vehicle_id=${vehicle}`,other.token)).items.length===0);
 sql(`UPDATE vehicle SET user_id=${vehicle} WHERE id=${vehicle};`);
 check('原档案评价核销不被撤回删除',sql(`SELECT (SELECT COUNT(*) FROM order_review WHERE order_id=${order})+(SELECT COUNT(*) FROM order_redemption WHERE order_id=${order})+(SELECT COUNT(*) FROM service_archive_job WHERE order_id=${order} AND status='DONE')`)==='3');
 sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id='${other.jti}';`);
 expect('撤销会话即时阻断',await api('GET',`/api/experience-cards?vehicle_id=${vehicle}`,other.token),401);
 if(process.argv.includes('--keep-ui')){fs.writeFileSync('.cache/a7-card-ui-sessions.json',JSON.stringify({owner,order,vehicle,card:card.card_id}),{mode:0o600});keep=true;console.log('Synthetic UI session retained locally; credentials withheld');}
}
main().catch(e=>{console.error(e.message);process.exitCode=1}).finally(()=>{
 try{if(validated)sql(`UPDATE technician_report SET is_deleted=0 WHERE order_id=${order}; UPDATE vehicle SET user_id=${vehicle} WHERE id=${vehicle};`);const revoke=keep?sessions.slice(1):sessions;if(revoke.length)sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id IN (${revoke.map(s=>"'"+s.jti+"'").join(',')});`);}catch{console.error('Synthetic cleanup failed');process.exitCode=1;}
 const passed=f.results.filter(r=>r.ok).length;fs.writeFileSync('.cache/a7-experience-cards-e2e.json',JSON.stringify({passed,total:f.results.length,results:f.results},null,2));console.log(`A7 private experience real HTTP: ${passed}/${f.results.length}`);if(passed!==f.results.length)process.exitCode=1;
});
