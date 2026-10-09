// Opt-in isolated real HTTP acceptance. Existing A7 real redemption is a prerequisite.
const fs=require('node:fs'),https=require('node:https'),{createHash}=require('node:crypto');
const f=require('./local_service_work_fixtures.cjs');
const {sql,api,check,dataOf,codeOf,docker,randomUUID}=f;
const order=f.ORDER_POSITIVE,vehicle=9206290,otherId=9207391;let sessions=[],keep=false,validated=false,changedFile=null;
function expect(name,r,status=200){check(name,r.status===status,`HTTP=${r.status},code=${codeOf(r)}`);if(r.status!==status)throw Error('Unexpected response at '+name);return dataOf(r);}
async function waitArchive(token){for(let n=0;n<40;n++){const r=await api('GET',`/api/archive/list?vehicle_id=${vehicle}`,token);if(r.status!==200)throw Error('Archive query failed');const a=dataOf(r).list.find(a=>a.input_type===4&&a.source.order_id===order);if(a)return a;await new Promise(r=>setTimeout(r,500));}throw Error('Archive task did not complete');}
async function download(value){const url=new URL(value);if(url.origin!=='https://127.0.0.1:9443')throw Error('Only isolated TLS endpoint supported');
 return new Promise((resolve,reject)=>{const req=https.get(url,{ca:fs.readFileSync('test-results/local-upload-cert.pem')},r=>{let bytes=0;r.on('data',chunk=>bytes+=chunk.length);r.on('end',()=>resolve({status:r.statusCode,bytes}));});req.setTimeout(10000,()=>req.destroy());req.on('error',()=>reject(Error('Isolated TLS certificate/download failed')));});}
