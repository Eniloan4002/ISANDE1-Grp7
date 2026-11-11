import { gradeEntryFixture } from './gradeEntryFixture.js';

let state = structuredClone(gradeEntryFixture);

function clone(value) {
  return structuredClone(value);
}

function findClass(classId) {
  const item = state.classes.find(entry => String(entry.id) === String(classId));
  if (!item) {
    const error = new Error('Assigned class was not found.');
    error.status = 404;
    throw error;
  }
  return item;
}

export async function loadTeacherClasses() {
  return clone({ teacher: state.teacher, items: state.classes.map(({ id, subject, subjectCode, gradeLevel, section, room, academicYear, periods }) => ({ id, subject, subjectCode, gradeLevel, section, room, academicYear, periods })) });
}

export async function loadClassGradebook(classId, periodId) {
  const item = findClass(classId);
  const period = item.periods.find(entry => entry.id === periodId) || item.periods[0];
  return clone({
    teacher: state.teacher,
    class: { id: item.id, subject: item.subject, subjectCode: item.subjectCode, gradeLevel: item.gradeLevel, section: item.section, room: item.room, academicYear: item.academicYear },
    period,
    students: item.students,
    grades: item.grades[period.id],
    submission: item.submission[period.id],
  });
}

export async function saveGradeDraft(classId, periodId, grades) {
  const item = findClass(classId);
  if (item.submission[periodId] === 'submitted') {
    const error = new Error('Submitted grades are read-only.');
    error.status = 409;
    throw error;
  }
  item.grades[periodId] = clone(grades);
  return loadClassGradebook(classId, periodId);
}

export async function submitGrades(classId, periodId, grades) {
  const item = findClass(classId);
  if (item.submission[periodId] === 'submitted') {
    const error = new Error('Grades have already been submitted.');
    error.status = 409;
    throw error;
  }
  item.grades[periodId] = clone(grades);
  item.submission[periodId] = 'submitted';
  return loadClassGradebook(classId, periodId);
}

export function resetGradeEntryFixture() {
  state = structuredClone(gradeEntryFixture);
}
