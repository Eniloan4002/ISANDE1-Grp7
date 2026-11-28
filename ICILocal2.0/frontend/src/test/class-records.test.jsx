import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import ClassRecords, { describeGrade } from '../academic/ClassRecords.jsx';
import { loadGradeCatalog } from '../academic/gradeEntryApi.js';
import { loadAdvisorySections, loadClassRecords } from '../academic/classRecordsApi.js';

vi.mock('../academic/gradeEntryApi.js', () => ({ loadGradeCatalog: vi.fn() }));
vi.mock('../academic/classRecordsApi.js', () => ({ loadAdvisorySections: vi.fn(), loadClassRecords: vi.fn() }));

const catalog = {
  years: [{ id: 1, label: '2026-2027' }],
  terms: [{ id: 10, academic_year_id: 1, name: 'First Semester', code: 'S1' }],
};
const records = {
  section: { id: 5, name: 'STEM 11-A', gradeLevel: 'Grade 11' },
  periods: [{ id: 100, code: 'Q1' }, { id: 101, code: 'Q2' }],
  period: { id: 100, code: 'Q1' },
  subjects: [{ classId: 1, code: 'MATH11', name: 'General Mathematics' }, { classId: 2, code: 'PHYS11', name: 'Physical Science' }],
  students: [
    { enrollmentId: 7, studentNumber: 'STU-1', name: 'Ana Garcia', grades: { 1: { value: 91.5, mark: null, state: 'submitted' }, 2: { value: 74, mark: null, state: 'finalized' } } },
    { enrollmentId: 8, studentNumber: 'STU-2', name: 'Ben Cruz', grades: { 1: { value: null, mark: 'INC', state: 'submitted' } } },
  ],
};

beforeEach(() => {
  loadGradeCatalog.mockResolvedValue(catalog);
  loadAdvisorySections.mockResolvedValue([{ id: 5, name: 'STEM 11-A', gradeLevel: 'Grade 11' }]);
  loadClassRecords.mockResolvedValue(records);
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('grade cell wording', () => {
  it('marks INC, failing and missing grades with words, not only colour', () => {
    expect(describeGrade({ mark: 'INC' })).toMatchObject({ kind: 'inc', text: 'INC', note: 'Incomplete' });
    expect(describeGrade({ value: 74.99 })).toMatchObject({ kind: 'fail', note: 'Failing' });
    expect(describeGrade({ value: 75 })).toMatchObject({ kind: 'pass', text: '75.00', note: '' });
    expect(describeGrade({ mark: 'DRP' })).toMatchObject({ kind: 'mark', text: 'DRP' });
    expect(describeGrade(undefined)).toMatchObject({ kind: 'missing', text: '—' });
  });
});

describe('Class Records', () => {
  it('shows each student against every subject in one table', async () => {
    render(<ClassRecords />);
    const table = await screen.findByRole('table');
    expect(within(table).getByText('General Mathematics')).toBeInTheDocument();
    expect(within(table).getByText('Physical Science')).toBeInTheDocument();
    const ana = within(table).getByText('Ana Garcia').closest('tr');
    expect(within(ana).getByText('91.50')).toBeInTheDocument();
    expect(within(ana).getByText('74.00')).toBeInTheDocument();
    expect(within(ana).getByText('Failing')).toBeInTheDocument();
    const ben = within(table).getByText('Ben Cruz').closest('tr');
    expect(within(ben).getByText('INC')).toBeInTheDocument();
    expect(within(ben).getByText('Incomplete')).toBeInTheDocument();
    expect(loadClassRecords).toHaveBeenCalledWith({ academicYearId: '1', termId: '10' }, '5', '', expect.anything());
  });

  it('counts students with an INC or a failing grade', async () => {
    render(<ClassRecords />);
    await screen.findByRole('table');
    const flags = screen.getByLabelText('Grade flags');
    expect(within(flags).getByText('With an INC').nextSibling).toHaveTextContent('1');
    expect(within(flags).getByText(/Failing \(below 75\)/).nextSibling).toHaveTextContent('1');
  });

  it('is read-only', async () => {
    render(<ClassRecords />);
    await screen.findByRole('table');
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    expect(screen.getByText(/Only submitted grades are shown/)).toBeInTheDocument();
  });

  it('loads the chosen grading period', async () => {
    render(<ClassRecords />);
    await screen.findByRole('table');
    await waitFor(() => expect(screen.getByLabelText('Grading period')).toHaveValue('100'));
    fireEvent.change(screen.getByLabelText('Grading period'), { target: { value: '101' } });
    await waitFor(() => expect(loadClassRecords).toHaveBeenLastCalledWith({ academicYearId: '1', termId: '10' }, '5', '101', expect.anything()));
  });

  it('offers a section picker only when the adviser has several sections', async () => {
    loadAdvisorySections.mockResolvedValue([{ id: 5, name: 'STEM 11-A' }, { id: 6, name: 'STEM 11-B' }]);
    render(<ClassRecords />);
    await screen.findByRole('table');
    fireEvent.change(screen.getByLabelText('Section'), { target: { value: '6' } });
    await waitFor(() => expect(loadClassRecords).toHaveBeenLastCalledWith({ academicYearId: '1', termId: '10' }, '6', '', expect.anything()));
  });

  it('explains when the adviser has no section', async () => {
    loadAdvisorySections.mockResolvedValue([]);
    render(<ClassRecords />);
    expect(await screen.findByText('No advisory section assigned')).toBeInTheDocument();
    expect(loadClassRecords).not.toHaveBeenCalled();
  });

  it('explains empty sections and sections without subjects', async () => {
    loadClassRecords.mockResolvedValueOnce({ ...records, students: [] });
    const { unmount } = render(<ClassRecords />);
    expect(await screen.findByText('No students in this section')).toBeInTheDocument();
    unmount();
    loadClassRecords.mockResolvedValueOnce({ ...records, subjects: [] });
    render(<ClassRecords />);
    expect(await screen.findByText('No subjects set up')).toBeInTheDocument();
  });

  it('shows the server message when the records cannot be loaded', async () => {
    loadClassRecords.mockRejectedValue(new Error('Advisory section not found.'));
    render(<ClassRecords />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Advisory section not found.');
  });
});
