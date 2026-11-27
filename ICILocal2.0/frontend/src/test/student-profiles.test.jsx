import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import StudentProfiles from '../reporting/StudentProfiles.jsx';
import { pages } from '../routes/routeConfig.js';
import * as guidanceApi from '../reporting/guidanceApi.js';

vi.mock('../reporting/guidanceApi.js', async importOriginal => ({
  ...(await importOriginal()),
  searchGuidanceStudents: vi.fn(),
  getGuidanceStudentHistory: vi.fn(),
  listGuidanceRisks: vi.fn(),
  listGuidanceCases: vi.fn(),
}));

const sampleStudents = [
  {
    id: 102,
    first_name: 'Ben',
    middle_name: 'D.',
    last_name: 'Cruz',
    student_number: '2026-0102',
    lrn: '123456789012',
    active: true,
  },
];

const sampleHistory = {
  student: {
    id: 102,
    studentNumber: '2026-0102',
    name: 'Ben D. Cruz',
    lrn: '123456789012',
    active: true,
    campus: 'Iligan Main',
    program: 'ABM',
  },
  terms: [
    {
      enrollmentId: 502,
      academicYear: '2026-2027',
      term: '1st Semester',
      program: 'ABM',
      gradeLevel: 'Grade 11',
      section: '11-ABM-A',
      adviser: 'Ms. Reyes',
      room: 'Room 204',
      status: 'enrolled',
      subjects: [],
    },
  ],
  sectionChanges: [
    {
      fromSection: '11-ABM-B',
      toSection: '11-ABM-A',
      actor: 'Registrar Staff',
      occurredAt: '2026-08-20T09:00:00Z',
    },
  ],
  credentials: {
    applicationNumber: 'APP-2026-0102',
    overallStatus: 'Complete',
    submitted: 4,
    mandatoryTotal: 4,
  },
};

const sampleRisks = {
  items: [
    {
      enrollmentId: 502,
      studentId: 102,
      academicYear: '2026-2027',
      term: '1st Semester',
      section: '11-ABM-A',
      signals: ['failing_grades', 'consecutive_unexcused_absences'],
      incompleteGrades: 0,
      failingGrades: 2,
      missingRequirements: 0,
      consecutiveUnexcusedAbsences: 4,
      case: { id: 31, status: 'monitoring' },
      atRisk: true,
    },
  ],
  total: 1,
};

const sampleCases = {
  items: [
    {
      id: 31,
      enrollmentId: 502,
      studentId: 102,
      studentNumber: '2026-0102',
      name: 'Ben D. Cruz',
      status: 'monitoring',
      reason: 'Four consecutive absences and two failing subjects.',
      createdAt: '2026-11-26T08:00:00Z',
    },
  ],
  total: 1,
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Guidance Student Profiles screen (ICI2-59)', () => {
  it('keeps /reporting/student-profiles assigned exclusively to Guidance Staff', () => {
    const route = pages.find(page => page.path === '/reporting/student-profiles');
    expect(route).toBeDefined();
    expect(route.label).toBe('Student Profiles');
    expect(route.allowedRoles).toEqual(['guidance']);
  });

  it('searches students and displays read-only details, enrollment history, risk signals, and links to counseling cases', async () => {
    guidanceApi.searchGuidanceStudents.mockResolvedValue({ items: sampleStudents, total: 1, page: 0, size: 10 });
    guidanceApi.getGuidanceStudentHistory.mockResolvedValue(sampleHistory);
    guidanceApi.listGuidanceRisks.mockResolvedValue(sampleRisks);
    guidanceApi.listGuidanceCases.mockResolvedValue(sampleCases);

    render(
      <MemoryRouter initialEntries={['/reporting/student-profiles']}>
        <StudentProfiles />
      </MemoryRouter>
    );

    expect(await screen.findByRole('heading', { name: 'Student Profiles' })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Name, LRN or student number'), {
      target: { value: '2026-0102' },
    });
    await waitFor(() =>
      expect(guidanceApi.searchGuidanceStudents).toHaveBeenCalledWith({
        search: '2026-0102',
        page: 0,
        size: 10,
      })
    );

    fireEvent.click(await screen.findByRole('button', { name: 'Open profile for 2026-0102' }));

    expect(await screen.findByRole('heading', { name: 'Ben D. Cruz' })).toBeInTheDocument();
    expect(screen.getByText('Iligan Main')).toBeInTheDocument();
    expect(screen.getAllByText('ABM').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Ms. Reyes')).toBeInTheDocument();
    expect(screen.getByText('11-ABM-B → 11-ABM-A')).toBeInTheDocument();

    // Risk signals & counseling cases displayed
    expect(await screen.findByText('Failing grades')).toBeInTheDocument();
    expect(screen.getByText('Consecutive absences')).toBeInTheDocument();
    expect(await screen.findByText('Four consecutive absences and two failing subjects.')).toBeInTheDocument();

    // Links into the counseling case screen and read-only enforcement (no edit/save forms)
    const caseLink = screen.getByRole('link', { name: 'Open case #31' });
    expect(caseLink).toHaveAttribute('href', '/reporting/guidance?caseId=31');
    expect(screen.queryByRole('textbox', { name: /reason|note/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /save|submit|open counseling case/i })).not.toBeInTheDocument();
  });
});
