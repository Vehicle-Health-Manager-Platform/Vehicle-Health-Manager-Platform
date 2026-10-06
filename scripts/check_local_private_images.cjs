// Opt-in integration against the isolated local database and real services.
// WeChat codes, tokens, object keys and signed URLs never enter output.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const https = require('node:https')
const { spawnSync } = require('node:child_process')
const { randomUUID } = require('node:crypto')
if (!process.argv.includes('--allow-local-image-test-writes')) {
  console.error('Requires --allow-local-image-test-writes; isolated local services only')
  process.exit(2)
}
const backend='http://127.0.0.1:18080', mysql='vehicle-auth-local-mysql-1', scanner='vehicle-auth-local-clamav'
const image=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aSkcAAAAASUVORK5CYII=','base64')
const checks=[]
function docker(args,input) {
  const r=spawnSync('docker',args,{input,encoding:'utf8',windowsHide:true,timeout:20000})
  if(r.status!==0) throw new Error('Local Docker operation failed')
  return r.stdout.trim()
}
const sql=q=>docker(['exec','-i',mysql,'sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --default-character-set=utf8mb4 -u"$MYSQL_USER" "$MYSQL_DATABASE" --batch --skip-column-names'],q)
function check(ok,label){assert.ok(ok,label);checks.push(label);console.log('PASS '+label)}
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms))
let token='', ws, scannerStopped=false, stage='setup'
async function request(path,method='GET',body,key) {
  const r=await fetch(backend+path,{method,headers:{...(token?{Authorization:'Bearer '+token}:{}),...(body?{'Content-Type':'application/json'}:{}),...(key?{'Idempotency-Key':key}:{})},body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(35000)})
  return {status:r.status,body:await r.json()}
}
async function upload(bytes,key,name='local-fixture.png') {
  const form=new FormData();form.append('file',new Blob([bytes],{type:'image/png'}),name)
  const r=await fetch(backend+'/api/file/upload',{method:'POST',headers:{Authorization:'Bearer '+token,'Idempotency-Key':key},body:form,signal:AbortSignal.timeout(40000)})
  return {status:r.status,body:await r.json()}
}
function download(url) {
  const target=new URL(url)
  if(target.origin!=='https://127.0.0.1:9443') throw new Error('Loopback TLS origin required')
  return new Promise((resolve,reject)=>{
    const req=https.get(target,{ca:fs.readFileSync('test-results/local-upload-cert.pem')},r=>{
      const chunks=[];r.on('data',x=>chunks.push(x));r.on('end',()=>resolve({status:r.statusCode,bytes:Buffer.concat(chunks)}))
    });req.setTimeout(10000,()=>req.destroy(new Error('Local TLS timeout')));req.on('error',reject)
  })
}
async function login() {
  ws=new WebSocket('ws://127.0.0.1:9420')
  await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('DevTools unavailable')),15000);ws.onopen=()=>{clearTimeout(timer);resolve()};ws.onerror=()=>{clearTimeout(timer);reject(new Error('DevTools unavailable'))}})
  const wx=await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(new Error('WeChat code timeout')),15000);ws.onmessage=e=>{const m=JSON.parse(e.data);if(m.id!=='1')return;clearTimeout(timer);m.error?reject(new Error('WeChat code failed')):resolve(m.result)};ws.send(JSON.stringify({id:'1',method:'App.callWxMethod',params:{method:'login',args:[{provider:'weixin'}]}}))})
  const code=wx?.code??wx?.result?.code??wx?.data?.code
  if(typeof code!=='string'||!code)throw new Error('Missing WeChat code')
  const r=await request('/api/auth/wx-login','POST',{code,role:'owner'})
  check(r.status===200&&r.body.code===0,'真实微信车主会话')
  token=r.body.data.access_token
}
async function main() {
  for(const name of [mysql,scanner,'vehicle-auth-local-minio']) {
    if(docker(['inspect',name,'--format','{{index .Config.Labels "com.docker.compose.project"}}'])!=='vehicle-auth-local')throw new Error('Refusing non-test services')
  }
  check(docker(['exec',scanner,'clamdscan','--config-file=/etc/clamav/upload-clamd.conf','--ping=1']).includes('PONG'),'真实官方库扫描器可达')
  await login();stage='upload'
  const key=randomUUID(), first=await upload(image,key)
  check(first.status===200&&first.body.code===0,'真实图片上传成功')
  const fileId=first.body.data.file_id
  const vehicleId=Number(sql(`SELECT id FROM vehicle WHERE model_id IN (9100601,9100602) AND user_id=(SELECT owner_id FROM file_object WHERE id=${fileId}) AND is_deleted=0 ORDER BY id DESC LIMIT 1;`))
  if(!Number.isSafeInteger(vehicleId)||vehicleId<=0)throw new Error('Create an owned synthetic local vehicle first')
  check(sql(`SELECT scan_status FROM file_object WHERE id=${fileId};`)==='CLEAN','官方新鲜病毒库放行且元数据CLEAN')
  const replay=await upload(image,key,'renamed-fixture.png')
  check(replay.status===200&&replay.body.data.file_id===fileId,'原图同键改名重放同一文件')
  check((await upload(Buffer.concat([image,Buffer.from('different')]),key)).status===409,'同键异字节拒绝')
  check(sql(`SELECT COUNT(*) FROM audit_log WHERE resource_type='file_object' AND resource_id=${fileId} AND action='FILE_UPLOAD';`)==='1','重放不重复成功审计')
  stage='signed access'
  const access=await request(`/api/file/${fileId}/access`), signed=access.body.data
  check(access.status===200&&signed.url.startsWith('https://127.0.0.1:9443/'),'真实短时HTTPS签名')
  const content=await download(signed.url)
  check(content.status===200&&content.bytes.equals(image),'HTTPS签名下载原字节')
  const anonymous=new URL(signed.url);anonymous.search=''
  check((await download(anonymous.href)).status===403,'匿名对象下载拒绝')
  const bucket=new URL(signed.url);bucket.pathname='/'+bucket.pathname.split('/')[1];bucket.search='?list-type=2'
  check((await download(bucket.href)).status===403,'匿名列桶拒绝')
  const wait=Date.parse(signed.expires_at)-Date.now()+1500
  if(wait>10000||wait<0)throw new Error('Set local signed URL lifetime to five seconds')
  await pause(wait)
  check((await download(signed.url)).status===403,'已签URL到期拒绝')
  const fresh=await request(`/api/file/${fileId}/access`)
  check(fresh.status===200&&(await download(fresh.body.data.url)).bytes.equals(image),'重新获取签名可下载')
  stage='archive links'
  const base={vehicle_id:vehicleId,archive_type:1,recorded_date:'2026-10-06',title:'本地真实图片归档 '+Date.now(),file_ids:[fileId]}
  const photo=await request('/api/archive/add','POST',{...base,input_type:1},randomUUID())
  check(photo.status===200&&photo.body.code===0,'拍照方式关联真实CLEAN图片保存')
  const manual=await request('/api/archive/add','POST',{...base,title:base.title+' 手动',input_type:3},randomUUID())
  check(manual.status===200&&manual.body.code===0,'手动方式关联真实图片兼容')
  check((await request('/api/archive/add','POST',{...base,input_type:1,file_ids:[]},randomUUID())).status===400,'拍照方式无图拒绝')
  const rows=(await request(`/api/archive/list?vehicle_id=${vehicleId}&page=1&page_size=100`)).body.data.list
  check([photo,manual].every(r=>rows.some(a=>a.archive_id===r.body.data.archive_id&&a.file_ids.includes(fileId)&&[1,3].includes(a.input_type))),'真实混合来源列表和图片引用')
  const ids=[photo.body.data.archive_id,manual.body.data.archive_id].join(',')
  check(sql(`SELECT (SELECT COUNT(*) FROM vehicle_archive_file WHERE archive_id IN (${ids}) AND file_id=${fileId}),(SELECT COUNT(*) FROM audit_log WHERE resource_type='vehicle_archive' AND resource_id IN (${ids}));`)==='2\t2','图片关联与成功审计一致')
  // Explicitly synthetic metadata exercises authorization before object access.
  const foreignKey='uploads/00000000-0000-4000-8000-000000008001'
  if(sql(`SELECT COUNT(*) FROM file_object WHERE id=9100801 AND (owner_id<>9100690 OR object_key<>'${foreignKey}');`)!=='0')throw new Error('Synthetic file ID collision')
  sql(`INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) VALUES(9100801,'user',9100690,'${foreignKey}','image/png',68,'CLEAN') ON DUPLICATE KEY UPDATE id=id;`)
  check((await request('/api/file/9100801/access')).status===404,'合成他人图片访问拒绝')
  check((await request('/api/archive/add','POST',{...base,file_ids:[9100801]},randomUUID())).status===404,'合成他人图片归档拒绝')
  const pendingKey='uploads/00000000-0000-4000-8000-000000009001'
  if(sql(`SELECT COUNT(*) FROM file_object WHERE id=9100901 AND (scan_status<>'PENDING' OR object_key<>'${pendingKey}' OR owner_id<>(SELECT owner_id FROM file_object WHERE id=${fileId}));`)!=='0')throw new Error('Synthetic pending ID collision')
  if(sql('SELECT COUNT(*) FROM file_object WHERE id=9100901;')==='0') {
    sql(`INSERT INTO file_object(id,owner_type,owner_id,object_key,content_type,size_bytes,scan_status) SELECT 9100901,'user',owner_id,'${pendingKey}','image/png',68,'PENDING' FROM file_object WHERE id=${fileId};`)
  }
  check((await request('/api/file/9100901/access')).status===409,'本人合成PENDING图片不可预览')
  check((await request('/api/archive/add','POST',{...base,file_ids:[9100901]},randomUUID())).status===404,'本人合成PENDING图片不可归档')
  stage='scan failure retry'
  docker(['stop',scanner]);scannerStopped=true
  const retryKey=randomUUID(), failed=await upload(image,retryKey)
  check(failed.status===503,'扫描器故障拒绝上传')
  check(sql(`SELECT COUNT(*) FROM file_object f JOIN upload_request r ON f.object_key=r.object_key WHERE r.idempotency_key='${retryKey}';`)==='0','扫描故障不留成功文件元数据')
  docker(['start',scanner]);scannerStopped=false
  for(let i=0;i<30;i++){try{if(docker(['exec',scanner,'clamdscan','--config-file=/etc/clamav/upload-clamd.conf','--ping=1']).includes('PONG'))break}catch{}await pause(1000)}
  const recovered=await upload(image,retryKey)
  check(recovered.status===200&&recovered.body.code===0,'扫描恢复后原图原键重试成功')
  check(sql(`SELECT COUNT(*) FROM audit_log WHERE resource_type='file_object' AND resource_id=${recovered.body.data.file_id} AND action='FILE_UPLOAD';`)==='1','恢复上传只有一条成功审计')
  console.log(JSON.stringify({passed:checks.length,checks,fileIds:[fileId,recovered.body.data.file_id],archiveIds:[photo.body.data.archive_id,manual.body.data.archive_id],boundary:'Real backend/storage/official database; photo source is API contract only; actual camera and second real identity pending'}))
}
main().catch(()=>{console.error('Local image acceptance failed at '+stage+'; sensitive details withheld');process.exitCode=1}).finally(async()=>{if(scannerStopped){try{docker(['start',scanner])}catch{console.error('Restart isolated scanner before continuing')}}if(token){try{await request('/api/auth/logout','POST')}catch{}}ws?.close()})
