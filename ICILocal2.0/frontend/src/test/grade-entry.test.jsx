import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import GradeEntry from '../academic/GradeEntry.jsx';
import { pages } from '../routes/routeConfig.js';
import * as api from '../academic/gradeEntryApi.js';

vi.mock('../academic/gradeEntryApi.js');

afterEach(() => { cleanup(); vi.clearAllMocks(); });
HTMLDialogElement.prototype.showModal ??= function showModal() { this.open = true; };
HTMLDialogElement.prototype.close ??= function close() { this.open = false; };

const classes = { teacher: { id: 501, name: 'Michael Reyes' }, items: [{ id: 301, subjectCode: 'MATH101', subject: 'Mathematics', section: 'STEM 11-A', gradeLevel: 'Grade 11', room: 'Room 1', periods: [{ id: 'q1', label: 'First Grading Period' }] }] };
const book = { teacher: classes.teacher, class: classes.items[0], period: { id: 'q1', label: 'First Grading Period', status: 'open', deadline: 'October 15, 2026' }, students: [{ id: 1, studentNumber: '2026-0001', name: 'Althea Santos' }, { id: 2, studentNumber: '2026-0002', name: 'Brandon Cruz' }], grades: { 1: { value: '87.50', kind: 'numeric' }, 2: { value: 'INC', kind: 'mark' } }, submission: 'draft' };

function renderPage() {
  api.loadTeacherClasses.mockResolvedValue(classes);
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
    expect(screen.getByText('Incomplete mark')).toBeInTheDocument();
  });

  it('validates numeric and non-numeric paths separately', async () => {
    renderPage();
    await screen.findByRole('textbox', { name: 'Grade for Althea Santos' });
    const input = screen.getByRole('textbox', { name: 'Grade for Althea Santos' });
    fireEvent.change(input, { target: { value: '101' } });
    expect(screen.getByText('Numeric grades must be between 0.00 and 100.00.')).toBeInTheDocument();
    fireEvent.change(input, { target: { value: 'INC' } });
    expect(screen.getAllByText('Non-numeric mark')).toHaveLength(2);
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
  });

  it('saves drafts through the mock service and reports the boundary', async () => {
    renderPage();
    await screen.findByRole('textbox', { name: 'Grade for Althea Santos' });
    api.saveGradeDraft.mockResolvedValue({ ...book, grades: book.grades });
    fireEvent.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(api.saveGradeDraft).toHaveBeenCalledWith('301', 'q1', expect.objectContaining({ 1: expect.any(Object) })));
    expect(await screen.findByText(/temporary frontend fixture/)).toBeInTheDocument();
  });

  it('requires confirmation and makes submitted grades read-only', async () => {
    renderPage();
    await screen.findByText('Althea Santos');
    fireEvent.click(screen.getByRole('button', { name: 'Submit grades' }));
    expect(screen.getByRole('heading', { name: 'Submit grades?' })).toBeInTheDocument();
    api.submitGrades.mockResolvedValue({ ...book, submission: 'submitted' });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(api.submitGrades).toHaveBeenCalled());
    expect(await screen.findByText('Submitted · read-only')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save draft' })).toBeDisabled();
  });
});
