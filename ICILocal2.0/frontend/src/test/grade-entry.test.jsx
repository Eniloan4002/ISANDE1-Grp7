import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import GradeEntry from '../academic/GradeEntry.jsx';
import { pages } from '../routes/routeConfig.js';
import * as api from '../academic/gradeEntryApi.js';

vi.mock('../academic/gradeEntryApi.js', async importOriginal => ({ ...(await importOriginal()), loadGradeCatalog: vi.fn(), loadTeacherClasses: vi.fn(), loadGradingPeriods: vi.fn(), loadClassGradebook: vi.fn(), saveGradeDraft: vi.fn(), submitGrades: vi.fn() }));

afterEach(() => { cleanup(); vi.clearAllMocks(); });
HTMLDialogElement.prototype.showModal ??= function showModal() { this.open = true; };
HTMLDialogElement.prototype.close ??= function close() { this.open = false; };

const catalog = { years: [{ id: 1, label: '2026-2027' }], terms: [{ id: 10, academic_year_id: 1, name: 'First Term', code: 'T1' }] };
const classes = { items: [{ id: 301, subjectCode: 'MATH101', subject: 'Mathematics', section: 'STEM 11-A', gradeLevel: 'Grade 11', room: 'Room 1' }] };
const periods = [{ id: 7, label: 'Q1', status: 'open' }];
const book = {
  class: classes.items[0],
  period: { id: 7, label: 'Q1', status: 'open', deadline: 'October 15, 2026' },
  students: [{ id: 11, studentNumber: '2026-0001', name: 'Althea Santos', version: 0 }, { id: 12, studentNumber: '2026-0002', name: 'Brandon Cruz', version: 0 }],
  grades: { 11: { value: '87.50', kind: 'numeric' }, 12: { value: 'INC', kind: 'mark' } },
  submission: 'draft',
};

function renderPage() {
  api.loadGradeCatalog.mockResolvedValue(catalog);
  api.loadTeacherClasses.mockResolvedValue(classes);
  api.loadGradingPeriods.mockResolvedValue(periods);
  api.loadClassGradebook.mockResolvedValue(book);
  return render(<GradeEntry />);
}

function renderPageWith(classList, search) {
  window.history.replaceState({}, '', `/academic/grade-entry${search}`);
  api.loadGradeCatalog.mockResolvedValue(catalog);
  api.loadTeacherClasses.mockResolvedValue(classList);
  api.loadGradingPeriods.mockResolvedValue(periods);
  api.loadClassGradebook.mockResolvedValue(book);
  return render(<GradeEntry />);
}

