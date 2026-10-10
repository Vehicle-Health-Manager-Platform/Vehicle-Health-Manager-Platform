// Isolated diagnostics: transport, decoder and visual preview are separate outcomes.
const fs=require('node:fs'),https=require('node:https'),assert=require('node:assert/strict'),{spawn}=require('node:child_process'),{X509Certificate}=require('node:crypto')
const f=require('./local_service_work_fixtures.cjs')
const {runNativeCompletion,cleanupNativeCompletion}=require('./native-completion-e2e.cjs')
const {tapButton}=require('../apps/miniapp/test/helpers/native-elements.cjs')
const previewKind=process.argv.find(arg=>arg.startsWith('--preview-kind='))?.split('=')[1]||'review'
assert.ok(['review','archive','signature'].includes(previewKind),'Invalid preview kind')
const report={diagnosticsComplete:false,acceptanceComplete:false,startedAt:new Date().toISOString(),checks:[],outcomes:[],scope:'isolated Devtools urlCheck=false; explicit certificate trust for Node downloads; not formal domain/TLS or device acceptance'}
let preparation,server
const pass=name=>{report.checks.push({name,passed:true});console.log('PASS '+name)}
const delay=ms=>new Promise(r=>setTimeout(r,ms))
function download(value,ca){
  const url=new URL(value);assert.equal(url.origin,'https://127.0.0.1:9443');assert.equal(url.username,'');assert.equal(url.password,'')
  return new Promise((resolve,reject)=>{
    const request=https.get(url,{...(ca?{ca}:{}),rejectUnauthorized:true},response=>{
      const parts=[];let size=0;response.on('data',chunk=>{size+=chunk.length;if(size>10*1024*1024)request.destroy();else parts.push(chunk)})
      response.on('end',()=>resolve({status:response.statusCode,bytes:Buffer.concat(parts)}));response.on('error',()=>reject(Error('Download interrupted')))
    });request.setTimeout(10000,()=>request.destroy());request.on('error',e=>reject(Object.assign(Error('Local TLS download failed'),{code:e.code})))
  })
}
async function startServer(){
  server=spawn(process.execPath,['scripts/local_upload_tls.cjs','--allow-local-upload-tls','--native-preview-certificate'],{windowsHide:true,stdio:['ignore','pipe','pipe']})
  await new Promise((resolve,reject)=>{
    const timeout=setTimeout(()=>reject(Error('Local TLS helper startup timeout')),10000)
    server.once('error',()=>{clearTimeout(timeout);reject(Error('Local TLS helper start failed'))})
    server.once('exit',()=>{clearTimeout(timeout);reject(Error('Local TLS helper exited before ready; port may be occupied'))})
    server.stdout.on('data',chunk=>{if(String(chunk).includes('TLS endpoint ready')){clearTimeout(timeout);resolve()}})
  })
}
async function main(){
  const ca=fs.readFileSync('test-results/native-preview-cert.pem'),cert=new X509Certificate(ca)
  assert.ok(Date.parse(cert.validFrom)<=Date.now()&&Date.parse(cert.validTo)>Date.now());assert.equal(cert.checkIP('127.0.0.1'),'127.0.0.1')
  report.certificate={fingerprint:cert.fingerprint256,validTo:cert.validTo,loopbackSan:true};await startServer();pass('owned loopback TLS helper and valid dedicated certificate')
  preparation=await runNativeCompletion()
  report.preparation={completionChecks:preparation.report.checks.length,workChecks:preparation.work.report.checks.length,completed:preparation.report.completed}
  const {work,photo,archive,record}=preparation,{fixture,client,actor,page}=work,{order,actors}=fixture
  const signature=Number(f.sql(`SELECT file_id FROM service_evidence_file WHERE order_id=${order} AND kind='SIGNATURE'`))
  const cases=[
    {name:'review',actor:'owner',target:`/api/file/${photo}/access`,route:`pages/order/review?id=${order}`,button:'查看评价图片 1'},
    {name:'archive',actor:'owner',target:`/api/archive/${archive}/files/${record.file_ids[0]}/access`,route:'pages/archive/index',button:'查看施工图片 1'},
    {name:'signature',actor:'tech',target:`/api/tech/orders/${order}/work/files/${signature}/access`,route:`pages/technician/work?id=${order}`,button:'查看本人质检签名'}
  ]
  for(const item of cases){
    const access=await f.api('GET',item.target,actors[item.actor].session.token);assert.equal(access.status,200);pass(item.name+' private authorization')
    const signed=f.dataOf(access)
    await assert.rejects(download(signed.url),e=>['DEPTH_ZERO_SELF_SIGNED_CERT','SELF_SIGNED_CERT_IN_CHAIN','UNABLE_TO_VERIFY_LEAF_SIGNATURE'].includes(e.code));pass(item.name+' untrusted certificate rejected by strict client')
    const actual=await download(signed.url,ca);assert.equal(actual.status,200);assert.ok(actual.bytes.length>0);pass(item.name+' real HTTPS download with explicit certificate trust')
    const native=(await client.rpc('App.callFunction',{functionDeclaration:`function(url){return new Promise(resolve=>wx.getImageInfo({src:url,success:r=>resolve({ok:true,width:r.width,height:r.height,type:r.type}),fail:()=>resolve({ok:false,reason:'getImageInfo failed'})}))}`,args:[signed.url]})).result
    report.outcomes.push({name:item.name+' native decoder',status:native.ok?'passed':'blocked',...native})
    if(item.name!==previewKind)continue
    await actor(item.actor);const p=await page(item.route);await tapButton(client.rpc,p,item.button);await delay(10000)
    const shot=await client.rpc('App.captureScreenshot');fs.writeFileSync(`test-results/native-preview-${item.name}.png`,Buffer.from(shot.data,'base64'))
    // Screenshot inspection is recorded separately; successful callback is not visual acceptance.
    report.outcomes.push({name:item.name+' page preview',status:'needs-visual-review',screenshot:`test-results/native-preview-${item.name}.png`})
    const close=(await client.rpc('App.callFunction',{functionDeclaration:`function(){if(typeof wx.closePreviewImage!=='function')return false;wx.closePreviewImage();return true}`,args:[]})).result
    if(!close)report.outcomes.push({name:'preview dismissal',status:'blocked',reason:'closePreviewImage unavailable; reopen isolated project before another visual case or modal probe'})
  }
  // A modal under an undismissed preview is inconclusive; run the independent probe below in a fresh project.
  report.outcomes.push({name:'actual native modal confirm',status:'needs-isolated-probe',reason:'must run native-modal-probe.cjs after reopening isolated project'})
  report.outcomes.push({name:'actual native picker click',status:'blocked',reason:'official local Native API has no file-selection command; physical device selection remains required'})
  report.diagnosticsComplete=true
}
main().catch(e=>{report.failure=e.message;console.error(e.message);process.exitCode=1}).finally(async()=>{
  if(preparation)try{await preparation.work.client.rpc('App.callFunction',{functionDeclaration:'function(){delete wx.__nativePreviewProbe;return true}',args:[]})}catch{}
  report.cleaned=await cleanupNativeCompletion()
  if(server){const exited=new Promise(resolve=>{if(server.exitCode!==null)resolve();else server.once('exit',resolve)});server.kill();await Promise.race([exited,delay(3000)]);report.serverStopped=server.exitCode!==null||server.signalCode!==null;if(!report.serverStopped)process.exitCode=1}
  report.previewKind=previewKind;report.finishedAt=new Date().toISOString();fs.writeFileSync(`test-results/wechat-native-preview-${previewKind}.json`,JSON.stringify(report,null,2));console.log(`Preview diagnostics ${report.diagnosticsComplete&&report.cleaned?'COMPLETE':'INCOMPLETE'}: ${report.checks.length} transport checks; visual/tool outcomes separate`)
})
