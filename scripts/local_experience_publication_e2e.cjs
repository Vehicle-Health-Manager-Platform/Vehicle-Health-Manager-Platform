// Explicitly opt-in, named loopback stack only. All identities and source payment
// records below are synthetic fixtures. No real SMS, WeChat payment or device claim.
const fs=require('node:fs'),{spawnSync}=require('node:child_process'),{randomBytes,createHash}=require('node:crypto'),f=require('./local_service_work_fixtures.cjs');
const {sql,api,dataOf,codeOf,check,docker,randomUUID}=f;
const ORDER=9207641,OWNER=9207590,READER=9207591,VEHICLE=9207590,READER_VEHICLE=9207591,MODEL=9100601;
const quote=n=>"'"+String(n).replaceAll("'","''")+"'";
let sessions=[],operatorId,operatorToken,validated=false,keep=false,completed=false;
function expect(name,r,status=200){check(name,r.status===status,`HTTP=${r.status},code=${codeOf(r)}`);if(r.status!==status)throw Error('Unexpected response: '+name);return dataOf(r)}
function clone(table,where,overrides={}) {
 const cols=sql(`SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=${quote(table)} ORDER BY ordinal_position`).split(/\r?\n/).filter(c=>c!=='id');
 return sql(`INSERT INTO \`${table}\`(${cols.map(c=>'`'+c+'`').join(',')}) SELECT ${cols.map(c=>overrides[c]??'`'+c+'`').join(',')} FROM \`${table}\` WHERE ${where}; SELECT LAST_INSERT_ID();`);
}
function createSource(){
 if(sql(`SELECT COUNT(*) FROM \`order\` WHERE id=${ORDER} AND order_no<>'synthetic-a7-publication'`)!=='0')throw Error('Synthetic order collision');
 if(sql(`SELECT COUNT(*) FROM user WHERE id IN (${OWNER},${READER}) AND openid NOT IN ('synthetic-a7-publication-owner','synthetic-a7-publication-reader')`)!=='0')throw Error('Synthetic identity collision');
 if(sql(`SELECT COUNT(*) FROM vehicle WHERE id IN (${VEHICLE},${READER_VEHICLE}) AND plate_no NOT IN ('合成发布A7','合成同款A7')`)!=='0')throw Error('Synthetic vehicle collision');
 if(sql(`SELECT COUNT(*) FROM \`order\` o JOIN user u ON u.id=o.user_id JOIN order_redemption r ON r.order_id=o.id WHERE o.id=${f.ORDER_POSITIVE} AND o.order_no='synthetic-a6-positive' AND u.openid='local-a6-owner' AND r.test_mode=1 AND o.status='COMPLETED'`)!=='1')throw Error('Verified synthetic baseline required');
 sql(`INSERT INTO user(id,openid,status) VALUES(${OWNER},'synthetic-a7-publication-owner',1),(${READER},'synthetic-a7-publication-reader',1) ON DUPLICATE KEY UPDATE status=1;
 INSERT INTO vehicle(id,user_id,plate_no,model_id) VALUES(${VEHICLE},${OWNER},'合成发布A7',${MODEL}),(${READER_VEHICLE},${READER},'合成同款A7',${MODEL}) ON DUPLICATE KEY UPDATE model_id=${MODEL},is_deleted=0;`);
 if(sql(`SELECT COUNT(*) FROM \`order\` WHERE id=${ORDER}`)==='0'){
  const cols=sql("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='order' ORDER BY ordinal_position").split(/\r?\n/);
  const changes={id:ORDER,order_no:quote('synthetic-a7-publication'),user_id:OWNER,vehicle_id:VEHICLE};
  sql(`INSERT INTO \`order\`(${cols.map(c=>'`'+c+'`').join(',')}) SELECT ${cols.map(c=>changes[c]??'`'+c+'`').join(',')} FROM \`order\` WHERE id=${f.ORDER_POSITIVE}`);
  const payment=clone('payment',`order_id=${f.ORDER_POSITIVE} AND status='SUCCEEDED' AND is_deleted=0`,{order_id:ORDER,channel:quote('WECHAT'),payment_no:quote('synthetic-pub-'+randomUUID().slice(0,20)),channel_payment_no:quote('synthetic-only-'+randomUUID())});
  clone('payment_event',`payment_id=(SELECT payment_id FROM order_redemption WHERE order_id=${f.ORDER_POSITIVE}) AND outcome='PAID' AND is_deleted=0`,{payment_id:payment,channel:quote('WECHAT'),event_id:quote(randomUUID()),event_hash:quote(randomBytes(32).toString('hex'))});
  clone('order_redemption',`order_id=${f.ORDER_POSITIVE}`,{order_id:ORDER,payment_id:payment,test_mode:0});
  const report=clone('technician_report',`order_id=${f.ORDER_POSITIVE} AND is_deleted=0`,{order_id:ORDER,repair_plan:quote('合成来源，仅验证发布协议，不是真实施工付款'),fault_analysis:quote('合成隐私原文，不应出现在公共DTO')});
  const evidence=sql(`SELECT file_id,kind FROM service_evidence_file WHERE order_id=${f.ORDER_POSITIVE} AND kind IN ('PROCESS','FAULT','FINISH','SIGNATURE') ORDER BY file_id`).split(/\r?\n/).map(row=>row.split('\t'));
  const groups={PROCESS:[],FAULT:[],FINISH:[]};
  for(const [old,kind] of evidence){const file=clone('file_object',`id=${Number(old)}`,{object_key:quote('synthetic-publication/'+randomUUID())});sql(`INSERT INTO service_evidence_file(order_id,record_id,file_id,kind) VALUES(${ORDER},${report},${file},${quote(kind)})`);if(groups[kind])groups[kind].push(Number(file));}
  sql(`UPDATE technician_report SET process_photos=CAST(${quote(JSON.stringify(groups.PROCESS))} AS JSON),fault_part_photos=CAST(${quote(JSON.stringify(groups.FAULT))} AS JSON),finish_photos=CAST(${quote(JSON.stringify(groups.FINISH))} AS JSON) WHERE id=${report}`);
  clone('service_report_submission',`order_id=${f.ORDER_POSITIVE}`,{order_id:ORDER,report_id:report});
 }
 // Reset only this verified synthetic source's derived review/archive/card outputs.
 sql(`DELETE m FROM experience_card_moderation m JOIN experience_card c ON c.id=m.card_id WHERE c.order_id=${ORDER}; DELETE FROM experience_card WHERE order_id=${ORDER};
 DELETE af FROM vehicle_archive_file af JOIN service_archive_job j ON j.archive_id=af.archive_id WHERE j.order_id=${ORDER}; DELETE a FROM vehicle_archive a JOIN service_archive_job j ON j.archive_id=a.id WHERE j.order_id=${ORDER}; DELETE FROM service_archive_job WHERE order_id=${ORDER}; DELETE FROM order_review WHERE order_id=${ORDER};`);
}
function credentials(){
 const password=randomBytes(18).toString('base64url'),sms=String(Number.parseInt(randomBytes(3).toString('hex'),16)%1000000).padStart(6,'0'),account='pub-'+randomUUID().slice(0,18);
 const dir='.cache/a7-publication-image',original=fs.readFileSync(dir+'/backend.env','utf8');
 fs.writeFileSync(dir+'/operator.env',original+`\nOPERATOR_ACCOUNT=${account}\nOPERATOR_PASSWORD=${password}\nOPERATOR_PHONE=13900007590\nOPERATOR_CAN_REVIEW=true\nSERVICE_ARCHIVE_ENABLED=false\n`,{mode:0o600});
 try { const output=docker(['run','--rm','--network','vehicle-auth-local_default','--env-file',dir+'/operator.env','vehicle-auth/backend:a7-experience-publication',`--spring.profiles.active=${f.envVar(f.BACKEND,'SPRING_PROFILES_ACTIVE')},operator-admin`,'--spring.main.web-application-type=none','--operator.action=create']);
  const match=output.match(/OPERATOR_ACCOUNT_ID=(\d+)/);if(!match)throw Error('Offline CLI did not create operator');operatorId=Number(match[1]);
 } finally { fs.unlinkSync(dir+'/operator.env') }
 const hashProcess=spawnSync('docker',['run','--rm','-i','--entrypoint','java','--mount',`type=bind,source=${process.cwd()},target=/workspace`,'maven:3.9-eclipse-temurin-17','-cp','/workspace/.cache/a7-publication-crypto/*','/workspace/.cache/a7-publication-crypto/BCryptFixture.java'],{input:sms+'\n',encoding:'utf8',windowsHide:true});
 if(hashProcess.status!==0 || !hashProcess.stdout.trim().startsWith('$2'))throw Error('Synthetic OTP hash setup failed');
 sql(`INSERT INTO sms_code(phone,purpose,code_hash,expires_at) VALUES('13900007590','O${operatorId}',${quote(hashProcess.stdout.trim())},DATE_ADD(UTC_TIMESTAMP(),INTERVAL 5 MINUTE))`);
 return {account,password,sms};
}
async function main(){
 f.verifyIsolation('a7-publication');check('隔离镜像、V019与61表',true);
 const sha=createHash('sha256').update(fs.readFileSync('backend/target/platform-backend-0.1.0-SNAPSHOT.jar')).digest('hex');
 if(docker(['exec',f.BACKEND,'sha256sum','/app/app.jar']).split(/\s/)[0]!==sha)throw Error('Runtime mismatch; refusing fixture writes');check('运行JAR一致',true);
 if(f.envVar(f.BACKEND,'EXPERIENCE_PUBLICATION_ENABLED')!=='true')throw Error('Isolated publication flag required');validated=true;
 createSource();check('独立合成来源未改原LOCAL_TEST核销',sql(`SELECT test_mode FROM order_redemption WHERE order_id=${f.ORDER_POSITIVE}`)==='1');
 const secret=f.envVar(f.BACKEND,'JWT_SECRET'),app=f.envVar(f.BACKEND,'WECHAT_APP_ID');
 const owner=f.mint(secret,app,{type:'user',role:'OWNER',subject:OWNER}),reader=f.mint(secret,app,{type:'user',role:'OWNER',subject:READER});sessions.push(owner,reader);
 const c=credentials();check('离线CLI无HTTP创建审核账号',safeOperator());
 expect('无正式短信适配器返回503',await api('POST','/api/auth/operator/code','',{body:{account:c.account,password:c.password}}),503);
 expect('错误密码拒绝',await api('POST','/api/auth/operator/login','',{body:{account:c.account,password:'wrong',sms_code:c.sms}}),401);
 const result=expect('真实HTTP密码与合成OTP登录',await api('POST','/api/auth/operator/login','',{body:{account:c.account,password:c.password,sms_code:c.sms}}));operatorToken=result.access_token;
 check('十五分钟且无refresh',result.expires_in===900&&result.user.role==='operator'&&!Object.hasOwn(result,'refresh_token'));
 expect('OTP不能重复消费',await api('POST','/api/auth/operator/login','',{body:{account:c.account,password:c.password,sms_code:c.sms}}),401);
 expect('车主不能读取运营待审',await api('GET','/api/admin/experience-cards',owner.token),403);
 expect('运营不能读取车主同款',await api('GET',`/api/community/experiences?vehicle_id=${READER_VEHICLE}`,operatorToken),403);
 expect('匿名同款拒绝',await api('GET',`/api/community/experiences?vehicle_id=${READER_VEHICLE}`),401);
 expect('其他车主车辆拒绝',await api('GET',`/api/community/experiences?vehicle_id=${VEHICLE}`,reader.token),404);
 expect('本人评价生成可靠档案任务',await api('POST','/api/order/review',owner.token,{key:randomUUID(),body:{order_id:ORDER,rating:5,content:'合成发布验收：原文保持私有',photo_file_ids:[]}}));
 let card;for(let n=0;n<50;n++){const r=expect('本人卡片查询',await api('GET',`/api/experience-cards?vehicle_id=${VEHICLE}`,owner.token));card=r.items.find(c=>c.order_id===ORDER);if(card)break;await new Promise(r=>setTimeout(r,500));}if(!card)throw Error('Card generation timed out');
 check('默认私有且合成非测试通道',card.status==='DRAFT'&&!card.test_mode);
 const path=`/api/experience-cards/${card.card_id}`,declaration={agree:true,consent_version:'experience-v1'};
 card=expect('车主明确授权送审',await api('POST',path+'/consent',owner.token,{key:randomUUID(),body:declaration})).card;
 const queue=await api('GET','/api/admin/experience-cards',operatorToken);const listed=expect('运营读取待审',queue).items.find(n=>n.card_id===card.card_id);check('待审不泄露来源身份',listed&&Object.keys(listed).sort().join(',')==='card_id,model_id,revision,summary,title'&&queue.noStore);
 const moderate=`/api/admin/experience-cards/${card.card_id}/moderate`,key=randomUUID(),body={revision:card.revision,decision:'APPROVE',reason_code:null};
 expect('未知审核字段拒绝',await api('POST',moderate,operatorToken,{key:randomUUID(),body:{...body,user_id:OWNER}}),400);
 expect('批准发布',await api('POST',moderate,operatorToken,{key,body}));expect('同键批准重放',await api('POST',moderate,operatorToken,{key,body}));
 check('只一份批准记录',sql(`SELECT COUNT(*) FROM experience_card_moderation WHERE card_id=${card.card_id}`)==='1');
 let feed=await api('GET',`/api/community/experiences?vehicle_id=${READER_VEHICLE}`,reader.token),publicCard=expect('其他同款车主读取',feed).items[0];
 check('公共只有五字段且no-store',publicCard&&Object.keys(publicCard).sort().join(',')==='experience_id,model_id,published_at,summary,title'&&feed.noStore&&!JSON.stringify(publicCard).includes('合成隐私'));
 sql(`UPDATE vehicle SET model_id=9100602 WHERE id=${READER_VEHICLE}`);check('不同车型不展示',expect('不同车型查询',await api('GET',`/api/community/experiences?vehicle_id=${READER_VEHICLE}`,reader.token)).items.length===0);sql(`UPDATE vehicle SET model_id=${MODEL} WHERE id=${READER_VEHICLE}`);
 const withdrawn=expect('已发布车主撤回',await api('POST',path+'/withdraw',owner.token,{key:randomUUID(),body:{}})).card;check('撤回清空授权',withdrawn.status==='WITHDRAWN'&&withdrawn.consented_at===null);
 check('下一HTTP立即隐藏',expect('撤回后同款查询',await api('GET',`/api/community/experiences?vehicle_id=${READER_VEHICLE}`,reader.token)).items.length===0);
 expect('旧批准键不能覆盖撤回',await api('POST',moderate,operatorToken,{key,body}),409);
 card=expect('撤回后重新明确授权',await api('POST',path+'/consent',owner.token,{key:randomUUID(),body:declaration})).card;
 expect('固定理由驳回',await api('POST',moderate,operatorToken,{key:randomUUID(),body:{revision:card.revision,decision:'REJECT',reason_code:'NOT_SUITABLE'}}));
 card=expect('本人读取驳回理由',await api('GET',`/api/experience-cards?vehicle_id=${VEHICLE}`,owner.token)).items[0];check('只返回固定驳回码',card.status==='REJECTED'&&card.review_reason==='NOT_SUITABLE');
 card=expect('驳回后再次明确授权',await api('POST',path+'/consent',owner.token,{key:randomUUID(),body:declaration})).card;
 sql(`UPDATE operator_account SET can_review=0 WHERE id=${operatorId}`);expect('权限即时生效',await api('GET','/api/admin/experience-cards',operatorToken),403);sql(`UPDATE operator_account SET can_review=1 WHERE id=${operatorId}`);
 expect('再次批准',await api('POST',moderate,operatorToken,{key:randomUUID(),body:{revision:card.revision,decision:'APPROVE',reason_code:null}}));
 check('原档案评价核销均保留',sql(`SELECT (SELECT COUNT(*) FROM order_review WHERE order_id=${ORDER})+(SELECT COUNT(*) FROM order_redemption WHERE order_id=${ORDER})+(SELECT COUNT(*) FROM service_archive_job WHERE order_id=${ORDER} AND status='DONE')`)==='3');
 if(process.argv.includes('--keep-ui')){fs.writeFileSync('.cache/a7-publication-ui.json',JSON.stringify({owner,reader,operatorToken,operatorId,vehicle:VEHICLE,readerVehicle:READER_VEHICLE,order:ORDER,card:card.card_id}),{mode:0o600});keep=true;}
 else {expect('退出撤销运营会话',await api('POST','/api/auth/operator/logout',operatorToken,{body:{}}));expect('退出后拒绝',await api('GET','/api/admin/experience-cards',operatorToken),401);}
 completed=true;
}
function safeOperator(){return sql(`SELECT COUNT(*) FROM operator_account WHERE id=${operatorId} AND account LIKE 'pub-%' AND can_review=1 AND password_hash LIKE '$2%'`)==='1'}
main().catch(e=>{console.error(e.message);process.exitCode=1}).finally(()=>{
 try{if(validated)sql(`UPDATE vehicle SET model_id=${MODEL} WHERE id=${READER_VEHICLE}`);if(!keep){if(sessions.length)sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id IN (${sessions.map(s=>quote(s.jti)).join(',')})`);if(operatorId)sql(`UPDATE operator_account SET status='DISABLED' WHERE id=${operatorId}; UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE subject_type='operator_account' AND subject_id=${operatorId}`)}}catch{console.error('Synthetic cleanup failed');process.exitCode=1}
 const passed=f.results.filter(r=>r.ok).length;fs.writeFileSync('.cache/a7-publication-e2e.json',JSON.stringify({completed,passed,total:f.results.length,results:f.results},null,2));console.log(`A7 publication real HTTP: ${completed?'COMPLETE':'INCOMPLETE'} ${passed}/${f.results.length}`);if(!completed||passed!==f.results.length)process.exitCode=1;
});
