import test from 'node:test'
import assert from 'node:assert/strict'
import {lifeGroups,partnerService} from '../src/services/car-life.js'
test('three car life categories use explicit local routes and fixed partner keys',()=>{
 assert.deepEqual(Object.keys(lifeGroups),['benefits','used','new'])
 for(const g of Object.values(lifeGroups))for(const item of g.items){assert.ok(item.title);if(item.partner)assert.equal(partnerService(item.partner).available,false);else assert.match(item.url,/^\/pages\//)}
 assert.equal(partnerService('__proto__'),null);assert.equal(partnerService('https://evil.invalid'),null)
})
test('partner configuration fails closed for untrusted destinations and malformed data',()=>{
 for(const url of ['http://example.org','https://example.org?a=vin','https://example.org/#jwt','https://u:p@example.org','https://127.0.0.1','https://localhost','https://example.org/../private','javascript:alert(1)','//example.org','https://example.org:8443'])assert.equal(partnerService('fuel',JSON.stringify({fuel:{url,provider:'测试合作方'}})).available,false,url)
 for(const raw of ['{','null','[]','{"fuel":{}}'])assert.equal(partnerService('fuel',raw).available,false)
 const service=partnerService('fuel',JSON.stringify({fuel:{url:'https://partner.example.org/fuel',provider:'测试合作方'}}))
 assert.equal(service.available,true);assert.equal(service.host,'partner.example.org');assert.equal(service.url,'https://partner.example.org/fuel')
 assert.equal(partnerService('fuel',JSON.stringify({fuel:{url:'https://partner.example.org',provider:'bad\nname'}})).available,false)
})
