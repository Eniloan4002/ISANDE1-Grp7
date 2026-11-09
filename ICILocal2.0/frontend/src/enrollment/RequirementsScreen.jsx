import React, { useMemo, useState } from 'react';
import { Link } from 'react-router';
import './enrollment.css';

const documents = [
  'PSA Birth Certificate',
  'Form 137',
  'Certificate of Completion',
  'Good Moral Certificate',
];

const documentStatuses = [
  { value: 'submitted', label: 'Submitted', tone: 'success' },
  { value: 'missing', label: 'Missing', tone: 'danger' },
  { value: 'pending', label: 'Pending', tone: 'warning' },
  { value: 'invalid', label: 'Invalid', tone: 'danger' },
];

const defaultDocuments = {
  'APP-2026-0148': { 'PSA Birth Certificate': 'submitted', 'Form 137': 'pending', 'Certificate of Completion': 'missing', 'Good Moral Certificate': 'missing' },
  'APP-2026-0149': { 'PSA Birth Certificate': 'submitted', 'Form 137': 'pending', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'missing' },
  'APP-2026-0150': { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' },
  'APP-2026-0151': { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' },
  'APP-2026-0152': { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' },
};

const defaultAudits = {
  'APP-2026-0148': ['Certificate of Completion marked Missing by R. Santos — Aug 3, 2026 04:19 PM', 'PSA Birth Certificate marked Submitted by R. Santos — Aug 3, 2026 09:58 AM'],
  'APP-2026-0149': ['Form 137 marked Pending by R. Santos — Aug 5, 2026 09:14 AM', 'PSA Birth Certificate marked Submitted by R. Santos — Aug 3, 2026 02:41 PM'],
  'APP-2026-0150': ['All documents verified by R. Santos — Aug 5, 2026 08:10 AM'],
};

function fullName(applicant) {
  return [applicant.firstName, applicant.middleName, applicant.lastName].filter(Boolean).join(' ');
}

function prettyDate(date) {
  if (!date) return '—';
  return new Date(`${date}T00:00:00`).toLocaleDateString('en-PH', { year: 'numeric', month: 'short', day: 'numeric' });
}

function overallStatus(documentState) {
  const values = Object.values(documentState);
  if (!values.length || values.every(value => value === 'pending')) return 'Pending';
  if (values.every(value => value === 'submitted')) return 'Complete';
  if (values.some(value => value === 'missing' || value === 'invalid')) return 'Incomplete';
  return 'Pending';
}

function statusMeta(value) {
  return documentStatuses.find(status => status.value === value) || documentStatuses[2];
}

function overallTone(status) {
  return status === 'Complete' ? 'success' : status === 'Incomplete' ? 'danger' : 'warning';
}

function initialDocuments(applicant) {
  return { ...(defaultDocuments[applicant.id] || Object.fromEntries(documents.map(document => [document, 'pending']))) };
}

function RequirementsChecklist({ applicant, documentState, audits, onChange, onFollowUp, onClose }) {
  const overall = overallStatus(documentState);
  const submittedCount = Object.values(documentState).filter(value => value === 'submitted').length;
  return <div className="requirements-dialog-backdrop" role="presentation" onMouseDown={event => event.target === event.currentTarget && onClose()}>
    <section className="requirements-dialog" role="dialog" aria-modal="true" aria-labelledby="requirements-dialog-title">
      <header className="requirements-dialog-header">
        <div><p className="eyebrow">ENROLLMENT MANAGEMENT</p><h2 id="requirements-dialog-title">Requirements Checklist</h2></div>
        <button className="requirements-close" type="button" aria-label="Close checklist" onClick={onClose}>×</button>
      </header>
      <div className="requirements-dialog-body">
        <div className="requirements-meta">
          <span><strong>Student:</strong> {fullName(applicant)}</span>
          <span><strong>App ID:</strong> {applicant.id}</span>
          <span><strong>Category:</strong> {applicant.category}</span>
          <span><strong>Contact:</strong> {applicant.phone || '—'}</span>
        </div>
        <div className="requirements-document-list">
          {documents.map(document => {
            const meta = statusMeta(documentState[document]);
            return <div className="requirements-document-row" key={document}>
              <span className="requirements-document-name">{document}</span>
              <span className={`status status-${meta.tone}`}>{meta.label}</span>
              <select aria-label={`${document} status`} value={documentState[document]} onChange={event => onChange(document, event.target.value)}>
                {documentStatuses.map(status => <option value={status.value} key={status.value}>{status.label}</option>)}
              </select>
              <button className="button button-outline requirements-follow-up" type="button" onClick={onFollowUp}>Follow up</button>
            </div>;
          })}
        </div>
        <div className="requirements-overall-note"><span aria-hidden="true">Σ</span><div><strong>Computed overall status:</strong> <span className={`status status-${overallTone(overall)}`}>{overall}</span> — {submittedCount} of {documents.length} submitted.</div></div>
        <p className="requirements-audit-title">Update log</p>
        <div className="requirements-audit-list" aria-label="Requirement update log">
          {(audits.length ? audits : ['No updates recorded yet.']).map((audit, index) => <div className="requirements-audit-item" key={`${audit}-${index}`}><span className="requirements-audit-dot" aria-hidden="true" /><span>{audit}</span></div>)}
        </div>
        <div className="requirements-dialog-actions">
          <button className="button button-secondary" type="button" onClick={onClose}>Close</button>
          <button className="button" type="button" onClick={onFollowUp}>Send Follow-up Notice</button>
        </div>
      </div>
    </section>
  </div>;
}

function ContactDetails({ applicant, onClose, onSent }) {
  return <div className="requirements-dialog-backdrop" role="presentation" onMouseDown={event => event.target === event.currentTarget && onClose()}>
    <section className="requirements-dialog requirements-contact-dialog" role="dialog" aria-modal="true" aria-labelledby="contact-dialog-title">
      <header className="requirements-dialog-header">
        <div><p className="eyebrow">ENROLLMENT MANAGEMENT</p><h2 id="contact-dialog-title">Contact Details</h2></div>
        <button className="requirements-close" type="button" aria-label="Close contact details" onClick={onClose}>×</button>
      </header>
      <div className="requirements-dialog-body">
        <p className="requirements-audit-title">Student</p>
        <div className="requirements-meta"><span><strong>Name:</strong> {fullName(applicant)}</span><span><strong>Contact No.:</strong> {applicant.phone || '—'}</span><span><strong>Email:</strong> {applicant.email || '—'}</span></div>
        <p className="requirements-audit-title">Parent / Guardian</p>
        <div className="requirements-meta"><span><strong>Name:</strong> {applicant.guardianName || applicant.guardians?.[0]?.fullName || '—'}</span><span><strong>Contact No.:</strong> {applicant.guardianPhone || applicant.guardians?.[0]?.phone || '—'}</span></div>
        <div className="requirements-overall-note"><span aria-hidden="true">i</span><div>Phone contact is preferred over email for this demographic. Contact the guardian first if the student cannot be reached.</div></div>
        <div className="requirements-dialog-actions">
          <button className="button button-secondary" type="button" onClick={onClose}>Close</button>
          <button className="button" type="button" onClick={onSent}>Send Follow-up Notice</button>
        </div>
      </div>
    </section>
  </div>;
}

export default function RequirementsScreen({ applicants, basePath = '/enrollment' }) {
  const [documentsByApplicant, setDocumentsByApplicant] = useState(() => Object.fromEntries(applicants.map(applicant => [applicant.id, initialDocuments(applicant)])));
  const [auditsByApplicant, setAuditsByApplicant] = useState(() => Object.fromEntries(applicants.map(applicant => [applicant.id, defaultAudits[applicant.id] || []])));
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('all');
  const [programFilter, setProgramFilter] = useState('all');
  const [selectedApplicant, setSelectedApplicant] = useState(null);
  const [contactApplicant, setContactApplicant] = useState(null);
  const [notice, setNotice] = useState('');

  const rows = useMemo(() => applicants.map(applicant => ({
    applicant,
    documents: documentsByApplicant[applicant.id] || initialDocuments(applicant),
    overall: overallStatus(documentsByApplicant[applicant.id] || initialDocuments(applicant)),
  })).filter(row => {
    const query = search.trim().toLowerCase();
    const name = fullName(row.applicant).toLowerCase();
    const program = row.applicant.program?.toLowerCase() || '';
    return (!query || `${name} ${row.applicant.id} ${row.applicant.lrn || ''}`.toLowerCase().includes(query)) &&
      (statusFilter === 'all' || row.overall.toLowerCase() === statusFilter) &&
      (programFilter === 'all' || program === programFilter);
  }), [applicants, documentsByApplicant, programFilter, search, statusFilter]);

  const counts = useMemo(() => {
    const allRows = applicants.map(applicant => overallStatus(documentsByApplicant[applicant.id] || initialDocuments(applicant)));
    return {
      Complete: allRows.filter(status => status === 'Complete').length,
      Incomplete: allRows.filter(status => status === 'Incomplete').length,
      Pending: allRows.filter(status => status === 'Pending').length,
    };
  }, [applicants, documentsByApplicant]);

  function updateDocument(document, value) {
    const applicant = selectedApplicant;
    setDocumentsByApplicant(current => ({ ...current, [applicant.id]: { ...current[applicant.id], [document]: value } }));
    setAuditsByApplicant(current => ({ ...current, [applicant.id]: [`${document} marked ${statusMeta(value).label} by Registrar — ${new Date().toLocaleString('en-PH')}`, ...(current[applicant.id] || [])] }));
    setNotice(`${document} updated to ${statusMeta(value).label}.`);
  }

  function showFollowUp() {
    setContactApplicant(selectedApplicant);
  }

  function sendFollowUp() {
    setNotice(`Follow-up notice prepared for ${fullName(contactApplicant)}.`);
    setContactApplicant(null);
    setSelectedApplicant(null);
  }

  return <div className="enrollment-workspace">
    <header className="page-heading enrollment-heading">
      <div><p className="eyebrow">ENROLLMENT MANAGEMENT</p><h1>Requirements Checklist</h1><p>Track submitted credentials per applicant. The overall status is computed from each document status.</p></div>
      <Link className="button button-secondary" to={`${basePath}/queue`}>← Back to Queue</Link>
    </header>
    {notice && <p className="requirements-notice" role="status">{notice}</p>}
    <div className="enrollment-stats requirements-stats" aria-label="Requirement statistics">
      <section className="enrollment-stat"><strong>{counts.Complete}</strong><div><span>Complete</span><small>All documents submitted</small></div></section>
      <section className="enrollment-stat"><strong>{counts.Incomplete}</strong><div><span>Incomplete</span><small>Missing or invalid documents</small></div></section>
      <section className="enrollment-stat"><strong>{counts.Pending}</strong><div><span>Pending</span><small>Awaiting submission</small></div></section>
      <section className="enrollment-stat"><strong>{counts.Incomplete + counts.Pending}</strong><div><span>For follow-up</span><small>Needs contact</small></div></section>
    </div>
    <section className="panel enrollment-panel" aria-labelledby="requirements-table-title">
      <div className="enrollment-panel-heading"><div><h2 id="requirements-table-title">Applicant Requirements</h2><p>Open an applicant to review and update their checklist.</p></div></div>
      <div className="enrollment-filters requirements-filters">
        <label>Search<input type="search" aria-label="Search requirements" placeholder="Filter by name / ID / LRN..." value={search} onChange={event => setSearch(event.target.value)} /></label>
        <label>Status<select aria-label="Filter by requirement status" value={statusFilter} onChange={event => setStatusFilter(event.target.value)}><option value="all">Status: All</option><option value="complete">Complete</option><option value="incomplete">Incomplete</option><option value="pending">Pending</option></select></label>
        <label>Program<select aria-label="Filter by program" value={programFilter} onChange={event => setProgramFilter(event.target.value)}><option value="all">All programs</option>{[...new Set(applicants.map(applicant => applicant.program).filter(Boolean))].map(program => <option value={program.toLowerCase()} key={program}>{program}</option>)}</select></label>
      </div>
      {rows.length ? <div className="enrollment-table-wrap"><table className="enrollment-table requirements-table">
        <thead><tr><th>Applicant</th><th>App ID</th><th>Category</th><th>Documents</th><th>Overall status</th><th>Last updated</th><th>Action</th></tr></thead>
        <tbody>{rows.map(({ applicant, documents: documentState, overall }) => <tr key={applicant.id}>
          <td><strong>{applicant.lastName}, {applicant.firstName} {applicant.middleName?.charAt(0) ? `${applicant.middleName.charAt(0)}.` : ''}</strong><small>LRN {applicant.lrn || '—'}</small></td>
          <td>{applicant.id}</td><td>{applicant.category}</td>
          <td><div className="requirements-chips">{documents.map(document => { const meta = statusMeta(documentState[document]); return <span className={`requirements-chip ${meta.tone}`} key={document} title={`${document}: ${meta.label}`}>{document === 'PSA Birth Certificate' ? 'PSA Birth Cert' : document === 'Certificate of Completion' ? 'Completion' : document}</span>; })}</div></td>
          <td><span className={`status status-${overallTone(overall)}`}>{overall}</span></td><td>{prettyDate(applicant.submittedAt)}</td>
          <td><button className="button button-outline enrollment-view" type="button" onClick={() => setSelectedApplicant(applicant)}>View</button></td>
        </tr>)}</tbody>
      </table></div> : <div className="empty-state enrollment-empty"><h3>No matching applicants</h3><p>Try changing or clearing the search and filters.</p><button className="button button-outline" type="button" onClick={() => { setSearch(''); setStatusFilter('all'); setProgramFilter('all'); }}>Clear filters</button></div>}
      <p className="requirements-result-count">Showing {rows.length} of {applicants.length} applicants</p>
    </section>
    {selectedApplicant && <RequirementsChecklist applicant={selectedApplicant} documentState={documentsByApplicant[selectedApplicant.id] || initialDocuments(selectedApplicant)} audits={auditsByApplicant[selectedApplicant.id] || []} onChange={updateDocument} onFollowUp={showFollowUp} onClose={() => setSelectedApplicant(null)} />}
    {contactApplicant && <ContactDetails applicant={contactApplicant} onClose={() => setContactApplicant(null)} onSent={sendFollowUp} />}
  </div>;
}
