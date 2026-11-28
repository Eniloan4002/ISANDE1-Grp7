import { apiRequest } from '../api.js';

function qs(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params || {})) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value));
    }
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

export function formatWithdrawalError(error, fallback = 'The withdrawal request could not be completed.') {
  if (!error) return fallback;
  if (error.status === 403) {
    return error.message || 'Your role or campus scope does not allow this withdrawal action.';
  }
  if (error.status === 409) {
    return error.message || 'This withdrawal or clearance changed since you loaded it. Reload and try again.';
  }
  return error.message || fallback;
}

export function listWithdrawals({ campusId = '', termId = '', status = '', page = 0, size = 25 } = {}, options = {}) {
  return apiRequest(`/api/academic/withdrawals${qs({ campusId, termId, status, page, size })}`, options);
}

export function getWithdrawal(id, options = {}) {
  return apiRequest(`/api/academic/withdrawals/${id}`, options);
}

export function getReleaseEligibility(id, options = {}) {
  return apiRequest(`/api/academic/withdrawals/${id}/release-eligibility`, options);
}

export function createWithdrawal({ enrollmentId, enrollmentVersion, reason }) {
  return apiRequest('/api/academic/withdrawals', {
    method: 'POST',
    body: JSON.stringify({
      enrollmentId: Number(enrollmentId),
      enrollmentVersion: Number(enrollmentVersion),
      reason: String(reason || '').trim(),
    }),
  });
}

export function listAdviserEndorsements({ page = 0, size = 25 } = {}, options = {}) {
  return apiRequest(`/api/academic/withdrawal-endorsements${qs({ page, size })}`, options);
}

export function decideAdviserEndorsement(withdrawalId, { version, decision, notes }) {
  return apiRequest(`/api/academic/withdrawal-endorsements/${withdrawalId}`, {
    method: 'PUT',
    body: JSON.stringify({
      version: Number(version),
      decision,
      notes: String(notes || '').trim(),
    }),
  });
}

export function decideOfficeClearance(id, office, { version, withdrawalVersion, decision, notes }) {
  return apiRequest(`/api/academic/withdrawals/${id}/clearances/${office}`, {
    method: 'PUT',
    body: JSON.stringify({
      version: Number(version),
      withdrawalVersion: Number(withdrawalVersion),
      decision,
      notes: String(notes || '').trim(),
    }),
  });
}

export function searchEnrolledApplications({ search = '', page = 0, size = 15 } = {}, options = {}) {
  return apiRequest(
    `/api/enrollment/applications${qs({ status: 'enrolled', search: search.trim(), page, size })}`,
    options
  );
}

export function getApplicationEnrollment(applicationId, options = {}) {
  return apiRequest(`/api/applications/${applicationId}/enrollment`, options);
}
