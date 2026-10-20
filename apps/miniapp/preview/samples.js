// Synthetic, read-only fixtures for the isolated developer-tools build only.
export const PREVIEW_ORIGIN = 'https://local-preview.invalid'
const stamp = '2026-10-18T02:00:00Z'
const day = '2026-10-18'
const merchant = {id:1,name:'演示 · 健康汽车服务站',address:'合成测试区域 · 非真实营业地址'}
const projects = [
  {id:1,category:1,project_name:'演示 · 机油机滤保养',base_price_low:'198.00',base_price_high:'398.00',service_content:'更换机油与机滤，检查油液并记录保养里程。',quality_standard:'按车型要求选用机油，复核油位并检查渗漏。'},
  {id:2,category:2,project_name:'演示 · 制动系统检查',base_price_low:'58.00',base_price_high:'128.00',service_content:'检查刹车片、制动盘和制动液，留存检查结果。',quality_standard:'检查结果需经技师确认，异常项目另行沟通。'},
  {id:3,category:3,project_name:'演示 · 轮胎养护',base_price_low:'68.00',base_price_high:'168.00',service_content:'轮胎外观、胎压及磨损情况检查。',quality_standard:'记录胎压与磨损读数。'},
]
const vehicle = {vehicle_id:1,model_id:1,current_mileage:32500,model_name:'演示 · 2020款家用轿车',plate_no_masked:'演A***01',vin_masked:'DEMO*********0001'}
const archives = [
  {archive_id:101,vehicle_id:1,archive_type:1,input_type:3,recorded_date:'2026-10-01',title:'演示 · 更换机油与机滤',notes:'合成记录：完成常规保养，并检查油液。',mileage:32000,file_ids:[]},
  {archive_id:102,vehicle_id:1,archive_type:2,input_type:3,recorded_date:'2026-09-15',title:'演示 · 制动系统检查',notes:'合成记录：检查制动片及制动液。',mileage:31000,file_ids:[]},
  {archive_id:103,vehicle_id:1,archive_type:3,input_type:3,recorded_date:'2026-08-01',title:'演示 · 保险资料',notes:'由本人登记的合成保险记录，不是实际保单。',mileage:null,file_ids:[]},
]
const orderStates = ['PAID','RECEIVED','IN_SERVICE','PENDING_VERIFY','COMPLETED']
function order(id,owner=false) {
  const status = orderStates[(id-1)%orderStates.length]
  const project = projects[(id-1)%projects.length]
  return {order_id:id,order_no:'DEMO-20261018-'+String(id).padStart(3,'0'),status,amount_due:'198.00',created_at:stamp,expires_at:null,closed_at:null,close_reason:null,
    payment_summary:null,has_payment_exception:false,allowed_actions:[],
    project_snapshot:{standard_project_id:project.id,project_name:project.project_name,service_content:project.service_content},
    merchant_snapshot:{merchant_id:1,merchant_name:merchant.name,address:merchant.address},
    appointment_snapshot:{slot_id:id,starts_at:'2026-10-19T02:00:00Z',ends_at:'2026-10-19T03:00:00Z'},
    price_snapshot:{merchant_project_id:1,quote_version_id:1,version:1,price:'198.00'},
    ...(owner?{vehicle_id:1,appointment_code:'012345',verify_code:'012345',test_mode:true}:{}),
  }
}
function job(id) {
  const pending = id===2
  const o = order(id)
  return {assignment_id:100+id,order_id:id,order_no:o.order_no,order_status:pending?'RECEIVED':o.status,
    assignment_status:pending?'ASSIGNED':'ACCEPTED',assigned_at:stamp,accepted_at:pending?null:stamp,can_accept:pending,
    project_snapshot:o.project_snapshot,appointment_snapshot:o.appointment_snapshot}
}
const quote = p => ({merchant_project_id:p.id,version_id:p.id,version:1,price:p.base_price_low,status:1,standard_project_id:p.id,project_name:p.project_name,available:true})
const terms = {name:'演示 · 保养抵扣券',type:'DISCOUNT',project_id:1,amount:'20.00',min_spend:'100.00',total_quantity:50,valid_from:'2026-10-01T00:00:00Z',valid_to:'2026-11-30T00:00:00Z'}
const coupon = {coupon_id:1,merchant_id:1,terms,revision:2,status:'PUBLISHED',stock_left:38,publication_revision:2,pool_selected:true,pool_revision:1}
function queryOf(url) {
  const [path,search=''] = url.split('?')
  const query = Object.fromEntries(search.split('&').filter(Boolean).map(pair=>{const [k,v='']=pair.split('=');return [decodeURIComponent(k),decodeURIComponent(v)]}))
  return {path,q:query}
}
const ok = data => ({statusCode:200,data:{code:0,data:JSON.parse(JSON.stringify(data))}})
const reject = (statusCode,message) => ({statusCode,data:{code:statusCode*100,message}})
const paged = (items,q={},key='items',size=20) => ({[key]:items.slice(((Number(q.page)||1)-1)*size,(Number(q.page)||1)*size),total:items.length,page:Number(q.page)||1,page_size:size})
const filtered = (rows,q,key='status') => rows.filter(r=>!q[key]||r[key]===q[key])
function health() {
  const metrics=['OIL','BRAKE_PAD','TIRE','TRACTION_BATTERY','COOLANT','AUX_BATTERY']
  return {vehicle:{vehicle_id:1,model_id:1,model_name:vehicle.model_name,model_source:'OWNER_REGISTERED',power_type:'UNKNOWN'},today:day,
    overall:{status:'UNKNOWN',score:null,weighted_sum:null,weight_total:null},
    metrics:metrics.map(metric=>({metric,status:'POLICY_NOT_SET',rule_version:0,rule:null,observation:{metric,revision:0,archive_id:null,recorded_date:null,measurement:{},status:'NOT_SET'},confirmation:{revision:0,confirmed:false},score:null,trend:{status:'NOT_AVAILABLE',points:[],delta:null,truncated:false},action_status:'NONE'})),required_projects:[],
    reminders:['FAULT','MAINTENANCE','INSURANCE','INSPECTION','VIOLATION'].map(kind=>({kind,status:'NOT_SET',items:[],limited:false})),
    preference:{maintenance_days:0,maintenance_km:0,insurance_days:0,inspection_days:0,maintenance_project_id:null,revision:0}}
}
function bench(q,tech) {
  const bucketFor={1:'RECEIVE',2:'WAIT_ACCEPT',3:'SERVICE',4:'VERIFY'}
  const rows=(tech?[2,3,4]:[1,2,3,4]).map(id=>({order_id:id,order_status:order(id).status,bucket:bucketFor[id],project_name:order(id).project_snapshot.project_name,starts_at:'2026-10-19T02:00:00Z'}))
  const names=['RECEIVE','CONFIRM','DISPUTE','ASSIGN','WAIT_ACCEPT','SERVICE','VERIFY','REVIEW_SOURCE']
  return {counts:Object.fromEntries(names.map(k=>[k,rows.filter(r=>r.bucket===k).length])),tasks:paged(rows.filter(r=>!q.bucket||r.bucket===q.bucket),q)}
}
function business(month='2026-10') {
  const [year,m]=month.split('-').map(Number)
  const from=new Date(Date.UTC(year,m-1,1)-8*3600000).toISOString().replace('.000Z','Z')
  const to=new Date(Date.UTC(year,m,1)-8*3600000).toISOString().replace('.000Z','Z')
  const money=new Set(['payments','refunds','commission_accrual'])
  const keys=['orders_created','payments','refunds','orders_completed','cash_coupon_uses','service_coupon_uses','new_customers','commission_accrual']
  const observed=stamp<from?from:stamp
  return {merchant,period_month:month,timezone:'Asia/Shanghai',window_from:from,window_to:observed<to?observed:to,observed_at:observed,currency:'CNY',source_version:'R8B_BUSINESS_V1',
    metrics:Object.fromEntries(keys.map(k=>[k,{formal:{state:'KNOWN',count:0,unknown_count:0,excluded_count:0,amount:money.has(k)?'0.00':null},test:{state:'KNOWN',count:0,unknown_count:0,excluded_count:0,amount:money.has(k)?'0.00':null},unclassified_count:0}])),
    cash_difference:{formal:{state:'KNOWN',amount:'0.00'},test:{state:'KNOWN',amount:'0.00'}},assessment:null}
}
export function previewResponse(url,method='GET',body) {
  if (typeof url!=='string'||!url.startsWith(PREVIEW_ORIGIN+'/')) return reject(403,'演示已阻止真实网络请求')
  const {path,q}=queryOf(url.slice(PREVIEW_ORIGIN.length))
  // The only POST is a fixed UI example, never a real AI call or business write.
  if (path==='/api/ai/chat'&&method==='POST') return ok({reply:'【本地固定示例，并非 AI 诊断】实际页面会结合获授权档案显示建议。可先记录发生时间、里程和症状，再到店检查；本演示不调用外部模型。',model:'local-preview-example',grounded:false,vehicle_id:body?.vehicle_id || null})
  if (method!=='GET') return reject(409,'只读演示：此操作不会提交或保存')
  if (path==='/api/vehicle/list') return ok(paged([vehicle],q,'list'))
  if (path==='/api/archive/list') return ok(paged(archives,q,'list'))
  if (path==='/api/brand/list') return ok(paged([{id:1,name:'演示品牌'}],q,'list',100))
  if (path==='/api/series/list') return ok(paged([{id:1,name:'演示车系',brand_id:1}],q,'list',100))
  if (path==='/api/model/list') return ok(paged([{id:1,name:'演示车型',series_id:1,year:'2020',config_name:'演示配置'}],q,'list',100))
  if (path==='/api/service/projects') return ok(paged(projects.filter(p=>!q.category||p.category===Number(q.category)).map(({service_content,quality_standard,...r})=>r),q))
  let match=path.match(/^\/api\/service\/project\/(\d+)$/)
  if (match) return ok(projects.find(p=>p.id===Number(match[1])) || projects[0])
  if (path==='/api/merchant/projects') return ok(paged(projects.map(quote),q))
  if (path==='/api/merchant/standard-projects') return ok(paged(projects.map(({service_content,quality_standard,...r})=>r),q))
  if (path==='/api/merchant/slots'||path==='/api/order/slots') return ok(paged(projects.map(p=>({slot_id:p.id,standard_project_id:p.id,project_name:p.project_name,starts_at:'2026-10-19T02:00:00Z',ends_at:'2026-10-19T03:00:00Z',capacity:5,capacity_left:3,open:true})),q))
  match=path.match(/^\/api\/service\/project\/(\d+)\/merchants$/)
  if (match) return ok(paged([{...quote(projects.find(p=>p.id===Number(match[1]))||projects[0]),merchant_id:1,merchant_name:merchant.name,address:merchant.address}],q))
  match=path.match(/^\/api\/order\/quote\/(\d+)$/)
  if (match) {const p=projects.find(p=>p.id===Number(match[1]))||projects[0];return ok({...quote(p),quote_version_id:p.id,merchant_id:1,merchant_name:merchant.name,address:merchant.address})}
  if (path==='/api/order/list') return ok(paged(filtered([1,2,3,4,5].map(id=>order(id,true)),q),q))
  if (path==='/api/merchant/orders') return ok(paged(filtered([1,2,3,4,5].map(id=>order(id)),q),q))
  match=path.match(/^\/api\/(merchant\/orders|order)\/(\d+)$/)
  if (match) return ok(order(Number(match[2]),match[1]==='order'))
  if (path==='/api/tech/orders') return ok(paged(filtered([2,3,4].map(job),q,'assignment_status'),q))
  match=path.match(/^\/api\/tech\/orders\/(\d+)$/)
  if (match) return ok(job(Number(match[1])))
  if (path==='/api/merchant/technicians') return ok(paged([{technician_id:9003,label:'演示技师 · 小林'}],q))
  match=path.match(/^\/api\/merchant\/orders\/(\d+)\/assignment$/)
  if (match) {const id=Number(match[1]);return ok({assignment:id===1?null:{assignment_id:100+id,order_id:id,technician_id:9003,status:id===2?'ASSIGNED':'ACCEPTED',technician_label:'演示技师 · 小林',assigned_at:stamp,accepted_at:id===2?null:stamp}})}
  if (/^\/api\/(merchant|tech)\/workbench$/.test(path)) return ok(bench(q,path.includes('/tech/')))
  match=path.match(/^\/api\/(merchant|tech)\/orders\/(\d+)\/work$/)
  if (match) return ok({order_id:Number(match[2]),order_status:order(Number(match[2])).status,protection:{items:['SEAT_COVER','STEERING_COVER','FLOOR_MAT'],uploaded_at:stamp,photo_file_id:501},report:null})
  match=path.match(/^\/api\/(merchant|tech)\/orders\/(\d+)\/service-context$/)
  if (match) return ok({order_id:Number(match[2]),order_status:order(Number(match[2])).status,vehicle:{brand:'演示品牌',series:'演示车系',year:'2020',config_name:'家用配置'},project:{standard_project_id:1,project_name:projects[0].project_name,service_content:projects[0].service_content},arrival_mileage:32500,archives:paged([{archive_id:101,state:'ACTIVE',record:{archive_id:101,archive_type:1,recorded_date:'2026-10-01',title:archives[0].title,notes:archives[0].notes,mileage:32000,fields:{service_items:['演示机油','演示机滤']},files:[]}}],q)})
  if (path==='/api/merchant/profile') return ok({merchant_id:1,name:merchant.name,address:merchant.address,contact_phone:'13800000000',lng:null,lat:null,merchant_type:2,region_code:'',status:1,can_edit:true})
  if (path==='/api/merchant/staff') return ok(paged(filtered([
    {staff_id:9002,account:'demo-frontdesk',role:'STAFF',display_name:'演示店员 · 小李',phone_masked:'138****0000',status:'ACTIVE',employee_code_issued:false,wechat_bound:false,created_at:stamp},
    {staff_id:9003,account:'demo-technician',role:'TECHNICIAN',display_name:'演示技师 · 小林',phone_masked:null,status:'ACTIVE',employee_code_issued:true,wechat_bound:true,created_at:stamp},
  ],q,'role'),q))
  if (path==='/api/coupon/mine') return ok(paged([{user_coupon_id:1,coupon_id:1,merchant_id:1,source_known:true,terms,status:'AVAILABLE',availability:'AVAILABLE',publication_revision:2}],q))
  if (path==='/api/coupon/offers'||path==='/api/merchant/coupons') return ok(paged([coupon],q))
  if (path==='/api/vehicle/health-observations') return ok({vehicle_id:1,today:day,list:health().metrics.map(m=>m.observation)})
  if (path==='/api/vehicle/reminders') return ok({vehicle_id:1,today:day,current_mileage:32500,list:[1,3,7].map(archive_type=>({archive_type,revision:0,active:false,archive_id:null,recorded_date:null,due_date:null,due_mileage:null,status:'NOT_SET',triggered_by:[]}))})
  match=path.match(/^\/api\/archive\/(\d+)\/structured$/)
  if (match) {const a=archives.find(a=>a.archive_id===Number(match[1])) || archives[0];return ok({vehicle_id:1,archive_id:a.archive_id,archive_type:a.archive_type,recorded_date:a.recorded_date,revision:0,fields:{},status:'NOT_SET'})}
  if (path==='/api/invite/reward-policy') return ok({scope:'OWNER',state:'UNKNOWN',policy:null})
  if (path==='/api/invite/diagnosis-eligibility') return ok({month:'2026-10',timezone:'Asia/Shanghai',qualified_count:0,state:'UNKNOWN',policy:null,usable_until:null,service_available:false,rule_version:'R6B_DIAGNOSIS_V1'})
  if (/^\/api\/(invite|merchant\/invite)\/progress$/.test(path)) return ok({qualified_count:0,completed_cycles:0,tier:1,tier_count:0,tier_target:1,next_threshold:1,month:'2026-10',timezone:'Asia/Shanghai',verified_identity_enabled:false,rule_version:'R6A_INVITE_V1',pending_total:0,unknown_total:0,test_total:0,rejected_total:0})
  if (/^\/api\/(invite|merchant\/invite)\/(records|rewards)$/.test(path)) return ok(paged([],q))
  if (/^\/api\/(invite\/qrcode|merchant\/invite\/code)$/.test(path)) return ok({code:{value:'a'.repeat(32),source_type:path.includes('/merchant/')?'STAFF':'OWNER',created_at:stamp,landing_path:'/pages/welfare/invite-entry?code='+'a'.repeat(32),rule_version:'R6A_INVITE_V1'}})
  if (path==='/api/invite/registration') return ok({registration:null})
  if (path==='/api/merchant/location') return ok({location:null,can_edit:true})
  if (path==='/api/check/pickup/context') return ok({order_id:Number(q.order_id),order_status:order(Number(q.order_id)).status,mileage_baseline:{mileage:32500,source:'VEHICLE'},server_now:stamp,appointment_snapshot:order(Number(q.order_id)).appointment_snapshot})
  if (path==='/api/merchant/assessment/month') {const m=q.period_month||'2026-09',[y,n]=m.split('-').map(Number);const end=new Date(Date.UTC(y,n,1)-8*3600000).toISOString().replace('.000Z','Z');return ok({merchant,period_month:m,timezone:'Asia/Shanghai',server_now:stamp,month_end:end,revision:0,latest:null,policy:null,state:stamp<end?'MONTH_OPEN':'POLICY_UNKNOWN',can_settle:false})}
  if (path==='/api/merchant/assessment/history') return ok(paged([],q))
  if (path==='/api/merchant/assessment/staff') return ok({role:'MERCHANT',staff_id:9002,merchant_id:1,period_month:q.period_month||'2026-09',timezone:'Asia/Shanghai',state:'NO_SNAPSHOT',revision:0,captured_from:null,captured_to:null,attribution:{included:0,excluded:0,unknown:0}})
  if (path==='/api/merchant/customer-relations') return ok({observed_at:stamp,relationships:paged([{relationship_order_id:1,order_count:5,last_order_at:stamp,last_service_at:stamp,identity_source:'TEST',latest_vehicle:{brand:'演示品牌',series:'演示车系',year:'2020',config_name:'家用配置'}}],q)})
  if (path==='/api/point/overview') return ok({balance:20,balance_scope:'VERIFIED_TASKS_V1',legacy_state:'NONE',earning_available:false,day,timezone:'Asia/Shanghai',tasks:['CHECK_IN','ARCHIVE_FIRST_COMPLETE','AI_FIRST_SUCCESS'].map((task_type,i)=>({task_type,frequency:i===0?'DAILY':'LIFETIME',policy:null,state:'UNKNOWN',points_awarded:0,occurred_at:null,credited_at:null}))})
  if (/^\/api\/vehicle\/1\/health-dashboard$/.test(path)) return ok(health())
  if (path==='/api/merchant/commissions') {const totals={accrued:'0.00',reversed:'0.00',net:'0.00',pending_count:0};return ok({merchant_id:1,policy:{state:'CONFIGURED',rate_percent:'3.00',effective_from:'2026-10-01T00:00:00Z',waived_until:'2027-01-01T00:00:00Z',rule_version:'R2C_V1'},summary:{real:totals,test:totals},...paged([],q)})}
  if (/^\/api\/(owner|merchant|tech)\/notifications$/.test(path)) return ok({unread_count:1,notifications:paged([{notification_id:1,kind:'ORDER_ASSIGN',order_id:2,order_no:order(2).order_no,order_status:'RECEIVED',occurred_at:stamp,read_at:null}],q)})
  if (path==='/api/merchant/business/dashboard') return ok(business(q.period_month))
  if (path==='/api/point/flow') return ok(paged([{id:1,delta:20,balance_after:20,created_at:stamp,task_type:'CHECK_IN',period_key:day,policy_id:1,policy_revision:2}],q))
  return reject(503,'该页面尚未配置演示样本，可切换角色查看其他内容')
}
