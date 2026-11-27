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

export function loadAdviserRisks({ academicYearId = '', termId = '', sectionId = '', asOf = '', page = 0, size = 100 } = {}, options = {}) {
  return apiRequest(
    `/api/guidance/risks${qs({ academicYearId, termId, sectionId, asOf, page, size })}`,
    options
  );
}

export function referStudentToGuidance({ enrollmentId, reason }) {
  return apiRequest('/api/guidance/referrals', {
    method: 'POST',
    body: JSON.stringify({
      enrollmentId: Number(enrollmentId),
      reason: String(reason || '').trim(),
    }),
  });
}
