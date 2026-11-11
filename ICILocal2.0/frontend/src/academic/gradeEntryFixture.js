export const gradeEntryFixture = {
  teacher: { id: 501, name: 'Michael Reyes' },
  classes: [
    {
      id: 301,
      subject: 'Mathematics in the Modern World',
      subjectCode: 'MATH101',
      gradeLevel: 'Grade 11',
      section: 'STEM 11-A',
      room: 'Room 1',
      academicYear: 'AY 2026-2027',
      students: [
        { id: 1, studentNumber: '2026-0001', name: 'Althea Santos' },
        { id: 2, studentNumber: '2026-0002', name: 'Brandon Cruz' },
        { id: 3, studentNumber: '2026-0003', name: 'Celine Dela Cruz' },
      ],
      periods: [
        { id: 'q1', label: 'First Grading Period', status: 'open', deadline: 'October 15, 2026' },
        { id: 'q2', label: 'Second Grading Period', status: 'open', deadline: 'December 15, 2026' },
      ],
      grades: {
        q1: {
          1: { value: '87.50', kind: 'numeric' },
          2: { value: '', kind: 'empty' },
          3: { value: 'INC', kind: 'mark' },
        },
        q2: {
          1: { value: '', kind: 'empty' },
          2: { value: '', kind: 'empty' },
          3: { value: '', kind: 'empty' },
        },
      },
      submission: { q1: 'draft', q2: 'draft' },
    },
  ],
};

export const approvedNonNumericMarks = ['INC', 'NG', 'DRP'];
