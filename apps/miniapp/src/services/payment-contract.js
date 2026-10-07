const id=n=>Number.isSafeInteger(n)&&n>0
const money=n=>typeof n==='string'&&/^(0|[1-9]\d{0,7})\.\d{2}$/.test(n)&&Number(n)>0
export const validPaymentSummary=p=>p===null||(p&&id(p.payment_id)&&['WECHAT','LOCAL_TEST'].includes(p.channel)&&typeof p.test_mode==='boolean'&&p.test_mode===(p.channel==='LOCAL_TEST')&&['CREATED','PENDING','SUCCEEDED','FAILED','CLOSED'].includes(p.status)&&money(p.amount)&&p.currency==='CNY'&&typeof p.requires_review==='boolean')
