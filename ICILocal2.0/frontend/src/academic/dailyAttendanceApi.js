import { apiRequest } from '../api.js';
import { isoDay } from '../api/dates.js';

export const attendanceStatuses = [['present', 'Present'], ['absent', 'Absent'], ['excused', 'Excused']];
export const maxBatch = 500;


const context = ({ academicYearId, termId }) => new URLSearchParams({ academicYearId, termId });

/** The signed-in teacher's classes for a term, with the campus each class belongs to. */
export async function loadAttendanceClasses(filters) {
  const result = await apiRequest(`/api/academic/classes?${context(filters)}`);
  return (result?.items || []).map(item => ({
    id: item.classId, subject: item.subject, subjectCode: item.subjectCode, section: item.section,
    gradeLevel: item.gradeLevel, campusId: item.campusId, termId: item.termId,
  }));
}

export async function loadClassStudents(classId, filters) {
  const result = await apiRequest(`/api/academic/classes/${classId}?${context(filters)}`);
  return (result?.students || []).filter(student => student.enrollmentId != null);
}

/** The explicitly configured days for a campus and term. Dates missing from it are not school days. */
export async function loadSchoolDays(campusId, termId) {
  const result = await apiRequest(`/api/academic/school-days/${campusId}/${termId}`);
  return (result?.items || []).map(item => ({ ...item, date: isoDay(item.date) }));
}

export function saveSchoolDays(campusId, termId, days, reason) {
  return apiRequest(`/api/academic/school-days/${campusId}/${termId}`, {
    method: 'PUT',
    body: JSON.stringify({ days, reason: String(reason || '').trim() }),
  }).then(result => ({ ...result, items: (result?.items || []).map(item => ({ ...item, date: isoDay(item.date) })) }));
}

export async function loadStudentAttendance(enrollmentId) {
  const result = await apiRequest(`/api/academic/daily-attendance/${enrollmentId}`);
  return (result?.items || []).map(item => ({ ...item, date: isoDay(item.date) }));
}

/** Saves one batch. The whole batch is rejected if any entry is stale, duplicated or not on a school day. */
export function saveAttendance(entries) {
  return apiRequest('/api/academic/daily-attendance/import', { method: 'POST', body: JSON.stringify({ entries }) });
}

/** Campuses and terms (with their dates) for staff who manage the calendar. */
export const loadCalendarReference = () => apiRequest('/api/enrollment/reference-data');
