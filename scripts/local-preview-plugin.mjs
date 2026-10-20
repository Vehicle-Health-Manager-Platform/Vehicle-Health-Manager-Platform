import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
export const PREVIEW_MARKER = '__autocareLocalPreviewStatus'
export function localPreviewPlugin(env = process.env) {
  if (env.AUTOCARE_LOCAL_PREVIEW !== '1') return null
  const expected = path.join(root, '.cache/local-preview/mp-weixin')
  if (path.resolve(env.UNI_OUTPUT_DIR || '') !== expected || env.UNI_PLATFORM !== 'mp-weixin'
      || env.VITE_API_BASE_URL || env.VITE_WECHAT_CLOUD_ENV_ID || env.VITE_WECHAT_CLOUD_SERVICE) {
    throw new Error('Local preview requires isolated WeChat output and no real service configuration')
  }
  const src = path.join(root, 'apps/miniapp/src').replaceAll('\\', '/')
  const runtime = path.join(root, 'apps/miniapp/preview/runtime.js').replaceAll('\\', '/')
  const virtual = 'virtual:autocare-local-preview', resolved = '\0' + virtual
  return {
    name: 'isolated-local-role-preview', enforce: 'pre',
    resolveId(id) { if (id === virtual) return resolved },
    load(id) {
      if (id !== resolved) return
      return `
import {setOwnerSession} from ${JSON.stringify(src + '/services/owner-session.js')};
import {merchantSession} from ${JSON.stringify(src + '/services/merchant-session.js')};
import {setTechnicianSession} from ${JSON.stringify(src + '/services/technician-session.js')};
import {PREVIEW_TOKENS,installPreviewGuard} from ${JSON.stringify(runtime)};
export function installLocalPreview(app){
 app.mixin({onLaunch(){
  installPreviewGuard(wx);
  setOwnerSession({access_token:PREVIEW_TOKENS.owner,user:{id:9001,phone_bound:true}});
  merchantSession.accessToken=PREVIEW_TOKENS.merchant;
  setTechnicianSession({access_token:PREVIEW_TOKENS.tech,user:{id:9003}});
 }});
}`
    },
    transform(code, id) {
      const name = id.replaceAll('\\', '/').split('?')[0]
      if (id.includes('?')) return
      if (name === src + '/services/api-config.js') return `export const apiOrigin='https://local-preview.invalid';export const cloudRun=Object.freeze({env:'',service:''});export const cloudRunEnabled=false;export const CLOUD_RUN_ORIGIN='https://cloudrun.invalid';`
      if (name === src + '/services/api-runtime.js') return `import {createLocalPreviewRuntime} from ${JSON.stringify(runtime)};\n` + code.replace('runtime: () => uni,', 'runtime: () => createLocalPreviewRuntime(uni),')
      if (name === src + '/main.js') return `import {installLocalPreview} from '${virtual}';\n` + code.replace('const app = createSSRApp(App);', 'const app = createSSRApp(App); installLocalPreview(app);')
      if (name === src + '/pages/index/index.vue') code = code.replace('uni.navigateTo({ url: `/${path}` })', "if(path==='pages/owner/index')uni.switchTab({url:'/pages/home/index'});else uni.navigateTo({url:`/${path}`})")
      if (name === src + '/components/RoleLanding.vue') code = code.replace('登录后可使用的工作流程', '可查看的工作流程').replace('已登录。业务页面会重新核对当前账号权限。', '当前使用本地演示身份，不代表真实登录。')
      const needsNotice = name.startsWith(src + '/pages/') || ['/components/RoleLanding.vue','/components/OwnerTabShell.vue'].some(component=>name === src + component)
      if (name.endsWith('.vue') && needsNotice && /<template>\s*<view\b/.test(code)) {
        code = code.replace(/(<template>\s*<view\b[^>]*>)/, `$1<view style="padding:16rpx 20rpx;margin-bottom:20rpx;background:#fff2df;border-radius:12rpx;color:#874600;font-size:24rpx;line-height:36rpx"><text>本地只读演示 · 全部为合成数据</text><button style="margin:12rpx 0 0;font-size:24rpx;line-height:56rpx;color:#874600;background:#fff" @tap="uni.reLaunch({url:'/pages/index/index'})">切换车主 / 商家 / 技师</button></view>`)
        return code
      }
      if (name === src + '/pages/index/index.vue') return code
    },
  }
}
