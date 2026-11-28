import { useEffect, useMemo, useState } from 'react';
import { Failure, Pager } from '../EnrollmentWorkspace.jsx';
import { StatTile, StatusChip } from '../ui/components.jsx';
import { downloadReport, emptyFilters, loadFilterOptions, loadReportPreview } from './reportsApi.js';
import '../academic/academic-screens.css';

const standing = { enrolled: ['success', 'Enrolled'], provisional: ['attention', 'Pre-enlisted'] };
const enrollmentStatuses = [['enrolled', 'Enrolled'], ['provisional', 'Pre-enlisted'], ['withdrawn', 'Withdrawn']];

function Select({ label, value, onChange, children }) {
  return <label>{label}<select value={value} onChange={event => onChange(event.target.value)}>{children}</select></label>;
}

export default function ReportingMonitoring() {
  const [options, setOptions] = useState(null);
  const [optionsError, setOptionsError] = useState('');
  const [draft, setDraft] = useState(emptyFilters);
  const [applied, setApplied] = useState(emptyFilters);
  const [page, setPage] = useState(0);
  const [report, setReport] = useState({ status: 'loading', data: null, error: '' });
  const [exporting, setExporting] = useState('');
  const [exportError, setExportError] = useState('');
  const [exported, setExported] = useState('');
  const [revision, setRevision] = useState(0);

  useEffect(() => {
    let active = true;
    loadFilterOptions()
      .then(result => active && setOptions(result))
      .catch(failure => active && setOptionsError(failure?.message || 'The filter choices could not be loaded.'));
    return () => { active = false; };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setReport(current => ({ ...current, status: 'loading', error: '' }));
    loadReportPreview(applied, page, controller.signal)
      .then(data => setReport({ status: 'ready', data, error: '' }))
      .catch(failure => {
        if (failure?.name === 'AbortError') return;
        setReport({ status: 'error', data: null, error: failure?.message || 'The report could not be loaded.' });
      });
    return () => controller.abort();
  }, [applied, page, revision]);

  // The choices narrow each other: terms follow the year, periods follow the term, sections follow campus and term.
  const terms = useMemo(() => (options?.terms ?? []).filter(term => !draft.academicYearId || String(term.academicYearId) === draft.academicYearId), [options, draft.academicYearId]);
  const periods = useMemo(() => (options?.gradingPeriods ?? []).filter(period => draft.termId && String(period.termId) === draft.termId), [options, draft.termId]);
  const sections = useMemo(() => (options?.sections ?? []).filter(section =>
    (!draft.campusId || String(section.campusId) === draft.campusId) && (!draft.termId || String(section.termId) === draft.termId)), [options, draft.campusId, draft.termId]);

  function set(key, value) {
    setDraft(current => {
      const next = { ...current, [key]: value };
      if (key === 'academicYearId') { next.termId = ''; next.gradingPeriodId = ''; next.sectionId = ''; }
      if (key === 'termId') { next.gradingPeriodId = ''; next.sectionId = ''; }
      if (key === 'campusId') next.sectionId = '';
      return next;
    });
  }
  function apply(event) {
    event.preventDefault();
    setApplied(draft);
    setPage(0);
    setExported('');
    setExportError('');
  }
  function reset() {
    setDraft(emptyFilters);
    setApplied(emptyFilters);
    setPage(0);
    setExported('');
    setExportError('');
  }
  async function exportReport(format) {
    setExporting(format);
    setExportError('');
    setExported('');
    try {
      const name = await downloadReport(applied, format);
      setExported(`Downloaded ${name}.`);
    } catch (failure) {
      setExportError(failure.message);
    } finally {
      setExporting('');
    }
  }

  const data = report.data;
  const rows = data?.items ?? [];
  const summary = data?.summary;
  return <div className="enrollment-workspace ac-screen">
    <header className="page-heading">
      <p className="eyebrow">Reporting &amp; monitoring</p>
      <h1>Reporting &amp; Monitoring</h1>
      <p>Enrollment totals, document completeness and voucher counts for the campuses you report on.</p>
    </header>
    <Failure error={optionsError} />
    <form className="panel" aria-label="Report filters" onSubmit={apply}>
      <div className="ac-filters">
        <Select label="Campus" value={draft.campusId} onChange={value => set('campusId', value)}>
          <option value="">All campuses</option>{(options?.campuses ?? []).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
        </Select>
        <Select label="Academic year" value={draft.academicYearId} onChange={value => set('academicYearId', value)}>
          <option value="">All years</option>{(options?.academicYears ?? []).map(item => <option key={item.id} value={item.id}>{item.label}</option>)}
        </Select>
        <Select label="Term" value={draft.termId} onChange={value => set('termId', value)}>
          <option value="">All terms</option>{terms.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
        </Select>
        <Select label="Grading period" value={draft.gradingPeriodId} onChange={value => set('gradingPeriodId', value)}>
          <option value="">{draft.termId ? 'All periods' : 'Choose a term first'}</option>{periods.map(item => <option key={item.id} value={item.id}>{item.code}</option>)}
        </Select>
        <Select label="Section" value={draft.sectionId} onChange={value => set('sectionId', value)}>
          <option value="">{draft.termId ? 'All sections' : 'Choose a term first'}</option>{draft.termId && sections.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
        </Select>
        <Select label="Grade level" value={draft.gradeLevel} onChange={value => set('gradeLevel', value)}>
          <option value="">All grade levels</option>{(options?.gradeLevels ?? []).map(item => <option key={item} value={item}>{item}</option>)}
        </Select>
        <Select label="Voucher" value={draft.voucher} onChange={value => set('voucher', value)}>
          <option value="">All vouchers</option>{(options?.vouchers ?? []).map(item => <option key={item} value={item}>{item}</option>)}
        </Select>
        <Select label="Requirements" value={draft.requirementStatus} onChange={value => set('requirementStatus', value)}>
          <option value="">Any</option><option value="complete">Complete</option><option value="incomplete">Incomplete</option>
        </Select>
        <Select label="Enrollment status" value={draft.enrollmentStatus} onChange={value => set('enrollmentStatus', value)}>
          <option value="">Any</option>{enrollmentStatuses.map(([code, label]) => <option key={code} value={code}>{label}</option>)}
        </Select>
        <div className="ac-filter-actions">
          <button type="submit" className="ui-btn ui-btn-primary">Apply filters</button>
          <button type="button" className="ui-btn ui-btn-secondary" onClick={reset}>Reset</button>
        </div>
      </div>
    </form>

    <Failure error={report.error} reload={() => setRevision(value => value + 1)} />
    {report.status === 'loading' && <p role="status">Loading report…</p>}
    {summary && <>
      <div className="ac-stats" aria-label="Report summary">
        <StatTile label="Matching enrollments" value={summary.total} />
        <StatTile label="Enrolled" value={summary.enrolled} tone="success" />
        <StatTile label="Incomplete documents" value={summary.incomplete} tone={summary.incomplete > 0 ? 'attention' : undefined} />
      </div>
      <section className="panel" aria-label="Voucher counts">
        <div className="ac-vouchers">
          <h2>By voucher</h2>
          {(data.vouchers ?? []).length === 0
            ? <p className="ac-muted">No vouchers in this report.</p>
            : <ul>{data.vouchers.map(item => <li key={item.voucher}><span>{item.voucher}</span><b>{item.total}</b></li>)}</ul>}
        </div>
      </section>
      <section className="panel" aria-labelledby="report-table-title">
        <div className="ac-report-head">
          <h2 id="report-table-title">Enrollments</h2>
          <div className="ac-filter-actions">
            <button type="button" className="ui-btn ui-btn-secondary" disabled={Boolean(exporting)} onClick={() => exportReport('csv')}>{exporting === 'csv' ? 'Preparing CSV…' : 'Export CSV'}</button>
            <button type="button" className="ui-btn ui-btn-secondary" disabled={Boolean(exporting)} onClick={() => exportReport('pdf')}>{exporting === 'pdf' ? 'Preparing PDF…' : 'Export PDF'}</button>
          </div>
        </div>
        {exportError && <div className="notice notice-error" role="alert">{exportError}</div>}
        {exported && <p className="notice" role="status">{exported}</p>}
        {rows.length === 0
          ? <div className="empty-state"><h3>No enrollments match these filters</h3><p>Try widening the filters, or reset them.</p></div>
          : <>
            <div className="setup-table-wrap">
              <table className="ac-table">
                <thead><tr><th scope="col">Student</th><th scope="col">Campus and term</th><th scope="col">Class</th><th scope="col">Voucher</th><th scope="col">Standing</th><th scope="col">Missing documents</th></tr></thead>
                <tbody>{rows.map(row => {
                  const [tone, label] = standing[row.status] ?? ['neutral', row.status];
                  return <tr key={row.enrollmentId}>
                    <td data-label="Student"><strong>{row.name}</strong><small>{row.studentNumber}</small></td>
                    <td data-label="Campus and term"><strong>{row.campus}</strong><small>{row.academicYear} · {row.term}</small></td>
                    <td data-label="Class"><strong>{row.gradeLevel || '—'}{row.section ? ` · ${row.section}` : ''}</strong><small>{row.program}</small></td>
                    <td data-label="Voucher">{row.voucher}</td>
                    <td data-label="Standing"><StatusChip tone={tone}>{label}</StatusChip></td>
                    <td data-label="Missing documents">{row.missingRequirements > 0 ? <span><b>{row.missingRequirements}</b> · <span className="ac-muted">{row.missingDocuments}</span></span> : <StatusChip tone="success">Complete</StatusChip>}</td>
                  </tr>;
                })}</tbody>
              </table>
            </div>
            <div className="ac-pager"><Pager page={page} total={data.total ?? 0} onChange={setPage} disabled={report.status === 'loading'} /></div>
          </>}
      </section>
    </>}
  </div>;
}
