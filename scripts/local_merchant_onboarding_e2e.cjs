// R1a merchant onboarding close-out: real backend container + real isolated MySQL.
// Explicitly opt-in, named loopback stack only. All identities and operator accounts
// below are synthetic fixtures: no real SMS, real store or real applicant. Qualification
// files ARE really uploaded through the private upload core (real MinIO object + real
// ClamAV scan) so the controlled access endpoint can be exercised end to end; only the
// deliberately not-CLEAN file and the missing-id probe are direct database fixtures.
// The uploaded bytes are a tiny synthetic PNG, never a real applicant document.
const fs=require('node:fs'),{spawnSync}=require('node:child_process'),{randomBytes,createHash}=require('node:crypto'),f=require('./local_service_work_fixtures.cjs');
const {sql,api,dataOf,codeOf,check,docker,randomUUID}=f;
const OWNER_A=9207701,OWNER_B=9207702,OWNER_C=9207703;
const FILE_DIRTY=9207727,FILE_MISSING=9207798,APP_MISSING=9207799;
const REGION_FULL='440106',REGION_SECOND='440105',ORIGIN='http://127.0.0.1:18080';
const SAMPLE_PNG=fs.readFileSync('.cache/a6-photo.png');
const quote=n=>"'"+String(n).replaceAll("'","''")+"'";
let sessions=[],operators=[],applicationIds=[],storeIds=[],uploadedFiles=[],completed=false;

function expect(name,r,status=200,code){check(name,r.status===status,`HTTP=${r.status},code=${codeOf(r)}`);if(code!==undefined&&codeOf(r)!==code)check(name+' 错误码',false,`期望 ${code} 实得 ${codeOf(r)}`);if(r.status!==status)throw Error('Unexpected response: '+name);return dataOf(r)}
function application(name,region,files){return {merchant_name:name,category:'REPAIR',region_code:region,address:'广州市天河区合成验收路1号',contact_phone:'13800007701',qualification_file_ids:files}}

/** Real multipart upload through the private upload core; the body is single `file`. */
async function uploadQualification(token,label){
 const form=new FormData();
 form.append('file',new Blob([SAMPLE_PNG],{type:'image/png'}),label+'.png');
 const response=await fetch(ORIGIN+'/api/file/upload',{method:'POST',headers:{Authorization:'Bearer '+token,'Idempotency-Key':randomUUID()},body:form});
 const text=await response.text();let json=null;try{json=JSON.parse(text)}catch{/* keep text */}
 if(response.status!==200||!json||json.code!==0||!json.data||!json.data.file_id)throw Error('Qualification upload failed: HTTP '+response.status+' '+text.slice(0,200));
 const id=json.data.file_id;uploadedFiles.push(id);
 const status=sql(`SELECT scan_status FROM file_object WHERE id=${id} AND is_deleted=0`);
 if(status!=='CLEAN')throw Error('Uploaded qualification is not CLEAN: '+status);
 return id;
}

