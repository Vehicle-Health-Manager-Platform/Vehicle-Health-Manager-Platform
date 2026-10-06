// Run with gstack browse eval on the opt-in loopback acceptance harness only.
// wx.login bridges to real DevTools; HTTP is forwarded to the real local backend.
// The dropped response occurs AFTER backend commit to exercise safe retries.
return await (async () => {
  if (location.origin !== 'http://127.0.0.1:4317') throw new Error('Local acceptance origin required')
  const original = { login:uni.login, request:uni.request }, checks=[]
  let stage='login', token='', lastVehicle=null, lastArchive=null
  const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms))
  const text=()=>document.body.textContent
  const assert=(condition,label)=>{ if(!condition) throw new Error(label); checks.push(label) }
  const wait=async(condition,label)=>{ for(let i=0;i<150;i++){ if(condition()) return; await sleep(100) } throw new Error('Timeout: '+label) }
  const button=label=>[...document.querySelectorAll('uni-button')].find(el=>el.textContent.trim()===label && !el.hasAttribute('disabled') && el.getClientRects().length)
  const click=async label=>{ await wait(()=>button(label),'button '+label); button(label).click(); await sleep(250) }
  const local=async(action,body={})=>{
    const response=await fetch('/__local/'+action,{method:'POST',headers:{'Content-Type':'application/json','X-Local-Business':'1'},body:JSON.stringify(body)})
    if(!response.ok) throw new Error('Local harness unavailable')
    return response.json()
  }
  const api=async(route,method='GET',body,key)=>{
    const response=await fetch(route,{method,headers:{Authorization:token,...(body?{'Content-Type':'application/json'}:{}),...(key?{'Idempotency-Key':key}:{})},body:body?JSON.stringify(body):undefined})
    return {status:response.status,body:await response.json()}
  }
  uni.login=options=>{
    local('code').then(result=>{options.success?.({code:result.code}); options.complete?.({})}).catch(()=>{options.fail?.({errMsg:'Local WeChat bridge unavailable'}); options.complete?.({})})
  }
  uni.request=options=>{
    if(!options.url.startsWith('http://127.0.0.1:18080/')) throw new Error('Unexpected backend origin')
    const success=options.success
    return original.request({...options,url:options.url.replace('http://127.0.0.1:18080',location.origin),success:response=>{
      if(options.header?.Authorization) token=options.header.Authorization
      if(response.statusCode===200 && response.data?.code===0){
        if(options.url.endsWith('/api/vehicle/add')) lastVehicle=response.data.data
        if(options.url.endsWith('/api/archive/add')) lastArchive=response.data.data
      }
      success?.(response)
    }})
  }
  async function pick(index){
    const el=document.querySelectorAll('uni-picker')[index]
    if(!el) throw new Error('Missing picker')
    el.click(); await sleep(200)
    const confirm=document.querySelector('.uni-picker-toggle .uni-picker-action-confirm')
    if(!confirm) throw new Error('Picker did not open')
    confirm.click(); await sleep(350)
  }
  function input(index,value){
    const el=document.querySelectorAll('uni-input input')[index]
    if(!el) throw new Error('Missing input')
    el.value=value; el.dispatchEvent(new Event('input',{bubbles:true}))
  }
  async function createVehicle(mileage,plate,drop){
    await click('手动添加'); await wait(()=>text().includes('添加我的车辆'),'vehicle form')
    await wait(()=>!document.querySelector('uni-picker')?.hasAttribute('disabled'),'brand loaded')
    await pick(0); await pick(1); await pick(2); await pick(3)
    assert(text().includes('本地合成测试品牌') && text().includes('合成配置A'),'真实四级车型选择 '+mileage)
    input(0,String(mileage)); input(1,plate); input(2,'')
    if(drop) await local('drop-once',{path:'/api/vehicle/add'})
    lastVehicle=null; await click('保存车辆')
    if(drop){ await wait(()=>text().includes('无法连接车辆服务'),'vehicle ambiguous failure'); await click('保存车辆') }
    await wait(()=>text().includes('车辆已保存'),'vehicle saved')
    const id=lastVehicle.vehicle_id
    await click('返回查看车辆'); await wait(()=>text().includes('当前里程 '+mileage+' km'),'vehicle appears')
    assert(!text().includes(plate),'车牌列表脱敏 '+mileage)
    return id
  }
  function selectMileage(mileage){
    const row=[...document.querySelectorAll('[data-testid="vehicle-list"] .vehicle')].find(el=>el.textContent.includes('当前里程 '+mileage+' km'))
    if(!row) throw new Error('Vehicle row missing')
    row.click()
  }
  async function createArchive(vehicleId,title,drop){
    await click('手动录入'); await wait(()=>text().includes('新增车辆档案'),'archive form')
    input(0,title); input(1,'1200')
    const note=document.querySelector('uni-textarea textarea'); if(note){ note.value='合成联调记录，无真实个人资料'; note.dispatchEvent(new Event('input',{bubbles:true})) }
    if(drop) await local('drop-once',{path:'/api/archive/add'})
    lastArchive=null; await click('保存档案')
    if(drop){ await wait(()=>text().includes('无法连接档案服务'),'archive ambiguous failure'); await click('保存档案') }
    await wait(()=>text().includes('档案已保存'),'archive saved')
    assert(lastArchive?.vehicle_id===vehicleId,'档案保存属于所选车辆 '+title)
    await click('返回查看档案'); await wait(()=>text().includes(title),'archive appears')
    assert(text().includes('手动录入'),'列表包含手动来源 '+title)
    return lastArchive.archive_id
  }
  try {
    assert(typeof original.request === 'function', 'H5发布产物包含原生请求API')
    await click('微信登录'); await wait(()=>button('进入车主首页'),'real owner login')
    await click('进入车主首页'); await wait(()=>text().includes('我的车辆'),'owner home')
    assert(Boolean(token),'真实微信会话与后端连接')
    await new Promise((resolve,reject)=>uni.switchTab({url:'/pages/archive/index',success:resolve,fail:reject}))
    await wait(()=>text().includes('我的车辆'),'archive tab')
    const before=await local('database')
    const writeStart=(await local('evidence')).writes.length
    stage='vehicle A'
    const stamp=Date.now(), mileageA=stamp%10000000, mileageB=mileageA+1, suffix=String(stamp).slice(-5), plateA='京A'+suffix, plateB='京B'+suffix
    const vehicleA=await createVehicle(mileageA,plateA,true)
    stage='archive A'
    selectMileage(mileageA); await wait(()=>button('手动录入'),'selected A')
    const titleA='合成联调A '+suffix
    const archiveA=await createArchive(vehicleA,titleA,true)
    stage='vehicle B'
    const vehicleB=await createVehicle(mileageB,plateB,false)
    selectMileage(mileageB); await wait(()=>button('手动录入'),'selected B')
    stage='archive B'
    const titleB='合成联调B '+suffix, archiveB=await createArchive(vehicleB,titleB,false)
    stage='shared selection and summary'
    selectMileage(mileageA); await wait(()=>text().includes(titleA) && !text().includes(titleB),'A record isolation')
    assert(true,'切换车辆隔离档案内容')
    await new Promise((resolve,reject)=>uni.switchTab({url:'/pages/home/index',success:resolve,fail:reject}))
    await wait(()=>document.querySelector('[data-testid="home-archive-summary"]')?.textContent.includes(titleA),'A summary')
    assert(document.querySelector('[data-testid="home-archive-summary"]').textContent.includes('已记录 1 条养护档案'),'首页A真实总数及最近记录')
    selectMileage(mileageB); await wait(()=>document.querySelector('[data-testid="home-archive-summary"]')?.textContent.includes(titleB),'B summary')
    assert(!document.querySelector('[data-testid="home-archive-summary"]').textContent.includes(titleA),'首页切车清除旧摘要')
    await new Promise((resolve,reject)=>uni.switchTab({url:'/pages/archive/index',success:resolve,fail:reject}))
    await wait(()=>document.querySelector('[data-testid="archive-records"]')?.textContent.includes(titleB),'B selection retained')
    assert(true,'首页与档案Tab共用所选车辆')
    stage='API integrity and ownership'
    const page=await api('/api/archive/list?vehicle_id='+vehicleA+'&page=1&page_size=1')
    assert(page.status===200 && page.body.data.total===1 && page.body.data.list[0].archive_id===archiveA,'真实按车分页与记录总数')
    const foreign=await api('/api/archive/list?vehicle_id=9100690')
    assert(foreign.status===404,'真实车主读取合成他人车辆被拒')
    const foreignWrite=await api('/api/archive/add','POST',{vehicle_id:9100690,archive_type:1,recorded_date:'2026-10-06',title:'应拒绝的跨车主记录'},crypto.randomUUID())
    assert(foreignWrite.status===404,'真实车主写入合成他人车辆被拒')
    const evidence=await local('evidence')
    evidence.writes=evidence.writes.slice(writeStart)
    for(const route of ['/api/vehicle/add','/api/archive/add']){
      const first=evidence.writes.find(w=>w.route===route && w.dropped)
      const retry=evidence.writes.find(w=>w.route===route && w.key===first?.key && !w.dropped && w.status===200)
      assert(Boolean(first && retry && first.hash===retry.hash),'提交成功丢响应后原键原文重试 '+route)
    }
    const archiveWrite=evidence.writes.find(w=>w.route==='/api/archive/add' && w.dropped)
    const changed=await api('/api/archive/add','POST',{vehicle_id:vehicleA,archive_type:1,recorded_date:'2026-10-06',title:'不同正文'},archiveWrite.key)
    assert(changed.status===400,'同一幂等键不同正文被拒')
    const after=await local('database')
    assert(after.vehicles-before.vehicles===2 && after.vehicleAudits-before.vehicleAudits===2,'两辆车辆落库与成功审计一致且无重复')
    assert(after.archives-before.archives===2 && after.archiveAudits-before.archiveAudits===2,'两条档案落库与成功审计一致且失败不留业务记录')
    return {passed:checks.length,checks,vehicleIds:[vehicleA,vehicleB],archiveIds:[archiveA,archiveB],databaseDelta:{vehicles:2,archives:2,vehicleAudits:2,archiveAudits:2},boundary:'H5 actual UI, real DevTools code, real local backend; foreign owner is synthetic, not a second real WeChat identity'}
  } catch(error) {
    return {failed:true,stage,reason:error.message,passed:checks.length,checks}
  } finally { uni.login=original.login; uni.request=original.request }
})()

