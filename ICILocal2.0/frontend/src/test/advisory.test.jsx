import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { AuthProvider } from '../auth/AuthContext.jsx';
import App from '../App.jsx';
import AdvisoryClass from '../academic/AdvisoryClass.jsx';
import { api } from '../api/client.js';
import { setAccessToken } from '../api.js';

const roster = {
  source: 'development-fixture',
  academicYear: '2026-2027',
  term: 'First Semester',
  adviser: { name: 'Development Adviser' },
  section: { name: 'STEM 11-A', gradeLevel: 'Grade 11' },
  students: [
    { studentNumber: 'STU-1', name: 'Ana Garcia', contactNumber: '0917', enrollmentStatus: 'Enrolled', requirementsStatus: 'Complete', academicStanding: 'Good standing', academicFlag: null },
    { studentNumber: 'STU-2', name: 'Miguel Santos', contactNumber: '0918', enrollmentStatus: 'Pre-enlisted', requirementsStatus: 'Incomplete', academicStanding: 'At risk', academicFlag: 'Missing Form 137' },
  ],
};

vi.mock('../academic/advisoryRoster.js', () => ({
  advisoryDataSource: () => 'development-fixture',
  loadAdvisoryRoster: vi.fn(() => Promise.resolve(roster)),
}));

function token() {
  const payload = btoa(JSON.stringify({ roles: ['ROLE_ADVISER'], exp: Math.floor(Date.now() / 1000) + 3600 }))
    .replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `header.${payload}.signature`;
}

afterEach(() => {
  cleanup();
  setAccessToken(null);
  vi.clearAllMocks();
});

describe('Advisory Class dashboard', () => {
  it('renders the dashboard through the adviser route', async () => {
    setAccessToken(token());
    api.defaults.adapter = async config => ({
      status: 200, statusText: 'OK', headers: {}, config,
      data: { userId: 7, username: 'adviser', fullName: 'Teacher Adviser', grants: [{ role: 'ROLE_ADVISER', campusId: 1 }] },
    });
    render(<MemoryRouter initialEntries={['/academic/advisory']}><AuthProvider><App /></AuthProvider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Advisory Class' })).toBeInTheDocument();
    expect((await screen.findAllByText('STEM 11-A')).length).toBeGreaterThan(0);
    expect(screen.getByText('Development fixture mode: this roster is temporary and is not connected to the database.')).toBeInTheDocument();
  });

  it('shows roster information and filters by name or student number', async () => {
    render(<AdvisoryClass />);
    expect(await screen.findByText('Ana Garcia')).toBeInTheDocument();
    expect(screen.getByText('Miguel Santos')).toBeInTheDocument();
    expect(screen.getByText('0917')).toBeInTheDocument();
    expect(screen.getByText('Complete')).toBeInTheDocument();
    expect(screen.getByText('Good standing')).toBeInTheDocument();
    expect(screen.getByText('Missing Form 137')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search students' }), { target: { value: 'STU-2' } });
    expect(screen.queryByText('Ana Garcia')).not.toBeInTheDocument();
    expect(screen.getByText('Miguel Santos')).toBeInTheDocument();
  });

  it('does not render the removed summary cards', async () => {
    render(<AdvisoryClass />);
    await screen.findByText('Ana Garcia');
    expect(screen.queryByText('Enrollment follow-up')).not.toBeInTheDocument();
    expect(screen.queryByText('Academic concerns')).not.toBeInTheDocument();
    expect(screen.getByText('(2)')).toBeInTheDocument();
  });

  it('is read-only and has no edit controls', async () => {
    render(<AdvisoryClass />);
    await screen.findByText('Ana Garcia');
    expect(screen.queryByRole('button', { name: /edit|save|update|change/i })).not.toBeInTheDocument();
    expect(screen.getAllByText(/read-only/i).length).toBeGreaterThan(0);
  });

  it('renders an empty search result', async () => {
    render(<AdvisoryClass />);
    await screen.findByText('Ana Garcia');
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search students' }), { target: { value: 'not-found' } });
    expect(screen.getByRole('heading', { name: 'No matching students' })).toBeInTheDocument();
  });

  it('renders API errors instead of falling back to data', async () => {
    const { loadAdvisoryRoster } = await import('../academic/advisoryRoster.js');
    loadAdvisoryRoster.mockRejectedValueOnce(new Error('Roster service unavailable.'));
    render(<AdvisoryClass />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Roster service unavailable.');
    expect(screen.queryByText('Ana Garcia')).not.toBeInTheDocument();
  });

  it('renders an empty assigned roster', async () => {
    const { loadAdvisoryRoster } = await import('../academic/advisoryRoster.js');
    loadAdvisoryRoster.mockResolvedValueOnce({ ...roster, students: [] });
    render(<AdvisoryClass />);
    expect(await screen.findByRole('heading', { name: 'No assigned students' })).toBeInTheDocument();
  });
});
