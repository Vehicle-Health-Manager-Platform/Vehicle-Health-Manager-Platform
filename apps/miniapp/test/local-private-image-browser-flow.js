// gstack browse eval; real native H5 picker/upload/preview and real backend.
// A synthetic PNG is supplied to the file input; this is NOT a real camera test.
// The signed-byte bridge trusts only the local certificate and avoids system trust changes.
return await (async()=>{
  if(location.origin!=='http://127.0.0.1:4317')throw new Error('Local acceptance origin required')
  const original={login:uni.login,request:uni.request,uploadFile:uni.uploadFile,chooseImage:uni.chooseImage,chooseMedia:uni.chooseMedia,previewImage:uni.previewImage}
  const checks=[],uploads=[],sources=[],previews=[],blobs=[],choices=[]
  let stage='login',token='',lastFile=null,lastArchive=null
  const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms))
  const text=()=>document.body.textContent
  const check=(ok,label)=>{if(!ok)throw new Error(label);checks.push(label)}
  const wait=async(fn,label)=>{for(let i=0;i<150;i++){if(fn())return;await sleep(100)}throw new Error('Timeout: '+label)}
  const button=label=>[...document.querySelectorAll('uni-button')].find(e=>e.textContent.trim()===label&&!e.hasAttribute('disabled')&&e.getClientRects().length)
  const click=async label=>{await wait(()=>button(label),'button '+label);button(label).click();await sleep(150)}
  const local=async(action,body={})=>{const r=await fetch('/__local/'+action,{method:'POST',headers:{'X-Local-Business':'1','Content-Type':'application/json'},body:JSON.stringify(body)});if(!r.ok)throw new Error('Local bridge unavailable');return r.json()}
  uni.login=o=>local('code').then(r=>o.success({code:r.code})).catch(()=>o.fail({errMsg:'Local code bridge failed'}))
  uni.request=o=>original.request({...o,url:o.url.replace('http://127.0.0.1:18080',location.origin),success:r=>{if(o.header?.Authorization)token=o.header.Authorization;if(o.url.endsWith('/api/archive/add')&&r.statusCode===200&&r.data?.code===0)lastArchive=r.data.data;o.success?.(r)}})
  uni.chooseImage=o=>{sources.push(o.sourceType);return original.chooseImage({...o,success:r=>{choices.push({result:'success',count:r.tempFiles?.length,size:r.tempFiles?.[0]?.size});o.success?.(r)},fail:e=>{choices.push({result:'fail',cancel:String(e.errMsg).includes('cancel')});o.fail?.(e)}})}
  // 页面优先 chooseMedia；两条都覆盖，真实拍摄仍由真机验收，这里只是 H5 合成 PNG。
  uni.chooseMedia=uni.chooseImage
  uni.uploadFile=o=>{
    uploads.push({key:o.header['Idempotency-Key'],path:o.filePath})
    return original.uploadFile({...o,url:o.url.replace('http://127.0.0.1:18080',location.origin),success:r=>{try{const b=JSON.parse(r.data);if(r.statusCode===200&&b.code===0)lastFile=b.data.file_id}catch{}o.success?.(r)}})
  }
  uni.previewImage=o=>{
    fetch('/__local/image',{method:'POST',headers:{'X-Local-Business':'1','Content-Type':'application/json'},body:JSON.stringify({url:o.current})}).then(async r=>{
      if(!r.ok)throw new Error('Signed bytes unavailable')
      const bytes=await r.blob(),url=URL.createObjectURL(bytes);blobs.push(url);previews.push(bytes.size)
      original.previewImage({...o,urls:[url],current:url})
    }).catch(()=>o.fail?.({errMsg:'Local signed preview failed'}))
  }
  function input(index,value){const e=document.querySelectorAll('[data-testid="archive-record-add"] uni-input input')[index];if(!e)throw new Error('Missing archive input');e.value=value;e.dispatchEvent(new Event('input',{bubbles:true}))}
  async function chooseFixture(label){
    const nativeClick=HTMLInputElement.prototype.click
    // Headless Chromium cancels an OS dialog immediately. Suppress only that
    // dialog, then supply the fixture to uni-app's unchanged native input handler.
    HTMLInputElement.prototype.click=function(){if(this.type!=='file')return nativeClick.call(this)}
    try{
      await click(label);await wait(()=>document.querySelector('input[type=file]'),'native file input')
      const data=Uint8Array.from(atob('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aSkcAAAAASUVORK5CYII='),c=>c.charCodeAt(0))
      const transfer=new DataTransfer();transfer.items.add(new File([data],'local-archive-fixture.png',{type:'image/png'}))
      const picker=document.querySelector('input[type=file]');picker.files=transfer.files;picker.dispatchEvent(new Event('change',{bubbles:true}))
    }finally{HTMLInputElement.prototype.click=nativeClick}
  }
  async function preview(){
    const count=previews.length;await click('预览');await wait(()=>previews.length===count+1,'real signed download')
    await wait(()=>[...document.querySelectorAll('img')].some(e=>e.src.startsWith('blob:')&&e.complete&&e.naturalWidth>0),'native image decoded')
    check(previews.at(-1)===68,'实际预览取得原始PNG字节 '+(count+1));uni.closePreviewImage();await sleep(200)
  }
  try{
    check(['request','uploadFile','chooseImage','previewImage'].every(k=>typeof original[k]==='function'),'H5发布产物图片API齐全')
    await click('微信登录');await wait(()=>button('进入车主首页'),'real login')
    await click('进入车主首页');await new Promise((resolve,reject)=>uni.switchTab({url:'/pages/archive/index',success:resolve,fail:reject}))
    await wait(()=>document.querySelector('[data-testid="vehicle-list"] .vehicle'),'owned vehicle list')
    document.querySelector('[data-testid="vehicle-list"] .vehicle').click()
    await click('拍照录入');await wait(()=>text().includes('新增车辆档案'),'photo form')
    check(!button('保存档案'),'拍照模式无图不可提交')
    input(0,'本地图片UI拍照方式 '+Date.now());input(1,'1200')
    stage='photo upload retry'
    await local('drop-once',{path:'/api/file/upload'});await chooseFixture('拍照并上传')
    await wait(()=>button('使用原图片重试上传'),'ambiguous upload failure')
    await click('使用原图片重试上传');await wait(()=>text().includes('图片 1 已上传'),'upload recovered')
    check(uploads.length===2&&uploads[0].key===uploads[1].key&&uploads[0].path===uploads[1].path,'提交后丢响应使用原图原键重试')
    check(sources[0].length===1&&sources[0][0]==='camera','拍照模式请求相机来源')
    await preview();await click('保存档案');await wait(()=>text().includes('档案已保存'),'photo saved')
    const photoId=lastArchive.archive_id,photoFile=lastFile
    await click('返回查看档案');await wait(()=>text().includes('本地图片UI拍照方式'),'photo list')
    check(text().includes('拍照录入'),'实际列表包含拍照来源')
    stage='manual image archive'
    await click('手动录入');await wait(()=>text().includes('新增车辆档案'),'manual form')
    input(0,'本地图片UI手动方式 '+Date.now());input(1,'1200')
    await chooseFixture('选择并上传图片');await wait(()=>text().includes('图片 1 已上传'),'manual upload')
    check(sources[1].includes('album')&&sources[1].includes('camera'),'手动模式保留相册与相机')
    await preview();await click('保存档案');await wait(()=>text().includes('档案已保存'),'manual saved')
    const manualId=lastArchive.archive_id,manualFile=lastFile
    await click('返回查看档案');await wait(()=>text().includes('本地图片UI手动方式'),'manual list')
    const selected=document.querySelector('[data-testid="archive-records"] .heading')?.textContent
    await new Promise((resolve,reject)=>uni.switchTab({url:'/pages/home/index',success:resolve,fail:reject}))
    await wait(()=>document.querySelector('[data-testid="home-archive-summary"]')?.textContent.includes('本地图片UI手动方式'),'home summary')
    check(document.querySelector('[data-testid="home-archive-summary"]').textContent.includes('手动录入'),'首页最近记录展示真实来源')
    const writes=(await local('evidence')).writes.filter(w=>w.route==='/api/file/upload')
    check(writes.some(w=>w.dropped&&w.key===uploads[0].key)&&writes.some(w=>!w.dropped&&w.key===uploads[0].key&&w.status===200),'真实上传提交后截断响应与重放证据')
    check(Boolean(token&&selected),'实际本人车辆与后端会话贯通')
    return{passed:checks.length,checks,fileIds:[photoFile,manualFile],archiveIds:[photoId,manualId],boundary:'Native H5 picker supplied synthetic file, real upload and TLS-verified signed bytes in native preview; physical camera, mobile HTTPS and second real identity pending'}
  }catch(e){return{failed:true,stage,reason:e.message,passed:checks.length,checks,uploadCount:uploads.length,choices,route:location.hash,pageIds:[...document.querySelectorAll('[data-testid]')].map(e=>e.getAttribute('data-testid')),feedback:[...document.querySelectorAll('[data-testid="archive-record-add"] .status')].map(e=>e.textContent)}}
  finally{Object.assign(uni,original);uni.closePreviewImage?.();for(const url of blobs)URL.revokeObjectURL(url)}
})()
