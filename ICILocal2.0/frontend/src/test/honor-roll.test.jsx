import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import HonorRoll from '../reporting/HonorRoll.jsx';
import * as honors from '../reporting/honorsApi.js';
import { categoryFor, weightedAverage } from '../reporting/honorMath.js';
import { pages } from '../routes/routeConfig.js';

let mockRoles = ['registrar'];
vi.mock('../auth/AuthContext.jsx', () => ({ useAuth: () => ({ roles: mockRoles }) }));
vi.mock('../reporting/honorsApi.js', async importOriginal => ({
  ...(await importOriginal()),
  loadHonorFilters: vi.fn(), loadPrograms: vi.fn(), loadSubjects: vi.fn(), loadPolicies: vi.fn(),
  createPolicy: vi.fn(), updatePolicy: vi.fn(), approvePolicy: vi.fn(), loadHonorRoll: vi.fn(),
}));

const filters = {
  campuses: [{ id: 1, name: 'Iligan Main' }],
  academicYears: [{ id: 1, label: '2026-2027' }],
  terms: [{ id: 10, name: 'First Semester', academicYearId: 1 }],
  gradingPeriods: [{ id: 100, code: 'Q1', termId: 10 }],
  gradeLevels: ['11'],
};
const rules = {
  subjects: [{ subjectId: 1, weight: 1 }, { subjectId: 2, weight: 3 }], decimalPlaces: 2, rounding: 'HALF_UP', minimumSubjectGrade: 85,
  categories: [{ name: 'With Honors', minimumAverage: 90 }], tiePolicy: 'competition_include_all',
  examples: [{ name: 'Weighted case', values: [88, 92], expectedAverage: 91, expectedCategory: 'With Honors' }],
};
const draft = { id: 5, campus_id: 1, program_id: 1, grade_level: '11', grading_period_id: 100, status: 'draft', version: 1, rules, approval_reference: null, approved_at: null, updated_at: '2026-11-20T00:00:00Z' };
const approved = { ...draft, id: 4, status: 'approved', version: 3, approval_reference: 'Board resolution 12', approved_at: '2026-11-21T00:00:00Z' };

function conflict(message) { return Object.assign(new Error(message), { status: 409 }); }

