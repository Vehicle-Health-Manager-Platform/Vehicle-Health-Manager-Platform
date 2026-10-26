const {test}=require('node:test');
const assert=require('node:assert/strict');
const modulePromise=import('../../../scripts/check-wechat-identity-readiness.mjs');
function fixture(){return {
 backend:{MYSQL_HOST:'mysql.private.internal',MYSQL_DATABASE:'identity',MYSQL_USER:'private-db-user',MYSQL_PASSWORD:'private-db-password',JWT_SECRET:'private-jwt-value-that-is-long-enough',WECHAT_APP_ID:'wx1234567890abcdef',WECHAT_CLOUD_RUN_ENABLED:'true'},
 miniapp:{VITE_WECHAT_CLOUD_ENV_ID:'cloud-test-123',VITE_WECHAT_CLOUD_SERVICE:'autocare-api'},
 deployment:{schema_version:1,target:'wechat-cloudrun',environment_id:'cloud-test-123',service:'autocare-api',region:'ap-shanghai',public_access:'disabled',identity_gateway:'wechat-managed-headers'},
 manifest:{'mp-weixin':{appid:'wx1234567890abcdef'}},
};}
async function inspect(f){const {inspectCloudDeploymentReadiness}=await modulePromise;return inspectCloudDeploymentReadiness(f.backend,f.miniapp,f.deployment,f.manifest);}

test('matching cloud configuration never certifies deployment or formal identities',async()=>{
 const report=await inspect(fixture());assert.equal(report.configurationReady,true);assert.equal(report.cloudDeployed,false);assert.equal(report.formalIdentityAccepted,false);
 assert.equal(report.trustEvidence,'local-declaration-only');assert.ok(report.pending.includes('PUBLIC_DIRECT_ACCESS_DISABLED'));
 assert.equal(report.capabilities.fileUpload,'cloud-storage-adapter-required');
});
test('cloud login without secret does not imply phone binding configuration works',async()=>{
 const f=fixture();let report=await inspect(f);assert.equal(report.configurationReady,true);assert.equal(report.capabilities.phoneBinding,'configuration-blocked');
 f.backend.WECHAT_APP_SECRET='private-app-secret';report=await inspect(f);assert.equal(report.capabilities.phoneBinding,'configuration-ready-permission-unverified');
 assert.ok(report.pending.includes('WECHAT_PHONE_PERMISSION_AND_REAL_BINDING'));
});
test('missing or inconsistent environment/service prevents a ready declaration',async()=>{
 for(const [part,key,value] of [['miniapp','VITE_WECHAT_CLOUD_ENV_ID',''],['miniapp','VITE_WECHAT_CLOUD_SERVICE',''],['deployment','environment_id','other-env'],['deployment','service','other-api'],['miniapp','VITE_WECHAT_CLOUD_SERVICE','https://private.invalid']]){
  const f=fixture();f[part][key]=value;assert.equal((await inspect(f)).configurationReady,false);
 }
});
test('backend switch, secrets, database configuration and actual miniapp AppID must agree',async()=>{
 for(const [key,value] of [['WECHAT_CLOUD_RUN_ENABLED','false'],['WECHAT_CLOUD_RUN_ENABLED','yes'],['MYSQL_PASSWORD','change-me'],['JWT_SECRET','short'],['WECHAT_APP_ID','wx0000000000000000']]){
  const f=fixture();f.backend[key]=value;assert.equal((await inspect(f)).configurationReady,false);
 }
 const f=fixture();f.manifest['mp-weixin'].appid='';assert.equal((await inspect(f)).configurationReady,false);
});
test('container-local or Compose MySQL addresses cannot pass cloud connectivity configuration',async()=>{
 for(const host of ['localhost','127.0.0.1','127.9.8.7','0.0.0.0','::1','[::1]','mysql','db','user@db.internal','db.internal/path']){
  const f=fixture();f.backend.MYSQL_HOST=host;assert.equal((await inspect(f)).configurationReady,false,host);
 }
});
test('public access, unverified gateway and malformed declarations are blocked',async()=>{
 for(const patch of [{public_access:'enabled'},{public_access:'unknown'},{identity_gateway:'unverified'},{target:'cloud-function'},{schema_version:'1'},{region:''},{extra:'secret-value'}]){
  const f=fixture();Object.assign(f.deployment,patch);assert.equal((await inspect(f)).configurationReady,false);
 }
 for(const value of [null,[],{},false]){const f=fixture();f.deployment=value;assert.equal((await inspect(f)).configurationReady,false);}
});
test('deferring SMS preserves blocked merchant/operator login without inventing delivery success',async()=>{
 const f=fixture();f.backend.SMS_PROVIDER='tencent';const report=await inspect(f);
 assert.equal(report.capabilities.merchantSmsLogin,'deferred-no-bypass');assert.equal(report.capabilities.operatorSmsLogin,'deferred-no-bypass');
 assert.equal(report.formalIdentityAccepted,false);
});
test('cloud report exports fixed statuses and never configuration values or injected keys',async()=>{
 const f=fixture();f.backend.PHONE='private-phone-marker';f.deployment.extra='injected-marker';const report=JSON.stringify(await inspect(f));
 for(const value of ['private-db-user','private-db-password','private-jwt-value-that-is-long-enough','wx1234567890abcdef','cloud-test-123','ap-shanghai','private-phone-marker','injected-marker'])assert.equal(report.includes(value),false);
});
test('cloud CLI uses explicit files, ignores inherited credentials and never returns acceptance success',async()=>{
 const {runReadinessCli}=await modulePromise;const f=fixture();const args=['--cloud-deployment','--env-file','private-backend','--miniapp-env-file','private-miniapp','--deployment-file','private-deploy'];
 const env=object=>Object.entries(object).map(([k,v])=>k+'='+v).join('\n');
 const files={'private-backend':env(f.backend),'private-miniapp':env(f.miniapp),'private-deploy':JSON.stringify(f.deployment)};
 const read=name=>name instanceof URL?JSON.stringify(f.manifest):files[name];
 let result=runReadinessCli(args,{},read);assert.equal(result.exitCode,2);assert.equal(JSON.parse(result.output).configurationReady,true);
 files['private-backend']='';result=runReadinessCli(args,f.backend,read);assert.equal(JSON.parse(result.output).configurationReady,false);
});
test('cloud CLI read/JSON/argument errors do not echo private paths or exception text',async()=>{
 const {runReadinessCli}=await modulePromise;const args=['--cloud-deployment','--env-file','private-backend','--miniapp-env-file','private-miniapp','--deployment-file','private-deploy'];
 for(const read of [()=>{throw Error('private-error')},()=>'{invalid private-data']){
  const result=runReadinessCli(args,{},read);assert.equal(result.exitCode,1);assert.equal(result.output,'{"error":"CONFIG_READ_FAILED"}\n');
 }
 const result=runReadinessCli([...args,'--unknown-secret'],{});assert.equal(result.exitCode,1);assert.equal(result.output,'{"error":"INVALID_ARGUMENTS"}\n');
});
