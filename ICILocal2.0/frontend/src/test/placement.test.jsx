import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import EnrollmentWorkspace from '../EnrollmentWorkspace.jsx';
import { seatState } from '../enrollment/Placement.jsx';
import { apiRequest } from '../api.js';

vi.mock('../api.js', () => ({ apiRequest: vi.fn() }));

const reference = { campuses: [{ id: 1, name: 'Iligan Main' }], terms: [{ id: 2, name: 'Semester 1', academicYear: '2026-2027' }], programs: [{ id: 3, name: 'STEM' }], advisers: [] };
const student = (id, name, extra = {}) => ({ id, name, campusId: 1, campus: 'Iligan Main', termId: 2, programId: 3, program: 'STEM', gradeLevel: '11', status: 'enrolled', sectionId: null, section: null, ...extra });
const section = (id, name, occupied, capacity = 40, extra = {}) => ({ id, name, campusId: 1, termId: 2, programId: 3, gradeLevel: '11', room: '204', capacity, occupied, available: capacity - occupied, adviser: 'Ms. Reyes', version: 0, ...extra });
let students, sections, enrollments;

function detailFor(id) {
  const row = students.find(item => item.id === id);
  const enrollment = id in enrollments ? enrollments[id] : { id: 100 + id, status: 'enrolled', version: 3, sectionId: row.sectionId, section: row.section };
  return { application: { id, name: row.name, campusId: 1, termId: 2, programId: 3, version: 1 }, enrollment };
}
const listCalls = () => apiRequest.mock.calls.map(([path]) => path).filter(path => path.startsWith('/api/enrollment/applications?'));
const show = (path = '/enrollment/enrolled') => render(<MemoryRouter initialEntries={[path]}><EnrollmentWorkspace mode="allocation" /></MemoryRouter>);

