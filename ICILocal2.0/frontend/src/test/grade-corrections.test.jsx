import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import GradeCorrections from '../academic/GradeCorrections.jsx';
import { pages } from '../routes/routeConfig.js';
import * as entryApi from '../academic/gradeEntryApi.js';
import * as corrApi from '../academic/teacherGradeCorrectionsApi.js';

vi.mock('../academic/gradeEntryApi.js', () => ({
  loadGradeCatalog: vi.fn(),
  loadTeacherClasses: vi.fn(),
  loadGradingPeriods: vi.fn(),
}));

vi.mock('../academic/teacherGradeCorrectionsApi.js', async importOriginal => ({
  ...(await importOriginal()),
  loadCorrectionGradebook: vi.fn(),
  listMyGradeCorrections: vi.fn(),
  getGradeCorrectionDetail: vi.fn(),
  submitGradeCorrection: vi.fn(),
  downloadCorrectionEvidence: vi.fn(),
}));

const sampleGradebook = {
  class: { classId: 10, subject: 'General Mathematics', subjectCode: 'MATH11', section: '11-STEM-A' },
  period: { id: 201, code: 'Q1', status: 'locked' },
  state: 'finalized',
  periodLocked: true,
  students: [
    {
      gradeId: 501,
      enrollmentId: 301,
      studentNumber: '2026-0001',
      name: 'Ana Santos',
      value: 84.5,
      mark: null,
      state: 'finalized',
      version: 2,
      eligible: true,
    },
    {
      gradeId: 502,
      enrollmentId: 302,
      studentNumber: '2026-0002',
      name: 'Ben Cruz',
      value: null,
      mark: 'INC',
      state: 'submitted',
      version: 1,
      eligible: true,
    },
    {
      gradeId: null,
      enrollmentId: 303,
      studentNumber: '2026-0003',
      name: 'Cara Lim',
      value: null,
      mark: null,
      state: 'draft',
      version: 0,
      eligible: false,
    },
  ],
};

const sampleRequests = [
  {
    id: 71,
    gradeId: 501,
    studentName: 'Ana Santos',
    studentNumber: '2026-0001',
    subject: 'General Mathematics',
    section: '11-STEM-A',
    gradingPeriod: 'Q1',
    originalValue: 84.5,
    originalMark: null,
    proposedValue: 91.25,
    status: 'pending',
    justification: 'Quiz 4 score was omitted from the final tally.',
    requestedAt: '2026-11-26T08:00:00Z',
    reviewReason: null,
    reviewedAt: null,
  },
  {
    id: 72,
    gradeId: 502,
    studentName: 'Ben Cruz',
    studentNumber: '2026-0002',
    subject: 'General Mathematics',
    section: '11-STEM-A',
    gradingPeriod: 'Q1',
    originalValue: null,
    originalMark: 'INC',
    proposedValue: 88.0,
    status: 'applied',
    justification: 'Completed make-up laboratory work.',
    requestedAt: '2026-11-24T09:15:00Z',
    reviewReason: 'Verified signed completion slip and updated grade.',
    reviewedAt: '2026-11-25T10:00:00Z',
  },
  {
    id: 73,
    gradeId: 503,
    studentName: 'Dino Reyes',
    studentNumber: '2026-0004',
    subject: 'General Mathematics',
    section: '11-STEM-A',
    gradingPeriod: 'Q1',
    originalValue: 78.0,
    originalMark: null,
    proposedValue: 86.0,
    status: 'rejected',
    justification: 'Adjusted class participation.',
    requestedAt: '2026-11-22T07:00:00Z',
    reviewReason: 'Attached evidence does not show the recomputed rubric.',
    reviewedAt: '2026-11-23T11:30:00Z',
  },
];

