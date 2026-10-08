// Opt-in A6 acceptance: real HTTP, MySQL, private storage and ClamAV.
// Synthetic login sessions are a test bridge, not real WeChat/device acceptance.
const fs=require('node:fs'),https=require('node:https'),{createHash}=require('node:crypto')
const f=require('./local_service_work_fixtures.cjs')
const {sql,api,check,dataOf,codeOf,results,deepEqual,docker,randomUUID}=f
const ORIGIN='http://127.0.0.1:18080',order=f.ORDER_POSITIVE
let sessions=[]
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aSkcAAAAASUVORK5CYII=','base64')
const safe=value=>!/("(?:user_id|vehicle_id|merchant_id|staff_id|technician_id|actor_id|openid|binding_id|access_token|refresh_token|verify_code|phone)"\s*:|https?:\/\/)/.test(JSON.stringify(value))
function expect(name,r,status,code){check(name,r.status===status&&(code===undefined||codeOf(r)===code),`HTTP=${r.status}, code=${codeOf(r)}`);return r}
async function upload(token,role,bytes=png,key=randomUUID()){
 const form=new FormData();form.append('file',new Blob([bytes],{type:'image/png'}),'synthetic-a6.png')
 const r=await fetch(ORIGIN+`/api/${role}/files/upload`,{method:'POST',headers:{Authorization:'Bearer '+token,'Idempotency-Key':key},body:form,redirect:'manual',signal:AbortSignal.timeout(85000)})
 return {status:r.status,noStore:r.headers.get('cache-control')==='no-store',json:await r.json()}
}
function download(value){
 const url=new URL(value)
 if(url.origin!=='https://127.0.0.1:9443')throw Error('Only isolated loopback TLS endpoint is supported')
 return new Promise((resolve,reject)=>{const req=https.get(url,{ca:fs.readFileSync('test-results/local-upload-cert.pem')},r=>{const chunks=[];r.on('data',c=>chunks.push(c));r.on('end',()=>resolve({status:r.statusCode,bytes:Buffer.concat(chunks)}));r.on('error',()=>reject(Error('Local signed download failed')))});req.setTimeout(10000,()=>req.destroy());req.on('error',()=>reject(Error('Local certificate/download unavailable; never disable verification')))})
}
async function main(){
 const image=f.verifyIsolation();check('隔离 A6 镜像 / V014 / 54 表',image.startsWith('vehicle-auth/backend:a6-service'))
 const hash=createHash('sha256').update(fs.readFileSync('backend/target/platform-backend-0.1.0-SNAPSHOT.jar')).digest('hex')
 const matches=docker(['exec','vehicle-auth-local-backend','sha256sum','/app/app.jar']).split(/\s/)[0]===hash
 check('运行容器 JAR 与本地已编译 JAR 一致',matches);if(!matches)throw Error('Backend JAR mismatch; refusing fixture writes')
 const health=await fetch(ORIGIN+'/actuator/health').then(r=>r.json());check('真实后端健康',health.status==='UP')
 const s=await f.setup(),tech=s.techA.token,shop=s.shopA.token
 sessions=Object.values(s).filter(v=>v?.jti).map(v=>v.jti)
 check('真实 A4 确认 → A5 派工 → 本人接单',sql(`SELECT status FROM \`order\` WHERE id=${order};`)==='IN_SERVICE')
 const path=`/api/tech/orders/${order}/work`,shopPath=`/api/merchant/orders/${order}/work`
 expect('匿名施工查询 401',await api('GET',path),401)
 expect('商家不可查询技师施工接口 403',await api('GET',path,shop),403)
 expect('技师不可查询商家施工接口 403',await api('GET',shopPath,tech),403)
 expect('另一技师施工查询 404',await api('GET',path,s.techB.token),404)
 expect('他店施工查询 404',await api('GET',shopPath,s.shopB.token),404)
 const empty=await api('GET',path,tech);expect('本人最小施工查询 200',empty,200,0)
 check('施工读取 no-store / 最小投影 / 空证据',empty.noStore&&safe(dataOf(empty))&&dataOf(empty).protection===null&&dataOf(empty).report===null)
 // Every successful file is stored/scanned by the real adapters; no file metadata fixture.
 const protectionUpload=await upload(shop,'merchant');expect('商家 PNG 私有上传',protectionUpload,200,0)
 const protectFile=dataOf(protectionUpload).file_id
 const processUpload=await upload(tech,'tech');expect('本人施工 PNG 私有上传',processUpload,200,0)
 const processFile=dataOf(processUpload).file_id
 const faultUpload=await upload(tech,'tech');expect('本人故障件 PNG 私有上传',faultUpload,200,0)
 const faultFile=dataOf(faultUpload).file_id
 const finishUpload=await upload(tech,'tech');expect('本人完工 PNG 私有上传',finishUpload,200,0)
 const finishFile=dataOf(finishUpload).file_id
 const signatureUpload=await upload(tech,'tech');expect('本人签字 PNG 私有上传',signatureUpload,200,0)
 const signatureFile=dataOf(signatureUpload).file_id
 const otherUpload=await upload(s.techB.token,'tech');expect('另一技师私有 PNG 上传',otherUpload,200,0)
 const foreignFile=dataOf(otherUpload).file_id
 check('真实官方扫描放行、类型/大小/本人归属及 no-store',protectionUpload.noStore&&processUpload.noStore&&sql(`SELECT COUNT(*) FROM file_object WHERE id IN (${protectFile},${processFile},${faultFile},${finishFile},${signatureFile},${foreignFile}) AND scan_status='CLEAN' AND content_type='image/png' AND size_bytes=${png.length} AND owner_type='staff_account';`)==='6')
 const body={order_id:order,process_photos:[processFile],fault_part_photos:[faultFile],finish_photos:[finishFile],no_fault_parts:false,repair_plan:'合成验收：更换故障件并复核施工部位',fault_analysis:'合成验收：故障件磨损，须更换后质检',parts_used:[{name:'合成配件',model:'A6-FIXTURE',brand:'合成品牌',quantity:2}],no_parts:false,work_hours:45}
 const protection={order_id:order,items:['SEAT_COVER','STEERING_COVER'],photo_file_id:protectFile}
 expect('缺防护不能报工 43002',await api('POST','/api/tech/report/submit',tech,{key:randomUUID(),body}),409,43002)
 expect('商家不能报工 403',await api('POST','/api/tech/report/submit',shop,{key:randomUUID(),body}),403)
 expect('技师不能提交商家防护 403',await api('POST','/api/check/protection/upload',tech,{key:randomUUID(),body:protection}),403)
 expect('缺接车不能提交防护 43001',await api('POST','/api/check/protection/upload',shop,{key:randomUUID(),body:{...protection,order_id:f.ORDER_NO_CHECKIN}}),409,43001)
 expect('车主未确认不能提交防护 43003',await api('POST','/api/check/protection/upload',shop,{key:randomUUID(),body:{...protection,order_id:f.ORDER_NO_CONFIRM}}),409,43003)
 expect('防护缺必备项目 400',await api('POST','/api/check/protection/upload',shop,{key:randomUUID(),body:{...protection,items:['SEAT_COVER']}}),400)
 expect('防护未传幂等键 400',await api('POST','/api/check/protection/upload',shop,{body:protection}),400)
 expect('防护不能使用技师私有文件 422',await api('POST','/api/check/protection/upload',shop,{key:randomUUID(),body:{...protection,photo_file_id:processFile}}),422)
 const protectionKey=randomUUID(),protectedView=await api('POST','/api/check/protection/upload',shop,{key:protectionKey,body:protection});expect('真实施工防护成功',protectedView,200,0)
 check('防护响应 no-store / 仅安全证据 ID',protectedView.noStore&&safe(dataOf(protectedView))&&dataOf(protectedView).protection.photo_file_id===protectFile)
 const protectionReplay=await api('POST','/api/check/protection/upload',shop,{key:protectionKey,body:protection});check('防护原键重放响应相同',protectionReplay.status===200&&deepEqual(dataOf(protectionReplay),dataOf(protectedView)))
 expect('防护新键不可覆盖 40905',await api('POST','/api/check/protection/upload',shop,{key:randomUUID(),body:protection}),409,40905)
 expect('无完整报工不能签字 43005',await api('POST','/api/tech/sign',tech,{key:randomUUID(),body:{order_id:order,signature_file_id:signatureFile}}),409,43005)
 sql(`UPDATE file_object SET scan_status='PENDING' WHERE id=${protectFile};`)
 expect('缓存防护重放也复核扫描状态 43002',await api('POST','/api/check/protection/upload',shop,{key:protectionKey,body:protection}),409,43002)
 expect('失效防护阻断报工 43002',await api('POST','/api/tech/report/submit',tech,{key:randomUUID(),body}),409,43002)
 sql(`UPDATE file_object SET scan_status='CLEAN' WHERE id=${protectFile};`)
 expect('另一技师不能报工 404',await api('POST','/api/tech/report/submit',s.techB.token,{key:randomUUID(),body}),404)
 expect('报工不能使用另一技师图片 422',await api('POST','/api/tech/report/submit',tech,{key:randomUUID(),body:{...body,process_photos:[foreignFile]}}),422)
 expect('报工图片不可跨组复用 400',await api('POST','/api/tech/report/submit',tech,{key:randomUUID(),body:{...body,finish_photos:[processFile]}}),400)
 expect('未显式无故障声明不允许空列表 400',await api('POST','/api/tech/report/submit',tech,{key:randomUUID(),body:{...body,fault_part_photos:[]}}),400)
 expect('未显式无配件声明不允许空列表 400',await api('POST','/api/tech/report/submit',tech,{key:randomUUID(),body:{...body,parts_used:[]}}),400)
 expect('工时必须整数分钟 400',await api('POST','/api/tech/report/submit',tech,{key:randomUUID(),body:{...body,work_hours:1.5}}),400)
 const reportKey=randomUUID(),report=await api('POST','/api/tech/report/submit',tech,{key:reportKey,body});expect('完整报工真实提交成功',report,200,0)
 check('报工只读但未自动完工 / no-store / 最小投影',report.noStore&&safe(dataOf(report))&&dataOf(report).report.status==='SUBMITTED'&&dataOf(report).order_status==='IN_SERVICE'&&dataOf(report).report.signature_file_id===null)
 const reportReplay=await api('POST','/api/tech/report/submit',tech,{key:reportKey,body});check('报工原键响应相同',reportReplay.status===200&&deepEqual(dataOf(reportReplay),dataOf(report)))
 expect('报工新键不可覆盖 40905',await api('POST','/api/tech/report/submit',tech,{key:randomUUID(),body}),409,40905)
 expect('报工原键异体 400',await api('POST','/api/tech/report/submit',tech,{key:reportKey,body:{...body,work_hours:46}}),400)
 // Fault injection changes only this run's real file metadata / synthetic order.
 sql(`UPDATE file_object SET is_deleted=1 WHERE id=${finishFile};`)
 expect('失效完工图片阻断签字 43005',await api('POST','/api/tech/sign',tech,{key:randomUUID(),body:{order_id:order,signature_file_id:signatureFile}}),409,43005)
 sql(`UPDATE file_object SET is_deleted=0 WHERE id=${finishFile}; UPDATE \`order\` SET status='DISPUTED' WHERE id=${order};`)
 expect('未解决争议阻断缓存报工重放 43007',await api('POST','/api/tech/report/submit',tech,{key:reportKey,body}),409,43007)
 expect('未解决争议阻断本人签字 43007',await api('POST','/api/tech/sign',tech,{key:randomUUID(),body:{order_id:order,signature_file_id:signatureFile}}),409,43007)
 sql(`UPDATE \`order\` SET status='IN_SERVICE' WHERE id=${order};`)
 expect('已用施工证据不能当签名 422',await api('POST','/api/tech/sign',tech,{key:randomUUID(),body:{order_id:order,signature_file_id:processFile}}),422)
 expect('商家通用完工被停用 43005',await api('POST',`/api/merchant/orders/${order}/actions`,shop,{key:randomUUID(),body:{action:'FINISH_SERVICE'}}),409,43005)
 const signBody={order_id:order,signature_file_id:signatureFile},signKey=randomUUID(),signed=await api('POST','/api/tech/sign',tech,{key:signKey,body:signBody});expect('本人质检签字成功',signed,200,0)
 check('签字仅送待核销 / 时间齐全 / no-store / 最小投影',signed.noStore&&safe(dataOf(signed))&&dataOf(signed).order_status==='PENDING_VERIFY'&&dataOf(signed).report.status==='SIGNED'&&!!dataOf(signed).report.signed_at&&dataOf(signed).report.signature_file_id===signatureFile)
 const signReplay=await api('POST','/api/tech/sign',tech,{key:signKey,body:signBody});check('签字原键重放响应相同',signReplay.status===200&&deepEqual(dataOf(signReplay),dataOf(signed)))
 expect('新键不能重复签字 40905',await api('POST','/api/tech/sign',tech,{key:randomUUID(),body:signBody}),409,40905)
 const shopView=await api('GET',shopPath,shop);check('商家读取同一只读记录',shopView.status===200&&shopView.noStore&&deepEqual(dataOf(shopView),dataOf(signed)))
 const accessPath=path+`/files/${signatureFile}/access`,access=await api('GET',accessPath,tech)
 expect('本人按订单读取关联签名短期访问',access,200,0)
 const accessData=dataOf(access),ttl=(Date.parse(accessData.expires_at)-Date.now())/1000
 check('短期 HTTPS 签名 / 1–300 秒 / no-store',access.noStore&&accessData.url.startsWith('https://127.0.0.1:9443/')&&ttl>0&&ttl<=300)
 const downloaded=await download(accessData.url);check('显式信任本机证书下载真实原字节',downloaded.status===200&&downloaded.bytes.equals(png),`HTTP=${downloaded.status},bytes=${downloaded.bytes.length},error=${downloaded.bytes.toString().match(/<Code>([^<]+)<\/Code>/)?.[1]||'none'}`)
 const anonymous=new URL(accessData.url);anonymous.search='';const denied=await download(anonymous.href);check('无签名私有对象拒绝 403',denied.status===403,`HTTP=${denied.status},bytes=${denied.bytes.length}`)
 expect('另一技师无法访问关联签字 404',await api('GET',accessPath,s.techB.token),404)
 expect('非关联文件不可通过订单访问 404',await api('GET',path+`/files/${foreignFile}/access`,tech),404)
 const delegated=await api('GET',shopPath+`/files/${signatureFile}/access`,shop);check('本店商家可按订单读取本人技师证据',delegated.status===200&&delegated.noStore&&!!dataOf(delegated).url)
 const evidence=sql(`SELECT (SELECT COUNT(*) FROM repair_protection WHERE order_id=${order}), (SELECT COUNT(*) FROM service_report_submission WHERE order_id=${order}), (SELECT COUNT(*) FROM technician_report WHERE order_id=${order} AND status=1 AND signed_at IS NOT NULL), (SELECT COUNT(*) FROM service_evidence_file WHERE order_id=${order}), (SELECT COUNT(*) FROM order_status_transition WHERE order_id=${order} AND action='ORDER_SERVICE_FINISH'), (SELECT COUNT(*) FROM audit_log WHERE resource_type='technician_report' AND resource_id=(SELECT id FROM technician_report WHERE order_id=${order}) AND action='ORDER_SERVICE_FINISH'), (SELECT COUNT(*) FROM \`order\` WHERE id=${order} AND status='PENDING_VERIFY' AND service_report_ready_at IS NOT NULL);`)
 check('落库单防护/报工/签名、五图关联及迁移双审计唯一',evidence==='1\t1\t1\t5\t1\t1\t1',evidence)
 const privacy=sql(`SELECT COUNT(*) FROM idempotency_record WHERE actor_id IN (9206201,9206231) AND request_path IN ('/api/check/protection/upload','/api/tech/report/submit','/api/tech/sign') AND response_body REGEXP '"(user_id|vehicle_id|merchant_id|staff_id|technician_id|actor_id|openid|binding_id|access_token|verify_code)"';`)
 check('三类成功幂等缓存不含身份字段',privacy==='0')
 sql(`UPDATE staff_wechat_identity SET status='REVOKED',unbound_at=UTC_TIMESTAMP() WHERE id=${f.BIND_A};`)
 expect('解绑后旧签字缓存不再重放 401',await api('POST','/api/tech/sign',tech,{key:signKey,body:signBody}),401)
 sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id IN (${Object.values(s).filter(v=>v?.jti).map(v=>"'"+v.jti+"'").join(',')});`)
 expect('撤销合成会话后施工查询 401',await api('GET',path,tech),401)
 const passed=results.filter(r=>r.ok).length
 console.log(`\npassed: ${passed} / ${results.length}; synthetic sessions only; no real camera/WeChat/production acceptance`)
 if(passed!==results.length)process.exitCode=1
}
main().catch(()=>{console.error('A6 isolated acceptance failed after '+results.length+' checks; private details withheld');process.exitCode=1}).finally(()=>{if(sessions.length){try{sql(`UPDATE auth_session SET revoked_at=UTC_TIMESTAMP() WHERE id IN (${sessions.map(id=>"'"+id+"'").join(',')});`)}catch{console.error('Synthetic session cleanup failed; revoke only A6 fixture sessions');process.exitCode=1}}})
