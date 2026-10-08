import test from 'node:test'
import assert from 'node:assert/strict'
import { createWorkApi, protectionBody, reportBody, validWorkView, WorkError } from '../src/services/service-work.js'
import { createWorkImageFlow, initialWorkImageState } from '../src/services/work-image-flow.js'
import { createSignatureStroke, signatureFile, exportSignature } from '../src/services/signature-pad.js'
import { createImageApi } from '../src/services/private-images.js'
import { createReservationWriteFlow, initialReservationWriteState } from '../src/services/reservations.js'
const key = '01234567-89ab-4cde-8fab-0123456789ab'
const time = '2026-10-08T00:00:00Z'
const protection = () => ({ items: ['SEAT_COVER','STEERING_COVER'], uploaded_at: time, photo_file_id: 101 })
const form = () => ({ plan: ' 实际施工 ', analysis: ' 实际分析 ', minutes: '45', noFaultParts: false, noParts: false, parts: [{ name:'配件',model:'型号',brand:'品牌',quantity:'2' }] })
const photos = () => [{kind:'PROCESS',fileId:102},{kind:'FAULT',fileId:103},{kind:'FINISH',fileId:104}]
const report = () => { const {order_id,...r}=reportBody(1,form(),photos());return {...r,report_id:1,submitted_at:time,signed_at:null,signature_file_id:null,status:'SUBMITTED'} }
const view = () => ({order_id:1,order_status:'IN_SERVICE',protection:protection(),report:null})
const signed = () => ({...view(),order_status:'PENDING_VERIFY',report:{...report(),status:'SIGNED',signed_at:time,signature_file_id:105}})
const runtime = handler => () => ({request: handler})

