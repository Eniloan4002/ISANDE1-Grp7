import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import WithdrawalsWorkspace from '../academic/WithdrawalsWorkspace.jsx';
import { pages } from '../routes/routeConfig.js';
import * as withdrawalsApi from '../academic/withdrawalsApi.js';

let mockRoles = ['registrar'];

vi.mock('../auth/AuthContext.jsx', () => ({
  useAuth: () => ({ roles: mockRoles }),
}));

vi.mock('../academic/withdrawalsApi.js', async importOriginal => ({
  ...(await importOriginal()),
  listWithdrawals: vi.fn(),
  getWithdrawal: vi.fn(),
  getReleaseEligibility: vi.fn(),
  createWithdrawal: vi.fn(),
  listAdviserEndorsements: vi.fn(),
  decideAdviserEndorsement: vi.fn(),
  decideOfficeClearance: vi.fn(),
  searchEnrolledApplications: vi.fn(),
  getApplicationEnrollment: vi.fn(),
}));

HTMLDialogElement.prototype.showModal ??= function showModal() {
  this.open = true;
};
HTMLDialogElement.prototype.close ??= function close() {
  this.open = false;
};

const baseWithdrawal = {
  id: 81,
  enrollmentId: 401,
  studentId: 901,
  applicationId: 701,
  campusId: 1,
  termId: 11,
  sectionId: 21,
  enrollmentStatus: 'enrolled',
  enrollmentVersion: 2,
  reason: 'Family relocating to another city.',
  status: 'pending',
  version: 0,
  requestedAt: '2026-11-27T08:00:00Z',
  studentNumber: '2026-0091',
  studentName: 'Ana Santos',
  section: '11-STEM-A',
  credentialReleaseEligible: false,
  clearances: [
    { office: 'adviser', status: 'pending', version: 0, actedBy: null, actorName: null, actedAt: null, notes: null },
    { office: 'principal', status: 'pending', version: 0, actedBy: null, actorName: null, actedAt: null, notes: null },
    { office: 'cashier', status: 'pending', version: 0, actedBy: null, actorName: null, actedAt: null, notes: null },
  ],
  history: [
    {
      id: 1,
      tableName: 'withdrawals',
      operation: 'INSERT',
      occurredAt: '2026-11-27T08:00:00Z',
      actorId: 3,
      actorName: 'Registrar Staff',
      oldStatus: null,
      newStatus: 'pending',
      office: null,
      reason: 'Family relocating to another city.',
    },
  ],
};

