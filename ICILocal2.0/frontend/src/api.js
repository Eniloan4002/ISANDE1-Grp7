import {api,configureApiAuth} from './api/client.js';
const catalogs=new Map();
let accessToken=sessionStorage.getItem('ici_access_token')||'';
export function getAccessToken(){return accessToken;}
export function setAccessToken(token) {
  accessToken=token||'';
  catalogs.clear();
  if(accessToken)sessionStorage.setItem('ici_access_token',accessToken);
  else sessionStorage.removeItem('ici_access_token');
  configureApiAuth(accessToken,()=>window.dispatchEvent(new Event('auth:expired')));
}
setAccessToken(accessToken);
export function hasAccessToken(){return Boolean(accessToken);}
async function networkRequest(path,options={}) {
  try {
    const response=await api.request({url:path.replace(/^\/api(?=\/)/,''),method:options.method||'GET',data:options.body?JSON.parse(options.body):undefined,headers:options.headers});
    return response.status===204?null:response.data;
  }catch(original){
    const data=original.response?.data;
    const error=new Error(data?.detail||(original.response?'The request could not be completed. Try again.':'The service is unavailable. Check your connection and try again.'));
    error.fields=data?.errors||{};error.status=original.response?.status;throw error;
  }
}

// Cache only reference catalogs, never applicant records, decisions, or authorization responses.
export async function apiRequest(path,options={}) {
  const read=(options.method||'GET').toUpperCase()==='GET';
  const catalog=read&&/\/reference-data(?:\?|$)/.test(path);
  const key=accessToken+'\n'+path;
  const prior=catalogs.get(key);
  if(catalog&&!options.force&&prior&&prior.expires>Date.now())return prior.promise;
  if(!read)catalogs.clear();
  const promise=networkRequest(path,options);
  if(catalog)catalogs.set(key,{promise,expires:Date.now()+30000});
  try{
    const value=await promise;
    if(!read)catalogs.clear();
    return value;
  }catch(error){
    if(catalog&&catalogs.get(key)?.promise===promise)catalogs.delete(key);
    throw error;
  }
}
