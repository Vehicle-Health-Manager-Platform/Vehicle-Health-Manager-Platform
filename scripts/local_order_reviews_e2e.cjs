// Opt-in acceptance of real owner feedback; existing A7.1 real HTTP redemption is the prerequisite.
// Only login is bridged by synthetic sessions. No business success payload is mocked.
const fs=require('node:fs'),{createHash}=require('node:crypto')
const f=require('./local_service_work_fixtures.cjs')
const {sql,api,check,dataOf,codeOf,results,docker,randomUUID,deepEqual}=f
const order=f.ORDER_POSITIVE,ownerId=9206290,otherId=9207291,origin='http://127.0.0.1:18080'
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aSkcAAAAASUVORK5CYII=','base64')
let sessions=[],keep=false
function expect(name,r,status=200,code=0){check(name,r.status===status&&codeOf(r)===code,`HTTP=${r.status},code=${codeOf(r)}`);if(r.status!==status||codeOf(r)!==code)throw Error('Unexpected response at '+name);return dataOf(r)}
async function upload(token){const body=new FormData();body.append('file',new Blob([png],{type:'image/png'}),'synthetic-owner-review.png');const r=await fetch(origin+'/api/file/upload',{method:'POST',headers:{Authorization:'Bearer '+token,'Idempotency-Key':randomUUID()},body,signal:AbortSignal.timeout(85000)});const j=await r.json();if(r.status!==200||!j.data?.file_id)throw Error('Real scanned owner upload failed; details withheld');return j.data.file_id}
async function main(){
 const image=f.verifyIsolation('a7-review');check('评价隔离镜像与V016/57表',image==='vehicle-auth/backend:a7-owner-review')
 const hash=createHash('sha256').update(fs.readFileSync('backend/target/platform-backend-0.1.0-SNAPSHOT.jar')).digest('hex');if(docker(['exec',f.BACKEND,'sha256sum','/app/app.jar']).split(/\s/)[0]!==hash)throw Error('Runtime JAR mismatch; refuse fixtures');check('运行JAR与当前评价代码一致',true)
 const health=await fetch(origin+'/actuator/health').then(r=>r.json());if(health.status!=='UP')throw Error('Backend not healthy')
 if(sql(`SELECT COUNT(*) FROM \`order\` o JOIN user u ON u.id=o.user_id JOIN order_redemption r ON r.order_id=o.id WHERE o.id=${order} AND o.order_no='synthetic-a6-positive' AND u.id=${ownerId} AND u.openid='local-a6-owner' AND o.status='COMPLETED' AND r.test_mode=1;`)!=='1')throw Error('Requires previously accepted A7.1 synthetic real HTTP redemption; no fabricated eligibility')
 if(sql(`SELECT COUNT(*) FROM user WHERE id=${otherId} AND openid<>'local-a7-review-other';`)!=='0')throw Error('Refuse synthetic identity collision')
 sql(`INSERT INTO user(id,openid,nickname,status) VALUES(${otherId},'local-a7-review-other','评价合成他人',1) ON DUPLICATE KEY UPDATE status=1,is_deleted=0;`)
 // This acceptance owns feedback only on the explicitly named synthetic order.
 const old=sql(`SELECT id FROM order_review WHERE order_id=${order};`);if(old){if(sql(`SELECT COUNT(*) FROM order_review WHERE id=${old} AND user_id=${ownerId};`)!=='1')throw Error('Refuse foreign feedback cleanup');sql(`DELETE FROM order_review_file WHERE review_id=${old};DELETE FROM audit_log WHERE resource_type='order_review' AND resource_id=${old};DELETE FROM order_review WHERE id=${old};`)}
 sql(`DELETE FROM idempotency_record WHERE actor_type='user' AND actor_id=${ownerId} AND request_path='/api/order/review';`)
 const app=f.envVar(f.BACKEND,'WECHAT_APP_ID'),secret=f.envVar(f.BACKEND,'JWT_SECRET')
 const s={owner:f.mint(secret,app,{type:'user',role:'OWNER',subject:ownerId}),other:f.mint(secret,app,{type:'user',role:'OWNER',subject:otherId}),shop:f.mint(secret,app,{type:'staff_account',role:'MERCHANT',subject:9206201,appId:'merchant-account',merchantId:9206201}),tech:f.mint(secret,app,{type:'staff_account',role:'TECHNICIAN',subject:f.TECH_A,appId:app,merchantId:9206201,bindingId:f.BIND_A}),order}
 sessions=Object.values(s).filter(v=>v?.jti).map(v=>v.jti);const token=s.owner.token,path='/api/order/review',detail=`/api/order/${order}/review`
 const initial=expect('真实本人查询评价资格',await api('GET',detail,token));check('可信测试核销允许评价且未生成评价',initial.can_submit===true&&initial.test_mode===true&&initial.review===null)
 const photo=await upload(token),foreign=await upload(s.other.token);check('评价图片经真实私有存储与安全扫描',sql(`SELECT COUNT(*) FROM file_object WHERE id IN (${photo},${foreign}) AND scan_status='CLEAN' AND is_deleted=0;`)==='2')
 const body={order_id:order,rating:5,content:'合成本机验收：服务沟通清楚，复检正常🙂',photo_file_ids:[photo]}
 expect('匿名不能提交评价',await api('POST',path,null,{key:randomUUID(),body}),401,40100)
 for(const role of ['shop','tech']){expect(role+'不能代替车主评价',await api('POST',path,s[role].token,{key:randomUUID(),body}),403,40300);expect(role+'不能读取本人评价',await api('GET',detail,s[role].token),403,40300)}
 expect('他人不能读取订单评价',await api('GET',detail,s.other.token),404,40400);expect('他人不能提交订单评价',await api('POST',path,s.other.token,{key:randomUUID(),body}),404,40400)
 expect('无UUID不能提交',await api('POST',path,token,{body}),400,40001)
 for(const [name,b] of [['超界评分',{...body,rating:6}],['空文字',{...body,content:' '}],['重复图片',{...body,photo_file_ids:[photo,photo]}],['额外身份',{...body,user_id:ownerId}],['伪造测试标识',{...body,test_mode:false}]])expect(name+'拒绝',await api('POST',path,token,{key:randomUUID(),body:b}),400,40001)
 const duplicate=await fetch(origin+path,{method:'POST',headers:{Authorization:'Bearer '+token,'Idempotency-Key':randomUUID(),'Content-Type':'application/json'},body:JSON.stringify(body).replace('"rating":5','"rating":5,"rating":1')});check('重复JSON键拒绝',duplicate.status===400)
 expect('查询参数不可覆盖归属',await api('GET',detail+'?user_id='+ownerId,token),400,40001)
 expect('他人安全图片不能绑定',await api('POST',path,token,{key:randomUUID(),body:{...body,photo_file_ids:[foreign]}}),422,42200)
 for(const [name,change,restore] of [
  ['未完成',`UPDATE \`order\` SET status='PENDING_VERIFY' WHERE id=${order}`,`UPDATE \`order\` SET status='COMPLETED' WHERE id=${order}`],
  ['历史缺可信核销',`UPDATE order_redemption SET merchant_id=9206202 WHERE order_id=${order}`,`UPDATE order_redemption SET merchant_id=9206201 WHERE order_id=${order}`],
  ['付款金额不符',`UPDATE payment SET amount=129 WHERE order_id=${order} AND status='SUCCEEDED'`,`UPDATE payment SET amount=128 WHERE order_id=${order} AND status='SUCCEEDED'`],
  ['有效付款事件缺失',`UPDATE payment_event SET is_deleted=1 WHERE payment_id IN (SELECT id FROM payment WHERE order_id=${order})`,`UPDATE payment_event SET is_deleted=0 WHERE payment_id IN (SELECT id FROM payment WHERE order_id=${order})`]]){
  sql(change);try{expect(name+'不能评价',await api('POST',path,token,{key:randomUUID(),body}),409,44001)}finally{sql(restore)}
 }
 sql(`UPDATE file_object SET is_deleted=1 WHERE id=${photo};`);try{expect('删除图片不能提交',await api('POST',path,token,{key:randomUUID(),body}),422,42200)}finally{sql(`UPDATE file_object SET is_deleted=0 WHERE id=${photo};`)}
 if(process.argv.includes('--prepare-ui')){fs.writeFileSync('.cache/a7-review-ui-sessions.json',JSON.stringify(s),{mode:0o600});keep=true;check('真实H5评价前置已准备，合成凭据不打印',true);return}
 const key=randomUUID(),saved=await api('POST',path,token,{key,body}),record=expect('真实本人评价提交',saved);check('写响应no-store并保留测试来源与图片',saved.noStore&&record.review.test_mode===true&&deepEqual(record.review.photo_file_ids,[photo]))
 const replay=expect('同键原内容重试',await api('POST',path,token,{key,body}));check('原响应一致',deepEqual(record,replay))
 const same=expect('新键同内容返回已有评价',await api('POST',path,token,{key:randomUUID(),body}));check('新键仍为同一评价',deepEqual(record,same))
 expect('同键不同评分拒绝',await api('POST',path,token,{key,body:{...body,rating:1}}),400,40001);expect('新键不同内容不可修改',await api('POST',path,token,{key:randomUUID(),body:{...body,content:'另一条反馈'}}),409,44002)
 const read=expect('本人读取已保存评价',await api('GET',detail,token));check('已有评价锁定且最小字段不泄露身份',read.can_submit===false&&read.unavailable_reason==='ALREADY_REVIEWED'&&Object.keys(read.review).length===6&&Object.keys(read).length===5)
 const access=expect('本人评价图片真实私有签名接口',await api('GET',`/api/file/${photo}/access`,token));check('签名仅临时本人访问',!!access.url&&!!access.expires_at)
 check('唯一评价唯一审计状态仍COMPLETED',sql(`SELECT (SELECT COUNT(*) FROM order_review WHERE order_id=${order}),(SELECT COUNT(*) FROM audit_log WHERE action='ORDER_REVIEW_CREATE' AND resource_type='order_review' AND resource_id=${record.review.review_id}),(SELECT status FROM \`order\` WHERE id=${order});`)==='1\t1\tCOMPLETED')
 const audit=sql(`SELECT after_state FROM audit_log WHERE action='ORDER_REVIEW_CREATE' AND resource_id=${record.review.review_id};`);check('审计不复制评价文字或图片ID',!audit.includes(body.content)&&!audit.includes('photo_file_ids'))
 sql(`UPDATE file_object SET is_deleted=1 WHERE id=${photo};`);try{expect('失效图片不能命中旧成功缓存',await api('POST',path,token,{key,body}),422,42200);const history=expect('失效图片仍可读取本人历史文字',await api('GET',detail,token));check('历史文字保留',history.review.content===body.content)}finally{sql(`UPDATE file_object SET is_deleted=0 WHERE id=${photo};`)}
 sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id='${s.owner.jti}';`);expect('撤销本人会话不能重放',await api('POST',path,token,{key,body}),401,40100)
}
main().catch(e=>{check('脚本执行',false,e.message);process.exitCode=1}).finally(()=>{
 if(!keep&&sessions.length)sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id IN (${sessions.map(v=>"'"+v+"'").join(',')});`)
 fs.mkdirSync('test-results',{recursive:true});const report={stage:'A7.2a',syntheticLogin:true,realMoney:false,redemptionPrerequisite:'A7.1 real HTTP accepted synthetic order',results,passed:results.filter(r=>r.ok).length,total:results.length};fs.writeFileSync(process.argv.includes('--prepare-ui')?'test-results/local-owner-review-ui-prepare.json':'test-results/local-owner-review-e2e.json',JSON.stringify(report,null,2));console.log(`A7.2a real HTTP ${report.passed}/${report.total}; credentials withheld`);if(report.passed!==report.total)process.exitCode=1
})
