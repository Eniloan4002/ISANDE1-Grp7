import { afterEach, describe, expect, it } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import App from '../App.jsx';

afterEach(cleanup);

function show(path) {
  return render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
}

function fillRequiredEnrollmentFields() {
  fireEvent.change(screen.getByLabelText(/First name/), { target: { value: 'Jamie' } });
  fireEvent.change(screen.getByLabelText(/Last name/), { target: { value: 'Rivera' } });
  fireEvent.change(screen.getByLabelText(/Date of birth/), { target: { value: '2009-01-01' } });
  fireEvent.change(screen.getByLabelText(/Applicant category/), { target: { value: 'new' } });
  fireEvent.change(screen.getByLabelText(/Preferred campus/), { target: { value: 'demo-campus-iligan-main' } });
  fireEvent.change(screen.getByLabelText(/Academic term/), { target: { value: 'demo-term' } });
  fireEvent.change(screen.getByLabelText(/^Program/), { target: { value: 'demo-program-shs' } });
  fireEvent.change(screen.getByLabelText(/^Track/), { target: { value: 'ACADEMIC' } });
  fireEvent.change(screen.getByLabelText(/Strand \/ specialization/), { target: { value: 'STEM' } });
  fireEvent.change(screen.getByLabelText(/Voucher classification/), { target: { value: 'QVR' } });
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

  it('creates a mock application and opens its applicant details', async () => {
    show('/dev/enrollment/intake');
    fillRequiredEnrollmentFields();

    fireEvent.submit(screen.getByRole('form', { name: 'Applicant intake form' }));
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Jamie Rivera' })).toBeInTheDocument());
    expect(screen.getAllByText(/APP-\d{4}-0153/)).toHaveLength(2);
    expect(screen.getByText('Pre-enlisted')).toBeInTheDocument();
  });

  it('requires application identity and enrollment references while allowing an absent LRN', () => {
    show('/dev/enrollment/intake');
    const form = screen.getByRole('form', { name: 'Applicant intake form' });
    expect(screen.getByLabelText(/First name/)).toBeRequired();
    expect(screen.getByLabelText(/Last name/)).toBeRequired();
    expect(screen.getByLabelText(/Date of birth/)).toBeRequired();
    expect(screen.getByLabelText(/Academic term/)).toBeRequired();
    expect(screen.getByLabelText(/Preferred campus/)).toBeRequired();
    expect(screen.getByLabelText(/^Program/)).toBeRequired();
    expect(screen.getByLabelText(/Learner Reference Number/)).not.toBeRequired();
    expect(form).not.toBeValid();
  });

  it('supports multiple guardian contacts and blocks partially completed contacts', async () => {
    show('/dev/enrollment/intake');
    fillRequiredEnrollmentFields();
    fireEvent.click(screen.getByRole('button', { name: 'Add another parent / guardian' }));
    fireEvent.change(screen.getByLabelText('Full name', { selector: '#applicant-guardian-name-0' }), { target: { value: 'Maria Rivera' } });
    fireEvent.change(screen.getByLabelText('Relationship', { selector: '#applicant-guardian-relationship-0' }), { target: { value: 'mother' } });
    fireEvent.change(screen.getByLabelText('Full name', { selector: '#applicant-guardian-name-1' }), { target: { value: 'Jose Rivera' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add applicant to queue' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Complete the name and relationship');
    expect(screen.getByRole('heading', { name: 'Applicant Intake Form' })).toBeInTheDocument();
  });

  it('submits multiple guardian contacts and displays them in applicant details', async () => {
    show('/dev/enrollment/intake');
    fillRequiredEnrollmentFields();
    fireEvent.change(screen.getByLabelText('Full name', { selector: '#applicant-guardian-name-0' }), { target: { value: 'Maria Rivera' } });
    fireEvent.change(screen.getByLabelText('Relationship', { selector: '#applicant-guardian-relationship-0' }), { target: { value: 'mother' } });
    fireEvent.change(screen.getByLabelText('Contact number', { selector: '#applicant-guardian-phone-0' }), { target: { value: '09170000001' } });
    fireEvent.click(screen.getByRole('button', { name: 'Add another parent / guardian' }));
    fireEvent.change(screen.getByLabelText('Full name', { selector: '#applicant-guardian-name-1' }), { target: { value: 'Jose Rivera' } });
    fireEvent.change(screen.getByLabelText('Relationship', { selector: '#applicant-guardian-relationship-1' }), { target: { value: 'father' } });
    fireEvent.click(screen.getByRole('button', { name: 'Set contact 2 as primary' }));
    fireEvent.click(screen.getByRole('button', { name: 'Add applicant to queue' }));

    expect(await screen.findByRole('heading', { name: 'Jamie Rivera' })).toBeInTheDocument();
    expect(screen.getByText('Maria Rivera (Mother, 09170000001); Jose Rivera (Father, primary contact)')).toBeInTheDocument();
  });

  it('opens the requirements checklist and filters mock applicants', () => {
    show('/dev/enrollment/requirements');
    expect(screen.getByRole('heading', { name: 'Requirements Checklist' })).toBeInTheDocument();
    expect(screen.getByText('APP-2026-0148')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search requirements' }), { target: { value: 'APP-2026-0150' } });
    expect(screen.getByText('APP-2026-0150')).toBeInTheDocument();
    expect(screen.queryByText('APP-2026-0148')).not.toBeInTheDocument();
  });

  it('updates a document status and recomputes the checklist summary', async () => {
    show('/dev/enrollment/requirements');
    fireEvent.click(screen.getAllByRole('button', { name: 'View' })[0]);
    expect(screen.getByRole('dialog', { name: 'Requirements Checklist' })).toBeInTheDocument();
    fireEvent.change(screen.getByRole('combobox', { name: 'Certificate of Completion status' }), { target: { value: 'submitted' } });
    expect(await screen.findByRole('status')).toHaveTextContent('Certificate of Completion updated to Submitted');
    expect(screen.getByText(/2 of 4 submitted/)).toBeInTheDocument();
  });
});
