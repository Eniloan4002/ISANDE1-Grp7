import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import App from '../App.jsx';

afterEach(cleanup);

function show(path) {
  return render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
}

describe('enrollment development preview', () => {
  it('opens the queue without authenticating and filters mock applicants', () => {
    show('/dev/enrollment/queue');
    expect(screen.getByRole('heading', { name: 'Enrollment Queue' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Staff sign in' })).not.toBeInTheDocument();
    expect(screen.getByText('APP-2026-0148')).toBeInTheDocument();

    fireEvent.change(screen.getByRole('searchbox', { name: 'Search applicants' }), { target: { value: 'garcia' } });
    expect(screen.getByText('APP-2026-0148')).toBeInTheDocument();
    expect(screen.queryByText('APP-2026-0149')).not.toBeInTheDocument();
  });

  it('creates a mock application and opens its applicant details', () => {
    show('/dev/enrollment/intake');
    fireEvent.change(screen.getByLabelText(/First name/), { target: { value: 'Jamie' } });
    fireEvent.change(screen.getByLabelText(/Last name/), { target: { value: 'Rivera' } });
    fireEvent.change(screen.getByLabelText(/Applicant category/), { target: { value: 'New' } });
    fireEvent.change(screen.getByLabelText(/Preferred campus/), { target: { value: 'Iligan Main' } });
    fireEvent.change(screen.getByLabelText(/Track/), { target: { value: 'Academic' } });
    fireEvent.change(screen.getByLabelText(/Strand \/ specialization/), { target: { value: 'STEM' } });
    fireEvent.change(screen.getByLabelText(/Voucher classification/), { target: { value: 'QVR' } });

    fireEvent.submit(screen.getByRole('form', { name: 'Applicant intake form' }));
    expect(screen.getByRole('heading', { name: 'Jamie Rivera' })).toBeInTheDocument();
    expect(screen.getAllByText(/APP-\d{4}-0153/)).toHaveLength(2);
    expect(screen.getByText('Pre-enlisted')).toBeInTheDocument();
  });
});
