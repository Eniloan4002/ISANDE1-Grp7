import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import RequirementsScreen from '../enrollment/RequirementsScreen.jsx';
import { apiRequest } from '../api.js';

vi.mock('../api.js', () => ({ apiRequest: vi.fn() }));

const row = { id: 10, applicationNumber: 'APP-10', name: 'Ana Santos', lrn: '123456789012', category: 'new', program: 'STEM', requirementsStatus: 'Pending' };
const item = (code, name, extra = {}) => ({ code, name, mandatory: true, status: 'pending', version: 0, notes: null, ...extra });
let detail;
const fixture = () => ({
  id: 10, firstName: 'Ana', lastName: 'Santos', category: 'new', lrn: '123456789012', phone: '09170000001', email: 'ana@example.invalid',
  profile: { guardians: [{ fullName: 'Maria Santos', relationship: 'mother', phone: '09912345678' }] },
  workflow: {
    application: { id: 10, name: 'Ana Santos' },
    checklist: {
      applicationId: 10, studentId: null,
      summary: { overallStatus: 'Pending', submitted: 1, mandatoryTotal: 3, missing: 1, invalid: 0 },
      items: [item('PSA', 'PSA Birth Certificate', { status: 'submitted', notes: 'Original seen', verifiedAt: '2026-06-01T00:00:00Z' }), item('FORM137', 'Report Card', { status: 'missing' }), item('GOOD_MORAL', 'Good Moral')],
    },
  },
});

function show(path = '/enrollment/requirements?applicationId=10') {
  return render(<MemoryRouter initialEntries={[path]}><RequirementsScreen /></MemoryRouter>);
}

beforeEach(() => {
  detail = fixture();
  HTMLDialogElement.prototype.showModal = function showModal() { this.open = true; };
  HTMLDialogElement.prototype.close = function close() { this.open = false; };
  apiRequest.mockImplementation(async (path, options = {}) => {
    if (options.method === 'PUT') return {};
    if (path.endsWith('/reference-data')) return { campuses: [], terms: [], programs: [] };
    if (path.startsWith('/api/enrollment/applications?')) return { items: [row], total: 1 };
    if (path === '/api/intake/applications/10') return detail;
    if (path.includes('/requirements/audit')) return { items: [], total: 0 };
    throw new Error(`Unexpected ${path}`);
  });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); vi.unstubAllGlobals(); });

