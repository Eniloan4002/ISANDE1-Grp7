import {beforeEach,expect,it,vi} from 'vitest';
const transport=vi.hoisted(()=>({request:vi.fn(),configure:vi.fn()}));
vi.mock('../api/client.js',()=>({api:{request:transport.request},configureApiAuth:transport.configure}));
import {apiRequest,setAccessToken} from '../api.js';
beforeEach(()=>{transport.request.mockReset();setAccessToken('test-role-token');});
it('deduplicates reference catalogs but always reloads live applicant data',async()=>{
 transport.request.mockResolvedValue({status:200,data:{items:[]}});
 await Promise.all([apiRequest('/api/intake/reference-data'),apiRequest('/api/intake/reference-data')]);
 expect(transport.request).toHaveBeenCalledTimes(1);
 await apiRequest('/api/enrollment/applications');await apiRequest('/api/enrollment/applications');
 expect(transport.request).toHaveBeenCalledTimes(3);
});
it('clears cached catalogs after mutations and when the signed-in account changes',async()=>{
 transport.request.mockResolvedValue({status:200,data:{items:[]}});
 await apiRequest('/api/intake/reference-data');await apiRequest('/api/setup/terms',{method:'POST',body:'{}'});await apiRequest('/api/intake/reference-data');
 expect(transport.request).toHaveBeenCalledTimes(3);
 setAccessToken('different-role-token');await apiRequest('/api/intake/reference-data');expect(transport.request).toHaveBeenCalledTimes(4);
});
it('explicit reload bypasses catalogs and a rejected catalog request is not retained',async()=>{
 transport.request.mockRejectedValueOnce(new Error('offline')).mockResolvedValue({status:200,data:{campuses:[]}});
 await expect(apiRequest('/api/intake/reference-data')).rejects.toThrow('service is unavailable');
 await apiRequest('/api/intake/reference-data');await apiRequest('/api/intake/reference-data',{force:true});expect(transport.request).toHaveBeenCalledTimes(3);
});
it('shares live reads in flight without caching completed records',async()=>{
 let resolve;transport.request.mockImplementation(()=>new Promise(done=>{resolve=done;}));
 const first=apiRequest('/api/enrollment/applications'),second=apiRequest('/api/enrollment/applications');
 expect(transport.request).toHaveBeenCalledTimes(1);resolve({status:200,data:{items:[]}});await Promise.all([first,second]);
 transport.request.mockResolvedValue({status:200,data:{items:[]}});await apiRequest('/api/enrollment/applications');expect(transport.request).toHaveBeenCalledTimes(2);
});
it('does not let one canceled subscriber cancel another subscriber',async()=>{
 let resolve;transport.request.mockImplementation(()=>new Promise(done=>{resolve=done;}));
 const controller=new AbortController();
 const first=apiRequest('/api/enrollment/applications',{signal:controller.signal});
 const second=apiRequest('/api/enrollment/applications');controller.abort();
 await expect(first).rejects.toMatchObject({name:'AbortError'});
 expect(transport.request.mock.calls[0][0].signal.aborted).toBe(false);
 resolve({status:200,data:{items:[1]}});expect(await second).toEqual({items:[1]});
});
it('does not repopulate catalogs from a read started before a write',async()=>{
 let resolve;transport.request.mockImplementationOnce(()=>new Promise(done=>{resolve=done;})).mockResolvedValue({status:200,data:{terms:['new']}});
 const old=apiRequest('/api/intake/reference-data');await apiRequest('/api/setup/terms',{method:'POST',body:'{}'});
 resolve({status:200,data:{terms:['old']}});await old;
 expect(await apiRequest('/api/intake/reference-data')).toEqual({terms:['new']});expect(transport.request).toHaveBeenCalledTimes(3);
});
it('aborts abandoned transport after the last subscriber releases it',async()=>{
 transport.request.mockImplementation(()=>new Promise(()=>{}));
 const controller=new AbortController();const request=apiRequest('/api/students?search=old',{signal:controller.signal});
 controller.abort();await expect(request).rejects.toMatchObject({name:'AbortError'});
 await new Promise(resolve=>setTimeout(resolve,10));expect(transport.request.mock.calls[0][0].signal.aborted).toBe(true);
});
