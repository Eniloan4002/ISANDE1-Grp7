import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import AcademicAssignments from '../academic/AcademicAssignments.jsx';
import { pages } from '../routes/routeConfig.js';

const catalog = {
  years: [{ id: 1, label: '2026-2027' }],
  terms: [{ id: 2, academic_year_id: 1, name: 'First Term', code: 'T1' }],
  campuses: [{ id: 3, name: 'Iligan Campus' }],
};

const assignments = {
  academicYear: '2026-2027',
  term: 'First Term',
  sections: [{ id: 10, name: 'STEM 11-A', gradeLevel: 'Grade 11', room: 'Room 1', capacity: 40, adviserId: 20, adviser: 'Ana Adviser', version: 4 }],
  classes: [{ classId: 30, sectionId: 10, section: 'STEM 11-A', subjectId: 40, subjectCode: 'MATH', subject: 'Mathematics', teacherId: 50, teacher: 'Tom Teacher', version: 6 }],
  teachers: [{ id: 50, name: 'Tom Teacher' }, { id: 51, name: 'New Teacher' }],
  advisers: [{ id: 20, name: 'Ana Adviser' }, { id: 21, name: 'New Adviser' }],
  subjects: [{ id: 40, code: 'MATH', name: 'Mathematics', units: 3 }],
};

const mocks = vi.hoisted(() => ({
  loadAssignmentCatalog: vi.fn(),
  loadAssignments: vi.fn(),
  createClassOffering: vi.fn(),
  assignTeacher: vi.fn(),
  assignAdviser: vi.fn(),
}));

vi.mock('../academic/assignmentApi.js', () => mocks);

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderPage() {
  mocks.loadAssignmentCatalog.mockResolvedValue(catalog);
  mocks.loadAssignments.mockResolvedValue(assignments);
  return render(<AcademicAssignments />);
}

describe('Academic Assignments', () => {
  it('is registered for Registrar users only', () => {
    expect(pages.find(page => page.path === '/academic/assignments').allowedRoles).toEqual(['registrar']);
  });

  it('renders context, sections, class offerings, staff, and subjects', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Teacher & Adviser Assignments' })).toBeInTheDocument();
    expect((await screen.findAllByText('STEM 11-A')).length).toBeGreaterThan(0);
    expect(screen.getByText('MATH')).toBeInTheDocument();
    expect(screen.getByText('Mathematics')).toBeInTheDocument();
    expect(screen.getAllByRole('option', { name: 'New Teacher' })).toHaveLength(2);
    expect(screen.getByRole('option', { name: 'New Adviser' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /MATH — Mathematics/ })).toBeInTheDocument();
  });

  it('sends exact create and assignment payloads including versions', async () => {
    renderPage();
    await screen.findByRole('heading', { name: 'Class offerings' });
    mocks.createClassOffering.mockResolvedValue({});
    fireEvent.click(screen.getByRole('button', { name: 'Create offering' }));
    await waitFor(() => expect(mocks.createClassOffering).toHaveBeenCalledWith({
      academicYearId: 1, termId: 2, sectionId: 10, subjectId: 40, teacherId: 50,
    }));

    fireEvent.change(screen.getByRole('combobox', { name: 'Subject Teacher for STEM 11-A MATH' }), { target: { value: '51' } });
    mocks.assignTeacher.mockResolvedValue({});
    fireEvent.click(screen.getByRole('button', { name: 'Save teacher' }));
    await waitFor(() => expect(mocks.assignTeacher).toHaveBeenCalledWith(30, {
      academicYearId: 1, termId: 2, teacherId: 51, version: 6,
    }));

    fireEvent.change(screen.getByRole('combobox', { name: 'Teacher Adviser for STEM 11-A' }), { target: { value: '21' } });
    mocks.assignAdviser.mockResolvedValue({});
    fireEvent.click(screen.getByRole('button', { name: 'Save adviser' }));
    await waitFor(() => expect(mocks.assignAdviser).toHaveBeenCalledWith(10, {
      academicYearId: 1, termId: 2, adviserId: 21, version: 4,
    }));
  });

  it('shows loading, empty, and conflict states', async () => {
    let resolveCatalog;
    mocks.loadAssignmentCatalog.mockReturnValue(new Promise(resolve => { resolveCatalog = resolve; }));
    render(<AcademicAssignments />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading academic context');
    resolveCatalog(catalog);
    mocks.loadAssignments.mockResolvedValue({ ...assignments, sections: [], classes: [] });
    expect(await screen.findByText('No sections found for this academic context.')).toBeInTheDocument();
    expect(screen.getByText('No class offerings found for this academic context.')).toBeInTheDocument();
  });

  it('reports stale-version conflicts without hiding the page', async () => {
    renderPage();
    await screen.findByRole('heading', { name: 'Class offerings' });
    mocks.assignTeacher.mockRejectedValueOnce(Object.assign(new Error('conflict'), { status: 409 }));
    fireEvent.click(screen.getByRole('button', { name: 'Save teacher' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('changed by another user');
    expect(screen.getByRole('heading', { name: 'Class offerings' })).toBeInTheDocument();
  });
});
