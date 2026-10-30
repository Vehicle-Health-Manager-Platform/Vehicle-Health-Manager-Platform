import { defineConfig } from 'vite'
import importedUni from '@dcloudio/vite-plugin-uni'
import { uniVueCompatibility } from '../../scripts/uni-vue-compat.mjs'
import { nativeAcceptancePlugin } from '../../scripts/native-acceptance-plugin.mjs'
import { localPreviewPlugin } from '../../scripts/local-preview-plugin.mjs'
// https://vitejs.dev/config/
export default defineConfig({
  resolve: { alias: uniVueCompatibility() },
  plugins: [
    localPreviewPlugin(),
    nativeAcceptancePlugin(),
    (typeof importedUni === 'function' ? importedUni : importedUni.default)(),
  ],
})
