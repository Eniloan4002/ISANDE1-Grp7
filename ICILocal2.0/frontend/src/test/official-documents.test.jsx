import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import OfficialDocuments from '../documents/OfficialDocuments.jsx';
import * as docs from '../documents/officialDocumentsApi.js';
import { pages } from '../routes/routeConfig.js';

vi.mock('../documents/officialDocumentsApi.js', async importOriginal => ({
  ...(await importOriginal()),
  loadReference: vi.fn(), searchStudents: vi.fn(), loadStudentRecord: vi.fn(), loadDocumentPolicies: vi.fn(),
  createDocumentPolicy: vi.fn(), updateDocumentPolicy: vi.fn(), approveDocumentPolicy: vi.fn(), loadSpecialOrder: vi.fn(),
  saveSpecialOrder: vi.fn(), verifyDocument: vi.fn(), issueDocument: vi.fn(), previewDocument: vi.fn(), downloadIssuedDocument: vi.fn(),
}));

const policy = {
  id: 4, campus_id: 1, program_id: 1, document_type: 'FORM137', institution_name: 'ICI', institution_address: 'Iligan City', signatories: 'Registrar',
  template_reference: 'Layout v1', so_required: true, so_prefix: 'SO-', so_max_length: 40, version: 2, approval_reference: 'Sign-off 3', approved_at: '2026-11-01T00:00:00Z',
};
const blocked = {
  eligible: false,
  reasons: [
    'UNRESOLVED_GRADES: Every selected grade must be finalized and numeric for enrollment 101.',
    'SPECIAL_ORDER_REQUIRED: Registrar SO information is required for enrollment 101.',
  ],
};

