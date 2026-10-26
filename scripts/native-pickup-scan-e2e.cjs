// Real native buttons and HTTP. Only scanCode result is assisted; never submits pickup.
const fs=require('node:fs'),assert=require('node:assert/strict')
const {connectDevtools,waitForPage}=require('../apps/miniapp/test/helpers/devtools-rpc.cjs')
const {elements,waitElement,tapElement}=require('../apps/miniapp/test/helpers/native-elements.cjs')
const {createWxMockScope}=require('../apps/miniapp/test/helpers/devtools-mocks.cjs')
const report={executed:true,passed:false,session_cleared:false,system_api_restored:false,checks:[],scope:'WeChat Devtools scanCode result assisted, synthetic sessions and SQL order, real native buttons and HTTP authorization; no physical camera/decoder, official identity or pickup submission acceptance'}
let client,mocks
const pass=name=>report.checks.push({name,passed:true})
async function main(){
  assert.equal(process.env.AUTOCARE_PICKUP_SCAN_NATIVE_QA,'1')
  const sessions=JSON.parse(fs.readFileSync(process.argv[2],'utf8'))
  client=await connectDevtools();const rpc=client.rpc;mocks=createWxMockScope(rpc)
  const actor=identity=>rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'merchant',token:sessions[identity+'_token']}]})
  const open=async()=>{
    const previous=await rpc('App.getCurrentPage');await rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/pages/merchant/orders'}]})
    const deadline=Date.now()+20000
    while(Date.now()<deadline){const p=await rpc('App.getCurrentPage');if(p.path==='pages/merchant/orders'&&p.pageId!==previous.pageId)return p;await new Promise(r=>setTimeout(r,100))}
    throw new Error('Devtools navigation timeout')
  }
  const read=async(p,action)=>{
    for(let attempt=0;attempt<3;attempt++)try{return await action()}catch(error){
      if(attempt===2||!error.message.startsWith('Devtools RPC'))throw error
      const current=await rpc('App.getCurrentPage');assert.equal(current.path,p.path);assert.deepEqual(current.query,p.query);Object.assign(p,current)
      report.read_retries=(report.read_retries||0)+1
    }
  }
  const text=(p,fragment)=>read(p,()=>waitElement(rpc,p,'text',e=>e.text?.includes(fragment)))
  const scan=async(p,payload,expect)=>{
    await mocks.mock('scanCode',{result:payload,scanType:'QR_CODE',errMsg:'scanCode:ok'})
    try{
      const button=await read(p,()=>waitElement(rpc,p,'button',e=>e.text?.trim()==='扫码接车'))
      await tapElement(rpc,p,button);await expect()
    }finally{await mocks.restore('scanCode')}
  }
  await actor('merchant');let p=await open()
  await text(p,'扫描车主订单详情中的接车二维码');pass('merchant native page identifies pickup QR rather than redemption code')
  await scan(p,'https://example.invalid/not-a-pickup',()=>text(p,'请扫描车主订单详情中的接车二维码'))
  assert.equal((await rpc('App.getCurrentPage')).path,'pages/merchant/orders');pass('invalid protocol stays on orders with useful error')
  p=await open();await scan(p,'VHMP:PICKUP:1:301',()=>text(p,'接车单状态已变化'))
  assert.equal((await rpc('App.getCurrentPage')).path,'pages/merchant/orders');pass('real HTTP rejects non-PAID order')
  await actor('other_merchant');p=await open();await scan(p,'VHMP:PICKUP:1:304',()=>text(p,'接车资源不存在或不可访问'))
  assert.equal((await rpc('App.getCurrentPage')).path,'pages/merchant/orders');pass('real HTTP denies foreign shop pickup')
  await actor('merchant');p=await open();await scan(p,'VHMP:PICKUP:1:304',async()=>{
    const target=await waitForPage(rpc,'pages/merchant/pickup');assert.equal(String(target.query.id),'304')
    await text(target,'接车检查');await read(target,()=>waitElement(rpc,target,'view[data-testid="pickup-order"]'))
  });pass('valid pickup QR uses real context and opens correct native pickup form')
  p=await open();await text(p,'扫描车主订单详情中的接车二维码')
  assert.equal(mocks.methods.length,0);pass('returning to orders leaves scanner API restored')
  report.passed=true
}
main().catch(error=>{report.error='Native scan check failed; private data omitted';report.error_kind=error.name;report.error_code=error.message.startsWith('Devtools')?error.message:'assertion or native element wait failed'}).finally(async()=>{
  if(client){try{for(const method of mocks?.methods||[])await mocks.restore(method);report.system_api_restored=(mocks?.methods.length||0)===0}catch{report.passed=false}
    try{await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'clear'}]});report.session_cleared=true}catch{report.passed=false}client.close()}
  fs.writeFileSync(process.argv[3],JSON.stringify(report,null,2)+'\n')
  if(!report.passed||!report.session_cleared||!report.system_api_restored)process.exitCode=1
})