beforeEach(() => {
  mockRoles = ['registrar'];
  honors.loadHonorFilters.mockResolvedValue(filters);
  honors.loadPrograms.mockResolvedValue([{ id: 1, code: 'STEM', name: 'Science, Technology, Engineering and Mathematics' }]);
  honors.loadSubjects.mockResolvedValue([{ id: 1, code: 'MATH11', name: 'General Mathematics', active: true }, { id: 2, code: 'SCI11', name: 'Earth Science', active: true }]);
  honors.loadPolicies.mockResolvedValue({ items: [draft, approved], total: 2 });
  honors.loadHonorRoll.mockResolvedValue({
    policyId: 4, policyVersion: 3, approvalReference: 'Board resolution 12', total: 4,
    items: [
      { enrollmentId: 1, studentNumber: 'S1', name: 'Ana Santos', average: 95.5, category: 'With Honors', eligible: true, reasons: [], rank: 1 },
      { enrollmentId: 2, studentNumber: 'S2', name: 'Ben Cruz', average: 95.5, category: 'With Honors', eligible: true, reasons: [], rank: 1 },
      { enrollmentId: 3, studentNumber: 'S3', name: 'Cara Lim', average: 92, category: 'With Honors', eligible: true, reasons: [], rank: 3 },
      { enrollmentId: 4, studentNumber: 'S4', name: 'Dan Reyes', average: null, category: null, eligible: false, reasons: ['non_numeric_mark_INC', 'pending_grade_correction'], rank: null },
    ],
  });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

async function chooseScope() {
  render(<HonorRoll />);
  await screen.findByRole('option', { name: 'Iligan Main' });
  await screen.findByRole('option', { name: /STEM/ });
  fireEvent.change(screen.getByLabelText('Campus'), { target: { value: '1' } });
  fireEvent.change(screen.getByLabelText('Program'), { target: { value: '1' } });
  fireEvent.change(screen.getByLabelText('Grade level'), { target: { value: '11' } });
  fireEvent.change(screen.getByLabelText('Grading period'), { target: { value: '100' } });
}

describe('Honor roll screen', () => {
  it('is routed for the registrar and principal', () => {
    expect(pages.find(page => page.path === '/reporting/honor-roll').allowedRoles).toEqual(['registrar', 'principal']);
  });

  it('shows ranked students with shared ranks, and ineligible students with reasons', async () => {
    await chooseScope();
    fireEvent.click(screen.getByRole('button', { name: 'Compute honor roll' }));
    const ranked = within(await screen.findByRole('region', { name: 'Honor roll' }));
    const rows = ranked.getAllByRole('row').slice(1).map(row => row.textContent);
    expect(rows[0]).toMatch(/^1Ana Santos/);
    expect(rows[1]).toMatch(/^1Ben Cruz/);
    expect(rows[2]).toMatch(/^3Cara Lim/);
    const ineligible = within(screen.getByRole('region', { name: 'Not eligible' }));
    expect(ineligible.getByText('Marked INC instead of a numeric grade')).toBeInTheDocument();
    expect(ineligible.getByText('A grade correction is still pending')).toBeInTheDocument();
    expect(honors.loadHonorRoll).toHaveBeenCalledWith({ campusId: '1', programId: '1', gradeLevel: '11', gradingPeriodId: '100' });
  });

  it('shows a clear empty state when no approved policy exists', async () => {
    honors.loadHonorRoll.mockRejectedValue(conflict('No school-approved honor policy applies.'));
    await chooseScope();
    fireEvent.click(screen.getByRole('button', { name: 'Compute honor roll' }));
    expect(await screen.findByText('No approved honor policy for this scope')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Open honor policies' }));
    expect(await screen.findByRole('tab', { name: 'Honor policies', selected: true })).toBeInTheDocument();
  });

  it('keeps approved policies read-only', async () => {
    await chooseScope();
    fireEvent.click(screen.getByRole('tab', { name: 'Honor policies' }));
    const row = (await screen.findByText('Board resolution 12')).closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: 'View' }));
    expect(await screen.findByText(/is approved and read-only/)).toBeInTheDocument();
    expect(screen.getByLabelText('Minimum subject grade')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save draft' })).not.toBeInTheDocument();
  });

  it('edits a draft and sends the rules with its version', async () => {
    honors.updatePolicy.mockResolvedValue({ ...draft, version: 2 });
    await chooseScope();
    fireEvent.click(screen.getByRole('tab', { name: 'Honor policies' }));
    const row = (await screen.findByText('#5 · Grade 11')).closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: 'Edit' }));
    expect(await screen.findByText(/These rules compute 91.00, category “With Honors”/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Minimum subject grade'), { target: { value: '80' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(honors.updatePolicy).toHaveBeenCalledWith(5, 1, { ...rules, minimumSubjectGrade: 80 }));
    expect(await screen.findByText('Draft saved.')).toBeInTheDocument();
  });

  it('lets the principal approve a draft with a reference after confirming', async () => {
    mockRoles = ['principal'];
    honors.approvePolicy.mockResolvedValue({ ...draft, status: 'approved', version: 2, approval_reference: 'Minutes 7', approved_at: '2026-11-28T00:00:00Z' });
    await chooseScope();
    fireEvent.click(screen.getByRole('tab', { name: 'Honor policies' }));
    expect(screen.queryByRole('button', { name: 'New draft' })).not.toBeInTheDocument();
    const row = (await screen.findByText('#5 · Grade 11')).closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: 'View' }));
    fireEvent.change(await screen.findByLabelText('School approval reference'), { target: { value: 'Minutes 7' } });
    fireEvent.click(screen.getByRole('button', { name: 'Approve policy' }));
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Approve' }));
    await waitFor(() => expect(honors.approvePolicy).toHaveBeenCalledWith(5, 1, 'Minutes 7'));
    expect(await screen.findByText(/Policy approved/)).toBeInTheDocument();
  });
});

describe('honor math', () => {
  it('computes the weighted average exactly and rounds once', () => {
    expect(weightedAverage(['88', '92'], ['1', '3'], 2, 'HALF_UP')).toBe('91.00');
    expect(weightedAverage(['90', '91'], ['1', '1'], 0, 'HALF_UP')).toBe('91');
    expect(weightedAverage(['90', '91'], ['1', '1'], 0, 'HALF_EVEN')).toBe('90');
    expect(weightedAverage(['90', '91'], ['1', '1'], 0, 'DOWN')).toBe('90');
    expect(weightedAverage(['1', '2', '2'], ['1', '1', '1'], 3, 'HALF_UP')).toBe('1.667');
  });

  it('awards the highest category met, and none below the subject minimum', () => {
    const categories = [{ name: 'With Honors', minimumAverage: '90' }, { name: 'With High Honors', minimumAverage: '95' }];
    expect(categoryFor('96', ['96', '96'], '85', categories)).toBe('With High Honors');
    expect(categoryFor('92', ['99', '84'], '85', categories)).toBeNull();
    expect(categoryFor('89.99', ['90', '89'], '85', categories)).toBeNull();
  });
});
