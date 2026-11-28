// ICI2-98: the requirements checklist as a right-hand drawer. Credentials are edited inline and follow-up contacts
// open in a popover, so no modal is ever stacked on another one.
import { useRef, useState } from 'react';
import { Link } from 'react-router';
import { Failure, History, useResource } from '../EnrollmentWorkspace.jsx';
import { Button, Drawer, Popover, ProgressMeter, StatusChip } from '../ui/components.jsx';
import { send } from './enrollmentApi.js';
import './requirements-drawer.css';

const statusOptions = ['submitted', 'missing', 'pending', 'invalid'];
const statusTone = { submitted: 'success', pending: 'attention', missing: 'blocked', invalid: 'blocked' };
const overallTone = { Complete: 'success', Incomplete: 'blocked', Pending: 'attention' };
const label = value => (value ? value[0].toUpperCase() + value.slice(1) : '—');

function CredentialRow({ item, applicationId, onSaved }) {
  const [editing, setEditing] = useState(false);
  const [status, setStatus] = useState(item.status);
  const [notes, setNotes] = useState(item.notes ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const editButton = useRef(null);

  function stop() {
    setEditing(false);
    setStatus(item.status);
    setNotes(item.notes ?? '');
    setError('');
    // Return focus to the row's Edit button once it is back on screen.
    setTimeout(() => editButton.current?.focus(), 0);
  }
  async function save(event) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await send(`/api/applications/${applicationId}/requirements/${encodeURIComponent(item.code)}`, 'PUT', { status, notes, version: item.version });
      setEditing(false);
      onSaved('Document verification saved.');
    } catch (failure) {
      // Keep the draft so the registrar can reload and try again without retyping.
      setError(failure.message);
    } finally {
      setBusy(false);
    }
  }

  return <li className={editing ? 'rq-row is-editing' : 'rq-row'}>
    <div className="rq-row-head">
      <div className="rq-row-name">
        <strong>{item.name}</strong>
        <small>{item.mandatory ? 'Mandatory' : 'Optional'}{item.verifiedAt ? ` · Verified ${new Date(item.verifiedAt).toLocaleDateString()}` : ''}</small>
      </div>
      <StatusChip tone={statusTone[item.status] ?? 'neutral'}>{label(item.status)}</StatusChip>
      {!editing && <Button ref={editButton} variant="secondary" className="rq-edit" aria-label={`Edit ${item.code}`} onClick={() => setEditing(true)}>Edit</Button>}
    </div>
    {!editing && item.notes && <p className="rq-note">{item.notes}</p>}
    {editing && <form className="rq-form" onSubmit={save} onKeyDown={event => { if (event.key === 'Escape' && !busy) { event.stopPropagation(); event.preventDefault(); stop(); } }}>
      <label>Status: {item.code}
        <select autoFocus value={status} disabled={busy} onChange={event => setStatus(event.target.value)}>
          {statusOptions.map(value => <option key={value} value={value}>{label(value)}</option>)}
        </select>
      </label>
      <label>Verification notes: {item.code}
        <textarea rows={2} maxLength={2000} value={notes} disabled={busy} onChange={event => setNotes(event.target.value)} />
      </label>
      <Failure error={error} />
      <div className="rq-form-actions">
        <Button type="submit" disabled={busy} aria-label={`Save ${item.code}`}>{busy ? 'Saving…' : 'Save'}</Button>
        <Button variant="secondary" disabled={busy} aria-label={`Cancel ${item.code}`} onClick={stop}>Cancel</Button>
      </div>
    </form>}
  </li>;
}

