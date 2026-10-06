// gstack eval on the explicit loopback harness. Synthetic merchant login bridge;
// quote APIs and MySQL transactions remain real. Owner uses real DevTools code.
return await (async()=>{
  if(location.origin!=='http://127.0.0.1:4317')throw new Error('Local harness required')
  const original={request:uni.request,login:uni.login},checks=[]
  let merchant='',owner='',other='',stage='merchant-login'
  const sleep=ms=>new Promise(r=>setTimeout(r,ms)),text=()=>document.body.innerText
  const assert=(ok,label)=>{if(!ok)throw new Error(label);checks.push(label)}
  const wait=async(fn,label)=>{for(let i=0;i<120;i++){if(fn())return;await sleep(100)}throw new Error('Timeout: '+label)}
  const button=label=>[...document.querySelectorAll('uni-button')].find(el=>el.textContent.trim()===label && el.getClientRects().length && !el.hasAttribute('disabled'))
  const click=async label=>{await wait(()=>button(label),label);button(label).click();await sleep(150)}
  const local=async(path,body={})=>{const r=await fetch('/__local/'+path,{method:'POST',headers:{'Content-Type':'application/json','X-Local-Business':'1'},body:JSON.stringify(body)});if(!r.ok)throw new Error('Local helper unavailable');return r.json()}
  const api=async(path,token,body,key)=>{const r=await fetch(path,{method:body?'POST':'GET',headers:{Authorization:token,...(body?{'Content-Type':'application/json','Idempotency-Key':key}:{})},body:body?JSON.stringify(body):undefined});return {status:r.status,body:await r.json()}}
  const input=(el,value)=>{el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}))}
  const visibleInputs=()=>[...document.querySelectorAll('uni-input input')].filter(el=>el.getClientRects().length)
  uni.login=options=>local('code').then(r=>options.success?.({code:r.code})).catch(()=>options.fail?.({errMsg:'Local code unavailable'}))
  uni.request=options=>{
    if(options.url.endsWith('/api/auth/merchant/login')){
      local('merchant',{store:'A'}).then(data=>{merchant='Bearer '+data.access_token;options.success?.({statusCode:200,data:{code:0,message:'ok',data}})}).catch(()=>options.fail?.({errMsg:'Synthetic merchant session unavailable'}))
      return {abort(){}}
    }
    if(!options.url.startsWith('http://127.0.0.1:18080/'))throw new Error('Unexpected backend')
    return original.request({...options,url:options.url.replace('http://127.0.0.1:18080',location.origin),success:result=>{
      if(result.statusCode===200 && result.data?.data?.access_token)owner='Bearer '+result.data.data.access_token
      options.success?.(result)
    }})
  }
  try{
    await wait(()=>visibleInputs().length===3,'merchant form')
    const fields=visibleInputs();['local-quotes-merchant-A','synthetic-password','123456'].forEach((value,i)=>input(fields[i],value))
    await click('登录商家端');await wait(()=>text().includes('商家登录成功'),'merchant response bridge')
    await click('管理本店服务');await wait(()=>text().includes('本店服务与报价'),'merchant page')
    const before=await local('merchant-database')
    const ownBefore=await api('/api/merchant/projects',merchant)
    assert(ownBefore.status===200,'有效测试商家会话访问真实本店接口')
    stage='save'
    // Select the real H5 picker option. Buttons, text input and network
    // retry use rendered controls.
    const existing=document.querySelector('[data-testid="merchant-quote-9101101"]')
    if(existing)existing.querySelector('uni-button').click()
    else{
      await click('新增选品');await wait(()=>document.querySelector('uni-picker'),'project picker')
      const picker=document.querySelector('uni-picker');picker.click();await sleep(250)
      const option=()=>[...document.querySelectorAll('*')].find(el=>el.textContent.trim()==='本地合成测试服务01' && !el.children.length && el.getClientRects().length)
      await wait(option,'picker option');option().click()
    }
    await wait(()=>text().includes('本地合成测试服务01') && visibleInputs().length===1,'selected standard')
    input(visibleInputs()[0],'299.00');await local('drop-once',{path:'/api/merchant/projects'})
    await click('保存报价');await wait(()=>text().includes('无法连接服务'),'committed response interruption')
    await click('保存报价');await wait(()=>text().includes('报价已保存'),'same key retry')
    const after=await local('merchant-database')
    assert(after.versions===before.versions+1 && after.audits===before.audits+1,'提交后断网原键重试仅产生一版及一次成功审计')
    const evidence=await local('evidence'),writes=evidence.writes.filter(row=>row.route==='/api/merchant/projects')
    assert(writes.length>=2 && writes.at(-1).key===writes.at(-2).key && writes.at(-1).hash===writes.at(-2).hash,'页面重试保留原键与正文')
    await click('返回本店列表');await wait(()=>document.querySelector('[data-testid="merchant-quote-9101101"]'),'saved list')
    assert(text().includes('本店报价 ¥299.00'),'真实本店列表显示精确价格')
    stage='isolation'
    const sessionB=await local('merchant',{store:'B'});other='Bearer '+sessionB.access_token
    const key=crypto.randomUUID(),body={standard_project_id:9101101,price:'99.00',status:1}
    assert((await api('/api/merchant/projects',other,body,key)).status===200,'第二合成商家真实保存报价')
    assert((await api('/api/merchant/projects',other,{...body,price:'98.00'},key)).status===400,'同键不同正文被拒绝')
    assert((await api('/api/merchant/projects',merchant,{...body,merchant_id:9101202},crypto.randomUUID())).status===400,'客户端不能指定其他商家归属')
    const aList=await api('/api/merchant/projects',merchant),bList=await api('/api/merchant/projects',other)
    assert(aList.body.data.items[0].merchant_project_id!==bList.body.data.items[0].merchant_project_id,'两店列表隔离')
    const a=aList.body.data.items.find(row=>row.standard_project_id===9101101)
    const changed=await api('/api/merchant/projects',merchant,{...body,price:'199.00'},crypto.randomUUID())
    assert(changed.status===200 && changed.body.data.version===a.version+1,'真实修改报价递增不可变版本')
    const down=await api('/api/merchant/projects',merchant,{...body,price:'199.00',status:0},crypto.randomUUID())
    assert(down.status===200 && down.body.data.status===0,'本店真实下架')
    await api('/api/merchant/projects',merchant,{...body,price:'199.00'},crypto.randomUUID())
    stage='owner-login';uni.reLaunch({url:'/pages/owner/index'});await click('微信登录');await wait(()=>text().includes('身份验证成功'),'real owner login')
    assert(Boolean(owner),'车主使用真实微信会话')
    assert((await api('/api/merchant/projects',owner,body,crypto.randomUUID())).status===403,'车主禁止维护商家报价')
    stage='owner-quotes';uni.navigateTo({url:'/pages/service/detail?id=9101101'})
    await wait(()=>text().includes('本地合成测试商家A') && text().includes('本地合成测试商家B'),'owner real quotes')
    const asc=await api('/api/service/project/9101101/merchants?sort=price_asc',owner)
    assert(asc.body.data.items[0].price==='99.00','车主真实升序报价')
    await click('价格从高到低');await wait(()=>text().indexOf('本地合成测试商家A')<text().indexOf('本地合成测试商家B'),'descending UI')
    assert(true,'车主页面价格降序切换')
    const anon=await fetch('/api/service/project/9101101/merchants');assert(anon.status===401,'匿名报价查询拒绝')
    return {passed:checks.length,checks,boundary:'H5 controls, synthetic merchant login response bridge, real owner WeChat code, real backend/MySQL; no SMS or physical-device acceptance'}
  }catch(error){return {failed:true,stage,reason:error.message,passed:checks.length,checks}}
  finally{
    if(owner)await fetch('/api/auth/logout',{method:'POST',headers:{Authorization:owner}}).catch(()=>{})
    await local('revoke-merchants').catch(()=>{});uni.request=original.request;uni.login=original.login
  }
})()
