// Restricted byte transport only; never replace backend/model responses.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),{spawn}=require('node:child_process')
const uuid=/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i
function admitted(route,headers,body,file){
 if(route==='/api/file/upload')return /^multipart\/form-data; boundary=[A-Za-z0-9_.=-]+$/.test(headers['content-type']||'')&&uuid.test(headers['idempotency-key']||'')
 let data;try{data=JSON.parse(body.toString('utf8'))}catch{return false}
 if(!data||typeof data!=='object'||Array.isArray(data))return false
 if((headers['content-type']||'').split(';')[0]!=='application/json')return false
 if(route==='/api/vehicle/mileage-recognition')return Object.keys(data).sort().join(',')==='consent,consent_version,file_id,vehicle_id'&&data.vehicle_id===992&&data.file_id===file&&file>0&&data.consent===true&&data.consent_version==='MILEAGE_OCR_V1'
 if(route==='/api/archive/add')return uuid.test(headers['idempotency-key']||'')&&Object.keys(data).sort().join(',')==='archive_type,file_ids,mileage,notes,recorded_date,title,vehicle_id'&&data.vehicle_id===992&&data.mileage===123456&&data.title==='synthetic-mileage-native'&&data.archive_type===1&&data.notes===''&&/^\d{4}-\d{2}-\d{2}$/.test(data.recorded_date)&&Array.isArray(data.file_ids)&&data.file_ids.length===1&&data.file_ids[0]===file&&file>0
 return false
}
function transportCommand(host_backend,run,config,headersPath){
 if(typeof host_backend!=='boolean')throw Error('Invalid transport mode')
 return host_backend?['curl.exe',['--config',config,'--dump-header',headersPath,'--data-binary','@-']]:['docker',['exec','-i','autocare-real-mileage-'+run.slice(0,8),'curl','--config','/workspace/.cache/real-mileage-'+run+'/native-curl.private.cfg','--dump-header','/workspace/.cache/real-mileage-'+run+'/native-headers.private.txt','--data-binary','@-']]
}
async function createMileageProxy({token,port,image,work,run,listenPort=18080,host_backend=false}){
 if(path.resolve(work)!==path.resolve(__dirname,'..','.cache','real-mileage-'+run)||!/^[a-f0-9]{32}$/.test(run)||!/^eyJ[\w.-]+$/.test(token)||!Number.isInteger(port)||port<1||port>65535)throw Error('Invalid fixture')
 const config=path.join(work,'native-curl.private.cfg'),headersPath=path.join(work,'native-headers.private.txt')
 const [executable,arguments_]=transportCommand(host_backend,run,config,headersPath)
 const counts={upload:0,recognition:0,archive:0};let busy=false,file=0
 const server=http.createServer(async(req,res)=>{
  if(req.headers.authorization!=='Bearer '+token){res.writeHead(401).end();return}
  if(req.method==='GET'&&req.url==='/__mileage-fixture.png'){res.writeHead(200,{'content-type':'image/png','cache-control':'no-store'}).end(Buffer.from(image,'base64'));return}
  const mode={'/api/file/upload':'upload','/api/vehicle/mileage-recognition':'recognition','/api/archive/add':'archive'}[req.url]
  if(req.method!=='POST'||!mode){res.writeHead(404).end();return}
  if(busy){res.writeHead(429).end();return}busy=true
  try{
   let size=0,chunks=[];for await(const chunk of req){size+=chunk.length;if(size>1048576)throw Error('Too large');chunks.push(chunk)}const body=Buffer.concat(chunks)
   if(!admitted(req.url,req.headers,body,file)){res.writeHead(400).end();return}
   fs.writeFileSync(config,`url = "http://127.0.0.1:${port}${req.url}"\nheader = "Authorization: Bearer ${token}"\nheader = "Content-Type: ${mode==='upload'?req.headers['content-type']:'application/json'}"\n${mode==='recognition'?'':'header = "Idempotency-Key: '+req.headers['idempotency-key']+'"\n'}max-time = 60\nsilent\n`)
   const bytes=await new Promise((resolve,reject)=>{const child=spawn(executable,arguments_,{windowsHide:true,stdio:['pipe','pipe','ignore']});let output=[];child.stdout.on('data',c=>output.push(c));child.on('error',reject);child.on('close',code=>code===0?resolve(Buffer.concat(output)):reject(Error('Transport failed')));child.stdin.on('error',()=>{});child.stdin.end(body)})
   const status=[...fs.readFileSync(headersPath,'utf8').matchAll(/HTTP\/[\d.]+ (\d+)/g)].at(-1);if(!status)throw Error('Missing status')
   counts[mode]++;if(mode==='upload'&&Number(status[1])===200){file=JSON.parse(bytes).data.file_id;if(!Number.isSafeInteger(file)||file<1)throw Error('Invalid file receipt')}
   res.writeHead(Number(status[1]),{'content-type':'application/json','cache-control':'no-store'}).end(bytes)
  }catch{res.writeHead(502).end()}finally{busy=false;for(const p of [config,headersPath])try{fs.unlinkSync(p)}catch{}}
 })
 await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(listenPort,'127.0.0.1',resolve)})
 return {server,counts,async close(){server.closeAllConnections();await new Promise(resolve=>server.close(resolve));for(const p of [config,headersPath])try{fs.unlinkSync(p)}catch{}}}
}
module.exports={admitted,createMileageProxy,transportCommand}
