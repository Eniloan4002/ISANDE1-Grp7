import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { Placeholder } from '../components/StatusPages.jsx';

vi.mock('../auth/AuthContext.jsx', () => ({ useAuth: () => ({ roles: ['registrar'] }) }));
afterEach(cleanup);

describe('Placeholder (ICI2-105)', () => {
  it('shows the page title, what it will do and the owning ticket', () => {
    render(<MemoryRouter><Placeholder page={{ label: 'Transcript Requests', summary: 'Request and track official transcripts.', ticket: 'ICI2-200' }} /></MemoryRouter>);
    expect(screen.getByRole('heading', { name: 'Transcript Requests' })).toBeInTheDocument();
    expect(screen.getByText('Coming soon')).toBeInTheDocument();
    expect(screen.getByText('Request and track official transcripts.')).toBeInTheDocument();
    expect(screen.getByText('ICI2-200')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to Enrollment Queue' })).toHaveAttribute('href', '/enrollment/queue');
  });

  it('falls back to a plain explanation when the page has no summary or ticket', () => {
    render(<MemoryRouter><Placeholder page={{ label: 'Future Screen' }} /></MemoryRouter>);
    expect(screen.getByText(/planned but has not been built yet/)).toBeInTheDocument();
    expect(screen.queryByText(/Tracked in/)).not.toBeInTheDocument();
  });
});
