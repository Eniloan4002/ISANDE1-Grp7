import React, { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import RequirementsScreen from './RequirementsScreen.jsx';
import './enrollment.css';

const initialApplicants = [
  {
    id: 'APP-2026-0148', firstName: 'Ana', middleName: 'Reyes', lastName: 'Garcia', lrn: '119270140008',
    submittedAt: '2026-08-02', category: 'New', campus: 'Iligan Main', program: 'Senior High School',
    track: 'Academic', strand: 'HUMSS', voucher: 'QVR', status: 'Pre-enlisted', requirements: 'Pending',
    duplicate: false, birthDate: '2009-04-12', sex: 'Female', phone: '09170000001', email: 'ana.garcia@example.invalid',
    previousSchool: 'Iligan City National High School', guardianName: 'Maria Garcia', guardianRelationship: 'Mother',
    guardianPhone: '09170000002', householdIncome: 'Not provided',
  },
  {
    id: 'APP-2026-0149', firstName: 'Miguel', middleName: 'Santos', lastName: 'Reyes', lrn: '108517230184',
    submittedAt: '2026-08-02', category: 'Transferee', campus: 'Iligan Main', program: 'Senior High School',
    track: 'Academic', strand: 'STEM', voucher: 'ESC', status: 'For review', requirements: 'Incomplete',
    duplicate: true, birthDate: '2008-11-03', sex: 'Male', phone: '09170000003', email: 'miguel.reyes@example.invalid',
    previousSchool: 'Northern Mindanao Academy', guardianName: 'Jose Reyes', guardianRelationship: 'Father',
    guardianPhone: '09170000004', householdIncome: 'Not provided',
  },
  {
    id: 'APP-2026-0150', firstName: 'Lia', middleName: '', lastName: 'Navarro', lrn: '120381650117',
    submittedAt: '2026-08-02', category: 'New', campus: 'Bugo — CDO', program: 'Senior High School',
    track: 'TVL', strand: 'Cookery', voucher: 'REGULAR', status: 'Pending requirements', requirements: 'Incomplete',
    duplicate: false, birthDate: '2009-07-26', sex: 'Female', phone: '09170000005', email: '',
    previousSchool: 'Bugo Central School', guardianName: 'Elena Navarro', guardianRelationship: 'Mother',
    guardianPhone: '09170000006', householdIncome: 'Not provided',
  },
  {
    id: 'APP-2026-0151', firstName: 'Noah', middleName: 'Dela', lastName: 'Cruz', lrn: '112500710292',
    submittedAt: '2026-08-05', category: 'Returnee', campus: 'Kapatagan', program: 'Senior High School',
    track: 'Academic', strand: 'ABM', voucher: 'ALS', status: 'Pending requirements', requirements: 'Complete',
    duplicate: false, birthDate: '2008-02-14', sex: 'Male', phone: '09170000007', email: 'noah.cruz@example.invalid',
    previousSchool: 'Kapatagan National High School', guardianName: 'Rosa Cruz', guardianRelationship: 'Mother',
    guardianPhone: '09170000008', householdIncome: 'Not provided',
  },
  {
    id: 'APP-2026-0152', firstName: 'Mika', middleName: 'Lopez', lastName: 'Tan', lrn: '119770320691',
    submittedAt: '2026-08-06', category: 'New', campus: 'Iligan Main', program: 'Senior High School',
    track: 'TVL', strand: 'ICT', voucher: 'QVR', status: 'Enrolled', requirements: 'Complete',
    duplicate: false, birthDate: '2009-01-19', sex: 'Female', phone: '09170000009', email: 'mika.tan@example.invalid',
    previousSchool: 'Iligan City National High School', guardianName: 'Paolo Tan', guardianRelationship: 'Father',
    guardianPhone: '09170000010', householdIncome: 'Not provided',
  },
];

const mockReferenceData = {
  campuses: [
    { id: 'demo-campus-iligan-main', name: 'Iligan Main' },
    { id: 'demo-campus-kapatagan', name: 'Kapatagan' },
    { id: 'demo-campus-bugo-cdo', name: 'Bugo — CDO' },
  ],
  programs: [
    { id: 'demo-program-shs', name: 'Senior High School' },
    { id: 'demo-program-tvet', name: 'TVET' },
  ],
  tracks: [
    { code: 'ACADEMIC', name: 'Academic', programId: 'demo-program-shs' },
    { code: 'TVL', name: 'TVL', programId: 'demo-program-shs' },
    { code: 'TVET', name: 'TVET', programId: 'demo-program-tvet' },
  ],
  specializations: [
    { code: 'STEM', name: 'STEM', trackCode: 'ACADEMIC' },
    { code: 'ABM', name: 'ABM', trackCode: 'ACADEMIC' },
    { code: 'HUMSS', name: 'HUMSS', trackCode: 'ACADEMIC' },
    { code: 'COOKERY', name: 'Cookery', trackCode: 'TVL' },
    { code: 'ICT', name: 'ICT', trackCode: 'TVL' },
    { code: 'TVET', name: 'TVET', trackCode: 'TVET' },
  ],
  vouchers: [
    { code: 'QVR', name: 'Qualified Voucher Recipient (QVR)' },
    { code: 'ESC', name: 'Education Service Contracting (ESC)' },
    { code: 'ALS', name: 'Alternative Learning System (ALS)' },
    { code: 'REGULAR', name: 'Regular / Paying' },
  ],
  terms: [{ id: 'demo-term', name: 'Demo term (preview only)' }],
};

const applicantCategories = [
  { code: 'new', name: 'New' },
  { code: 'transferee', name: 'Transferee' },
  { code: 'returnee', name: 'Returnee' },
  { code: 'continuing', name: 'Continuing' },
];

const EnrollmentMockContext = createContext(null);

export function EnrollmentMockProvider({ children }) {
  const [applicants, setApplicants] = useState(() => initialApplicants.map(applicant => ({
    ...applicant,
    campusId: mockReferenceData.campuses.find(item => item.name === applicant.campus)?.id,
    programId: mockReferenceData.programs.find(item => item.name === applicant.program)?.id,
    termId: 'demo-term',
    trackCode: applicant.track === 'Academic' ? 'ACADEMIC' : applicant.track === 'TVL' ? 'TVL' : 'TVET',
    specializationCode: mockReferenceData.specializations.find(item => item.name === applicant.strand)?.code,
    guardians: applicant.guardianName ? [{
      fullName: applicant.guardianName,
      relationship: applicant.guardianRelationship,
      phone: applicant.guardianPhone,
      primaryContact: true,
    }] : [],
  })));
  const [nextId, setNextId] = useState(153);
  const addApplicant = async values => {
    await Promise.resolve();
    const applicant = {
      ...values,
      id: `APP-${new Date().getFullYear()}-${String(nextId).padStart(4, '0')}`,
      submittedAt: new Date().toISOString().slice(0, 10),
      status: 'Pre-enlisted',
      requirements: 'Pending',
      duplicate: false,
    };
    setNextId(value => value + 1);
    setApplicants(current => [applicant, ...current]);
    return applicant;
  };
  const value = useMemo(() => ({ applicants, addApplicant, referenceData: mockReferenceData }), [applicants, nextId]);
  return <EnrollmentMockContext.Provider value={value}>{children}</EnrollmentMockContext.Provider>;
}

function useEnrollmentMock() {
  const context = useContext(EnrollmentMockContext);
  if (!context) throw new Error('Enrollment screens must be rendered inside EnrollmentMockProvider.');
  return context;
}

const statuses = ['Pre-enlisted', 'Pending requirements', 'For review', 'Enrolled'];

function statusTone(status) {
  if (status === 'Enrolled') return 'success';
  if (status === 'For review') return 'danger';
  if (status === 'Pending requirements') return 'warning';
  return 'neutral';
}

function prettyDate(date) {
  if (!date) return '—';
  return new Date(`${date}T00:00:00`).toLocaleDateString('en-PH', { year: 'numeric', month: 'short', day: 'numeric' });
}

function fullName(applicant) {
  return [applicant.firstName, applicant.middleName, applicant.lastName].filter(Boolean).join(' ');
}

function applicantUrl(basePath, id) {
  return `${basePath}/applicants/${encodeURIComponent(id)}`;
}

function EnrollmentQueue({ applicants, basePath, preview }) {
  const { referenceData } = useEnrollmentMock();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('all');
  const [program, setProgram] = useState('all');
  const [campus, setCampus] = useState('all');
  const intakePath = preview ? `${basePath}/intake` : '/enrollment/intake';
  const filtered = applicants.filter(applicant => {
    const query = search.trim().toLowerCase();
    const matchesSearch = !query || [fullName(applicant), applicant.id, applicant.lrn].some(value => value?.toLowerCase().includes(query));
    return matchesSearch &&
      (status === 'all' || applicant.status === status) &&
      (program === 'all' || applicant.track === program) &&
      (campus === 'all' || applicant.campus === campus);
  });
  const stats = [
    { label: 'Pre-enlisted', value: applicants.filter(applicant => applicant.status === 'Pre-enlisted').length, description: 'Awaiting evaluation' },
    { label: 'Pending requirements', value: applicants.filter(applicant => applicant.status === 'Pending requirements').length, description: 'Needs follow-up' },
    { label: 'Duplicate flags', value: applicants.filter(applicant => applicant.duplicate).length, description: 'For review' },
    { label: 'Enrolled', value: applicants.filter(applicant => applicant.status === 'Enrolled').length, description: 'This academic year' },
  ];

  return <div className="enrollment-workspace">
    <header className="page-heading enrollment-heading">
      <div><p className="eyebrow">ENROLLMENT MANAGEMENT</p><h1>Enrollment Queue</h1><p>Review and manage incoming applicant submissions.</p></div>
      <Link className="button" to={intakePath}>New applicant</Link>
    </header>
    <div className="enrollment-stats" aria-label="Application statistics">
      {stats.map(item => <section className="enrollment-stat" key={item.label}>
        <strong>{item.value}</strong><div><span>{item.label}</span><small>{item.description}</small></div>
      </section>)}
    </div>
    <section className="panel enrollment-panel" aria-labelledby="applicant-queue-title">
      <div className="enrollment-panel-heading"><div><h2 id="applicant-queue-title">Applicant Queue</h2><p>{filtered.length} of {applicants.length} applications</p></div></div>
      <div className="enrollment-filters">
        <label>Search applicants<input type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Name, application ID, or LRN" /></label>
        <label>Status<select value={status} onChange={event => setStatus(event.target.value)}><option value="all">All statuses</option>{statuses.map(value => <option key={value}>{value}</option>)}</select></label>
        <label>Track<select value={program} onChange={event => setProgram(event.target.value)}><option value="all">All tracks</option>{referenceData.tracks.map(item => <option key={item.code} value={item.name}>{item.name}</option>)}</select></label>
        <label>Campus<select value={campus} onChange={event => setCampus(event.target.value)}><option value="all">All campuses</option>{referenceData.campuses.map(item => <option key={item.id} value={item.name}>{item.name}</option>)}</select></label>
      </div>
      {filtered.length ? <div className="enrollment-table-wrap"><table className="enrollment-table">
        <thead><tr><th scope="col">Applicant</th><th scope="col">Application ID</th><th scope="col">Submitted</th><th scope="col">Program</th><th scope="col">Campus</th><th scope="col">Requirements</th><th scope="col">Status</th><th scope="col">Action</th></tr></thead>
        <tbody>{filtered.map(applicant => <tr key={applicant.id}>
          <td><strong>{applicant.lastName}, {applicant.firstName}</strong><small>{applicant.lrn ? `LRN ${applicant.lrn}` : 'LRN not provided'}</small></td>
          <td>{applicant.id}</td><td>{prettyDate(applicant.submittedAt)}</td><td>{applicant.track} · {applicant.strand}</td>
          <td>{applicant.campus}</td><td><span className={`status ${applicant.requirements === 'Complete' ? 'status-success' : 'status-warning'}`}>{applicant.requirements}</span></td>
          <td><span className={`status status-${statusTone(applicant.status)}`}>{applicant.status}</span>{applicant.duplicate && <small className="enrollment-flag">Possible duplicate</small>}</td>
          <td><Link className="button button-outline enrollment-view" to={applicantUrl(basePath, applicant.id)}>View</Link></td>
        </tr>)}</tbody>
      </table></div> : <div className="empty-state enrollment-empty"><h3>No matching applicants</h3><p>Try changing or clearing the search and filters.</p><button className="button button-outline" type="button" onClick={() => { setSearch(''); setStatus('all'); setProgram('all'); setCampus('all'); }}>Clear filters</button></div>}
    </section>
  </div>;
}

function emptyGuardian() {
  return { fullName: '', relationship: '', phone: '', primaryContact: false };
}

function createEmptyForm() {
  return {
    firstName: '', middleName: '', lastName: '', lrn: '', birthDate: '', sex: '',
    phone: '', email: '', campusId: '', category: '', programId: '', termId: '',
    trackCode: '', specializationCode: '', voucher: '', previousSchool: '',
    householdIncome: '', guardians: [emptyGuardian()],
  };
}

function ApplicantIntake({ addApplicant, navigate, basePath, title, referenceData }) {
  const [form, setForm] = useState(createEmptyForm);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const tracks = referenceData.tracks.filter(item => item.programId === form.programId);
  const specializations = referenceData.specializations.filter(item => item.trackCode === form.trackCode);
  const today = new Date().toISOString().slice(0, 10);

  function update(event) {
    const { name, value } = event.target;
    setForm(current => ({
      ...current,
      [name]: value,
      ...(name === 'programId' ? { trackCode: '', specializationCode: '' } : {}),
      ...(name === 'trackCode' ? { specializationCode: '' } : {}),
    }));
    setSubmitError('');
  }

  function updateGuardian(index, field, value) {
    setForm(current => ({
      ...current,
      guardians: current.guardians.map((guardian, currentIndex) =>
        currentIndex === index ? { ...guardian, [field]: value } : guardian),
    }));
    setSubmitError('');
  }

  function addGuardian() {
    setForm(current => ({ ...current, guardians: [...current.guardians, emptyGuardian()] }));
    setSubmitError('');
  }

  function removeGuardian(index) {
    setForm(current => ({ ...current, guardians: current.guardians.filter((_, currentIndex) => currentIndex !== index) }));
    setSubmitError('');
  }

  function setPrimaryGuardian(index) {
    setForm(current => ({
      ...current,
      guardians: current.guardians.map((guardian, currentIndex) => ({ ...guardian, primaryContact: currentIndex === index })),
    }));
    setSubmitError('');
  }

  async function submit(event) {
    event.preventDefault();
    const guardians = form.guardians.filter(guardian => guardian.fullName.trim() || guardian.relationship || guardian.phone.trim());
    const incompleteGuardian = guardians.some(guardian => !guardian.fullName.trim() || !guardian.relationship);
    if (incompleteGuardian) {
      setSubmitError('Complete the name and relationship for each parent or guardian, or remove the empty contact.');
      return;
    }

    setSubmitting(true);
    setSubmitError('');
    try {
      const campus = referenceData.campuses.find(item => item.id === form.campusId);
      const program = referenceData.programs.find(item => item.id === form.programId);
      const term = referenceData.terms.find(item => item.id === form.termId);
      const track = referenceData.tracks.find(item => item.code === form.trackCode);
      const specialization = referenceData.specializations.find(item => item.code === form.specializationCode);
      const category = applicantCategories.find(item => item.code === form.category);
      const applicant = await addApplicant({
        ...form,
        guardians: guardians.map(guardian => ({ ...guardian, fullName: guardian.fullName.trim(), phone: guardian.phone.trim() })),
        firstName: form.firstName.trim(),
        middleName: form.middleName.trim(),
        lastName: form.lastName.trim(),
        campus: campus?.name,
        program: program?.name,
        term: term?.name,
        track: track?.name,
        strand: specialization?.name,
        category: category?.name,
        guardianName: guardians[0]?.fullName.trim() || '',
        guardianRelationship: guardians[0]?.relationship || '',
        guardianPhone: guardians[0]?.phone.trim() || '',
        householdIncome: form.householdIncome.trim(),
      });
      navigate(applicantUrl(basePath, applicant.id));
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : 'The applicant could not be added. Please try again.');
      setSubmitting(false);
    }
  }

  return <div className="enrollment-workspace">
    <header className="page-heading"><div><p className="eyebrow">ENROLLMENT MANAGEMENT</p><h1>{title}</h1><p>Enter the applicant’s details and enrollment preferences.</p></div></header>
    <form className="enrollment-form" aria-label="Applicant intake form" onSubmit={submit} aria-busy={submitting}>
      {submitError && <p className="notice notice-error enrollment-form-error" role="alert">{submitError}</p>}
      <section className="panel enrollment-panel">
        <div className="enrollment-panel-heading"><div><h2>Applicant information</h2><p>Enter the applicant’s identity and contact information. Required fields are marked with *.</p></div></div>
        <div className="enrollment-form-grid">
          <label htmlFor="applicant-first-name">First name <span aria-hidden="true">*</span><input id="applicant-first-name" name="firstName" required maxLength="150" value={form.firstName} onChange={update} /></label>
          <label htmlFor="applicant-middle-name">Middle name<input id="applicant-middle-name" name="middleName" maxLength="150" value={form.middleName} onChange={update} /></label>
          <label htmlFor="applicant-last-name">Last name <span aria-hidden="true">*</span><input id="applicant-last-name" name="lastName" required maxLength="150" value={form.lastName} onChange={update} /></label>
          <label htmlFor="applicant-lrn">Learner Reference Number (LRN)<input id="applicant-lrn" name="lrn" inputMode="numeric" pattern="[0-9]{12}" maxLength="12" title="Enter exactly 12 digits, or leave blank if not available." placeholder="12 digits, if available" value={form.lrn} onChange={update} /><small>Optional. If provided, enter 12 digits.</small></label>
          <label htmlFor="applicant-birth-date">Date of birth <span aria-hidden="true">*</span><input id="applicant-birth-date" name="birthDate" type="date" required max={today} value={form.birthDate} onChange={update} /></label>
          <label htmlFor="applicant-sex">Sex<select id="applicant-sex" name="sex" value={form.sex} onChange={update}><option value="">Select…</option><option>Female</option><option>Male</option><option>Prefer not to say</option></select></label>
          <label htmlFor="applicant-phone">Contact number<input id="applicant-phone" name="phone" type="tel" maxLength="40" autoComplete="tel" value={form.phone} onChange={update} /></label>
          <label htmlFor="applicant-email">Email<input id="applicant-email" name="email" type="email" maxLength="254" value={form.email} onChange={update} /></label>
          <label htmlFor="applicant-category">Applicant category <span aria-hidden="true">*</span><select id="applicant-category" name="category" required value={form.category} onChange={update}><option value="">Select…</option>{applicantCategories.map(item => <option key={item.code} value={item.code}>{item.name}</option>)}</select></label>
        </div>
      </section>
      <section className="panel enrollment-panel">
        <div className="enrollment-panel-heading"><div><h2>Enrollment preferences</h2><p>Catalog options shown here are mock reference data for preview only.</p></div></div>
        <div className="enrollment-form-grid">
          <label htmlFor="applicant-campus">Preferred campus <span aria-hidden="true">*</span><select id="applicant-campus" name="campusId" required value={form.campusId} onChange={update}><option value="">Select…</option>{referenceData.campuses.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label htmlFor="applicant-term">Academic term <span aria-hidden="true">*</span><select id="applicant-term" name="termId" required value={form.termId} onChange={update}><option value="">Select…</option>{referenceData.terms.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label htmlFor="applicant-program">Program <span aria-hidden="true">*</span><select id="applicant-program" name="programId" required value={form.programId} onChange={update}><option value="">Select…</option>{referenceData.programs.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label htmlFor="applicant-track">Track <span aria-hidden="true">*</span><select id="applicant-track" name="trackCode" required disabled={!tracks.length} value={form.trackCode} onChange={update}><option value="">{tracks.length ? 'Select…' : 'Select a program first'}</option>{tracks.map(item => <option key={item.code} value={item.code}>{item.name}</option>)}</select></label>
          <label htmlFor="applicant-strand">Strand / specialization <span aria-hidden="true">*</span><select id="applicant-strand" name="specializationCode" required disabled={!specializations.length} value={form.specializationCode} onChange={update}><option value="">{specializations.length ? 'Select…' : 'Select a track first'}</option>{specializations.map(item => <option key={item.code} value={item.code}>{item.name}</option>)}</select></label>
          <label htmlFor="applicant-voucher">Voucher classification <span aria-hidden="true">*</span><select id="applicant-voucher" name="voucher" required value={form.voucher} onChange={update}><option value="">Select…</option>{referenceData.vouchers.map(item => <option key={item.code} value={item.code}>{item.name}</option>)}</select></label>
        </div>
      </section>
      <section className="panel enrollment-panel">
        <div className="enrollment-panel-heading"><div><h2>Previous school and household information</h2><p>Use the institution’s defined income description when available; no bracket values are assumed.</p></div></div>
        <div className="enrollment-form-grid">
          <label htmlFor="applicant-previous-school">Junior high / previous school<input id="applicant-previous-school" name="previousSchool" maxLength="250" value={form.previousSchool} onChange={update} /></label>
          <label htmlFor="applicant-household-income">Household income bracket<input id="applicant-household-income" name="householdIncome" maxLength="100" placeholder="School-defined description (optional)" value={form.householdIncome} onChange={update} /></label>
        </div>
      </section>
      <section className="panel enrollment-panel" aria-labelledby="applicant-guardians-heading">
        <div className="enrollment-panel-heading"><div><h2 id="applicant-guardians-heading">Parent / guardian information</h2><p>Add one or more contacts. A name and relationship are needed for any contact you enter; a primary contact is optional.</p></div></div>
        <div className="enrollment-guardians">
          {form.guardians.map((guardian, index) => <fieldset className="enrollment-guardian" key={index}>
            <legend>Contact {index + 1}{guardian.primaryContact ? ' · Primary' : ''}</legend>
            <div className="enrollment-form-grid">
              <label htmlFor={`applicant-guardian-name-${index}`}>Full name<input id={`applicant-guardian-name-${index}`} maxLength="250" autoComplete="name" value={guardian.fullName} onChange={event => updateGuardian(index, 'fullName', event.target.value)} /></label>
              <label htmlFor={`applicant-guardian-relationship-${index}`}>Relationship<select id={`applicant-guardian-relationship-${index}`} value={guardian.relationship} onChange={event => updateGuardian(index, 'relationship', event.target.value)}><option value="">Select…</option><option value="mother">Mother</option><option value="father">Father</option><option value="guardian">Guardian</option></select></label>
              <label htmlFor={`applicant-guardian-phone-${index}`}>Contact number<input id={`applicant-guardian-phone-${index}`} type="tel" maxLength="40" autoComplete="tel" value={guardian.phone} onChange={event => updateGuardian(index, 'phone', event.target.value)} /></label>
            </div>
            <div className="enrollment-guardian-actions">
              <button className="button button-outline" type="button" onClick={() => setPrimaryGuardian(index)} disabled={guardian.primaryContact}>{guardian.primaryContact ? 'Primary contact' : `Set contact ${index + 1} as primary`}</button>
              <button className="button button-outline enrollment-remove-guardian" type="button" onClick={() => removeGuardian(index)}>Remove contact</button>
            </div>
          </fieldset>)}
          <button className="button button-outline enrollment-add-guardian" type="button" onClick={addGuardian}>Add another parent / guardian</button>
        </div>
      </section>
      <div className="enrollment-form-actions"><Link className="button button-outline" to={previewQueuePath(basePath)}>Cancel</Link><button className="button" type="submit" disabled={submitting}>{submitting ? 'Adding applicant…' : 'Add applicant to queue'}</button></div>
      {submitting && <p className="enrollment-submit-status" role="status">Saving applicant to the mock queue…</p>}
    </form>
  </div>;
}

function previewQueuePath(basePath) {
  return basePath === '/dev/enrollment' ? `${basePath}/queue` : '/enrollment/queue';
}

function ApplicantDetails({ applicant, basePath, preview }) {
  const queuePath = preview ? `${basePath}/queue` : '/enrollment/queue';
  if (!applicant) return <section className="content-card"><h1>Applicant not found</h1><p>This mock application may have been cleared by a page refresh.</p><Link className="button button-outline" to={queuePath}>Back to queue</Link></section>;

  const sections = [
    { title: 'Personal information', fields: [['Full name', fullName(applicant)], ['Application ID', applicant.id], ['LRN', applicant.lrn || 'Not provided'], ['Date of birth', prettyDate(applicant.birthDate)], ['Sex', applicant.sex || 'Not provided'], ['Contact number', applicant.phone || 'Not provided'], ['Email', applicant.email || 'Not provided']] },
    { title: 'Enrollment preferences', fields: [['Campus', applicant.campus], ['Academic term', applicant.term || 'Not provided'], ['Program', applicant.program], ['Track', applicant.track], ['Strand / specialization', applicant.strand], ['Applicant category', applicant.category], ['Voucher classification', applicant.voucher]] },
    { title: 'Previous school and family contact', fields: [
      ['Junior high / previous school', applicant.previousSchool || 'Not provided'],
      ['Parent / guardian contacts', applicant.guardians?.length
        ? applicant.guardians.map(guardian => `${guardian.fullName} (${guardian.relationship ? `${guardian.relationship[0].toUpperCase()}${guardian.relationship.slice(1)}` : 'relationship not provided'}${guardian.phone ? `, ${guardian.phone}` : ''}${guardian.primaryContact ? ', primary contact' : ''})`).join('; ')
        : 'Not provided'],
      ['Household income bracket', applicant.householdIncome || 'Not provided'],
    ] },
  ];
  return <div className="enrollment-workspace">
    <header className="page-heading enrollment-heading">
      <div><p className="eyebrow">ENROLLMENT MANAGEMENT / APPLICANT DETAILS</p><h1>{fullName(applicant)}</h1><p>{applicant.id} · Submitted {prettyDate(applicant.submittedAt)}</p></div>
      <Link className="button button-outline" to={queuePath}>Back to queue</Link>
    </header>
    <section className="enrollment-detail-status" aria-label="Application status">
      <span className={`status status-${statusTone(applicant.status)}`}>{applicant.status}</span>
      <span className={`status ${applicant.requirements === 'Complete' ? 'status-success' : 'status-warning'}`}>Requirements {applicant.requirements.toLowerCase()}</span>
      {applicant.duplicate && <span className="status status-danger">Possible duplicate — review needed</span>}
    </section>
    <div className="enrollment-detail-grid">{sections.map(section => <section className="panel enrollment-panel" key={section.title}>
      <div className="enrollment-panel-heading"><h2>{section.title}</h2></div>
      <dl className="enrollment-detail-list">{section.fields.map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value || 'Not provided'}</dd></div>)}</dl>
    </section>)}</div>
    <p className="enrollment-mock-note">Mock preview only. Application changes are held in memory and are cleared when the page reloads.</p>
  </div>;
}

export function EnrollmentWorkspace({ preview = false, screen: requestedScreen, title: requestedTitle }) {
  const { applicants, addApplicant, referenceData } = useEnrollmentMock();
  const location = useLocation();
  const navigate = useNavigate();
  const basePath = preview ? '/dev/enrollment' : '/enrollment';
  const relativePath = location.pathname.slice(basePath.length).replace(/^\/+|\/+$/g, '');
  const detailMatch = relativePath.match(/^applicants\/([^/]+)$/);
  const screen = requestedScreen || (preview ? detailMatch ? 'details' : relativePath === 'intake' ? 'intake' : relativePath === 'requirements' ? 'requirements' : 'queue' : 'queue');
  const applicationId = detailMatch ? decodeURIComponent(detailMatch[1]) : location.pathname.match(/\/enrollment\/applicants\/([^/]+)/)?.[1];
  const applicant = applicants.find(item => item.id === applicationId);
  const title = requestedTitle || (screen === 'intake' ? 'Applicant Intake Form' : 'Enrollment Submission');

  useEffect(() => {
    if (preview && !relativePath) navigate(`${basePath}/queue`, { replace: true });
  }, [basePath, navigate, preview, relativePath]);

  const content = screen === 'queue'
    ? <EnrollmentQueue applicants={applicants} basePath={basePath} preview={preview} />
    : screen === 'requirements'
      ? <RequirementsScreen applicants={applicants} basePath={basePath} />
    : screen === 'details'
      ? <ApplicantDetails applicant={applicant} basePath={basePath} preview={preview} />
      : <ApplicantIntake addApplicant={addApplicant} navigate={navigate} basePath={basePath} title={title} referenceData={referenceData} />;

  if (!preview) return content;
  return <main className="enrollment-preview">
    <div className="enrollment-preview-banner"><strong>Development preview</strong><span>Mock applicants only · changes reset on refresh · sign-in bypass is available only in Vite development mode.</span></div>
    <nav className="enrollment-preview-nav" aria-label="Enrollment screen previews">
      <Link to={`${basePath}/queue`}>Enrollment Queue</Link>
      <Link to={`${basePath}/intake`}>Applicant Intake Form</Link>
      <Link to={`${basePath}/requirements`}>Requirements Checklist</Link>
    </nav>
    {content}
  </main>;
}