const detailById = {
  71: {
    ...sampleRequests[0],
    requestedByName: 'Michael Reyes',
    grade: {
      studentName: 'Ana Santos',
      studentNumber: '2026-0001',
      subject: 'General Mathematics',
      section: '11-STEM-A',
      academicYear: '2026-2027',
      gradingPeriod: 'Q1',
    },
  },
  72: {
    ...sampleRequests[1],
    requestedByName: 'Michael Reyes',
    grade: {
      studentName: 'Ben Cruz',
      studentNumber: '2026-0002',
      subject: 'General Mathematics',
      section: '11-STEM-A',
      academicYear: '2026-2027',
      gradingPeriod: 'Q1',
    },
  },
  73: {
    ...sampleRequests[2],
    requestedByName: 'Michael Reyes',
    grade: {
      studentName: 'Dino Reyes',
      studentNumber: '2026-0004',
      subject: 'General Mathematics',
      section: '11-STEM-A',
      academicYear: '2026-2027',
      gradingPeriod: 'Q1',
    },
  },
};

function setupMocks({ gradebook = sampleGradebook, requests = sampleRequests } = {}) {
  entryApi.loadGradeCatalog.mockResolvedValue({
    years: [{ id: 1, label: '2026-2027' }],
    terms: [{ id: 11, name: '1st Semester', academicYearId: 1 }],
  });
  entryApi.loadTeacherClasses.mockResolvedValue({
    items: [{ id: 10, subject: 'General Mathematics', subjectCode: 'MATH11', section: '11-STEM-A' }],
  });
  entryApi.loadGradingPeriods.mockResolvedValue([
    { id: 201, label: 'Q1', status: gradebook.periodLocked ? 'locked' : 'open' },
  ]);
  corrApi.loadCorrectionGradebook.mockResolvedValue(gradebook);
  corrApi.listMyGradeCorrections.mockResolvedValue({ items: requests, total: requests.length, page: 0, size: 25 });
  corrApi.getGradeCorrectionDetail.mockImplementation(id => Promise.resolve(detailById[Number(id)] || detailById[71]));
}

