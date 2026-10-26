// Opt-in Devtools, synthetic sessions, real isolated delivery HTTP; never redeems.
const fs=require('node:fs'),assert=require('node:assert/strict')
const {connectDevtools,waitForPage}=require('../apps/miniapp/test/helpers/devtools-rpc.cjs')
const {elements,waitElement,tapElement}=require('../apps/miniapp/test/helpers/native-elements.cjs')
const report={executed:true,passed:false,session_cleared:false,checks:[],scope:'WeChat Devtools, synthetic in-memory identities and SQL facts, real HTTP reads; no phone, official login, image preview or redemption acceptance'}
let client
const pass=name=>report.checks.push({name,passed:true})
async function main(){
  assert.equal(process.env.AUTOCARE_DELIVERY_NATIVE_QA,'1')
  const sessions=JSON.parse(fs.readFileSync(process.argv[2],'utf8'))
  client=await connectDevtools();const rpc=client.rpc
  const actor=async identity=>rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:identity.includes('merchant')?'merchant':'owner',token:sessions[identity+'_token'],vehicle:1}]})
  const open=async url=>{
    const previous=await rpc('App.getCurrentPage')
    await rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/'+url}]})
    const deadline=Date.now()+20000
    while(Date.now()<deadline){const current=await rpc('App.getCurrentPage');if(current.path===url.split('?')[0]&&current.pageId!==previous.pageId)return current;await new Promise(resolve=>setTimeout(resolve,100))}
    throw new Error('Devtools navigation did not create a fresh page')
  }
  const compare=async(id,role='owner')=>open(`pages/order/delivery-compare?id=${id}&role=${role}`)
  const stableRead=async(p,read)=>{
    for(let attempt=0;attempt<3;attempt++){
      try{return await read()}catch(error){
        if(attempt===2||!error.message.startsWith('Devtools RPC'))throw error
        const current=await rpc('App.getCurrentPage')
        assert.equal(current.path,p.path);assert.deepEqual(current.query,p.query)
        Object.assign(p,current);report.read_retries=(report.read_retries||0)+1
        await new Promise(resolve=>setTimeout(resolve,150))
      }
    }
  }
  const list=(p,selector)=>stableRead(p,()=>elements(rpc,p,selector))
  const box=(p,name)=>stableRead(p,()=>waitElement(rpc,p,`view[data-testid="delivery-compare-${name}"]`))
  report.step='owner-session';await actor('owner');report.step='owner-ready';let p=await compare(301)
  let result=await box(p,'result');assert.ok(result.text.includes('证据齐备'))
  assert.ok((await box(p,'baseline')).text.includes('120'))
  assert.ok((await box(p,'work')).text.includes('完工照 1 张'))
  assert.equal((await list(p,'input')).length,0);pass('owner READY shows baseline and real work counts without redemption input')
  p=await compare(302);result=await box(p,'result');assert.ok(result.text.includes('不齐'))
  assert.ok((await box(p,'missing')).text.includes('完工'));pass('incomplete evidence displays actual missing facts')
  p=await compare(305);result=await box(p,'result');assert.ok(result.text.includes('只做证据回看'));pass('completed order remains read-only')
  p=await compare(303);await box(p,'failure');assert.equal((await list(p,'view[data-testid="delivery-compare-result"]')).length,0);pass('missing baseline fails without stale success')
  await actor('other');p=await compare(301);await box(p,'failure');assert.equal((await list(p,'view[data-testid="delivery-compare-baseline"]')).length,0);pass('foreign owner sees no baseline')
  await actor('merchant');p=await open('pages/merchant/redeem?id=301')
  const entry=await stableRead(p,()=>waitElement(rpc,p,'button',e=>e.text?.trim()==='取车对比（核销前核对证据）'));await tapElement(rpc,p,entry);p=await waitForPage(rpc,'pages/order/delivery-compare')
  result=await box(p,'result');assert.ok(result.text.includes('核销前'))
  pass('actual merchant redemption entry navigates to merchant comparison')
  await actor('other_merchant');p=await compare(301,'merchant');await box(p,'failure');assert.equal((await list(p,'view[data-testid="delivery-compare-work"]')).length,0);pass('foreign merchant sees no work evidence')
  report.passed=true
}
main().catch(error=>{report.error='Native delivery assertion/connection failed; private data omitted';report.error_kind=error.name;report.error_code=error.message.startsWith('Devtools')?error.message:'assertion or element wait failed'}).finally(async()=>{
  if(client){try{await client.rpc('App.callWxMethod',{method:'__autocareNativeAcceptance',args:[{role:'clear'}]});report.session_cleared=true}catch{report.passed=false}client.close()}
  fs.writeFileSync(process.argv[3],JSON.stringify(report,null,2)+'\n');if(!report.passed||!report.session_cleared)process.exitCode=1
})
