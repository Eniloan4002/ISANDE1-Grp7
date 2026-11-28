import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import CutoverReconciliation from '../imports/CutoverReconciliation.jsx';
import * as reconciliation from '../imports/reconciliationApi.js';
import { navGroupsFor, pages } from '../routes/routeConfig.js';

vi.mock('../auth/AuthContext.jsx', () => ({
  useAuth: () => ({ user: { userId: 7, fullName: 'Registrar User', grants: [] }, roles: ['registrar'] }),
}));
vi.mock('../EnrollmentWorkspace.jsx', async importOriginal => ({
  ...(await importOriginal()),
  useResource: () => ({ data: { campuses: [{ id: 1, name: 'Iligan Main' }, { id: 2, name: 'Bugo' }] }, error: '', loading: false, reload: () => {} }),
}));
vi.mock('../imports/reconciliationApi.js', async importOriginal => ({
  ...(await importOriginal()),
  loadReconciliation: vi.fn(),
  signOffBatch: vi.fn(),
}));

HTMLDialogElement.prototype.showModal ??= function showModal() { this.open = true; };
HTMLDialogElement.prototype.close ??= function close() { this.open = false; };

const batch = (id, extra = {}) => ({
  id, sourceType: 'legacy_student_csv', sourceName: `students-${id}.csv`, campusId: 1, campus: 'Iligan Main', status: 'imported', version: 3,
  sourceCount: 120, importedCount: 120, validCount: 0, invalidCount: 0, flaggedCount: 0, skippedCount: 0, unimportedCount: 0,
  importedAt: '2026-11-20T02:00:00Z', signedOffBy: null, signedOffAt: null, discrepancies: [], canSignOff: true, ...extra,
});
const ready = batch(11);
const blocked = batch(12, {
  sourceType: 'legacy_grade_matrix_csv', sourceName: 'grades-2019.csv', sourceCount: 80, importedCount: 75, invalidCount: 3, skippedCount: 2,
  unimportedCount: 5, liveGradeRecords: 600, voidedGradeRecords: 0, canSignOff: false,
  discrepancies: [{ code: 'UNIMPORTED_ROWS', detail: '5 of 80 source rows were not imported (invalid 3, flagged as possible duplicates 0, skipped 2, valid but not imported 0). Correct them and upload a new batch.' }],
});
const signed = batch(13, { sourceName: 'students-2018.csv', status: 'reconciled', version: 4, sourceCount: 40, importedCount: 40, canSignOff: false, signedOffBy: 7, signedOffAt: '2026-11-21T03:00:00Z' });
const summary = (batches = [ready, blocked, signed], extra = {}) => ({
  batches,
  totals: { batches: batches.length, sourceRows: 240, importedRows: 235, unimportedRows: 5, reconciledBatches: 1, readyForSignOff: 1, blockedBatches: 1 },
  excludedBatches: 2,
  readyToDecommission: false,
  ...extra,
});

const renderScreen = () => render(<MemoryRouter><CutoverReconciliation /></MemoryRouter>);
const rowFor = name => screen.getByText(name).closest('tr');

