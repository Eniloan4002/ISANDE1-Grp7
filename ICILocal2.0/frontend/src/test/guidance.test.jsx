import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { AtRiskReports, GuidanceRecords } from '../reporting/GuidanceWorkspace.jsx';
import GuidancePolicyPanel from '../reporting/GuidancePolicyPanel.jsx';
import { pages } from '../routes/routeConfig.js';
import * as guidanceApi from '../reporting/guidanceApi.js';

let mockRoles = ['guidance'];

vi.mock('../auth/AuthContext.jsx', () => ({
  useAuth: () => ({
    user: {
      userId: 12,
      fullName: 'Guidance Counselor',
      grants: [{ campusId: 1, campusCode: 'ILIGAN', campusName: 'Iligan Main', role: 'ROLE_GUIDANCE' }],
    },
    roles: mockRoles,
  }),
}));

vi.mock('../reporting/guidanceApi.js', async importOriginal => ({
  ...(await importOriginal()),
  listGuidanceRisks: vi.fn(),
  getGuidancePolicy: vi.fn(),
  saveGuidancePolicy: vi.fn(),
  listGuidanceCases: vi.fn(),
  getGuidanceCase: vi.fn(),
  createGuidanceCase: vi.fn(),
  updateGuidanceCase: vi.fn(),
  addGuidanceCaseNote: vi.fn(),
  exportGuidanceCase: vi.fn(),
}));

const sampleRisks = [
  {
    enrollmentId: 501,
    studentId: 101,
    studentNumber: '2026-0101',
    name: 'Ana Santos',
    campusId: 1,
    campus: 'Iligan Main',
    academicYearId: 9,
    academicYear: '2026-2027',
    termId: 14,
    term: '1st Semester',
    section: '11-STEM-A',
    gradeLevel: 'Grade 11',
    program: 'STEM',
    status: 'enrolled',
    missingRequirements: 2,
    missingDocuments: 'Form 137, Birth Certificate',
    signals: ['incomplete_grades', 'overdue_credentials'],
    unconfiguredSignals: [],
    incompleteGrades: 1,
    failingGrades: 0,
    consecutiveUnexcusedAbsences: 0,
    case: null,
    atRisk: true,
  },
  {
    enrollmentId: 502,
    studentId: 102,
    studentNumber: '2026-0102',
    name: 'Ben Cruz',
    campusId: 1,
    campus: 'Iligan Main',
    academicYearId: 9,
    academicYear: '2026-2027',
    termId: 14,
    term: '1st Semester',
    section: '11-ABM-A',
    gradeLevel: 'Grade 11',
    program: 'ABM',
    status: 'enrolled',
    missingRequirements: 0,
    missingDocuments: '',
    signals: ['failing_grades', 'consecutive_unexcused_absences'],
    unconfiguredSignals: [],
    incompleteGrades: 0,
    failingGrades: 2,
    consecutiveUnexcusedAbsences: 4,
    case: { id: 31, enrollment_id: 502, status: 'open', version: 1 },
    atRisk: true,
  },
];

const samplePolicy = {
  configured: true,
  campusId: 1,
  termId: 14,
  failingBelow: 75,
  consecutiveAbsences: 3,
  credentialsDueOn: '2026-09-15',
  reason: 'Approved SY 2026-2027 guidance memo',
  version: 2,
};

const sampleCase = {
  id: 31,
  enrollment_id: 502,
  campus_id: 1,
  student_id: 102,
  student_number: '2026-0102',
  name: 'Ben Cruz',
  status: 'open',
  reason: 'Four consecutive unexcused absences and two failing subjects.',
  version: 1,
  created_at: '2026-11-26T08:00:00Z',
  updated_at: '2026-11-26T08:00:00Z',
  notes: [
    {
      id: 1,
      note: 'Initial phone outreach to guardian.',
      authorId: 12,
      author: 'Guidance Counselor',
      createdAt: '2026-11-26T09:00:00Z',
    },
  ],
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  mockRoles = ['guidance'];
});

