// ICI2-100: Enrolled & Assigned as two panes. Students on the left, the sections they can join on the right.
import { useEffect, useId, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { apiRequest } from '../api.js';
import { Failure, Pager, useDebouncedValue, useResource } from '../EnrollmentWorkspace.jsx';
import { Button, Dialog, ProgressMeter, StatusChip } from '../ui/components.jsx';
import './placement.css';

const tabs = [
  { key: 'unplaced', label: 'Needs a section' },
  { key: 'placed', label: 'Placed' },
  { key: 'all', label: 'All confirmed' },
];
const emptyFilters = { search: '', campusId: '', termId: '', programId: '', district: '', transportRoute: '' };

function listQuery(filters, placement, page, size = 25) {
  const params = new URLSearchParams({ status: 'enrolled', page: String(page), size: String(size) });
  for (const [key, value] of Object.entries({ ...filters, placement: placement === 'all' ? '' : placement })) if (value) params.set(key, value);
  return `/api/enrollment/applications?${params}`;
}

/** Almost full: at most a tenth of the seats (and at least two) remain. */
export function seatState(section) {
  if (section.available <= 0) return 'full';
  return section.available <= Math.max(2, Math.ceil(section.capacity * 0.1)) ? 'almost' : 'open';
}

function SectionOption({ section, current, checked, name, onChange }) {
  const state = seatState(section);
  const disabled = state === 'full' || current;
  return <label className={['pl-card', checked && 'is-checked', disabled && 'is-disabled'].filter(Boolean).join(' ')}>
    <input type="radio" name={name} value={section.id} checked={checked} disabled={disabled} onChange={() => onChange(section)} />
    <span className="pl-card-head">
      <strong>{section.name}</strong>
      {current ? <StatusChip tone="info">Current section</StatusChip>
        : state === 'full' ? <StatusChip tone="blocked">Full</StatusChip>
          : state === 'almost' ? <StatusChip tone="attention">Almost full</StatusChip> : null}
    </span>
    <span className="pl-card-meta">{section.adviser ? `Adviser: ${section.adviser}` : 'No adviser assigned'}</span>
    <span className="pl-card-meta">Grade {String(section.gradeLevel).replace(/^Grade\s+/i, '')}{section.room ? ` · Room ${section.room}` : ''}</span>
    <ProgressMeter label="Seats" value={section.occupied} max={section.capacity} valueText={`${section.occupied} of ${section.capacity} seats used`} />
  </label>;
}

function PlacementPane({ id, onPlaced, onSkip, canSkip }) {
  const detail = useResource(`/api/applications/${id}/enrollment`);
  const app = detail.data?.application;
  const enrollment = detail.data?.enrollment;
  const sections = useResource(app ? `/api/sections?campusId=${app.campusId}&termId=${app.termId}&programId=${app.programId}&page=0&size=100` : null);
  const [target, setTarget] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [removing, setRemoving] = useState(false);
  const radioName = useId();

  async function place(sectionId) {
    setBusy(true);
    setError('');
    try {
      await apiRequest(`/api/enrollments/${enrollment.id}/section`, { method: 'PUT', body: JSON.stringify({ sectionId, version: enrollment.version }) });
      const placedIn = sectionId ? (sections.data?.items ?? []).find(item => item.id === sectionId)?.name ?? target?.name : null;
      setTarget(null);
      setRemoving(false);
      sections.reload();
      detail.reload();
      onPlaced(app, placedIn);
    } catch (failure) {
      // The server has the final say on capacity and versions; show why and refresh the live figures.
      setError(failure.message || 'The placement could not be saved.');
      setRemoving(false);
      sections.reload();
      detail.reload();
    } finally {
      setBusy(false);
    }
  }

  if (detail.error) return <Failure error={detail.error} reload={detail.reload} />;
  if (!detail.data) return <p role="status">Loading student…</p>;
  if (enrollment?.status !== 'enrolled') return <div className="notice">Confirm enrollment before placing this student in a section.</div>;
  const items = sections.data?.items ?? [];
  // Use the live figures: a section that filled up since it was chosen is no longer a valid choice.
  const live = items.find(item => item.id === target?.id);
  const chosen = live && seatState(live) !== 'full' && live.id !== enrollment.sectionId ? live : null;

  return <div className="pl-pane-body">
    <header className="pl-student">
      <div>
        <h2 id="pl-student-name">{app.name}</h2>
        <p className="pl-muted">Current section: <strong>{enrollment.section ?? 'None yet'}</strong></p>
      </div>
      {enrollment.sectionId && <Button variant="secondary" disabled={busy} onClick={() => setRemoving(true)}>Remove from section</Button>}
    </header>
    {error && <div className="notice notice-error" role="alert">{error}</div>}
    <Failure error={sections.error} reload={sections.reload} />
    {sections.loading && !sections.data && <p role="status">Loading sections…</p>}
    {sections.data && (items.length
      ? <fieldset className="pl-cards" aria-labelledby="pl-student-name pl-sections-label">
        <legend id="pl-sections-label" className="pl-legend">Sections for this campus, term and program</legend>
        {items.map(section => <SectionOption key={section.id} section={section} name={radioName} current={section.id === enrollment.sectionId}
          checked={chosen?.id === section.id} onChange={value => { setTarget(value); setError(''); }} />)}
      </fieldset>
      : <p className="notice">No sections are set up for this campus, term and program yet.</p>)}
    <div className="pl-actions">
      <Button disabled={!chosen || busy} onClick={() => place(chosen.id)}>{busy ? 'Placing…' : chosen ? `Place in ${chosen.name}` : 'Choose a section'}</Button>
      <Button variant="secondary" disabled={busy || !canSkip} onClick={onSkip}>Skip</Button>
    </div>
    {removing && <Dialog open title={`Remove ${app.name} from ${enrollment.section}?`} onClose={() => setRemoving(false)}
      actions={<><Button variant="secondary" disabled={busy} onClick={() => setRemoving(false)}>Cancel</Button><Button disabled={busy} onClick={() => place(null)}>Remove from section</Button></>}>
      <p>The student moves back to Needs a section. Existing class memberships can block this; the server will say if they do.</p>
    </Dialog>}
  </div>;
}

export default function PlacementWorkspace() {
  const [params, setParams] = useSearchParams();
  const selected = params.get('applicationId');
  const [tab, setTab] = useState('unplaced');
  const [filters, setFilters] = useState(emptyFilters);
  const [moreFilters, setMoreFilters] = useState(false);
  const [page, setPage] = useState(0);
  const [notice, setNotice] = useState('');
  const reference = useResource('/api/enrollment/reference-data');
  const search = useDebouncedValue(filters.search);
  const applied = { ...filters, search };
  const list = useResource(listQuery(applied, tab, page));
  const counts = { unplaced: useResource(listQuery(applied, 'unplaced', 0, 1)), placed: useResource(listQuery(applied, 'placed', 0, 1)), all: useResource(listQuery(applied, 'all', 0, 1)) };
  const rows = list.data?.items ?? [];
  const pane = useRef(null);

  const change = key => event => { setFilters(current => ({ ...current, [key]: event.target.value })); setPage(0); };
  const select = id => { setParams(id ? { applicationId: String(id) } : {}); setNotice(''); };
  const neighbour = () => {
    const index = rows.findIndex(row => String(row.id) === selected);
    return rows[index + 1] ?? (index > 0 ? rows[index - 1] : null);
  };
  useEffect(() => { if (selected && window.matchMedia?.('(max-width: 900px)').matches) pane.current?.scrollIntoView?.({ block: 'start' }); }, [selected]);

  function placed(app, sectionName) {
    setNotice(sectionName ? `${app.name} placed in ${sectionName}.` : `${app.name} moved back to Needs a section.`);
    list.reload();
    Object.values(counts).forEach(resource => resource.reload());
    // In Needs a section, a placed student leaves the list, so move on to the next one.
    if (tab === 'unplaced' && sectionName) { const next = neighbour(); setParams(next ? { applicationId: String(next.id) } : {}); }
  }

  return <div className="enrollment-workspace pl-screen">
    <header className="pl-header">
      <div><p className="eyebrow">Enrollment management</p><h1>Enrolled &amp; Assigned</h1></div>
      <p className="pl-muted">Place confirmed students in a section. Capacity is live and checked again when you place.</p>
    </header>

    <section className="pl-filters" aria-label="Filters">
      <label className="pl-search">Search students<input type="search" maxLength={200} value={filters.search} onChange={change('search')} placeholder="Name, application ID or LRN" /></label>
      {[['campusId', 'Campus', 'campuses', 'All campuses'], ['termId', 'Term', 'terms', 'All terms'], ['programId', 'Program', 'programs', 'All programs']].map(([key, label, source, all]) =>
        <label key={key}>{label}<select value={filters[key]} onChange={change(key)}><option value="">{all}</option>
          {(reference.data?.[source] ?? []).map(item => <option key={item.id} value={item.id}>{item.academicYear ? `${item.academicYear} / ` : ''}{item.name}</option>)}</select></label>)}
      <button type="button" className="ui-btn ui-btn-secondary pl-more" aria-expanded={moreFilters} aria-controls="pl-more-filters" onClick={() => setMoreFilters(open => !open)}>More filters</button>
      {moreFilters && <div id="pl-more-filters" className="pl-more-filters">
        <label>Residential district<input maxLength={200} value={filters.district} onChange={change('district')} /></label>
        <label>Transportation route<input maxLength={200} value={filters.transportRoute} onChange={change('transportRoute')} /></label>
      </div>}
    </section>
    <Failure error={reference.error} reload={reference.reload} />
    {notice && <p className="notice" role="status">{notice}</p>}

    <div className="pl-layout">
      <section className="pl-list" aria-label="Students">
        <div className="pl-tabs" role="tablist" aria-label="Placement">
          {tabs.map(item => <button key={item.key} type="button" role="tab" id={`pl-tab-${item.key}`} aria-selected={tab === item.key} aria-controls="pl-tabpanel"
            className={tab === item.key ? 'pl-tab is-selected' : 'pl-tab'} onClick={() => { setTab(item.key); setPage(0); }}>
            {item.label}<span className="pl-count">{counts[item.key].data?.total ?? '…'}</span>
          </button>)}
        </div>
        <div id="pl-tabpanel" role="tabpanel" aria-labelledby={`pl-tab-${tab}`}>
          <Failure error={list.error} reload={list.reload} />
          {list.loading && !list.data && <p role="status" className="pl-pad">Loading students…</p>}
          {list.data && (rows.length
            ? <ul className="pl-students">{rows.map(row => <li key={row.id}>
              <button type="button" className={String(row.id) === selected ? 'pl-student-row is-selected' : 'pl-student-row'} aria-current={String(row.id) === selected ? 'true' : undefined} onClick={() => select(row.id)}>
                <span className="pl-student-text"><strong>{row.name}</strong><small>{row.program}{row.gradeLevel ? ` · Grade ${row.gradeLevel}` : ''} · {row.campus}</small></span>
                {row.section ? <StatusChip tone="info">{row.section}</StatusChip> : <StatusChip tone="attention">No section</StatusChip>}
              </button>
            </li>)}</ul>
            : <div className="empty-state pl-pad"><h3>{tab === 'unplaced' ? 'Everyone here has a section' : 'No students match'}</h3><p>Try another tab or filter.</p></div>)}
          <div className="pl-pad"><Pager page={page} total={list.data?.total ?? 0} onChange={setPage} disabled={list.loading} /></div>
        </div>
      </section>

      <section className="pl-pane" ref={pane} aria-label="Placement">
        {selected && /^\d+$/.test(selected)
          ? <PlacementPane key={selected} id={selected} onPlaced={placed} canSkip={Boolean(neighbour())} onSkip={() => { const next = neighbour(); if (next) select(next.id); }} />
          : <div className="empty-state pl-pad"><h3>Choose a student</h3><p>Pick a student on the left to see the sections they can join.</p></div>}
      </section>
    </div>
  </div>;
}
