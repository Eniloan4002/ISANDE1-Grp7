// ICI2-99: the Confirm enrollment dialog and the First-Week Attendance table.
import { useState } from 'react';
import { Link } from 'react-router';
import { apiRequest } from '../api.js';
import { Button, Dialog, StatusChip } from '../ui/components.jsx';
import './confirm-enrollment.css';

const docTone = { Complete: 'success', Incomplete: 'blocked', Pending: 'attention' };

/** The one thing to do next on the attendance screen. `select` opens the record below; `confirm` also opens the dialog. */
export function attendanceStep(row) {
  const id = row.id;
  if (row.status === 'enrolled') return row.sectionId
    ? { label: 'View placement', to: `/enrollment/enrolled?applicationId=${id}` }
    : { label: 'Place in section', to: `/enrollment/enrolled?applicationId=${id}` };
  if (row.status === 'rejected' || row.status === 'withdrawn') return { label: 'View record', to: `/enrollment/applicants/${id}` };
  if (row.status !== 'pre_enlisted') return { label: 'Review application', to: `/enrollment/applicants/${id}` };
  if (row.requirementsStatus !== 'Complete') return { label: 'Open checklist', to: `/enrollment/requirements?applicationId=${id}` };
  if (!row.attendancePresent) return { label: 'Record attendance', action: 'select' };
  return { label: 'Confirm enrollment', action: 'confirm' };
}

export function AttendanceTable({ rows, selected, onRecord, onConfirm }) {
  return <div className="ce-table-wrap"><table className="ce-table">
    <thead><tr><th scope="col">Applicant</th><th scope="col">Program</th><th scope="col">Documents</th><th scope="col">Attendance</th><th scope="col"><span className="ce-hidden">Next step</span></th></tr></thead>
    <tbody>{rows.map(row => {
      const step = attendanceStep(row);
      const label = `${step.label}: ${row.name}`;
      const enrolled = row.status === 'enrolled';
      return <tr key={row.id} className={String(row.id) === selected ? 'is-selected' : undefined} aria-current={String(row.id) === selected ? 'true' : undefined}>
        <td data-label="Applicant"><div><strong>{row.name}</strong><small>{row.lrn ? `LRN ${row.lrn}` : 'No LRN'}</small></div></td>
        <td data-label="Program"><div><span className="ce-line">{row.program}{row.gradeLevel ? ` · Grade ${row.gradeLevel}` : ''}</span><small>{row.campus}{row.term ? ` · ${row.term}` : ''}</small></div></td>
        <td data-label="Documents"><StatusChip tone={docTone[row.requirementsStatus] ?? 'neutral'}>{row.requirementsStatus ?? '—'}</StatusChip></td>
        <td data-label="Attendance">{row.attendancePresent || enrolled
          ? <StatusChip tone="success">Present</StatusChip>
          : <StatusChip tone="attention">Not recorded</StatusChip>}</td>
        <td className="ce-action">{step.to
          ? <Link className="ui-btn ui-btn-secondary" data-row-action={row.id} to={step.to} aria-label={label}>{step.label}</Link>
          : <Button variant={step.action === 'confirm' ? 'primary' : 'secondary'} data-row-action={row.id} aria-label={label}
            onClick={() => (step.action === 'confirm' ? onConfirm(row) : onRecord(row))}>{step.label}</Button>}</td>
      </tr>;
    })}</tbody>
  </table></div>;
}

function Check({ ok, children, detail }) {
  return <li className={ok ? 'ce-check is-ok' : 'ce-check'}>
    <span className="ce-check-mark" aria-hidden="true">{ok ? '✓' : '✕'}</span>
    <span><span className="ce-hidden">{ok ? 'Passed: ' : 'Not met: '}</span>{children}{!ok && detail && <small>{detail}</small>}</span>
  </li>;
}

/**
 * Shows who is being confirmed and the three gates the server enforces. The server rechecks everything on confirm;
 * if it refuses, the reason stays in this dialog and the checks are reloaded.
 */
export function ConfirmEnrollmentDialog({ detail, programName, termName, onClose, onConfirmed, onRefused }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const app = detail.application;
  const documents = detail.checklist?.summary?.overallStatus === 'Complete';
  const gateMessages = ['Submit every mandatory credential.', 'Record present orientation / first-week attendance.', 'Resolve open duplicate cases first.'];
  const otherBlockers = (detail.blockers ?? []).filter(item => !gateMessages.includes(item));

  async function confirm() {
    setBusy(true);
    setError('');
    try {
      await apiRequest(`/api/applications/${app.id}/confirm-enrollment`, { method: 'POST', body: JSON.stringify({ version: app.version }) });
      onConfirmed(app);
    } catch (failure) {
      setError(failure.message || 'The enrollment could not be confirmed.');
      onRefused?.();
    } finally {
      setBusy(false);
    }
  }

  return <Dialog open title={`Confirm enrollment for ${app.name}?`} onClose={() => { if (!busy) onClose(); }}
    actions={<>
      <Button variant="secondary" disabled={busy} onClick={onClose}>Cancel</Button>
      <Button disabled={busy || !detail.canConfirm} onClick={confirm}>{busy ? 'Confirming…' : 'Confirm enrollment'}</Button>
    </>}>
    <dl className="ce-facts">
      <div><dt>Program</dt><dd>{programName || '—'}</dd></div>
      <div><dt>Term</dt><dd>{termName || '—'}</dd></div>
    </dl>
    <ul className="ce-checks" aria-label="Enrollment checks">
      <Check ok={documents} detail="Every mandatory credential must be marked Submitted.">Documents complete</Check>
      <Check ok={Boolean(detail.attendancePresent)} detail="Record a Present day inside the approved first-week dates.">First-week attendance recorded</Check>
      <Check ok={!detail.duplicateOpen} detail="Resolve the open duplicate case first.">No open duplicate flags</Check>
    </ul>
    {otherBlockers.length > 0 && <ul className="ce-other">{otherBlockers.map(item => <li key={item}>{item}</li>)}</ul>}
    <p className="ce-consequence">{app.name} becomes <strong>enrolled</strong> and moves to Enrolled &amp; Assigned for section placement. The server checks these again when you confirm.</p>
    {error && <div className="notice notice-error" role="alert">{error}</div>}
  </Dialog>;
}
