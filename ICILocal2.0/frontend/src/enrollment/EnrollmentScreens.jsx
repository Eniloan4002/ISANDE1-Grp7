import React, { createContext, useContext, useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
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

const EnrollmentMockContext = createContext(null);

export function EnrollmentMockProvider({ children }) {
  const [applicants, setApplicants] = useState(initialApplicants);
  const [nextId, setNextId] = useState(153);
  const addApplicant = values => {
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
  const value = useMemo(() => ({ applicants, addApplicant }), [applicants, nextId]);
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
        <label>Track<select value={program} onChange={event => setProgram(event.target.value)}><option value="all">All tracks</option><option>Academic</option><option>TVL</option><option>TVET</option></select></label>
        <label>Campus<select value={campus} onChange={event => setCampus(event.target.value)}><option value="all">All campuses</option><option>Iligan Main</option><option>Kapatagan</option><option>Bugo — CDO</option></select></label>
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

const emptyForm = {
  firstName: '', middleName: '', lastName: '', lrn: '', birthDate: '', sex: '',
  phone: '', email: '', campus: '', category: '', program: 'Senior High School',
  track: '', strand: '', voucher: '', previousSchool: '', guardianName: '',
  guardianRelationship: '', guardianPhone: '', householdIncome: '',
};

function ApplicantIntake({ addApplicant, navigate, basePath, title }) {
  const [form, setForm] = useState(emptyForm);
  const strands = form.track === 'Academic' ? ['STEM', 'ABM', 'HUMSS'] : form.track === 'TVL' ? ['Cookery', 'ICT'] : form.track === 'TVET' ? ['TVET'] : [];
  function update(event) {
    const { name, value } = event.target;
    setForm(current => ({ ...current, [name]: value, ...(name === 'track' ? { strand: '' } : {}) }));
  }
  function submit(event) {
    event.preventDefault();
    const applicant = addApplicant({ ...form, middleName: form.middleName.trim() });
    navigate(applicantUrl(basePath, applicant.id));
  }

  return <div className="enrollment-workspace">
    <header className="page-heading"><div><p className="eyebrow">ENROLLMENT MANAGEMENT</p><h1>{title}</h1><p>Enter the applicant’s details and enrollment preferences.</p></div></header>
    <form className="enrollment-form" aria-label="Applicant intake form" onSubmit={submit}>
      <section className="panel enrollment-panel">
        <div className="enrollment-panel-heading"><div><h2>Applicant information</h2><p>Fields marked required are needed to add this mock application to the queue.</p></div></div>
        <div className="enrollment-form-grid">
          <label htmlFor="applicant-first-name">First name <span aria-hidden="true">*</span><input id="applicant-first-name" name="firstName" required maxLength="150" value={form.firstName} onChange={update} /></label>
          <label htmlFor="applicant-middle-name">Middle name<input id="applicant-middle-name" name="middleName" maxLength="150" value={form.middleName} onChange={update} /></label>
          <label htmlFor="applicant-last-name">Last name <span aria-hidden="true">*</span><input id="applicant-last-name" name="lastName" required maxLength="150" value={form.lastName} onChange={update} /></label>
          <label htmlFor="applicant-lrn">Learner Reference Number (LRN)<input id="applicant-lrn" name="lrn" inputMode="numeric" pattern="[0-9]{12}" maxLength="12" title="Enter exactly 12 digits, or leave blank if not available." placeholder="12 digits, if available" value={form.lrn} onChange={update} /></label>
          <label htmlFor="applicant-birth-date">Date of birth<input id="applicant-birth-date" name="birthDate" type="date" value={form.birthDate} onChange={update} /></label>
          <label htmlFor="applicant-sex">Sex<select id="applicant-sex" name="sex" value={form.sex} onChange={update}><option value="">Select…</option><option>Female</option><option>Male</option><option>Prefer not to say</option></select></label>
          <label htmlFor="applicant-phone">Contact number<input id="applicant-phone" name="phone" type="tel" maxLength="40" value={form.phone} onChange={update} /></label>
          <label htmlFor="applicant-email">Email<input id="applicant-email" name="email" type="email" maxLength="254" value={form.email} onChange={update} /></label>
          <label htmlFor="applicant-category">Applicant category <span aria-hidden="true">*</span><select id="applicant-category" name="category" required value={form.category} onChange={update}><option value="">Select…</option><option>New</option><option>Transferee</option><option>Returnee</option><option>Continuing</option></select></label>
        </div>
      </section>
      <section className="panel enrollment-panel">
        <div className="enrollment-panel-heading"><div><h2>Enrollment preferences</h2><p>Choose the applicant’s requested campus, program, track, and voucher classification.</p></div></div>
        <div className="enrollment-form-grid">
          <label htmlFor="applicant-campus">Preferred campus <span aria-hidden="true">*</span><select id="applicant-campus" name="campus" required value={form.campus} onChange={update}><option value="">Select…</option><option>Iligan Main</option><option>Kapatagan</option><option>Bugo — CDO</option></select></label>
          <label htmlFor="applicant-program">Program <span aria-hidden="true">*</span><select id="applicant-program" name="program" required value={form.program} onChange={update}><option>Senior High School</option><option>TVET</option></select></label>
          <label htmlFor="applicant-track">Track <span aria-hidden="true">*</span><select id="applicant-track" name="track" required value={form.track} onChange={update}><option value="">Select…</option><option>Academic</option><option>TVL</option><option>TVET</option></select></label>
          <label htmlFor="applicant-strand">Strand / specialization <span aria-hidden="true">*</span><select id="applicant-strand" name="strand" required disabled={!strands.length} value={form.strand} onChange={update}><option value="">{strands.length ? 'Select…' : 'Select a track first'}</option>{strands.map(strand => <option key={strand}>{strand}</option>)}</select></label>
          <label htmlFor="applicant-voucher">Voucher classification <span aria-hidden="true">*</span><select id="applicant-voucher" name="voucher" required value={form.voucher} onChange={update}><option value="">Select…</option><option value="QVR">Qualified Voucher Recipient (QVR)</option><option value="ESC">Education Service Contracting (ESC)</option><option value="ALS">Alternative Learning System (ALS)</option><option value="REGULAR">Regular / Paying</option></select></label>
        </div>
      </section>
      <section className="panel enrollment-panel">
        <div className="enrollment-panel-heading"><div><h2>Previous school and family contact</h2><p>Household income is descriptive and uses a school-defined bracket; no bracket values are assumed.</p></div></div>
        <div className="enrollment-form-grid">
          <label htmlFor="applicant-previous-school">Junior high / previous school<input id="applicant-previous-school" name="previousSchool" maxLength="250" value={form.previousSchool} onChange={update} /></label>
          <label htmlFor="applicant-household-income">Household income bracket<input id="applicant-household-income" name="householdIncome" maxLength="100" placeholder="School-defined bracket (optional)" value={form.householdIncome} onChange={update} /></label>
          <label htmlFor="applicant-guardian-name">Parent / guardian name<input id="applicant-guardian-name" name="guardianName" maxLength="250" value={form.guardianName} onChange={update} /></label>
          <label htmlFor="applicant-guardian-relationship">Relationship<select id="applicant-guardian-relationship" name="guardianRelationship" value={form.guardianRelationship} onChange={update}><option value="">Select…</option><option>Mother</option><option>Father</option><option>Guardian</option></select></label>
          <label htmlFor="applicant-guardian-phone">Parent / guardian contact<input id="applicant-guardian-phone" name="guardianPhone" type="tel" maxLength="40" value={form.guardianPhone} onChange={update} /></label>
        </div>
      </section>
      <div className="enrollment-form-actions"><Link className="button button-outline" to={previewQueuePath(basePath)}>Cancel</Link><button className="button" type="submit">Add applicant to queue</button></div>
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
    { title: 'Enrollment preferences', fields: [['Campus', applicant.campus], ['Program', applicant.program], ['Track', applicant.track], ['Strand / specialization', applicant.strand], ['Applicant category', applicant.category], ['Voucher classification', applicant.voucher]] },
    { title: 'Previous school and family contact', fields: [['Junior high / previous school', applicant.previousSchool || 'Not provided'], ['Parent / guardian', applicant.guardianName || 'Not provided'], ['Relationship', applicant.guardianRelationship || 'Not provided'], ['Guardian contact', applicant.guardianPhone || 'Not provided'], ['Household income bracket', applicant.householdIncome || 'Not provided']] },
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
  const { applicants, addApplicant } = useEnrollmentMock();
  const location = useLocation();
  const navigate = useNavigate();
  const basePath = preview ? '/dev/enrollment' : '/enrollment';
  const relativePath = location.pathname.slice(basePath.length).replace(/^\/+|\/+$/g, '');
  const detailMatch = relativePath.match(/^applicants\/([^/]+)$/);
  const screen = requestedScreen || (preview ? detailMatch ? 'details' : relativePath === 'intake' ? 'intake' : 'queue' : 'queue');
  const applicationId = detailMatch ? decodeURIComponent(detailMatch[1]) : location.pathname.match(/\/enrollment\/applicants\/([^/]+)/)?.[1];
  const applicant = applicants.find(item => item.id === applicationId);
  const title = requestedTitle || (screen === 'intake' ? 'Applicant Intake Form' : 'Enrollment Submission');

  useEffect(() => {
    if (preview && !relativePath) navigate(`${basePath}/queue`, { replace: true });
  }, [basePath, navigate, preview, relativePath]);

  const content = screen === 'queue'
    ? <EnrollmentQueue applicants={applicants} basePath={basePath} preview={preview} />
    : screen === 'details'
      ? <ApplicantDetails applicant={applicant} basePath={basePath} preview={preview} />
      : <ApplicantIntake addApplicant={addApplicant} navigate={navigate} basePath={basePath} title={title} />;

  if (!preview) return content;
  return <main className="enrollment-preview">
    <div className="enrollment-preview-banner"><strong>Development preview</strong><span>Mock applicants only · changes reset on refresh · sign-in bypass is available only in Vite development mode.</span></div>
    <nav className="enrollment-preview-nav" aria-label="Enrollment screen previews">
      <Link to={`${basePath}/queue`}>Enrollment Queue</Link>
      <Link to={`${basePath}/intake`}>Applicant Intake Form</Link>
    </nav>
    {content}
  </main>;
}