test('防护须显式勾选必选项和安全文件，不允许未知或重复勾选',()=>{
  assert.deepEqual(protectionBody(1,['SEAT_COVER','STEERING_COVER'],101),{order_id:1,items:['SEAT_COVER','STEERING_COVER'],photo_file_id:101})
  for(const list of [[],['SEAT_COVER'],['SEAT_COVER','SEAT_COVER'],['SEAT_COVER','STEERING_COVER','UNKNOWN']])assert.throws(()=>protectionBody(1,list,101))
  for(const file of [0,1.5,'101'])assert.throws(()=>protectionBody(1,['SEAT_COVER','STEERING_COVER'],file))
})
test('完整报工转换整数分钟与配件数量，保留真实三类照片',()=>{
  const body=reportBody(1,form(),photos());assert.equal(body.work_hours,45);assert.equal(body.parts_used[0].quantity,2);assert.equal(body.repair_plan,'实际施工');assert.deepEqual(body.fault_part_photos,[103])
})
test('无故障件和无配件须明确声明，不能将漏填解释为无记录',()=>{
  const f=form();assert.throws(()=>reportBody(1,f,photos().filter(p=>p.kind!=='FAULT')));f.noFaultParts=true;f.noParts=true;f.parts=[];assert.throws(()=>reportBody(1,f,photos()));const body=reportBody(1,f,photos().filter(p=>p.kind!=='FAULT'));assert.deepEqual(body.parts_used,[]);assert.deepEqual(body.fault_part_photos,[])
})
test('照片跨组不能复用、未上传或超出九张，工时和配件边界严格',()=>{
  for(const changed of [[...photos(),{kind:'PROCESS',fileId:104}],[...photos(),{kind:'PROCESS',fileId:0}],photos().filter(p=>p.kind!=='FINISH'),[...photos(),...Array.from({length:9},(_,i)=>({kind:'PROCESS',fileId:200+i}))]])assert.throws(()=>reportBody(1,form(),changed))
  for(const n of ['0','1.5','1441','-1','']){const f=form();f.minutes=n;assert.throws(()=>reportBody(1,f,photos()))}
  for(const n of ['0','2.5','1000','']){const f=form();f.parts[0].quantity=n;assert.throws(()=>reportBody(1,f,photos()))}
})
test('施工响应仅允许白名单，不接收身份、永久路径或缺证据的已签字',()=>{
  assert.equal(validWorkView(view()),true);assert.equal(validWorkView(signed()),true)
  for(const v of [{...view(),user_id:99},{...view(),protection:{...protection(),owner_id:1}},{...view(),report:{...report(),technician_id:12}},{...signed(),report:{...signed().report,signature_file_id:null}},{...view(),protection:{...protection(),photo_file_id:null}}])assert.equal(validWorkView(v),false)
})
test('商家和技师施工读取使用各自路径与令牌，并拒绝串单响应',async()=>{
  let call;const api=createWorkApi({baseUrl:'http://local',runtime:runtime(o=>{call=o;o.success({statusCode:200,data:{code:0,data:view()}})})});await api.detail('shop','merchant',1);assert.equal(call.url,'http://local/api/merchant/orders/1/work');await api.detail('tech','tech',1);assert.equal(call.header.Authorization,'Bearer tech');await assert.rejects(api.detail('tech','tech',2),e=>e.kind==='protocol');assert.throws(()=>api.detail('tech','owner',1))
})
test('三个写入保持核心路径与幂等键，签字需真正进入待核销',async()=>{
  let call;const api=createWorkApi({baseUrl:'http://local',runtime:runtime(o=>{call=o;const value=o.url.endsWith('/sign')?signed():o.url.endsWith('/submit')?{...view(),report:report()}:view();o.success({statusCode:200,data:{code:0,data:value}})})});await api.protect('shop',protectionBody(1,['SEAT_COVER','STEERING_COVER'],101),key);assert.equal(call.url,'http://local/api/check/protection/upload');await api.submit('tech',reportBody(1,form(),photos()),key);assert.equal(call.url,'http://local/api/tech/report/submit');await api.sign('tech',1,105,key);assert.equal(call.url,'http://local/api/tech/sign');assert.equal(call.header['Idempotency-Key'],key);assert.deepEqual(call.data,{order_id:1,signature_file_id:105});assert.throws(()=>api.sign('tech',1,105,''));assert.throws(()=>api.submit('tech',{...reportBody(1,form(),photos()),status:'SIGNED'},key))
})
test('缺防护和争议错误有实际操作提示，不反射服务器错误内容',async()=>{
  let code=43002;const api=createWorkApi({baseUrl:'http://local',runtime:runtime(o=>o.success({statusCode:409,data:{code,message:'private SQL'}}))});await assert.rejects(api.submit('tech',reportBody(1,form(),photos()),key),e=>e.code===43002&&e.message.includes('防护')&&!e.message.includes('SQL'));code=43007;await assert.rejects(api.sign('tech',1,105,key),e=>e.message.includes('争议'))
})
test('关联图片走订单授权，拒绝 HTTP、凭证 URL、过期签名与多余字段',async()=>{
  let call,data={url:'https://test.invalid/signed',expires_at:new Date(Date.now()+120000).toISOString()};const api=createWorkApi({baseUrl:'http://local',runtime:runtime(o=>{call=o;o.success({statusCode:200,data:{code:0,data}})})});await api.access('tech','tech',1,101);assert.equal(call.url,'http://local/api/tech/orders/1/work/files/101/access');for(const bad of [{...data,url:'http://test.invalid/image'},{...data,url:'https://user:pass@test.invalid/image'},{...data,expires_at:time},{...data,object_key:'private'}]){data=bad;await assert.rejects(api.access('tech','tech',1,101),e=>e.kind==='protocol')}
})
test('技师图片上传使用独立 TECH 路径，不借用商家/车主接口',async()=>{
  let call;const api=createImageApi({baseUrl:'http://local',prefix:'/api/tech/files',runtime:()=>({uploadFile:o=>{call=o;o.success({statusCode:200,data:{code:0,data:{file_id:102,content_type:'image/png',size_bytes:100}}})}})});await api.upload('tech',{path:'photo.png',size:100},key);assert.equal(call.url,'http://local/api/tech/files/upload');assert.equal(call.header.Authorization,'Bearer tech');assert.throws(()=>api.choose(''),e=>e.message.includes('技师'))
})
function fixture(overrides={}){
  let actor='tech',counter=0;const calls=[],state=initialWorkImageState();const api={choose:async()=>({path:'actual.png',size:100}),upload:async(t,file,k)=>{calls.push({t,file,k});return {file_id:++counter,content_type:'image/png'}},access:async()=>({url:'https://test.invalid/signed'}),preview:async()=>{},...overrides};const flow=createWorkImageFlow({state,api,token:()=>actor,newKey:()=>key});flow.resume();return {state,api,flow,calls,change:n=>{actor=n}}
}
test('相机取消不报错，开发者工具降级提示在上传后保留',async()=>{
  const f=fixture({choose:async()=>null});await f.flow.choose('PROCESS');assert.equal(f.state.files.length,0);assert.equal(f.calls.length,0);f.api.choose=async()=>({path:'album.png',size:100,degraded:true});await f.flow.choose('PROCESS');assert.match(f.state.message,/开发者工具没有摄像头/);assert.equal(f.state.files[0].fileId,1)
})
test('失败图片重试保留原图与原键，替换后才换图',async()=>{
  let attempts=0;const calls=[];const f=fixture({upload:async(t,file,k)=>{calls.push({t,file,k});if(!attempts++)throw new WorkError('unavailable','重试');return {file_id:102,content_type:'image/png'}}});await f.flow.choose('PROCESS');const entry=f.state.files[0];assert.equal(entry.fileId,0);await f.flow.upload(entry);assert.deepEqual(calls[0],calls[1]);assert.equal(entry.fileId,102);f.flow.remove(entry);assert.equal(f.state.files.length,0)
})
test('切账号与隐藏后返回，都不能接收旧取图回调',async()=>{
  let resolve;const f=fixture({choose:()=>new Promise(r=>{resolve=r})});const task=f.flow.choose('PROCESS');f.flow.suspend();f.flow.resume();resolve({path:'old.png',size:100});await task;assert.equal(f.state.files.length,0);const switched=f.flow.choose('PROCESS');f.change('new-tech');f.flow.reset();f.flow.resume();resolve({path:'old.png',size:100});await switched;assert.equal(f.calls.length,0)
})
test('隐藏期间上传成功回执丢弃，返回原图重试仍使用原键',async()=>{
  let resolve,attempt=0;const calls=[];const f=fixture({upload:(t,file,k)=>{calls.push({t,file,k});return attempt++?Promise.resolve({file_id:102,content_type:'image/png'}):new Promise(r=>{resolve=r})}});const task=f.flow.choose('PROCESS');await Promise.resolve();f.flow.suspend();f.flow.resume();resolve({file_id:102,content_type:'image/png'});await task;const entry=f.state.files[0];assert.equal(entry.fileId,0);assert.ok(entry.candidate);await f.flow.upload(entry);assert.deepEqual(calls[0],calls[1]);assert.equal(entry.fileId,102)
})
test('迟到预览签名不能打开新账号的图片窗口',async()=>{
  let resolve,opened=0;const f=fixture({access:()=>new Promise(r=>{resolve=r}),preview:async()=>{opened++}});const task=f.flow.preview(101);f.change('new');f.flow.reset();f.flow.resume();resolve({url:'https://test.invalid/old'});await task;assert.equal(opened,0);assert.equal(f.state.busy,false)
})
test('每类最多九张，防护仅一张；忙时不能重复选择',async()=>{
  const f=fixture();for(let i=0;i<9;i++)await f.flow.choose('PROCESS');await f.flow.choose('PROCESS');assert.equal(f.calls.length,9);assert.match(f.state.message,/最多 9/);await f.flow.choose('PROTECTION');await f.flow.choose('PROTECTION');assert.equal(f.calls.length,10)
})
test('施工拒绝 WEBP，签名只接受 PNG，不把已上传的其他格式当成就绪',async()=>{
  const f=fixture({upload:async()=>({file_id:101,content_type:'image/webp'})});await f.flow.choose('PROCESS');assert.equal(f.state.files[0].fileId,0);assert.match(f.state.message,/JPEG/);f.api.upload=async()=>({file_id:105,content_type:'image/jpeg'});await f.flow.signature({path:'sig.png',size:100});assert.equal(f.state.files.find(p=>p.kind==='SIGNATURE').fileId,0);assert.match(f.state.message,/PNG/)
})
test('网络重试报工保持请求键，改工时后换键；切账号丢弃旧结果',async()=>{
  const f=form(),state=initialReservationWriteState();let actor='tech',calls=[],counter=0,resolve;const flow=createReservationWriteFlow({state,token:()=>actor,newKey:()=>String(++counter),body:()=>reportBody(1,f,photos()),request:(t,b,k)=>{calls.push({t,b,k});return Promise.reject(new WorkError('unavailable','重试'))}});await flow.save();await flow.save();assert.deepEqual(calls[0],calls[1]);f.minutes='60';await flow.save();assert.notEqual(calls[1].k,calls[2].k)
  const delayed=createReservationWriteFlow({state:initialReservationWriteState(),token:()=>actor,newKey:()=>key,body:()=>reportBody(1,f,photos()),request:()=>new Promise(r=>{resolve=r})});const task=delayed.save();actor='new-tech';delayed.reset();resolve(view());await task
})
test('签名板拒绝空白、单点与无效坐标，实际笔画可导出并清空',()=>{
  const calls=[];const context=new Proxy({}, {get:(_,method)=>(...args)=>calls.push([method,...args])});const pad=createSignatureStroke(context,200,100);pad.clear();assert.equal(pad.hasInk(),false);pad.start({x:10,y:10});pad.end();assert.equal(pad.hasInk(),false);pad.start({x:10,y:10});pad.move({x:NaN,y:5});pad.move({x:11,y:10});assert.equal(pad.hasInk(),false);pad.move({x:210,y:200});assert.equal(pad.hasInk(),true);assert.ok(calls.some(c=>c[0]==='lineTo'&&c[1]===200&&c[2]===100));pad.clear();assert.equal(pad.hasInk(),false)
})
test('签名 PNG 导出先刷新绘制，再测量临时文件；H5 支持文件信息回退',async()=>{
  const calls=[];const context={draw:(reserve,done)=>{calls.push('draw');done()}};const runtime={canvasToTempFilePath:(o,scope)=>{calls.push('export');assert.equal(o.fileType,'png');assert.equal(scope,'component');o.success({tempFilePath:'signature.png'})},getFileInfo:o=>{calls.push('size');o.success({size:100})}};assert.deepEqual(await exportSignature({context,runtime,scope:'component',current:()=>true}),{path:'signature.png',size:100});assert.deepEqual(calls,['draw','export','size']);await assert.rejects(signatureFile({getFileInfo:o=>o.success({size:0})},'empty.png'));await assert.rejects(signatureFile({getFileInfo:o=>o.success({size:11*1024*1024})},'large.png'))
})
test('账号/页面代次变化后，不再导出旧画布，也不交付迟到文件',async()=>{
  let draw,live=true,exports=0;const task=exportSignature({context:{draw:(_,done)=>{draw=done}},runtime:{canvasToTempFilePath:()=>{exports++}},current:()=>live});live=false;draw();await assert.rejects(task,e=>e.kind==='cancelled');assert.equal(exports,0)
  let size;live=true;const delayed=exportSignature({context:{draw:(_,done)=>done()},runtime:{canvasToTempFilePath:o=>o.success({tempFilePath:'old.png'}),getFileInfo:o=>{size=o.success}},current:()=>live});await Promise.resolve();await Promise.resolve();live=false;size({size:100});await assert.rejects(delayed,e=>e.kind==='cancelled')
})
test('原生签名导出无回调时会超时恢复，不能永久卡住按钮',async()=>{
  await assert.rejects(exportSignature({context:{draw:()=>{}},runtime:{},current:()=>true,timeout:10}),e=>e.kind==='timeout')
})
