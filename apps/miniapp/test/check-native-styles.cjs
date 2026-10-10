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
