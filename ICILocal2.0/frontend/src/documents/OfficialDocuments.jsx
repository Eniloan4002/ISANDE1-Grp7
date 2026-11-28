import { useEffect, useMemo, useState } from 'react';
import { Failure } from '../EnrollmentWorkspace.jsx';
import { Button, Dialog, StatusChip } from '../ui/components.jsx';
import {
  approveDocumentPolicy, createDocumentPolicy, describeBlocker, documentTypes, downloadIssuedDocument, issueDocument, loadDocumentPolicies,
  loadReference, loadSpecialOrder, loadStudentRecord, newRequestKey, previewDocument, saveSpecialOrder, searchStudents, typeLabel,
  updateDocumentPolicy, verifyDocument,
} from './officialDocumentsApi.js';
import '../academic/academic-screens.css';
import '../academic/records-screens.css';

function formatTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}
const fullName = student => [student.first_name, student.middle_name, student.last_name].filter(Boolean).join(' ');
const todayIso = () => new Date().toISOString().slice(0, 10);

function useReference() {
  const [reference, setReference] = useState({ campuses: [], programs: [] });
  useEffect(() => {
    let active = true;
    loadReference().then(data => active && setReference({ campuses: data?.campuses ?? [], programs: data?.programs ?? [] })).catch(() => {});
    return () => { active = false; };
  }, []);
  return reference;
}

function policyName(policy, reference) {
  const campus = reference.campuses.find(item => item.id === policy.campus_id)?.name ?? `Campus #${policy.campus_id}`;
  const program = reference.programs.find(item => item.id === policy.program_id)?.code ?? `Program #${policy.program_id}`;
  return `${typeLabel[policy.document_type] ?? policy.document_type} · ${program} · ${campus}`;
}

function StudentPicker({ selected, onSelect }) {
  const [search, setSearch] = useState('');
  const [results, setResults] = useState({ status: 'idle', items: [], error: '' });
  async function find(event) {
    event.preventDefault();
    if (!search.trim()) return;
    setResults({ status: 'loading', items: [], error: '' });
    try {
      const data = await searchStudents(search.trim());
      setResults({ status: 'ready', items: data?.items ?? [], error: '' });
    } catch (failure) {
      setResults({ status: 'error', items: [], error: failure?.message || 'Students could not be searched.' });
    }
  }
  return <section className="panel rs-section" aria-labelledby="student-title">
    <h2 id="student-title">1. Student</h2>
    <form className="rs-grid" onSubmit={find} role="search">
      <label>Find a student<input value={search} maxLength={200} placeholder="Name, student number or LRN" onChange={event => setSearch(event.target.value)} /></label>
      <div className="rs-actions"><Button type="submit" variant="secondary" disabled={!search.trim() || results.status === 'loading'}>{results.status === 'loading' ? 'Searching…' : 'Search'}</Button></div>
    </form>
    <Failure error={results.error} />
    {results.status === 'ready' && results.items.length === 0 && <p className="ac-muted">No students match “{search.trim()}”.</p>}
    {results.items.length > 0 && <ul className="rs-picklist" aria-label="Matching students">{results.items.map(student => <li key={student.id}>
      <button type="button" aria-pressed={selected?.id === student.id} onClick={() => onSelect(student)}>
        {fullName(student)}<small>{student.student_number}{student.lrn ? ` · LRN ${student.lrn}` : ''}</small>
      </button>
    </li>)}</ul>}
  </section>;
}

