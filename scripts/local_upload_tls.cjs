// Local test transport only. Preserve signed host/path/query; never log them.
const https=require('node:https'),http=require('node:http'),fs=require('node:fs')
if(!process.argv.includes('--allow-local-upload-tls')) {
  console.error('Requires --allow-local-upload-tls; loopback only')
  process.exit(2)
}
const server=https.createServer({
  key:fs.readFileSync(process.argv.includes('--native-preview-certificate')?'test-results/native-preview-key.pem':'test-results/local-upload-key.pem'),
  cert:fs.readFileSync(process.argv.includes('--native-preview-certificate')?'test-results/native-preview-cert.pem':'test-results/local-upload-cert.pem'),
},(req,res)=>{
  if(req.headers.origin&&req.headers.origin!=='http://127.0.0.1:4317'){res.writeHead(403);return res.end()}
  if(req.method==='OPTIONS'){
    res.writeHead(204,{'Access-Control-Allow-Origin':'http://127.0.0.1:4317','Access-Control-Allow-Methods':'GET,HEAD'})
    return res.end()
  }
  if(!['GET','HEAD'].includes(req.method)){res.writeHead(405);return res.end()}
  const upstream=http.request({hostname:'127.0.0.1',port:9000,path:req.url,method:req.method,headers:{host:req.headers.host}},response=>{
    res.writeHead(response.statusCode,{...response.headers,'Access-Control-Allow-Origin':'http://127.0.0.1:4317','Cache-Control':'no-store'})
    response.pipe(res)
    response.on('error',()=>res.destroy())
  })
  upstream.setTimeout(10000,()=>upstream.destroy())
  upstream.on('error',()=>{if(!res.headersSent)res.writeHead(503);res.end()})
  req.on('aborted',()=>upstream.destroy());upstream.end()
})
server.listen(9443,'127.0.0.1',()=>console.log('Local signed-download TLS endpoint ready; details withheld'))
process.on('SIGINT',()=>server.close(()=>process.exit(0)))
