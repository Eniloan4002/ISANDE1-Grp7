let accessToken=sessionStorage.getItem('ici_access_token')||'';
export function setAccessToken(token) {
  accessToken=token||'';
  if(accessToken)sessionStorage.setItem('ici_access_token',accessToken);
  else sessionStorage.removeItem('ici_access_token');
}
export function hasAccessToken(){return Boolean(accessToken);}
export async function apiRequest(path,options={}) {
  let response;
  try {
    response=await fetch(path,{...options,headers:{'Content-Type':'application/json',...(accessToken?{Authorization:`Bearer ${accessToken}`} : {}),...options.headers}});
  }catch(error){throw new Error('The service is unavailable. Check your connection and try again.');}
  if(response.status===204)return null;
  const json=(response.headers.get('content-type')||'').includes('json');
  const data=json?await response.json():null;
  if(!response.ok) {
    if(response.status===401&&path!=='/api/auth/login')window.dispatchEvent(new Event('auth:expired'));
    const error=new Error(data?.detail||'The request could not be completed. Check that the backend is running and try again.');
    error.fields=data?.errors||{};error.status=response.status;throw error;
  }
  if(data===null)throw new Error('The service returned an unexpected response.');
  return data;
}
