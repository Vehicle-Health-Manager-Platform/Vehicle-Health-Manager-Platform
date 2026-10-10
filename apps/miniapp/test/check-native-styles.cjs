// Build artifact gate for the native-only regression: page style-src CSS was
// deduplicated into one page rather than being available throughout the app.
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const appStyles = fs.readFileSync(path.resolve(__dirname, '../dist/build/mp-weixin/app.wxss'), 'utf8')
for (const selector of ['reservation-page', 'reservation-title', 'reservation-panel', 'reservation-primary']) {
  assert.ok(appStyles.includes(`.${selector}`), `Shared native selector missing from app.wxss: ${selector}`)
}
console.log('PASS native shared styles available globally in app.wxss')
function checkNormalBundle(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name)
    if (entry.isDirectory()) checkNormalBundle(file)
    else if (entry.name.endsWith('.js')) assert.ok(!fs.readFileSync(file, 'utf8').includes('__autocareNativeAcceptance'), 'Test identity bridge found in normal bundle')
  }
}
checkNormalBundle(path.resolve(__dirname, '../dist/build/mp-weixin'))
console.log('PASS normal WeChat bundle contains no native test identity bridge')