function renderWorkspace({ roles, mode = 'approvals', entry = '/academic/withdrawal-approvals' }) {
  mockRoles = roles;
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <WithdrawalsWorkspace mode={mode} />
    </MemoryRouter>
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Withdrawal Approvals & Clearance screens (ICI2-56)', () => {
  it('assigns withdrawal and clearance routes to Registrar, Adviser, Principal, and Cashier roles', () => {
    const approvals = pages.find(page => page.path === '/academic/withdrawal-approvals');
    const clearances = pages.find(page => page.path === '/cashiering/clearances');
    expect(approvals).toBeDefined();
    expect(approvals.allowedRoles).toEqual(['registrar', 'adviser', 'principal']);
    expect(clearances).toBeDefined();
    expect(clearances.allowedRoles).toEqual(['cashier_ref']);
  });

  it('allows the Registrar to start a withdrawal request for an enrolled student', async () => {
    withdrawalsApi.searchEnrolledApplications.mockResolvedValue({
      items: [
        {
          id: 701,
          name: 'Ana Santos',
          applicationNumber: 'APP-2026-0701',
          campus: 'Iligan Main',
          program: 'STEM',
          section: '11-STEM-A',
        },
      ],
      total: 1,
    });
    withdrawalsApi.getApplicationEnrollment.mockResolvedValue({
      enrollment: { id: 401, version: 2, status: 'enrolled', sectionId: 21, section: '11-STEM-A' },
    });
    withdrawalsApi.listWithdrawals.mockResolvedValue({ items: [], total: 0 });
    withdrawalsApi.createWithdrawal.mockResolvedValue(baseWithdrawal);
    withdrawalsApi.getWithdrawal.mockResolvedValue(baseWithdrawal);
    withdrawalsApi.getReleaseEligibility.mockResolvedValue({
      withdrawalId: 81,
      eligible: false,
      reason: 'Credential release is blocked by unresolved or rejected withdrawal clearance.',
    });

    renderWorkspace({ roles: ['registrar'] });

    expect(await screen.findByRole('heading', { name: 'Withdrawal Approvals' })).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: 'Select Ana Santos' }));

    const reasonInput = await screen.findByLabelText('Reason for withdrawal');
    fireEvent.change(reasonInput, { target: { value: 'Family relocating to another city.' } });
    fireEvent.click(screen.getByRole('button', { name: 'Start withdrawal request' }));

    expect(screen.getByRole('dialog', { name: 'Start withdrawal request?' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(withdrawalsApi.createWithdrawal).toHaveBeenCalledWith({
        enrollmentId: 401,
        enrollmentVersion: 2,
        reason: 'Family relocating to another city.',
      })
    );
    expect(await screen.findByText(/Withdrawal request #81 started for Ana Santos/)).toBeInTheDocument();
  });

  it('shows pending section endorsements for the Teacher Adviser and records endorsement with remarks', async () => {
    withdrawalsApi.listAdviserEndorsements.mockResolvedValue({
      items: [
        {
          withdrawalId: 81,
          enrollmentId: 401,
          reason: 'Family relocating to another city.',
          requestedAt: '2026-11-27T08:00:00Z',
          section: '11-STEM-A',
          studentNumber: '2026-0091',
          student: 'Ana Santos',
          version: 0,
        },
      ],
      total: 1,
    });
    withdrawalsApi.listWithdrawals.mockResolvedValue({ items: [baseWithdrawal], total: 1 });
    withdrawalsApi.getWithdrawal.mockResolvedValue(baseWithdrawal);
    withdrawalsApi.decideAdviserEndorsement.mockResolvedValue({
      withdrawalId: 81,
      endorsementStatus: 'cleared',
      withdrawalStatus: 'pending',
      version: 1,
      withdrawalVersion: 1,
    });

    renderWorkspace({ roles: ['adviser'] });

    expect(await screen.findByRole('heading', { name: 'Pending endorsements for my sections' })).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: 'Review endorsement #81' }));

    fireEvent.change(await screen.findByLabelText('Decision'), { target: { value: 'cleared' } });
    fireEvent.change(screen.getByLabelText('Remarks (required)'), {
      target: { value: 'Returned borrowed class modules.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Review decision' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(withdrawalsApi.decideAdviserEndorsement).toHaveBeenCalledWith(81, {
        version: 0,
        decision: 'cleared',
        notes: 'Returned borrowed class modules.',
      })
    );
    expect(await screen.findByText(/Adviser endorsement recorded/)).toBeInTheDocument();
  });

  it('enforces Adviser -> Principal -> Cashier order and lets Principal approve once Adviser clears', async () => {
    const adviserCleared = {
      ...baseWithdrawal,
      version: 1,
      clearances: [
        {
          office: 'adviser',
          status: 'cleared',
          version: 1,
          actedBy: 5,
          actorName: 'Adviser User',
          actedAt: '2026-11-27T09:00:00Z',
          notes: 'Modules returned.',
        },
        { office: 'principal', status: 'pending', version: 0, actedBy: null, actorName: null, actedAt: null, notes: null },
        { office: 'cashier', status: 'pending', version: 0, actedBy: null, actorName: null, actedAt: null, notes: null },
      ],
    };

    // First verify Principal cannot act when Adviser is still pending
    withdrawalsApi.listWithdrawals.mockResolvedValue({ items: [baseWithdrawal], total: 1 });
    withdrawalsApi.getWithdrawal.mockResolvedValue(baseWithdrawal);
    withdrawalsApi.getReleaseEligibility.mockResolvedValue({
      withdrawalId: 81,
      eligible: false,
      reason: 'Credential release is blocked by unresolved or rejected withdrawal clearance.',
    });

    const { unmount } = renderWorkspace({
      roles: ['principal'],
      entry: '/academic/withdrawal-approvals?withdrawalId=81',
    });

    expect(
      await screen.findByText(/waiting for the Teacher Adviser endorsement before Principal approval/)
    ).toBeInTheDocument();
    expect(screen.queryByLabelText('Decision')).not.toBeInTheDocument();
    unmount();

    // Now Adviser cleared -> Principal can approve
    withdrawalsApi.listWithdrawals.mockResolvedValue({ items: [adviserCleared], total: 1 });
    withdrawalsApi.getWithdrawal.mockResolvedValue(adviserCleared);
    withdrawalsApi.decideOfficeClearance.mockResolvedValue({ ...adviserCleared, status: 'approved', version: 2 });

    renderWorkspace({
      roles: ['principal'],
      entry: '/academic/withdrawal-approvals?withdrawalId=81',
    });

    fireEvent.change(await screen.findByLabelText('Decision'), { target: { value: 'cleared' } });
    fireEvent.change(screen.getByLabelText('Remarks (required)'), {
      target: { value: 'Approved for transfer.' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Review decision' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(withdrawalsApi.decideOfficeClearance).toHaveBeenCalledWith(81, 'principal', {
        version: 0,
        withdrawalVersion: 1,
        decision: 'cleared',
        notes: 'Approved for transfer.',
      })
    );
    expect(await screen.findByText(/Principal approval recorded/)).toBeInTheDocument();
  });

  it('lets Cashier complete final clearance on Clearance Status and displays release eligibility and 409/403 errors', async () => {
    const principalApproved = {
      ...baseWithdrawal,
      status: 'approved',
      version: 2,
      clearances: [
        { office: 'adviser', status: 'cleared', version: 1, actedBy: 5, actorName: 'Adviser User', actedAt: '2026-11-27T09:00:00Z', notes: 'OK' },
        { office: 'principal', status: 'cleared', version: 1, actedBy: 6, actorName: 'Principal User', actedAt: '2026-11-27T10:00:00Z', notes: 'Approved' },
        { office: 'cashier', status: 'pending', version: 0, actedBy: null, actorName: null, actedAt: null, notes: null },
      ],
    };
    const completed = {
      ...principalApproved,
      status: 'completed',
      enrollmentStatus: 'withdrawn',
      version: 3,
      credentialReleaseEligible: true,
      clearances: [
        principalApproved.clearances[0],
        principalApproved.clearances[1],
        { office: 'cashier', status: 'cleared', version: 1, actedBy: 8, actorName: 'Cashier User', actedAt: '2026-11-27T11:00:00Z', notes: 'No balance.' },
      ],
    };

    withdrawalsApi.listWithdrawals.mockResolvedValue({ items: [principalApproved], total: 1 });
    withdrawalsApi.getWithdrawal.mockResolvedValue(principalApproved);
    withdrawalsApi.getReleaseEligibility.mockResolvedValue({
      withdrawalId: 81,
      eligible: false,
      reason: 'Credential release is blocked by unresolved or rejected withdrawal clearance.',
    });

    renderWorkspace({
      roles: ['cashier_ref'],
      mode: 'clearances',
      entry: '/cashiering/clearances?withdrawalId=81',
    });

    expect(await screen.findByRole('heading', { name: 'Clearance Status' })).toBeInTheDocument();
    expect(await screen.findByText(/Record release eligibility:/)).toHaveTextContent('Blocked');

    // Simulate 409 version conflict first, then 403 permission error, then success
    fireEvent.change(await screen.findByLabelText('Decision'), { target: { value: 'cleared' } });
    fireEvent.change(screen.getByLabelText('Remarks (required)'), { target: { value: 'No outstanding balance.' } });

    const conflictErr = new Error('Withdrawal changed. Reload before deciding.');
    conflictErr.status = 409;
    withdrawalsApi.decideOfficeClearance.mockRejectedValueOnce(conflictErr);
    fireEvent.click(screen.getByRole('button', { name: 'Review decision' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText('Withdrawal changed. Reload before deciding.')).toBeInTheDocument();

    const forbiddenErr = new Error('Your role cannot decide this office\'s clearance.');
    forbiddenErr.status = 403;
    withdrawalsApi.decideOfficeClearance.mockRejectedValueOnce(forbiddenErr);
    fireEvent.click(screen.getByRole('button', { name: 'Review decision' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    expect(await screen.findByText("Your role cannot decide this office's clearance.")).toBeInTheDocument();

    withdrawalsApi.decideOfficeClearance.mockResolvedValueOnce(completed);
    withdrawalsApi.getWithdrawal.mockResolvedValue(completed);
    withdrawalsApi.getReleaseEligibility.mockResolvedValue({
      withdrawalId: 81,
      eligible: true,
      reason: 'Withdrawal clearances complete; other document rules still apply.',
    });

    fireEvent.click(screen.getByRole('button', { name: 'Review decision' }));
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    await waitFor(() =>
      expect(withdrawalsApi.decideOfficeClearance).toHaveBeenLastCalledWith(81, 'cashier', {
        version: 0,
        withdrawalVersion: 2,
        decision: 'cleared',
        notes: 'No outstanding balance.',
      })
    );
    expect(await screen.findByText(/Cashier clearance recorded/)).toBeInTheDocument();
    expect(await screen.findByText(/Eligible for release/)).toBeInTheDocument();
  });
});
