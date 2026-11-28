import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import AssignedClasses from '../academic/AssignedClasses.jsx';
import { loadGradeCatalog, loadTeacherClasses } from '../academic/gradeEntryApi.js';
import { loadClassRoster, loadClassSummary } from '../academic/classListApi.js';

vi.mock('../academic/gradeEntryApi.js', () => ({
  loadGradeCatalog: vi.fn(),
  loadTeacherClasses: vi.fn(),
  loadGradingPeriods: vi.fn(),
}));
vi.mock('../academic/classListApi.js', () => ({
  loadClassRoster: vi.fn(),
  loadClassSummary: vi.fn(),
}));

const catalog = {
  years: [{ id: 1, label: '2026-2027' }],
  terms: [{ id: 10, academic_year_id: 1, name: 'First Semester', code: 'S1' }],
};
const classes = {
  items: [
    { id: 5, subject: 'General Mathematics', subjectCode: 'MATH11', gradeLevel: 'Grade 11', section: 'STEM 11-A', room: '204' },
    { id: 6, subject: 'Physical Science', subjectCode: 'PHYS11', gradeLevel: 'Grade 11', section: 'STEM 11-B', room: null },
  ],
};

function show() {
  render(<MemoryRouter initialEntries={['/academic/class-list']}><AssignedClasses /></MemoryRouter>);
}

beforeEach(() => {
  loadGradeCatalog.mockResolvedValue(catalog);
  loadTeacherClasses.mockResolvedValue(classes);
  loadClassSummary.mockImplementation(async id => id === 5
    ? { studentCount: 32, period: { id: 1, label: 'Q1', status: 'open' }, state: 'draft' }
    : { studentCount: 28, period: { id: 1, label: 'Q1', status: 'locked' }, state: 'submitted' });
  loadClassRoster.mockResolvedValue({
    students: [
      { studentNumber: 'STU-1', name: 'Ana Garcia', enrollmentStatus: 'Enrolled' },
      { studentNumber: 'STU-2', name: 'Miguel Santos', enrollmentStatus: 'Pre-enlisted' },
    ],
  });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('Assigned Classes', () => {
  it('lists the teacher classes with student counts and submission status', async () => {
    show();
    expect(await screen.findByText('General Mathematics')).toBeInTheDocument();
    const row = screen.getByText('General Mathematics').closest('tr');
    await waitFor(() => expect(within(row).getByText('32')).toBeInTheDocument());
    expect(within(row).getByText('Draft')).toBeInTheDocument();
    expect(within(row).getByText('Q1')).toBeInTheDocument();
    const second = screen.getByText('Physical Science').closest('tr');
    await waitFor(() => expect(within(second).getByText('Submitted')).toBeInTheDocument());
    expect(within(second).getByText(/locked/)).toBeInTheDocument();
    expect(loadTeacherClasses).toHaveBeenCalledWith({ academicYearId: '1', termId: '10' });
  });

  it('links each class to Grade Entry for that class and term', async () => {
    show();
    const link = await screen.findByRole('link', { name: /Open grade entry for General Mathematics/ });
    const url = new URL(link.getAttribute('href'), 'http://x');
    expect(url.pathname).toBe('/academic/grade-entry');
    expect(url.searchParams.get('classId')).toBe('5');
    expect(url.searchParams.get('termId')).toBe('10');
  });

  it('opens a read-only roster for a class', async () => {
    show();
    fireEvent.click(await screen.findByRole('button', { name: /View roster for General Mathematics/ }));
    expect(await screen.findByText('Ana Garcia')).toBeInTheDocument();
    expect(screen.getByText('Miguel Santos')).toBeInTheDocument();
    expect(screen.getByText(/2 students/)).toBeInTheDocument();
    expect(loadClassRoster).toHaveBeenCalledWith(5, { academicYearId: '1', termId: '10' });
  });

  it('shows an empty state when no classes are assigned', async () => {
    loadTeacherClasses.mockResolvedValue({ items: [] });
    show();
    expect(await screen.findByText('No classes assigned')).toBeInTheDocument();
  });

  it('shows the server message when classes cannot be loaded', async () => {
    loadTeacherClasses.mockRejectedValue(Object.assign(new Error('Service unavailable.'), { status: 503 }));
    show();
    expect(await screen.findByRole('alert')).toHaveTextContent('Service unavailable.');
  });

  it('keeps the list usable when one class summary fails', async () => {
    loadClassSummary.mockImplementation(async id => {
      if (id === 6) throw new Error('boom');
      return { studentCount: 32, period: null, state: 'none' };
    });
    show();
    const second = (await screen.findByText('Physical Science')).closest('tr');
    await waitFor(() => expect(within(second).getByText('Unavailable')).toBeInTheDocument());
    expect(screen.getByText('General Mathematics')).toBeInTheDocument();
  });

  it('explains when no academic term exists', async () => {
    loadGradeCatalog.mockResolvedValue({ years: [], terms: [] });
    show();
    expect(await screen.findByText('No academic term available')).toBeInTheDocument();
    expect(loadTeacherClasses).not.toHaveBeenCalled();
  });
});
