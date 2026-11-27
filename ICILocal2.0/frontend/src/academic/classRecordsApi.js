import { apiRequest } from '../api.js';

const context = ({ academicYearId, termId }) => new URLSearchParams({ academicYearId, termId });

/** The sections the signed-in adviser is assigned to for a term. */
export async function loadAdvisorySections(filters, signal) {
  const result = await apiRequest(`/api/academic/advisory/rosters?${context(filters)}`, { signal });
  return (result?.sections || []).map(section => ({ id: section.id, name: section.name, gradeLevel: section.gradeLevel }));
}

/**
 * Grade matrix for one advisory section: subjects across, students down, one grading period.
 * Leave the period out to get the term's first one; the response lists every period for the picker.
 */
export function loadClassRecords(filters, sectionId, gradingPeriodId, signal) {
  const query = context(filters);
  query.set('sectionId', sectionId);
  if (gradingPeriodId) query.set('gradingPeriodId', gradingPeriodId);
  return apiRequest(`/api/academic/advisory/class-records?${query}`, { signal });
}
