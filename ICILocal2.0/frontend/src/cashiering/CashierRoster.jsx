import { useEffect, useMemo, useState } from 'react';
import { Failure, Pager } from '../EnrollmentWorkspace.jsx';
import { StatTile, StatusChip } from '../ui/components.jsx';
import { emptyRosterFilters, exportRoster, loadRoster, voucherGroups } from './cashierApi.js';
import '../academic/academic-screens.css';
import '../academic/records-screens.css';

const groupLabel = { QVR: 'QVR', ESC: 'ESC', ALS: 'ALS', OTHER: 'Other vouchers', UNCLASSIFIED: 'Unclassified' };
const groupTone = { QVR: 'info', ESC: 'info', ALS: 'info', OTHER: 'neutral', UNCLASSIFIED: 'attention' };

function formatTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/**
 * The cashier role has no access to the shared report filter catalog, so the campus, term and section
 * choices come from the roster rows themselves. Every page loaded adds to what can be picked.
 */
function mergeChoices(current, items) {
  const next = { campuses: new Map(current.campuses), terms: new Map(current.terms), sections: new Map(current.sections) };
  for (const row of items ?? []) {
    if (row.campusId) next.campuses.set(String(row.campusId), row.campus);
    if (row.termId) next.terms.set(String(row.termId), { label: `${row.academicYear} · ${row.term}`, campusId: String(row.campusId) });
    if (row.sectionId) next.sections.set(String(row.sectionId), { label: row.section, termId: String(row.termId), campusId: String(row.campusId) });
  }
  return next;
}

