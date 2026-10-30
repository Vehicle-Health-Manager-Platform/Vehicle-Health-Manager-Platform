import test from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import {createRequire} from 'node:module'
import {pathToFileURL} from 'node:url'
import {uniVueCompatibility} from '../../../scripts/uni-vue-compat.mjs'

test('uni shared helpers preserve existing slot flag writability across component updates',()=>{
 const alias=uniVueCompatibility()['@vue/shared']
 const require=createRequire(pathToFileURL(path.resolve(path.dirname(alias),'../package.json')))
 const {def}=require('./dist/shared.cjs.js')
 const slots={_:1,default:()=>[]};def(slots,'_',1)
 assert.equal(Object.getOwnPropertyDescriptor(slots,'_').enumerable,false)
 assert.doesNotThrow(()=>Object.assign(slots,{_:1,default:()=>['updated']}))
 assert.deepEqual(slots.default(),['updated'])
})