function SpecialOrderEditor({ enrollmentId, label, onSaved }) {
  const [record, setRecord] = useState({ status: 'loading', data: null, error: '' });
  const [form, setForm] = useState({ number: '', issuedOn: '', issuingAuthority: '' });
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let active = true;
    loadSpecialOrder(enrollmentId).then(data => {
      if (!active) return;
      setRecord({ status: 'ready', data, error: '' });
      setForm({ number: data?.number ?? '', issuedOn: data?.issued_on ?? '', issuingAuthority: data?.issuing_authority ?? '' });
    }).catch(failure => active && setRecord({ status: 'error', data: null, error: failure?.message || 'The SO record could not be loaded.' }));
    return () => { active = false; };
  }, [enrollmentId]);

  async function save(event) {
    event.preventDefault();
    if (!form.number.trim() || !form.issuedOn || !form.issuingAuthority.trim()) { setError('Enter the SO number, issue date and issuing authority.'); return; }
    setBusy(true);
    setError('');
    try {
      const data = await saveSpecialOrder(enrollmentId, { version: record.data?.version ?? -1, ...form });
      setRecord({ status: 'ready', data, error: '' });
      setEditing(false);
      onSaved();
    } catch (failure) {
      setError(failure?.status === 409 ? 'This SO was changed by someone else. Reload the page to see the latest before saving.' : failure?.message || 'The SO could not be saved.');
    } finally {
      setBusy(false);
    }
  }

  const data = record.data;
  const recorded = data && data.version >= 0;
  return <li className="rs-stack">
    <div className="rs-actions">
      <strong>{label}</strong>
      {recorded ? <StatusChip tone="success">SO {data.number}</StatusChip> : record.status === 'ready' && <StatusChip tone="attention">No SO recorded</StatusChip>}
      {!editing && record.status === 'ready' && <Button variant="secondary" onClick={() => setEditing(true)}>{recorded ? 'Correct SO' : 'Enter SO'}</Button>}
    </div>
    <Failure error={record.error} />
    {recorded && !editing && <p className="ac-muted">Issued {data.issued_on} by {data.issuing_authority}</p>}
    {(data?.policyChecks ?? []).filter(check => check.status !== 'OK' && check.status !== 'NOT_REQUIRED').map(check => <p key={check.documentType} className="ac-muted">{typeLabel[check.documentType] ?? check.documentType}: {check.message}</p>)}
    {editing && <form className="rs-grid" onSubmit={save} aria-label={`Special Order for ${label}`}>
      <label>SO number<input value={form.number} maxLength={120} onChange={event => setForm({ ...form, number: event.target.value })} /></label>
      <label>Issued on<input type="date" max={todayIso()} value={form.issuedOn} onChange={event => setForm({ ...form, issuedOn: event.target.value })} /></label>
      <label>Issuing authority<input value={form.issuingAuthority} maxLength={300} onChange={event => setForm({ ...form, issuingAuthority: event.target.value })} /></label>
      <div className="rs-actions">
        <Button type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save SO'}</Button>
        <Button variant="secondary" onClick={() => { setEditing(false); setError(''); }} disabled={busy}>Cancel</Button>
      </div>
    </form>}
    {error && <div className="notice notice-error" role="alert">{error}</div>}
  </li>;
}

