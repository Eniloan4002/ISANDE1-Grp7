import { apiRequest } from '../api.js';
import { loadGradingPeriods } from './gradeEntryApi.js';

const context = ({ academicYearId, termId }) => new URLSearchParams({ academicYearId, termId });

/** The students enrolled in one of the signed-in teacher's classes. */
export async function loadClassRoster(classId, filters) {
  const result = await apiRequest(`/api/academic/classes/${classId}?${context(filters)}`);
  return { students: result?.students || [] };
}

/**
 * Student count plus where grade submission stands for the period teachers are working in:
 * the first open grading period, otherwise the last one.
 */
export async function loadClassSummary(classId, filters) {
  const [roster, periods] = await Promise.all([loadClassRoster(classId, filters), loadGradingPeriods(classId, filters)]);
  const period = periods.find(item => item.status === 'open') || periods[periods.length - 1] || null;
  if (!period) return { studentCount: roster.students.length, period: null, state: 'none' };
  const query = context(filters);
  query.set('gradingPeriodId', period.id);
  const book = await apiRequest(`/api/academic/classes/${classId}/grades?${query}`);
  const state = book?.state === 'finalized' ? 'finalized' : book?.state === 'submitted' ? 'submitted' : 'draft';
  return { studentCount: roster.students.length, period, state };
}
