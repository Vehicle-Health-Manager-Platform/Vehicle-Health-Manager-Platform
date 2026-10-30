import path from 'node:path'
import { createRequire } from 'node:module'
import { readFileSync } from 'node:fs'

const appRequire = createRequire(new URL('../apps/miniapp/package.json', import.meta.url))
// uni's customized renderer must use its own declared shared helpers. In a
// workspace the root Vue can be newer; 3.5's def() changes slot writability.
export function uniVueCompatibility() {
  const vendorPath = appRequire.resolve('@dcloudio/uni-cli-shared/package.json')
  const vendorRequire = createRequire(vendorPath)
  const required = JSON.parse(readFileSync(vendorPath, 'utf8')).dependencies['@vue/shared']
  // uni patches package subpath resolution while loading Vite config; resolve
  // the entry and read metadata directly instead of requiring a subpath.
  const sharedPath = path.join(path.dirname(vendorRequire.resolve('@vue/shared')), 'package.json')
  const shared = JSON.parse(readFileSync(sharedPath, 'utf8'))
  if (shared.version !== required) throw new Error('uni Vue shared helper version does not match its renderer')
  return { '@vue/shared': path.join(path.dirname(sharedPath), shared.module).replaceAll('\\', '/') }
}
