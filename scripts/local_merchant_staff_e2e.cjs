// R1b close-out: real backend container + real isolated MySQL.
// Explicitly opt-in. Every identity below is a SYNTHETIC fixture and every session is a
// documented test bridge (a JWT minted with the local secret and written into auth_session),
// NOT real WeChat/SMS login. The employee code path is however exercised for real: the code
// is issued over HTTP by the store manager and then handed to the real
// POST /api/auth/technician/bind endpoint, so rotation/revocation/invalid-old-code are proven
// against the actual lookup, not by reading a column. The real WeChat identity and formal SMS
// remain R9 and are NOT covered here.
const fs=require('node:fs'),{createHash,randomUUID}=require('node:crypto'),f=require('./local_service_work_fixtures.cjs');
const {sql,api,dataOf,codeOf,check,docker}=f;

const SHOP_A=9208101,SHOP_B=9208102;
const MGR_A=9208111,STAFF_A=9208112,TECH_A=9208121,MGR_B=9208113,TECH_FOREIGN=9208122,BIND_A=9208131;
const PROFILE_PHONE='13800008801',REGION='440106';
const MISSING_STAFF=9208199,PROFILE_PHONE_B='13800008802';
const rowKeys='account,created_at,display_name,employee_code_issued,phone_masked,role,staff_id,status,wechat_bound';
const profileKeys='address,can_edit,contact_phone,lat,lng,merchant_id,merchant_type,name,region_code,status';
const writeKeys='address,contact_phone,lat,lng,name';
const quote=n=>"'"+String(n).replaceAll("'","''")+"'";
const password=()=>'R1b'+randomUUID().replaceAll('-','').slice(0,12);
let sessions=[],completed=false;

function expect(name,r,status=200,code){check(name,r.status===status,`HTTP=${r.status},code=${codeOf(r)}`);if(code!==undefined&&codeOf(r)!==code)check(name+' 错误码',false,`期望 ${code} 实得 ${codeOf(r)}`);if(r.status!==status)throw Error('Unexpected response: '+name+' HTTP '+r.status);return dataOf(r)}
const keysOf=o=>Object.keys(o).sort().join(',');

function prepareFixtures(){
 if(sql(`SELECT COUNT(*) FROM merchant WHERE id IN (${SHOP_A},${SHOP_B}) AND name NOT LIKE '合成验收门店%'`)!=='0')throw Error('Synthetic store collision');
 if(sql(`SELECT COUNT(*) FROM staff_account WHERE merchant_id IN (${SHOP_A},${SHOP_B}) AND account NOT LIKE 's${SHOP_A}-%' AND account NOT LIKE 't${SHOP_A}-%' AND account NOT LIKE 'm${SHOP_A}' AND account NOT LIKE 's${SHOP_B}-%' AND account NOT LIKE 't${SHOP_B}-%' AND account NOT LIKE 'm${SHOP_B}'`)!=='0')throw Error('Synthetic staff collision');
 if(sql(`SELECT COUNT(*) FROM staff_wechat_identity WHERE openid LIKE 'synthetic-r1b-%' AND id<>${BIND_A}`)!=='0')throw Error('Synthetic binding collision');
 // Repeatable: reset only this script's own synthetic rows.
 sql(`
DELETE FROM audit_log WHERE (resource_type='staff_account' AND actor_id IN (${MGR_A},${STAFF_A},${MGR_B}) AND actor_type='staff_account');
DELETE FROM audit_log WHERE resource_type='merchant' AND resource_id IN (${SHOP_A},${SHOP_B});
DELETE FROM audit_log WHERE resource_type='staff_account' AND resource_id IN (SELECT id FROM staff_account WHERE merchant_id IN (${SHOP_A},${SHOP_B})) AND actor_type='staff_account';
DELETE FROM idempotency_record WHERE actor_type='staff_account' AND actor_id IN (${MGR_A},${STAFF_A},${MGR_B},${TECH_A},${TECH_FOREIGN});
DELETE FROM auth_session WHERE subject_type='staff_account' AND subject_id IN (SELECT id FROM staff_account WHERE merchant_id IN (${SHOP_A},${SHOP_B}));
DELETE FROM staff_wechat_identity WHERE staff_account_id IN (SELECT id FROM staff_account WHERE merchant_id IN (${SHOP_A},${SHOP_B})) OR openid LIKE 'synthetic-r1b-%';
DELETE FROM staff_account WHERE merchant_id IN (${SHOP_A},${SHOP_B});
DELETE FROM merchant WHERE id IN (${SHOP_A},${SHOP_B});

START TRANSACTION;
INSERT INTO merchant(id,merchant_type,name,address,contact_phone,status,region_code) VALUES
 (${SHOP_A},2,'合成验收门店甲','合成验收地址甲',${quote(PROFILE_PHONE)},1,${quote(REGION)}),
 (${SHOP_B},2,'合成验收门店乙','合成验收地址乙',${quote(PROFILE_PHONE_B)},1,${quote(REGION)});
INSERT INTO staff_account(id,merchant_id,role,account,display_name,status,created_by) VALUES
 (${MGR_A},${SHOP_A},'MERCHANT','m${SHOP_A}','合成店长甲','ACTIVE',NULL),
 (${STAFF_A},${SHOP_A},'STAFF','s${SHOP_A}-90','合成店员甲','ACTIVE',${MGR_A}),
 (${TECH_A},${SHOP_A},'TECHNICIAN','t${SHOP_A}-90','合成技师甲','ACTIVE',${MGR_A}),
 (${MGR_B},${SHOP_B},'MERCHANT','m${SHOP_B}','合成店长乙','ACTIVE',NULL),
 (${TECH_FOREIGN},${SHOP_B},'TECHNICIAN','t${SHOP_B}-90','合成他店技师','ACTIVE',${MGR_B});
INSERT INTO staff_wechat_identity(id,app_id,openid,staff_account_id) VALUES
 (${BIND_A},'__APPID__','synthetic-r1b-openid-tech-a',${TECH_A});
COMMIT;`.replaceAll('__APPID__',f.envVar(f.BACKEND,'WECHAT_APP_ID')));
}

