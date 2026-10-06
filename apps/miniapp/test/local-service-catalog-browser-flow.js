// gstack eval; real DevTools code and backend, isolated synthetic service data.
return await (async () => {
  if(location.origin!=='http://127.0.0.1:4317') throw new Error('Local harness required')
  const original={ login:uni.login, request:uni.request }, checks=[]
  let token='', failOnce=false, stage='login'
  const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms))
  const text=()=>document.body.innerText
  const assert=(ok,label)=>{ if(!ok)throw new Error(label); checks.push(label) }
  const wait=async(fn,label)=>{ for(let i=0;i<100;i++){if(fn())return;await sleep(100)} throw new Error('Timeout: '+label) }
  const button=label=>[...document.querySelectorAll('uni-button')].find(el=>el.textContent.trim()===label && el.getClientRects().length && !el.hasAttribute('disabled'))
  const click=async label=>{ await wait(()=>button(label),label);button(label).click();await sleep(100) }
  const api=async path=>{ const response=await fetch(path,{headers:{Authorization:token}});return {status:response.status,body:await response.json()} }
  uni.login=options=>fetch('/__local/code',{method:'POST',headers:{'Content-Type':'application/json','X-Local-Business':'1'},body:'{}'})
    .then(response=>response.json()).then(result=>options.success?.({code:result.code})).catch(()=>options.fail?.({errMsg:'Local login unavailable'}))
  uni.request=options=>{
    if(!options.url.startsWith('http://127.0.0.1:18080/'))throw new Error('Unexpected backend')
    if(options.header?.Authorization) token=options.header.Authorization
    if(failOnce && options.url.includes('/api/service/projects')) {
      failOnce=false; setTimeout(()=>options.fail?.({errMsg:'Controlled network interruption'}),50);return {abort(){}}
    }
    return original.request({...options,url:options.url.replace('http://127.0.0.1:18080',location.origin),success:response=>{
      if(response.statusCode===200 && response.data?.data?.access_token) token=`Bearer ${response.data.data.access_token}`
      options.success?.(response)
    }})
  }
  try {
    await click('微信登录');await wait(()=>text().includes('身份验证成功'),'real owner login')
    assert(Boolean(token),'真实微信车主会话可用')
    stage='list';uni.switchTab({url:'/pages/service/index'})
    await wait(()=>document.querySelector('[data-testid="service-project-9101101"]'),'first project')
    assert(text().includes('参考价 ¥0.10–199.99'),'真实列表精确参考价')
    await click('加载更多服务');await wait(()=>document.querySelector('[data-testid="service-project-9101124"]'),'second page')
    assert(!text().includes('本地合成测试服务25') && !text().includes('本地合成测试服务26'),'停用及删除项目不展示')
    assert(document.querySelectorAll('[data-testid^="service-project-"]').length>=24,'真实分页加载合成项目')
    stage='category';await click('轮胎');await wait(()=>document.querySelector('[data-testid="service-project-9101123"]'),'category result')
    assert(!document.querySelector('[data-testid="service-project-9101101"]'),'分类查询清除其他分类')
    await click('用品');await wait(()=>text().includes('该分类暂无服务项目'),'empty category')
    assert(!document.querySelector('[data-testid^="service-project-"]'),'真实空分类')
    failOnce=true;await click('轮胎');await wait(()=>text().includes('无法连接服务'),'controlled network failure')
    await click('重试加载服务');await wait(()=>document.querySelector('[data-testid="service-project-9101123"]'),'retry result')
    assert(!text().includes('无法连接服务'),'前端断网重试恢复真实结果')
    stage='detail';document.querySelector('[data-testid="service-project-9101123"]').click()
    await wait(()=>document.querySelector('[data-testid="service-detail"]'),'detail page')
    assert(text().includes('本地合成服务内容，仅供联调') && text().includes('暂未提供质量标准'),'真实详情与缺省质量标准')
    await click('返回服务列表');await wait(()=>document.querySelector('[data-testid="service-category-0"]'),'returned list')
    uni.navigateTo({url:'/pages/service/detail?id=9101125'})
    await wait(()=>text().includes('项目不存在或已停用'),'disabled detail')
    assert(!document.querySelector('[data-testid="service-detail"]'),'停用项目详情不可用')
    stage='backend';const invalid=await api('/api/service/projects?category=7'), deleted=await api('/api/service/project/9101126')
    assert(invalid.status===400 && deleted.status===404,'真实后端参数与软删除校验')
    const anonymous=await fetch('/api/service/projects');assert(anonymous.status===401,'匿名接口拒绝')
    const logout=await fetch('/api/auth/logout',{method:'POST',headers:{Authorization:token}})
    const revoked=await api('/api/service/projects');assert(logout.status===200 && revoked.status===401,'真实退出会话撤销')
    return {passed:checks.length,checks,boundary:'H5 UI + real WeChat session + local backend/MySQL; controlled UI network failure; synthetic projects/prices; no physical-device acceptance'}
  } catch(error) { return {failed:true,stage,reason:error.message,passed:checks.length,checks} }
  finally {uni.login=original.login;uni.request=original.request}
})()
