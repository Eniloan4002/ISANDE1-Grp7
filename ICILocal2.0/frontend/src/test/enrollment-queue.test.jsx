import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { EnrollmentWorkspace, nextStep } from '../enrollment/EnrollmentScreens.jsx';
import { apiRequest } from '../api.js';

vi.mock('../api.js', () => ({ apiRequest: vi.fn() }));

const catalogs = {
  campuses: [{ id: 1, name: 'Iligan Main' }, { id: 2, name: 'Bugo' }],
  terms: [{ id: 2, name: 'Semester 1', academicYear: '2026-2027' }, { id: 3, name: 'Semester 2', academicYear: '2026-2027' }],
  programs: [{ id: 3, code: 'STEM', name: 'STEM', category: 'SHS' }],
  vouchers: [],
};
const row = (id, firstName, lastName, extra = {}) => ({
  id, applicationNumber: `APP-${id}`, firstName, lastName, name: `${firstName} ${lastName}`, lrn: null, campus: 'Iligan Main', program: 'STEM',
  submittedAt: '2026-06-01', status: 'submitted', requirementsStatus: 'Pending', requirementsSubmitted: 1, requirementsMandatory: 4,
  attendancePresent: false, sectionId: null, ...extra,
});
const rows = [
  row(10, 'Ana', 'Santos', { lrn: '123456789012' }),
  row(11, 'Ben', 'Cruz', { status: 'pre_enlisted', requirementsStatus: 'Complete', requirementsSubmitted: 4 }),
  row(12, 'Cara', 'Lim', { status: 'duplicate_flag' }),
];
const totals = { '': 42, submitted: 9, pre_enlisted: 12, duplicate_flag: 2, enrolled: 19 };

const directoryCalls = () => apiRequest.mock.calls.map(([path]) => path).filter(path => path.startsWith('/api/enrollment/applications?'));
const show = () => render(<MemoryRouter initialEntries={['/enrollment/queue']}><EnrollmentWorkspace screen="queue" /></MemoryRouter>);

beforeEach(() => {
  apiRequest.mockImplementation(async path => {
    if (path.endsWith('/reference-data')) return catalogs;
    if (path.startsWith('/api/enrollment/applications?')) {
      const params = new URLSearchParams(path.split('?')[1]);
      if (params.get('size') === '1') return { items: [], total: totals[params.get('status')] + (params.get('termId') ? 100 : 0) };
      return { items: rows, total: rows.length };
    }
    throw new Error(`Unexpected ${path}`);
  });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('Enrollment Queue revamp (ICI2-97)', () => {
  it('picks one next step per applicant state', () => {
    const step = (statusCode, extra = {}) => nextStep({ statusCode, requirements: 'Pending', attendancePresent: false, sectionId: null, ...extra });
    expect(step('submitted')).toBe('Review & pre-enlist');
    expect(step('under_review')).toBe('Review & pre-enlist');
    expect(step('duplicate_flag')).toBe('Resolve duplicate');
    expect(step('pre_enlisted')).toBe('Open checklist');
    expect(step('pre_enlisted', { requirements: 'Complete' })).toBe('Record attendance');
    expect(step('pre_enlisted', { requirements: 'Complete', attendancePresent: true })).toBe('Confirm enrollment');
    expect(step('enrolled')).toBe('Place in section');
    expect(step('enrolled', { sectionId: 30 })).toBe('View record');
    expect(step('withdrawn')).toBe('View record');
  });

  it('shows name with LRN, a documents meter, a status chip and a next-step link to the applicant record, without application IDs', async () => {
    show();
    const ana = (await screen.findByText('Santos, Ana')).closest('tr');
    expect(within(ana).getByText('LRN 123456789012')).toBeInTheDocument();
    expect(within(ana).getByRole('meter', { name: 'Documents for Ana Santos' })).toHaveAttribute('aria-valuetext', '1 of 4 documents');
    expect(within(ana).getByText('Submitted')).toBeInTheDocument();
    expect(within(ana).getByRole('link', { name: 'Review & pre-enlist: Ana Santos' })).toHaveAttribute('href', '/enrollment/applicants/10');
    const ben = screen.getByText('Cruz, Ben').closest('tr');
    expect(within(ben).getByText('No LRN')).toBeInTheDocument();
    expect(within(ben).getByRole('link', { name: 'Record attendance: Ben Cruz' })).toHaveAttribute('href', '/enrollment/applicants/11');
    expect(within(screen.getByText('Lim, Cara').closest('tr')).getByRole('link', { name: 'Resolve duplicate: Cara Lim' })).toBeInTheDocument();
    expect(screen.queryByText('APP-10')).not.toBeInTheDocument();
    expect(screen.queryByRole('columnheader', { name: /Application ID/ })).not.toBeInTheDocument();
  });

  it('uses the stat tiles as status filters and keeps them in step with the status select', async () => {
    show();
    await screen.findByText('Santos, Ana');
    const tiles = screen.getByRole('group', { name: 'Filter by application status' });
    await waitFor(() => expect(within(tiles).getByRole('button', { name: /Needs review/ })).toHaveTextContent('9'));
    expect(within(tiles).getByRole('button', { name: /All/ })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(within(tiles).getByRole('button', { name: /Pre-enlisted/ }));
    expect(within(tiles).getByRole('button', { name: /Pre-enlisted/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByLabelText('Status')).toHaveValue('pre_enlisted');
    await waitFor(() => expect(directoryCalls().some(path => path.includes('size=25') && path.includes('status=pre_enlisted'))).toBe(true));
    fireEvent.change(screen.getByLabelText('Status'), { target: { value: 'enrolled' } });
    expect(within(tiles).getByRole('button', { name: /Enrolled/ })).toHaveAttribute('aria-pressed', 'true');
  });

  it('recounts the tiles for the chosen campus and term, and keeps search and program filters', async () => {
    show();
    await screen.findByText('Santos, Ana');
    fireEvent.change(screen.getByLabelText('Term'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText('Campus'), { target: { value: '2' } });
    const tiles = screen.getByRole('group', { name: 'Filter by application status' });
    await waitFor(() => expect(within(tiles).getByRole('button', { name: /Duplicate flags/ })).toHaveTextContent('102'));
    expect(directoryCalls().some(path => path.includes('size=1') && path.includes('status=duplicate_flag') && path.includes('campusId=2') && path.includes('termId=3'))).toBe(true);
    fireEvent.change(screen.getByLabelText('Program'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText('Search applicants'), { target: { value: 'Santos' } });
    await waitFor(() => expect(directoryCalls().some(path => path.includes('size=25') && path.includes('programId=3') && path.includes('search=Santos') && path.includes('campusId=2') && path.includes('termId=3'))).toBe(true));
  });

  it('keeps New applicant as the single primary page action', async () => {
    show();
    await screen.findByText('Santos, Ana');
    expect(screen.getByRole('heading', { level: 1, name: 'Enrollment Queue' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'New applicant' })).toHaveAttribute('href', '/enrollment/intake');
  });
});
