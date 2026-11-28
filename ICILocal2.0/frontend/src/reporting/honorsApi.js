import { apiRequest } from '../api.js';
import { query } from '../api/download.js';

export const roundingModes = [['HALF_UP', 'Round half up'], ['HALF_EVEN', 'Round half to even'], ['DOWN', 'Truncate (round down)']];
export const tiePolicy = 'competition_include_all';

/** Campuses, grading periods and grade levels the caller may report on. */
export const loadHonorFilters = () => apiRequest('/api/reports/filter-options');

/** Programs come from the enrollment catalog; staff without it see program ids instead of names. */
export const loadPrograms = () => apiRequest('/api/enrollment/reference-data').then(data => data?.programs ?? []);

/** Subject names come from academic setup; staff without it see subject ids instead of names. */
export const loadSubjects = () => apiRequest('/api/setup/subjects');

export const loadPolicies = (scope, options = {}) =>
  apiRequest(`/api/honors/policies${query({ ...scope, size: 100 })}`, options);

export const createPolicy = (scope, rules) => apiRequest('/api/honors/policies', {
  method: 'POST',
  body: JSON.stringify({ campusId: Number(scope.campusId), programId: Number(scope.programId), gradeLevel: scope.gradeLevel, gradingPeriodId: Number(scope.gradingPeriodId), rules }),
});

export const updatePolicy = (id, version, rules) =>
  apiRequest(`/api/honors/policies/${id}`, { method: 'PUT', body: JSON.stringify({ version, rules }) });

export const approvePolicy = (id, version, reference) =>
  apiRequest(`/api/honors/policies/${id}/approve`, { method: 'POST', body: JSON.stringify({ version, reference: String(reference || '').trim() }) });

/** Computes the honor roll. Rejects with status 409 when no approved policy applies to the scope. */
export const loadHonorRoll = (scope, options = {}) => apiRequest(`/api/honors/roll${query(scope)}`, options);

const reasonText = {
  missing_subject: 'Not enrolled in one of the honor subjects',
  ambiguous_subject: 'Enrolled in more than one class for an honor subject',
  pending_grade_correction: 'A grade correction is still pending',
  unfinalized_or_missing_grade: 'A grade is not finalized yet',
  missing_grade: 'A grade is missing',
  below_subject_minimum: 'A subject grade is below the minimum',
  below_honor_threshold: 'Average is below every honor category',
};

/** Plain-language text for an ineligibility code from the honor-roll computation. */
export function describeReason(code) {
  if (reasonText[code]) return reasonText[code];
  const mark = /^non_numeric_mark_(.+)$/.exec(code);
  if (mark) return `Marked ${mark[1]} instead of a numeric grade`;
  return String(code).replaceAll('_', ' ');
}
