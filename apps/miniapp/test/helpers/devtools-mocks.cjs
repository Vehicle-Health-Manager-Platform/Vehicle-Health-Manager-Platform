// Restoring an API which was never mocked can replace its implementation with undefined.
function createWxMockScope(rpc) {
  const active=new Set()
  return {
    get methods(){return [...active]},
    async mock(method,result){await rpc('App.mockWxMethod',{method,result});active.add(method)},
    async restore(method){if(active.has(method)){await rpc('App.mockWxMethod',{method});active.delete(method)}},
  }
}
module.exports={createWxMockScope}
