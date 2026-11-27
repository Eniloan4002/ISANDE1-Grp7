import { apiRequest } from '../api.js';

export function loadSettingsReferenceData(signal) {
  return apiRequest('/api/enrollment/reference-data', { signal });
}

export function loadAttendanceWindow(campusId, termId, signal) {
  const params = new URLSearchParams({ campusId: String(campusId), termId: String(termId) });
  return apiRequest(`/api/enrollment/attendance-window?${params.toString()}`, { signal });
}

export function saveAttendanceWindow(campusId, termId, payload) {
  const params = new URLSearchParams({ campusId: String(campusId), termId: String(termId) });
  return apiRequest(`/api/enrollment/attendance-window?${params.toString()}`, {
    method: 'PUT',
    body: JSON.stringify({
      startsOn: payload.startsOn,
      endsOn: payload.endsOn,
      approvalNote: payload.approvalNote.trim(),
      version: payload.version ?? 0,
      orientationStartsOn: payload.orientationStartsOn || null,
      orientationEndsOn: payload.orientationEndsOn || null,
    }),
  });
}

export function loadGuidancePolicySetting(campusId, termId, signal) {
  return apiRequest(`/api/guidance/policies/${campusId}/${termId}`, { signal });
}

export function saveGuidancePolicySetting(campusId, termId, payload) {
  return apiRequest(`/api/guidance/policies/${campusId}/${termId}`, {
    method: 'PUT',
    body: JSON.stringify({
      failingBelow:
        payload.failingBelow === '' || payload.failingBelow == null ? null : Number(payload.failingBelow),
      consecutiveAbsences:
        payload.consecutiveAbsences === '' || payload.consecutiveAbsences == null
          ? null
          : Number(payload.consecutiveAbsences),
      credentialsDueOn: payload.credentialsDueOn || null,
      reason: payload.reason.trim(),
      version: payload.version ?? 0,
    }),
  });
}

export function loadSchoolCalendar(campusId, termId, signal) {
  return apiRequest(`/api/academic/school-days/${campusId}/${termId}`, { signal });
}

export function saveSchoolCalendar(campusId, termId, days, reason) {
  return apiRequest(`/api/academic/school-days/${campusId}/${termId}`, {
    method: 'PUT',
    body: JSON.stringify({
      days: days.map(day => ({
        date: day.date,
        schoolDay: Boolean(day.schoolDay),
        version: day.version ?? 0,
      })),
      reason: reason.trim(),
    }),
  });
}

/**
 * Best-effort lookup of the latest audit entry for a given settings table and campus
 * so the UI can display who last changed a setting and when.
 */
export async function loadLatestSettingAudit(recordType, campusId, signal) {
  try {
    const params = new URLSearchParams({
      recordType,
      campusId: String(campusId),
      page: '0',
      size: '1',
    });
    const data = await apiRequest(`/api/admin/audit-log?${params.toString()}`, { signal });
    const latest = Array.isArray(data?.items) ? data.items[0] : null;
    if (!latest) return null;
    return {
      who: latest.actorName || latest.actorUsername || 'System',
      when: latest.occurredAt || null,
    };
  } catch {
    return null;
  }
}

export function extractLastChanged(setting, auditFallback) {
  const who =
    setting?.updatedByName ||
    setting?.updatedBy ||
    setting?.lastChangedBy ||
    auditFallback?.who ||
    null;
  const when =
    setting?.updatedAt ||
    setting?.lastChangedAt ||
    auditFallback?.when ||
    null;
  if (!who && !when) return null;
  return { who, when };
}

export function buildDateRangeDays(startIso, endIso, existingItems = [], markWeekendsOff = true) {
  if (!startIso || !endIso || endIso < startIso) return [];
  const existingByDate = new Map(existingItems.map(item => [String(item.date), item]));
  const result = [];
  const cursor = new Date(`${startIso}T00:00:00Z`);
  const end = new Date(`${endIso}T00:00:00Z`);
  let guard = 0;
  while (cursor <= end && guard < 366) {
    const iso = cursor.toISOString().slice(0, 10);
    const dow = cursor.getUTCDay();
    const isWeekend = dow === 0 || dow === 6;
    const existing = existingByDate.get(iso);
    result.push({
      date: iso,
      schoolDay: markWeekendsOff ? !isWeekend : true,
      version: existing?.version ?? 0,
    });
    cursor.setUTCDate(cursor.getUTCDate() + 1);
    guard++;
  }
  return result;
}
