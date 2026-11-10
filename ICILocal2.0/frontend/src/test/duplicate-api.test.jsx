import {beforeEach,expect,it,vi} from 'vitest';
import {resolveDuplicateCase,scanDuplicateCases} from '../api/duplicates.js';
const request=vi.hoisted(()=>vi.fn());
vi.mock('../api.js',()=>({apiRequest:request}));
beforeEach(()=>request.mockReset());
it.each(['same_person','distinct','reject'])('persists %s as a distinct versioned decision with its reason',async action=>{
 const decision={action,reason:'Verified against source records',version:4,applicationVersion:8,studentVersion:3};
 request.mockResolvedValue({status:'saved'});
 await resolveDuplicateCase(42,decision);
 expect(request).toHaveBeenCalledWith('/api/duplicate-cases/42/resolve',{method:'POST',body:JSON.stringify(decision)});
});
it('does not silently translate the old ambiguous REJECT action or accept a blank reason',()=>{
 expect(()=>resolveDuplicateCase(1,{action:'REJECT',reason:'Verified'})).toThrow('valid identity');
 expect(()=>resolveDuplicateCase(1,{action:'distinct',reason:'  '})).toThrow('reason');
 expect(request).not.toHaveBeenCalled();
});
it('rescans existing applications through the real intake endpoint',async()=>{
 await scanDuplicateCases(23);
 expect(request).toHaveBeenCalledWith('/api/intake/applications/23/duplicates/scan',{method:'POST',body:'{}'});
});
