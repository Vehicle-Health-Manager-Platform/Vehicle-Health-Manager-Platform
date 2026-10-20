import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
import { nativeAcceptancePlugin } from '../../scripts/native-acceptance-plugin.mjs'
import { localPreviewPlugin } from '../../scripts/local-preview-plugin.mjs'
// https://vitejs.dev/config/
export default defineConfig({
  plugins: [
    localPreviewPlugin(),
    nativeAcceptancePlugin(),
    uni(),
  ],
})
