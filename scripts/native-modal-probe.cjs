// Run after reopening only the isolated project, without an image preview overlay.
const fs=require('node:fs'),assert=require('node:assert/strict')
const {connectDevtools}=require('../apps/miniapp/test/helpers/devtools-rpc.cjs')
let client
const report={completed:false,results:[],scope:'real system modal callbacks; no business writes or system-result mocks'}
async function main(){
  client=await connectDevtools({endpoint:'ws://127.0.0.1:9422'})
  const ready=(await client.rpc('App.callFunction',{functionDeclaration:`function(){return typeof wx.__autocareNativeAcceptance==='function'}`,args:[]})).result
  assert.equal(ready,true,'Requires isolated acceptance build')
  await client.rpc('App.callWxMethod',{method:'reLaunch',args:[{url:'/pages/index/index'}]})
  await client.rpc('App.callFunction',{functionDeclaration:`function(){wx.__nativePreviewProbe={done:false};wx.showModal({title:'合成原生交互验收',content:'仅测试系统按钮能力，不产生业务写入',success:r=>wx.__nativePreviewProbe={done:true,confirm:r.confirm,cancel:r.cancel},fail:()=>wx.__nativePreviewProbe={done:true,failed:true}});return true}`,args:[]})
  for(const method of ['confirmModal','cancelModal']){
    await client.rpc('Tool.native',{method});await new Promise(r=>setTimeout(r,1000))
    const state=(await client.rpc('App.callFunction',{functionDeclaration:'function(){return wx.__nativePreviewProbe}',args:[]})).result
    report.results.push({method,callbackObserved:state.done,confirm:state.confirm===true,cancel:state.cancel===true})
    if(state.done)break
  }
  const shot=await client.rpc('App.captureScreenshot');fs.writeFileSync('test-results/native-preview-modal-isolated.png',Buffer.from(shot.data,'base64'))
  report.completed=true
}
main().catch(e=>{report.failure=e.message;process.exitCode=1}).finally(async()=>{
  if(client){try{await client.rpc('App.callFunction',{functionDeclaration:'function(){delete wx.__nativePreviewProbe;return true}',args:[]})}finally{client.close()}}
  fs.writeFileSync('test-results/wechat-native-modal-probe.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report))
})