function prepareFixtures(){
 if(sql(`SELECT COUNT(*) FROM user WHERE id IN (${OWNER_A},${OWNER_B},${OWNER_C}) AND openid NOT IN ('synthetic-r1a-owner-a','synthetic-r1a-owner-b','synthetic-r1a-owner-c')`)!=='0')throw Error('Synthetic owner collision');
 if(sql(`SELECT COUNT(*) FROM file_object WHERE id=${FILE_DIRTY} AND object_key NOT LIKE 'synthetic-r1a/%'`)!=='0')throw Error('Synthetic file collision');
 // Repeatable: reset only this script's own synthetic rows before recreating them.
 const priorApps=sql(`SELECT COALESCE(GROUP_CONCAT(id),0) FROM merchant_application WHERE applicant_user_id IN (${OWNER_A},${OWNER_B},${OWNER_C})`);
 const priorStores=sql(`SELECT COALESCE(GROUP_CONCAT(merchant_id),0) FROM merchant_application WHERE applicant_user_id IN (${OWNER_A},${OWNER_B},${OWNER_C}) AND merchant_id IS NOT NULL`);
 sql(`DELETE FROM audit_log WHERE (resource_type='merchant_application' AND resource_id IN (${priorApps})) OR (resource_type='merchant' AND resource_id IN (${priorStores})) OR (resource_type='merchant_region_category_quota') OR (resource_type='operator_account' AND actor_type='system' AND resource_id IN (SELECT id FROM operator_account WHERE account LIKE 'r1a-%'));
 DELETE FROM merchant_application_review WHERE application_id IN (${priorApps});
 DELETE FROM merchant_application WHERE applicant_user_id IN (${OWNER_A},${OWNER_B},${OWNER_C});
 DELETE FROM staff_account WHERE account LIKE 'm%' AND merchant_id IN (${priorStores});
 DELETE FROM merchant WHERE id IN (${priorStores});
 DELETE FROM merchant_region_category_quota WHERE region_code IN (${quote(REGION_FULL)},${quote(REGION_SECOND)});
 DELETE FROM idempotency_record WHERE (actor_type='user' AND actor_id IN (${OWNER_A},${OWNER_B},${OWNER_C})) OR (actor_type='operator_account' AND actor_id IN (SELECT id FROM operator_account WHERE account LIKE 'r1a-%'));
 DELETE FROM auth_session WHERE subject_id IN (${OWNER_A},${OWNER_B},${OWNER_C}) AND subject_type='user';
 DELETE FROM operator_account WHERE account LIKE 'r1a-%';
 DELETE FROM sms_code WHERE phone IN ('13900007791','13900007792');
 DELETE FROM file_object WHERE owner_type='user' AND (owner_id IN (${OWNER_A},${OWNER_B},${OWNER_C}) OR id=${FILE_DIRTY});
 DELETE FROM user WHERE id IN (${OWNER_A},${OWNER_B},${OWNER_C});`);
 sql(`INSERT INTO user(id,openid,status) VALUES(${OWNER_A},'synthetic-r1a-owner-a',1),(${OWNER_B},'synthetic-r1a-owner-b',1),(${OWNER_C},'synthetic-r1a-owner-c',1) ON DUPLICATE KEY UPDATE status=1;
 INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES
  (${FILE_DIRTY},'user',${OWNER_A},${quote('synthetic-r1a/not-clean-'+randomUUID())},'image/png',${SAMPLE_PNG.length},'PENDING')
 ON DUPLICATE KEY UPDATE scan_status=VALUES(scan_status),owner_id=VALUES(owner_id);`);
}

// Operator accounts come from the offline operator-admin CLI inside the current image;
// passwords and OTP codes are random and never printed.
function createOperator(onboard,review){
 const password=randomBytes(18).toString('base64url'),sms=String(Number.parseInt(randomBytes(3).toString('hex'),16)%1000000).padStart(6,'0'),account='r1a-'+randomUUID().slice(0,18);
 const dir='.cache/r1a-onboarding-image',original=fs.readFileSync(dir+'/backend.env','utf8');
 fs.writeFileSync(dir+'/operator.env',original+`\nOPERATOR_ACCOUNT=${account}\nOPERATOR_PASSWORD=${password}\nOPERATOR_PHONE=1390000779${onboard?'1':'2'}\nOPERATOR_CAN_ONBOARD=${onboard}\nOPERATOR_CAN_REVIEW=${review}\n`,{mode:0o600});
 try { const output=docker(['run','--rm','--network','vehicle-auth-local_default','--env-file',dir+'/operator.env','vehicle-auth/backend:r1a-onboarding',`--spring.profiles.active=${f.envVar(f.BACKEND,'SPRING_PROFILES_ACTIVE')},operator-admin`,'--spring.main.web-application-type=none','--operator.action=create']);
  const match=output.match(/OPERATOR_ACCOUNT_ID=(\d+)/);if(!match)throw Error('Offline CLI did not create operator');const id=Number(match[1]);
  const hashProcess=spawnSync('docker',['run','--rm','-i','--entrypoint','java','--mount',`type=bind,source=${process.cwd()},target=/workspace`,'maven:3.9-eclipse-temurin-17','-cp','/workspace/.cache/a7-publication-crypto/*','/workspace/.cache/a7-publication-crypto/BCryptFixture.java'],{input:sms+'\n',encoding:'utf8',windowsHide:true});
  if(hashProcess.status!==0 || !hashProcess.stdout.trim().startsWith('$2'))throw Error('Synthetic OTP hash setup failed');
  sql(`INSERT INTO sms_code(phone,purpose,code_hash,expires_at) VALUES('1390000779${onboard?'1':'2'}','O${id}',${quote(hashProcess.stdout.trim())},DATE_ADD(UTC_TIMESTAMP(),INTERVAL 5 MINUTE))`);
  return {id,account,password,sms};
 } finally { fs.unlinkSync(dir+'/operator.env') }
}
async function loginOperator(c){
 const result=expect('运营真实HTTP密码+合成OTP登录',await api('POST','/api/auth/operator/login','',{body:{account:c.account,password:c.password,sms_code:c.sms}}));
 return result.access_token;
}
async function disableOperator(c){
 const dir='.cache/r1a-onboarding-image',original=fs.readFileSync(dir+'/backend.env','utf8');
 fs.writeFileSync(dir+'/operator.env',original+`\nOPERATOR_ACCOUNT=${c.account}\n`,{mode:0o600});
 try { docker(['run','--rm','--network','vehicle-auth-local_default','--env-file',dir+'/operator.env','vehicle-auth/backend:r1a-onboarding',`--spring.profiles.active=${f.envVar(f.BACKEND,'SPRING_PROFILES_ACTIVE')},operator-admin`,'--spring.main.web-application-type=none','--operator.action=disable']); } finally { fs.unlinkSync(dir+'/operator.env') }
}

