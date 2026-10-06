import { quoteBody, quoteFailure } from './merchant-quotes.js'
export const initialQuoteListState = () => ({ items:[],page:0,total:0,busy:false,loaded:false,sort:'price_asc',message:'',failureKind:'' })
export function createQuoteListFlow({ state, fetchPage, token }) {
  let generation=0,retryMore=false
  function suspend(){generation++;state.busy=false}
  function reset(){suspend();Object.assign(state,initialQuoteListState())}
  async function load(more=false){
    if(state.busy || (more && (!state.loaded || state.items.length>=state.total)))return
    retryMore=more;const current=++generation,actor=token(),n=more?state.page+1:1
    state.busy=true;state.message='';state.failureKind=''
    try {
      const result=await fetchPage(actor,n,state.sort)
      if(current!==generation || actor!==token())return
      state.items=more?[...state.items,...result.items]:result.items;state.page=n;state.total=result.total;state.loaded=true
    }catch(error){if(current!==generation || actor!==token())return;const safe=quoteFailure(error);state.message=safe.message;state.failureKind=safe.kind}
    finally{if(current===generation)state.busy=false}
  }
  function sort(value){if(!['price_asc','price_desc'].includes(value))return;reset();state.sort=value;return load()}
  return{load,retry:()=>load(retryMore),sort,suspend,reset}
}
export const initialQuoteFormState = () => ({projectId:0,name:'',price:'',status:1,available:true,busy:false,saved:null,message:'',failureKind:''})
export function createQuoteFormFlow({state,api,token,newKey}){
  let generation=0,pending=null
  function suspend(){generation++;state.busy=false}
  function reset(){suspend();pending=null;Object.assign(state,initialQuoteFormState())}
  function edit(row){reset();Object.assign(state,{projectId:row.standard_project_id,name:row.project_name,price:row.price,status:row.status,available:row.available})}
  async function save(){
    if(state.busy || state.saved)return
    let body;try{body=quoteBody(state)}catch(error){const safe=quoteFailure(error);state.message=safe.message;state.failureKind=safe.kind;return}
    const canonical=JSON.stringify(body);if(!pending || pending.canonical!==canonical)pending={canonical,body,key:newKey()}
    const current=++generation,actor=token();state.busy=true;state.message='';state.failureKind=''
    try{const result=await api.save(actor,pending.body,pending.key);if(current!==generation || actor!==token())return;state.saved=result;state.message='报价已保存'}
    catch(error){if(current!==generation || actor!==token())return;const safe=quoteFailure(error);state.message=safe.message;state.failureKind=safe.kind}
    finally{if(current===generation)state.busy=false}
  }
  return{save,edit,suspend,reset}
}
