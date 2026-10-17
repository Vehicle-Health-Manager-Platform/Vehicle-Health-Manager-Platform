import {validInvitationCode} from './invitation-context.js'
// A local text carrier; no official WeChat code, profile or credentials are drawn.
export function posterLines(code,staff=false){if(!validInvitationCode(code)||typeof staff!=='boolean')throw new Error('请先读取当前账号推广码');return ['汽车健康管家',staff?'好友服务邀请':'一起记录爱车健康','打开小程序 → 我的 → 输入邀请码',code.slice(0,16),code.slice(16),'首次注册前输入；具体奖励以当时规则为准','已注册账号不计新客；不会覆盖原邀请来源']}
export function drawInvitationPoster({code,staff=false,canvasId,component,runtime=()=>uni}){const lines=posterLines(code,staff);return new Promise((resolve,reject)=>{try{const r=runtime();if(typeof r.createCanvasContext!=='function'||typeof r.canvasToTempFilePath!=='function')return reject(new Error('当前环境不支持本地海报，可复制邀请码'));const ctx=r.createCanvasContext(canvasId,component);ctx.setFillStyle('#f2f6f3');ctx.fillRect(0,0,640,800);ctx.setFillStyle('#ffffff');ctx.fillRect(32,32,576,736);const sizes=[24,34,23,31,31,19,19],ys=[90,158,250,350,400,580,625];for(let i=0;i<lines.length;i++){ctx.setFillStyle(i===1?'#008f24':'#1d2129');ctx.setFontSize(sizes[i]);ctx.fillText(lines[i],54,ys[i]);}ctx.draw(false,()=>r.canvasToTempFilePath({canvasId,width:640,height:800,destWidth:1280,destHeight:1600,fileType:'png',success:n=>typeof n.tempFilePath==='string'&&n.tempFilePath?resolve(n.tempFilePath):reject(new Error('海报生成失败，请重试')),fail:()=>reject(new Error('海报生成失败，请复制邀请码'))},component))}catch{reject(new Error('当前环境无法生成海报，请复制邀请码'))}})}
export function initialPosterState(){return {busy:false,path:'',previewing:false,message:''}}
export function createPosterFlow({state,identity,code,draw,save,cleanup=()=>{}}={}){
 let epoch=0;const current=(e,t,c)=>e===epoch&&t===identity()&&c===code(),inUse=new Set(),retired=new Set()
 function release(p){if(inUse.has(p))retired.add(p);else cleanup(p)}
 function reset(){epoch++;const p=state.path;Object.assign(state,initialPosterState());if(p)release(p)}
 async function generate(){if(state.busy||!identity()||!validInvitationCode(code()))return;reset();const e=epoch,t=identity(),c=code();state.busy=true;try{const p=await draw(c);if(current(e,t,c))state.path=p;else cleanup(p)}catch(err){if(current(e,t,c))state.message=err.message||'生成失败，请重试'}finally{if(current(e,t,c))state.busy=false}}
 async function store(){if(state.busy||!state.path||!identity())return;const e=epoch,t=identity(),c=code(),p=state.path;state.busy=true;inUse.add(p);try{await save(p);if(current(e,t,c))state.message='已保存到相册'}catch(err){if(current(e,t,c))state.message=err.message||'操作未完成，可预览或复制邀请码'}finally{inUse.delete(p);if(retired.delete(p))cleanup(p);if(current(e,t,c))state.busy=false}}
 function preview(){if(!state.busy&&state.path&&identity())state.previewing=true}
 return {generate,reset,save:store,preview,closePreview:()=>{state.previewing=false}}
}