async function main(){
 f.verifyIsolation('a7-archive');check('隔离归档镜像与V017/58表',true);
 const sha=createHash('sha256').update(fs.readFileSync('backend/target/platform-backend-0.1.0-SNAPSHOT.jar')).digest('hex');const matches=docker(['exec',f.BACKEND,'sha256sum','/app/app.jar']).split(/\s/)[0]===sha;check('运行JAR与当前代码一致',matches);if(!matches)throw Error('Runtime JAR mismatch; refuse fixture writes');
 if(sql(`SELECT COUNT(*) FROM \`order\` o JOIN user u ON u.id=o.user_id JOIN vehicle v ON v.id=o.vehicle_id JOIN order_redemption r ON r.order_id=o.id WHERE o.id=${order} AND o.order_no LIKE 'synthetic-a6-%' AND u.openid='local-a6-owner' AND v.user_id=${vehicle} AND o.status='COMPLETED' AND r.test_mode=1`)!=='1')throw Error('Expected previously HTTP-verified synthetic redemption');
 if(sql(`SELECT COUNT(*) FROM user WHERE id=${otherId} AND openid<>'synthetic-a7-archive-other'`)!=='0')throw Error('Synthetic identity collision');
 if(sql(`SELECT COUNT(*) FROM technician_report WHERE order_id=${order} AND status=1 AND signed_at IS NOT NULL AND is_deleted=0`)!=='1')throw Error('Expected immutable signed synthetic work');
 validated=true;
 const secret=f.envVar(f.BACKEND,'JWT_SECRET'),app=f.envVar(f.BACKEND,'WECHAT_APP_ID');
 const owner=f.mint(secret,app,{type:'user',role:'OWNER',subject:vehicle});
 sessions.push(owner);
 sql(`INSERT INTO user(id,openid,status) VALUES(${otherId},'synthetic-a7-archive-other',1) ON DUPLICATE KEY UPDATE status=1;`);
 const other=f.mint(secret,app,{type:'user',role:'OWNER',subject:otherId});
 sessions.push(other);
 const merchant=f.mint(secret,app,{type:'staff_account',role:'MERCHANT',subject:9206201,merchantId:9206201,appId:'merchant-account'});sessions=[owner,other,merchant];
 // Reset only the named synthetic order's evaluation/output, retaining its real redemption and work.
 sql(`DELETE af FROM vehicle_archive_file af JOIN service_archive_job j ON j.archive_id=af.archive_id WHERE j.order_id=${order}; DELETE a FROM vehicle_archive a JOIN service_archive_job j ON j.archive_id=a.id WHERE j.order_id=${order}; DELETE FROM service_archive_job WHERE order_id=${order}; DELETE rf FROM order_review_file rf JOIN order_review r ON r.id=rf.review_id WHERE r.order_id=${order}; DELETE FROM order_review WHERE order_id=${order};`);
 const baseline=expect('本人旧档案可查',await api('GET',`/api/archive/list?vehicle_id=${vehicle}`,owner.token)).total;
 const body={order_id:order,rating:5,content:'合成施工归档验收：已完成服务',photo_file_ids:[]},key=randomUUID();
 // A source gap must leave the evaluation successful and the background task recoverable.
 sql(`UPDATE technician_report SET is_deleted=1 WHERE order_id=${order};`);
 expect('缺报工不撤销评价',await api('POST','/api/order/review',owner.token,{key,body}));
 check('新评价只创建一个任务',sql(`SELECT COUNT(*) FROM service_archive_job WHERE order_id=${order}`)==='1');
 await new Promise(r=>setTimeout(r,1600));check('来源不完整不生成档案',sql(`SELECT status FROM service_archive_job WHERE order_id=${order}`)==='PENDING');
 check('恢复状态留下失败记录',Number(sql(`SELECT attempts FROM service_archive_job WHERE order_id=${order}`))>=1);
 sql(`UPDATE technician_report SET is_deleted=0 WHERE order_id=${order}; UPDATE service_archive_job SET next_attempt_at=UTC_TIMESTAMP() WHERE order_id=${order};`);
 const archived=await waitArchive(owner.token);check('后台恢复并自动归档',archived.input_type===4);
 check('同订单唯一档案',sql(`SELECT COUNT(*) FROM service_archive_job WHERE order_id=${order} AND status='DONE' AND archive_id IS NOT NULL`)==='1');
 check('保留旧手动档案',expect('本人列表含新记录',await api('GET',`/api/archive/list?vehicle_id=${vehicle}`,owner.token)).total===baseline+1);
 check('里程不伪造',archived.mileage===null);check('测试施工标注',archived.test_mode===true);
 check('实际工时和来源追溯',archived.work_minutes>0&&archived.source.order_id===order&&archived.source.report_id>0&&archived.source.review_id>0&&archived.source.redemption_id>0);
 check('施工照片不泄露签字',archived.file_ids.length>0&&!archived.file_ids.includes(Number(sql(`SELECT e.file_id FROM service_evidence_file e WHERE e.order_id=${order} AND e.kind='SIGNATURE'`))));
 expect('同键评价重放',await api('POST','/api/order/review',owner.token,{key,body}));expect('新键同内容评价重放',await api('POST','/api/order/review',owner.token,{key:randomUUID(),body}));
 check('重放仍只有一个任务',sql(`SELECT COUNT(*) FROM service_archive_job WHERE order_id=${order}`)==='1');
 const file=archived.file_ids[0],path=`/api/archive/${archived.archive_id}/files/${file}/access`;
 const access=await api('GET',path,owner.token),signed=expect('本人施工图片授权',access);check('短时私有HTTPS和不缓存',access.noStore&&signed.url.startsWith('https://')&&Date.parse(signed.expires_at)>Date.now());
 const image=await download(signed.url);check('实际私有HTTPS图片可读取（显式信任本机公钥证书）',image.status===200&&image.bytes>0);
 expect('其他车主图片拒绝',await api('GET',path,other.token),404);expect('商家身份图片拒绝',await api('GET',path,merchant.token),403);expect('匿名图片拒绝',await api('GET',path),401);
 expect('其他车主档案拒绝',await api('GET',`/api/archive/list?vehicle_id=${vehicle}`,other.token),404);
 expect('不能借普通图片接口读员工文件',await api('GET',`/api/file/${file}/access`,owner.token),404);
 changedFile=file;sql(`UPDATE file_object SET is_deleted=1 WHERE id=${file};`);expect('已删除图片不能再次签名',await api('GET',path,owner.token),404);sql(`UPDATE file_object SET is_deleted=0 WHERE id=${file};`);changedFile=null;
 check('归档独立系统审计',Number(sql(`SELECT COUNT(*) FROM audit_log WHERE action='SERVICE_ARCHIVE_CREATE' AND resource_id=${archived.archive_id}`))===1);
 sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id='${other.jti}';`);expect('会话撤销即时生效',await api('GET',path,other.token),401);
 if(process.argv.includes('--keep-ui')){fs.writeFileSync('.cache/a7-archive-ui-sessions.json',JSON.stringify({owner,order,vehicle,archive:archived.archive_id}),{mode:0o600});keep=true;console.log('Synthetic UI session retained locally; credentials withheld');}
}
main().catch(e=>{console.error(e.message);process.exitCode=1}).finally(()=>{
 try{if(validated)sql(`UPDATE technician_report SET is_deleted=0 WHERE order_id=${order};`);if(validated&&changedFile)sql(`UPDATE file_object SET is_deleted=0 WHERE id=${changedFile};`);if(!keep&&sessions.length)sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id IN (${sessions.map(s=>"'"+s.jti+"'").join(',')});`);else if(keep)sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id IN (${sessions.slice(1).map(s=>"'"+s.jti+"'").join(',')});`);}catch{console.error('Synthetic fixture cleanup failed');process.exitCode=1;}
 const passed=f.results.filter(r=>r.ok).length;fs.writeFileSync('.cache/a7-service-archives-e2e.json',JSON.stringify({passed,total:f.results.length,results:f.results},null,2));console.log(`A7 service archives real HTTP: ${passed}/${f.results.length}`);if(passed!==f.results.length)process.exitCode=1;
});
