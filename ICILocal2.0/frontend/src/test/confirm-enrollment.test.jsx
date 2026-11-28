import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import EnrollmentWorkspace from '../EnrollmentWorkspace.jsx';
import { attendanceStep } from '../enrollment/ConfirmEnrollment.jsx';
import { apiRequest } from '../api.js';

vi.mock('../api.js', () => ({ apiRequest: vi.fn() }));

const reference = { campuses: [{ id: 1, name: 'Iligan Main' }], terms: [{ id: 2, name: 'Semester 1', academicYear: '2026-2027' }], programs: [{ id: 3, name: 'STEM' }], advisers: [] };
const row = (id, name, extra = {}) => ({ id, applicationNumber: `uuid-${id}`, name, lrn: null, campusId: 1, campus: 'Iligan Main', termId: 2, term: '2026-2027 / Semester 1',
  programId: 3, program: 'STEM', gradeLevel: '11', status: 'pre_enlisted', requirementsStatus: 'Complete', attendancePresent: true, sectionId: null, ...extra });
const rows = [row(10, 'Ana Santos', { lrn: '123456789012' }), row(11, 'Ben Cruz', { attendancePresent: false }), row(12, 'Cara Lim', { requirementsStatus: 'Pending', attendancePresent: false })];
let detail;
const fixture = (extra = {}) => ({
  application: { id: 10, applicationNumber: 'uuid-10', name: 'Ana Santos', status: 'pre_enlisted', version: 7, campusId: 1, termId: 2, programId: 3 },
  checklist: { applicationId: 10, summary: { overallStatus: 'Complete', mandatoryTotal: 4, submitted: 4, missing: 0, invalid: 0 }, items: [] },
  attendance: [{ date: '2026-06-01', status: 'present', version: 1 }], attendancePresent: true, duplicateOpen: false, enrollment: null, blockers: [], canConfirm: true, ...extra,
});

const show = (path = '/enrollment/attendance') => render(<MemoryRouter initialEntries={[path]}><EnrollmentWorkspace mode="attendance" /></MemoryRouter>);