function IssueDocument({ policies, reference }) {
  const [student, setStudent] = useState(null);
  const [record, setRecord] = useState({ status: 'idle', terms: [], error: '' });
  const [documentType, setDocumentType] = useState('FORM137');
  const [policyId, setPolicyId] = useState('');
  const [chosen, setChosen] = useState([]);
  const [check, setCheck] = useState({ status: 'idle', data: null, error: '' });
  const [revision, setRevision] = useState(0);
  const [requestKey, setRequestKey] = useState(newRequestKey);
  const [busy, setBusy] = useState('');
  const [actionError, setActionError] = useState('');
  const [confirming, setConfirming] = useState(false);
  const [issued, setIssued] = useState(null);

  function pickStudent(next) {
    setStudent(next);
    setIssued(null);
    setRecord({ status: 'loading', terms: [], error: '' });
    loadStudentRecord(next.id)
      .then(data => setRecord({ status: 'ready', terms: data?.terms ?? [], error: '' }))
      .catch(failure => setRecord({ status: 'error', terms: [], error: failure?.message || 'The student record could not be loaded.' }));
  }

  const programCodes = new Set(record.terms.map(term => term.program));
  const codeOf = id => reference.programs.find(item => item.id === id)?.code;
  const candidates = policies.filter(policy => policy.document_type === documentType && (!reference.programs.length || programCodes.has(codeOf(policy.program_id))));
  const policy = policies.find(item => String(item.id) === policyId);
  const terms = useMemo(() => record.terms.filter(term => !policy || !reference.programs.length || term.program === codeOf(policy.program_id)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [record.terms, policy, reference.programs]);
  const termLabel = id => {
    const term = record.terms.find(item => String(item.enrollmentId) === String(id));
    return term ? `${term.academicYear} ${term.term}` : null;
  };

  // Pick the only matching policy and select every matching term; the registrar can narrow from there.
  useEffect(() => {
    setPolicyId(current => (candidates.some(item => String(item.id) === current) ? current : candidates.length === 1 ? String(candidates[0].id) : ''));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentType, record.terms, policies]);
  useEffect(() => { setChosen(terms.map(term => term.enrollmentId)); }, [terms]);

  const selection = student && policy && chosen.length
    ? { studentId: student.id, policyId: policy.id, policyVersion: policy.version, enrollmentIds: [...chosen].sort((a, b) => a - b) }
    : null;
  const selectionKey = JSON.stringify(selection);

  // Each change to the selection is a new document request; retries of the same selection reuse its key.
  useEffect(() => { setRequestKey(newRequestKey()); setIssued(null); setActionError(''); }, [selectionKey]);
  useEffect(() => {
    if (!selection) { setCheck({ status: 'idle', data: null, error: '' }); return undefined; }
    let active = true;
    setCheck(current => ({ ...current, status: 'loading', error: '' }));
    verifyDocument(selection)
      .then(data => active && setCheck({ status: 'ready', data, error: '' }))
      .catch(failure => active && setCheck({ status: 'error', data: null, error: failure?.message || 'Verification could not be run.' }));
    return () => { active = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectionKey, revision]);

  async function preview() {
    setBusy('preview');
    setActionError('');
    try { await previewDocument(selection); } catch (failure) { setActionError(failure.message); } finally { setBusy(''); }
  }
  async function issue() {
    setBusy('issue');
    setActionError('');
    try {
      setIssued(await issueDocument({ ...selection, requestKey }));
      setConfirming(false);
    } catch (failure) {
      setConfirming(false);
      setActionError(failure.message || 'The document could not be issued.');
      if (failure.status === 409) setRevision(value => value + 1);
    } finally {
      setBusy('');
    }
  }
  async function download() {
    setBusy('download');
    setActionError('');
    try { await downloadIssuedDocument(issued.id); } catch (failure) { setActionError(failure.message); } finally { setBusy(''); }
  }

  const result = check.data;
  const reasons = result?.reasons ?? [];
  return <>
    <StudentPicker selected={student} onSelect={pickStudent} />
    {student && <section className="panel rs-section" aria-labelledby="document-title">
      <h2 id="document-title">2. Document for {fullName(student)}</h2>
      <Failure error={record.error} />
      {record.status === 'loading' && <p role="status">Loading the student's enrollments…</p>}
      {record.status === 'ready' && record.terms.length === 0 && <div className="empty-state"><h3>No enrollments on record</h3><p>Documents can only be issued for recorded enrollment terms.</p></div>}
      {record.status === 'ready' && record.terms.length > 0 && <>
        <div className="rs-grid">
          <label>Document type<select value={documentType} onChange={event => setDocumentType(event.target.value)}>
            {documentTypes.map(([value, name]) => <option key={value} value={value}>{name}</option>)}
          </select></label>
          <label>Document policy<select value={policyId} onChange={event => setPolicyId(event.target.value)}>
            <option value="">{candidates.length ? 'Choose a policy' : 'No policy for this program'}</option>
            {candidates.map(item => <option key={item.id} value={item.id}>{policyName(item, reference)}{item.approved_at ? '' : ' (not approved)'}</option>)}
          </select></label>
        </div>
        {candidates.length === 0 && <p className="ac-muted">Set up a {typeLabel[documentType]} policy for this student's program under Document policies first.</p>}
        {policy && <fieldset className="rs-checks">
          <legend className="ac-muted">Terms to include ({chosen.length} of {terms.length}). Only the selected terms appear on the document.</legend>
          {terms.map(term => <label key={term.enrollmentId}>
            <input type="checkbox" checked={chosen.includes(term.enrollmentId)}
              onChange={event => setChosen(current => (event.target.checked ? [...current, term.enrollmentId] : current.filter(id => id !== term.enrollmentId)))} />
            <span><b>{term.academicYear} · {term.term}</b> <span className="ac-muted">{term.program}{term.gradeLevel ? ` · Grade ${term.gradeLevel}` : ''}{term.section ? ` · ${term.section}` : ''} · {term.status}</span></span>
          </label>)}
        </fieldset>}
      </>}
    </section>}

    {selection && <section className="panel rs-section" aria-labelledby="verify-title">
      <header>
        <h2 id="verify-title">3. Verification</h2>
        <div className="rs-actions">
          {check.status === 'ready' && (result.eligible ? <StatusChip tone="success">Ready to issue</StatusChip> : <StatusChip tone="blocked">Blocked</StatusChip>)}
          <Button variant="secondary" onClick={() => setRevision(value => value + 1)} disabled={check.status === 'loading'}>Check again</Button>
        </div>
      </header>
      {check.status === 'loading' && <p role="status">Running checks…</p>}
      <Failure error={check.error} />
      {check.status === 'ready' && (reasons.length === 0
        ? <p>Every check passed: grades are finalized, credentials are verified and the SO information matches the approved policy.</p>
        : <ul className="rs-reasons" aria-label="Blocking reasons">{reasons.map(reason => {
          const { title, detail } = describeBlocker(reason, termLabel);
          return <li key={reason}><strong>{title}</strong><span className="ac-muted">{detail}</span></li>;
        })}</ul>)}
      <div>
        <h3 className="ac-subheading">Special Order (SO) numbers</h3>
        <ul className="rs-so-list">{selection.enrollmentIds.map(id => <SpecialOrderEditor key={id} enrollmentId={id} label={termLabel(id) ?? `Enrollment ${id}`} onSaved={() => setRevision(value => value + 1)} />)}</ul>
      </div>
    </section>}

    {selection && <section className="panel rs-section" aria-labelledby="issue-title">
      <h2 id="issue-title">4. Preview and issue</h2>
      <p className="ac-muted">A preview is marked DRAFT and lists any blockers. An issued document is permanent and cannot be changed afterwards.</p>
      <div className="rs-actions">
        <Button variant="secondary" onClick={preview} disabled={Boolean(busy)}>{busy === 'preview' ? 'Preparing preview…' : 'Preview draft PDF'}</Button>
        <Button onClick={() => setConfirming(true)} disabled={Boolean(busy) || !result?.eligible || Boolean(issued)}>Issue document</Button>
      </div>
      {actionError && <div className="notice notice-error" role="alert">{actionError}</div>}
      {issued && <div className="rs-stack" role="status">
        <p className="notice">Issued {typeLabel[issued.document_type] ?? issued.document_type} on {formatTime(issued.generated_at)}.</p>
        <dl className="ac-fields">
          <div className="ac-field"><dt>Document ID</dt><dd className="rs-hash">{issued.id}</dd></div>
          <div className="ac-field"><dt>Issued</dt><dd>{formatTime(issued.generated_at)}</dd></div>
          <div className="ac-field"><dt>SHA-256</dt><dd className="rs-hash">{issued.sha256}</dd></div>
          <div className="ac-field"><dt>Policy</dt><dd>#{issued.policy_id}, version {issued.policy_version}</dd></div>
        </dl>
        <div className="rs-actions"><Button onClick={download} disabled={Boolean(busy)}>{busy === 'download' ? 'Downloading…' : 'Download PDF'}</Button></div>
      </div>}
    </section>}

    <Dialog open={confirming} onClose={() => setConfirming(false)} title="Issue this document?" actions={<>
      <Button variant="secondary" onClick={() => setConfirming(false)} disabled={busy === 'issue'}>Cancel</Button>
      <Button onClick={issue} disabled={busy === 'issue'}>{busy === 'issue' ? 'Issuing…' : 'Issue'}</Button>
    </>}>
      <p>The {typeLabel[policy?.document_type] ?? 'document'} for {student ? fullName(student) : 'this student'} will be issued with {chosen.length} {chosen.length === 1 ? 'term' : 'terms'}. Once issued it is permanent; later grade changes do not alter it.</p>
    </Dialog>
  </>;
}

const blankPolicy = { campusId: '', programId: '', documentType: 'FORM137', institutionName: '', institutionAddress: '', signatories: '', templateReference: '', soRequired: true, soPrefix: '', soMaxLength: '120' };
const policyToForm = policy => ({
  campusId: String(policy.campus_id), programId: String(policy.program_id), documentType: policy.document_type,
  institutionName: policy.institution_name, institutionAddress: policy.institution_address, signatories: policy.signatories,
  templateReference: policy.template_reference, soRequired: policy.so_required, soPrefix: policy.so_prefix ?? '', soMaxLength: String(policy.so_max_length),
});

function PolicyForm({ policy, reference, onSaved, onClose }) {
  const [form, setForm] = useState(() => (policy ? policyToForm(policy) : blankPolicy));
  const [approval, setApproval] = useState('');
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const set = (key, value) => setForm(current => ({ ...current, [key]: value }));

  async function save(event) {
    event.preventDefault();
    setBusy('save');
    setError('');
    const input = { ...form, campusId: Number(form.campusId), programId: Number(form.programId), soMaxLength: Number(form.soMaxLength), version: policy?.version };
    try {
      const saved = policy ? await updateDocumentPolicy(policy.id, input) : await createDocumentPolicy(input);
      onSaved(saved, policy?.approved_at ? 'Policy saved. Editing removed its approval, so approve it again before issuing.' : 'Policy saved.');
    } catch (failure) {
      setError(failure?.status === 409 ? 'This policy changed since you opened it. Close it and open it again.' : failure?.message || 'The policy could not be saved.');
    } finally {
      setBusy('');
    }
  }
  async function approve() {
    setBusy('approve');
    setError('');
    try {
      onSaved(await approveDocumentPolicy(policy.id, policy.version, approval), 'Policy approved.');
    } catch (failure) {
      setError(failure?.message || 'The policy could not be approved.');
    } finally {
      setBusy('');
    }
  }

  return <section className="panel rs-section" aria-labelledby="doc-policy-title">
    <header>
      <h2 id="doc-policy-title">{policy ? policyName(policy, reference) : 'New document policy'}</h2>
      <div className="rs-actions">{policy && (policy.approved_at ? <StatusChip tone="success">Approved</StatusChip> : <StatusChip tone="attention">Not approved</StatusChip>)}<Button variant="secondary" onClick={onClose}>Close</Button></div>
    </header>
    {policy?.approved_at && <p className="notice">Approved {formatTime(policy.approved_at)} with reference <b>{policy.approval_reference}</b>. Saving any change removes the approval.</p>}
    <form className="rs-stack" onSubmit={save}>
      <fieldset className="rs-plain" disabled={Boolean(busy)}>
        <div className="rs-grid">
          <label>Campus<select value={form.campusId} disabled={Boolean(policy)} onChange={event => set('campusId', event.target.value)} required>
            <option value="">Choose a campus</option>{reference.campuses.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select></label>
          <label>Program<select value={form.programId} disabled={Boolean(policy)} onChange={event => set('programId', event.target.value)} required>
            <option value="">Choose a program</option>{reference.programs.map(item => <option key={item.id} value={item.id}>{item.code} · {item.name}</option>)}
          </select></label>
          <label>Document type<select value={form.documentType} disabled={Boolean(policy)} onChange={event => set('documentType', event.target.value)}>
            {documentTypes.map(([value, name]) => <option key={value} value={value}>{name}</option>)}
          </select></label>
        </div>
        <label className="rs-field">Institution name<input value={form.institutionName} maxLength={200} onChange={event => set('institutionName', event.target.value)} required /></label>
        <label className="rs-field">Institution address<input value={form.institutionAddress} maxLength={500} onChange={event => set('institutionAddress', event.target.value)} required /></label>
        <label className="rs-field">Signatories (one per line)<textarea rows={3} maxLength={1000} value={form.signatories} onChange={event => set('signatories', event.target.value)} required /></label>
        <label className="rs-field">Template reference<input value={form.templateReference} maxLength={300} placeholder="Registrar-reviewed layout and version" onChange={event => set('templateReference', event.target.value)} required /></label>
        <div className="rs-grid">
          <label className="rs-checks"><span><input type="checkbox" checked={form.soRequired} onChange={event => set('soRequired', event.target.checked)} /> SO number required</span></label>
          <label>SO prefix<input value={form.soPrefix} maxLength={60} placeholder="Optional" onChange={event => set('soPrefix', event.target.value)} /></label>
          <label>SO maximum length<input type="number" min="1" max="120" value={form.soMaxLength} onChange={event => set('soMaxLength', event.target.value)} required /></label>
        </div>
        <div className="rs-actions"><Button type="submit">{busy === 'save' ? 'Saving…' : 'Save policy'}</Button></div>
      </fieldset>
    </form>
    {policy && !policy.approved_at && <div className="rs-stack rs-divider">
      <h3 className="ac-subheading">Approve</h3>
      <p className="ac-muted">Confirm the school has reviewed this layout and SO format, then record the sign-off reference.</p>
      <label className="rs-field">Registrar approval reference<input value={approval} maxLength={300} onChange={event => setApproval(event.target.value)} /></label>
      <div className="rs-actions"><Button onClick={approve} disabled={!approval.trim() || Boolean(busy)}>{busy === 'approve' ? 'Approving…' : 'Approve policy'}</Button></div>
    </div>}
    {error && <div className="notice notice-error" role="alert">{error}</div>}
  </section>;
}

function PolicyList({ policies, reference, status, error, reload, onSaved }) {
  const [open, setOpen] = useState(null);
  const [notice, setNotice] = useState('');
  return <>
    <section className="panel" aria-labelledby="doc-policies-title">
      <div className="ac-report-head">
        <h2 id="doc-policies-title">Document policies</h2>
        <div className="ac-filter-actions"><Button variant="secondary" onClick={() => { setNotice(''); setOpen({ id: 'new' }); }}>New policy</Button></div>
      </div>
      <Failure error={error} reload={reload} />
      {status === 'loading' && <p role="status" className="rs-pad">Loading policies…</p>}
      {status === 'ready' && policies.length === 0 && <div className="empty-state"><h3>No document policies yet</h3><p>Create one per campus, program and document type.</p></div>}
      {policies.length > 0 && <div className="setup-table-wrap"><table className="ac-table">
        <thead><tr><th scope="col">Policy</th><th scope="col">Approval</th><th scope="col">SO</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
        <tbody>{policies.map(item => <tr key={item.id}>
          <td data-label="Policy"><strong>{policyName(item, reference)}</strong><small>{item.template_reference}</small></td>
          <td data-label="Approval">{item.approved_at ? <StatusChip tone="success">Approved</StatusChip> : <StatusChip tone="attention">Not approved</StatusChip>}</td>
          <td data-label="SO">{item.so_required ? `Required${item.so_prefix ? `, starts with ${item.so_prefix}` : ''}, up to ${item.so_max_length}` : 'Not required'}</td>
          <td data-label="Actions"><div className="ac-actions"><Button variant="secondary" onClick={() => { setNotice(''); setOpen(item); }}>Open</Button></div></td>
        </tr>)}</tbody>
      </table></div>}
    </section>
    {notice && <p className="notice" role="status">{notice}</p>}
    {open && <PolicyForm key={`${open.id}-${open.version ?? 0}`} policy={open.id === 'new' ? null : open} reference={reference}
      onClose={() => setOpen(null)} onSaved={(saved, message) => { setOpen(saved); setNotice(message); onSaved(); }} />}
  </>;
}

export default function OfficialDocuments() {
  const reference = useReference();
  const [tab, setTab] = useState('issue');
  const [policies, setPolicies] = useState({ status: 'loading', items: [], error: '' });
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    setPolicies(current => ({ ...current, status: 'loading', error: '' }));
    loadDocumentPolicies()
      .then(items => active && setPolicies({ status: 'ready', items: Array.isArray(items) ? items : items?.items ?? [], error: '' }))
      .catch(failure => active && setPolicies({ status: 'error', items: [], error: failure?.message || 'Document policies could not be loaded.' }));
    return () => { active = false; };
  }, [revision]);

  const reload = () => setRevision(value => value + 1);
  return <div className="enrollment-workspace ac-screen rs-screen">
    <header className="page-heading">
      <p className="eyebrow">Registrar</p>
      <h1>Official Documents</h1>
      <p>Verify, preview and issue Form 137 and Transcript of Records documents, and record DepEd Special Order numbers.</p>
    </header>
    <div className="rs-tabs" role="tablist" aria-label="Official document views">
      <button type="button" role="tab" aria-selected={tab === 'issue'} onClick={() => setTab('issue')}>Issue a document</button>
      <button type="button" role="tab" aria-selected={tab === 'policies'} onClick={() => setTab('policies')}>Document policies</button>
    </div>
    {tab === 'issue'
      ? <IssueDocument policies={policies.items} reference={reference} />
      : <PolicyList policies={policies.items} reference={reference} status={policies.status} error={policies.error} reload={reload} onSaved={reload} />}
  </div>;
}
