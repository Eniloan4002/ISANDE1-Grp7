import React, { useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import './enrollment.css';
import {useResource,Failure,Pager} from '../EnrollmentWorkspace.jsx';
import {send,referenceData as adaptReference,intakePayload,queueApplicant,applicantDetail} from './enrollmentApi.js';

const applicantCategories = [
  { code: 'new', name: 'New' },
  { code: 'transferee', name: 'Transferee' },
  { code: 'returnee', name: 'Returnee' },
  { code: 'continuing', name: 'Continuing' },
];

const statuses = ['submitted','pre_enlisted','under_review','duplicate_flag','enrolled','rejected','withdrawn'];

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

function EnrollmentQueue({ referenceData, basePath, title }) {
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('all');
  const [program, setProgram] = useState('all');
  const [campus, setCampus] = useState('all');
  const intakePath = '/enrollment/intake';
  const [page,setPage]=useState(0);
  const params=new URLSearchParams({page:String(page),size:'25',search,status:status==='all'?'':status,campusId:campus==='all'?'':campus,programId:program==='all'?'':program});
  const directory=useResource(`/api/enrollment/applications?${params}`);
  const statistics=useResource('/api/intake/statistics');
  const filtered=(directory.data?.items??[]).map(queueApplicant);
  const stats=[
    {label:'Submitted',value:statistics.data?.submitted,description:'Awaiting registrar review'},
    {label:'Pre-enlisted',value:statistics.data?.preEnlisted,description:'Ready for credential verification'},
    {label:'Duplicate flags',value:statistics.data?.duplicates,description:'For review'},
    {label:'Enrolled',value:statistics.data?.enrolled,description:'Confirmed enrollment'},
  ];

  return <div className="enrollment-workspace">
    <header className="page-heading enrollment-heading">
      <div><p className="eyebrow">ENROLLMENT MANAGEMENT</p><h1>{title||'Enrollment Queue'}</h1><p>Review and manage incoming applicant submissions.</p></div>
      <Link className="button" to={intakePath}>New applicant</Link>
    </header>
    <div className="enrollment-stats" aria-label="Application statistics">
      {stats.map(item => <section className="enrollment-stat" key={item.label}>
        <strong>{item.value??"…"}</strong><div><span>{item.label}</span><small>{item.description}</small></div>
      </section>)}
    </div>
    <section className="panel enrollment-panel" aria-labelledby="applicant-queue-title">
      <div className="enrollment-panel-heading"><div><h2 id="applicant-queue-title">Applicant Queue</h2><p>{directory.data?.total??0} matching applications</p></div></div>
      <div className="enrollment-filters">
        <label>Search applicants<input type="search" value={search} onChange={event => { setSearch(event.target.value); setPage(0); }} placeholder="Name, application ID, or LRN" /></label>
        <label>Status<select value={status} onChange={event => { setStatus(event.target.value); setPage(0); }}><option value="all">All statuses</option>{statuses.map(value => <option key={value} value={value}>{value.replaceAll('_',' ')}</option>)}</select></label>
        <label>Program<select value={program} onChange={event => { setProgram(event.target.value); setPage(0); }}><option value="all">All programs</option>{referenceData.specializations.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
        <label>Campus<select value={campus} onChange={event => { setCampus(event.target.value); setPage(0); }}><option value="all">All campuses</option>{referenceData.campuses.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      </div>
      <Failure error={directory.error} reload={directory.reload}/><Failure error={statistics.error} reload={statistics.reload}/>{directory.loading&&<p role="status">Loading applications…</p>}
      {!directory.loading&&!directory.error&&(filtered.length ? <div className="enrollment-table-wrap"><table className="enrollment-table">
        <thead><tr><th scope="col">Applicant</th><th scope="col">Application ID</th><th scope="col">Submitted</th><th scope="col">Program</th><th scope="col">Campus</th><th scope="col">Requirements</th><th scope="col">Status</th><th scope="col">Action</th></tr></thead>
        <tbody>{filtered.map(applicant => <tr key={applicant.id}>
          <td><strong>{applicant.lastName}, {applicant.firstName}</strong><small>{applicant.lrn ? `LRN ${applicant.lrn}` : 'LRN not provided'}</small></td>
          <td>{applicant.applicationNumber}</td><td>{prettyDate(applicant.submittedAt)}</td><td>{applicant.track} · {applicant.strand}</td>
          <td>{applicant.campus}</td><td><span className={`status ${applicant.requirements === 'Complete' ? 'status-success' : 'status-warning'}`}>{applicant.requirements}</span></td>
          <td><span className={`status status-${statusTone(applicant.status)}`}>{applicant.status}</span>{applicant.duplicate && <small className="enrollment-flag">Possible duplicate</small>}</td>
          <td><Link className="button button-outline enrollment-view" to={applicantUrl(basePath, applicant.id)}>View</Link></td>
        </tr>)}</tbody>
      </table></div> : <div className="empty-state enrollment-empty"><h3>No matching applicants</h3><p>Try changing or clearing the search and filters.</p><button className="button button-outline" type="button" onClick={() => { setSearch(''); setStatus('all'); setProgram('all'); setCampus('all'); }}>Clear filters</button></div>)}<Pager page={page} total={directory.data?.total??0} onChange={setPage} disabled={directory.loading}/>
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
    householdIncome: '', gradeLevel:'', district:'', transportRoute:'', guardians: [emptyGuardian()],
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
      const campus = referenceData.campuses.find(item => String(item.id) === form.campusId);
      const program = referenceData.programs.find(item => item.id === form.programId);
      const term = referenceData.terms.find(item => String(item.id) === form.termId);
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
      <fieldset disabled={submitting} className="intake-fields"><section className="panel enrollment-panel">
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
        <div className="enrollment-panel-heading"><div><h2>Enrollment preferences</h2><p>Choose from the school’s configured programs and academic terms.</p></div></div>
        <div className="enrollment-form-grid">
          <label htmlFor="applicant-campus">Preferred campus <span aria-hidden="true">*</span><select id="applicant-campus" name="campusId" required value={form.campusId} onChange={update}><option value="">Select…</option>{referenceData.campuses.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label htmlFor="applicant-term">Academic term <span aria-hidden="true">*</span><select id="applicant-term" name="termId" required value={form.termId} onChange={update}><option value="">Select…</option>{referenceData.terms.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label htmlFor="applicant-program">Program <span aria-hidden="true">*</span><select id="applicant-program" name="programId" required value={form.programId} onChange={update}><option value="">Select…</option>{referenceData.programs.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <label htmlFor="applicant-track">Track <span aria-hidden="true">*</span><select id="applicant-track" name="trackCode" required disabled={!tracks.length} value={form.trackCode} onChange={update}><option value="">{tracks.length ? 'Select…' : 'Select a program first'}</option>{tracks.map(item => <option key={item.code} value={item.code}>{item.name}</option>)}</select></label>
          <label htmlFor="applicant-strand">Strand / specialization <span aria-hidden="true">*</span><select id="applicant-strand" name="specializationCode" required disabled={!specializations.length} value={form.specializationCode} onChange={update}><option value="">{specializations.length ? 'Select…' : 'Select a track first'}</option>{specializations.map(item => <option key={item.code} value={item.code}>{item.name}</option>)}</select></label>
          <label htmlFor="applicant-grade">Grade level <span aria-hidden="true">*</span><input id="applicant-grade" name="gradeLevel" required maxLength="30" placeholder="e.g. 11 or institution-defined level" value={form.gradeLevel} onChange={update}/></label>
          <label htmlFor="applicant-district">Residential district<input id="applicant-district" name="district" maxLength="200" value={form.district} onChange={update}/></label>
          <label htmlFor="applicant-route">Transportation route<input id="applicant-route" name="transportRoute" maxLength="200" value={form.transportRoute} onChange={update}/></label>
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
      </fieldset><div className="enrollment-form-actions"><Link className="button button-outline" to={previewQueuePath(basePath)}>Cancel</Link><button className="button" type="submit" disabled={submitting}>{submitting ? 'Adding applicant…' : 'Add applicant to queue'}</button></div>
      {submitting && <p className="enrollment-submit-status" role="status">Saving application…</p>}
    </form>
  </div>;
}

function previewQueuePath(basePath) {
  return basePath === '/dev/enrollment' ? `${basePath}/queue` : '/enrollment/queue';
}

function ExistingStudentLink({applicant,onLinked}){
  const [search,setSearch]=useState(''),[page,setPage]=useState(0),[selected,setSelected]=useState(null),[reason,setReason]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const matches=useResource(`/api/students?${new URLSearchParams({search,active:'true',page:String(page),size:'25'})}`);
  const student=useResource(selected?`/api/students/${selected}`:null);
  async function link(event){event.preventDefault();setBusy(true);setError('');try{await send(`/api/intake/applications/${applicant.id}/link-student`,'POST',{studentId:Number(selected),studentVersion:student.data.version,version:applicant.version,reason});setSelected(null);setReason('');onLinked();}catch(e){setError(e.message);}finally{setBusy(false);}}
  return <section className="panel"><h2>Link existing student</h2><div className="panel-body"><p>Search a verified active student identity for a returning/continuing applicant. Confirm the DOB and LRN/name before linking. This keeps the existing master record and academic history.</p><label htmlFor="existing-student-search">Find existing student</label><input id="existing-student-search" maxLength={150} value={search} onChange={event=>{setSearch(event.target.value);setPage(0);}}/><Failure error={matches.error} reload={matches.reload}/><div className="setup-table-wrap"><table className="setup-table"><thead><tr><th>Student</th><th>LRN</th><th>Select</th></tr></thead><tbody>{matches.data?.items.map(row=><tr key={row.id}><td>{row.first_name} {row.last_name}<small>{row.student_number}</small></td><td>{row.lrn||'Not recorded'}</td><td><button className="button button-outline" onClick={()=>setSelected(String(row.id))}>Select student {row.id}</button></td></tr>)}</tbody></table></div><Pager page={page} total={matches.data?.total??0} onChange={setPage}/><Failure error={error}/><Failure error={student.error} reload={student.reload}/>{student.data&&<form onSubmit={link}><p><strong>{student.data.first_name} {student.data.last_name}</strong> / DOB {student.data.birth_date} / LRN {student.data.lrn||'Not recorded'}</p><label htmlFor="link-reason">Identity verification reason</label><textarea id="link-reason" required maxLength={2000} value={reason} disabled={busy} onChange={event=>setReason(event.target.value)}/><button className="button" disabled={busy||!reason.trim()}>Confirm student link</button></form>}</div></section>;
}

function ApplicantDetails({ applicant, basePath, preview, onReviewed }) {
  const [busy,setBusy]=useState(false),[error,setError]=useState('');
  async function review(){setBusy(true);setError('');try{await send(`/api/intake/applications/${applicant.id}/review`,'POST',{version:applicant.version});onReviewed();}catch(e){setError(e.message);}finally{setBusy(false);}}
  const queuePath = preview ? `${basePath}/queue` : '/enrollment/queue';
  if (!applicant) return <section className="content-card"><h1>Applicant not found</h1><p>The application may be unavailable in your campus scope.</p><Link className="button button-outline" to={queuePath}>Back to queue</Link></section>;

  const sections = [
    { title: 'Personal information', fields: [['Full name', fullName(applicant)], ['Application ID', applicant.applicationNumber], ['LRN', applicant.lrn || 'Not provided'], ['Date of birth', prettyDate(applicant.birthDate)], ['Sex', applicant.sex || 'Not provided'], ['Contact number', applicant.phone || 'Not provided'], ['Email', applicant.email || 'Not provided']] },
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
      <div><p className="eyebrow">ENROLLMENT MANAGEMENT / APPLICANT DETAILS</p><h1>{fullName(applicant)}</h1><p>{applicant.applicationNumber} / Submitted {prettyDate(applicant.submittedAt)}</p></div>
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
    <Failure error={error}/>
    {['submitted','duplicate_flag','under_review'].includes(applicant.statusCode)&&!applicant.studentId&&<ExistingStudentLink applicant={applicant} onLinked={onReviewed}/>}
    {applicant.studentId&&<p className="notice">Linked student #{applicant.studentId}. Review and pre-enlist when duplicate checks are clear.</p>}
    <div className="component-row">
      {applicant.statusCode==='submitted'&&<button className="button" disabled={busy} onClick={review}>{busy?'Reviewing…':'Review and pre-enlist'}</button>}
      <Link className="button button-outline" to={`/enrollment/duplicate?applicationId=${applicant.id}`}>Review duplicate checks</Link>
      <Link className="button button-outline" to={`/enrollment/requirements?applicationId=${applicant.id}`}>Open requirements</Link>
      <Link className="button button-outline" to={`/enrollment/attendance?applicationId=${applicant.id}`}>Attendance and confirmation</Link>
    </div><p className="field-help">Review checks for duplicate identities and creates the student master record. Enrollment is confirmed separately after credentials and attendance are verified.</p>
  </div>;
}

export function EnrollmentWorkspace({screen='queue',title}) {
  const location=useLocation(),navigate=useNavigate();
  const reference=useResource('/api/intake/reference-data');
  const referenceData=adaptReference(reference.data);
  const id=location.pathname.match(/^\/enrollment\/applicants\/(\d+)$/)?.[1];
  const detail=useResource(screen==='details'&&id?`/api/intake/applications/${id}`:null);
  async function addApplicant(values){return send('/api/intake/applications','POST',intakePayload(values,referenceData));}
  if(reference.error)return <Failure error={reference.error} reload={reference.reload}/>;
  if(!referenceData)return <p role="status">Loading enrollment catalogs…</p>;
  if(screen==='details')return <><Failure error={detail.error} reload={detail.reload}/>{detail.loading?<p role="status">Loading application…</p>:detail.data?<ApplicantDetails applicant={applicantDetail(detail.data,referenceData)} basePath="/enrollment" onReviewed={detail.reload}/>:!detail.error&&<p>Application not found.</p>}</>;
  if(screen==='queue')return <EnrollmentQueue referenceData={referenceData} basePath="/enrollment" title={title}/>;
  return <ApplicantIntake addApplicant={addApplicant} navigate={navigate} basePath="/enrollment" title="Applicant Intake Form" referenceData={referenceData}/>;
}
