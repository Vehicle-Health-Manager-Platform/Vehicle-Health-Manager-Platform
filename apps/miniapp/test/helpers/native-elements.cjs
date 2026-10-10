const assert = require('node:assert/strict')
async function elements(rpc, page, selector) {
  const found = [], queue = [null], visited = new Set()
  while (queue.length) {
    const elementId = queue.shift(), params = { pageId: page.pageId, ...(elementId ? { elementId } : {}) }
    const method = elementId ? 'Element.getElements' : 'Page.getElements'
    found.push(...(await rpc(method, { ...params, selector })).elements)
    for (const child of (await rpc(method, { ...params, selector: 'component' })).elements) {
      if (!visited.has(child.elementId)) { visited.add(child.elementId); queue.push(child.elementId) }
    }
    assert.ok(visited.size <= 50, 'Unexpected native component tree')
  }
  return Promise.all(found.map(async element => {
    const { properties } = await rpc('Element.getDOMProperties', { pageId: page.pageId, elementId: element.elementId, names: ['innerText'] })
    return { ...element, text: properties[0] }
  }))
}
async function waitElement(rpc, page, selector, predicate = () => true) {
  const deadline = Date.now() + 15000
  do {
    const found = (await elements(rpc, page, selector)).find(predicate)
    if (found) return found
    await new Promise(resolve => setTimeout(resolve, 150))
  } while (Date.now() < deadline)
  throw new Error(`Native element timeout: ${selector}`)
}
async function tapButton(rpc, page, label) {
  const button = await waitElement(rpc, page, 'button', element => element.text?.trim() === label)
  await tapElement(rpc,page,button)
}
async function tapElement(rpc,page,element) {
  const offset=await rpc('Element.getOffset',{pageId:page.pageId,elementId:element.elementId})
  assert.ok(Number.isFinite(offset.top),'Native element offset missing')
  await rpc('App.callWxMethod',{method:'pageScrollTo',args:[{scrollTop:Math.max(0,offset.top-120),duration:0}]})
  await new Promise(resolve=>setTimeout(resolve,150))
  await rpc('Element.tap',{pageId:page.pageId,elementId:element.elementId})
}
module.exports = { elements, waitElement, tapButton, tapElement }
