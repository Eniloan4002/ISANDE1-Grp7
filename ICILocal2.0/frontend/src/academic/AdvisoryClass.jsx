import { useEffect, useMemo, useState } from 'react';
import { Failure } from '../EnrollmentWorkspace.jsx';
import { useAuth } from '../auth/AuthContext.jsx';
import { advisoryDataSource, loadAdvisoryRoster } from './advisoryRoster.js';
import './advisory.css';

function badgeTone(value) {
  if (['Complete', 'Enrolled', 'Good standing'].includes(value)) return 'status-success';
  if (['Incomplete', 'At risk', 'Needs follow-up'].includes(value)) return 'status-warning';
  return '';
}

function StatusBadge({ children }) {
  return <span className={`status ${badgeTone(children)}`}>{children}</span>;
}

export default function AdvisoryClass() {
  const auth = useAuth();
  const authenticatedName = auth?.user?.fullName;
  const [data, setData] = useState(null);
  const [search, setSearch] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    loadAdvisoryRoster({ signal: controller.signal })
      .then(setData)
      .catch(requestError => {
        if (requestError.name !== 'AbortError') setError(requestError.message);
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, []);

  const filteredStudents = useMemo(() => {
    const term = search.trim().toLowerCase();
    return (data?.students || []).filter(student =>
      !term || `${student.name} ${student.studentNumber}`.toLowerCase().includes(term),
    );
  }, [data, search]);

  const fixtureMode = advisoryDataSource() === 'development-fixture';

  return <div className="academic-workspace">
    <header className="page-heading">
      <p className="eyebrow">Academic Records</p>
      <h1>Advisory Class</h1>
      <p>Monitor synchronized enrollment, requirements, and academic status for the assigned advisory section.</p>
    </header>
    {fixtureMode && <div className="notice advisory-source-notice" role="status">
      Development fixture mode: this roster is temporary and is not connected to the database.
    </div>}
    <Failure error={error} reload={() => window.location.reload()} />
    {loading && <p role="status">Loading advisory roster…</p>}
    {!loading && !error && data && <>
      <section className="advisory-overview panel" aria-label="Advisory section information">
        <div><span className="eyebrow">Assigned section</span><h2>{data.section.name}</h2><p>{data.section.gradeLevel}</p></div>
        <dl>
          <div><dt>Academic year</dt><dd>{data.academicYear || 'Not available'}</dd></div>
          <div><dt>Active term</dt><dd>{data.term || 'Not available'}</dd></div>
          <div><dt>Adviser</dt><dd>{data.adviser?.name || authenticatedName || 'Authenticated adviser'}</dd></div>
        </dl>
      </section>
      <section className="panel advisory-roster-panel" aria-labelledby="advisory-roster-title">
        <div className="panel-heading">
          <div><h2 id="advisory-roster-title">Student roster <span className="advisory-student-count">({data.students.length})</span></h2><p>Read-only information for the assigned section.</p></div>
          <label className="advisory-search">Search students<input aria-label="Search students" type="search" value={search} onChange={event => setSearch(event.target.value)} placeholder="Name or student number" /></label>
        </div>
        {data.students.length === 0
          ? <div className="empty-state"><h3>No assigned students</h3><p>This adviser has no students in the assigned section.</p></div>
          : filteredStudents.length === 0
            ? <div className="empty-state"><h3>No matching students</h3><p>Try a different name or student number.</p></div>
            : <div className="setup-table-wrap"><table className="setup-table advisory-table"><thead><tr><th>Student</th><th>Contact number</th><th>Enrollment</th><th>Requirements</th><th>Academic standing</th><th>Academic flag</th></tr></thead><tbody>
              {filteredStudents.map(student => <tr key={student.studentNumber}><td><strong>{student.name}</strong><small>{student.studentNumber}</small></td><td>{student.contactNumber || 'Not recorded'}</td><td><StatusBadge>{student.enrollmentStatus}</StatusBadge></td><td><StatusBadge>{student.requirementsStatus}</StatusBadge></td><td><StatusBadge>{student.academicStanding}</StatusBadge></td><td>{student.academicFlag || 'None'}</td></tr>)}
            </tbody></table></div>}
        <p className="field-help advisory-read-only-note">Advisory data is read-only. Student records and assignments must be updated through their authorized workflows.</p>
      </section>
    </>}
  </div>;
}
