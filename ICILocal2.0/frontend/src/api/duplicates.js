import {apiRequest} from '../api.js';

// PR #11 integration: production always uses the persisted backend. No mock/live toggle.
// Distinct people and rejection of an incoming application are separate decisions.
export function resolveDuplicateCase(id,decision) {
  if(!['same_person','distinct','reject'].includes(decision.action)) throw new Error('Choose a valid identity decision.');
  if(!decision.reason?.trim()) throw new Error('A decision reason is required.');
  return apiRequest(`/api/duplicate-cases/${id}/resolve`,{method:'POST',body:JSON.stringify(decision)});
}
export function scanDuplicateCases(applicationId) {
  return apiRequest(`/api/intake/applications/${applicationId}/duplicates/scan`,{method:'POST',body:'{}'});
}
