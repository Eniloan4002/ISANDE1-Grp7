import { apiRequest } from '../api.js';

export function loadAdvisoryCatalog() {
  return apiRequest('/api/academic/terms');
}

export function loadAdvisoryRoster({ academicYearId, termId, signal }) {
  const query = new URLSearchParams({ academicYearId, termId });
  return apiRequest(`/api/academic/advisory/rosters?${query}`, { signal });
}