describe('Requirements checklist drawer (ICI2-98)', () => {
  it('opens as a drawer beside the applicant list with every credential, its status and mandatory progress', async () => {
    show();
    const drawer = await screen.findByRole('dialog', { name: 'Requirements Checklist' });
    expect(drawer).toHaveClass('ui-drawer', 'rq-drawer');
    expect(screen.getByRole('table')).toBeInTheDocument();
    const list = await within(drawer).findByRole('list', { name: '' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(3);
    expect(within(drawer).getByText('PSA Birth Certificate').closest('li')).toHaveTextContent('Submitted');
    expect(within(drawer).getByText('Report Card').closest('li')).toHaveTextContent('Missing');
    expect(within(drawer).getByText('Original seen')).toBeInTheDocument();
    expect(within(drawer).getByRole('meter', { name: 'Mandatory documents' })).toHaveAttribute('aria-valuetext', '1 of 3 submitted');
    expect(within(drawer).queryByLabelText('Status: PSA')).not.toBeInTheDocument();
  });

  it('edits a credential inline and saves with the existing API call and version', async () => {
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit FORM137' }));
    expect(screen.getByLabelText('Status: FORM137')).toHaveValue('missing');
    fireEvent.change(screen.getByLabelText('Status: FORM137'), { target: { value: 'submitted' } });
    fireEvent.change(screen.getByLabelText('Verification notes: FORM137'), { target: { value: 'Original inspected' } });
    detail = fixture();
    detail.workflow.checklist.items[1] = { ...detail.workflow.checklist.items[1], status: 'submitted', version: 1, notes: 'Original inspected' };
    fireEvent.click(screen.getByRole('button', { name: 'Save FORM137' }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith('/api/applications/10/requirements/FORM137', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ status: 'submitted', notes: 'Original inspected', version: 0 }) })));
    expect(await screen.findByText('Document verification saved.')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('Report Card').closest('li')).toHaveTextContent('Submitted'));
    expect(screen.queryByLabelText('Status: FORM137')).not.toBeInTheDocument();
  });

  it('cancels an edit, discards the draft and returns focus to Edit', async () => {
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit PSA' }));
    fireEvent.change(screen.getByLabelText('Verification notes: PSA'), { target: { value: 'Draft to discard' } });
    fireEvent.click(screen.getByRole('button', { name: 'Cancel PSA' }));
    expect(screen.queryByLabelText('Verification notes: PSA')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Edit PSA' })).toHaveFocus());
    fireEvent.click(screen.getByRole('button', { name: 'Edit PSA' }));
    expect(screen.getByLabelText('Verification notes: PSA')).toHaveValue('Original seen');
    fireEvent.keyDown(screen.getByLabelText('Verification notes: PSA'), { key: 'Escape' });
    expect(screen.queryByLabelText('Verification notes: PSA')).not.toBeInTheDocument();
    expect(screen.getByRole('dialog', { name: 'Requirements Checklist' })).toBeInTheDocument();
  });

  it('keeps the draft when a save is refused', async () => {
    const original = apiRequest.getMockImplementation();
    apiRequest.mockImplementation((path, options) => options?.method === 'PUT' ? Promise.reject(new Error('Record changed; reload before saving.')) : original(path, options));
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit GOOD_MORAL' }));
    fireEvent.change(screen.getByLabelText('Verification notes: GOOD_MORAL'), { target: { value: 'Keep my note' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save GOOD_MORAL' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Record changed');
    expect(screen.getByLabelText('Verification notes: GOOD_MORAL')).toHaveValue('Keep my note');
  });

  it('shows follow-up contacts in a popover inside the drawer, not a second modal, and copies the numbers', async () => {
    const writeText = vi.fn().mockResolvedValue();
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } });
    show();
    const trigger = await screen.findByRole('button', { name: 'Contact for follow-up' });
    expect(trigger).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(trigger);
    expect(trigger).toHaveAttribute('aria-expanded', 'true');
    const panel = screen.getByRole('group', { name: 'Contact for follow-up' });
    expect(within(panel).getByText('Maria Santos')).toBeInTheDocument();
    expect(within(panel).getByText('09170000001')).toBeInTheDocument();
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    fireEvent.click(within(panel).getByRole('button', { name: 'Copy numbers' }));
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('09170000001\n09912345678'));
    expect(await within(panel).findByText('Copied 2 numbers.')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('group', { name: 'Contact for follow-up' })).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(screen.getByRole('dialog', { name: 'Requirements Checklist' })).toBeInTheDocument();
    expect(apiRequest.mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true);
  });

  it('closes with the close button or Escape and returns focus to the row that opened it', async () => {
    show('/enrollment/requirements');
    const open = await screen.findByRole('button', { name: 'Open APP-10' });
    open.focus();
    fireEvent.click(open);
    const drawer = await screen.findByRole('dialog', { name: 'Requirements Checklist' });
    fireEvent.click(within(drawer).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(open).toHaveFocus();
    fireEvent.click(open);
    const again = await screen.findByRole('dialog', { name: 'Requirements Checklist' });
    act(() => { again.dispatchEvent(new Event('cancel', { cancelable: true })); });
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(open).toHaveFocus();
  });

  it('returns focus to the row even after a save reloaded the list', async () => {
    show('/enrollment/requirements');
    const open = await screen.findByRole('button', { name: 'Open APP-10' });
    open.focus();
    fireEvent.click(open);
    fireEvent.click(await screen.findByRole('button', { name: 'Edit GOOD_MORAL' }));
    fireEvent.click(screen.getByRole('button', { name: 'Save GOOD_MORAL' }));
    await screen.findByText('Document verification saved.');
    await waitFor(() => expect(screen.getByRole('button', { name: 'Open APP-10' })).toBeInTheDocument());
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Close' }));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Open APP-10' })).toHaveFocus());
  });
});