export default function CashierRoster() {
  const [draft, setDraft] = useState(emptyRosterFilters);
  const [applied, setApplied] = useState(emptyRosterFilters);
  const [page, setPage] = useState(0);
  const [roster, setRoster] = useState({ status: 'loading', data: null, error: '' });
  const [choices, setChoices] = useState({ campuses: new Map(), terms: new Map(), sections: new Map() });
  const [codes, setCodes] = useState([]);
  const [revision, setRevision] = useState(0);
  const [exporting, setExporting] = useState(false);
  const [exportNote, setExportNote] = useState({ error: '', done: '' });

  // A wide first read seeds the filter choices.
  useEffect(() => {
    let active = true;
    loadRoster(emptyRosterFilters, 0, { size: 100 }).then(data => {
      if (!active) return;
      setChoices(current => mergeChoices(current, data?.items));
      setCodes(current => [...new Set([...current, ...(data?.vouchers ?? []).map(item => item.voucher)])]);
    }).catch(() => { /* the main read below reports failures */ });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setRoster(current => ({ ...current, status: 'loading', error: '' }));
    loadRoster(applied, page, { signal: controller.signal }).then(data => {
      setRoster({ status: 'ready', data, error: '' });
      setChoices(current => mergeChoices(current, data?.items));
      setCodes(current => [...new Set([...current, ...(data?.vouchers ?? []).map(item => item.voucher)])]);
    }).catch(failure => {
      if (failure?.name === 'AbortError') return;
      setRoster({ status: 'error', data: null, error: failure?.message || 'The roster could not be loaded.' });
    });
    return () => controller.abort();
  }, [applied, page, revision]);

  const terms = useMemo(() => [...choices.terms].filter(([, term]) => !draft.campusId || term.campusId === draft.campusId), [choices.terms, draft.campusId]);
  const sections = useMemo(() => [...choices.sections].filter(([, section]) =>
    (!draft.campusId || section.campusId === draft.campusId) && (!draft.termId || section.termId === draft.termId)), [choices.sections, draft.campusId, draft.termId]);
  const voucherChoices = useMemo(() => {
    const fixed = ['QVR', 'ESC', 'ALS'];
    const others = codes.filter(code => !fixed.includes(code) && code !== 'UNCLASSIFIED').sort();
    return [...fixed, ...others, 'UNCLASSIFIED'];
  }, [codes]);

  function set(key, value) {
    setDraft(current => {
      const next = { ...current, [key]: value };
      if (key === 'campusId') { next.termId = ''; next.sectionId = ''; }
      if (key === 'termId') next.sectionId = '';
      return next;
    });
  }
  function apply(event) {
    event.preventDefault();
    setApplied(draft);
    setPage(0);
    setExportNote({ error: '', done: '' });
  }
  function reset() {
    setDraft(emptyRosterFilters);
    setApplied(emptyRosterFilters);
    setPage(0);
    setExportNote({ error: '', done: '' });
  }
  async function exportCsv() {
    setExporting(true);
    setExportNote({ error: '', done: '' });
    try {
      const name = await exportRoster(applied);
      setExportNote({ error: '', done: `Downloaded ${name}.` });
    } catch (failure) {
      setExportNote({ error: failure.message, done: '' });
    } finally {
      setExporting(false);
    }
  }

  const data = roster.data;
  const rows = data?.items ?? [];
  const groups = data?.voucherGroups ?? {};
  return <div className="enrollment-workspace ac-screen rs-screen">
    <header className="page-heading">
      <p className="eyebrow">Cashiering reference</p>
      <h1>Enrolled Student Reference</h1>
      <p>Confirmed enrollments grouped by voucher, for billing reference. This roster is read-only.</p>
    </header>

    <form className="panel" aria-label="Roster filters" onSubmit={apply}>
      <div className="ac-filters">
        <label>Campus<select value={draft.campusId} onChange={event => set('campusId', event.target.value)}>
          <option value="">All campuses</option>
          {[...choices.campuses].map(([id, name]) => <option key={id} value={id}>{name}</option>)}
        </select></label>
        <label>Term<select value={draft.termId} onChange={event => set('termId', event.target.value)}>
          <option value="">All terms</option>
          {terms.map(([id, term]) => <option key={id} value={id}>{term.label}</option>)}
        </select></label>
        <label>Section<select value={draft.sectionId} onChange={event => set('sectionId', event.target.value)}>
          <option value="">All sections</option>
          {sections.map(([id, section]) => <option key={id} value={id}>{section.label}</option>)}
        </select></label>
        <label>Voucher type<select value={draft.voucher} onChange={event => set('voucher', event.target.value)}>
          <option value="">All vouchers</option>
          {voucherChoices.map(code => <option key={code} value={code}>{code === 'UNCLASSIFIED' ? 'Unclassified' : code}</option>)}
        </select></label>
        <div className="ac-filter-actions">
          <button type="submit" className="ui-btn ui-btn-primary">Apply filters</button>
          <button type="button" className="ui-btn ui-btn-secondary" onClick={reset}>Reset</button>
        </div>
      </div>
    </form>

    <Failure error={roster.error} reload={() => setRevision(value => value + 1)} />
    {roster.status === 'loading' && <p role="status">Loading roster…</p>}
    {data && <>
      <div className="ac-stats rs-stats-6" aria-label="Voucher counts">
        <StatTile label="Total confirmed" value={data.total ?? 0} />
        {voucherGroups.map(group => <StatTile key={group} label={groupLabel[group]} value={groups[group] ?? 0} />)}
      </div>
      <section className="panel" aria-labelledby="roster-title">
        <div className="ac-report-head">
          <div><h2 id="roster-title">Roster</h2>{data.asOf && <p className="ac-muted rs-flush">As of {formatTime(data.asOf)}</p>}</div>
          <div className="ac-filter-actions">
            <button type="button" className="ui-btn ui-btn-secondary" disabled={exporting} onClick={exportCsv}>{exporting ? 'Preparing CSV…' : 'Export CSV'}</button>
          </div>
        </div>
        {exportNote.error && <div className="notice notice-error" role="alert">{exportNote.error}</div>}
        {exportNote.done && <p className="notice" role="status">{exportNote.done}</p>}
        {rows.length === 0
          ? <div className="empty-state"><h3>No confirmed enrollments match these filters</h3><p>Try widening the filters, or reset them.</p></div>
          : <>
            <div className="setup-table-wrap">
              <table className="ac-table">
                <thead><tr><th scope="col">Student</th><th scope="col">Campus and term</th><th scope="col">Class</th><th scope="col">Voucher</th><th scope="col">Confirmed</th></tr></thead>
                <tbody>{rows.map(row => <tr key={row.enrollmentId}>
                  <td data-label="Student"><strong>{row.name}</strong><small>{row.studentNumber}</small></td>
                  <td data-label="Campus and term"><strong>{row.campus}</strong><small>{row.academicYear} · {row.term}</small></td>
                  <td data-label="Class"><strong>{row.gradeLevel || '—'}{row.section ? ` · ${row.section}` : ''}</strong><small>{row.program}</small></td>
                  <td data-label="Voucher"><StatusChip tone={groupTone[row.voucherGroup] ?? 'neutral'}>{row.voucher === 'UNCLASSIFIED' ? 'Unclassified' : row.voucher}</StatusChip></td>
                  <td data-label="Confirmed">{formatTime(row.confirmedAt)}</td>
                </tr>)}</tbody>
              </table>
            </div>
            <div className="ac-pager"><Pager page={page} total={data.total ?? 0} onChange={setPage} disabled={roster.status === 'loading'} /></div>
          </>}
      </section>
    </>}
  </div>;
}