describe('Guidance At-Risk Dashboard & Counseling Cases (ICI2-58)', () => {
  it('keeps confidential guidance routes restricted strictly to guidance role', () => {
    const guidance = pages.find(page => page.path === '/reporting/guidance');
    const reports = pages.find(page => page.path === '/reporting/reports');
    expect(guidance.allowedRoles).toEqual(['guidance']);
    expect(reports.allowedRoles).toEqual(['guidance']);
  });

  it('filters at-risk students by campus, term, and signal type, inspects evidence, and opens a counseling case', async () => {
    guidanceApi.listGuidanceRisks.mockResolvedValue({
      items: sampleRisks,
      total: 2,
      atRiskTotal: 2,
      page: 0,
      size: 100,
      asOf: '2026-11-27',
    });
    guidanceApi.getGuidancePolicy.mockResolvedValue(samplePolicy);
    guidanceApi.createGuidanceCase.mockResolvedValue({
      id: 45,
      enrollment_id: 501,
      status: 'open',
      version: 1,
    });

    render(
      <MemoryRouter initialEntries={['/reporting/reports']}>
        <AtRiskReports />
      </MemoryRouter>
    );

    expect(await screen.findByRole('heading', { name: 'Reports' })).toBeInTheDocument();
    expect(await screen.findByText('Ana Santos')).toBeInTheDocument();
    expect(screen.getByText('Ben Cruz')).toBeInTheDocument();

    // Filter by signal type
    fireEvent.change(screen.getByLabelText('Signal type'), { target: { value: 'incomplete_grades' } });
    expect(screen.getByText('Ana Santos')).toBeInTheDocument();
    expect(screen.queryByText('Ben Cruz')).not.toBeInTheDocument();

    // Filter by campus & term triggers API call
    fireEvent.change(screen.getByLabelText('Campus', { selector: '#risk-campus-filter' }), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('Term', { selector: '#risk-term-filter' }), { target: { value: '14' } });
    await waitFor(() =>
      expect(guidanceApi.listGuidanceRisks).toHaveBeenCalledWith(
        expect.objectContaining({ campusId: '1', termId: '14' })
      )
    );

    // Inspect risk details & evidence
    fireEvent.click(screen.getByRole('button', { name: 'Inspect Ana Santos' }));
    expect(await screen.findByRole('heading', { name: 'Student risk details — Ana Santos' })).toBeInTheDocument();
    expect(screen.getByText(/Form 137, Birth Certificate/)).toBeInTheDocument();
    expect(screen.getByText('1 INC')).toBeInTheDocument();

    // Open a counseling case from the flagged student
    fireEvent.change(screen.getByLabelText('Initial referral / intervention reason'), {
      target: { value: 'Follow up on INC grade and missing Form 137.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Open counseling case' }));

    await waitFor(() =>
      expect(guidanceApi.createGuidanceCase).toHaveBeenCalledWith({
        enrollmentId: 501,
        reason: 'Follow up on INC grade and missing Form 137.',
      })
    );
    expect(await screen.findByText(/Confidential counseling case #45 opened for Ana Santos/)).toBeInTheDocument();
  });

  it('lists counseling cases, updates status, logs intervention notes, and exports PDF/CSV', async () => {
    guidanceApi.listGuidanceCases.mockResolvedValue({
      items: [{ id: 31, enrollmentId: 502, status: 'open', version: 1, studentNumber: '2026-0102', name: 'Ben Cruz' }],
      total: 1,
      page: 0,
      size: 25,
    });
    guidanceApi.getGuidanceCase.mockResolvedValue(sampleCase);
    guidanceApi.updateGuidanceCase.mockResolvedValue({
      ...sampleCase,
      status: 'monitoring',
      reason: 'Guardian conference scheduled.',
      version: 2,
    });
    guidanceApi.addGuidanceCaseNote.mockResolvedValue({
      ...sampleCase,
      status: 'monitoring',
      version: 3,
      notes: [
        ...sampleCase.notes,
        {
          id: 2,
          note: 'Met with student and adviser for recovery plan.',
          authorId: 12,
          author: 'Guidance Counselor',
          createdAt: '2026-11-27T10:00:00Z',
        },
      ],
    });
    guidanceApi.exportGuidanceCase.mockResolvedValue('ici-guidance-case-31.pdf');

    render(
      <MemoryRouter initialEntries={['/reporting/guidance']}>
        <GuidanceRecords />
      </MemoryRouter>
    );

    expect(await screen.findByRole('heading', { name: 'Guidance Records' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'Confidential counseling case #31' })).toBeInTheDocument();
    expect(await screen.findByText('Initial phone outreach to guardian.')).toBeInTheDocument();

    // Update case status
    fireEvent.change(screen.getByLabelText('Case status', { selector: '#case-status-select' }), {
      target: { value: 'monitoring' },
    });
    fireEvent.change(screen.getByLabelText('Case summary / status reason'), {
      target: { value: 'Guardian conference scheduled.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save case status' }));

    await waitFor(() =>
      expect(guidanceApi.updateGuidanceCase).toHaveBeenCalledWith(31, {
        status: 'monitoring',
        reason: 'Guardian conference scheduled.',
        version: 1,
      })
    );

    // Log intervention note (uses bumped version 2)
    fireEvent.change(screen.getByLabelText('Intervention or counseling note'), {
      target: { value: 'Met with student and adviser for recovery plan.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Log intervention note' }));

    await waitFor(() =>
      expect(guidanceApi.addGuidanceCaseNote).toHaveBeenCalledWith(31, {
        note: 'Met with student and adviser for recovery plan.',
        version: 2,
      })
    );
    expect(await screen.findByText('Met with student and adviser for recovery plan.')).toBeInTheDocument();

    // Export PDF and CSV
    fireEvent.click(screen.getByRole('button', { name: 'Export PDF' }));
    await waitFor(() => expect(guidanceApi.exportGuidanceCase).toHaveBeenCalledWith(31, 'pdf'));
    fireEvent.click(screen.getByRole('button', { name: 'Export CSV' }));
    await waitFor(() => expect(guidanceApi.exportGuidanceCase).toHaveBeenCalledWith(31, 'csv'));
  });

  it('allows authorized staff to edit risk thresholds while keeping them read-only otherwise', async () => {
    guidanceApi.getGuidancePolicy.mockResolvedValue(samplePolicy);
    guidanceApi.saveGuidancePolicy.mockResolvedValue({ ...samplePolicy, failingBelow: 74, version: 3 });

    const { unmount } = render(
      <GuidancePolicyPanel
        campuses={[{ id: 1, name: 'Iligan Main' }]}
        terms={[{ id: 14, name: '1st Semester', academicYear: '2026-2027' }]}
        initialCampusId="1"
        initialTermId="14"
        canConfigure={false}
      />
    );

    expect(await screen.findByText('Configured (v2)')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save risk thresholds' })).not.toBeInTheDocument();
    unmount();

    render(
      <GuidancePolicyPanel
        campuses={[{ id: 1, name: 'Iligan Main' }]}
        terms={[{ id: 14, name: '1st Semester', academicYear: '2026-2027' }]}
        initialCampusId="1"
        initialTermId="14"
        canConfigure
      />
    );

    const failingInput = await screen.findByLabelText('Failing grade below (0–100)');
    fireEvent.change(failingInput, { target: { value: '74' } });
    fireEvent.change(screen.getByLabelText('Approval reference / policy reason (required)'), {
      target: { value: 'Updated per Academic Council memo' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save risk thresholds' }));

    await waitFor(() =>
      expect(guidanceApi.saveGuidancePolicy).toHaveBeenCalledWith('1', '14', {
        failingBelow: '74',
        consecutiveAbsences: '3',
        credentialsDueOn: '2026-09-15',
        reason: 'Updated per Academic Council memo',
        version: 2,
      })
    );
    expect(await screen.findByText('Guidance risk thresholds saved.')).toBeInTheDocument();
  });
});
