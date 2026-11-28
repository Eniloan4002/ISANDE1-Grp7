import { useState } from 'react';
import { Link } from 'react-router';
import { Failure, Pager, useDebouncedValue, useResource } from '../EnrollmentWorkspace.jsx';
import { Drawer, StatusChip } from '../ui/components.jsx';
import '../academic/academic-screens.css';

const requirementsTone = { Complete: 'success', Incomplete: 'blocked', Pending: 'attention' };

function Field({ label, children }) {
  return <div className="ac-field"><dt>{label}</dt><dd>{children || 'Not recorded'}</dd></div>;
}

function RecordDrawer({ row, onClose }) {
  const detail = useResource(`/api/intake/applications/${row.id}`);
  const guardians = detail.data?.profile?.guardians ?? [];
  return <Drawer open onClose={onClose} title={row.name}
    footer={row.studentId
      ? <Link className="ui-btn ui-btn-primary" to={`/academic/registrar-records?studentId=${row.studentId}`}>Open full academic history</Link>
      : <span className="ac-muted">No student record is linked yet, so there is no academic history.</span>}>
    <dl className="ac-fields">
      <Field label="Student number">{row.studentNumber}</Field>
      <Field label="LRN">{row.lrn}</Field>
      <Field label="Category">{row.category}</Field>
      <Field label="Program">{row.program}</Field>
      <Field label="Campus">{row.campus}</Field>
      <Field label="Term">{row.term}</Field>
      <Field label="Section">{row.section}</Field>
      <Field label="Enrollment status"><StatusChip tone={row.status === 'enrolled' ? 'success' : 'neutral'}>{row.status === 'enrolled' ? 'Enrolled' : row.status}</StatusChip></Field>
      <Field label="Requirements"><StatusChip tone={requirementsTone[row.requirementsStatus] || 'neutral'}>{row.requirementsStatus}</StatusChip></Field>
      <Field label="Phone">{row.phone}</Field>
      <Field label="Email">{row.email}</Field>
      <Field label="Voucher">{detail.data?.voucher}</Field>
    </dl>
    {detail.loading && <p role="status">Loading voucher and guardian details…</p>}
    <Failure error={detail.error} reload={detail.reload} />
    {guardians.length > 0 && <>
      <h3 className="ac-subheading">Parents and guardians</h3>
      <ul className="ac-roster">
        {guardians.map((guardian, index) => <li key={`${guardian.fullName}-${index}`}>
          <div><strong>{guardian.fullName}</strong><small>{guardian.relationship}{guardian.phone ? ` · ${guardian.phone}` : ''}</small></div>
        </li>)}
      </ul>
    </>}
  </Drawer>;
}

export default function EnrollmentRecords() {
  const [search, setSearch] = useState('');
  const [campus, setCampus] = useState('');
  const [term, setTerm] = useState('');
  const [program, setProgram] = useState('');
  const [section, setSection] = useState('');
  const [page, setPage] = useState(0);
  const [open, setOpen] = useState(null);
  const effectiveSearch = useDebouncedValue(search);

  const reference = useResource('/api/enrollment/reference-data');
  const sectionQuery = new URLSearchParams({ size: '100' });
  if (campus) sectionQuery.set('campusId', campus);
  if (term) sectionQuery.set('termId', term);
  if (program) sectionQuery.set('programId', program);
  const sections = useResource(`/api/sections?${sectionQuery}`);

  const query = new URLSearchParams({ status: 'enrolled', page: String(page), size: '25', search: effectiveSearch.trim() });
  if (campus) query.set('campusId', campus);
  if (term) query.set('termId', term);
  if (program) query.set('programId', program);
  if (section) query.set('sectionId', section);
  const directory = useResource(`/api/enrollment/applications?${query}`);
  const rows = directory.data?.items ?? [];

  // Changing a parent filter clears the section, because the list of sections depends on it.
  const change = (setter, clearSection = false) => event => {
    setter(event.target.value);
    if (clearSection) setSection('');
    setPage(0);
  };
  const campuses = reference.data?.campuses ?? [];
  const terms = reference.data?.terms ?? [];
  const programs = reference.data?.programs ?? [];
  const sectionOptions = sections.data?.items ?? [];

  return <div className="enrollment-workspace ac-screen">
    <header className="page-heading">
      <p className="eyebrow">Enrollment management</p>
      <h1>Enrollment Records</h1>
      <p>Enrolled students, with their section, requirements and a link to their full academic history.</p>
    </header>
    <Failure error={reference.error} reload={reference.reload} />
    <section className="panel" aria-label="Filters">
      <div className="ac-filters">
        <label>Search
          <input type="search" aria-label="Search enrolled students" maxLength={200} value={search} onChange={change(setSearch)} placeholder="Name, student number or LRN" />
        </label>
        <label>Campus
          <select value={campus} onChange={change(setCampus, true)}><option value="">All campuses</option>{campuses.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select>
        </label>
        <label>Term
          <select value={term} onChange={change(setTerm, true)}><option value="">All terms</option>{terms.map(item => <option key={item.id} value={item.id}>{item.academicYear} / {item.name}</option>)}</select>
        </label>
        <label>Program
          <select value={program} onChange={change(setProgram, true)}><option value="">All programs</option>{programs.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select>
        </label>
        <label>Section
          <select value={section} onChange={change(setSection)}><option value="">All sections</option>{sectionOptions.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select>
        </label>
      </div>
    </section>
    <Failure error={directory.error} reload={directory.reload} />
    {directory.loading && <p role="status">Loading enrollment records…</p>}
    {!directory.loading && !directory.error && (rows.length === 0
      ? <div className="empty-state"><h3>No enrolled students match</h3><p>Try clearing the search or a filter.</p></div>
      : <section className="panel" aria-label="Enrolled students">
        <div className="setup-table-wrap">
          <table className="ac-table">
            <thead><tr><th scope="col">Student</th><th scope="col">Program</th><th scope="col">Campus</th><th scope="col">Section</th><th scope="col">Requirements</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>{rows.map(row => <tr key={row.id}>
              <td data-label="Student"><strong>{row.name}</strong><small>{row.studentNumber || row.applicationNumber}</small></td>
              <td data-label="Program">{row.program}</td>
              <td data-label="Campus">{row.campus}</td>
              <td data-label="Section">{row.section || <span className="ac-muted">Not placed</span>}</td>
              <td data-label="Requirements"><StatusChip tone={requirementsTone[row.requirementsStatus] || 'neutral'}>{row.requirementsStatus}</StatusChip></td>
              <td data-label="Actions" className="ac-actions"><button type="button" className="ui-btn ui-btn-secondary" onClick={() => setOpen(row)} aria-label={`View record for ${row.name}`}>View record</button></td>
            </tr>)}</tbody>
          </table>
        </div>
        <div className="ac-pager"><Pager page={page} total={directory.data?.total ?? 0} onChange={setPage} disabled={directory.loading} /></div>
      </section>)}
    {open && <RecordDrawer row={open} onClose={() => setOpen(null)} />}
  </div>;
}
