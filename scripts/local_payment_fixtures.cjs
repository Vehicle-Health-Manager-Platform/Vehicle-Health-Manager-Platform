// Only loaded by the opt-in isolated loopback harness. No secret reaches H5.
const fs=require('node:fs'),path=require('node:path')
const {createHmac,randomUUID}=require('node:crypto')
module.exports=(sql,root)=>({
  async notify(input,authorization){
    if(!Number.isSafeInteger(input.payment_id)||input.payment_id<1||!['SUCCEEDED','FAILED'].includes(input.status))throw new Error('Invalid fixture notification')
    const detail=await fetch(`http://127.0.0.1:18080/api/payments/${input.payment_id}`,{headers:{Authorization:authorization||''},signal:AbortSignal.timeout(10000)})
    if(!detail.ok)throw new Error('Own payment required');const p=(await detail.json()).data
    const order=await fetch(`http://127.0.0.1:18080/api/order/${p.order_id}`,{headers:{Authorization:authorization||''},signal:AbortSignal.timeout(10000)})
    if(!order.ok)throw new Error('Own order required');const o=(await order.json()).data
    if(p.channel!=='LOCAL_TEST'||!o.merchant_snapshot?.merchant_name?.startsWith('本地合成测试商家'))throw new Error('Synthetic payment required')
    if(sql(`SELECT COUNT(*) FROM payment p JOIN \`order\` o ON o.id=p.order_id WHERE p.id=${input.payment_id} AND o.merchant_id IN (9101201,9101202)`)!=='1')throw new Error('Fixed fixture shop required')
    const env=fs.readFileSync(path.join(root,'.env.payment-test.local'),'utf8'),secret=env.split(/\r?\n/).find(l=>l.startsWith('PAYMENT_LOCAL_TEST_SECRET='))?.slice('PAYMENT_LOCAL_TEST_SECRET='.length)
    if(!secret||secret.length<32)throw new Error('Private isolated key required')
    const event=input.event_id||randomUUID();if(!/^[a-f0-9-]{36}$/i.test(event))throw new Error('Fixture event ID required')
    const body=JSON.stringify({event_id:event,payment_id:p.payment_id,channel_payment_no:`local-test-${p.payment_id}`,status:input.status,amount:input.wrong_amount?'0.01':p.amount,currency:'CNY',order_no:o.order_no,occurred_at:input.occurred_at||new Date().toISOString()})
    const timestamp=String(Math.floor(Date.now()/1000)),nonce=randomUUID(),signature=input.invalid_signature?'0'.repeat(64):createHmac('sha256',secret).update(timestamp+'\n'+nonce+'\n'+body+'\n').digest('hex')
    const response=await fetch('http://127.0.0.1:18080/api/payments/callback/LOCAL_TEST',{method:'POST',headers:{'Content-Type':'application/json','X-Test-Timestamp':timestamp,'X-Test-Nonce':nonce,'X-Test-Signature':signature},body,signal:AbortSignal.timeout(10000)})
    return{status:response.status,event_id:event,occurred_at:JSON.parse(body).occurred_at,ack:(await response.json()).code}
  },
  evidence(){const values=sql("SELECT (SELECT COUNT(*) FROM payment p JOIN `order` o ON o.id=p.order_id WHERE o.merchant_id IN (9101201,9101202)),(SELECT COUNT(*) FROM payment_event e JOIN payment p ON p.id=e.payment_id JOIN `order` o ON o.id=p.order_id WHERE o.merchant_id IN (9101201,9101202)),(SELECT COUNT(*) FROM payment_exception e JOIN `order` o ON o.id=e.order_id WHERE o.merchant_id IN (9101201,9101202)),(SELECT COUNT(*) FROM audit_log a JOIN `order` o ON a.resource_type='order' AND a.resource_id=o.id WHERE o.merchant_id IN (9101201,9101202) AND a.action='ORDER_PAID');").split('\t').map(Number);return{payments:values[0],events:values[1],exceptions:values[2],paidAudits:values[3]}}
})