async function bind(openid,code){
 return api('POST','/api/auth/technician/bind',f.bindToken(f.envVar(f.BACKEND,'JWT_SECRET'),f.envVar(f.BACKEND,'WECHAT_APP_ID'),openid),{body:{employee_code:code}});
}

async function main(){
 f.verifyIsolation('r1b-staff');check('隔离镜像、V021 两列与 63 表',true);
 const sha=createHash('sha256').update(fs.readFileSync('backend/target/platform-backend-0.1.0-SNAPSHOT.jar')).digest('hex');
 if(docker(['exec',f.BACKEND,'sha256sum','/app/app.jar']).split(/\s/)[0]!==sha)throw Error('Runtime mismatch; refusing fixture writes');check('运行JAR与工作树一致',true);
 if(f.envVar(f.BACKEND,'MERCHANT_STAFF_ENABLED')!=='true')throw Error('Isolated staff flag required');
 prepareFixtures();check('合成门店与员工夹具就绪',true);
 const secret=f.envVar(f.BACKEND,'JWT_SECRET'),app=f.envVar(f.BACKEND,'WECHAT_APP_ID');
 const refreshA=randomUUID()+randomUUID();
 const mgrA=f.mint(secret,app,{type:'staff_account',role:'MERCHANT',subject:MGR_A,appId:'merchant-account',merchantId:SHOP_A});
 const staffA=f.mint(secret,app,{type:'staff_account',role:'STAFF',subject:STAFF_A,appId:'merchant-account',merchantId:SHOP_A,refreshToken:refreshA});
 const mgrB=f.mint(secret,app,{type:'staff_account',role:'MERCHANT',subject:MGR_B,appId:'merchant-account',merchantId:SHOP_B});
 const techA=f.mint(secret,app,{type:'staff_account',role:'TECHNICIAN',subject:TECH_A,appId:app,merchantId:SHOP_A,bindingId:BIND_A});
 sessions=[mgrA,staffA,mgrB,techA];

 // ---- 匿名与身份边界 ----
 expect('匿名读员工列表拒绝',await api('GET','/api/merchant/staff'),401,40100);
 expect('匿名读门店资料拒绝',await api('GET','/api/merchant/profile'),401,40100);
 expect('技师令牌读员工列表拒绝',await api('GET','/api/merchant/staff',techA.token),403,40300);
 expect('技师令牌读门店资料拒绝',await api('GET','/api/merchant/profile',techA.token),403,40300);
 expect('技师令牌新增员工拒绝',await api('POST','/api/merchant/staff',techA.token,{key:randomUUID(),body:{role:'STAFF',display_name:'合成丙',phone:'13800008803',password:'abcd1234'}}),403,40300);
 // 店员在履约执行面与店长同级（选品定价写仍限店长，属管理面）。
 expect('店员可读本店订单（执行面同级）',await api('GET','/api/merchant/orders?page=1&page_size=20',staffA.token));
 check('匿名与角色边界',true);

 // ---- 严格正文与参数 ----
 const good={role:'STAFF',display_name:'合成店员乙',phone:'13800008804',password:password()};
 for(const [name,body] of [['未知字段',{...good,extra:1}],['角色为店长',{...good,role:'MERCHANT'}],['未知角色',{...good,role:'BOSS'}],
   ['姓名过短',{...good,display_name:'甲'}],['姓名首空白',{...good,display_name:' 合成店员乙'}],['密码无数字',{...good,password:'abcdefghij'}],
   ['密码过短',{...good,password:'a1'}],['密码含非 ASCII',{...good,password:'abcdé123'}],['店员缺手机号',{role:'STAFF',display_name:'合成店员乙',password:'abcd1234'}],
   ['手机号非大陆',{...good,phone:'1380000880'}],['手机号非文本',{...good,phone:13800008804}],['字段过少',{role:'STAFF',display_name:'合成店员乙'}]])
  expect('新增正文拒绝:'+name,await api('POST','/api/merchant/staff',mgrA.token,{key:randomUUID(),body}),400,40001);
 expect('新增缺幂等键拒绝',await api('POST','/api/merchant/staff',mgrA.token,{body:good}),400,40001);
 expect('新增非 UUID 幂等键拒绝',await api('POST','/api/merchant/staff',mgrA.token,{key:'not-a-uuid',body:good}),400,40001);
 expect('新增多余 query 拒绝',await api('POST','/api/merchant/staff?role=STAFF',mgrA.token,{key:randomUUID(),body:good}),400,40001);
 expect('列表未知 query 拒绝',await api('GET','/api/merchant/staff?sort=id',mgrA.token),400,40001);
 expect('列表未知角色筛选拒绝',await api('GET','/api/merchant/staff?role=MERCHANT',mgrA.token),400,40001);
 expect('列表页大小超限拒绝',await api('GET','/api/merchant/staff?page_size=51',mgrA.token),400,40001);
 expect('停用正文非空对象拒绝',await api('POST',`/api/merchant/staff/${TECH_A}/disable`,mgrA.token,{key:randomUUID(),body:{staff_id:TECH_A}}),400,40001);
 expect('停用缺幂等键拒绝',await api('POST',`/api/merchant/staff/${TECH_A}/disable`,mgrA.token,{body:{}}),400,40001);
 expect('资料读带 query 拒绝',await api('GET','/api/merchant/profile?x=1',mgrA.token),400,40001);
 expect('资料写字段数不为五拒绝',await api('PUT','/api/merchant/profile',mgrA.token,{key:randomUUID(),body:{name:'合成验收门店甲',address:'合成验收地址甲',contact_phone:PROFILE_PHONE,lng:null}}),400,40001);
 expect('资料写未知字段拒绝',await api('PUT','/api/merchant/profile',mgrA.token,{key:randomUUID(),body:{name:'合成验收门店甲',address:'合成验收地址甲',contact_phone:PROFILE_PHONE,lng:null,lat:null,status:1}}),400,40001);
 expect('资料写越界经度拒绝',await api('PUT','/api/merchant/profile',mgrA.token,{key:randomUUID(),body:{name:'合成验收门店甲',address:'合成验收地址甲',contact_phone:PROFILE_PHONE,lng:181,lat:null}}),400,40001);
 expect('资料写坐标为文本拒绝',await api('PUT','/api/merchant/profile',mgrA.token,{key:randomUUID(),body:{name:'合成验收门店甲',address:'合成验收地址甲',contact_phone:PROFILE_PHONE,lng:'113.2644',lat:null}}),400,40001);
 check('严格正文与幂等键前置校验',true);

 // ---- 新增员工：账号生成、同形与明文不落库 ----
 const clerkPassword=good.password,clerkKey=randomUUID();
 const createdResponse=await api('POST','/api/merchant/staff',mgrA.token,{key:clerkKey,body:good});
 const created=expect('店长新增店员',createdResponse);
 check('创建响应九字段',keysOf(created)===rowKeys,keysOf(created));
 check('账号按规则生成且状态为启用',created.account===`s${SHOP_A}-1`&&created.role==='STAFF'&&created.status==='ACTIVE'&&created.display_name==='合成店员乙',JSON.stringify(created));
 check('手机号脱敏且无明文回显',created.phone_masked==='138****8804'&&!JSON.stringify(created).includes('13800008804'),JSON.stringify(created.phone_masked));
 check('未签发员工码且未绑定微信',created.employee_code_issued===false&&created.wechat_bound===false);
 const replay=await api('POST','/api/merchant/staff',mgrA.token,{key:clerkKey,body:good});
 check('同键同载荷重放原响应（含 request_id）',replay.status===200&&replay.json.request_id===createdResponse.json.request_id&&f.deepEqual(replay.json.data,created),'HTTP '+replay.status);
 expect('同键不同载荷拒绝',await api('POST','/api/merchant/staff',mgrA.token,{key:clerkKey,body:{...good,display_name:'合成店员丙'}}),400,40001);
 const tech=expect('店长新增技师（手机号可选）',await api('POST','/api/merchant/staff',mgrA.token,{key:randomUUID(),body:{role:'TECHNICIAN',display_name:'合成技师乙',password:password()}}));
 check('技师账号规则与手机号为空',tech.account===`t${SHOP_A}-1`&&tech.role==='TECHNICIAN'&&tech.phone_masked===null,JSON.stringify(tech));
 check('创建与列表行逐字段同形',keysOf(tech)===rowKeys);
 // 新建的店员用来做「仍然有效的店员身份」越权与只读检查——固定夹具里的店员稍后会被停用。
 const clerk=f.mint(secret,app,{type:'staff_account',role:'STAFF',subject:created.staff_id,appId:'merchant-account',merchantId:SHOP_A});
 sessions.push(clerk);
 check('新建店员会话可用（执行面同级）',(await api('GET','/api/merchant/orders?page=1&page_size=20',clerk.token)).status===200);
 check('密码以 BCrypt 存储',sql(`SELECT SUBSTRING(password_hash,1,4) FROM staff_account WHERE id=${created.staff_id}`)==='$2a$'||sql(`SELECT SUBSTRING(password_hash,1,4) FROM staff_account WHERE id=${created.staff_id}`)==='$2b$');
 // 幂等记录只存请求哈希与响应体，明文密码要么在响应体里要么已经在请求哈希里消失——
 // 两处都查：落库载荷（response_body/audit）与磁盘全文（request_hash 之外不该有明文字符号）。
 const persisted=sql(`SELECT COALESCE(CAST(response_body AS CHAR),'') FROM idempotency_record WHERE actor_type='staff_account' AND actor_id=${MGR_A}`).split('\n').join('')
  +sql(`SELECT CONCAT(COALESCE(CAST(before_state AS CHAR),''),'|',COALESCE(CAST(after_state AS CHAR),'')) FROM audit_log WHERE resource_type='staff_account' AND actor_id=${MGR_A}`).split('\n').join('');
 check('明文密码不落幂等记录与审计',!persisted.includes(clerkPassword)&&!persisted.toLowerCase().includes('password')&&!persisted.includes('13800008804'));
 const listed=expect('店长查看本店员工列表',await api('GET','/api/merchant/staff',mgrA.token));
 check('列表含四名下属且不含店长自己',listed.total===4&&listed.items.every(i=>i.staff_id!==MGR_A&&i.role!=='MERCHANT'),JSON.stringify(listed.items.map(i=>[i.staff_id,i.role])));
 check('列表行键集合与创建响应一致',listed.items.every(i=>keysOf(i)===rowKeys));
 check('列表投影不含密码或明文手机号',!JSON.stringify(listed).includes('password')&&!JSON.stringify(listed).includes('13800008804'));
 const staffOnly=expect('按角色筛选只出店员',await api('GET','/api/merchant/staff?role=STAFF',mgrA.token));
 check('角色筛选生效',staffOnly.total===2&&staffOnly.items.every(i=>i.role==='STAFF'));
 check('分页元数据正确',listed.page===1&&listed.page_size===20);
 const foreignList=expect('他店店长只看到本店员工',await api('GET','/api/merchant/staff',mgrB.token));
 check('跨店列表不串',foreignList.total===1&&foreignList.items[0].staff_id===TECH_FOREIGN);

 // ---- 停用即时阻断原会话 ----
 const before=Number(sql(`SELECT COUNT(*) FROM auth_session WHERE subject_type='staff_account' AND subject_id=${STAFF_A} AND revoked_at IS NULL`));
 check('停用前存在有效会话',before>=1,String(before));
 expect('店员调用管理面拒绝（停用前）',await api('POST',`/api/merchant/staff/${TECH_A}/disable`,staffA.token,{key:randomUUID(),body:{}}),403,40300);
 const disableKey=randomUUID();
 const disabled=expect('店长停用店员',await api('POST',`/api/merchant/staff/${STAFF_A}/disable`,mgrA.token,{key:disableKey,body:{}}));
 check('停用响应回传撤销会话数',disabled.status==='DISABLED'&&disabled.sessions_revoked===before,JSON.stringify(disabled));
 expect('停用后原访问令牌立即失效',await api('GET','/api/merchant/staff',staffA.token),401,40100);
 expect('停用后刷新令牌不可用',await api('POST','/api/auth/refresh','',{body:{refresh_token:refreshA}}),401,40100);
 check('停用撤销该员工全部会话',sql(`SELECT COUNT(*) FROM auth_session WHERE subject_type='staff_account' AND subject_id=${STAFF_A} AND revoked_at IS NULL`)==='0');
 expect('重复停用幂等成功',await api('POST',`/api/merchant/staff/${STAFF_A}/disable`,mgrA.token,{key:randomUUID(),body:{}}));
 check('重复停用不再重复撤销',sql(`SELECT COUNT(*) FROM auth_session WHERE subject_id=${STAFF_A} AND revoked_at IS NOT NULL`)==='1');
 expect('跨店停用他人员工按不存在处理',await api('POST',`/api/merchant/staff/${STAFF_A}/disable`,mgrB.token,{key:randomUUID(),body:{}}),404,40400);
 expect('停用不存在的员工按不存在处理',await api('POST',`/api/merchant/staff/${MISSING_STAFF}/disable`,mgrA.token,{key:randomUUID(),body:{}}),404,40400);
 expect('店长账号不可经本接口停用',await api('POST',`/api/merchant/staff/${MGR_B}/disable`,mgrB.token,{key:randomUUID(),body:{}}),404,40400);

 // ---- 技师停用：撤销微信绑定 ----
 const techDisable=expect('店长停用技师',await api('POST',`/api/merchant/staff/${TECH_A}/disable`,mgrA.token,{key:randomUUID(),body:{}}));
 check('技师停用同样回传会话数',techDisable.status==='DISABLED'&&techDisable.sessions_revoked>=1,JSON.stringify(techDisable));
 check('技师停用撤销有效微信绑定',sql(`SELECT status FROM staff_wechat_identity WHERE id=${BIND_A}`)==='REVOKED');
 expect('技师停用后原令牌失效',await api('GET','/api/merchant/orders?page=1',techA.token),401,40100);
 const techRow=sql(`SELECT display_name FROM staff_account WHERE id=${TECH_A}`);check('停用保留员工行（不改历史）',techRow==='合成技师甲',techRow);
 const enableKey=randomUUID();
 const enableResponse=await api('POST',`/api/merchant/staff/${TECH_A}/enable`,mgrA.token,{key:enableKey,body:{}});
 const enabled=expect('店长启用技师',enableResponse);
 check('启用响应只含员工与状态',keysOf(enabled)==='staff_id,status'&&enabled.status==='ACTIVE'&&enabled.staff_id===TECH_A,JSON.stringify(enabled));
 check('启用不恢复已撤销的微信绑定',sql(`SELECT status FROM staff_wechat_identity WHERE id=${BIND_A}`)==='REVOKED');
 const enableReplay=await api('POST',`/api/merchant/staff/${TECH_A}/enable`,mgrA.token,{key:enableKey,body:{}});
 check('启用同键重放原响应',enableReplay.status===200&&f.deepEqual(enableReplay.json.data,enabled)&&enableReplay.json.request_id===enableResponse.json.request_id);
 expect('店员启用他人拒绝',await api('POST',`/api/merchant/staff/${TECH_A}/enable`,clerk.token,{key:randomUUID(),body:{}}),403,40300);
 expect('停用店员后再签发员工码拒绝',await api('POST',`/api/merchant/staff/${STAFF_A}/employee-code`,mgrA.token),404,40400);
 check('非技师不能签发员工码',true);

 // ---- 员工码：签发 → 真实绑定 → 轮换 → 撤销 ----
 expect('店员签发员工码拒绝',await api('POST',`/api/merchant/staff/${TECH_A}/employee-code`,clerk.token),403,40300);
 expect('跨店签发员工码按不存在处理',await api('POST',`/api/merchant/staff/${TECH_A}/employee-code`,mgrB.token),404,40400);
 const issued1=expect('店长签发员工码',await api('POST',`/api/merchant/staff/${TECH_A}/employee-code`,mgrA.token));
 check('签发响应为一次性明文码',keysOf(issued1)==='employee_code,single_use,staff_id'&&issued1.staff_id===TECH_A&&issued1.single_use===true);
 check('员工码形如 32 位 base64url',/^[A-Za-z0-9_-]{32}$/.test(issued1.employee_code),issued1.employee_code.length+' chars');
 check('库中只存员工码哈希',sql(`SELECT employee_code_hash FROM staff_account WHERE id=${TECH_A}`)===f.sha256(issued1.employee_code));
 check('员工码明文不落库',!sql(`SELECT CONCAT(COALESCE(CAST(before_state AS CHAR),''),'|',COALESCE(CAST(after_state AS CHAR),'')) FROM audit_log WHERE resource_type='staff_account' AND resource_id=${TECH_A}`).includes(issued1.employee_code));
 check('员工码不进幂等记录',sql(`SELECT COUNT(*) FROM idempotency_record WHERE actor_type='staff_account' AND actor_id=${MGR_A} AND request_path LIKE '%employee-code%'`)==='0');
 const openid1='synthetic-r1b-openid-1';
 const bound1=expect('用员工码真实绑定微信身份',await bind(openid1,issued1.employee_code));
 check('绑定返回技师会话',typeof bound1.access_token==='string'&&typeof bound1.refresh_token==='string'&&bound1.user&&bound1.user.role==='technician',JSON.stringify(bound1.user||{}));
 check('绑定写入有效身份行',sql(`SELECT CONCAT(status,'|',staff_account_id) FROM staff_wechat_identity WHERE openid=${quote(openid1)} AND is_deleted=0`)==='ACTIVE|'+TECH_A);
 check('列表显示员工码已签发且微信已绑定',expect('列表反映签发与绑定状态',await api('GET','/api/merchant/staff?role=TECHNICIAN',mgrA.token)).items.find(i=>i.staff_id===TECH_A).employee_code_issued===true);
 const issued2=expect('重新签发员工码（轮换）',await api('POST',`/api/merchant/staff/${TECH_A}/employee-code`,mgrA.token));
 check('轮换产生新码',issued2.employee_code!==issued1.employee_code);
 check('轮换后库中哈希为新码',sql(`SELECT employee_code_hash FROM staff_account WHERE id=${TECH_A}`)===f.sha256(issued2.employee_code));
 expect('轮换后旧码绑定拒绝（旧码已失效）',await bind('synthetic-r1b-openid-2',issued1.employee_code),403,40300);
 expect('轮换后旧绑定已撤销',await api('GET','/api/merchant/orders?page=1',bound1.access_token),401,40100);
 check('轮换撤销既有有效绑定',sql(`SELECT COUNT(*) FROM staff_wechat_identity WHERE staff_account_id=${TECH_A} AND status='ACTIVE' AND is_deleted=0`)==='0');
 const bound2=expect('轮换后的新码可以绑定',await bind('synthetic-r1b-openid-3',issued2.employee_code));
 check('新码绑定成功',typeof bound2.access_token==='string');
 const revoked=expect('店长撤销员工码',await api('DELETE',`/api/merchant/staff/${TECH_A}/employee-code`,mgrA.token));
 check('撤销响应形状',keysOf(revoked)==='employee_code_revoked,staff_id'&&revoked.employee_code_revoked===true);
 check('撤销清空哈希',sql(`SELECT employee_code_hash IS NULL FROM staff_account WHERE id=${TECH_A}`)==='1');
 expect('撤销后员工码不可绑定',await bind('synthetic-r1b-openid-4',issued2.employee_code),403,40300);
 expect('撤销后上一轮绑定失效',await api('GET','/api/merchant/orders?page=1',bound2.access_token),401,40100);
 expect('撤销后重复撤销幂等成功',await api('DELETE',`/api/merchant/staff/${TECH_A}/employee-code`,mgrA.token));
 check('员工码轮换与撤销闭环',true);

 // ---- 门店资料 ----
 const staffProfile=expect('店员可读门店资料',await api('GET','/api/merchant/profile',clerk.token));
 check('资料读为十字段白名单',keysOf(staffProfile)===profileKeys,keysOf(staffProfile));
 check('店员读资料 can_edit=false',staffProfile.can_edit===false&&staffProfile.merchant_id===SHOP_A);
 check('资料读不含资质与运营字段',['qualification','grade','commission_rate','region_protected','is_deleted'].every(k=>!(k in staffProfile)));
 const mgrProfile=expect('店长读本店资料',await api('GET','/api/merchant/profile',mgrA.token));
 check('店长读 can_edit=true',mgrProfile.can_edit===true);
 expect('店员写门店资料拒绝',await api('PUT','/api/merchant/profile',clerk.token,{key:randomUUID(),body:{name:'合成验收门店甲',address:'合成验收地址甲',contact_phone:PROFILE_PHONE,lng:null,lat:null}}),403,40300);
 const foreignProfile=expect('他店店长读资料读到本店',await api('GET','/api/merchant/profile',mgrB.token));
 check('跨店资料不串',foreignProfile.merchant_id===SHOP_B&&foreignProfile.name==='合成验收门店乙');
 const profileWrite={name:'合成验收门店甲改',address:'合成验收地址甲改',contact_phone:PROFILE_PHONE,lng:113.2644,lat:23.1291};
 const profileKey=randomUUID();
 const auditsBefore=Number(sql(`SELECT COUNT(*) FROM audit_log WHERE resource_type='merchant' AND resource_id=${SHOP_A} AND action='MERCHANT_PROFILE_UPDATE'`));
 const saved=expect('店长写门店资料',await api('PUT','/api/merchant/profile',mgrA.token,{key:profileKey,body:profileWrite}));
 check('写响应为五字段白名单',keysOf(saved)===writeKeys,keysOf(saved));
 check('写响应坐标与请求一致',saved.lng==='113.2644'&&saved.lat==='23.1291',JSON.stringify(saved));
 const profileReplay=await api('PUT','/api/merchant/profile',mgrA.token,{key:profileKey,body:profileWrite});
 check('同键同载荷重放原响应',profileReplay.status===200&&f.deepEqual(profileReplay.json&&profileReplay.json.data,saved));
 // 尾随零是同一个意图：JSON 里 113.26440000 与 113.2644 必须复用同一幂等记录。
 const trailing=await api('PUT','/api/merchant/profile',mgrA.token,{key:profileKey,body:{...profileWrite,lng:113.26440000}});
 check('坐标尾随零不产生新意图',trailing.status===200&&f.deepEqual(trailing.json&&trailing.json.data,saved),'HTTP '+trailing.status);
 check('重放不产生新审计',Number(sql(`SELECT COUNT(*) FROM audit_log WHERE resource_type='merchant' AND resource_id=${SHOP_A} AND action='MERCHANT_PROFILE_UPDATE'`))===auditsBefore+1,String(auditsBefore));
 expect('同键不同载荷拒绝',await api('PUT','/api/merchant/profile',mgrA.token,{key:profileKey,body:{...profileWrite,name:'合成验收门店丙'}}),400,40001);
 const readBack=expect('写后读回坐标规范化',await api('GET','/api/merchant/profile',mgrA.token));
 check('读写坐标一致（DECIMAL 不补零）',readBack.lng==='113.2644'&&readBack.lat==='23.1291',readBack.lng+'/'+readBack.lat);
 const cleared=expect('坐标置空仍可保存',await api('PUT','/api/merchant/profile',mgrA.token,{key:randomUUID(),body:{...profileWrite,lng:null,lat:null}}));
 check('空坐标写响应为 null',cleared.lng===null&&cleared.lat===null);
 check('空坐标读回为 null',expect('读回空坐标',await api('GET','/api/merchant/profile',mgrA.token)).lng===null);
 const auditStates=sql(`SELECT CONCAT(COALESCE(CAST(before_state AS CHAR),''),'|',COALESCE(CAST(after_state AS CHAR),'')) FROM audit_log WHERE resource_type='merchant' AND resource_id=${SHOP_A} AND action='MERCHANT_PROFILE_UPDATE'`).split(/\r?\n/).filter(Boolean);
 const stateKeys=auditStates.map(r=>[...r.matchAll(/\{[^}]*\}/g)].map(m=>keysOf(JSON.parse(m[0])))).flat();
 check('资料审计两次写各留一条且只含白名单五字段',auditStates.length===2&&stateKeys.length===4&&stateKeys.every(k=>k===writeKeys),auditStates.length+' 条 -> '+JSON.stringify(stateKeys));
 check('资料审计不含运营字段与资质',!auditStates.join('').includes('qualification')&&!auditStates.join('').includes('commission_rate'));
 check('未改动的运营字段保持原值',sql(`SELECT CONCAT(status,'|',merchant_type,'|',region_code) FROM merchant WHERE id=${SHOP_A}`)===`1|2|${REGION}`);
 completed=true;
}

