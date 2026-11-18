// Mock ICI-28 API adapter. Replace these functions with apiRequest calls when
// the incomplete-case endpoints are implemented by the backend.
const wait = () => new Promise(resolve => setTimeout(resolve, 120));

let cases = [
  {
    id: 'INC-2026-001', studentNumber: 'ICI-2026-0186', studentName: 'Leah Marie Santos',
    gradeLevel: 'Grade 11', section: '11-STEM-A', subjectCode: 'MATH-11', subject: 'General Mathematics',
    teacherId: 'teacher-demo', teacher: 'Michael Reyes', teacherUsername: 'mreyes',
    academicYear: '2026–2027', gradingPeriod: 'Quarter 1', currentMark: 'INC', status: 'pending_teacher_submission',
    periodLocked: false, updatedAt: '2026-10-18T09:15:00+08:00', proposedGrade: null,
    followUps: [{ id: 'f-1', note: 'Teacher notified about the missing assessment and completion schedule.', method: 'Email', by: 'R. Santos', at: '2026-10-18T09:15:00+08:00' }],
  },
  {
    id: 'INC-2026-002', studentNumber: 'ICI-2026-0456', studentName: 'Marco Luis Dela Cruz',
    gradeLevel: 'Grade 12', section: '12-TVL-ICT', subjectCode: 'ICT-12', subject: 'Computer Programming 2',
    teacherId: 'teacher-demo', teacher: 'Michael Reyes', teacherUsername: 'mreyes',
    academicYear: '2026–2027', gradingPeriod: 'Quarter 1', currentMark: 'INC', status: 'registrar_review',
    periodLocked: false, updatedAt: '2026-10-20T14:05:00+08:00', proposedGrade: 88.5,
    followUps: [
      { id: 'f-2', note: 'Student completed the practical assessment; proposed grade submitted for registrar review.', method: 'In person', by: 'Michael Reyes', at: '2026-10-20T14:05:00+08:00' },
      { id: 'f-3', note: 'Teacher confirmed the completion deadline with the student.', method: 'Phone', by: 'Michael Reyes', at: '2026-10-16T11:30:00+08:00' },
    ],
  },
  {
    id: 'INC-2025-014', studentNumber: 'ICI-2025-0082', studentName: 'Angela Mae Villanueva',
    gradeLevel: 'Grade 11', section: '11-ABM-B', subjectCode: 'FABM-11', subject: 'Fundamentals of Accountancy',
    teacherId: 'teacher-rosa', teacher: 'Rosa Mendoza', teacherUsername: 'rmendoza',
    academicYear: '2025–2026', gradingPeriod: 'Quarter 4', currentMark: 'INC', status: 'grade_correction',
    periodLocked: true, updatedAt: '2026-06-27T10:20:00+08:00', proposedGrade: 84,
    followUps: [{ id: 'f-4', note: 'Completion proposal recorded. Locked period requires the grade correction approval workflow.', method: 'System note', by: 'Rosa Mendoza', at: '2026-06-27T10:20:00+08:00' }],
  },
  {
    id: 'INC-2025-011', studentNumber: 'ICI-2025-0211', studentName: 'Joshua Tan',
    gradeLevel: 'Grade 12', section: '12-HUMSS-A', subjectCode: 'ENG-12', subject: 'Creative Writing',
    teacherId: 'teacher-rosa', teacher: 'Rosa Mendoza', teacherUsername: 'rmendoza',
    academicYear: '2025–2026', gradingPeriod: 'Quarter 3', currentMark: '91.00', status: 'completed',
    periodLocked: false, updatedAt: '2026-05-02T16:40:00+08:00', proposedGrade: 91,
    followUps: [
      { id: 'f-5', note: 'Final mark 91 recorded and INC case closed after completion was verified.', method: 'System note', by: 'R. Santos', at: '2026-05-02T16:40:00+08:00' },
      { id: 'f-6', note: 'Teacher confirmed the final writing portfolio was submitted.', method: 'Email', by: 'Rosa Mendoza', at: '2026-04-29T09:00:00+08:00' },
    ],
  },
  {
    id: 'INC-2024-006', studentNumber: 'ICI-2024-0104', studentName: 'Catherine Flores',
    gradeLevel: 'Grade 11', section: '11-STEM-C', subjectCode: 'PHY-11', subject: 'Earth and Life Science',
    teacherId: 'teacher-rosa', teacher: 'Rosa Mendoza', teacherUsername: 'rmendoza',
    academicYear: '2024–2025', gradingPeriod: 'Quarter 2', currentMark: 'INC', status: 'pending_teacher_submission',
    periodLocked: true, updatedAt: '2025-01-12T13:10:00+08:00', proposedGrade: null,
    followUps: [{ id: 'f-7', note: 'Registrar requested the subject teacher provide the missing completion evidence.', method: 'Email', by: 'R. Santos', at: '2025-01-12T13:10:00+08:00' }],
  },
];

