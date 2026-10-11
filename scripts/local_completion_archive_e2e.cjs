// Explicit opt-in, synthetic identities and evidence metadata, real HTTP/MySQL.
// Does not test real uploads, login, funds or device image rendering.
const fs=require('node:fs'), {createHash,randomUUID}=require('node:crypto'), {isDeepStrictEqual}=require('node:util')
const f=require('./local_service_work_fixtures.cjs'), {sql,docker,check}=f
const C='vehicle-r2a-archive-backend', ORIGIN='http://127.0.0.1:18082'
const O=9210201,U=9210202,M=9210203,S=9210204,T=9210205,V=9210206,R=9210207,P=9210208,K=9210209,PRO=9210210,A=9210211,OTHER=9210212
const FILES=Array.from({length:11},(_,i)=>9210300+i)
let validated=false, workerAttempts=0
async function call(method,path,token,body,key){const headers={};if(token)headers.Authorization='Bearer '+token;if(body)headers['Content-Type']='application/json';if(key)headers['Idempotency-Key']=key
 const response=await fetch(ORIGIN+path,{method,headers,body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(15000)});return {status:response.status,body:await response.json()}}
function expect(name,r,status=200){check(name,r.status===status,'HTTP='+r.status);if(r.status!==status)throw Error(name+' failed');return r.body.data}
// Cover the production consumer's 60-second retry delay, including a cold connection failure.
async function waitFor(query){const until=Date.now()+120000;while(Date.now()<until){if(query())return;await new Promise(r=>setTimeout(r,1000))}throw Error('Archive worker timeout: '+sql(`SELECT status,attempts,last_error,next_attempt_at,UTC_TIMESTAMP(),CURRENT_TIMESTAMP() FROM service_archive_job WHERE order_id=${O}`))}
function cleanup(){
 const archives=sql(`SELECT archive_id FROM service_archive_job WHERE order_id=${O} AND archive_id IS NOT NULL`).split(/\s+/).filter(Boolean)
 const cards=sql(`SELECT id FROM experience_card WHERE order_id=${O}`).split(/\s+/).filter(Boolean)
 for(const id of archives){sql(`DELETE FROM vehicle_archive_file WHERE archive_id=${id}; DELETE FROM vehicle_archive WHERE id=${id} AND vehicle_id=${V}; DELETE FROM audit_log WHERE actor_type='system' AND action='SERVICE_ARCHIVE_CREATE' AND resource_type='vehicle_archive' AND resource_id=${id}`)}
 for(const id of cards)sql(`DELETE FROM audit_log WHERE actor_type='system' AND action='EXPERIENCE_CARD_CREATE' AND resource_type='experience_card' AND resource_id=${id}`)
 sql(`DELETE FROM experience_card WHERE order_id=${O}; DELETE FROM service_archive_job WHERE order_id=${O}; DELETE rf FROM order_review_file rf JOIN order_review r ON r.id=rf.review_id WHERE r.order_id=${O}; DELETE FROM order_review WHERE order_id=${O};
 DELETE FROM order_redemption WHERE order_id=${O}; DELETE FROM order_status_transition WHERE order_id=${O}; DELETE FROM payment_event WHERE payment_id=${P}; DELETE FROM payment WHERE id=${P};
 DELETE FROM service_evidence_file WHERE order_id=${O}; DELETE FROM service_report_submission WHERE order_id=${O}; DELETE FROM technician_report WHERE id=${R}; DELETE FROM repair_protection WHERE id=${PRO}; DELETE FROM technician_assignment WHERE id=${A};
 DELETE FROM pickup_check_file WHERE pickup_check_id=${K}; DELETE FROM pickup_check WHERE id=${K}; DELETE FROM file_object WHERE id IN (${FILES.join(',')}) AND object_key LIKE 'synthetic-r2a-%';
 DELETE FROM \`order\` WHERE id=${O} AND order_no='synthetic-r2a-completion'; DELETE FROM vehicle WHERE id=${V} AND user_id=${U};
 DELETE FROM idempotency_record WHERE (actor_type='user' AND actor_id=${U}) OR (actor_type='staff_account' AND actor_id=${S});
 DELETE FROM audit_log WHERE (actor_type='user' AND actor_id=${U}) OR (actor_type='staff_account' AND actor_id=${S});
 DELETE FROM auth_session WHERE (subject_type='user' AND subject_id IN (${U},${OTHER})) OR (subject_type='staff_account' AND subject_id=${S});
 DELETE FROM auth_rate_limit WHERE scope='redeem_code' AND key_hash='${f.sha256(M+':'+O)}';
 DELETE FROM staff_account WHERE id IN (${S},${T}) AND account LIKE 'synthetic-r2a-%'; DELETE FROM merchant WHERE id=${M} AND name='合成R2a验收门店'; DELETE FROM user WHERE id IN (${U},${OTHER}) AND openid LIKE 'synthetic-r2a-%';`)
}
async function main(){
 f.verifyIsolation('r1b-staff'); const inspected=JSON.parse(docker(['inspect',C]))[0], binding=inspected.HostConfig.PortBindings['8080/tcp']?.[0]
 if(inspected.Config.Image!=='vehicle-auth/backend:r2a-archive'||binding?.HostIp!=='127.0.0.1'||binding?.HostPort!=='18082'||!inspected.NetworkSettings.Networks['vehicle-auth-local_default'])throw Error('Only exact isolated R2a sidecar allowed')
 if(f.envVar(C,'SERVICE_ARCHIVE_ENABLED')!=='true'||f.envVar(C,'EXPERIENCE_CARD_ENABLED')!=='true')throw Error('Expected isolated archive/card switches')
 if(docker(['exec',C,'sha256sum','/app/app.jar']).split(/\s/)[0]!==createHash('sha256').update(fs.readFileSync('backend/target/platform-backend-0.1.0-SNAPSHOT.jar')).digest('hex'))throw Error('Runtime JAR mismatch')
 check('隔离镜像端口网络与JAR一致',true)
 const collisions=[['order',[O]],['user',[U,OTHER]],['merchant',[M]],['staff_account',[S,T]],['vehicle',[V]],['technician_report',[R]],['payment',[P]],['pickup_check',[K]],['repair_protection',[PRO]],['technician_assignment',[A]],['file_object',FILES]]
 for(const [table,ids] of collisions)if(sql(`SELECT COUNT(*) FROM \`${table}\` WHERE id IN (${ids.join(',')})`)!=='0')throw Error('Synthetic collision; refusing writes')
 validated=true
 try{
 sql(`INSERT INTO user(id,openid,status) VALUES(${U},'synthetic-r2a-owner',1),(${OTHER},'synthetic-r2a-other',1);
 INSERT INTO merchant(id,merchant_type,name,address,status) VALUES(${M},2,'合成R2a验收门店','合成验收地址',1);
 INSERT INTO staff_account(id,merchant_id,role,account,status) VALUES(${S},${M},'MERCHANT','synthetic-r2a-manager','ACTIVE'),(${T},${M},'TECHNICIAN','synthetic-r2a-technician','ACTIVE');
 INSERT INTO vehicle(id,user_id,current_mileage) VALUES(${V},${U},10);
 INSERT INTO \`order\`(id,order_no,user_id,vehicle_id,merchant_id,amount,pay_amount,status,verify_code,check_in_completed_at,owner_confirmed_at,assigned_at,service_report_ready_at) VALUES(${O},'synthetic-r2a-completion',${U},${V},${M},10,10,'PENDING_VERIFY','123456',UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP(),UTC_TIMESTAMP());
 INSERT INTO pickup_check(id,order_id,merchant_id,staff_id,owner_confirm,confirm_at) VALUES(${K},${O},${M},${S},1,UTC_TIMESTAMP());
 INSERT INTO technician_assignment(id,order_id,merchant_id,technician_id,assigned_by,status,accepted_at) VALUES(${A},${O},${M},${T},${S},'ACCEPTED',UTC_TIMESTAMP());
 INSERT INTO repair_protection(id,order_id,items,uploaded_at) VALUES(${PRO},${O},'["SEAT_COVER","STEERING_COVER"]',UTC_TIMESTAMP());
 INSERT INTO technician_report(id,order_id,technician_id,process_photos,fault_part_photos,finish_photos,repair_plan,fault_analysis,parts_used,work_hours,status,signed_at) VALUES(${R},${O},${T},'[${FILES[8]}]','[]','[${FILES[9]}]','合成检查与清洁','合成未发现故障件','[]',40,1,UTC_TIMESTAMP());
 INSERT INTO service_report_submission(order_id,report_id,no_parts,no_fault_parts,submitted_at) VALUES(${O},${R},1,1,UTC_TIMESTAMP());
 INSERT INTO payment(id,order_id,channel,payment_no,currency,amount,status,paid_at,channel_payment_no) VALUES(${P},${O},'LOCAL_TEST','synthetic-r2a-payment','CNY',10,'SUCCEEDED',UTC_TIMESTAMP(),'synthetic-r2a-paid');
 INSERT INTO payment_event(channel,event_id,payment_id,event_hash,outcome) VALUES('LOCAL_TEST','synthetic-r2a-event',${P},'${'a'.repeat(64)}','PAID');`)
 for(let i=0;i<FILES.length;i++)sql(`INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(${FILES[i]},'staff_account',${i<8?S:T},'synthetic-r2a-file-${i}','image/png',100,'CLEAN')`)
 for(const [i,photo] of ['FRONT','REAR','LEFT','RIGHT','ROOF','DASHBOARD','INTERIOR'].entries())sql(`INSERT INTO pickup_check_file(pickup_check_id,file_id,photo_slot) VALUES(${K},${FILES[i]},'${photo}')`)
 for(const [i,kind] of ['PROTECTION','PROCESS','FINISH','SIGNATURE'].entries())sql(`INSERT INTO service_evidence_file(order_id,record_id,file_id,kind) VALUES(${O},${i===0?PRO:R},${FILES[7+i]},'${kind}')`)
 const secret=f.envVar(C,'JWT_SECRET'),app=f.envVar(C,'WECHAT_APP_ID')
 const owner=f.mint(secret,app,{type:'user',role:'OWNER',subject:U}),other=f.mint(secret,app,{type:'user',role:'OWNER',subject:OTHER}),shop=f.mint(secret,app,{type:'staff_account',role:'MERCHANT',subject:S,merchantId:M,appId:'merchant-account'})
 const path=`/api/merchant/orders/${O}/redeem`,key=randomUUID()
 expect('匿名不能完成订单',await call('POST',path,null,{code:'123456'},key),401)
 expect('车主不能冒充门店核销',await call('POST',path,owner.token,{code:'123456'},key),403)
 const completed=expect('真实HTTP核销完成',await call('POST',path,shop.token,{code:'123456'},key));check('完成响应保留测试标注',completed.order_status==='COMPLETED'&&completed.test_mode===true)
 await waitFor(()=>sql(`SELECT COUNT(*) FROM service_archive_job WHERE order_id=${O} AND status='DONE'`)==='1')
 workerAttempts=Number(sql(`SELECT attempts FROM service_archive_job WHERE order_id=${O}`))
 check('未评价即归档',sql(`SELECT COUNT(*) FROM order_review WHERE order_id=${O}`)==='0')
 check('未评价不生成经验草稿',sql(`SELECT COUNT(*) FROM experience_card WHERE order_id=${O}`)==='0')
 const row=expect('本人档案真实HTTP读取',await call('GET',`/api/archive/list?vehicle_id=${V}`,owner.token)).list[0]
 check('来源评价明确null',row.source.review_id===null&&row.source.order_id===O)
 check('实际工时与非伪造里程',row.work_minutes===40&&row.mileage===null&&row.test_mode===true)
 expect('其他车主不能读取档案',await call('GET',`/api/archive/list?vehicle_id=${V}`,other.token),404)
 expect('门店不能读取车主全部档案',await call('GET',`/api/archive/list?vehicle_id=${V}`,shop.token),403)
 const before=sql(`SELECT payload FROM service_archive_job WHERE order_id=${O}`)
 expect('同键完成重放',await call('POST',path,shop.token,{code:'123456'},key));expect('新键完成重放',await call('POST',path,shop.token,{code:'123456'},randomUUID()))
 expect('归档后本人评价',await call('POST','/api/order/review',owner.token,{order_id:O,rating:5,content:'合成晚评价记录',photo_file_ids:[]},randomUUID()))
 await waitFor(()=>sql(`SELECT COUNT(*) FROM experience_card WHERE order_id=${O}`)==='1')
 check('晚评价未改冻结快照',isDeepStrictEqual(JSON.parse(before),JSON.parse(sql(`SELECT payload FROM service_archive_job WHERE order_id=${O}`))))
 check('单任务单档案',sql(`SELECT COUNT(*) FROM service_archive_job WHERE order_id=${O}`)==='1'&&sql(`SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=${V}`)==='1')
 check('晚评价草稿仍私有',sql(`SELECT status FROM experience_card WHERE order_id=${O}`)==='DRAFT')
 const card=Number(sql(`SELECT id FROM experience_card WHERE order_id=${O}`))
 expect('测试草稿不可授权公开',await call('POST',`/api/experience-cards/${card}/consent`,owner.token,{agree:true,consent_version:'experience-v1'},randomUUID()),409)
 check('完成及归档审计唯一',sql(`SELECT COUNT(*) FROM audit_log WHERE action='ORDER_COMPLETE' AND resource_id=${O}`)==='1'&&sql(`SELECT COUNT(*) FROM audit_log WHERE action='SERVICE_ARCHIVE_CREATE' AND resource_id=${row.archive_id}`)==='1')
 } finally { if(validated)cleanup() }
 check('合成业务及身份零残留',sql(`SELECT (SELECT COUNT(*) FROM \`order\` WHERE id=${O})+(SELECT COUNT(*) FROM vehicle_archive WHERE vehicle_id=${V})+(SELECT COUNT(*) FROM service_archive_job WHERE order_id=${O})+(SELECT COUNT(*) FROM experience_card WHERE order_id=${O})+(SELECT COUNT(*) FROM user WHERE id IN (${U},${OTHER}))+(SELECT COUNT(*) FROM merchant WHERE id=${M})+(SELECT COUNT(*) FROM staff_account WHERE id IN (${S},${T}))`)==='0')
 fs.writeFileSync('.cache/r2a-http-acceptance.json',JSON.stringify({checks:f.results,workerAttempts,formalIdentityAccepted:false,realFundsAccepted:false,realUploadsAccepted:false},null,2))
 if(f.results.some(r=>!r.ok))throw Error('R2a acceptance contains failures')
}
main().catch(e=>{console.error(e.message);process.exitCode=1})
