// gstack eval: real H5 controls/WeChat owner/backend/MySQL. Merchant login
// response is the explicit isolated synthetic bridge; no payment is simulated.
return await (async()=>{
  if(location.origin!=='http://127.0.0.1:4317')throw new Error('Loopback harness required')
  const original={request:uni.request,login:uni.login},checks=[];let merchant='',owner='',stage='merchant',published=null,created=null,extraOrder=null
  const sleep=ms=>new Promise(r=>setTimeout(r,ms)),text=()=>document.body.innerText
  const assert=(ok,label)=>{if(!ok)throw new Error(label);checks.push(label)}
  const wait=async(fn,label)=>{for(let i=0;i<150;i++){if(fn())return;await sleep(100)}throw new Error('Timeout: '+label)}
  const button=label=>[...document.querySelectorAll('uni-button,.uni-modal__btn')].find(el=>el.textContent.trim()===label&&el.getClientRects().length&&!el.hasAttribute('disabled'))
  const click=async label=>{await wait(()=>button(label),label);button(label).click();await sleep(150)}
  const local=async(path,body={})=>{const r=await fetch('/__local/'+path,{method:'POST',headers:{'Content-Type':'application/json','X-Local-Business':'1',Authorization:owner||merchant},body:JSON.stringify(body)});if(!r.ok)throw new Error('Local helper unavailable');return r.json()}
  const api=async(path,token,body,key)=>{const r=await fetch(path,{method:body?'POST':'GET',headers:{Authorization:token,...(body?{'Content-Type':'application/json','Idempotency-Key':key}:{})},body:body?JSON.stringify(body):undefined});return {status:r.status,body:await r.json()}}
  const inputs=()=>[...document.querySelectorAll('uni-input input')].filter(el=>el.getClientRects().length)
  const input=(el,value)=>{el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}))}
  uni.login=o=>local('code').then(r=>o.success?.({code:r.code})).catch(()=>o.fail?.({errMsg:'Local code unavailable'}))
  uni.request=o=>{
    if(o.url.endsWith('/api/auth/merchant/login')){local('merchant',{store:'A'}).then(data=>{merchant='Bearer '+data.access_token;o.success?.({statusCode:200,data:{code:0,data}})}).catch(()=>o.fail?.({errMsg:'Synthetic merchant unavailable'}));return{abort(){}}}
    if(!o.url.startsWith('http://127.0.0.1:18080/'))throw new Error('Unexpected backend')
    return original.request({...o,url:o.url.replace('http://127.0.0.1:18080',location.origin),success:r=>{if(r.statusCode===200&&r.data?.data?.access_token)owner='Bearer '+r.data.data.access_token;if(r.statusCode===200&&o.method==='POST'&&o.url.endsWith('/api/merchant/slots'))published=r.data.data;if(r.statusCode===200&&o.url.endsWith('/api/order/create'))created=r.data.data;o.success?.(r)}})
  }
  try{
    await wait(()=>inputs().length===3,'merchant form');['local-quotes-merchant-A','synthetic-password','123456'].forEach((v,i)=>input(inputs()[i],v));await click('登录商家端');await wait(()=>text().includes('商家登录成功'),'merchant session')
    const previous=await api('/api/merchant/slots',merchant),used=new Set(previous.body.data.items.map(r=>r.standard_project_id));let project=9101101;while(used.has(project)&&project<9101120)project++
    const quoteWrite=await api('/api/merchant/projects',merchant,{standard_project_id:project,price:'129.00',status:1},crypto.randomUUID());assert(quoteWrite.status===200,'真实本店在售项目准备')
    const quoteId=quoteWrite.body.data.merchant_project_id
    await click('管理本店服务');await click('管理预约时段');await wait(()=>document.querySelector(`[data-testid="slot-project-${project}"]`),'project selection');document.querySelector(`[data-testid="slot-project-${project}"]`).click();await wait(()=>inputs().length===1,'capacity input');input(inputs()[0],'2');await click('发布预约时段');await wait(()=>published,'real slot saved')
    assert(published.capacity===2&&published.open,'商家页面真实发布项目时段和容量')
    assert((await api('/api/merchant/slots',merchant,{standard_project_id:project,starts_at:published.starts_at,ends_at:published.ends_at,capacity:2},crypto.randomUUID())).body.code===40904,'同店同项目重叠时段拒绝')
    stage='owner';uni.reLaunch({url:'/pages/owner/index'});await click('微信登录');await wait(()=>owner&&text().includes('身份验证成功'),'real WeChat owner')
    let cars=await api('/api/vehicle/list',owner);if(!cars.body.data.list.length){await api('/api/vehicle/add',owner,{add_type:4,model_id:9100601,current_mileage:1,plate_no:'',vin:''},crypto.randomUUID());cars=await api('/api/vehicle/list',owner)}const car=cars.body.data.list[0].vehicle_id
    const before=await local('reservation-database')
    uni.navigateTo({url:`/pages/order/book?id=${quoteId}`});await wait(()=>document.querySelector(`[data-testid="booking-vehicle-${car}"]`),'real owner vehicles')
    document.querySelector(`[data-testid="booking-vehicle-${car}"]`).click();await click('确认当前报价')
    // Supply the selected date to H5's unchanged native date-input handler.
    const nativeDate=document.querySelector('uni-picker input[type=date]');if(!nativeDate)throw new Error('Native date input required')
    nativeDate.value=new Date(Date.parse(published.starts_at)+8*3600000).toISOString().slice(0,10);nativeDate.dispatchEvent(new Event('change',{bubbles:true}))
    await wait(()=>document.querySelector(`[data-testid="booking-slot-${published.slot_id}"]`),'published appointment slot')
    document.querySelector(`[data-testid="booking-slot-${published.slot_id}"]`).click();await local('drop-once',{path:'/api/order/create'});await click('创建待支付预约');await wait(()=>text().includes('无法连接服务'),'response lost after real commit');await click('创建待支付预约');await wait(()=>created&&document.querySelector('[data-testid="order-detail"]'),'same-key booking receipt')
    const after=await local('reservation-database');assert(after.orders===before.orders+1&&after.createAudits===before.createAudits+1,'提交后断网原键重试只创建一个订单及一次审计')
    assert(text().includes('应付 ¥129.00')&&text().includes('待支付')&&text().includes('正式微信支付尚未配置'),'真实订单快照、金额与待支付边界展示')
    const evidence=await local('evidence'),writes=evidence.writes.filter(r=>r.route==='/api/order/create').slice(-2);assert(writes.length===2&&writes[0].key===writes[1].key&&writes[0].hash===writes[1].hash,'预约页面重试保留原键原正文')
    stage='payments';const beforePayments=await local('payment-database');await click('微信支付');await wait(()=>text().includes('正式微信支付尚未配置，当前不可支付'),'unconfigured real channel');assert((await local('payment-database')).payments===beforePayments.payments,'未配置正式微信支付不生成支付记录')
    const paymentKey=crypto.randomUUID();const p=(await api('/api/payments/create',owner,{order_id:created.order_id,channel:'LOCAL_TEST'},paymentKey)).body.data
    const replay=(await api('/api/payments/create',owner,{order_id:created.order_id,channel:'LOCAL_TEST'},paymentKey)).body.data
    assert(p?.status==='PENDING'&&p.test_mode&&p.payment_id===replay.payment_id,'隔离测试发起原键重试复用同一支付记录')
    assert((await local('payment-notice',{payment_id:p.payment_id,status:'FAILED'})).status===200,'真实签名测试失败通知提交')
    await click('刷新订单状态');await wait(()=>text().includes('支付失败，可在订单有效期内重新发起。'),'failed payment summary');assert(text().includes('测试支付，未真实扣款')&&text().includes('重新发起微信支付'),'失败状态及测试标识来自真实支付查询')
    const second=(await api('/api/payments/create',owner,{order_id:created.order_id,channel:'LOCAL_TEST'},crypto.randomUUID())).body.data;assert(second.payment_id!==p.payment_id,'失败后新键生成新的测试支付')
    assert((await local('payment-notice',{payment_id:second.payment_id,status:'SUCCEEDED',invalid_signature:true})).status===401,'无效签名不接受成功通知')
    assert((await local('payment-notice',{payment_id:second.payment_id,status:'SUCCEEDED',wrong_amount:true})).status===400,'签名正确但金额错误拒绝')
    const event=crypto.randomUUID(),occurred=new Date().toISOString(),notice={payment_id:second.payment_id,status:'SUCCEEDED',event_id:event,occurred_at:occurred};assert((await local('payment-notice',notice)).ack==='SUCCESS','有效签名成功通知真实提交')
    await click('刷新订单状态');await wait(()=>text().includes('已支付待接车'),'paid UI');assert(text().includes('测试支付，未真实扣款，不可作为收款凭据')&&!button('取消待支付预约'),'测试已支付页面明确标识且禁止未支付取消')
    await local('payment-notice',notice);await local('payment-notice',{...notice,event_id:crypto.randomUUID()});const afterPayments=await local('payment-database');assert(afterPayments.paidAudits===beforePayments.paidAudits+1,'同事件和不同事件重复通知均不重复订单成功审计')
    assert((await api('/api/order/cancel',owner,{order_id:created.order_id},crypto.randomUUID())).status===400,'已支付订单服务端拒绝未支付取消')
    await click('返回我的订单');await click('已支付');await wait(()=>document.querySelector(`[data-testid="order-${created.order_id}"]`),'paid filter');assert(text().includes('测试支付，未真实扣款'),'已支付过滤保留测试支付标识')
    stage='late';const next=(await api('/api/order/create',owner,{merchant_project_id:quoteId,quote_version_id:(await api('/api/order/quote/'+quoteId,owner)).body.data.quote_version_id,vehicle_id:car,slot_id:published.slot_id},crypto.randomUUID())).body.data
    extraOrder=next
    const late=(await api('/api/payments/create',owner,{order_id:next.order_id,channel:'LOCAL_TEST'},crypto.randomUUID())).body.data
    assert((await api('/api/order/cancel',owner,{order_id:next.order_id},crypto.randomUUID())).status===200,'第二笔待支付订单本人取消')
    assert((await local('payment-notice',{payment_id:late.payment_id,status:'SUCCEEDED'})).status===200,'取消后成功通知记录异常而不恢复订单')
    uni.navigateTo({url:`/pages/order/detail?id=${next.order_id}`});await wait(()=>text().includes('支付异常待核对，尚未执行退款'),'late receipt review');assert(text().includes('已关闭')&&text().includes('测试支付，未真实扣款'),'迟到付款异常与关闭状态明确展示')
    assert((await local('payment-database')).exceptions===beforePayments.exceptions+1&&(await local('reservation-database')).reserved===before.reserved+1,'异常只记录一次且仅保留已支付预约名额')
    assert((await api('/api/payments/'+second.payment_id,merchant)).status===403,'商家无权访问本人支付接口');assert((await fetch('/api/payments/'+second.payment_id)).status===401,'匿名支付查询拒绝')
    return {passed:checks.length,checks,boundary:'Real WeChat owner/H5/backend/MySQL; explicit LOCAL_TEST signed notices; no real debit/refund/SMS/device acceptance'}
  }catch(error){return {failed:true,stage,reason:error.message,passed:checks.length,checks}}
  finally{for(const row of [created,extraOrder])if(row&&owner){const current=await api('/api/order/'+row.order_id,owner).catch(()=>null);if(current?.body?.data?.status==='PENDING_PAYMENT')await api('/api/order/cancel',owner,{order_id:row.order_id},crypto.randomUUID()).catch(()=>{})}if(owner)await fetch('/api/auth/logout',{method:'POST',headers:{Authorization:owner}}).catch(()=>{});await local('revoke-merchants').catch(()=>{});uni.request=original.request;uni.login=original.login}
})()