async function main(){
 f.verifyIsolation('r1a-onboarding');check('隔离镜像、V020与63表',true);
 const sha=createHash('sha256').update(fs.readFileSync('backend/target/platform-backend-0.1.0-SNAPSHOT.jar')).digest('hex');
 if(docker(['exec',f.BACKEND,'sha256sum','/app/app.jar']).split(/\s/)[0]!==sha)throw Error('Runtime mismatch; refusing fixture writes');check('运行JAR一致',true);
 if(f.envVar(f.BACKEND,'MERCHANT_ONBOARDING_ENABLED')!=='true')throw Error('Isolated onboarding flag required');
 prepareFixtures();check('合成车主与资质文件就绪',true);
 const secret=f.envVar(f.BACKEND,'JWT_SECRET'),app=f.envVar(f.BACKEND,'WECHAT_APP_ID');
 const ownerA=f.mint(secret,app,{type:'user',role:'OWNER',subject:OWNER_A}),ownerB=f.mint(secret,app,{type:'user',role:'OWNER',subject:OWNER_B}),ownerC=f.mint(secret,app,{type:'user',role:'OWNER',subject:OWNER_C});
 sessions.push(ownerA,ownerB,ownerC);
 const aFiles=[await uploadQualification(ownerA.token,'qualification-a1'),await uploadQualification(ownerA.token,'qualification-a2')];
 const bFiles=[await uploadQualification(ownerB.token,'qualification-b1'),await uploadQualification(ownerB.token,'qualification-b2')];
 const cFiles=[await uploadQualification(ownerC.token,'qualification-c1'),await uploadQualification(ownerC.token,'qualification-c2')];
 check('资质文件真实上传且扫描完成',uploadedFiles.length===6);
 const onboardOp=createOperator(true,false),reviewOp=createOperator(false,true);operators.push(onboardOp,reviewOp);
 const operator=await loginOperator(onboardOp),reviewer=await loginOperator(reviewOp);
 check('登录响应带入驻权限位',true);

 // ---- 身份与越权 ----
 expect('匿名车主查询拒绝',await api('GET','/api/merchant-applications/mine'),401,40100);
 expect('匿名运营查询拒绝',await api('GET','/api/admin/merchant-applications'),401,40100);
 for(const [name,target] of [['运营待审列表','/api/admin/merchant-applications'],['运营配额','/api/admin/merchant-quotas']]){
  expect('车主读'+name+'拒绝',await api('GET',target,ownerA.token),403,40300);
  expect('仅经验审核权限运营读'+name+'拒绝',await api('GET',target,reviewer),403,40300);
 }
 expect('车主设置配额拒绝',await api('PUT','/api/admin/merchant-quotas',ownerA.token,{key:randomUUID(),body:{region_code:REGION_FULL,category:'REPAIR',max_active:1}}),403,40300);
 expect('运营读车主申请拒绝',await api('GET','/api/merchant-applications/mine',operator),403,40300);
 check('匿名/越权/权限隔离',true);

 // ---- 严格正文校验 ----
 const strict=application('合成验收汽修厂',REGION_FULL,[aFiles[0],aFiles[1]]);
 const badBodies=[
  ['未知字段',{...strict,extra:1}],['未知品类',{...strict,category:'UNKNOWN'}],['区域码位数',{...strict,region_code:'44010'}],
  ['手机号',{...strict,contact_phone:'1380000770'}],['空资质文件',{...strict,qualification_file_ids:[]}],
  ['重复资质文件',{...strict,qualification_file_ids:[aFiles[0],aFiles[0]]}],['十个资质文件',{...strict,qualification_file_ids:[aFiles[0],aFiles[1],1,2,3,4,5,6,7,8]}],
  ['名称过短',{...strict,merchant_name:'甲'}],['名称首空白',{...strict,merchant_name:' 合成验收汽修厂'}]];
 for(const [name,body] of badBodies)expect('申请正文拒绝:'+name,await api('POST','/api/merchant-applications',ownerA.token,{key:randomUUID(),body}),400,40001);
 expect('缺幂等键拒绝',await api('POST','/api/merchant-applications',ownerA.token,{body:strict}),400,40001);
 const badModerations=[['未知字段',{revision:1,decision:'APPROVE',reason_code:null,extra:1}],['批准带驳回码',{revision:1,decision:'APPROVE',reason_code:'DUPLICATE_STORE'}],['驳回无码',{revision:1,decision:'REJECT',reason_code:null}],['未知驳回码',{revision:1,decision:'REJECT',reason_code:'OTHER'}],['未知决定',{revision:1,decision:'MAYBE',reason_code:null}]];
 for(const [name,body] of badModerations)expect('审核正文拒绝:'+name,await api('POST',`/api/admin/merchant-applications/${APP_MISSING}/moderate`,operator,{key:randomUUID(),body}),400,40001);
 expect('不存在的申请详情404',await api('GET',`/api/admin/merchant-applications/${APP_MISSING}`,operator),404,40400);
 check('严格正文与幂等键前置校验',true);

 // ---- 不安全资质文件 ----
 for(const [name,files] of [['不存在的文件',[FILE_MISSING]],['未完成扫描的文件',[FILE_DIRTY]],['他人文件',[bFiles[0]]]]){
  expect('不安全资质拒绝:'+name,await api('POST','/api/merchant-applications',ownerA.token,{key:randomUUID(),body:application('合成验收汽修厂',REGION_FULL,files)}),409,49002);
 }
 const emptyMine=expect('无申请时mine只含null申请',await api('GET','/api/merchant-applications/mine',ownerA.token));
 check('无申请响应形状',emptyMine.application===null&&!Object.hasOwn(emptyMine,'reviews'));
 check('不安全文件不产生申请行',sql(`SELECT COUNT(*) FROM merchant_application WHERE applicant_user_id=${OWNER_A}`)==='0');

 // ---- fail-closed 配额 ----
 const emptyQuotas=expect('配额初始为空',await api('GET','/api/admin/merchant-quotas',operator));
 check('配额空列表',Array.isArray(emptyQuotas.items)&&emptyQuotas.items.length===0);
 const submitB=expect('车主B提交TIRE品类申请',await api('POST','/api/merchant-applications',ownerB.token,{key:randomUUID(),body:{...application('合成验收轮胎店',REGION_FULL,[bFiles[0],bFiles[1]]),category:'TIRE'}}));
 const appB=submitB.application.application_id;applicationIds.push(appB);
 check('首提revision=1待审',submitB.revision===1&&submitB.application.status==='PENDING_REVIEW');
 expect('未配额区域批准失败(fail-closed)',await api('POST',`/api/admin/merchant-applications/${appB}/moderate`,operator,{key:randomUUID(),body:{revision:1,decision:'APPROVE',reason_code:null}}),409,49010);
 check('无配额记录视为0',true);

 // ---- 配额设置与同值幂等 ----
 const quotaKey=randomUUID();
 const setQuota=expect('运营设置配额1',await api('PUT','/api/admin/merchant-quotas',operator,{key:quotaKey,body:{region_code:REGION_FULL,category:'REPAIR',max_active:1}}));
 check('配额响应含活跃门店',setQuota.max_active===1&&setQuota.active_stores===0);
 const auditAfterSet=Number(sql(`SELECT COUNT(*) FROM audit_log WHERE resource_type='merchant_region_category_quota'`));
 expect('同值重复设置成功',await api('PUT','/api/admin/merchant-quotas',operator,{key:randomUUID(),body:{region_code:REGION_FULL,category:'REPAIR',max_active:1}}));
 check('同值设置不产生新审计',Number(sql(`SELECT COUNT(*) FROM audit_log WHERE resource_type='merchant_region_category_quota'`))===auditAfterSet);
 expect('同幂等键不同载荷拒绝',await api('PUT','/api/admin/merchant-quotas',operator,{key:quotaKey,body:{region_code:REGION_FULL,category:'REPAIR',max_active:2}}),400,40001);

 // ---- 开店主链（车主A）----
 const submitKeyA=randomUUID();
 const first=expect('车主A提交申请',await api('POST','/api/merchant-applications',ownerA.token,{key:submitKeyA,body:strict}));
 const appA=first.application.application_id;applicationIds.push(appA);
 const replay=await api('POST','/api/merchant-applications',ownerA.token,{key:submitKeyA,body:strict});
 check('同键同载荷重放原响应',replay.status===200&&f.deepEqual(replay.json&&replay.json.data,first));
 expect('不同键重复提交拒绝',await api('POST','/api/merchant-applications',ownerA.token,{key:randomUUID(),body:strict}),409,49001);
 const pending=expect('运营待审列表',await api('GET','/api/admin/merchant-applications',operator));
 const listed=pending.items.find(i=>i.application_id===appA);
 check('待审固定摘要六字段',listed&&Object.keys(listed).sort().join(',')==='application_id,category,merchant_name,region_code,revision,submitted_at'&&listed.revision===1);
 const detail=expect('运营详情',await api('GET',`/api/admin/merchant-applications/${appA}`,operator));
 check('详情含申请与空审核史',detail.application.contact_phone==='13800007701'&&detail.reviews.length===0);
 const access=expect('资质受控访问',await api('GET',`/api/admin/merchant-applications/${appA}/files/${aFiles[0]}/access`,operator));
 check('短时签名地址',typeof access.url==='string'&&access.url.length>0&&typeof access.expires_at==='string');
 expect('快照外文件拒绝',await api('GET',`/api/admin/merchant-applications/${appA}/files/${bFiles[0]}/access`,operator),404,40400);
 expect('仅经验审核权限运营不能访问资质',await api('GET',`/api/admin/merchant-applications/${appA}/files/${aFiles[0]}/access`,reviewer),403,40300);
 const approveKey=randomUUID(),approveBody={revision:1,decision:'APPROVE',reason_code:null};
 const approved=expect('运营批准开店',await api('POST',`/api/admin/merchant-applications/${appA}/moderate`,operator,{key:approveKey,body:approveBody}));
 const storeId=approved.merchant_id;storeIds.push(storeId);
 check('批准响应回填门店',approved.application.status==='APPROVED'&&approved.application.merchant_id===storeId&&approved.decision==='APPROVE');
 const replayModerate=await api('POST',`/api/admin/merchant-applications/${appA}/moderate`,operator,{key:approveKey,body:approveBody});
 check('批准同键重放原响应',replayModerate.status===200&&f.deepEqual(replayModerate.json.data,approved));
 expect('已审revision再批拒绝',await api('POST',`/api/admin/merchant-applications/${appA}/moderate`,operator,{key:randomUUID(),body:approveBody}),409,49003);
 const storeRow=sql(`SELECT CONCAT(merchant_type,'|',region_code,'|',status,'|',REPLACE(qualification,' ','')) FROM merchant WHERE id=${storeId}`);
 check('门店快照落库正确',storeRow==='3|'+REGION_FULL+'|1|['+aFiles[0]+','+aFiles[1]+']',storeRow);
 check('开店账号待激活无密码',sql(`SELECT CONCAT(role,'|',account,'|',status,'|',password_hash IS NULL) FROM staff_account WHERE account='m${storeId}'`)==='MERCHANT|m'+storeId+'|PENDING_ACTIVATION|1');
 check('审核记录唯一不可变',sql(`SELECT COUNT(*) FROM merchant_application_review WHERE application_id=${appA}`)==='1');
 const mineA=expect('车主A读取进度',await api('GET','/api/merchant-applications/mine',ownerA.token));
 check('进度含批准历史',mineA.application.status==='APPROVED'&&mineA.reviews[0].decision==='APPROVED'&&mineA.reviews[0].merchant_id===storeId);
 expect('已通过再提交拒绝',await api('POST','/api/merchant-applications',ownerA.token,{key:randomUUID(),body:strict}),409,49001);

 // ---- 满额与配额下限 ----
 const submitC=expect('车主C提交同区同品类',await api('POST','/api/merchant-applications',ownerC.token,{key:randomUUID(),body:application('合成验收二店',REGION_FULL,[cFiles[0],cFiles[1]])}));
 const appC=submitC.application.application_id;applicationIds.push(appC);
 expect('配额满批准拒绝',await api('POST',`/api/admin/merchant-applications/${appC}/moderate`,operator,{key:randomUUID(),body:{revision:1,decision:'APPROVE',reason_code:null}}),409,49010);
 const rejectKey=randomUUID();
 const rejected=expect('满额后固定码驳回',await api('POST',`/api/admin/merchant-applications/${appC}/moderate`,operator,{key:rejectKey,body:{revision:1,decision:'REJECT',reason_code:'REGION_QUOTA_FULL'}}));
 check('驳回响应与原因',rejected.decision==='REJECT'&&rejected.reason_code==='REGION_QUOTA_FULL'&&rejected.application.status==='REJECTED');
 expect('配额低于有效门店拒绝',await api('PUT','/api/admin/merchant-quotas',operator,{key:randomUUID(),body:{region_code:REGION_FULL,category:'REPAIR',max_active:0}}),409,49011);
 const quotasNow=expect('配额列表含活跃数',await api('GET','/api/admin/merchant-quotas',operator));
 const row=quotasNow.items.find(q=>q.region_code===REGION_FULL&&q.category==='REPAIR');
 check('活跃门店计数',row&&row.max_active===1&&row.active_stores===1);

 // ---- 驳回重提 ----
 expect('重提前换区配额',await api('PUT','/api/admin/merchant-quotas',operator,{key:randomUUID(),body:{region_code:REGION_SECOND,category:'REPAIR',max_active:2}}));
 const resubmit=expect('车主C驳回后重提',await api('POST','/api/merchant-applications',ownerC.token,{key:randomUUID(),body:application('合成验收二店',REGION_SECOND,[cFiles[0],cFiles[1]])}));
 check('重提复用行且revision=2',resubmit.application.application_id===appC&&resubmit.revision===2&&resubmit.application.status==='PENDING_REVIEW'&&resubmit.application.review_reason===null);
 expect('旧revision审核拒绝',await api('POST',`/api/admin/merchant-applications/${appC}/moderate`,operator,{key:randomUUID(),body:{revision:1,decision:'APPROVE',reason_code:null}}),409,49003);
 const second=expect('重提后批准开店',await api('POST',`/api/admin/merchant-applications/${appC}/moderate`,operator,{key:randomUUID(),body:{revision:2,decision:'APPROVE',reason_code:null}}));
 const storeC=second.merchant_id;storeIds.push(storeC);
 const mineC=expect('车主C读取完整历史',await api('GET','/api/merchant-applications/mine',ownerC.token));
 check('审核历史按revision倒序',mineC.application.revision===2&&mineC.reviews.length===2&&mineC.reviews[0].revision===2&&mineC.reviews[0].decision==='APPROVED'&&mineC.reviews[1].decision==='REJECTED'&&mineC.reviews[1].reason_code==='REGION_QUOTA_FULL');
 expect('配额低于有效门店拒绝(二区)',await api('PUT','/api/admin/merchant-quotas',operator,{key:randomUUID(),body:{region_code:REGION_SECOND,category:'REPAIR',max_active:0}}),409,49011);

 // ---- 车主B在配额后批准 ----
 expect('车主B区域品类配额',await api('PUT','/api/admin/merchant-quotas',operator,{key:randomUUID(),body:{region_code:REGION_FULL,category:'TIRE',max_active:1}}));
 const approvedB=expect('TIRE配额后批准',await api('POST',`/api/admin/merchant-applications/${appB}/moderate`,operator,{key:randomUUID(),body:{revision:1,decision:'APPROVE',reason_code:null}}));
 storeIds.push(approvedB.merchant_id);
 check('三店落库',storeIds.length===3&&new Set(storeIds).size===3);

 // ---- 会话失效 ----
 sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=${quote(ownerB.jti)}`);
 expect('撤销会话后请求拒绝',await api('GET','/api/merchant-applications/mine',ownerB.token),401,40100);
 expect('运营会话不受影响',await api('GET','/api/admin/merchant-quotas',operator),200);

 // ---- 审计最小化 ----
 const auditRows=sql(`SELECT before_state,after_state FROM audit_log WHERE resource_type='merchant_application' AND resource_id IN (${applicationIds.join(',')})`).split(/\r?\n/).filter(Boolean);
 const leaked=auditRows.filter(row=>row.includes('138000077')||row.includes('synthetic-r1a/'));
 const shapeOk=auditRows.every(row=>{const states=[...row.matchAll(/\{[^}]*\}/g)].map(m=>Object.keys(JSON.parse(m[0])).sort().join(','));return states.every(k=>k===''||k==='category,merchant_id,region_code,revision,status')});
 check('审计不含手机号与对象键且仅白名单字段',auditRows.length>=6&&leaked.length===0&&shapeOk);
 completed=true;
}

async function cleanup(){
 for(const s of sessions){try{sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=${quote(s.jti)} AND revoked_at IS NULL`)}catch{}}
 for(const o of operators){try{await disableOperator(o)}catch{}}
 if(!completed)return;
 const stores=storeIds.join(',');
 sql(`DELETE FROM audit_log WHERE (resource_type='merchant_application' AND resource_id IN (${applicationIds.join(',')})) OR (resource_type='merchant' AND resource_id IN (${stores})) OR (resource_type='merchant_region_category_quota') OR (resource_type='operator_account' AND resource_id IN (${operators.map(o=>o.id).join(',')}) AND actor_type='system');
 DELETE FROM merchant_application_review WHERE application_id IN (${applicationIds.join(',')});
 DELETE FROM merchant_application WHERE id IN (${applicationIds.join(',')}) OR applicant_user_id IN (${OWNER_A},${OWNER_B},${OWNER_C});
 DELETE FROM staff_account WHERE account IN (${storeIds.map(id=>quote('m'+id)).join(',')});
 DELETE FROM merchant WHERE id IN (${stores});
 DELETE FROM merchant_region_category_quota WHERE region_code IN (${quote(REGION_FULL)},${quote(REGION_SECOND)});
 DELETE FROM idempotency_record WHERE (actor_type='user' AND actor_id IN (${OWNER_A},${OWNER_B},${OWNER_C})) OR (actor_type='operator_account' AND actor_id IN (${operators.map(o=>o.id).join(',')}));
 DELETE FROM sms_code WHERE phone IN ('13900007791','13900007792');
 DELETE FROM file_object WHERE owner_type='user' AND (owner_id IN (${OWNER_A},${OWNER_B},${OWNER_C}) OR id=${FILE_DIRTY});
 DELETE FROM auth_session WHERE subject_id IN (${OWNER_A},${OWNER_B},${OWNER_C}) AND subject_type='user';
 DELETE FROM user WHERE id IN (${OWNER_A},${OWNER_B},${OWNER_C});
 DELETE FROM audit_log WHERE resource_type='operator_account' AND actor_type='system' AND resource_id IN (${operators.map(o=>o.id).join(',')});
 DELETE FROM operator_account WHERE id IN (${operators.map(o=>o.id).join(',')});`);
}

main().then(async()=>{const failed=f.results.filter(r=>!r.ok);console.log(`\n结果: ${f.results.length-failed.length}/${f.results.length} 通过`);await cleanup();process.exitCode=failed.length?1:0}).catch(async e=>{console.error('失败:',e.message);await cleanup();process.exitCode=1});
