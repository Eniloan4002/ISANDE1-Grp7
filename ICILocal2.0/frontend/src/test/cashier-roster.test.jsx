import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import CashierRoster from '../cashiering/CashierRoster.jsx';
import * as cashier from '../cashiering/cashierApi.js';
import { pages } from '../routes/routeConfig.js';

vi.mock('../cashiering/cashierApi.js', async importOriginal => ({
  ...(await importOriginal()),
  loadRoster: vi.fn(),
  exportRoster: vi.fn(),
}));

const row = (id, name, voucher, voucherGroup, extra = {}) => ({
  enrollmentId: id, studentNumber: `STU-${id}`, name, campusId: 1, campus: 'Iligan Main', academicYearId: 1, academicYear: '2026-2027',
  termId: 10, term: 'First Semester', programId: 1, program: 'STEM', sectionId: 40, section: 'STEM 11-A', gradeLevel: '11',
  voucher, voucherGroup, confirmedAt: '2026-06-03T01:00:00Z', ...extra,
});
const roster = {
  items: [row(1, 'Ana Santos', 'QVR', 'QVR'), row(2, 'Ben Cruz', 'REGULAR', 'OTHER', { campusId: 2, campus: 'Tibanga', termId: 11, term: 'Second Semester', sectionId: 41, section: 'ABM 11-B' }), row(3, 'Cara Lim', 'UNCLASSIFIED', 'UNCLASSIFIED')],
  total: 3,
  voucherGroups: { QVR: 1, ESC: 0, ALS: 0, OTHER: 1, UNCLASSIFIED: 1 },
  vouchers: [{ voucher: 'QVR', voucherGroup: 'QVR', total: 1 }, { voucher: 'REGULAR', voucherGroup: 'OTHER', total: 1 }, { voucher: 'UNCLASSIFIED', voucherGroup: 'UNCLASSIFIED', total: 1 }],
  page: 0, size: 25, asOf: '2026-11-28T00:00:00Z', purpose: 'cashiering_reference',
};

beforeEach(() => {
  cashier.loadRoster.mockResolvedValue(roster);
  cashier.exportRoster.mockResolvedValue('ici-cashiering-roster.csv');
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('Cashiering enrolled student reference', () => {
  it('is routed for the cashiering reference role only', () => {
    expect(pages.find(page => page.path === '/cashiering/students').allowedRoles).toEqual(['cashier_ref']);
  });

  it('shows the roster with a count for every voucher group', async () => {
    render(<CashierRoster />);
    expect(await screen.findByText('Ana Santos')).toBeInTheDocument();
    const counts = screen.getByLabelText('Voucher counts');
    expect(within(counts).getByText('Total confirmed').nextSibling).toHaveTextContent('3');
    expect(within(counts).getByText('ESC').nextSibling).toHaveTextContent('0');
    expect(within(counts).getByText('Other vouchers').nextSibling).toHaveTextContent('1');
    expect(within(counts).getByText('Unclassified').nextSibling).toHaveTextContent('1');
    expect(within(screen.getByRole('table')).getByText('REGULAR')).toBeInTheDocument();
  });

  it('has no edit actions', async () => {
    render(<CashierRoster />);
    await screen.findByText('Ana Santos');
    const buttons = screen.getAllByRole('button').map(button => button.textContent);
    expect(buttons.some(text => /edit|delete|save|approve/i.test(text))).toBe(false);
  });

  it('filters by campus, term, section and voucher type when applied', async () => {
    render(<CashierRoster />);
    await screen.findByText('Ana Santos');
    await screen.findByRole('option', { name: 'Tibanga' });
    fireEvent.change(screen.getByLabelText('Campus'), { target: { value: '1' } });
    expect(screen.queryByRole('option', { name: '2026-2027 · Second Semester' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Term'), { target: { value: '10' } });
    expect(screen.queryByRole('option', { name: 'ABM 11-B' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Section'), { target: { value: '40' } });
    fireEvent.change(screen.getByLabelText('Voucher type'), { target: { value: 'REGULAR' } });
    fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }));
    await waitFor(() => expect(cashier.loadRoster).toHaveBeenLastCalledWith({ campusId: '1', termId: '10', sectionId: '40', voucher: 'REGULAR' }, 0, expect.anything()));
  });

  it('exports the applied filters as CSV and shows server errors', async () => {
    render(<CashierRoster />);
    await screen.findByText('Ana Santos');
    fireEvent.click(screen.getByRole('button', { name: 'Export CSV' }));
    expect(await screen.findByText('Downloaded ici-cashiering-roster.csv.')).toBeInTheDocument();
    expect(cashier.exportRoster).toHaveBeenCalledWith({ campusId: '', termId: '', sectionId: '', voucher: '' });
    cashier.exportRoster.mockRejectedValueOnce(new Error('Narrow the filters: the export is limited to 5,000 enrollments.'));
    fireEvent.click(screen.getByRole('button', { name: 'Export CSV' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('limited to 5,000');
  });

  it('shows an empty state', async () => {
    cashier.loadRoster.mockResolvedValue({ ...roster, items: [], total: 0, voucherGroups: { QVR: 0, ESC: 0, ALS: 0, OTHER: 0, UNCLASSIFIED: 0 }, vouchers: [] });
    render(<CashierRoster />);
    expect(await screen.findByText('No confirmed enrollments match these filters')).toBeInTheDocument();
  });
});
