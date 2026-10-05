import test from 'node:test'
import assert from 'node:assert/strict'
import { archiveEntryUrl, archiveInputType, archiveInputTypeName } from '../src/services/archive-entry-mode.js'

test('manual and photo entry routes stay tied to the selected vehicle', () => {
  assert.equal(archiveEntryUrl(42), '/pages/archive/record-add?vehicle_id=42')
  assert.equal(archiveEntryUrl(42, 'photo'), '/pages/archive/record-add?vehicle_id=42&mode=photo')
  assert.throws(() => archiveEntryUrl(0, 'photo'))
  assert.throws(() => archiveEntryUrl(42, 'voice'))
})

test('only the exact photo mode marks a photo entry and labels known sources', () => {
  assert.equal(archiveInputType({ mode: 'photo' }), 1)
  assert.equal(archiveInputType({ mode: 'voice' }), 3)
  assert.equal(archiveInputType(null), 3)
  assert.equal(archiveInputTypeName(1), '拍照录入')
  assert.equal(archiveInputTypeName(3), '手动录入')
})