beforeEach(() => {
  docs.loadReference.mockResolvedValue({ campuses: [{ id: 1, name: 'Iligan Main' }], programs: [{ id: 1, code: 'STEM', name: 'STEM' }, { id: 2, code: 'ABM', name: 'ABM' }] });
  docs.loadDocumentPolicies.mockResolvedValue([policy]);
  docs.searchStudents.mockResolvedValue({ items: [{ id: 9, first_name: 'Ana', last_name: 'Santos', student_number: 'STU-9', lrn: '1234' }], total: 1 });
  docs.loadStudentRecord.mockResolvedValue({ terms: [
    { enrollmentId: 101, academicYear: '2025-2026', term: 'First Semester', program: 'STEM', gradeLevel: '11', section: 'STEM 11-A', status: 'completed' },
    { enrollmentId: 102, academicYear: '2026-2027', term: 'First Semester', program: 'STEM', gradeLevel: '12', section: 'STEM 12-A', status: 'enrolled' },
    { enrollmentId: 103, academicYear: '2024-2025', term: 'First Semester', program: 'ABM', gradeLevel: '10', section: null, status: 'completed' },
  ] });
  docs.verifyDocument.mockResolvedValue(blocked);
  docs.loadSpecialOrder.mockImplementation(async id => ({ enrollment_id: id, version: -1, policyChecks: [] }));
  docs.previewDocument.mockResolvedValue('DRAFT-academic-record.pdf');
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

async function pickStudent() {
  render(<OfficialDocuments />);
  fireEvent.change(screen.getByLabelText('Find a student'), { target: { value: 'Santos' } });
  fireEvent.click(screen.getByRole('button', { name: 'Search' }));
  fireEvent.click(await screen.findByRole('button', { name: /Ana Santos/ }));
  await screen.findByText(/Terms to include/);
}

describe('Official documents screen', () => {
  it('is routed for the registrar', () => {
    expect(pages.find(page => page.path === '/documents/official').allowedRoles).toEqual(['registrar']);
  });

  it('verifies the selected student, policy and matching terms, and lists blockers in plain language', async () => {
    await pickStudent();
    expect(screen.getByLabelText('Document policy')).toHaveValue('4');
    expect(screen.queryByText(/ABM · Grade 10/)).not.toBeInTheDocument();
    await waitFor(() => expect(docs.verifyDocument).toHaveBeenCalledWith({ studentId: 9, policyId: 4, policyVersion: 2, enrollmentIds: [101, 102] }));
    const reasons = within(await screen.findByRole('list', { name: 'Blocking reasons' }));
    expect(reasons.getByText('Some grades are not finalized')).toBeInTheDocument();
    expect(reasons.getByText('The SO number is missing')).toBeInTheDocument();
    expect(reasons.getAllByText(/2025-2026 First Semester/).length).toBeGreaterThan(0);
    expect(screen.getByRole('button', { name: 'Issue document' })).toBeDisabled();
    fireEvent.click(screen.getByLabelText(/2026-2027 · First Semester/));
    await waitFor(() => expect(docs.verifyDocument).toHaveBeenLastCalledWith(expect.objectContaining({ enrollmentIds: [101] })));
  });

  it('records an SO number and runs verification again', async () => {
    docs.saveSpecialOrder.mockResolvedValue({ enrollment_id: 101, number: 'SO-2026-1', issued_on: '2026-06-01', issuing_authority: 'DepEd Region X', version: 0, policyChecks: [] });
    await pickStudent();
    await screen.findByRole('list', { name: 'Blocking reasons' });
    const calls = docs.verifyDocument.mock.calls.length;
    fireEvent.click(screen.getAllByRole('button', { name: 'Enter SO' })[0]);
    const form = within(screen.getByRole('form', { name: /Special Order for 2025-2026/ }));
    fireEvent.change(form.getByLabelText('SO number'), { target: { value: 'SO-2026-1' } });
    fireEvent.change(form.getByLabelText('Issued on'), { target: { value: '2026-06-01' } });
    fireEvent.change(form.getByLabelText('Issuing authority'), { target: { value: 'DepEd Region X' } });
    fireEvent.click(form.getByRole('button', { name: 'Save SO' }));
    await waitFor(() => expect(docs.saveSpecialOrder).toHaveBeenCalledWith(101, { version: -1, number: 'SO-2026-1', issuedOn: '2026-06-01', issuingAuthority: 'DepEd Region X' }));
    expect(await screen.findByText('SO SO-2026-1')).toBeInTheDocument();
    await waitFor(() => expect(docs.verifyDocument.mock.calls.length).toBeGreaterThan(calls));
  });

  it('previews, issues after confirming, and shows the hash and issue time', async () => {
    docs.verifyDocument.mockResolvedValue({ eligible: true, reasons: [] });
    docs.issueDocument.mockResolvedValue({ id: '7f6c2a1e-0000-4000-8000-000000000001', document_type: 'FORM137', sha256: 'ab12cd34', generated_at: '2026-11-28T02:00:00Z', policy_id: 4, policy_version: 2 });
    await pickStudent();
    expect(await screen.findByText('Ready to issue')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Preview draft PDF' }));
    await waitFor(() => expect(docs.previewDocument).toHaveBeenCalledWith({ studentId: 9, policyId: 4, policyVersion: 2, enrollmentIds: [101, 102] }));
    fireEvent.click(screen.getByRole('button', { name: 'Issue document' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Issue' }));
    await waitFor(() => expect(docs.issueDocument).toHaveBeenCalledWith(expect.objectContaining({ requestKey: expect.stringMatching(/^[0-9a-f-]{36}$/) })));
    expect(await screen.findByText('ab12cd34')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Issue document' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Download PDF' }));
    await waitFor(() => expect(docs.downloadIssuedDocument).toHaveBeenCalledWith('7f6c2a1e-0000-4000-8000-000000000001'));
  });

  it('reuses the request key when an issue attempt is retried', async () => {
    docs.verifyDocument.mockResolvedValue({ eligible: true, reasons: [] });
    docs.issueDocument.mockRejectedValueOnce(new Error('The service is unavailable. Check your connection and try again.'));
    docs.issueDocument.mockResolvedValueOnce({ id: 'g1', document_type: 'FORM137', sha256: 'ff', generated_at: '2026-11-28T02:00:00Z', policy_id: 4, policy_version: 2 });
    await pickStudent();
    await screen.findByText('Ready to issue');
    for (let attempt = 0; attempt < 2; attempt++) {
      fireEvent.click(screen.getByRole('button', { name: 'Issue document' }));
      fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Issue' }));
      await waitFor(() => expect(docs.issueDocument).toHaveBeenCalledTimes(attempt + 1));
    }
    const [first, second] = docs.issueDocument.mock.calls.map(call => call[0].requestKey);
    expect(second).toBe(first);
  });
});

describe('document blockers', () => {
  it('turns server codes into a title and names the term', () => {
    expect(docs.describeBlocker('WITHDRAWAL_CLEARANCE: Every student withdrawal must be completed before release.'))
      .toEqual({ code: 'WITHDRAWAL_CLEARANCE', title: 'A withdrawal is not completed', detail: 'Every student withdrawal must be completed before release.' });
    expect(docs.describeBlocker('MISSING_CREDENTIALS: verified for enrollment 5.', () => 'Grade 11 First Semester').detail).toBe('verified for Grade 11 First Semester.');
  });
});
