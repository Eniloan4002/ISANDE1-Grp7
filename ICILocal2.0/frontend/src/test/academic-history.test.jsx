import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import AcademicHistory, { generalAverage, subjectState } from '../academic/AcademicHistory.jsx';
import { pages } from '../routes/routeConfig.js';
import { apiRequest } from '../api.js';

vi.mock('../api.js', () => ({ apiRequest: vi.fn() }));

const students = [
  { id: 1, first_name: 'Ana', middle_name: null, last_name: 'Santos', student_number: 'STU-3f2a9c1d-7b4e-4c1a-9e2f-0a1b2c3d4e5f', lrn: '123456789012', active: true, section: 'STEM 11-A' },
  { id: 2, first_name: 'Ben', middle_name: 'R', last_name: 'Cruz', student_number: '2026-0002', lrn: null, active: false },
];
const grade = (sequence, value, state = 'finalized', mark = null) => ({ period: `Q${sequence}`, sequence, value, mark, state });
const history = {
  student: { id: 1, name: 'Ana Santos', studentNumber: 'STU-3f2a9c1d-7b4e-4c1a-9e2f-0a1b2c3d4e5f', lrn: '123456789012', active: true, campus: 'Iligan Main', program: 'STEM', programName: 'Science, Technology, Engineering and Mathematics' },
  terms: [{
    enrollmentId: 50, academicYear: '2026-2027', term: 'First Term', status: 'enrolled', section: 'STEM 11-A', gradeLevel: 'Grade 11', adviser: 'Ms. Reyes', room: '101',
    subjects: [
      { code: 'MATH101', name: 'General Mathematics', units: 3, grades: [grade(1, 90), grade(2, 92)] },
      { code: 'SCI101', name: 'Earth Science', units: 3, grades: [grade(1, 85), grade(2, 88, 'submitted')] },
      { code: 'ENG101', name: 'Oral Communication', units: 3, grades: [grade(1, null, 'submitted', 'INC')] },
    ],
  }],
  sectionChanges: [{ fromSection: 'STEM 11-B', toSection: 'STEM 11-A', actor: 'Registrar', occurredAt: '2026-08-10T02:00:00Z' }],
  credentials: { applicationNumber: 'APP-77', overallStatus: 'Incomplete', mandatoryTotal: 4, submitted: 3, complete: false, items: [{ code: 'PSA', name: 'PSA birth certificate', mandatory: true, status: 'submitted' }, { code: 'F137', name: 'Form 137', mandatory: true, status: 'missing' }] },
  adviserChanges: [],
  incompleteCases: [{ id: 9, status: 'open', resolvedAt: null, subjectCode: 'ENG101', period: 'Q1', enrollmentId: 50 }],
  withdrawals: [],
};

const listCalls = () => apiRequest.mock.calls.filter(([path]) => path.startsWith('/api/academic/history/students?'));

function Location() {
  const location = useLocation();
  return <output data-testid="location">{location.search}</output>;
}

function show(url = '/academic/registrar-records') {
  return render(<MemoryRouter initialEntries={[url]}><AcademicHistory /><Location /></MemoryRouter>);
}