function renderScreen(initialEntry = '/academic/progress-reports') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <GradeCorrections />
    </MemoryRouter>
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Grade Corrections screen (ICI2-55)', () => {
  it('keeps /academic/progress-reports assigned to Subject Teacher with label Grade Corrections', () => {
    const route = pages.find(page => page.path === '/academic/progress-reports');
    expect(route).toBeDefined();
    expect(route.label).toBe('Grade Corrections');
    expect(route.allowedRoles).toEqual(['teacher']);
  });

  it('submits a correction for a finalized/locked grade with reason, proposed grade, and evidence', async () => {
    setupMocks();
    corrApi.submitGradeCorrection.mockResolvedValue({ id: 99, status: 'pending' });

    renderScreen();

    expect(await screen.findByRole('heading', { name: 'Grade Corrections' })).toBeInTheDocument();
    const gradeSelect = await screen.findByLabelText('Finalized or locked grade');
    await waitFor(() => expect(gradeSelect.value).toBe('501'));

    // Draft student should not be offered as an eligible finalized/locked grade
    expect(screen.queryByRole('option', { name: /Cara Lim/ })).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Proposed grade (0.00–100.00)'), {
      target: { value: '91.25' },
    });
    fireEvent.change(screen.getByLabelText('Reason for correction'), {
      target: { value: 'Recomputed exam sheet attached.' },
    });

    const pdf = new File(['%PDF-1.4 evidence'], 'recomputed-sheet.pdf', { type: 'application/pdf' });
    fireEvent.change(screen.getByLabelText(/Supporting evidence/), {
      target: { files: [pdf] },
    });

    fireEvent.click(screen.getByRole('button', { name: 'Submit correction request' }));

    await waitFor(() =>
      expect(corrApi.submitGradeCorrection).toHaveBeenCalledWith({
        gradeId: 501,
        gradeVersion: 2,
        proposedValue: '91.25',
        justification: 'Recomputed exam sheet attached.',
        evidence: pdf,
      })
    );
    expect(await screen.findByText(/Correction request #99 submitted for Ana Santos/)).toBeInTheDocument();
  });

  it('shows client-side file type and file size errors before submitting', async () => {
    setupMocks();
    renderScreen();

    const gradeSelect = await screen.findByLabelText('Finalized or locked grade');
    await waitFor(() => expect(gradeSelect.value).toBe('501'));

    fireEvent.change(screen.getByLabelText('Proposed grade (0.00–100.00)'), {
      target: { value: '90' },
    });
    fireEvent.change(screen.getByLabelText('Reason for correction'), {
      target: { value: 'Correction reason' },
    });

    const badType = new File(['hello'], 'notes.txt', { type: 'text/plain' });
    fireEvent.change(screen.getByLabelText(/Supporting evidence/), {
      target: { files: [badType] },
    });
    expect(await screen.findByText(/Unsupported file type\. Upload a PDF, PNG, or JPEG image\./)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Submit correction request' }));
    expect(corrApi.submitGradeCorrection).not.toHaveBeenCalled();

    const oversizeBytes = new Uint8Array(1_000_001);
    const tooLarge = new File([oversizeBytes], 'scan.pdf', { type: 'application/pdf' });
    fireEvent.change(screen.getByLabelText(/Supporting evidence/), {
      target: { files: [tooLarge] },
    });
    expect(await screen.findByText(/Evidence file is too large\. Maximum allowed size is 1 MB/)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Submit correction request' }));
    expect(corrApi.submitGradeCorrection).not.toHaveBeenCalled();
  });

  it('surfaces server 403, 409, and 413 errors clearly', async () => {
    setupMocks();
    renderScreen();

    const gradeSelect = await screen.findByLabelText('Finalized or locked grade');
    await waitFor(() => expect(gradeSelect.value).toBe('501'));

    fireEvent.change(screen.getByLabelText('Proposed grade (0.00–100.00)'), {
      target: { value: '89.50' },
    });
    fireEvent.change(screen.getByLabelText('Reason for correction'), {
      target: { value: 'Rechecked rubric.' },
    });
    const png = new File(['\x89PNG'], 'proof.png', { type: 'image/png' });
    fireEvent.change(screen.getByLabelText(/Supporting evidence/), {
      target: { files: [png] },
    });

    for (const [status, message] of [
      [403, 'You are not authorized to submit a correction for this grade.'],
      [409, 'This grade already has a pending correction.'],
      [413, 'Evidence must be at most 1,000,000 bytes.'],
    ]) {
      const err = new Error(message);
      err.status = status;
      corrApi.submitGradeCorrection.mockRejectedValueOnce(err);
      fireEvent.click(screen.getByRole('button', { name: 'Submit correction request' }));
      expect(await screen.findByText(message)).toBeInTheDocument();
    }
  });

  it('displays My requests with pending, approved, and rejected statuses, reviewer remarks, and evidence download', async () => {
    setupMocks();
    corrApi.downloadCorrectionEvidence.mockResolvedValue('proof.pdf');

    renderScreen();

    expect(await screen.findByRole('heading', { name: 'My requests' })).toBeInTheDocument();
    expect(await screen.findByText('Verified signed completion slip and updated grade.')).toBeInTheDocument();
    expect(screen.getByText('Attached evidence does not show the recomputed rubric.')).toBeInTheDocument();

    // Status badges for pending, approved (applied), and rejected
    expect(screen.getAllByText('Pending').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Approved').length).toBeGreaterThanOrEqual(1);
    expect(screen.getAllByText('Rejected').length).toBeGreaterThanOrEqual(1);

    fireEvent.click(screen.getByRole('button', { name: 'View #72' }));
    expect(await screen.findByRole('heading', { name: 'Request #72' })).toBeInTheDocument();
    expect(screen.getByText('Approved & applied by Registrar')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Download evidence' }));
    await waitFor(() => expect(corrApi.downloadCorrectionEvidence).toHaveBeenCalledWith(72));
  });

  it('informs the teacher to use Grade Entry when the grading period is still open', async () => {
    setupMocks({
      gradebook: {
        ...sampleGradebook,
        period: { id: 201, code: 'Q1', status: 'open' },
        periodLocked: false,
        students: sampleGradebook.students.map(item => ({ ...item, state: 'draft', eligible: false })),
      },
      requests: [],
    });

    renderScreen();

    expect(
      await screen.findByText(/This grading period is still open\. Edit grades directly in Grade Entry/)
    ).toBeInTheDocument();
  });
});
