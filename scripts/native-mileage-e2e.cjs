const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict')
const {connectDevtools,waitForPage}=require('../apps/miniapp/test/helpers/devtools-rpc.cjs')
const {waitElement,tapElement}=require('../apps/miniapp/test/helpers/native-elements.cjs')
const {createWxMockScope}=require('../apps/miniapp/test/helpers/devtools-mocks.cjs')
const {createMileageProxy}=require('./native-mileage-proxy.cjs')
const {ResponseGate,runInFlight}=require('./native-mileage-inflight.cjs')
const root=path.resolve(__dirname,'..'),work=path.resolve(process.argv[2]||''),run=path.basename(work).replace('real-mileage-','')
const delay=ms=>new Promise(r=>setTimeout(r,ms)),report={passed:false,checks:[],scope:'Devtools picker/switch/input events assisted; real upload, real OCR gateway, original archive save; synthetic identities/image/scanner; no phone or owner accuracy acceptance'}
let client,mocks,proxy,gate
const pass=name=>report.checks.push({name,passed:true})
async function main(){
 assert.ok(process.argv.includes('--allow-isolated-mileage'));assert.match(run,/^[a-f0-9]{32}$/);assert.equal(path.dirname(work),path.join(root,'.cache'))
 const readyFile=path.join(work,'native-ready.private.json'),deadline=Date.now()+15*60000
 while(!fs.existsSync(readyFile)){assert.ok(!fs.existsSync(path.join(work,'backend-finished')),'JVM exited before handoff');assert.ok(Date.now()<deadline,'JVM native handoff timed out');await delay(200)}
 const ready=JSON.parse(fs.readFileSync(readyFile,'utf8'))
 gate=new ResponseGate();proxy=await createMileageProxy({...ready,work,run,responseGate:ready.reliability?o=>gate.accept(o):null})
 client=await connectDevtools({timeoutMs:20000});const rpc=client.rpc;mocks=createWxMockScope(rpc)
 report.sdk_version=(await rpc('Tool.getInfo')).SDKVersion;assert.equal(report.sdk_version,'3.17.2','Native mileage acceptance requires the verified SDK 3.17.2')
 const downloaded=(await rpc('App.callWxMethod',{method:'downloadFile',args:[{url:'http://127.0.0.1:18080/__mileage-fixture.png',header:{Authorization:'Bearer '+ready.token}}]})).result
 assert.equal(downloaded.statusCode,200);const local=downloaded.tempFilePath
 const checkpoint=async phase=>{fs.writeFileSync(path.join(work,'checkpoint-'+phase+'.request'),phase);const file=path.join(work,'checkpoint-'+phase+'.json'),until=Date.now()+15000;while(!fs.existsSync(file)){assert.ok(Date.now()<until,'Database checkpoint timed out');await delay(100)}assert.equal(JSON.parse(fs.readFileSync(file,'utf8')).passed,true)}
 if(ready.reliability)report.inflight=await runInFlight({rpc,mocks,proxy,gate,ready,local,checkpoint})
 const offset=ready.reliability?{upload:5,recognition:5}: {upload:0,recognition:0}
 await rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'owner',token:ready.token,vehicle:992}]})
 await rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/pages/archive/record-add?vehicle_id=992'}]})
 const p=await waitForPage(rpc,'pages/archive/record-add')
 const read=async fn=>{for(let i=0;i<3;i++)try{return await fn()}catch(error){if(i===2||!error.message.startsWith('Devtools RPC'))throw error;const current=await rpc('App.getCurrentPage');assert.equal(current.path,p.path);Object.assign(p,current)}}
 const element=selector=>read(()=>waitElement(rpc,p,selector))
 const tap=async selector=>tapElement(rpc,p,await element(selector))
 const text=value=>read(()=>waitElement(rpc,p,'view',e=>e.text?.includes(value)))
 const change=async(selector,value,type='change')=>{const e=await element(selector);await rpc('Element.triggerEvent',{pageId:p.pageId,elementId:e.elementId,type,detail:{value}})}
 const currentMileage=value=>read(()=>waitElement(rpc,p,'view.row',e=>e.text?.includes('材料所载里程')&&e.text?.includes('当前：'+value)))
 const size=Buffer.from(ready.image,'base64').length
 await mocks.mock('chooseMedia',{tempFiles:[{tempFilePath:local,size,fileType:'image'}],type:'image',errMsg:'chooseMedia:ok'})
 await mocks.mock('chooseImage',{tempFilePaths:[local],tempFiles:[{path:local,size}],errMsg:'chooseImage:ok'})
 try{const button=await read(()=>waitElement(rpc,p,'button',e=>e.text?.trim()==='选择并上传图片'));await tapElement(rpc,p,button);await text('图片已上传，保存档案后才会归档')}finally{await mocks.restore('chooseMedia');await mocks.restore('chooseImage')}
 assert.equal(proxy.counts.upload,1+offset.upload);pass('upload_real_png')
 await tap('button[data-testid="mileage-recognize"]');await delay(250);assert.equal(proxy.counts.recognition,offset.recognition)
 await change('picker[data-testid="mileage-source"]','0');await text('已选图片 1')
 await tap('button[data-testid="mileage-recognize"]');await delay(250);assert.equal(proxy.counts.recognition,offset.recognition);pass('selection_consent_required')
 await change('switch[data-testid="mileage-consent"]',true)
 await tap('button[data-testid="mileage-recognize"]');await text('机器候选尚未核对');await currentMileage('未填写');assert.equal(proxy.counts.recognition,1+offset.recognition);await checkpoint('candidate');pass('candidate_not_saved')
 await tap('button[data-testid="candidate-review"]');await text('材料所载里程（公里）：123456')
 await tap('button[data-testid="candidate-confirm"]');await currentMileage('未填写');assert.equal(proxy.counts.archive,0);pass('ack_required')
 await tap('button[data-testid="candidate-cancel"]');await checkpoint('cancelled')
 await tap('button[data-testid="mileage-recognize"]');await delay(250);assert.equal(proxy.counts.recognition,1+offset.recognition);pass('cancel_revokes_consent')
 await change('switch[data-testid="mileage-consent"]',true);await tap('button[data-testid="mileage-recognize"]');await text('机器候选尚未核对');await currentMileage('未填写');assert.equal(proxy.counts.recognition,2+offset.recognition);pass('second_real_recognition')
 await tap('button[data-testid="candidate-review"]');await change('switch[data-testid="candidate-ack"]',true);await tap('button[data-testid="candidate-confirm"]');await text('已带入表单，尚未保存或发送');assert.equal(proxy.counts.archive,0);await checkpoint('applied');pass('confirmation_only_applies')
 await change('input[data-testid="archive-title"]','synthetic-mileage-native','input')
 await tap('button[data-testid="archive-save"]');await text('档案已保存');assert.deepEqual(proxy.counts,{upload:1+offset.upload,recognition:2+offset.recognition,archive:1});pass('original_save_succeeds')
 await rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/pages/archive/record-add?vehicle_id=992'}]})
 let again;const until=Date.now()+10000;do{again=await waitForPage(rpc,'pages/archive/record-add');if(again.pageId!==p.pageId)break;await delay(100)}while(Date.now()<until)
 assert.notEqual(again.pageId,p.pageId);await waitElement(rpc,again,'text',e=>e.text?.includes('归档图片（0/5）'));report.local_reference_released=true;pass('reentry_empty')
 report.passed=true
}
const admittedRun=process.argv.includes('--allow-isolated-mileage')&&/^[a-f0-9]{32}$/.test(run)&&path.dirname(work)===path.join(root,'.cache')&&fs.existsSync(work)&&fs.statSync(work).isDirectory()
if(!admittedRun){console.error('Explicit isolated flag and existing owned run directory required');process.exitCode=1}else main().catch(error=>{fs.writeFileSync(path.join(work,'native-error.private.log'),error.stack||error.name);report.error_kind=error.name}).finally(async()=>{
 gate?.release()
 if(client){try{for(const method of mocks?.methods||[])await mocks.restore(method);report.system_api_restored=true}catch{report.passed=false}try{await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'clear'}]});report.session_cleared=true}catch{report.passed=false}client.close()}
 if(proxy){report.counts={...proxy.counts};await proxy.close();report.proxy_closed=true}
 fs.writeFileSync(path.join(work,'native.json'),JSON.stringify(report,null,2)+'\n');if(!report.passed)process.exitCode=1
})