describe('Grade Entry', () => {
  it('is restricted to Subject Teachers', () => {
    expect(pages.find(page => page.path === '/academic/grade-entry').allowedRoles).toEqual(['teacher']);
  });

  it('renders assigned class context and student grid', async () => {
    renderPage();
    expect(await screen.findByText('Althea Santos')).toBeInTheDocument();
    expect(screen.getByText('2026-0001')).toBeInTheDocument();
    expect(screen.getByText('Incomplete')).toBeInTheDocument();
    expect(screen.getByRole('textbox', { name: 'Grade for Althea Santos' })).toHaveValue('87.50');
  });

  it('keeps class, period, term, status and actions in one sticky context bar', async () => {
    renderPage();
    await screen.findByText('Althea Santos');
    const bar = screen.getByRole('region', { name: 'Grade entry context' });
    expect(within(bar).getByRole('combobox', { name: 'Class' })).toHaveDisplayValue('MATH101 Mathematics · Grade 11 STEM 11-A');
    expect(within(bar).getByRole('combobox', { name: 'Grading period' })).toHaveDisplayValue('Q1');
    expect(within(bar).getByRole('combobox', { name: 'Term' })).toHaveDisplayValue('2026-2027 · First Term');
    expect(within(bar).getByText('Period open')).toBeInTheDocument();
    expect(within(bar).getByText('Due October 15, 2026')).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Save draft' })).toBeInTheDocument();
    expect(within(bar).getByRole('button', { name: 'Submit grades' })).toBeInTheDocument();
    expect(screen.getAllByRole('combobox')).toHaveLength(3);
  });

  it('counts graded students and those that need attention', async () => {
    renderPage();
    await screen.findByText('Althea Santos');
    expect(screen.getByRole('meter', { name: 'Grading progress' })).toHaveAttribute('aria-valuetext', '1 of 2 graded · 1 needs attention');
    fireEvent.change(screen.getByRole('textbox', { name: 'Grade for Brandon Cruz' }), { target: { value: '90' } });
    expect(screen.getByRole('meter', { name: 'Grading progress' })).toHaveAttribute('aria-valuetext', '2 of 2 graded');
  });

  it('shows the save state as grades change', async () => {
    renderPage();
    await screen.findByText('Althea Santos');
    expect(screen.getByText('All changes saved')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox', { name: 'Grade for Althea Santos' }), { target: { value: '88' } });
    expect(screen.getByText('Unsaved changes')).toBeInTheDocument();
    api.saveGradeDraft.mockResolvedValue(book);
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByText('All changes saved')).toBeInTheDocument();
  });

  it('shortens generated student numbers and keeps the full value as a tooltip', async () => {
    const uuid = 'STU-3f2a9c1d-7b4e-4c1a-9e2f-0a1b2c3d4e5f';
    api.loadGradeCatalog.mockResolvedValue(catalog);
    api.loadTeacherClasses.mockResolvedValue(classes);
    api.loadGradingPeriods.mockResolvedValue(periods);
    api.loadClassGradebook.mockResolvedValue({ ...book, students: [{ ...book.students[0], studentNumber: uuid }] });
    render(<GradeEntry />);
    const cell = await screen.findByText('STU-3F2A9C1D');
    expect(cell).toHaveAttribute('title', uuid);
    expect(screen.queryByText(uuid)).not.toBeInTheDocument();
  });

  it('opens the class named in the link from Assigned Classes', async () => {
    const second = { id: 302, subjectCode: 'PHYS101', subject: 'Physical Science', section: 'STEM 11-B', gradeLevel: 'Grade 11', room: 'Room 2' };
    renderPageWith({ items: [classes.items[0], second] }, '?classId=302&academicYearId=1&termId=10');
    await waitFor(() => expect(api.loadClassGradebook).toHaveBeenCalledWith('302', '7', { academicYearId: '1', termId: '10' }));
    window.history.replaceState({}, '', '/');
  });

  it('falls back to the first class when the link names one that is not assigned', async () => {
    renderPageWith(classes, '?classId=999');
    await waitFor(() => expect(api.loadClassGradebook).toHaveBeenCalledWith('301', '7', { academicYearId: '1', termId: '10' }));
    window.history.replaceState({}, '', '/');
  });

  it('validates numeric and non-numeric paths separately', async () => {
    renderPage();
    await screen.findByRole('textbox', { name: 'Grade for Althea Santos' });
    const input = screen.getByRole('textbox', { name: 'Grade for Althea Santos' });
    fireEvent.change(input, { target: { value: '101' } });
    expect(screen.getByText('Max is 100.00')).toBeInTheDocument();
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Max is 100.00');
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
    fireEvent.change(input, { target: { value: '87.555' } });
    expect(screen.getByText('Use up to 2 decimal places')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: 'abc' } });
    expect(screen.getByText('Enter 0.00–100.00 or INC, NG, DRP')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: '100.00' } });
    expect(input).toHaveAttribute('aria-invalid', 'false');
    fireEvent.change(input, { target: { value: 'drp' } });
    expect(input).toHaveValue('DRP');
    expect(screen.getByText('Dropped')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeEnabled();
  });

  it('moves focus with keyboard navigation', async () => {
    renderPage();
    await screen.findByRole('textbox', { name: 'Grade for Althea Santos' });
    const first = screen.getByRole('textbox', { name: 'Grade for Althea Santos' });
    const second = screen.getByRole('textbox', { name: 'Grade for Brandon Cruz' });
    first.focus();
    fireEvent.keyDown(first, { key: 'ArrowDown' });
    expect(document.activeElement).toBe(second);
    fireEvent.keyDown(second, { key: 'ArrowUp' });
    expect(document.activeElement).toBe(first);
    fireEvent.keyDown(first, { key: 'Enter' });
    expect(document.activeElement).toBe(second);
    fireEvent.keyDown(second, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(first);
    fireEvent.keyDown(first, { key: 'Tab' });
    expect(document.activeElement).toBe(second);
  });

  it('loads the class for the selected year, term and grading period', async () => {
    renderPage();
    await screen.findByText('Althea Santos');
    expect(api.loadTeacherClasses).toHaveBeenCalledWith({ academicYearId: '1', termId: '10' });
    expect(api.loadClassGradebook).toHaveBeenCalledWith('301', '7', { academicYearId: '1', termId: '10' });
  });

  it('saves drafts with the student versions through the API', async () => {
    renderPage();
    await screen.findByRole('textbox', { name: 'Grade for Althea Santos' });
    api.saveGradeDraft.mockResolvedValue({ ...book, grades: book.grades });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(api.saveGradeDraft).toHaveBeenCalledWith('301', '7', expect.objectContaining({ 11: expect.any(Object) }), book.students, { academicYearId: '1', termId: '10' }));
    expect(await screen.findByText('Draft grades saved.')).toBeInTheDocument();
    expect(screen.queryByText(/mock/i)).not.toBeInTheDocument();
  });

  it('shows the server message when a save conflicts', async () => {
    renderPage();
    await screen.findByRole('textbox', { name: 'Grade for Althea Santos' });
    api.saveGradeDraft.mockRejectedValue(Object.assign(new Error('Grades changed. Reload before saving.'), { status: 409 }));
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Grades changed. Reload before saving.');
  });

  it('shows a locked period as read-only', async () => {
    renderPage();
    api.loadClassGradebook.mockResolvedValue({ ...book, period: { ...book.period, status: 'locked' } });
    render(<GradeEntry />);
    expect((await screen.findAllByText('Period locked')).length).toBeGreaterThan(0);
  });

  it('requires confirmation and makes submitted grades read-only', async () => {
    renderPage();
    await screen.findByText('Althea Santos');
    fireEvent.click(screen.getByRole('button', { name: 'Submit grades' }));
    expect(screen.getByRole('heading', { name: 'Submit grades?' })).toBeInTheDocument();
    api.saveGradeDraft.mockResolvedValue(book);
    api.submitGrades.mockResolvedValue({ ...book, submission: 'submitted' });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(api.submitGrades).toHaveBeenCalledWith('301', '7', { academicYearId: '1', termId: '10' }));
    expect(api.saveGradeDraft).toHaveBeenCalled();
    expect(await screen.findByText('Submitted · read-only')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
  });
});
