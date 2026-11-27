import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import StudentMonitoring from '../academic/StudentMonitoring.jsx';
import { pages } from '../routes/routeConfig.js';
import * as advisoryApi from '../academic/advisoryRoster.js';
import * as monitoringApi from '../academic/studentMonitoringApi.js';

vi.mock('../academic/advisoryRoster.js', () => ({
  loadAdvisoryCatalog: vi.fn(),
  loadAdvisoryRoster: vi.fn(),
}));

vi.mock('../academic/studentMonitoringApi.js', () => ({
  loadAdviserRisks: vi.fn(),
  referStudentToGuidance: vi.fn(),
}));

HTMLDialogElement.prototype.showModal ??= function showModal() {
  this.open = true;
};
HTMLDialogElement.prototype.close ??= function close() {
  this.open = false;
};

const sampleRoster = {
  sections: [
    {
      id: 21,
      name: '11-STEM-A',
      gradeLevel: 'Grade 11',
      program: 'STEM',
      students: [
        { studentNumber: '2026-0101', name: 'Ana Santos', enrollmentStatus: 'Enrolled', requirementsStatus: 'Incomplete' },
        { studentNumber: '2026-0102', name: 'Ben Cruz', enrollmentStatus: 'Enrolled', requirementsStatus: 'Complete' },
      ],
    },
  ],
};

const sampleRisks = {
  asOf: '2026-11-27',
  items: [
    {
      enrollmentId: 501,
      studentNumber: '2026-0101',
      name: 'Ana Santos',
      section: '11-STEM-A',
      program: 'STEM',
      gradeLevel: 'Grade 11',
      status: 'enrolled',
      signals: ['incomplete_grades', 'overdue_credentials'],
      unconfiguredSignals: [],
      incompleteGrades: 1,
      failingGrades: 0,
      missingRequirements: 2,
      missingDocuments: 'Form 137, Birth Certificate',
      consecutiveUnexcusedAbsences: 0,
      referredToGuidance: false,
      case: null,
      atRisk: true,
    },
    {
      enrollmentId: 502,
      studentNumber: '2026-0102',
      name: 'Ben Cruz',
      section: '11-STEM-A',
      program: 'STEM',
      gradeLevel: 'Grade 11',
      status: 'enrolled',
      signals: ['failing_grades', 'consecutive_unexcused_absences'],
      unconfiguredSignals: [],
      incompleteGrades: 0,
      failingGrades: 2,
      missingRequirements: 0,
      missingDocuments: '',
      consecutiveUnexcusedAbsences: 4,
      referredToGuidance: true,
      case: null,
      atRisk: true,
    },
  ],
  total: 2,
  atRiskTotal: 2,
};

function setupMocks() {
  advisoryApi.loadAdvisoryCatalog.mockResolvedValue({
    years: [{ id: 9, label: '2026-2027' }],
    terms: [{ id: 14, name: '1st Semester', academicYearId: 9 }],
  });
  advisoryApi.loadAdvisoryRoster.mockResolvedValue(sampleRoster);
  monitoringApi.loadAdviserRisks.mockResolvedValue(sampleRisks);
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Adviser Student Monitoring screen (ICI2-67)', () => {
  it('keeps /academic/student-monitoring assigned exclusively to Teacher Adviser', () => {
    const route = pages.find(page => page.path === '/academic/student-monitoring');
    expect(route).toBeDefined();
    expect(route.label).toBe('Student Monitoring');
    expect(route.allowedRoles).toEqual(['adviser']);
  });

  it('shows section risk signals, filters by signal type, and submits a Guidance referral', async () => {
    setupMocks();
    monitoringApi.referStudentToGuidance.mockResolvedValue({ referred: true, enrollmentId: 501 });

    render(
      <MemoryRouter initialEntries={['/academic/student-monitoring']}>
        <StudentMonitoring />
      </MemoryRouter>
    );

    expect(await screen.findByRole('heading', { name: 'Student Monitoring' })).toBeInTheDocument();
    expect(await screen.findByText('Ana Santos')).toBeInTheDocument();
    expect(screen.getByText('Ben Cruz')).toBeInTheDocument();

    await waitFor(() =>
      expect(monitoringApi.loadAdviserRisks).toHaveBeenCalledWith(
        expect.objectContaining({ academicYearId: '9', termId: '14', sectionId: '21' })
      )
    );

    // Filter by incomplete grades
    fireEvent.change(screen.getByLabelText('Signal filter'), { target: { value: 'incomplete_grades' } });
    expect(screen.getByText('Ana Santos')).toBeInTheDocument();
    expect(screen.queryByText('Ben Cruz')).not.toBeInTheDocument();

    // Open monitoring detail & refer Ana Santos to Guidance
    fireEvent.click(screen.getByRole('button', { name: 'Monitor Ana Santos' }));
    expect(await screen.findByRole('heading', { name: 'Student monitoring details — Ana Santos' })).toBeInTheDocument();
    expect(screen.getByText(/Form 137, Birth Certificate/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Referral reason'), {
      target: { value: 'Student has an unresolved INC and two missing credentials.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Refer to Guidance' }));

    expect(screen.getByRole('dialog', { name: 'Refer Ana Santos to Guidance?' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(monitoringApi.referStudentToGuidance).toHaveBeenCalledWith({
        enrollmentId: 501,
        reason: 'Student has an unresolved INC and two missing credentials.',
      })
    );
    expect(await screen.findByText(/Referral submitted for Ana Santos/)).toBeInTheDocument();
  });

  it('hides confidential counseling notes for already-referred students', async () => {
    setupMocks();

    render(
      <MemoryRouter initialEntries={['/academic/student-monitoring']}>
        <StudentMonitoring />
      </MemoryRouter>
    );

    fireEvent.click(await screen.findByRole('button', { name: 'View Ben Cruz' }));
    expect(await screen.findByRole('heading', { name: 'Student monitoring details — Ben Cruz' })).toBeInTheDocument();
    expect(screen.getByText(/Confidential counseling notes and intervention details are restricted to Guidance Staff only/)).toBeInTheDocument();
    expect(screen.queryByLabelText('Referral reason')).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /Guidance Records/i })).not.toBeInTheDocument();
  });
});
