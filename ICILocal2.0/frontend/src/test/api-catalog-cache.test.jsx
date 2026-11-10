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
