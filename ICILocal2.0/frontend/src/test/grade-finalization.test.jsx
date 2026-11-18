import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import GradeFinalization from '../academic/GradeFinalization.jsx';
import { createGradingLocksMock } from '../academic/gradeFinalizationApi.js';
import { pages } from '../routes/routeConfig.js';
import * as api from '../academic/gradeFinalizationApi.js';

vi.mock('../auth/AuthContext.jsx', () => ({ useAuth: () => ({ user: { fullName: 'R. Santos' } }) }));
vi.mock('../academic/gradeFinalizationApi.js', async importOriginal => ({
  ...(await importOriginal()),
  listGradingScopes: vi.fn(),
  getGradingScope: vi.fn(),
  updateGradingDeadline: vi.fn(),
  finalizeAndLockScope: vi.fn(),
}));

afterEach(() => { cleanup(); vi.clearAllMocks(); });
HTMLDialogElement.prototype.showModal ??= function showModal() { this.open = true; };
HTMLDialogElement.prototype.close ??= function close() { this.open = false; };

const scope = {
  id: 'scope-1', academicYear: '2026–2027', gradingPeriod: 'Quarter 1', gradeLevel: 'Grade 11', section: '11-STEM-A',
  deadline: '2026-12-01T09:00:00.000Z', lockedAt: null, lockedBy: null, lockReason: null, history: [],
  offerings: [
    { id: 'a', subject: 'Mathematics', teacher: 'Michael Reyes', submissionStatus: 'Submitted', submittedRecords: 20, totalRecords: 20, unresolvedInc: 0, finalizedGradeRecords: 20, finalizedIncRecords: 0, unsubmittedRecords: 0 },
    { id: 'b', subject: 'Physics', teacher: 'Angela Lim', submissionStatus: 'In progress', submittedRecords: 10, totalRecords: 20, unresolvedInc: 1, finalizedGradeRecords: 0, finalizedIncRecords: 0, unsubmittedRecords: 10 },
  ],
};

function renderPage(selectedScope = scope) {
  api.listGradingScopes.mockResolvedValue({ items: [selectedScope] });
  api.getGradingScope.mockResolvedValue(selectedScope);
  return render(<GradeFinalization />);
}