/** Rendered only while the popover is open, so guardian contacts load on demand. */
function Contacts({ application, studentId }) {
  const student = useResource(studentId ? `/api/students/${studentId}` : null);
  const [copied, setCopied] = useState('');
  const guardians = studentId
    ? (student.data?.guardians ?? []).map(g => ({ name: g.full_name, relationship: g.relationship, phone: g.phone }))
    : (application.guardians ?? []).map(g => ({ name: g.fullName, relationship: g.relationship, phone: g.phone }));
  const phone = application.phone || student.data?.phone;
  const email = application.email || student.data?.email;
  const numbers = [phone, ...guardians.map(g => g.phone)].filter(Boolean);

  async function copy() {
    try {
      await navigator.clipboard.writeText(numbers.join('\n'));
      setCopied(`Copied ${numbers.length === 1 ? '1 number' : `${numbers.length} numbers`}.`);
    } catch {
      setCopied('Copying is blocked in this browser. Select the numbers instead.');
    }
  }

  return <div className="rq-contacts">
    <h3>{application.name}</h3>
    <dl>
      <div><dt>Phone</dt><dd>{phone || 'Not recorded'}</dd></div>
      <div><dt>Email</dt><dd>{email || 'Not recorded'}</dd></div>
    </dl>
    <h3>Parent / guardian</h3>
    <Failure error={student.error} reload={student.reload} />
    {student.loading && <p role="status">Loading guardian contacts…</p>}
    {guardians.length > 0
      ? <ul>{guardians.map((g, index) => <li key={index}><strong>{g.name}</strong><span>{g.relationship} · {g.phone || 'Phone not recorded'}</span></li>)}</ul>
      : !student.loading && !student.error && <p>No guardian contact recorded.</p>}
    <Button variant="secondary" disabled={!numbers.length} onClick={copy}>Copy numbers</Button>
    {copied && <p className="rq-muted" role="status">{copied}</p>}
    <p className="rq-muted">Contact the applicant or guardian yourself. This screen does not send a message.</p>
  </div>;
}

export default function RequirementsDrawer({ id, onClose, onSaved }) {
  const detail = useResource(`/api/intake/applications/${id}`);
  const [revision, setRevision] = useState(0);
  const [notice, setNotice] = useState('');
  const data = detail.data;
  const workflow = data?.workflow;
  const checklist = workflow?.checklist;
  const summary = checklist?.summary;
  const application = data ? { ...workflow.application, name: `${data.firstName} ${data.lastName}`, phone: workflow.application?.phone ?? data.phone, email: workflow.application?.email ?? data.email, guardians: data.profile?.guardians } : null;

  function saved(message) {
    setNotice(message);
    setRevision(value => value + 1);
    detail.reload();
    onSaved();
  }

  return <Drawer open onClose={onClose} title="Requirements Checklist" className="rq-drawer"
    footer={<>
      <Button variant="secondary" onClick={detail.reload} disabled={detail.loading}>Reload checklist</Button>
      <Link className="ui-btn ui-btn-primary" to={`/enrollment/attendance?applicationId=${id}`}>Continue to attendance</Link>
    </>}>
    <Failure error={detail.error} reload={detail.reload} />
    {detail.loading && !data && <p role="status">Loading checklist…</p>}
    {data && checklist && <div className="rq-body">
      <div className="rq-who">
        <div>
          <p className="rq-name">{data.firstName} {data.lastName}</p>
          <p className="rq-muted">{label(data.category)}{data.lrn ? ` · LRN ${data.lrn}` : ''}</p>
        </div>
        <Popover label="Contact for follow-up" align="end" className="rq-contact">
          <Contacts application={application} studentId={checklist.studentId} />
        </Popover>
      </div>

      <div className="rq-progress">
        <ProgressMeter label="Mandatory documents" value={summary.submitted} max={summary.mandatoryTotal} valueText={`${summary.submitted} of ${summary.mandatoryTotal} submitted`} />
        <StatusChip tone={overallTone[summary.overallStatus] ?? 'neutral'}>{summary.overallStatus}</StatusChip>
      </div>

      {notice && <p className="notice" role="status">{notice}</p>}
      <h3 className="rq-heading">Credentials</h3>
      <p className="rq-muted">Verify the original before marking it Submitted. Optional documents do not block completeness.</p>
      <ul className="rq-list">
        {checklist.items.map(item => <CredentialRow key={`${item.code}-${item.version}`} item={item} applicationId={checklist.applicationId} onSaved={saved} />)}
      </ul>
      <History key={checklist.applicationId} id={checklist.applicationId} revision={revision} />
    </div>}
  </Drawer>;
}
