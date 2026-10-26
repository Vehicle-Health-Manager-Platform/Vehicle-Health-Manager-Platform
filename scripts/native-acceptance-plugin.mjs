import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

export const NATIVE_BRIDGE_MARKER = '__autocareNativeAcceptance'
export function nativeAcceptancePlugin(env = process.env) {
  const enabled = env.R0_NATIVE_TEST_BUILD === '1'
  if (!enabled) return null
  const output = path.resolve(env.UNI_OUTPUT_DIR || '')
  const expected = path.resolve(root, '.cache/r0-native/mp-weixin')
  if (output !== expected || env.VITE_API_BASE_URL !== 'http://127.0.0.1:18080' || env.VITE_WECHAT_CLOUD_ENV_ID || env.VITE_WECHAT_CLOUD_SERVICE) {
    throw new Error('Native acceptance requires the isolated output and loopback backend')
  }
  const virtual = 'virtual:autocare-native-acceptance', resolved = '\0' + virtual
  const source = path.resolve(root, 'apps/miniapp/src').replaceAll('\\', '/')
  return {
    name: 'isolated-native-acceptance', enforce: 'pre',
    resolveId(id) { if (id === virtual) return resolved },
    transform(code, id) { if (id.replaceAll('\\', '/').endsWith('/apps/miniapp/src/main.js')) return `import '${virtual}';\n${code}` },
    load(id) {
      if (id !== resolved) return
      return `
import {setOwnerSession,clearOwnerSession} from ${JSON.stringify(source + '/services/owner-session.js')};
import {merchantSession,clearMerchantSession} from ${JSON.stringify(source + '/services/merchant-session.js')};
import {setTechnicianSession,clearTechnicianSession} from ${JSON.stringify(source + '/services/technician-session.js')};
import {setOperatorSession,clearOperatorSession} from ${JSON.stringify(source + '/services/operator-session.js')};
import {selectOwnerVehicle,clearOwnerVehicle} from ${JSON.stringify(source + '/services/owner-vehicle-selection.js')};
import {apiRuntime} from ${JSON.stringify(source + '/services/api-runtime.js')};
import {installNativeAudioFixture} from ${JSON.stringify(root.replaceAll('\\', '/') + '/scripts/native-audio-fixture.mjs')};
let audioFixture;
wx.${NATIVE_BRIDGE_MARKER}=function(options){
 if(wx.getSystemInfoSync().platform!=='devtools')throw new Error('Native acceptance only supports Devtools');
 const answer=result=>{options.success?.(result);return result;};
 if(options.role==='audio-fixture'){if(audioFixture)throw new Error('Audio fixture already active');audioFixture=installNativeAudioFixture({runtime:apiRuntime,file:options.file});return answer(audioFixture.status());}
 if(options.role==='audio-status'){if(!audioFixture)throw new Error('Audio fixture missing');return answer(audioFixture.status());}
 if(options.role==='audio-restore'){let result={restored:true};if(audioFixture){audioFixture.restore();result=audioFixture.status();}audioFixture=null;return answer(result);}
 clearOwnerSession();clearMerchantSession();clearTechnicianSession();clearOperatorSession();clearOwnerVehicle();
 const role=options.role;
 if(role!=='clear' && (typeof options.token!=='string'||!options.token))throw new Error('Native acceptance session missing');
 if(role==='owner'){setOwnerSession({access_token:options.token,user:{phone_bound:false}});if(options.vehicle)selectOwnerVehicle({vehicle_id:options.vehicle});}
 else if(role==='merchant')merchantSession.accessToken=options.token;
 else if(role==='technician')setTechnicianSession({access_token:options.token});
 else if(role==='operator')setOperatorSession({access_token:options.token,expires_in:900,user:{id:options.actorId,can_review:true,can_analytics:options.canAnalytics===true}});
 else if(role!=='clear')throw new Error('Native acceptance role invalid');
 options.success?.({ready:true});return {ready:true};
};`
    },
  }
}
