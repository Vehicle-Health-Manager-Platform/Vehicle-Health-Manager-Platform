// Opt-in A7 acceptance against loopback backend, isolated MySQL, real storage/scanner.
// Synthetic sessions bridge login only; payment uses the authenticated LOCAL_TEST callback.
const fs=require('node:fs'),{createHmac,createHash}=require('node:crypto')
const f=require('./local_service_work_fixtures.cjs')
const {sql,api,check,dataOf,codeOf,results,deepEqual,docker,randomUUID}=f
const order=f.ORDER_POSITIVE,shopId=9206201,ownerId=9206290
const origin='http://127.0.0.1:18080',png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aSkcAAAAASUVORK5CYII=','base64')
let sessions=[],keep=false
function expect(name,r,status=200,code=0){check(name,r.status===status&&(code===undefined||codeOf(r)===code),`HTTP=${r.status},code=${codeOf(r)}`);if(r.status!==status)throw Error('Unexpected HTTP at '+name);return dataOf(r)}
async function upload(token,role){
 const body=new FormData();body.append('file',new Blob([png],{type:'image/png'}),'synthetic-a7.png')
 const r=await fetch(origin+`/api/${role}/files/upload`,{method:'POST',headers:{Authorization:'Bearer '+token,'Idempotency-Key':randomUUID()},body,signal:AbortSignal.timeout(85000)})
 const j=await r.json();if(r.status!==200||!j.data?.file_id)throw Error('Real scanned upload failed; details withheld');return j.data.file_id
}
async function main(){
 check('A7隔离镜像与V015/55表',f.verifyIsolation('a7')==='vehicle-auth/backend:a7-redeem')
 const hash=createHash('sha256').update(fs.readFileSync('backend/target/platform-backend-0.1.0-SNAPSHOT.jar')).digest('hex')
 if(docker(['exec',f.BACKEND,'sha256sum','/app/app.jar']).split(/\s/)[0]!==hash)throw Error('Runtime JAR mismatch; refuse fixtures');check('运行JAR与本地核销代码一致',true)
 const health=await fetch(origin+'/actuator/health').then(r=>r.json());if(health.status!=='UP')throw Error('Backend not healthy');check('真实后端健康',true)
 const appId=f.envVar(f.BACKEND,'WECHAT_APP_ID'),secret=f.envVar(f.BACKEND,'JWT_SECRET')
 if(f.envVar(f.BACKEND,'SPRING_PROFILES_ACTIVE')!=='local-payment-test'||f.envVar(f.BACKEND,'PAYMENT_LOCAL_TEST_ENABLED')!=='true')throw Error('Isolated payment profile required')
 if(sql("SELECT COUNT(*) FROM `order` WHERE id IN (9206401,9206402,9206403,9206404) AND order_no NOT LIKE 'synthetic-a6-%';")!=='0')throw Error('Refuse synthetic identifier collision')
 sql(`DELETE FROM audit_log WHERE resource_type='payment' AND resource_id IN (SELECT id FROM payment WHERE order_id IN (9206401,9206402,9206403,9206404));DELETE FROM payment_event WHERE payment_id IN (SELECT id FROM payment WHERE order_id IN (9206401,9206402,9206403,9206404));DELETE FROM payment_exception WHERE order_id IN (9206401,9206402,9206403,9206404);DELETE FROM payment WHERE order_id IN (9206401,9206402,9206403,9206404);DELETE FROM order_redemption WHERE order_id IN (9206401,9206402,9206403,9206404);DELETE FROM auth_rate_limit WHERE scope IN ('redeem_code','pickup_code') AND key_hash IN ('${createHash('sha256').update(shopId+':'+order).digest('hex')}');`)
 f.prepare(appId)
 const s={owner:f.mint(secret,appId,{type:'user',role:'OWNER',subject:ownerId}),shopA:f.mint(secret,appId,{type:'staff_account',role:'MERCHANT',subject:shopId,appId:'merchant-account',merchantId:shopId}),shopB:f.mint(secret,appId,{type:'staff_account',role:'MERCHANT',subject:9206202,appId:'merchant-account',merchantId:9206202}),techA:f.mint(secret,appId,{type:'staff_account',role:'TECHNICIAN',subject:f.TECH_A,appId,merchantId:shopId,bindingId:f.BIND_A}),order}
 sessions=Object.values(s).filter(v=>v?.jti).map(v=>v.jti)
 const shop=s.shopA.token,owner=s.owner.token,tech=s.techA.token,path=`/api/merchant/orders/${order}/redeem`,body={code:'246813'}
 // Restore only this synthetic order to unpaid; all following milestones use real HTTP.
 sql(`DELETE FROM pickup_check WHERE order_id=${order};UPDATE \`order\` SET status='PENDING_PAYMENT',check_in_completed_at=NULL,owner_confirmed_at=NULL,appointment_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),expires_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 15 MINUTE) WHERE id=${order};UPDATE appointment_slot SET starts_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 1 HOUR),ends_at=DATE_ADD(UTC_TIMESTAMP(),INTERVAL 2 HOUR) WHERE id=9206301;`)
 const payment=expect('真实测试支付创建',await api('POST','/api/payments/create',owner,{key:randomUUID(),body:{order_id:order,channel:'LOCAL_TEST'}}))
 const raw=JSON.stringify({event_id:randomUUID(),payment_id:payment.payment_id,channel_payment_no:'synthetic_a7_'+randomUUID().replaceAll('-',''),status:'SUCCEEDED',amount:'128.00',currency:'CNY',order_no:'synthetic-a6-positive',occurred_at:new Date().toISOString()})
 const timestamp=String(Math.floor(Date.now()/1000)),nonce=randomUUID(),signature=createHmac('sha256',f.envVar(f.BACKEND,'PAYMENT_LOCAL_TEST_SECRET')).update(timestamp+'\n'+nonce+'\n'+raw+'\n').digest('hex')
 const paid=await fetch(origin+'/api/payments/callback/LOCAL_TEST',{method:'POST',headers:{'Content-Type':'application/json','X-Test-Timestamp':timestamp,'X-Test-Nonce':nonce,'X-Test-Signature':signature},body:raw});check('真实签名测试付款回调',paid.status===200);if(paid.status!==200)throw Error('Callback failed')
 check('付款由真实PAID事件支持且未真实扣款',sql(`SELECT status FROM \`order\` WHERE id=${order};`)==='PAID'&&payment.test_mode===true&&sql(`SELECT COUNT(*) FROM payment_event WHERE payment_id=${payment.payment_id} AND outcome='PAID';`)==='1')
 const photos={};for(const slot of ['FRONT','REAR','LEFT','RIGHT','ROOF','DASHBOARD','INTERIOR'])photos[slot]=await upload(shop,'merchant')
 check('七张接车图片经过真实存储与扫描',Object.values(photos).length===7)
 expect('真实七图接车检查',await api('POST','/api/check/pickup/submit',shop,{key:randomUUID(),body:{order_id:order,appointment_code:'246813',photos,mileage:52000,fuel_level:'HALF',damage_status:'NONE',damages:[],arrival_reason:'合成本机验收时间偏差'}}))
 expect('真实车主确认',await api('POST','/api/check/pickup/confirm',owner,{key:randomUUID(),body:{order_id:order,decision:'CONFIRM'}}))
 expect('真实商家派工',await api('POST',`/api/merchant/orders/${order}/assign`,shop,{key:randomUUID(),body:{technician_id:f.TECH_A}}))
 expect('真实技师本人接单',await api('POST',`/api/tech/orders/${order}/accept`,tech,{key:randomUUID(),body:{}}))
 const protection=await upload(shop,'merchant'),processFile=await upload(tech,'tech'),finish=await upload(tech,'tech'),sign=await upload(tech,'tech')
 expect('真实防护提交',await api('POST','/api/check/protection/upload',shop,{key:randomUUID(),body:{order_id:order,items:['SEAT_COVER','STEERING_COVER'],photo_file_id:protection}}))
 expect('真实完整报工',await api('POST','/api/tech/report/submit',tech,{key:randomUUID(),body:{order_id:order,process_photos:[processFile],fault_part_photos:[],finish_photos:[finish],no_fault_parts:true,repair_plan:'合成核销验收：完成保养复检',fault_analysis:'合成验收无故障件',parts_used:[],no_parts:true,work_hours:30}}))
 expect('未质检时不能核销',await api('POST',path,shop,{key:randomUUID(),body}),409,40905)
 expect('真实PNG质检签字送待核销',await api('POST','/api/tech/sign',tech,{key:randomUUID(),body:{order_id:order,signature_file_id:sign}}))
 const ownerDetail=expect('本人订单展示已有核销码',await api('GET',`/api/order/${order}`,owner));check('码未重新发放',ownerDetail.appointment_code==='246813')
 const merchantDetail=expect('商家读接口不返回正确码',await api('GET',`/api/merchant/orders/${order}`,shop));check('商家投影无预约码',!('appointment_code' in merchantDetail)&&!('verify_code' in merchantDetail))
 expect('匿名不能核销',await api('POST',path,null,{key:randomUUID(),body}),401,40100)
 expect('车主不能代替商家核销',await api('POST',path,owner,{key:randomUUID(),body}),403,40300)
 expect('技师不能代替商家核销',await api('POST',path,tech,{key:randomUUID(),body}),403,40300)
 expect('他店不能核销',await api('POST',path,s.shopB.token,{key:randomUUID(),body}),404,40400)
 expect('严格输入拒绝目标状态',await api('POST',path,shop,{key:randomUUID(),body:{...body,status:'COMPLETED'}}),400,40001)
 expect('缺幂等键拒绝',await api('POST',path,shop,{body}),400,40001)
 expect('通用完成动作不能绕过',await api('POST',`/api/merchant/orders/${order}/actions`,shop,{key:randomUUID(),body:{action:'COMPLETE'}}),409,43006)
 for(const [name,change,restore,code] of [
  ['付款金额不符',`UPDATE payment SET amount=129 WHERE id=${payment.payment_id}`,`UPDATE payment SET amount=128 WHERE id=${payment.payment_id}`,43009],
  ['付款事件缺失',`UPDATE payment_event SET is_deleted=1 WHERE payment_id=${payment.payment_id}`,`UPDATE payment_event SET is_deleted=0 WHERE payment_id=${payment.payment_id}`,43009],
  ['质检签字失效',`UPDATE file_object SET is_deleted=1 WHERE id=${sign}`,`UPDATE file_object SET is_deleted=0 WHERE id=${sign}`,43005],
  ['车主确认失效',`UPDATE pickup_check SET owner_confirm=2 WHERE order_id=${order}`,`UPDATE pickup_check SET owner_confirm=1 WHERE order_id=${order}`,43003]]){
   sql(change);try{expect(name+'不能核销',await api('POST',path,shop,{key:randomUUID(),body}),409,code)}finally{sql(restore)}
 }
 for(let i=0;i<5;i++)expect('第'+(i+1)+'次错误码持久计数',await api('POST',path,shop,{key:randomUUID(),body:{code:'000000'}}),422,42200)
 const limited=await fetch(origin+path,{method:'POST',headers:{Authorization:'Bearer '+shop,'Idempotency-Key':randomUUID(),'Content-Type':'application/json'},body:JSON.stringify(body)});check('正确码也被共享限额阻断并返回等待秒数',limited.status===429&&Number(limited.headers.get('retry-after'))>=1&&Number(limited.headers.get('retry-after'))<=600)
 sql(`UPDATE auth_rate_limit SET window_start=DATE_SUB(window_start,INTERVAL 10 MINUTE) WHERE scope='redeem_code' AND key_hash='${createHash('sha256').update(shopId+':'+order).digest('hex')}';`)
 if(process.argv.includes('--prepare-ui')){fs.writeFileSync('.cache/a7-ui-sessions.json',JSON.stringify(s),{mode:0o600});keep=true;check('H5待核销合成会话已准备，凭据不打印',true);return}
 const key=randomUUID(),completed=await api('POST',path,shop,{key,body}),receipt=expect('真实核销完成',completed)
 check('核销结果no-store且明确测试模式',completed.noStore&&receipt.order_status==='COMPLETED'&&receipt.test_mode===true&&receipt.changed===true&&!!receipt.redeemed_at)
 const replay=expect('原键重试成功',await api('POST',path,shop,{key,body}));check('原键结果一致',deepEqual(receipt,replay))
 const repeat=expect('新键返回已有核销',await api('POST',path,shop,{key:randomUUID(),body}));check('新键没有再次核销',repeat.changed===false)
 const final=expect('车主读取已完成订单',await api('GET',`/api/order/${order}`,owner));check('完成后不展示有效码',final.status==='COMPLETED'&&!('appointment_code' in final))
 const own=expect('车主读取本人核销记录',await api('GET',`/api/order/${order}/redemption`,owner));check('本人核销记录有时间与测试标识',own.redemption.test_mode===true&&!!own.redemption.redeemed_at)
 expect('他店不可读核销结果',await api('GET',`/api/merchant/orders/${order}/redemption`,s.shopB.token),404,40400)
 check('订单唯一核销与双审计唯一',sql(`SELECT (SELECT COUNT(*) FROM order_redemption WHERE order_id=${order}),(SELECT COUNT(*) FROM order_status_transition WHERE order_id=${order} AND action='ORDER_COMPLETE'),(SELECT COUNT(*) FROM audit_log WHERE resource_type='order' AND resource_id=${order} AND action='ORDER_COMPLETE');`)==='1\t1\t1')
 const privacy=sql(`SELECT COUNT(*) FROM idempotency_record WHERE request_path='${path}' AND (response_body LIKE '%246813%' OR response_body REGEXP '"(code_digest|verify_code|staff_id|channel_payment_no|user_id)"');`);check('成功缓存不泄露码与内部身份凭据',privacy==='0')
 sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id='${s.shopA.jti}';`);expect('撤销会话不能重放已完成核销',await api('POST',path,shop,{key,body}),401,40100)
}
main().catch(e=>{check('脚本执行',false,e.message);process.exitCode=1}).finally(()=>{
 if(!keep&&sessions.length)sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id IN (${sessions.map(s=>"'"+s+"'").join(',')});`)
 fs.mkdirSync('test-results',{recursive:true});const report={stage:'A7.1',syntheticLogin:true,realMoney:false,results,passed:results.filter(r=>r.ok).length,total:results.length};fs.writeFileSync(process.argv.includes('--prepare-ui')?'test-results/local-redemption-ui-prepare.json':'test-results/local-redemption-e2e.json',JSON.stringify(report,null,2));console.log(`A7.1 real HTTP ${report.passed}/${report.total}; credentials withheld`);if(report.passed!==report.total)process.exitCode=1
})
