import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import App from '../App.jsx';
import { AuthProvider } from '../auth/AuthContext.jsx';
import EnrollmentSubmission from '../enrollment/EnrollmentSubmission.jsx';
import { DRAFT_STORAGE_KEY } from '../enrollment/studentSubmissionApi.js';
import { api } from '../api/client.js';
import { setAccessToken } from '../api.js';

const MOCK_CATALOG = {
  campuses: [
    { id: 1, code: 'ILIGAN', name: 'Iligan Main' },
    { id: 2, code: 'KAPATAGAN', name: 'Kapatagan' },
  ],
  programs: [
    { id: 10, code: 'STEM', name: 'Science, Technology, Engineering and Mathematics', category: 'SHS', track: 'ACADEMIC' },
    { id: 11, code: 'ABM', name: 'Accountancy, Business and Management', category: 'SHS', track: 'ACADEMIC' },
    { id: 12, code: 'TVL_ICT', name: 'TVL — Information and Communications Technology', category: 'SHS', track: 'TVL' },
  ],
  terms: [
    { id: 100, code: 'SEM1', name: 'First Semester', academicYear: '2026-2027' },
  ],
  vouchers: [
    { code: 'REGULAR', name: 'Regular / Paying' },
    { code: 'QVR', name: 'Qualified Voucher Recipient' },
  ],
  requirementTypes: [
    { code: 'PSA', name: 'PSA Birth Certificate', mandatory: true },
    { code: 'FORM137', name: 'Form 137 / Report Card', mandatory: true },
    { code: 'COMPLETION', name: 'Certificate of Completion', mandatory: true },
    { code: 'GOOD_MORAL', name: 'Certificate of Good Moral Character', mandatory: true },
  ],
};

function installCatalogAdapter(onSubmit) {
  api.defaults.adapter = async config => {
    if (config.url === '/public/enrollment/reference-data') {
      return { status: 200, statusText: 'OK', headers: {}, config, data: MOCK_CATALOG };
    }
    if (config.url === '/public/enrollment/submissions' && config.method === 'post') {
      const responseData = onSubmit ? await onSubmit(config) : {
        id: 501,
        applicationNumber: 'APP-2026-8F4C21AA',
        status: 'submitted',
        submittedAt: '2026-11-27',
        applicantName: 'Ana Santos',
        campus: 'Iligan Main',
        term: '2026-2027 / First Semester',
        program: 'Science, Technology, Engineering and Mathematics',
        gradeLevel: 'Grade 11',
        uploadedDocuments: [
          { code: 'PSA', originalFilename: 'psa-birth.pdf', contentType: 'application/pdf', sizeBytes: 1024 },
        ],
      };
      return { status: 201, statusText: 'Created', headers: {}, config, data: responseData };
    }
    throw { config, response: { status: 404, data: { detail: 'Not mocked: ' + config.url } } };
  };
}

beforeEach(() => {
  window.localStorage.clear();
  setAccessToken(null);
});

afterEach(() => {
  cleanup();
  window.localStorage.clear();
  setAccessToken(null);
  vi.restoreAllMocks();
});

