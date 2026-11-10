import {api,configureApiAuth} from './api/client.js';
const catalogs=new Map();
const pending=new Map();
let generation=0;
let accessToken=sessionStorage.getItem('ici_access_token')||'';
export function getAccessToken(){return accessToken;}
export function setAccessToken(token) {
  accessToken=token||'';
  catalogs.clear();
  generation++;
  if(accessToken)sessionStorage.setItem('ici_access_token',accessToken);
  else sessionStorage.removeItem('ici_access_token');
  configureApiAuth(accessToken,()=>window.dispatchEvent(new Event('auth:expired')));
}
setAccessToken(accessToken);
export function hasAccessToken(){return Boolean(accessToken);}
async function networkRequest(path,options={}) {
  try {
    const response=await api.request({url:path.replace(/^\/api(?=\/)/,''),method:options.method||'GET',data:options.body?JSON.parse(options.body):undefined,headers:options.headers,signal:options.signal});
    return response.status===204?null:response.data;
  }catch(original){
    if(original.code==='ERR_CANCELED'||original.name==='AbortError')throw new DOMException('Request canceled','AbortError');
    const data=original.response?.data;
    const error=new Error(data?.detail||(original.response?'The request could not be completed. Try again.':'The service is unavailable. Check your connection and try again.'));
    error.fields=data?.errors||{};error.status=original.response?.status;throw error;
  }
}

// Only catalogs survive completion. Other GETs share work solely while in flight.
// Each caller owns a lease so one unmount cannot cancel another caller's request.
function sharedRead(key,path,options){
  let entry=pending.get(key);
  if(!entry||entry.controller.signal.aborted){
    const controller=new AbortController();
    entry={controller,users:0,settled:false};
    entry.promise=networkRequest(path,{...options,signal:controller.signal}).finally(()=>{
      entry.settled=true;if(pending.get(key)===entry)pending.delete(key);
    });
    pending.set(key,entry);
  }
  entry.users++;
  return new Promise((resolve,reject)=>{
    let done=false;
    const finish=(callback,value)=>{
      if(done)return;done=true;options.signal?.removeEventListener('abort',cancel);entry.users--;
      // StrictMode's immediate remount may acquire the same request before this runs.
      if(!entry.users&&!entry.settled)setTimeout(()=>{if(!entry.users&&!entry.settled)entry.controller.abort();},0);
      callback(value);
    };
    const cancel=()=>finish(reject,new DOMException('Request canceled','AbortError'));
    entry.promise.then(value=>finish(resolve,value),error=>finish(reject,error));
    if(options.signal?.aborted)cancel();else options.signal?.addEventListener('abort',cancel,{once:true});
  });
}
export async function apiRequest(path,options={}) {
  const read=(options.method||'GET').toUpperCase()==='GET';
  const catalog=read&&/\/reference-data(?:\?|$)/.test(path);
  const key=accessToken+'\n'+generation+'\n'+path;
  const prior=catalogs.get(key);
  if(catalog&&!options.force&&prior&&prior.expires>Date.now())return prior.value;
  if(!read){catalogs.clear();generation++;}
  try{
    const value=read?await sharedRead(key+(options.force?'\nforce':''),path,options):await networkRequest(path,options);
    // A response started before a write/account change cannot repopulate its cache.
    if(catalog&&key===accessToken+'\n'+generation+'\n'+path)catalogs.set(key,{value,expires:Date.now()+30000});
    if(!read){catalogs.clear();generation++;}
    return value;
  }catch(error){
    if(catalog)catalogs.delete(key);
    throw error;
  }
}
