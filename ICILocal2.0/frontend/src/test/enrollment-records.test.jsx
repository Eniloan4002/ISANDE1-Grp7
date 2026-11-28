import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import EnrollmentRecords from '../enrollment/EnrollmentRecords.jsx';
import { apiRequest } from '../api.js';

vi.mock('../api.js', () => ({ apiRequest: vi.fn() }));

const reference = {
  campuses: [{ id: 1, name: 'Iligan Main' }, { id: 2, name: 'Bugo' }],
  terms: [{ id: 5, name: 'First Semester', academicYear: '2026-2027' }],
  programs: [{ id: 3, code: 'STEM', name: 'STEM' }],
};
const rows = [
  { id: 10, applicationNumber: 'APP-10', name: 'Ana Santos', studentId: 77, studentNumber: 'STU-0010', lrn: '123456789012', category: 'new', program: 'STEM', campus: 'Iligan Main', term: '2026-2027 / First Semester', section: 'STEM 11-A', status: 'enrolled', requirementsStatus: 'Complete', phone: '0917', email: 'ana@example.com' },
  { id: 11, applicationNumber: 'APP-11', name: 'Ben Cruz', studentId: null, studentNumber: null, program: 'STEM', campus: 'Iligan Main', section: null, status: 'enrolled', requirementsStatus: 'Incomplete' },
];
const detail = { id: 10, voucher: 'QVR', profile: { guardians: [{ fullName: 'Maria Santos', relationship: 'mother', phone: '0999' }] } };

const paths = () => apiRequest.mock.calls.map(([path]) => path);

function show() {
  render(<MemoryRouter initialEntries={['/enrollment/academic-records']}><EnrollmentRecords /></MemoryRouter>);
}

beforeEach(() => {
  apiRequest.mockImplementation(async path => {
    if (path === '/api/enrollment/reference-data') return reference;
    if (path.startsWith('/api/sections?')) return { items: [{ id: 40, name: 'STEM 11-A' }, { id: 41, name: 'STEM 11-B' }], total: 2 };
    if (path.startsWith('/api/enrollment/applications?')) return { items: rows, total: 2 };
    if (path === '/api/intake/applications/10') return detail;
    throw new Error(`Unexpected ${path}`);
  });
});
afterEach(() => { cleanup(); vi.clearAllMocks(); });

describe('Enrollment Records', () => {
  it('lists only enrolled students with their section and requirements status', async () => {
    show();
    expect(await screen.findByText('Ana Santos')).toBeInTheDocument();
    expect(screen.getByText('STU-0010')).toBeInTheDocument();
    expect(screen.getByText('Not placed')).toBeInTheDocument();
    expect(screen.getByText('Complete')).toBeInTheDocument();
    expect(screen.getByText('Incomplete')).toBeInTheDocument();
    expect(paths().some(path => path.startsWith('/api/enrollment/applications?') && path.includes('status=enrolled'))).toBe(true);
  });

  it('filters by campus, term, program and section on the server', async () => {
    show();
    await screen.findByText('Ana Santos');
    fireEvent.change(screen.getByLabelText('Campus'), { target: { value: '1' } });
    fireEvent.change(screen.getByLabelText('Term'), { target: { value: '5' } });
    fireEvent.change(screen.getByLabelText('Program'), { target: { value: '3' } });
    await screen.findByRole('option', { name: 'STEM 11-A' });
    fireEvent.change(screen.getByLabelText('Section'), { target: { value: '40' } });
    await waitFor(() => expect(paths().some(path => path.startsWith('/api/enrollment/applications?')
      && ['campusId=1', 'termId=5', 'programId=3', 'sectionId=40'].every(part => path.includes(part)))).toBe(true));
  });

  it('clears the chosen section when a parent filter changes', async () => {
    show();
    await screen.findByRole('option', { name: 'STEM 11-A' });
    fireEvent.change(screen.getByLabelText('Section'), { target: { value: '40' } });
    expect(screen.getByLabelText('Section')).toHaveValue('40');
    fireEvent.change(screen.getByLabelText('Campus'), { target: { value: '2' } });
    expect(screen.getByLabelText('Section')).toHaveValue('');
  });

  it('opens a record with voucher, guardians and a link to full academic history', async () => {
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'View record for Ana Santos' }));
    expect(await screen.findByText('QVR')).toBeInTheDocument();
    expect(screen.getByText('Maria Santos')).toBeInTheDocument();
    expect(screen.getByText('ana@example.com')).toBeInTheDocument();
    const link = screen.getByRole('link', { name: 'Open full academic history' });
    expect(link).toHaveAttribute('href', '/academic/registrar-records?studentId=77');
  });

  it('says so when a record has no linked student, instead of offering a dead link', async () => {
    apiRequest.mockImplementation(async path => {
      if (path === '/api/enrollment/reference-data') return reference;
      if (path.startsWith('/api/sections?')) return { items: [], total: 0 };
      if (path.startsWith('/api/enrollment/applications?')) return { items: [rows[1]], total: 1 };
      if (path === '/api/intake/applications/11') return { id: 11, voucher: null, profile: {} };
      throw new Error(`Unexpected ${path}`);
    });
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'View record for Ben Cruz' }));
    expect(await screen.findByText(/No student record is linked yet/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Open full academic history' })).not.toBeInTheDocument();
  });

  it('shows an empty state', async () => {
    apiRequest.mockImplementation(async path => {
      if (path === '/api/enrollment/reference-data') return reference;
      if (path.startsWith('/api/sections?')) return { items: [], total: 0 };
      return { items: [], total: 0 };
    });
    show();
    expect(await screen.findByText('No enrolled students match')).toBeInTheDocument();
  });

  it('shows the server message when the list cannot be loaded', async () => {
    apiRequest.mockImplementation(async path => {
      if (path === '/api/enrollment/reference-data') return reference;
      if (path.startsWith('/api/sections?')) return { items: [], total: 0 };
      throw new Error('Service unavailable; retry.');
    });
    show();
    expect(await screen.findByRole('alert')).toHaveTextContent('Service unavailable; retry.');
  });
});