beforeEach(() => {
  detail = fixture();
  HTMLDialogElement.prototype.showModal = function showModal() { this.open = true; };
  HTMLDialogElement.prototype.close = function close() { this.open = false; };
  apiRequest.mockImplementation(async (path, options = {}) => {
    if (options.method === 'POST') return {};
    if (path.includes('/reference-data')) return reference;
    if (path.startsWith('/api/enrollment/applications')) return { items: rows, total: rows.length };
    if (path === '/api/applications/10/enrollment') return detail;
    if (path.startsWith('/api/applications/') && path.endsWith('/enrollment')) return fixture({ application: { ...detail.application, id: Number(path.split('/')[3]), name: 'Other' } });
    throw new Error(`Unexpected ${path}`);
  });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('First-Week Attendance table (ICI2-99)', () => {
  it('picks one next step per applicant', () => {
    const base = { id: 1, status: 'pre_enlisted', requirementsStatus: 'Complete', attendancePresent: true, sectionId: null };
    expect(attendanceStep(base)).toMatchObject({ label: 'Confirm enrollment', action: 'confirm' });
    expect(attendanceStep({ ...base, attendancePresent: false })).toMatchObject({ label: 'Record attendance', action: 'select' });
    expect(attendanceStep({ ...base, requirementsStatus: 'Pending' })).toMatchObject({ label: 'Open checklist', to: '/enrollment/requirements?applicationId=1' });
    expect(attendanceStep({ ...base, status: 'submitted' })).toMatchObject({ label: 'Review application', to: '/enrollment/applicants/1' });
    expect(attendanceStep({ ...base, status: 'enrolled' })).toMatchObject({ label: 'Place in section', to: '/enrollment/enrolled?applicationId=1' });
    expect(attendanceStep({ ...base, status: 'enrolled', sectionId: 9 })).toMatchObject({ label: 'View placement' });
  });

  it('shows chips for documents and attendance and one action per row, without application IDs', async () => {
    show();
    const ana = (await screen.findByText('Ana Santos')).closest('tr');
    expect(within(ana).getByText('Complete')).toBeInTheDocument();
    expect(within(ana).getByText('Present')).toBeInTheDocument();
    expect(within(ana).getByRole('button', { name: 'Confirm enrollment: Ana Santos' })).toBeInTheDocument();
    const ben = screen.getByText('Ben Cruz').closest('tr');
    expect(within(ben).getByText('Not recorded')).toBeInTheDocument();
    expect(within(ben).getByRole('button', { name: 'Record attendance: Ben Cruz' })).toBeInTheDocument();
    expect(within(screen.getByText('Cara Lim').closest('tr')).getByRole('link', { name: 'Open checklist: Cara Lim' })).toHaveAttribute('href', '/enrollment/requirements?applicationId=12');
    expect(screen.queryByText('uuid-10')).not.toBeInTheDocument();
    for (const tr of screen.getAllByRole('row').slice(1)) expect(within(tr).queryAllByRole('button').length + within(tr).queryAllByRole('link').length).toBe(1);
  });

  it('Record attendance opens the applicant and focuses the attendance date', async () => {
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Record attendance: Ben Cruz' }));
    await waitFor(() => expect(screen.getByLabelText('Attendance date')).toHaveFocus());
    expect(screen.getByText('Ben Cruz').closest('tr')).toHaveAttribute('aria-current', 'true');
  });
});

describe('Confirm enrollment dialog (ICI2-99)', () => {
  async function openFromRow() {
    show();
    const trigger = await screen.findByRole('button', { name: 'Confirm enrollment: Ana Santos' });
    trigger.focus();
    fireEvent.click(trigger);
    return { trigger, dialog: await screen.findByRole('dialog', { name: 'Confirm enrollment for Ana Santos?' }) };
  }

  it('names the student, program and term, lists the three checks and the consequence', async () => {
    const { dialog } = await openFromRow();
    expect(within(dialog).getByText('STEM')).toBeInTheDocument();
    expect(within(dialog).getByText('2026-2027 / Semester 1')).toBeInTheDocument();
    expect(within(dialog).queryByText(/uuid-10/)).not.toBeInTheDocument();
    const checks = within(dialog).getByRole('list', { name: 'Enrollment checks' });
    expect(within(checks).getAllByRole('listitem').map(item => item.textContent.replace(/^[✓✕]/, ''))).toEqual([
      'Passed: Documents complete', 'Passed: First-week attendance recorded', 'Passed: No open duplicate flags']);
    expect(dialog).toHaveTextContent('Ana Santos becomes enrolled and moves to Enrolled & Assigned');
    expect(within(dialog).getAllByRole('button').map(button => button.textContent)).toEqual(['Cancel', 'Confirm enrollment']);
  });

  it('confirms with the application version and reports the result', async () => {
    const { dialog } = await openFromRow();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm enrollment' }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith('/api/applications/10/confirm-enrollment', expect.objectContaining({ method: 'POST', body: JSON.stringify({ version: 7 }) })));
    expect(await screen.findByText('Ana Santos is enrolled. Place them in a section from Enrolled & Assigned.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('shows a server refusal inside the dialog and reloads the checks', async () => {
    const original = apiRequest.getMockImplementation();
    apiRequest.mockImplementation((path, options) => options?.method === 'POST' ? Promise.reject(new Error('Record present orientation / first-week attendance.')) : original(path, options));
    const { dialog } = await openFromRow();
    const loads = apiRequest.mock.calls.filter(([path]) => path === '/api/applications/10/enrollment').length;
    detail = fixture({ attendancePresent: false, canConfirm: false, blockers: ['Record present orientation / first-week attendance.'] });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm enrollment' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Record present orientation');
    await waitFor(() => expect(apiRequest.mock.calls.filter(([path]) => path === '/api/applications/10/enrollment').length).toBeGreaterThan(loads));
    await waitFor(() => expect(within(dialog).getByText('First-week attendance recorded').closest('li')).toHaveTextContent('Not met'));
    expect(within(dialog).getByRole('alert')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Confirm enrollment' })).toBeDisabled();
  });

  it('lists unmet gates and blocks confirming', async () => {
    detail = fixture({ duplicateOpen: true, canConfirm: false, blockers: ['Resolve open duplicate cases first.', 'The linked student is archived.'] });
    const { dialog } = await openFromRow();
    expect(within(dialog).getByText('No open duplicate flags').closest('li')).toHaveTextContent('Not met');
    expect(within(dialog).getByText('The linked student is archived.')).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Confirm enrollment' })).toBeDisabled();
  });

  it('closes with Cancel or Escape and returns focus to the row', async () => {
    const { trigger, dialog } = await openFromRow();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(trigger).toHaveFocus();
    fireEvent.click(trigger);
    const again = await screen.findByRole('dialog');
    act(() => { again.dispatchEvent(new Event('cancel', { cancelable: true })); });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Confirm enrollment: Ana Santos' })).toHaveFocus();
    expect(apiRequest.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false);
  });
});