describe('Grade Finalization mock workflow', () => {
  it('keeps the route Registrar-only', () => {
    expect(pages.find(page => page.path === '/academic/finalize-grades').allowedRoles).toEqual(['registrar']);
  });

  it('shows the scope, warnings, and section-level submission rows', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Grade Finalization' })).toBeInTheDocument();
    expect(screen.getByText('Select an academic year, grading period, grade level, and section to manage grade submissions and finalization.')).toBeInTheDocument();
    expect(screen.getByText('Set the deadline for teachers to submit grades for all subjects under the selected section and grading period.')).toBeInTheDocument();
    expect(screen.getByText(/warnings only and do not prevent a manual lock/)).toBeInTheDocument();
    expect(screen.getByText('Mathematics')).toBeInTheDocument();
    expect(screen.getByText('10 / 20')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Grade Records Submitted' })).toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Deadline' })).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: 'Lock status' })).not.toBeInTheDocument();
    expect(screen.getByText('Manual Early Finalization')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Lock Section Now' })).toBeInTheDocument();
  });

  it('requires confirmation naming the full scope before manual locking', async () => {
    renderPage();
    await screen.findByText('Mathematics');
    fireEvent.click(screen.getByRole('button', { name: 'Lock Section Now' }));
    const dialog = screen.getByRole('dialog', { name: 'Finalize and lock this scope?' });
    expect(dialog).toHaveTextContent('2026–2027 / Quarter 1 / Grade 11 / 11-STEM-A');
    expect(dialog).toHaveTextContent('will remain unsubmitted');
    const locked = { ...scope, lockedAt: '2026-11-18T10:00:00.000Z', lockedBy: 'R. Santos', lockReason: 'manual' };
    api.finalizeAndLockScope.mockResolvedValue(locked);
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(api.finalizeAndLockScope).toHaveBeenCalledWith(scope.id, 'R. Santos'));
  });

  it('saves an edited deadline through the mock adapter', async () => {
    const updated = { ...scope, deadline: '2026-12-02T09:30:00.000Z', history: [{ id: 'h-1', action: 'Submission deadline updated', actor: 'R. Santos', occurredAt: '2026-11-18T10:00:00.000Z' }] };
    api.updateGradingDeadline.mockResolvedValue(updated);
    renderPage();
    const deadline = await screen.findByLabelText('Deadline date and time');
    fireEvent.change(deadline, { target: { value: '2026-12-02T09:30' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save deadline' }));
    await waitFor(() => expect(api.updateGradingDeadline).toHaveBeenCalledWith(scope.id, new Date('2026-12-02T09:30').toISOString(), 'R. Santos'));
    expect(await screen.findByText('Submission deadline saved in mock data.')).toBeInTheDocument();
  });

  it('does not offer deadline edits or a second lock action after finalization', async () => {
    const locked = { ...scope, lockedAt: '2026-11-18T10:00:00.000Z', lockedBy: 'R. Santos', lockReason: 'manual' };
    renderPage(locked);
    expect(await screen.findByLabelText('Deadline date and time')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Lock Section Now' })).not.toBeInTheDocument();
  });

  it('automatically locks when the configured deadline is in the past and preserves submission state', async () => {
    const now = Date.parse('2026-11-18T00:00:00.000Z');
    const mock = createGradingLocksMock({ clock: () => now });
    const before = await mock.getScope('scope-2026-2027-q1-grade11-stem-a');
    const updated = await mock.updateDeadline(before.id, new Date(now - 1000).toISOString(), 'R. Santos');
    expect(updated.lockReason).toBe('deadline');
    expect(updated.lockedBy).toBe('System');
    expect(updated.offerings.map(item => item.submissionStatus)).toEqual(before.offerings.map(item => item.submissionStatus));
    expect(updated.offerings.find(item => item.subject === 'Oral Communication')).toMatchObject({ finalizedGradeRecords: 19, finalizedIncRecords: 2, unsubmittedRecords: 9 });
    expect(updated.offerings.find(item => item.subject === 'Earth and Life Science')).toMatchObject({ finalizedGradeRecords: 0, finalizedIncRecords: 0, unsubmittedRecords: 30 });
    expect(updated.history[0].action).toContain('Automatically locked');
    await expect(mock.updateDeadline(before.id, new Date(now + 2_000).toISOString(), 'R. Santos')).rejects.toThrow('locked');
  });

  it('finalizes submitted grades and INC while leaving unsubmitted records untouched on manual lock', async () => {
    const mock = createGradingLocksMock({ clock: () => Date.parse('2026-11-18T00:00:00.000Z') });
    const before = await mock.getScope('scope-2026-2027-q1-grade11-stem-a');
    const updated = await mock.lockScope(before.id, 'R. Santos');
    expect(updated.offerings.map(item => item.submissionStatus)).toEqual(before.offerings.map(item => item.submissionStatus));
    expect(updated.offerings.map(item => item.submittedRecords)).toEqual(before.offerings.map(item => item.submittedRecords));
    const inProgress = updated.offerings.find(item => item.subject === 'Oral Communication');
    expect(inProgress).toMatchObject({ submittedRecords: 21, totalRecords: 30, finalizedGradeRecords: 19, finalizedIncRecords: 2, unsubmittedRecords: 9 });
    const neverSubmitted = updated.offerings.find(item => item.subject === 'Earth and Life Science');
    expect(neverSubmitted).toMatchObject({ submittedRecords: 0, totalRecords: 30, finalizedGradeRecords: 0, finalizedIncRecords: 0, unsubmittedRecords: 30 });
    updated.offerings.forEach(item => expect(item.totalRecords).toBe(item.finalizedGradeRecords + item.finalizedIncRecords + item.unsubmittedRecords));
    expect(updated.lockedAt).toBeTruthy();
    expect(updated.history[0]).toMatchObject({ action: 'Scope manually finalized and locked', actor: 'R. Santos' });
    await expect(mock.lockScope(before.id, 'R. Santos')).rejects.toThrow('already locked');
  });
});