describe('Enrollment Submission (ICI2-68)', () => {
  it('links prospective students from Sign In to the public /apply portal without staff auth', async () => {
    installCatalogAdapter();
    render(
      <MemoryRouter initialEntries={['/sign-in']}>
        <AuthProvider>
          <App />
        </AuthProvider>
      </MemoryRouter>
    );

    const applyLink = screen.getByRole('link', { name: 'Submit an enrollment application' });
    expect(applyLink).toHaveAttribute('href', '/apply');
    fireEvent.click(applyLink);

    expect(await screen.findByRole('heading', { name: 'Enrollment Submission' })).toBeInTheDocument();
    expect(screen.getByText('Prospective Student Enrollment Portal')).toBeInTheDocument();
    expect(screen.getByText('Step 1 of 6')).toBeInTheDocument();
  });

  it('validates each step before allowing navigation forward', async () => {
    installCatalogAdapter();
    render(
      <MemoryRouter>
        <EnrollmentSubmission />
      </MemoryRouter>
    );

    await screen.findByRole('option', { name: 'Iligan Main' });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(screen.getByText('Please complete the highlighted fields before continuing.')).toBeInTheDocument();
    expect(screen.getByText('Select your grade level.')).toBeInTheDocument();
    expect(screen.getByText('Select a track.')).toBeInTheDocument();
    expect(screen.getByText('Select your preferred campus.')).toBeInTheDocument();
    expect(screen.getByText('Step 1 of 6')).toBeInTheDocument();

    // Fill Step 1 and advance to Step 2
    fireEvent.change(screen.getByLabelText(/Grade Level/i), { target: { value: 'Grade 11' } });
    fireEvent.change(screen.getByLabelText(/^Track/i), { target: { value: 'ACADEMIC' } });
    fireEvent.change(screen.getByLabelText(/Strand \/ Specialization/i), { target: { value: '10' } });
    fireEvent.change(screen.getByLabelText(/Preferred Campus/i), { target: { value: '1' } });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(await screen.findByText('Step 2 of 6')).toBeInTheDocument();

    // Trigger Step 2 validation with bad LRN and email
    fireEvent.change(screen.getByLabelText(/Learner Reference Number \(LRN\)/i), { target: { value: '12345' } });
    fireEvent.change(screen.getByLabelText(/Email Address/i), { target: { value: 'not-an-email' } });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    expect(screen.getByText('LRN must be exactly 12 numeric digits.')).toBeInTheDocument();
    expect(screen.getByText('Enter a valid email address.')).toBeInTheDocument();
    expect(screen.getByText('Enter your last name.')).toBeInTheDocument();
  });

  it('saves progress to localStorage and restores it on reload', async () => {
    installCatalogAdapter();
    const { unmount } = render(
      <MemoryRouter>
        <EnrollmentSubmission />
      </MemoryRouter>
    );

    await screen.findByRole('option', { name: 'Iligan Main' });
    fireEvent.change(screen.getByLabelText(/Grade Level/i), { target: { value: 'Grade 12' } });
    fireEvent.change(screen.getByLabelText(/^Track/i), { target: { value: 'TVL' } });
    fireEvent.change(screen.getByLabelText(/Strand \/ Specialization/i), { target: { value: '12' } });
    fireEvent.change(screen.getByLabelText(/Preferred Campus/i), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save progress' }));

    expect(screen.getByText(/Progress saved on this device/i)).toBeInTheDocument();
    expect(window.localStorage.getItem(DRAFT_STORAGE_KEY)).toContain('Grade 12');

    unmount();

    render(
      <MemoryRouter>
        <EnrollmentSubmission />
      </MemoryRouter>
    );

    await screen.findByRole('option', { name: 'Kapatagan' });
    expect(screen.getByText(/Saved draft active on this device/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/Grade Level/i)).toHaveValue('Grade 12');
    expect(screen.getByLabelText(/Preferred Campus/i)).toHaveValue('2');

    fireEvent.click(screen.getByRole('button', { name: 'Start over' }));
    expect(screen.getByLabelText(/Grade Level/i)).toHaveValue('');
    expect(window.localStorage.getItem(DRAFT_STORAGE_KEY)).toBeNull();
  });

  it('validates uploaded requirement documents, submits all 6 steps, and displays the reference number', async () => {
    let submittedFormData = null;
    installCatalogAdapter(config => {
      submittedFormData = config.data;
      return {
        id: 501,
        applicationNumber: 'APP-2026-8F4C21AA',
        status: 'submitted',
        submittedAt: '2026-11-27',
        applicantName: 'Ana Santos',
        campus: 'Iligan Main',
        term: '2026-2027 / First Semester',
        program: 'Science, Technology, Engineering and Mathematics',
        gradeLevel: 'Grade 11',
        uploadedDocuments: [
          { code: 'PSA', originalFilename: 'psa-birth.pdf', contentType: 'application/pdf', sizeBytes: 1024 },
        ],
      };
    });

    render(
      <MemoryRouter>
        <EnrollmentSubmission />
      </MemoryRouter>
    );

    // Step 1: Preferences
    await screen.findByRole('option', { name: 'Iligan Main' });
    fireEvent.change(screen.getByLabelText(/Grade Level/i), { target: { value: 'Grade 11' } });
    fireEvent.change(screen.getByLabelText(/^Track/i), { target: { value: 'ACADEMIC' } });
    fireEvent.change(screen.getByLabelText(/Strand \/ Specialization/i), { target: { value: '10' } });
    fireEvent.change(screen.getByLabelText(/Preferred Campus/i), { target: { value: '1' } });
    fireEvent.click(screen.getByLabelText('Online'));
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    // Step 2: Student Information
    await screen.findByText('Step 2 of 6');
    fireEvent.change(screen.getByLabelText(/Learner Reference Number \(LRN\)/i), { target: { value: '123456789012' } });
    fireEvent.change(screen.getByLabelText(/Last Name/i), { target: { value: 'Santos' } });
    fireEvent.change(screen.getByLabelText(/First Name/i), { target: { value: 'Ana' } });
    fireEvent.change(screen.getByLabelText(/Date of Birth/i), { target: { value: '2009-05-14' } });
    fireEvent.change(screen.getByLabelText(/^Sex/i), { target: { value: 'Female' } });
    fireEvent.change(screen.getByLabelText(/Contact Number/i), { target: { value: '09171234567' } });
    fireEvent.change(screen.getByLabelText(/Email Address/i), { target: { value: 'ana.santos@example.com' } });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    // Step 3: Home Address
    await screen.findByText('Step 3 of 6');
    fireEvent.change(screen.getByLabelText(/House Number & Street/i), { target: { value: '14 Mabini St.' } });
    fireEvent.change(screen.getByLabelText(/Province/i), { target: { value: 'Lanao del Norte' } });
    fireEvent.change(screen.getByLabelText(/City \/ Municipality/i), { target: { value: 'Iligan City' } });
    fireEvent.change(screen.getByLabelText(/Barangay/i), { target: { value: 'Poblacion' } });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    // Step 4: Current School
    await screen.findByText('Step 4 of 6');
    fireEvent.change(screen.getByLabelText(/Current \/ Previous School Name/i), { target: { value: 'Iligan City National High School' } });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    // Step 5: Parent / Guardian
    await screen.findByText('Step 5 of 6');
    fireEvent.change(screen.getByLabelText(/Primary Guardian Name/i), { target: { value: 'Elena Santos' } });
    fireEvent.change(screen.getByLabelText(/Guardian Contact Number/i), { target: { value: '09179876543' } });
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));

    // Step 6: Upload Requirements & Review
    await screen.findByText('Step 6 of 6');
    const psaInput = screen.getByLabelText('Upload PSA Birth Certificate');

    // Reject unsupported file format
    const invalidFile = new File(['hello'], 'notes.txt', { type: 'text/plain' });
    fireEvent.change(psaInput, { target: { files: [invalidFile] } });
    expect(screen.getByText(/Unsupported format/i)).toBeInTheDocument();

    // Accept valid PDF
    const validPdf = new File([new Uint8Array(1024)], 'psa-birth.pdf', { type: 'application/pdf' });
    fireEvent.change(psaInput, { target: { files: [validPdf] } });
    expect(screen.getByText(/psa-birth\.pdf/i)).toBeInTheDocument();
    expect(screen.getByText('1 of 4 documents selected')).toBeInTheDocument();

    // Submit application
    fireEvent.click(screen.getByRole('button', { name: 'Submit application' }));

    expect(await screen.findByRole('heading', { name: 'Enrollment Application Submitted' })).toBeInTheDocument();
    expect(screen.getByTestId('reference-number')).toHaveTextContent('APP-2026-8F4C21AA');
    expect(window.localStorage.getItem(DRAFT_STORAGE_KEY)).toBeNull();

    // Verify FormData payload sent to backend
    expect(submittedFormData).toBeInstanceOf(FormData);
    const parsedApplication = JSON.parse(submittedFormData.get('application'));
    expect(parsedApplication.firstName).toBe('Ana');
    expect(parsedApplication.lastName).toBe('Santos');
    expect(parsedApplication.campusId).toBe(1);
    expect(parsedApplication.programId).toBe(10);
    expect(parsedApplication.learningModalities).toEqual(['Online']);
    expect(parsedApplication.guardians[0].fullName).toBe('Elena Santos');
    expect(submittedFormData.getAll('documentCodes')).toEqual(['PSA']);
    expect(submittedFormData.getAll('files')).toHaveLength(1);
  });
});