beforeEach(() => {
  reconciliation.loadReconciliation.mockResolvedValue(summary());
  reconciliation.signOffBatch.mockResolvedValue({ batchId: 11, status: 'reconciled', version: 4, signedOffBy: 7, signedOffAt: '2026-11-28T00:00:00Z', alreadySignedOff: false });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('Cutover reconciliation', () => {
  it('is routed and listed under Data Migration for registrar and admin only', () => {
    expect(pages.find(page => page.path === '/migration/reconciliation').allowedRoles).toEqual(['registrar', 'admin']);
    for (const role of ['registrar', 'admin']) {
      const group = navGroupsFor([role]).find(item => item.label === 'Data Migration');
      expect(group.items.map(item => item.path)).toContain('/migration/reconciliation');
    }
    expect(navGroupsFor(['teacher']).flatMap(group => group.items).some(item => item.path === '/migration/reconciliation')).toBe(false);
  });

  it('shows the legacy source totals next to the imported totals for every batch', async () => {
    renderScreen();
    await screen.findByText('students-11.csv');
    const totals = screen.getByLabelText('Reconciliation totals');
    expect(within(totals).getByText('Legacy source rows').nextSibling).toHaveTextContent('240');
    expect(within(totals).getByText('Imported rows').nextSibling).toHaveTextContent('235');
    expect(within(totals).getByText('Not imported').nextSibling).toHaveTextContent('5');
    expect(within(totals).getByText('2 discarded or failed not counted')).toBeInTheDocument();

    const row = rowFor('grades-2019.csv');
    expect(within(row).getByText('80')).toHaveAttribute('data-label', 'Legacy source');
    expect(within(row).getByText('75')).toHaveAttribute('data-label', 'Imported');
    expect(within(row).getByText('5')).toHaveAttribute('data-label', 'Not imported');
    expect(within(row).getByText('1 discrepancy')).toBeInTheDocument();
    expect(within(rowFor('students-11.csv')).getByText('Ready to sign off')).toBeInTheDocument();
    expect(within(rowFor('students-2018.csv')).getByText('Signed off')).toBeInTheDocument();
    expect(screen.getByText('1 of 3 batches signed off.')).toBeInTheDocument();
  });

  it('lists every discrepancy of a batch and blocks its sign-off', async () => {
    renderScreen();
    await screen.findByText('grades-2019.csv');
    fireEvent.click(screen.getByRole('button', { name: 'Review batch #12' }));
    const drawer = await screen.findByRole('dialog', { name: 'Batch #12' });
    expect(within(drawer).getByText('Rows not imported')).toBeInTheDocument();
    expect(within(drawer).getByText(/5 of 80 source rows were not imported/)).toBeInTheDocument();
    expect(within(drawer).getByText('Live grade records').nextSibling).toHaveTextContent('600');
    expect(within(drawer).getByRole('link', { name: 'Open this batch in Legacy Import Review' })).toHaveAttribute('href', '/migration/legacy-imports?type=grades&batchId=12');
    expect(within(drawer).queryByRole('button', { name: 'Sign off batch' })).not.toBeInTheDocument();
    expect(within(drawer).getByText(/cannot be signed off until every discrepancy is resolved/)).toBeInTheDocument();
  });

  it('signs off a ready batch with the version and imported count the registrar saw', async () => {
    renderScreen();
    await screen.findByText('students-11.csv');
    fireEvent.click(screen.getByRole('button', { name: 'Review and sign off batch #11' }));
    const drawer = await screen.findByRole('dialog', { name: 'Batch #11' });
    expect(within(drawer).getByText('None. Every legacy source row is accounted for.')).toBeInTheDocument();
    const submit = within(drawer).getByRole('button', { name: 'Sign off batch' });
    expect(submit).toBeDisabled();
    fireEvent.change(within(drawer).getByLabelText('Note (optional)'), { target: { value: '  Matches the 2019 ledger  ' } });
    fireEvent.click(within(drawer).getByRole('checkbox'));
    reconciliation.loadReconciliation.mockResolvedValue(summary([{ ...ready, status: 'reconciled', version: 4, canSignOff: false, signedOffBy: 7, signedOffAt: '2026-11-28T00:00:00Z' }, blocked, signed]));
    fireEvent.click(submit);

    await waitFor(() => expect(reconciliation.signOffBatch).toHaveBeenCalledWith(11, { version: 3, expectedImportedCount: 120, note: '  Matches the 2019 ledger  ' }));
    expect(await screen.findByText('Batch #11 (students-11.csv) is signed off.')).toBeInTheDocument();
    expect(await within(drawer).findByText(/Signed off by you on/)).toBeInTheDocument();
    expect(reconciliation.loadReconciliation).toHaveBeenCalledTimes(2);
  });

  it('shows a refused sign-off and reloads the current numbers', async () => {
    reconciliation.signOffBatch.mockRejectedValueOnce(Object.assign(new Error('The batch changed since you loaded it. Reload before signing off.'), { status: 409 }));
    renderScreen();
    await screen.findByText('students-11.csv');
    fireEvent.click(screen.getByRole('button', { name: 'Review and sign off batch #11' }));
    const drawer = await screen.findByRole('dialog', { name: 'Batch #11' });
    fireEvent.click(within(drawer).getByRole('checkbox'));
    fireEvent.click(within(drawer).getByRole('button', { name: 'Sign off batch' }));
    expect(await within(drawer).findByRole('alert')).toHaveTextContent('The batch changed since you loaded it.');
    await waitFor(() => expect(reconciliation.loadReconciliation).toHaveBeenCalledTimes(2));
    expect(screen.queryByText(/^Batch #11 .* is signed off\.$/)).not.toBeInTheDocument();
  });

  it('filters by sign-off state, source and campus', async () => {
    renderScreen();
    await screen.findByText('students-11.csv');
    fireEvent.click(screen.getByRole('button', { name: /Has discrepancies/ }));
    expect(screen.queryByText('students-11.csv')).not.toBeInTheDocument();
    expect(screen.getByText('grades-2019.csv')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /All batches/ }));
    fireEvent.change(screen.getByLabelText('Source'), { target: { value: 'legacy_student_csv' } });
    expect(screen.queryByText('grades-2019.csv')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Signed off/ })).toHaveTextContent('1');
    fireEvent.change(screen.getByLabelText('Campus'), { target: { value: '2' } });
    await waitFor(() => expect(reconciliation.loadReconciliation).toHaveBeenLastCalledWith('2', expect.anything()));
  });

  it('says when the legacy system can be retired', async () => {
    reconciliation.loadReconciliation.mockResolvedValue(summary([signed], { readyToDecommission: true, totals: { batches: 1, sourceRows: 40, importedRows: 40, unimportedRows: 0, reconciledBatches: 1, readyForSignOff: 0, blockedBatches: 0 } }));
    renderScreen();
    expect(await screen.findByText('Every live batch is signed off.')).toBeInTheDocument();
  });

  it('shows an empty state and load failures', async () => {
    reconciliation.loadReconciliation.mockResolvedValueOnce(summary([], { totals: {} }));
    renderScreen();
    expect(await screen.findByText('No import batches yet')).toBeInTheDocument();
    cleanup();
    reconciliation.loadReconciliation.mockRejectedValueOnce(new Error('Your account cannot view imports for this campus.'));
    renderScreen();
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot view imports for this campus');
  });
});
