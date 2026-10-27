import {AsrError} from './asr-jobs.js'
const bad=()=>{throw new AsrError('protocol','图文提取响应异常，请手动填写')}
const exact=(v,keys)=>v&&typeof v==='object'&&!Array.isArray(v)&&Object.keys(v).length===keys.length&&keys.every(k=>Object.hasOwn(v,k))
const blank=v=>!v.replace(/[\s\u0085]/gu,'')
function text(v,multi=false){if(typeof v!=='string'||(multi?/[\u0000-\u0009\u000b-\u001f\u007f-\u009f]/:/[\u0000-\u001f\u007f-\u009f]/).test(v)||Array.from(v).some(c=>c.codePointAt(0)>=0xd800&&c.codePointAt(0)<=0xdfff))bad();return v}
export function documentResult(v,metadata){
 if(!exact(v,['version','task','source','content_type','content_sha256','page_count','pages','status','requires_confirmation','image_recognition_performed','all_pages_processed'])||v.version!==1||v.task!=='DOCUMENT'||v.source!=='PDFBOX_PADDLE'||!['application/pdf','image/jpeg','image/png'].includes(v.content_type)||v.content_type!==metadata?.contentType||!/^[a-f0-9]{64}$/.test(v.content_sha256)||v.page_count!==metadata?.pages||!Number.isInteger(v.page_count)||v.page_count<1||v.page_count>4||v.content_type!=='application/pdf'&&v.page_count!==1||v.requires_confirmation!==true||v.all_pages_processed!==true||typeof v.image_recognition_performed!=='boolean'||!Array.isArray(v.pages)||v.pages.length!==v.page_count)bad()
 let chars=0,blocks=0,ocr=false
 const pages=v.pages.map((p,index)=>{
  if(!exact(p,['page','method','text','raster_size','blocks'])||p.page!==index+1||!['NATIVE_TEXT','OCR','EMPTY'].includes(p.method)||!Array.isArray(p.blocks))bad()
  chars+=text(p.text,true).length;blocks+=p.blocks.length
  if(p.method==='OCR'){ocr=true;if(!Array.isArray(p.raster_size)||p.raster_size.length!==2||p.raster_size.some(n=>!Number.isInteger(n)||n<1||n>4096)||p.raster_size[0]*p.raster_size[1]>6000000)bad()}
  else if(p.raster_size!==null)bad()
  if(p.method==='EMPTY'&&(p.text||p.blocks.length)||p.method==='NATIVE_TEXT'&&blank(p.text))bad()
  let offset=0;const expected=[]
  if(p.method==='NATIVE_TEXT')for(const line of p.text.split('\n')){if(line.length>500)bad();if(!blank(line))expected.push({text:line,start:offset,end:offset+line.length});offset+=line.length+1}
  if(p.method==='NATIVE_TEXT'&&expected.length!==p.blocks.length)bad()
  const rows=p.blocks.map((b,n)=>{
   if(!exact(b,['text','confidence','source'])||blank(text(b.text))||b.text.length>500)bad()
   if(p.method==='NATIVE_TEXT'){
    const e=expected[n]
    if(b.confidence!==null||!exact(b.source,['kind','start','end'])||b.source.kind!=='TEXT_OFFSET'||b.text!==e.text||b.source.start!==e.start||b.source.end!==e.end)bad()
   }else if(p.method==='OCR'){
    const s=b.source,points=s?.points
    if(!exact(s,['kind','points'])||s.kind!=='RASTER_POLYGON'||typeof b.confidence!=='number'||!Number.isFinite(b.confidence)||b.confidence<0||b.confidence>1||!Array.isArray(points)||points.length!==4)bad()
    for(const point of points)if(!Array.isArray(point)||point.length!==2||point.some((v,j)=>typeof v!=='number'||!Number.isFinite(v)||v<0||v>p.raster_size[j]))bad()
    let sign=0
    points.forEach((a,i)=>{const b=points[(i+1)%4],c=points[(i+2)%4],cross=(b[0]-a[0])*(c[1]-b[1])-(b[1]-a[1])*(c[0]-b[0]);if(!cross||sign&&Math.sign(cross)!==sign)bad();sign=Math.sign(cross)})
   }else bad()
   return Object.freeze({...b,source:Object.freeze({...b.source,...(b.source.points?{points:Object.freeze(b.source.points.map(p=>Object.freeze([...p])))}:{})})})
  })
  if(p.method==='OCR'&&p.text!==rows.map(b=>b.text).join('\n'))bad()
  return Object.freeze({...p,raster_size:p.raster_size?Object.freeze([...p.raster_size]):null,blocks:Object.freeze(rows)})
 })
 if(chars>4096||blocks>80||ocr!==v.image_recognition_performed||v.status!==(blocks?'EXTRACTED':'EMPTY'))bad()
 return Object.freeze({...v,pages:Object.freeze(pages)})
}
