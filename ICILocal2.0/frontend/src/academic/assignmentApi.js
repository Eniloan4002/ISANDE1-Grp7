import { apiRequest } from '../api.js';

export async function loadAssignmentCatalog() {
  const [years, terms, reference] = await Promise.all([
    apiRequest('/api/setup/academic-years'),
    apiRequest('/api/setup/terms'),
    apiRequest('/api/enrollment/reference-data'),
  ]);
  return { years, terms, campuses: reference.campuses || [] };
}

export function loadAssignments({ academicYearId, termId, campusId, signal }) {
  const query = new URLSearchParams({ academicYearId, termId, campusId });
  return apiRequest(`/api/academic/assignments?${query}`, { signal });
}

export function createClassOffering(payload) {
  return apiRequest('/api/academic/class-offerings', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function assignTeacher(classId, payload) {
  return apiRequest(`/api/academic/class-offerings/${classId}/teacher`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export function assignAdviser(sectionId, payload) {
  return apiRequest(`/api/academic/sections/${sectionId}/adviser`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}