beforeEach(() => {
  apiRequest.mockImplementation(async path => {
    if (path.startsWith('/api/academic/history/students?')) return { items: students, total: 2, page: 0, size: 12 };
    if (path === '/api/academic/history/students/1') return history;
    if (path === '/api/academic/history/students/2') return { ...history, student: { ...history.student, id: 2, name: 'Ben R Cruz', studentNumber: '2026-0002', active: false }, terms: [], credentials: null, incompleteCases: [], sectionChanges: [] };
    throw new Error(`Unexpected request ${path}`);
  });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('Academic History master-detail (ICI2-103)', () => {
  it('keeps the route and its roles', () => {
    expect(pages.filter(page => page.path === '/academic/registrar-records').length).toBeGreaterThan(0);
  });

  it('lists students with LRN and section, and never shows a raw UUID student number', async () => {
    show();
    const list = await screen.findByRole('region', { name: 'Students' });
    const ana = await within(list).findByRole('button', { name: /Ana Santos/ });
    expect(ana).toHaveTextContent('LRN 123456789012');
    expect(ana).toHaveTextContent('STEM 11-A');
    const ben = within(list).getByRole('button', { name: /Ben R Cruz/ });
    expect(ben).toHaveTextContent('2026-0002');
    expect(ben).toHaveTextContent('Archived');
    expect(screen.getByRole('heading', { name: 'Select a student' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('3f2a9c1d-7b4e');
  });

  it('opens a record without reloading the list and puts the student in the URL', async () => {
    show();
    fireEvent.click(await screen.findByRole('button', { name: /Ana Santos/ }));
    const record = await screen.findByRole('article', { name: 'Academic record for Ana Santos' });
    expect(screen.getByTestId('location')).toHaveTextContent('?studentId=1&tab=grades');
    expect(listCalls()).toHaveLength(1);
    expect(screen.getByRole('button', { name: /Ana Santos/ })).toHaveAttribute('aria-current', 'true');
    expect(within(record).getByText('STU-3F2A9C1D')).toHaveAttribute('title', history.student.studentNumber);
    expect(document.body).not.toHaveTextContent('3f2a9c1d-7b4e');
    expect(within(record).getByRole('button', { name: 'Print record' })).toBeInTheDocument();
  });

  it('opens the student and tab named in a shared link', async () => {
    show('/academic/registrar-records?studentId=1&tab=credentials');
    expect(await screen.findByRole('tab', { name: 'Credentials' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel')).toHaveTextContent('3 of 4 mandatory submitted');
    expect(screen.getByRole('row', { name: /Form 137/ })).toHaveTextContent('Missing');
  });

  it('shows summary tiles for average, subjects, incomplete and credentials', async () => {
    show('/academic/registrar-records?studentId=1');
    const record = await screen.findByRole('article', { name: 'Academic record for Ana Santos' });
    expect(within(record).getByText('General average').parentElement).toHaveTextContent('91.00');
    expect(within(record).getByText('Subjects').parentElement).toHaveTextContent('3');
    expect(within(record).getByText('Incomplete', { selector: '.ui-stat-label' }).parentElement).toHaveTextContent('1');
    expect(within(record).getByText('Credentials', { selector: '.ui-stat-label' }).parentElement).toHaveTextContent('3/4');
  });

  it('shows grades by subject with period columns and a state chip', async () => {
    show('/academic/registrar-records?studentId=1');
    const table = await screen.findByRole('table');
    expect(within(table).getByRole('columnheader', { name: 'Q1' })).toBeInTheDocument();
    expect(within(table).getByRole('columnheader', { name: 'Q2' })).toBeInTheDocument();
    const math = within(table).getByRole('row', { name: /General Mathematics/ });
    expect(math).toHaveTextContent('90.00');
    expect(math).toHaveTextContent('Finalized');
    expect(within(table).getByRole('row', { name: /Earth Science/ })).toHaveTextContent('88.00*');
    expect(within(table).getByRole('row', { name: /Oral Communication/ })).toHaveTextContent('Incomplete');
  });

  it('switches tabs with the keyboard and keeps the tab in the URL', async () => {
    show('/academic/registrar-records?studentId=1');
    const grades = await screen.findByRole('tab', { name: 'Grades' });
    grades.focus();
    fireEvent.keyDown(grades, { key: 'ArrowRight' });
    const sections = screen.getByRole('tab', { name: 'Sections' });
    expect(sections).toHaveAttribute('aria-selected', 'true');
    expect(document.activeElement).toBe(sections);
    expect(screen.getByTestId('location')).toHaveTextContent('tab=sections');
    expect(screen.getByRole('tabpanel')).toHaveTextContent('STEM 11-B → STEM 11-A');
    fireEvent.keyDown(sections, { key: 'End' });
    expect(screen.getByRole('tab', { name: 'Notes' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tabpanel')).toHaveTextContent('Incomplete grade · ENG101 Q1');
  });

  it('shows empty states for a student without enrollments or an application', async () => {
    show('/academic/registrar-records?studentId=2');
    expect(await screen.findByRole('heading', { name: 'No enrollment records yet' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('tab', { name: 'Credentials' }));
    expect(screen.getByRole('heading', { name: 'No application linked' })).toBeInTheDocument();
  });

  it('shows the server message when a record cannot be loaded', async () => {
    apiRequest.mockImplementation(async path => {
      if (path.startsWith('/api/academic/history/students?')) return { items: students, total: 2 };
      throw new Error('Student not found.');
    });
    show('/academic/registrar-records?studentId=99');
    expect(await screen.findByRole('alert')).toHaveTextContent('Student not found.');
  });
});

describe('history helpers', () => {
  it('derives a subject state from its grades', () => {
    expect(subjectState({ grades: [] }).label).toBe('No grades');
    expect(subjectState({ grades: [grade(1, 90), grade(2, 91)] }).label).toBe('Finalized');
    expect(subjectState({ grades: [grade(1, 90), grade(2, 91, 'submitted')] }).label).toBe('Submitted');
    expect(subjectState({ grades: [grade(1, null, 'finalized', 'INC')] }).label).toBe('Incomplete');
  });

  it('averages only fully finalized numeric subjects', () => {
    expect(generalAverage(history.terms[0])).toEqual({ value: 91, subjects: 1 });
    expect(generalAverage({ subjects: [{ grades: [grade(1, 80, 'submitted')] }] })).toBeNull();
    expect(generalAverage(undefined)).toBeNull();
  });
});
