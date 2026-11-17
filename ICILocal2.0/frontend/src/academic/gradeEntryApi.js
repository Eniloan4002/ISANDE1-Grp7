import { apiRequest } from '../api.js';

export const approvedNonNumericMarks = ['INC', 'NG', 'DRP'];

export function loadGradeCatalog() {
  return apiRequest('/api/academic/terms');
}

function context({ academicYearId, termId }) {
  return new URLSearchParams({ academicYearId, termId });
}

export async function loadTeacherClasses(filters) {
  const result = await apiRequest(`/api/academic/classes?${context(filters)}`);
  return {
    items: (result.items || []).map(item => ({
      id: item.classId,
      subject: item.subject,
      subjectCode: item.subjectCode,
      gradeLevel: item.gradeLevel,
      section: item.section,
      room: item.room,
    })),
  };
}

export async function loadGradingPeriods(classId, filters) {
  const result = await apiRequest(`/api/academic/classes/${classId}/grading-periods?${context(filters)}`);
  return (result.periods || []).map(period => ({ id: period.id, label: period.code, status: period.status }));
}

function formatDeadline(value) {
  if (!value) return 'Not set';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });
}

function toGradebook(result) {
  const grades = {};
  const students = (result.students || []).map(row => {
    const hasValue = row.value !== null && row.value !== undefined;
    grades[row.enrollmentId] = hasValue
      ? { value: Number(row.value).toFixed(2), kind: 'numeric' }
      : row.mark ? { value: row.mark, kind: 'mark' } : { value: '', kind: 'empty' };
    return { id: row.enrollmentId, studentNumber: row.studentNumber, name: row.name, version: row.version ?? null };
  });
  return {
    class: result.class,
    period: { id: result.period.id, label: result.period.code, status: result.period.status, deadline: formatDeadline(result.period.deadline) },
    students,
    grades,
    submission: ['submitted', 'finalized'].includes(result.state) ? 'submitted' : 'draft',
  };
}

export async function loadClassGradebook(classId, periodId, filters) {
  const query = context(filters);
  query.set('gradingPeriodId', periodId);
  return toGradebook(await apiRequest(`/api/academic/classes/${classId}/grades?${query}`));
}

export async function saveGradeDraft(classId, periodId, grades, students, filters) {
  const entries = students.map(student => {
    const grade = grades[student.id] || { value: '', kind: 'empty' };
    return {
      enrollmentId: student.id,
      value: grade.kind === 'numeric' ? Number(grade.value) : null,
      mark: grade.kind === 'mark' ? grade.value : null,
      version: student.version,
    };
  });
  return toGradebook(await apiRequest(`/api/academic/classes/${classId}/grades`, {
    method: 'PUT',
    body: JSON.stringify({ academicYearId: Number(filters.academicYearId), termId: Number(filters.termId), gradingPeriodId: Number(periodId), entries }),
  }));
}

export async function submitGrades(classId, periodId, filters) {
  return toGradebook(await apiRequest(`/api/academic/classes/${classId}/grades/submit`, {
    method: 'POST',
    body: JSON.stringify({ academicYearId: Number(filters.academicYearId), termId: Number(filters.termId), gradingPeriodId: Number(periodId) }),
  }));
}
