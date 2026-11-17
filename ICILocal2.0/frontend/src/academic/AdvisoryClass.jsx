import { useEffect, useMemo, useState } from 'react';
import { Failure } from '../EnrollmentWorkspace.jsx';
import { loadAdvisoryCatalog, loadAdvisoryRoster } from './advisoryRoster.js';
import './advisory.css';

function badgeTone(value) {
  if (['Complete', 'Enrolled'].includes(value)) return 'status-success';
  if (['Incomplete', 'Pending', 'Pre-enlisted'].includes(value)) return 'status-warning';
  return '';
}

function StatusBadge({ children }) {
  return <span className={`status ${badgeTone(children)}`}>{children}</span>;
}

function optionValue(value) {
  return value == null ? '' : String(value);
}

export default function AdvisoryClass() {
  const [catalog, setCatalog] = useState({ years: [], terms: [] });
  const [filters, setFilters] = useState({ academicYearId: '', termId: '' });
  const [data, setData] = useState(null);
  const [sectionId, setSectionId] = useState('');
  const [search, setSearch] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    loadAdvisoryCatalog()
      .then(result => {
        if (!active) return;
        const years = result?.years || [];
        const allTerms = result?.terms || [];
        setCatalog({ years, terms: allTerms });
        const year = years[0];
        const term = allTerms.find(item => optionValue(item.academic_year_id ?? item.academicYearId) === optionValue(year?.id));
        setFilters({ academicYearId: optionValue(year?.id), termId: optionValue(term?.id) });
        if (!year || !term) setLoading(false);
      })
      .catch(requestError => {
        if (!active) return;
        setError(requestError.message);
        setLoading(false);
      });
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!filters.academicYearId || !filters.termId) return undefined;
    const controller = new AbortController();
    setLoading(true);
    setError('');
    loadAdvisoryRoster({ ...filters, signal: controller.signal })
      .then(result => {
        const sections = result?.sections || [];
        setData({ ...result, sections });
        setSectionId(current => sections.some(section => optionValue(section.id) === current)
          ? current : optionValue(sections[0]?.id));
      })
      .catch(requestError => {
        if (requestError.name === 'AbortError' || requestError.name === 'CanceledError') return;
        setData(null);
        setError(requestError.message);
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, [filters.academicYearId, filters.termId]);

  const terms = useMemo(() => catalog.terms.filter(term => optionValue(term.academic_year_id ?? term.academicYearId) === filters.academicYearId), [catalog.terms, filters.academicYearId]);
  const section = data?.sections.find(item => optionValue(item.id) === sectionId);

  const filteredStudents = useMemo(() => {
    const term = search.trim().toLowerCase();
    return (section?.students || []).filter(student =>
      !term || `${student.name} ${student.studentNumber}`.toLowerCase().includes(term),
    );
  }, [section, search]);

  function updateYear(value) {
    const term = catalog.terms.find(item => optionValue(item.academic_year_id ?? item.academicYearId) === value);
    setFilters({ academicYearId: value, termId: optionValue(term?.id) });
  }

  return <div className="academic-workspace">
    <header className="page-heading">
      <p className="eyebrow">Academic Records</p>
      <h1>Advisory Class</h1>
      <p>Monitor synchronized enrollment and requirements status for your assigned advisory section.</p>
    </header>
    <Failure error={error} reload={() => window.location.reload()} />
    <section className="panel advisory-context" aria-label="Academic context">
      <div className="panel-body advisory-filter-grid">
        <label>Academic year
          <select value={filters.academicYearId} onChange={event => updateYear(event.target.value)} disabled={loading}>
            {catalog.years.map(year => <option key={year.id} value={year.id}>{year.label}</option>)}
          </select>
        </label>
        <label>Term
          <select value={filters.termId} onChange={event => setFilters(current => ({ ...current, termId: event.target.value }))} disabled={loading}>
            {terms.map(term => <option key={term.id} value={term.id}>{term.name} ({term.code})</option>)}
          </select>
        </label>
        {data?.sections.length > 1 && <label>Section
          <select value={sectionId} onChange={event => setSectionId(event.target.value)}>
            {data.sections.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
          </select>
        </label>}
      </div>
    </section>
    {loading && <p role="status">Loading advisory roster…</p>}
    {!loading && !error && data && !section && <div className="empty-state"><h3>No advisory section assigned</h3><p>You are not assigned as Teacher Adviser to a section in this term. Contact the registrar if this is unexpected.</p></div>}
    {!loading && !error && data && section && <>
      <section className="advisory-overview panel" aria-label="Advisory section information">
        <div><span className="eyebrow">Assigned section</span><h2>{section.name}</h2><p>{section.gradeLevel}{section.room ? ` · Room ${section.room}` : ''}</p></div>
        <dl>
          <div><dt>Academic year</dt><dd>{data.academicYear || 'Not available'}</dd></div>
          <div><dt>Active term</dt><dd>{data.term || 'Not available'}</dd></div>
          <div><dt>Adviser</dt><dd>{data.adviser?.name || 'Authenticated adviser'}</dd></div>
        </dl>
      </section>
      <section className="panel advisory-roster-panel" aria-labelledby="advisory-roster-title">
        <div className="panel-heading">
          <div><h2 id="advisory-roster-title">Student roster <span className="advisory-student-count">({section.students.length})</span></h2><p>Read-only information for the assigned section.</p></div>
          <label className="advisory-search">Search students<input aria-label="Search students" type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Name or student number" /></label>
        </div>
        {section.students.length === 0
          ? <div className="empty-state"><h3>No assigned students</h3><p>This adviser has no students in the assigned section.</p></div>
          : filteredStudents.length === 0
            ? <div className="empty-state"><h3>No matching students</h3><p>Try a different name or student number.</p></div>
            : <div className="setup-table-wrap"><table className="setup-table advisory-table"><thead><tr><th>Student</th><th>Contact number</th><th>Enrollment</th><th>Requirements</th></tr></thead><tbody>
              {filteredStudents.map(student => <tr key={student.studentNumber}><td><strong>{student.name}</strong><small>{student.studentNumber}</small></td><td>{student.contactNumber || 'Not recorded'}</td><td><StatusBadge>{student.enrollmentStatus}</StatusBadge></td><td><StatusBadge>{student.requirementsStatus}</StatusBadge></td></tr>)}
            </tbody></table></div>}
        <p className="field-help advisory-read-only-note">Advisory data is read-only. Student records and assignments must be updated through their authorized workflows.</p>
      </section>
    </>}
  </div>;
}
