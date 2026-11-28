import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import ReportingMonitoring from '../enrollment/ReportingMonitoring.jsx';
import * as reports from '../enrollment/reportsApi.js';
import { api } from '../api/client.js';

vi.mock('../enrollment/reportsApi.js', async importOriginal => ({
  ...(await importOriginal()),
  loadFilterOptions: vi.fn(),
  loadReportPreview: vi.fn(),
  downloadReport: vi.fn(),
}));

const options = {
  campuses: [{ id: 1, name: 'Iligan Main' }],
  academicYears: [{ id: 1, label: '2026-2027' }, { id: 2, label: '2025-2026' }],
  terms: [{ id: 10, name: 'First Semester', academicYearId: 1 }, { id: 9, name: 'Second Semester', academicYearId: 2 }],
  gradingPeriods: [{ id: 100, code: 'Q1', termId: 10 }, { id: 90, code: 'Q3', termId: 9 }],
  sections: [{ id: 40, name: 'STEM 11-A', termId: 10, campusId: 1 }, { id: 41, name: 'ABM 11-A', termId: 9, campusId: 1 }],
  gradeLevels: ['11', '12'],
  vouchers: ['QVR', 'ESC'],
};
const preview = {
  total: 2,
  summary: { total: 2, enrolled: 2, incomplete: 1 },
  vouchers: [{ voucher: 'ESC', total: 1 }, { voucher: 'QVR', total: 1 }],
  items: [
    { enrollmentId: 1, name: 'Ana Santos', studentNumber: 'STU-1', campus: 'Iligan Main', academicYear: '2026-2027', term: 'First Semester', gradeLevel: '11', section: 'STEM 11-A', program: 'STEM', voucher: 'QVR', status: 'enrolled', missingRequirements: 0, missingDocuments: '' },
    { enrollmentId: 2, name: 'Ben Cruz', studentNumber: 'STU-2', campus: 'Iligan Main', academicYear: '2026-2027', term: 'First Semester', gradeLevel: '11', section: null, program: 'STEM', voucher: 'ESC', status: 'provisional', missingRequirements: 2, missingDocuments: 'PSA, Form 138' },
  ],
};

