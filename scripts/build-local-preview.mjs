import path from 'node:path'
import fs from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { spawnSync } from 'node:child_process'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const cwd = path.join(root,'apps/miniapp')
const output = path.join(root,'.cache/local-preview/mp-weixin')
const require = createRequire(path.join(cwd,'package.json'))
const cli = path.join(path.dirname(require.resolve('@dcloudio/vite-plugin-uni/package.json')),'bin/uni.js')
const env = {...process.env,AUTOCARE_LOCAL_PREVIEW:'1',UNI_PLATFORM:'mp-weixin',UNI_OUTPUT_DIR:output,VITE_API_BASE_URL:'',VITE_WECHAT_CLOUD_ENV_ID:'',VITE_WECHAT_CLOUD_SERVICE:''}
const result = spawnSync(process.execPath,[cli,'build','-p','mp-weixin'],{cwd,env,stdio:'inherit'})
if (result.error) throw result.error
if (result.status !== 0) process.exit(result.status || 1)
const configPath = path.join(output,'project.config.json')
const config = JSON.parse(fs.readFileSync(configPath,'utf8'))
config.projectname = '汽车健康管家-本地只读演示'
fs.writeFileSync(configPath,JSON.stringify(config,null,2)+'\n')
// Label every native navigation bar as well as the in-page notice.
const app = JSON.parse(fs.readFileSync(path.join(output,'app.json'),'utf8'))
for (const route of app.pages) {
  const pageFile = path.join(output,route+'.json')
  const page = JSON.parse(fs.readFileSync(pageFile,'utf8'))
  page.navigationBarTitleText = '演示 · ' + (page.navigationBarTitleText || '汽车健康管家')
  fs.writeFileSync(pageFile,JSON.stringify(page,null,2)+'\n')
}
console.log('LOCAL READ-ONLY PREVIEW: '+output)
