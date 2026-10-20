import test from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {previewResponse,PREVIEW_ORIGIN} from '../preview/samples.js'
import {createLocalPreviewRuntime} from '../preview/runtime.js'
import {localPreviewPlugin} from '../../../scripts/local-preview-plugin.mjs'
import {createVehicleApi} from '../src/services/vehicles.js'
import {createArchiveApi} from '../src/services/archives.js'
import {createServiceCatalogApi} from '../src/services/service-catalog.js'
import {createReservationsApi} from '../src/services/reservations.js'
import {createMerchantOrdersApi} from '../src/services/merchant-orders.js'
import {createDispatchApi} from '../src/services/technician-dispatch.js'
import {createServiceWorkspaceApi} from '../src/services/service-workspace.js'
import {createWorkApi} from '../src/services/service-work.js'
import {createQuotesApi} from '../src/services/merchant-quotes.js'
import {createMerchantStaffApi} from '../src/services/merchant-staff.js'
import {createMerchantProfileApi} from '../src/services/merchant-profile.js'
import {createCouponsApi} from '../src/services/coupons.js'
import {validHealthDashboard} from '../src/services/health-dashboard.js'
import {validBusinessDashboard} from '../src/services/business-dashboard.js'

const rt={request(o){o.success(previewResponse(o.url,o.method,o.data))}}
const options={baseUrl:PREVIEW_ORIGIN,runtime:()=>rt}
const token='synthetic-not-a-jwt'
test('preview owner samples pass real vehicle, archive, service and order contracts',async()=>{
  assert.equal((await createVehicleApi(options).list(token)).list.length,1)
  assert.equal((await createArchiveApi(options).list(token,1)).list.length,3)
  assert.equal((await createServiceCatalogApi(options).list(token,0)).items.length,3)
  assert.equal((await createReservationsApi(options).list(token)).items.length,5)
  assert.ok(validHealthDashboard(previewResponse(PREVIEW_ORIGIN+'/api/vehicle/1/health-dashboard').data.data,1))
})
test('preview merchant and technician samples pass existing business contracts',async()=>{
  assert.equal((await createMerchantOrdersApi(options).list(token)).items.length,5)
  assert.equal((await createMerchantOrdersApi(options).detail(token,3)).status,'IN_SERVICE')
  assert.equal((await createDispatchApi(options).orders(token)).items.length,3)
  assert.equal((await createDispatchApi(options).order(token,3)).assignment_status,'ACCEPTED')
  assert.equal((await createQuotesApi(options).ownList(token)).items.length,3)
  assert.equal((await createMerchantProfileApi(options).read(token)).merchant_id,1)
  assert.equal((await createMerchantStaffApi(options).list(token)).items.length,2)
  for(const role of ['merchant','tech']){
    assert.ok((await createServiceWorkspaceApi(options).bench(token,role)).tasks.items.length)
    assert.equal((await createServiceWorkspaceApi(options).context(token,role,3)).arrival_mileage,32500)
    assert.equal((await createWorkApi(options).detail(token,role,3)).protection.photo_file_id,501)
  }
  assert.ok(validBusinessDashboard(previewResponse(PREVIEW_ORIGIN+'/api/merchant/business/dashboard?period_month=2026-10').data.data))
})
test('preview coupons use existing owner and merchant contracts',async()=>{
  const api=createCouponsApi(options)
  for(const mode of ['mine','offers','merchant']) assert.equal((await api.list(token,mode,null,1)).items.length,1)
})
test('read-only preview blocks writes and arbitrary origins without network fallback',async()=>{
  let network=0,toasts=0
  const runtime=createLocalPreviewRuntime({getSystemInfoSync:()=>({platform:'devtools'}),request(){network++},showToast(){toasts++}})
  for(const url of [PREVIEW_ORIGIN+'/api/order/create','https://example.com/api/vehicle/list']){
    const response=await new Promise(resolve=>runtime.request({url,method:'POST',success:resolve}))
    assert.notEqual(response.statusCode,200)
  }
  await new Promise(resolve=>runtime.uploadFile({success:()=>assert.fail('upload must be denied'),fail:resolve}))
  assert.equal(network,0)
  assert.equal(toasts,3)
  const device=createLocalPreviewRuntime({getSystemInfoSync:()=>({platform:'ios'})})
  assert.equal((await new Promise(resolve=>device.request({url:PREVIEW_ORIGIN+'/api/vehicle/list',success:resolve}))).statusCode,403)
})
test('normal builds omit preview plugin, isolated builds reject unsafe output or network configuration',()=>{
  assert.equal(localPreviewPlugin({}),null)
  const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../../..')
  const env={AUTOCARE_LOCAL_PREVIEW:'1',UNI_PLATFORM:'mp-weixin',UNI_OUTPUT_DIR:path.join(root,'.cache/local-preview/mp-weixin')}
  assert.equal(localPreviewPlugin(env).name,'isolated-local-role-preview')
  for(const patch of [{UNI_OUTPUT_DIR:path.join(root,'apps/miniapp/dist/build/mp-weixin')},{UNI_PLATFORM:'h5'},{VITE_API_BASE_URL:'https://real.example'},{VITE_WECHAT_CLOUD_ENV_ID:'live'}]) assert.throws(()=>localPreviewPlugin({...env,...patch}))
})
test('preview recovery storage never reads or changes real application drafts',()=>{
  const real={getStorageSync(){assert.fail('must not read real drafts')},setStorageSync(){assert.fail('must not persist demo records')},removeStorageSync(){assert.fail('must not remove real drafts')}}
  const runtime=createLocalPreviewRuntime(real)
  assert.equal(runtime.getStorageSync('preview-test'),undefined)
  const draft={value:1};runtime.setStorageSync('preview-test',draft);draft.value=2
  assert.deepEqual(runtime.getStorageSync('preview-test'),{value:1})
  runtime.removeStorageSync('preview-test');assert.equal(runtime.getStorageSync('preview-test'),undefined)
})