beforeEach(() => {
  reports.loadFilterOptions.mockResolvedValue(options);
  reports.loadReportPreview.mockResolvedValue(preview);
  reports.downloadReport.mockResolvedValue('ici-enrollments.csv');
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('Reporting & Monitoring screen', () => {
  it('shows summary cards, voucher counts and the preview table', async () => {
    render(<ReportingMonitoring />);
    expect(await screen.findByText('Ana Santos')).toBeInTheDocument();
    const summary = screen.getByLabelText('Report summary');
    expect(within(summary).getByText('Matching enrollments').nextSibling).toHaveTextContent('2');
    expect(within(summary).getByText('Incomplete documents').nextSibling).toHaveTextContent('1');
    const vouchers = screen.getByLabelText('Voucher counts');
    expect(within(vouchers).getByText('ESC')).toBeInTheDocument();
    expect(screen.getByText('PSA, Form 138')).toBeInTheDocument();
    expect(within(screen.getByRole('table')).getByText('Pre-enlisted')).toBeInTheDocument();
    expect(reports.loadReportPreview.mock.calls[0][1]).toBe(0);
  });

  it('applies every supported filter only when asked, and sends them together', async () => {
    render(<ReportingMonitoring />);
    await screen.findByText('Ana Santos');
    await screen.findByRole('option', { name: 'Iligan Main' });
    const callsBefore = reports.loadReportPreview.mock.calls.length;
    fireEvent.change(screen.getByLabelText('Campus'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('Academic year'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('Term'), { target: { value: '10' } });
    fireEvent.change(screen.getByLabelText('Grading period'), { target: { value: '100' } });
    fireEvent.change(screen.getByLabelText('Section'), { target: { value: '40' } });
    fireEvent.change(screen.getByLabelText('Grade level'), { target: { value: '11' } });
    fireEvent.change(screen.getByLabelText('Voucher'), { target: { value: 'QVR' } });
    fireEvent.change(screen.getByLabelText('Requirements'), { target: { value: 'incomplete' } });
    fireEvent.change(screen.getByLabelText('Enrollment status'), { target: { value: 'enrolled' } });
    expect(reports.loadReportPreview.mock.calls.length).toBe(callsBefore);
    fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }));
    await waitFor(() => expect(reports.loadReportPreview).toHaveBeenLastCalledWith(
      { campusId: '1', academicYearId: '1', termId: '10', gradingPeriodId: '100', sectionId: '40', gradeLevel: '11', voucher: 'QVR', requirementStatus: 'incomplete', enrollmentStatus: 'enrolled' },
      0, expect.anything()));
  });

  it('narrows terms, periods and sections to the chosen year and term', async () => {
    render(<ReportingMonitoring />);
    await screen.findByRole('option', { name: 'Iligan Main' });
    expect(screen.getByLabelText('Grading period')).toHaveDisplayValue('Choose a term first');
    fireEvent.change(screen.getByLabelText('Academic year'), { target: { value: '2' } });
    expect(screen.queryByRole('option', { name: 'First Semester' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Term'), { target: { value: '9' } });
    expect(screen.getByRole('option', { name: 'Q3' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Q1' })).not.toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'ABM 11-A' })).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'STEM 11-A' })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Section'), { target: { value: '41' } });
    fireEvent.change(screen.getByLabelText('Academic year'), { target: { value: '1' } });
    expect(screen.getByLabelText('Term')).toHaveValue('');
    expect(screen.getByLabelText('Section')).toHaveValue('');
  });

  it('exports the applied filters as CSV and as PDF', async () => {
    render(<ReportingMonitoring />);
    await screen.findByText('Ana Santos');
    fireEvent.click(screen.getByRole('button', { name: 'Export CSV' }));
    expect(await screen.findByText('Downloaded ici-enrollments.csv.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Export PDF' }));
    await waitFor(() => expect(reports.downloadReport).toHaveBeenCalledTimes(2));
    expect(reports.downloadReport.mock.calls.map(call => call[1])).toEqual(['csv', 'pdf']);
  });

  it('shows the server message when an export is too large', async () => {
    reports.downloadReport.mockRejectedValue(new Error('Narrow the filters: this operation is limited to 5000 enrollments.'));
    render(<ReportingMonitoring />);
    await screen.findByText('Ana Santos');
    fireEvent.click(screen.getByRole('button', { name: 'Export CSV' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('limited to 5000 enrollments');
    expect(screen.getByRole('button', { name: 'Export CSV' })).toBeEnabled();
  });

  it('shows an empty state and an error state', async () => {
    reports.loadReportPreview.mockResolvedValueOnce({ total: 0, summary: { total: 0, enrolled: 0, incomplete: 0 }, vouchers: [], items: [] });
    const { unmount } = render(<ReportingMonitoring />);
    expect(await screen.findByText('No enrollments match these filters')).toBeInTheDocument();
    unmount();
    reports.loadReportPreview.mockRejectedValueOnce(new Error('Campus is outside your report scope.'));
    render(<ReportingMonitoring />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Campus is outside your report scope.');
  });

  it('resets the filters', async () => {
    render(<ReportingMonitoring />);
    await screen.findByRole('option', { name: 'Iligan Main' });
    fireEvent.change(screen.getByLabelText('Voucher'), { target: { value: 'QVR' } });
    fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }));
    await waitFor(() => expect(reports.loadReportPreview.mock.lastCall[0].voucher).toBe('QVR'));
    fireEvent.click(screen.getByRole('button', { name: 'Reset' }));
    await waitFor(() => expect(reports.loadReportPreview.mock.lastCall[0].voucher).toBe(''));
    expect(screen.getByLabelText('Voucher')).toHaveValue('');
  });
});

describe('report requests', () => {
  it('sends only the filters that have a value', () => {
    const query = reports.reportQuery({ ...reports.emptyFilters, termId: '10', voucher: 'QVR' }, { page: 2, size: 25 });
    expect(query.toString()).toBe('termId=10&voucher=QVR&page=2&size=25');
  });

  it('turns a failed export into the server message', async () => {
    const original = reports.downloadReport;
    const { downloadReport } = await vi.importActual('../enrollment/reportsApi.js');
    const blob = new Blob([JSON.stringify({ detail: 'Narrow the filters: this operation is limited to 5000 enrollments.' })]);
    const spy = vi.spyOn(api, 'get').mockRejectedValue({ response: { status: 413, data: blob } });
    await expect(downloadReport({ ...reports.emptyFilters, termId: '10' }, 'pdf')).rejects.toThrow('limited to 5000 enrollments');
    expect(spy).toHaveBeenCalledWith(expect.stringContaining('/reports/enrollments/export?termId=10&format=pdf'), { responseType: 'blob' });
    spy.mockRestore();
    expect(original).toBeDefined();
  });
});
