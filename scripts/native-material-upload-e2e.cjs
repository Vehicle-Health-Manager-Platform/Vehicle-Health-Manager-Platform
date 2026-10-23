// Real WeChat filesystem/uploadFile -> host-loopback byte proxy -> isolated JVM/MinIO/ClamAV.
const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto'),assert=require('node:assert/strict'),{spawn,execFileSync}=require('node:child_process')
const {createProxy}=require('./native-material-proxy.cjs')
const {connectDevtools,waitForPage}=require('../apps/miniapp/test/helpers/devtools-rpc.cjs')
const {waitElement,tapElement}=require('../apps/miniapp/test/helpers/native-elements.cjs')
const {createWxMockScope}=require('../apps/miniapp/test/helpers/devtools-mocks.cjs')
const root=path.resolve(__dirname,'..'),id=crypto.randomUUID().replaceAll('-',''),work=path.join(root,'.cache','native-material-'+id)
const kind=process.argv.includes('--image')?'image':'pdf',imageMode=kind==='image'
fs.mkdirSync(work,{recursive:true});console.log('Native material run: '+work)
const report={passed:false,kind,session_cleared:false,system_api_restored:false,local_file_released:false,checks:[],scope:`Devtools ${imageMode?'image':'PDF'} picker assisted; real WeChat file/uploadFile, isolated JVM/MySQL/MinIO/ClamAV with synthetic scan DB; no phone picker, camera, preview rendering, production scanner or recognition acceptance`}
const pass=name=>report.checks.push({name,passed:true}),delay=ms=>new Promise(r=>setTimeout(r,ms))
let client,mocks,local,proxy,backend,backendExit,log,backendFinished=false
function digest(){const hash=crypto.createHash('sha256');for(const name of ['scripts/native-material-upload-e2e.cjs','scripts/native-material-proxy.cjs','scripts/run_material_workbench_acceptance.py','apps/miniapp/src/components/LocalInputMaterials.vue','apps/miniapp/src/services/input-material-upload.js','apps/miniapp/src/services/private-images.js','apps/miniapp/src/services/archive-flow.js','apps/miniapp/src/pages/archive/record-add.vue','apps/miniapp/test/helpers/native-elements.cjs'])hash.update(fs.readFileSync(path.join(root,name)));return hash.digest('hex')}
const before=digest()
async function native(ready){
  assert.equal(ready.kind,kind)
  proxy=await createProxy({...ready,container:'autocare-material-native-'+id.slice(0,8),work,containerWork:'/workspace/.cache/native-material-'+id})
  client=await connectDevtools({timeoutMs:20000});const rpc=client.rpc;mocks=createWxMockScope(rpc)
  const downloaded=(await rpc('App.callWxMethod',{method:'downloadFile',args:[{url:'http://127.0.0.1:18080/__native-fixture.'+(imageMode?'png':'pdf'),header:{Authorization:'Bearer '+ready.token}}]})).result
  assert.equal(downloaded.statusCode,200);local=downloaded.tempFilePath
  await rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'owner',token:ready.token,vehicle:991}]})
  await rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/pages/archive/record-add?vehicle_id=991'}]})
  const p=await waitForPage(rpc,'pages/archive/record-add')
  const read=async fn=>{for(let i=0;i<3;i++)try{return await fn()}catch(e){if(i===2||!e.message.startsWith('Devtools RPC'))throw e;const current=await rpc('App.getCurrentPage');assert.equal(current.path,p.path);Object.assign(p,current)}}
  const text=fragment=>read(()=>waitElement(rpc,p,'text',e=>e.text?.includes(fragment)))
  const tap=async label=>{const button=await read(()=>waitElement(rpc,p,'button',e=>e.text?.trim()===label));await tapElement(rpc,p,button)}
  if(imageMode){
    await text('归档图片（0/5）')
    const size=Buffer.from(ready.image,'base64').length
    await mocks.mock('chooseMedia',{tempFiles:[{tempFilePath:local,size,fileType:'image'}],type:'image',errMsg:'chooseMedia:ok'})
    await mocks.mock('chooseImage',{tempFilePaths:[local],tempFiles:[{path:local,size}],errMsg:'chooseImage:ok'})
    try{await tap('选择并上传图片');await text('图片已上传，保存档案后才会归档')}finally{await mocks.restore('chooseMedia');await mocks.restore('chooseImage')}
    pass('assisted image picker selects real WeChat temporary PNG')
    await text('图片 1 已上传');pass('real uploadFile receives validated PNG receipt')
    await text('归档图片（1/5）');pass('native page holds exactly one uploaded image before archive save')
    await tap('移除');await text('归档图片（0/5）');pass('remove clears local reference')
  }else{
  await mocks.mock('chooseMessageFile',{tempFiles:[{path:local,name:'synthetic.pdf',size:Buffer.from(ready.pdf,'base64').length}],errMsg:'chooseMessageFile:ok'})
  try{await tap('选择PDF');await text('synthetic.pdf')}finally{await mocks.restore('chooseMessageFile')}
  pass('native picker assistance selects real local PDF')
  await tap('上传本份材料');await text('已上传 · 扫描通过 · 尚未识别');pass('real uploadFile receives validated CLEAN PDF receipt')
  await text('未发送给AI或写入档案');pass('native receipt preserves no-recognition/no-archive boundary')
  await tap('移除本机引用');await text('本机辅助材料（0/3）');pass('remove clears local reference')
  }
  await rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/pages/archive/record-add?vehicle_id=991'}]})
  let again;const deadline=Date.now()+10000
  do{again=await waitForPage(rpc,'pages/archive/record-add');if(again.pageId!==p.pageId)break;await delay(100)}while(Date.now()<deadline)
  assert.notEqual(again.pageId,p.pageId,'Reentry must create a fresh page')
  await waitElement(rpc,again,'text',e=>e.text?.includes(imageMode?'归档图片（0/5）':'本机辅助材料（0/3）'));pass('reentry has no retained local material')
  assert.equal(proxy.requests,1);report.upload_requests=proxy.requests
  report.passed=true
}
async function main(){
  assert.ok(process.argv.includes('--allow-isolated-upload'),'Explicit isolated upload flag required')
  log=fs.openSync(path.join(work,'backend.private.log'),'w')
  backend=spawn('python',['scripts/run_material_workbench_acceptance.py','--docker'],{cwd:root,env:{...process.env,AUTOCARE_NATIVE_MATERIAL_RUN_ID:id,AUTOCARE_NATIVE_UPLOAD_KIND:kind},windowsHide:true,stdio:['ignore',log,log]})
  backendExit=new Promise(resolve=>{backend.on('error',()=>{backendFinished=true;resolve(127)});backend.on('close',code=>{backendFinished=true;resolve(code)})})
  const readyFile=path.join(work,'ready.private.json'),deadline=Date.now()+20*60000
  while(!fs.existsSync(readyFile)){assert.ok(Date.now()<deadline,'Backend preparation timed out');assert.ok(!backendFinished,'Backend exited before handoff');await delay(250)}
  try{await native(JSON.parse(fs.readFileSync(readyFile,'utf8')))}catch(e){fs.writeFileSync(path.join(work,'native-error.private.log'),e.stack||e.name);report.passed=false;report.error_kind=e.name;report.error_code=e.message.startsWith('Devtools')?e.message:'native assertion or element wait failed'}
  finally{
    if(client){try{for(const method of mocks?.methods||[])await mocks.restore(method);report.system_api_restored=true}catch{report.passed=false}
      try{report.local_file_released=report.checks.some(c=>c.name==='remove clears local reference');report.local_file_cleanup='WeChat-managed temporary download; no saved user-directory file created'}catch{report.passed=false}
      try{await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'clear'}]});report.session_cleared=true}catch{report.passed=false}client.close()}
    if(proxy){report.upload_requests=proxy.requests;await proxy.close();proxy=undefined;report.proxy_closed=true}
    fs.writeFileSync(path.join(work,'native.json'),JSON.stringify(report,null,2)+'\n')
  }
  const exit=await backendExit;fs.closeSync(log)
  const api=JSON.parse(fs.readFileSync(path.join(root,'backend/target/material-workbench-acceptance.json'),'utf8'))
  const database=JSON.parse(fs.readFileSync(path.join(work,'database.json'),'utf8'))
  const output={run_id:id,passed:exit===0&&api.passed&&report.passed&&database.passed,sources_unchanged:before===digest()&&api.sources_unchanged,source_sha256:before,native:report,database,backend:api,full_api_acceptance_passed:false}
  output.passed=Boolean(output.passed&&output.sources_unchanged)
  fs.writeFileSync(path.join(work,'report.json'),JSON.stringify(output,null,2)+'\n');console.log(output.passed?'PASS native material upload':'FAIL native material upload');if(!output.passed)process.exitCode=1
}
main().catch(async()=>{if(proxy)await proxy.close();
  if(backend&&!backendFinished){try{const name='autocare-material-native-'+id.slice(0,8);const data=JSON.parse(execFileSync('docker',['inspect',name],{encoding:'utf8',windowsHide:true}));if(data[0].Config.Env.includes('AUTOCARE_NATIVE_MATERIAL_RUN_ID='+id))execFileSync('docker',['stop',name],{timeout:20000,windowsHide:true,stdio:'ignore'})}catch{}if(backendExit)await Promise.race([backendExit,delay(10000)])}
  if(log!==undefined)try{fs.closeSync(log)}catch{}console.error('FAIL native material orchestration; inspect private backend log');process.exitCode=1}).finally(()=>{for(const name of ['ready.private.json','ready.tmp','curl.private.cfg','headers.private.txt'])try{fs.unlinkSync(path.join(work,name))}catch{}})
