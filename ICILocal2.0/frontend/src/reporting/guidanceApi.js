import { apiRequest } from '../api.js';
import { api } from '../api/client.js';

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

export function formatGuidanceError(error, fallback = 'The guidance request could not be completed.') {
  if (!error) return fallback;
  if (error.status === 403) {
    return error.message || 'Your role or campus scope does not allow this guidance action.';
  }
  if (error.status === 409) {
    return error.message || 'This record changed since you loaded it. Reload and try again.';
  }
  return error.message || fallback;
}

export function listGuidanceRisks(
  { campusId = '', termId = '', academicYearId = '', gradingPeriodId = '', asOf = '', page = 0, size = 100 } = {},
  options = {}
) {
  return apiRequest(
    `/api/guidance/risks${qs({ campusId, termId, academicYearId, gradingPeriodId, asOf, page, size })}`,
    options
  );
}

export function getGuidancePolicy(campusId, termId, options = {}) {
  return apiRequest(`/api/guidance/policies/${campusId}/${termId}`, options);
}

export function saveGuidancePolicy(
  campusId,
  termId,
  { failingBelow, consecutiveAbsences, credentialsDueOn, reason, version }
) {
  return apiRequest(`/api/guidance/policies/${campusId}/${termId}`, {
    method: 'PUT',
    body: JSON.stringify({
      failingBelow: failingBelow === '' || failingBelow == null ? null : Number(failingBelow),
      consecutiveAbsences:
        consecutiveAbsences === '' || consecutiveAbsences == null ? null : Number(consecutiveAbsences),
      credentialsDueOn: credentialsDueOn ? String(credentialsDueOn) : null,
      reason: String(reason || '').trim(),
      version: Number(version ?? 0),
    }),
  });
}

export function listGuidanceCases({ status = '', studentId = '', page = 0, size = 25 } = {}, options = {}) {
  return apiRequest(`/api/guidance/cases${qs({ status, studentId, page, size })}`, options);
}

export function searchGuidanceStudents({ search = '', page = 0, size = 10 } = {}, options = {}) {
  return apiRequest(`/api/academic/history/students${qs({ search, page, size })}`, options);
}

export function getGuidanceStudentHistory(studentId, options = {}) {
  return apiRequest(`/api/academic/history/students/${studentId}`, options);
}

export function getGuidanceCase(id, options = {}) {
  return apiRequest(`/api/guidance/cases/${id}`, options);
}

export function createGuidanceCase({ enrollmentId, reason }) {
  return apiRequest('/api/guidance/cases', {
    method: 'POST',
    body: JSON.stringify({
      enrollmentId: Number(enrollmentId),
      reason: String(reason || '').trim(),
    }),
  });
}

export function updateGuidanceCase(id, { status, reason, version }) {
  return apiRequest(`/api/guidance/cases/${id}`, {
    method: 'PUT',
    body: JSON.stringify({
      status,
      reason: String(reason || '').trim(),
      version: Number(version),
    }),
  });
}

export function addGuidanceCaseNote(id, { note, version }) {
  return apiRequest(`/api/guidance/cases/${id}/notes`, {
    method: 'POST',
    body: JSON.stringify({
      note: String(note || '').trim(),
      version: Number(version),
    }),
  });
}

function filenameFromDisposition(header, fallback) {
  if (!header) return fallback;
  const match = /filename="?([^";]+)"?/i.exec(header);
  return match?.[1] || fallback;
}

export async function exportGuidanceCase(id, format = 'pdf') {
  try {
    const response = await api.get(`/guidance/cases/${id}/export?format=${encodeURIComponent(format)}`, {
      responseType: 'blob',
    });
    const filename = filenameFromDisposition(
      response.headers?.['content-disposition'],
      `ici-guidance-case-${id}.${format}`
    );
    const url = URL.createObjectURL(response.data);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    return filename;
  } catch (original) {
    const detail = original?.response?.data?.detail;
    const error = new Error(detail || 'The guidance case export could not be downloaded.');
    error.status = original?.response?.status;
    throw error;
  }
}
