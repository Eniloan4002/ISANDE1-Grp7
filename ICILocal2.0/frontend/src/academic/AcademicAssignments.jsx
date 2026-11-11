import { useEffect, useMemo, useState } from 'react';
import { Failure } from '../EnrollmentWorkspace.jsx';
import {
  assignAdviser,
  assignTeacher,
  createClassOffering,
  loadAssignmentCatalog,
  loadAssignments,
} from './assignmentApi.js';
import './assignments.css';

function optionValue(value) {
  return value == null ? '' : String(value);
}

function requestError(error) {
  if (error?.status === 409) return 'This assignment was changed by another user. Reload the records and try again.';
  if (error?.status === 403) return 'You are not authorized to manage academic assignments.';
  return error?.message || 'The academic assignment request failed.';
}

function SelectField({ label, value, options, onChange, disabled, children }) {
  return <label className="assignment-field">{label}
    <select value={value} onChange={event => onChange(event.target.value)} disabled={disabled}>
      {children || options.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}
    </select>
  </label>;
}

export default function AcademicAssignments() {
  const [catalog, setCatalog] = useState({ years: [], terms: [], campuses: [] });
  const [filters, setFilters] = useState({ academicYearId: '', termId: '', campusId: '' });
  const [data, setData] = useState(null);
  const [loadingCatalog, setLoadingCatalog] = useState(true);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [newOffering, setNewOffering] = useState({ sectionId: '', subjectId: '', teacherId: '' });
  const [teacherChoices, setTeacherChoices] = useState({});
  const [adviserChoices, setAdviserChoices] = useState({});

  async function loadCatalog() {
    setLoadingCatalog(true);
    setError('');
    try {
      const result = await loadAssignmentCatalog();
      setCatalog(result);
      setFilters(current => ({
        academicYearId: current.academicYearId || optionValue(result.years[0]?.id),
        termId: current.termId || optionValue(result.terms[0]?.id),
        campusId: current.campusId || optionValue(result.campuses[0]?.id),
      }));
    } catch (requestFailure) {
      setError(requestError(requestFailure));
    } finally {
      setLoadingCatalog(false);
    }
  }

  async function load() {
    if (!filters.academicYearId || !filters.termId || !filters.campusId) {
      setData(null);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const result = await loadAssignments(filters);
      setData(result);
      setNewOffering(current => ({ ...current, sectionId: current.sectionId || optionValue(result.sections[0]?.id), subjectId: current.subjectId || optionValue(result.subjects[0]?.id), teacherId: current.teacherId || optionValue(result.teachers[0]?.id) }));
      setTeacherChoices({});
      setAdviserChoices({});
    } catch (requestFailure) {
      setData(null);
      setError(requestError(requestFailure));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { loadCatalog(); }, []);
  useEffect(() => { load(); }, [filters.academicYearId, filters.termId, filters.campusId]);

  const terms = useMemo(() => catalog.terms.filter(term => !filters.academicYearId || optionValue(term.academic_year_id ?? term.academicYearId) === filters.academicYearId), [catalog.terms, filters.academicYearId]);

  function updateFilter(key, value) {
    setFilters(current => ({ ...current, [key]: value, ...(key === 'academicYearId' ? { termId: '' } : {}) }));
  }

  async function refresh(message) {
    await load();
    setNotice(message);
  }

  async function saveTeacher(row) {
    const teacherId = teacherChoices[row.classId] || optionValue(row.teacherId);
    if (!teacherId) return;
    setBusy(`teacher-${row.classId}`);
    setError('');
    try {
      await assignTeacher(row.classId, { academicYearId: Number(filters.academicYearId), termId: Number(filters.termId), teacherId: Number(teacherId), version: row.version });
      await refresh('Subject Teacher assignment saved.');
    } catch (requestFailure) {
      setError(requestError(requestFailure));
    } finally {
      setBusy('');
    }
  }

  async function saveAdviser(row) {
    const adviserId = adviserChoices[row.id];
    setBusy(`adviser-${row.id}`);
    setError('');
    try {
      await assignAdviser(row.id, { academicYearId: Number(filters.academicYearId), termId: Number(filters.termId), adviserId: adviserId ? Number(adviserId) : null, version: row.version });
      await refresh('Teacher Adviser assignment saved.');
    } catch (requestFailure) {
      setError(requestError(requestFailure));
    } finally {
      setBusy('');
    }
  }

  async function submitOffering(event) {
    event.preventDefault();
    setBusy('create');
    setError('');
    try {
      await createClassOffering({
        academicYearId: Number(filters.academicYearId),
        termId: Number(filters.termId),
        sectionId: Number(newOffering.sectionId),
        subjectId: Number(newOffering.subjectId),
        teacherId: Number(newOffering.teacherId),
      });
      setNewOffering({ sectionId: '', subjectId: '', teacherId: '' });
      await refresh('Class offering created.');
    } catch (requestFailure) {
      setError(requestError(requestFailure));
    } finally {
      setBusy('');
    }
  }

  const disabled = loadingCatalog || loading || Boolean(busy);
  return <div className="academic-workspace">
    <header className="page-heading">
      <p className="eyebrow">Academic Records</p>
      <h1>Teacher &amp; Adviser Assignments</h1>
      <p>Manage class offerings, Subject Teacher assignments, and Teacher Adviser assignments for the selected academic context.</p>
    </header>
    {notice && <div className="notice" role="status">{notice}</div>}
    {error && <Failure error={error} reload={load} />}
    <section className="panel assignment-context">
      <h2>Academic context</h2>
      <div className="panel-body assignment-filter-grid">
        <SelectField label="Academic year" value={filters.academicYearId} disabled={disabled} onChange={value => updateFilter('academicYearId', value)}>
          <option value="">Select academic year</option>
          {catalog.years.map(year => <option key={year.id} value={year.id}>{year.label}</option>)}
        </SelectField>
        <SelectField label="Term" value={filters.termId} disabled={disabled} onChange={value => updateFilter('termId', value)}>
          <option value="">Select term</option>
          {terms.map(term => <option key={term.id} value={term.id}>{term.name} ({term.code})</option>)}
        </SelectField>
        <SelectField label="Campus" value={filters.campusId} disabled={disabled} onChange={value => updateFilter('campusId', value)}>
          <option value="">Select campus</option>
          {catalog.campuses.map(campus => <option key={campus.id} value={campus.id}>{campus.name}</option>)}
        </SelectField>
      </div>
    </section>
    {loadingCatalog && <p role="status">Loading academic context…</p>}
    {loading && <p role="status">Loading assignments…</p>}
    {!loadingCatalog && !loading && data && <div className="assignment-layout">
      <section className="panel">
        <h2>Section assignments</h2>
        <div className="setup-table-wrap"><table className="setup-table assignment-table"><thead><tr><th>Section</th><th>Grade level</th><th>Room</th><th>Capacity</th><th>Teacher Adviser</th><th>Action</th></tr></thead><tbody>
          {data.sections.length ? data.sections.map(row => <tr key={row.id}><td>{row.name}</td><td>{row.gradeLevel}</td><td>{row.room || 'Not recorded'}</td><td>{row.capacity}</td><td><select aria-label={`Teacher Adviser for ${row.name}`} value={adviserChoices[row.id] ?? optionValue(row.adviserId)} onChange={event => setAdviserChoices(current => ({ ...current, [row.id]: event.target.value }))} disabled={disabled}><option value="">Unassigned</option>{data.advisers.map(person => <option key={person.id} value={person.id}>{person.name}</option>)}</select></td><td><button className="button table-action" onClick={() => saveAdviser(row)} disabled={disabled}>Save adviser</button></td></tr>) : <tr><td colSpan="6">No sections found for this academic context.</td></tr>}
        </tbody></table></div>
      </section>
      <section className="panel">
        <h2>Class offerings</h2>
        <div className="setup-table-wrap"><table className="setup-table assignment-table"><thead><tr><th>Section</th><th>Subject</th><th>Subject Teacher</th><th>Action</th></tr></thead><tbody>
          {data.classes.length ? data.classes.map(row => <tr key={row.classId}><td>{row.section}</td><td><strong>{row.subjectCode}</strong><small>{row.subject}</small></td><td><select aria-label={`Subject Teacher for ${row.section} ${row.subjectCode}`} value={teacherChoices[row.classId] ?? optionValue(row.teacherId)} onChange={event => setTeacherChoices(current => ({ ...current, [row.classId]: event.target.value }))} disabled={disabled}>{data.teachers.map(person => <option key={person.id} value={person.id}>{person.name}</option>)}</select></td><td><button className="button table-action" onClick={() => saveTeacher(row)} disabled={disabled}>Save teacher</button></td></tr>) : <tr><td colSpan="4">No class offerings found for this academic context.</td></tr>}
        </tbody></table></div>
      </section>
      <section className="panel assignment-create-panel">
        <h2>Create class offering</h2>
        <form className="panel-body assignment-create-form" onSubmit={submitOffering}>
          <SelectField label="Section" value={newOffering.sectionId} disabled={disabled} onChange={value => setNewOffering(current => ({ ...current, sectionId: value }))} options={data.sections.map(row => ({ id: row.id, label: row.name }))} />
          <SelectField label="Subject" value={newOffering.subjectId} disabled={disabled} onChange={value => setNewOffering(current => ({ ...current, subjectId: value }))} options={data.subjects.map(row => ({ id: row.id, label: `${row.code} — ${row.name}` }))} />
          <SelectField label="Subject Teacher" value={newOffering.teacherId} disabled={disabled} onChange={value => setNewOffering(current => ({ ...current, teacherId: value }))} options={data.teachers.map(row => ({ id: row.id, label: row.name }))} />
          <button className="button" type="submit" disabled={disabled || !newOffering.sectionId || !newOffering.subjectId || !newOffering.teacherId}>Create offering</button>
        </form>
      </section>
    </div>}
  </div>;
}