async function cleanup(){
 for(const s of sessions){if(!s)continue;try{sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id=${quote(s.jti)} AND revoked_at IS NULL`)}catch{}}
 if(!completed)return;
 const stores=[SHOP_A,SHOP_B].join(',');
 const ids=sql(`SELECT COALESCE(GROUP_CONCAT(id),0) FROM staff_account WHERE merchant_id IN (${stores})`);
 sql(`
DELETE FROM audit_log WHERE actor_type='staff_account' AND (actor_id IN (${MGR_A},${STAFF_A},${MGR_B}) OR resource_id IN (${ids}));
DELETE FROM audit_log WHERE resource_type='merchant' AND resource_id IN (${stores});
DELETE FROM audit_log WHERE resource_type='staff_account' AND resource_id IN (${ids}) AND actor_type='staff_account';
DELETE FROM idempotency_record WHERE actor_type='staff_account' AND actor_id IN (${MGR_A},${STAFF_A},${MGR_B},${TECH_A},${TECH_FOREIGN});
DELETE FROM auth_session WHERE subject_type='staff_account' AND subject_id IN (${ids});
DELETE FROM staff_wechat_identity WHERE staff_account_id IN (${ids}) OR openid LIKE 'synthetic-r1b-%';
DELETE FROM staff_account WHERE merchant_id IN (${stores});
DELETE FROM merchant WHERE id IN (${stores});`);
}

// 零残留断言：逐表各发一条 COUNT，再拼成同一个形状。
// 刻意不用单个大 CONCAT——手工拼 6 个子查询时漏掉 CONCAT 的收尾括号会让整条语句
// 报 `near '' at line N`（看起来像输入被截断，其实是括号不配平），排查方向会被带偏。
function residue(){
 const stores=[SHOP_A,SHOP_B].join(','),ids=[MGR_A,STAFF_A,MGR_B,TECH_A,TECH_FOREIGN].join(',');
 return [
  `staff_account WHERE merchant_id IN (${stores})`,
  `merchant WHERE id IN (${stores})`,
  `staff_wechat_identity WHERE openid LIKE 'synthetic-r1b-%'`,
  `auth_session WHERE subject_type='staff_account' AND subject_id IN (${ids})`,
  `idempotency_record WHERE actor_type='staff_account' AND actor_id IN (${ids})`,
  `audit_log WHERE actor_type='staff_account' AND actor_id IN (${MGR_A},${STAFF_A},${MGR_B})`,
 ].map(where=>sql(`SELECT COUNT(*) FROM ${where}`)).join('|');
}

main().then(async()=>{
 const failed=f.results.filter(r=>!r.ok);
 console.log(`\n结果: ${f.results.length-failed.length}/${f.results.length} 通过`);
 await cleanup();
 const left=residue();
 check('合成数据零残留（员工/门店/绑定/会话/幂等/审计）',left==='0|0|0|0|0|0',left);
 const still=f.results.filter(r=>!r.ok);
 console.log(`最终: ${f.results.length-still.length}/${f.results.length} 通过`);
 process.exitCode=still.length?1:0;
}).catch(async e=>{console.error('失败:',e.message);await cleanup();process.exitCode=1});