beforeEach(() => {
  students = [student(10, 'Ana Santos'), student(11, 'Ben Cruz'), student(12, 'Cara Lim', { sectionId: 30, section: 'STEM A' })];
  sections = [section(30, 'STEM A', 29), section(31, 'STEM B', 37), section(32, 'STEM C', 40)];
  enrollments = {};
  HTMLDialogElement.prototype.showModal = function showModal() { this.open = true; };
  HTMLDialogElement.prototype.close = function close() { this.open = false; };
  apiRequest.mockImplementation(async (path, options = {}) => {
    if (options.method === 'PUT') {
      const { sectionId } = JSON.parse(options.body);
      const id = Number(path.split('/')[3]) - 100;
      students = students.map(row => row.id === id ? { ...row, sectionId, section: sections.find(item => item.id === sectionId)?.name ?? null } : row);
      return {};
    }
    if (path.includes('/reference-data')) return reference;
    if (path.startsWith('/api/enrollment/applications?')) {
      const params = new URLSearchParams(path.split('?')[1]);
      const placement = params.get('placement');
      const items = students.filter(row => !placement || (placement === 'placed') === Boolean(row.sectionId));
      return { items: params.get('size') === '1' ? [] : items, total: items.length };
    }
    const detail = /^\/api\/applications\/(\d+)\/enrollment$/.exec(path);
    if (detail) return detailFor(Number(detail[1]));
    if (path.startsWith('/api/sections?')) return { items: sections, total: sections.length };
    throw new Error(`Unexpected ${path}`);
  });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('Enrolled & Assigned placement (ICI2-100)', () => {
  it('flags almost-full and full sections', () => {
    expect(seatState(section(1, 'A', 29))).toBe('open');
    expect(seatState(section(1, 'A', 37))).toBe('almost');
    expect(seatState(section(1, 'A', 40))).toBe('full');
    expect(seatState(section(1, 'A', 8, 10))).toBe('almost');
  });

  it('shows the three tabs with counts and lists students with a section chip', async () => {
    show();
    const tabs = screen.getByRole('tablist', { name: 'Placement' });
    await waitFor(() => expect(within(tabs).getByRole('tab', { name: /Needs a section/ })).toHaveTextContent('2'));
    expect(within(tabs).getByRole('tab', { name: /Placed/ })).toHaveTextContent('1');
    expect(within(tabs).getByRole('tab', { name: /All confirmed/ })).toHaveTextContent('3');
    expect(within(tabs).getByRole('tab', { name: /Needs a section/ })).toHaveAttribute('aria-selected', 'true');
    const panel = screen.getByRole('tabpanel');
    expect(await within(panel).findByText('Ana Santos')).toBeInTheDocument();
    expect(within(panel).queryByText('Cara Lim')).not.toBeInTheDocument();
    expect(within(panel).getAllByText('No section')).toHaveLength(2);
    fireEvent.click(within(tabs).getByRole('tab', { name: /Placed/ }));
    expect(await within(panel).findByText('Cara Lim')).toBeInTheDocument();
    expect(within(panel).getByText('STEM A')).toBeInTheDocument();
    expect(listCalls().some(path => path.includes('size=25') && path.includes('placement=placed') && path.includes('status=enrolled'))).toBe(true);
  });

  it('hides district and route behind More filters', async () => {
    show();
    await screen.findByText('Ana Santos');
    expect(screen.queryByLabelText('Residential district')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'More filters' }));
    fireEvent.change(screen.getByLabelText('Residential district'), { target: { value: 'Tibanga' } });
    await waitFor(() => expect(listCalls().some(path => path.includes('district=Tibanga'))).toBe(true));
  });

  it('shows section cards with adviser and live capacity, and full sections cannot be chosen', async () => {
    show('/enrollment/enrolled?applicationId=10');
    const cards = await screen.findByRole('group', { name: /Ana Santos Sections for this campus/ });
    const b = within(cards).getByText('STEM B').closest('label');
    expect(b).toHaveTextContent('Almost full');
    expect(within(b).getByRole('meter', { name: 'Seats' })).toHaveAttribute('aria-valuetext', '37 of 40 seats used');
    expect(within(cards).getByText('STEM A').closest('label')).toHaveTextContent('Adviser: Ms. Reyes');
    const c = within(cards).getByText('STEM C').closest('label');
    expect(c).toHaveTextContent('Full');
    expect(within(c).getByRole('radio')).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Choose a section' })).toBeDisabled();
  });

  it('places a student with the enrollment version, moves them out of Needs a section and selects the next one', async () => {
    show('/enrollment/enrolled?applicationId=10');
    const cards = await screen.findByRole('group', { name: /Sections for this campus/ });
    fireEvent.click(within(cards).getByRole('radio', { name: /STEM A/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Place in STEM A' }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith('/api/enrollments/110/section', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ sectionId: 30, version: 3 }) })));
    expect(await screen.findByText('Ana Santos placed in STEM A.')).toBeInTheDocument();
    const panel = screen.getByRole('tabpanel');
    await waitFor(() => expect(within(panel).queryByText('Ana Santos')).not.toBeInTheDocument());
    await waitFor(() => expect(screen.getByRole('tab', { name: /Placed/ })).toHaveTextContent('2'));
    expect(await screen.findByRole('heading', { level: 2, name: 'Ben Cruz' })).toBeInTheDocument();
  });

  it('shows an over-capacity refusal inline and keeps the student selected', async () => {
    const original = apiRequest.getMockImplementation();
    apiRequest.mockImplementation((path, options) => options?.method === 'PUT' ? Promise.reject(new Error('Section STEM B is full.')) : original(path, options));
    show('/enrollment/enrolled?applicationId=10');
    const cards = await screen.findByRole('group', { name: /Sections for this campus/ });
    fireEvent.click(within(cards).getByRole('radio', { name: /STEM B/ }));
    const sectionLoads = apiRequest.mock.calls.filter(([path]) => path.startsWith('/api/sections?')).length;
    fireEvent.click(screen.getByRole('button', { name: 'Place in STEM B' }));
    sections = sections.map(item => item.id === 31 ? { ...item, occupied: 40, available: 0 } : item);
    expect(await screen.findByRole('alert')).toHaveTextContent('Section STEM B is full.');
    await waitFor(() => expect(apiRequest.mock.calls.filter(([path]) => path.startsWith('/api/sections?')).length).toBeGreaterThan(sectionLoads));
    // The refreshed figures show STEM B is now full, so it is no longer the chosen section.
    await waitFor(() => expect(screen.getByRole('button', { name: 'Choose a section' })).toBeDisabled());
    expect(within(screen.getByText('STEM B').closest('label')).getByRole('radio')).toBeDisabled();
    expect(screen.getByRole('heading', { level: 2, name: 'Ana Santos' })).toBeInTheDocument();
  });

  it('skips to the next student without placing', async () => {
    show('/enrollment/enrolled?applicationId=10');
    await screen.findByRole('heading', { level: 2, name: 'Ana Santos' });
    fireEvent.click(screen.getByRole('button', { name: 'Skip' }));
    expect(await screen.findByRole('heading', { level: 2, name: 'Ben Cruz' })).toBeInTheDocument();
    expect(apiRequest.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(false);
  });

  it('marks the current section and can remove a placement after confirming', async () => {
    show('/enrollment/enrolled?applicationId=12');
    const cards = await screen.findByRole('group', { name: /Sections for this campus/ });
    expect(within(cards).getByText('STEM A').closest('label')).toHaveTextContent('Current section');
    fireEvent.click(screen.getByRole('button', { name: 'Remove from section' }));
    const dialog = screen.getByRole('dialog', { name: 'Remove Cara Lim from STEM A?' });
    fireEvent.click(within(dialog).getByRole('button', { name: 'Remove from section' }));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith('/api/enrollments/112/section', expect.objectContaining({ body: JSON.stringify({ sectionId: null, version: 3 }) })));
    expect(await screen.findByText('Cara Lim moved back to Needs a section.')).toBeInTheDocument();
  });

  it('does not offer placement before enrollment is confirmed', async () => {
    enrollments[10] = null;
    show('/enrollment/enrolled?applicationId=10');
    expect(await screen.findByText('Confirm enrollment before placing this student in a section.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Place in/ })).not.toBeInTheDocument();
  });
});
