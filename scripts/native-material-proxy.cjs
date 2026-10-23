// Host loopback only. One mode-specific authenticated upload route; no shell execution.
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),{spawn}=require('node:child_process')
async function createProxy({token,port,container,work,containerWork,pdf,image,kind='pdf',listenPort=18080}){
  if(!['pdf','image'].includes(kind))throw new Error('Invalid proxy mode')
  if(!/^[\w.-]+$/.test(container)||!Number.isSafeInteger(port)||port<1||port>65535||!/^eyJ[\w.-]+$/.test(token)||!/^\/workspace\/\.cache\/native-material-[0-9a-f]{32}$/.test(containerWork))throw new Error('Invalid proxy fixture')
  const imageMode=kind==='image',route=imageMode?'/api/file/upload':'/api/input-materials/ARCHIVE/991/upload'
  const fixture=imageMode?image:pdf,fixturePath=imageMode?'/__native-fixture.png':'/__native-fixture.pdf'
  let busy=false,requests=0
  const config=path.join(work,'curl.private.cfg'),headers=path.join(work,'headers.private.txt')
  const server=http.createServer(async(req,res)=>{
    if(req.method==='GET'&&req.url===fixturePath){if(req.headers.authorization!=='Bearer '+token||!fixture){res.writeHead(401).end();return}res.writeHead(200,{'content-type':imageMode?'image/png':'application/pdf','cache-control':'no-store'}).end(Buffer.from(fixture,'base64'));return}
    if(req.method!=='POST'||req.url!==route){res.writeHead(404).end();return}
    if(req.headers.authorization!=='Bearer '+token){res.writeHead(401).end();return}
    const type=req.headers['content-type']||'',key=req.headers['idempotency-key']||''
    if(!/^multipart\/form-data; boundary=[A-Za-z0-9_.=-]+$/.test(type)||!(/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(key))||(imageMode?req.headers['x-material-kind']!==undefined:req.headers['x-material-kind']!=='pdf')){res.writeHead(400).end();return}
    if(busy){res.writeHead(429).end();return}busy=true
    try{
      let size=0;const chunks=[];for await(const chunk of req){size+=chunk.length;if(size>1024*1024)throw new Error('Fixture too large');chunks.push(chunk)}
      fs.writeFileSync(config,`url = "http://127.0.0.1:${port}${route}"\nheader = "Authorization: Bearer ${token}"\nheader = "Content-Type: ${type}"\nheader = "Idempotency-Key: ${key}"\n${imageMode?'':'header = "X-Material-Kind: pdf"\n'}max-time = 60\nsilent\n`)
      const result=await new Promise((resolve,reject)=>{
        const child=spawn('docker',['exec','-i',container,'curl','--config',containerWork+'/curl.private.cfg','--dump-header',containerWork+'/headers.private.txt','--data-binary','@-'],{windowsHide:true,stdio:['pipe','pipe','ignore']})
        let output=[];child.stdout.on('data',c=>output.push(c));child.on('error',reject);child.on('close',code=>code===0?resolve(Buffer.concat(output)):reject(new Error('Fixture transport failed')));child.stdin.on('error',()=>{});child.stdin.end(Buffer.concat(chunks))
      })
      const raw=fs.readFileSync(headers,'utf8'),status=[...raw.matchAll(/HTTP\/[\d.]+ (\d+)/g)].at(-1)
      if(!status)throw new Error('Missing backend status');requests++;res.writeHead(Number(status[1]),{'content-type':'application/json','cache-control':'no-store'}).end(result)
    }catch{res.writeHead(502).end()}finally{busy=false;for(const file of [config,headers])try{fs.unlinkSync(file)}catch{}}
  })
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(listenPort,'127.0.0.1',resolve)})
  return {server,get requests(){return requests},async close(){server.closeAllConnections();await new Promise(resolve=>server.close(resolve));for(const file of [config,headers])try{fs.unlinkSync(file)}catch{}}}
}
module.exports={createProxy}
