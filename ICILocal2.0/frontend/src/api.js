import {api,configureApiAuth} from './api/client.js';
let accessToken=sessionStorage.getItem('ici_access_token')||'';
export function getAccessToken(){return accessToken;}
export function setAccessToken(token) {
  accessToken=token||'';
  if(accessToken)sessionStorage.setItem('ici_access_token',accessToken);
  else sessionStorage.removeItem('ici_access_token');
  configureApiAuth(accessToken,()=>window.dispatchEvent(new Event('auth:expired')));
}
setAccessToken(accessToken);
export function hasAccessToken(){return Boolean(accessToken);}
export async function apiRequest(path,options={}) {
  try {
    const response=await api.request({url:path.replace(/^\/api(?=\/)/,''),method:options.method||'GET',data:options.body?JSON.parse(options.body):undefined,headers:options.headers});
    return response.status===204?null:response.data;
  }catch(original){
    const data=original.response?.data;
    const error=new Error(data?.detail||(original.response?'The request could not be completed. Try again.':'The service is unavailable. Check your connection and try again.'));
    error.fields=data?.errors||{};error.status=original.response?.status;throw error;
  }
}