const clone = value => JSON.parse(JSON.stringify(value));
const statusLabel = {
  pending_teacher_submission: 'Pending Teacher Submission',
  registrar_review: 'For Registrar Review / Update',
  grade_correction: 'For Grade Correction',
  completed: 'Completed',
};
function visibleToRole(item, role) {
  // In the mock dataset, teacher-demo is the signed-in teacher's sample roster.
  // The real API must enforce assignment and campus scope on the server.
  return role !== 'teacher' || item.teacherId === 'teacher-demo';
}

export async function listIncompleteCases({ role }) {
  await wait();
  const items = cases.filter(item => visibleToRole(item, role));
  return {
    items: clone(items),
    filters: Object.fromEntries(['academicYear', 'gradingPeriod', 'gradeLevel', 'section', 'subject', 'teacher', 'status'].map(key => [
      key, [...new Set(items.map(item => item[key]).filter(Boolean))].sort(),
    ])),
  };
}

export async function getIncompleteCase(caseId, role) {
  await wait();
  const item = cases.find(row => row.id === caseId && visibleToRole(row, role));
  if (!item) throw new Error('This incomplete-grade case is unavailable in your assigned scope.');
  return clone(item);
}

export async function addFollowUp(caseId, input) {
  await wait();
  const item = cases.find(row => row.id === caseId);
  if (!item) throw new Error('The incomplete-grade case could not be found.');
  const entry = { id: `mock-${Date.now()}`, note: input.note.trim(), method: input.method, by: input.actor, at: new Date().toISOString() };
  item.followUps.unshift(entry);
  item.updatedAt = entry.at;
  return clone(item);
}

export async function proposeCompletion(caseId, input) {
  await wait();
  const item = cases.find(row => row.id === caseId);
  if (!item || item.status === 'completed') throw new Error('This case cannot accept a completion proposal.');
  item.proposedGrade = Number(input.grade);
  item.status = item.periodLocked ? 'grade_correction' : 'registrar_review';
  item.updatedAt = new Date().toISOString();
  item.followUps.unshift({
    id: `mock-${Date.now()}`, note: `Proposed completed grade ${item.proposedGrade.toFixed(2)} submitted for review. ${input.note.trim()}`.trim(),
    method: 'System note', by: input.actor, at: item.updatedAt,
  });
  return clone(item);
}

export async function finalizeCompletion(caseId, input) {
  await wait();
  const item = cases.find(row => row.id === caseId);
  if (!item || item.periodLocked) throw new Error('Locked periods must use the grade correction approval workflow. This mock cannot approve or apply a correction.');
  if (item.proposedGrade == null && input.grade == null) throw new Error('Enter or review a proposed grade before finalizing.');
  item.proposedGrade = Number(input.grade ?? item.proposedGrade);
  item.currentMark = item.proposedGrade.toFixed(2);
  item.status = 'completed';
  item.updatedAt = new Date().toISOString();
  item.followUps.unshift({
    id: `mock-${Date.now()}`, note: `Final grade ${item.currentMark} recorded and case closed. ${input.note.trim()}`.trim(),
    method: 'System note', by: input.actor, at: item.updatedAt,
  });
  return clone(item);
}

export { statusLabel };
