import { useEffect, useMemo, useState } from 'react';
import { useAuth } from '../auth/AuthContext.jsx';
import { Failure } from '../EnrollmentWorkspace.jsx';
import { Button, Dialog, StatTile, StatusChip } from '../ui/components.jsx';
import {
  approvePolicy, createPolicy, describeReason, loadHonorFilters, loadHonorRoll, loadPolicies, loadPrograms, loadSubjects,
  roundingModes, tiePolicy, updatePolicy,
} from './honorsApi.js';
import { categoryFor, weightedAverage } from './honorMath.js';
import '../academic/academic-screens.css';
import '../academic/records-screens.css';

const emptyScope = { campusId: '', programId: '', gradeLevel: '', gradingPeriodId: '' };
const statusChip = { draft: ['attention', 'Draft'], approved: ['success', 'Approved'], superseded: ['neutral', 'Superseded'] };

function formatTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

const blankRules = () => ({
  subjects: [{ subjectId: '', weight: '1' }],
  decimalPlaces: '2',
  rounding: 'HALF_UP',
  minimumSubjectGrade: '',
  categories: [{ name: '', minimumAverage: '' }],
  examples: [{ name: '', values: [''], expectedAverage: '', expectedCategory: '' }],
});

const text = value => (value === null || value === undefined ? '' : String(value));

function rulesToForm(rules) {
  if (!rules) return blankRules();
  return {
    subjects: rules.subjects.map(item => ({ subjectId: text(item.subjectId), weight: text(item.weight) })),
    decimalPlaces: text(rules.decimalPlaces),
    rounding: rules.rounding,
    minimumSubjectGrade: text(rules.minimumSubjectGrade),
    categories: rules.categories.map(item => ({ name: item.name, minimumAverage: text(item.minimumAverage) })),
    examples: rules.examples.map(item => ({ name: item.name, values: item.values.map(text), expectedAverage: text(item.expectedAverage), expectedCategory: item.expectedCategory ?? '' })),
  };
}

function formToRules(form) {
  return {
    subjects: form.subjects.map(item => ({ subjectId: Number(item.subjectId), weight: Number(item.weight) })),
    decimalPlaces: Number(form.decimalPlaces),
    rounding: form.rounding,
    minimumSubjectGrade: Number(form.minimumSubjectGrade),
    categories: form.categories.map(item => ({ name: item.name.trim(), minimumAverage: Number(item.minimumAverage) })),
    tiePolicy,
    examples: form.examples.map(item => ({
      name: item.name.trim(), values: item.values.map(Number), expectedAverage: Number(item.expectedAverage),
      expectedCategory: item.expectedCategory.trim() || null,
    })),
  };
}

/** Missing pieces the server would reject, in words, so the registrar can fix them before saving. */
function formProblems(form) {
  const problems = [];
  if (form.subjects.some(item => !item.subjectId)) problems.push('Choose a subject on every subject row.');
  if (new Set(form.subjects.map(item => item.subjectId)).size !== form.subjects.length) problems.push('Each subject can be included once.');
  if (form.subjects.some(item => !(Number(item.weight) > 0 && Number(item.weight) <= 1000))) problems.push('Weights must be more than 0 and at most 1000.');
  if (form.minimumSubjectGrade === '') problems.push('Set the minimum subject grade.');
  if (form.categories.some(item => !item.name.trim() || item.minimumAverage === '')) problems.push('Give every category a name and a minimum average.');
  if (form.examples.some(item => !item.name.trim() || item.values.some(value => value === '') || item.expectedAverage === '')) problems.push('Complete every worked example.');
  return problems;
}

function ScopePicker({ options, programs, scope, onChange }) {
  const termName = useMemo(() => Object.fromEntries((options?.terms ?? []).map(term => [String(term.id), term])), [options]);
  const yearName = useMemo(() => Object.fromEntries((options?.academicYears ?? []).map(year => [String(year.id), year.label])), [options]);
  const periodLabel = period => {
    const term = termName[String(period.termId)];
    return term ? `${period.code} · ${term.name} ${yearName[String(term.academicYearId)] ?? ''}`.trim() : period.code;
  };
  const set = (key, value) => onChange({ ...scope, [key]: value });
  return <section className="panel ac-filters" aria-label="Honor roll scope">
    <label>Campus<select value={scope.campusId} onChange={event => set('campusId', event.target.value)}>
      <option value="">Choose a campus</option>
      {(options?.campuses ?? []).map(item => <option key={item.id} value={item.id}>{item.name}</option>)}
    </select></label>
    <label>Program<select value={scope.programId} onChange={event => set('programId', event.target.value)}>
      <option value="">Choose a program</option>
      {programs.map(item => <option key={item.id} value={item.id} title={item.name}>{item.code || item.name}</option>)}
    </select></label>
    <label>Grade level<select value={scope.gradeLevel} onChange={event => set('gradeLevel', event.target.value)}>
      <option value="">Choose a grade level</option>
      {(options?.gradeLevels ?? []).map(item => <option key={item} value={item}>{item}</option>)}
    </select></label>
    <label>Grading period<select value={scope.gradingPeriodId} onChange={event => set('gradingPeriodId', event.target.value)}>
      <option value="">Choose a grading period</option>
      {(options?.gradingPeriods ?? []).map(item => <option key={item.id} value={item.id}>{periodLabel(item)}</option>)}
    </select></label>
  </section>;
}

function RollView({ scope, complete, onOpenPolicies }) {
  const [roll, setRoll] = useState({ status: 'idle', data: null, error: '', missingPolicy: false });

  useEffect(() => { setRoll({ status: 'idle', data: null, error: '', missingPolicy: false }); }, [scope]);

  async function compute() {
    setRoll({ status: 'loading', data: null, error: '', missingPolicy: false });
    try {
      setRoll({ status: 'ready', data: await loadHonorRoll(scope), error: '', missingPolicy: false });
    } catch (failure) {
      setRoll({ status: 'error', data: null, error: failure?.status === 409 ? '' : failure?.message || 'The honor roll could not be computed.', missingPolicy: failure?.status === 409 });
    }
  }

  const items = roll.data?.items ?? [];
  const ranked = items.filter(item => item.eligible).sort((a, b) => a.rank - b.rank || String(a.studentNumber).localeCompare(String(b.studentNumber)));
  const ineligible = items.filter(item => !item.eligible);
  return <>
    <div className="rs-actions rs-gap">
      <Button onClick={compute} disabled={!complete || roll.status === 'loading'}>{roll.status === 'loading' ? 'Computing…' : 'Compute honor roll'}</Button>
      {!complete && <span className="ac-muted">Choose a campus, program, grade level and grading period first.</span>}
    </div>
    <Failure error={roll.error} />
    {roll.missingPolicy && <div className="empty-state">
      <h3>No approved honor policy for this scope</h3>
      <p>The honor roll can only be computed once the school approves an honor policy for this campus, program, grade level and grading period.</p>
      <Button variant="secondary" onClick={onOpenPolicies}>Open honor policies</Button>
    </div>}
    {roll.data && <>
      <div className="ac-stats" aria-label="Honor roll summary">
        <StatTile label="Students evaluated" value={roll.data.total ?? items.length} />
        <StatTile label="On the honor roll" value={ranked.length} tone="success" />
        <StatTile label="Not eligible" value={ineligible.length} tone={ineligible.length ? 'attention' : undefined} />
      </div>
      <p className="ac-muted">Computed with policy #{roll.data.policyId}, version {roll.data.policyVersion}. School approval reference: {roll.data.approvalReference || '—'}. Tied averages share a rank.</p>
      <section className="panel" aria-labelledby="ranked-title">
        <div className="ac-report-head"><h2 id="ranked-title">Honor roll</h2></div>
        {ranked.length === 0
          ? <div className="empty-state"><h3>No student qualifies for an honor this period</h3></div>
          : <div className="setup-table-wrap"><table className="ac-table">
            <thead><tr><th scope="col">Rank</th><th scope="col">Student</th><th scope="col">Average</th><th scope="col">Category</th></tr></thead>
            <tbody>{ranked.map(item => <tr key={item.enrollmentId}>
              <td data-label="Rank"><b>{item.rank}</b></td>
              <td data-label="Student"><strong>{item.name}</strong><small>{item.studentNumber}</small></td>
              <td data-label="Average">{item.average}</td>
              <td data-label="Category"><StatusChip tone="success">{item.category}</StatusChip></td>
            </tr>)}</tbody>
          </table></div>}
      </section>
      <section className="panel" aria-labelledby="ineligible-title">
        <div className="ac-report-head"><h2 id="ineligible-title">Not eligible</h2></div>
        {ineligible.length === 0
          ? <div className="empty-state"><h3>Every student in this cohort is eligible</h3></div>
          : <div className="setup-table-wrap"><table className="ac-table">
            <thead><tr><th scope="col">Student</th><th scope="col">Average</th><th scope="col">Why</th></tr></thead>
            <tbody>{ineligible.map(item => <tr key={item.enrollmentId}>
              <td data-label="Student"><strong>{item.name}</strong><small>{item.studentNumber}</small></td>
              <td data-label="Average">{item.average ?? '—'}</td>
              <td data-label="Why"><ul className="rs-reasons">{(item.reasons ?? []).map(reason => <li key={reason}>{describeReason(reason)}</li>)}</ul></td>
            </tr>)}</tbody>
          </table></div>}
      </section>
    </>}
  </>;
}

function PolicyEditor({ policy, scope, subjects, canConfigure, canApprove, onSaved, onClose }) {
  const [form, setForm] = useState(() => rulesToForm(policy?.rules));
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [reference, setReference] = useState('');
  const [confirming, setConfirming] = useState(false);
  const status = policy?.status ?? 'draft';
  const editable = canConfigure && status === 'draft';
  const subjectName = id => {
    const subject = subjects.find(item => String(item.id) === String(id));
    return subject ? `${subject.code} · ${subject.name}` : `Subject #${id}`;
  };

  const update = (key, value) => setForm(current => ({ ...current, [key]: value }));
  const updateRow = (key, index, patch) => update(key, form[key].map((row, at) => (at === index ? { ...row, ...patch } : row)));
  const removeRow = (key, index) => update(key, form[key].filter((_, at) => at !== index));
  function setSubjects(next) {
    // Worked-example values follow the subject order, so keep them in step.
    setForm(current => ({ ...current, subjects: next, examples: current.examples.map(example => ({ ...example, values: next.map((_, index) => example.values[index] ?? '') })) }));
  }
  function removeSubject(index) {
    setForm(current => ({
      ...current,
      subjects: current.subjects.filter((_, at) => at !== index),
      examples: current.examples.map(example => ({ ...example, values: example.values.filter((_, at) => at !== index) })),
    }));
  }

  const weights = form.subjects.map(item => item.weight);
  const computed = form.examples.map(example => {
    const average = weightedAverage(example.values, weights, form.decimalPlaces, form.rounding);
    return { average, category: average === null ? null : categoryFor(average, example.values, form.minimumSubjectGrade, form.categories) };
  });

  async function save() {
    const problems = formProblems(form);
    if (problems.length) { setError(problems.join(' ')); return; }
    setBusy('save');
    setError('');
    try {
      const saved = policy ? await updatePolicy(policy.id, policy.version, formToRules(form)) : await createPolicy(scope, formToRules(form));
      onSaved(saved, policy ? 'Draft saved.' : 'Draft created.');
    } catch (failure) {
      setError(failure?.status === 409 ? 'Someone else changed this policy. Close it and open it again to see the latest version.' : failure?.message || 'The policy could not be saved.');
    } finally {
      setBusy('');
    }
  }
  async function approve() {
    setBusy('approve');
    setError('');
    try {
      const approved = await approvePolicy(policy.id, policy.version, reference);
      setConfirming(false);
      onSaved(approved, 'Policy approved. It now applies to this scope and can no longer be edited.');
    } catch (failure) {
      setConfirming(false);
      setError(failure?.status === 409 ? 'This policy changed since you opened it. Close it and open it again before approving.' : failure?.message || 'The policy could not be approved.');
    } finally {
      setBusy('');
    }
  }

  const [tone, label] = statusChip[status] ?? ['neutral', status];
  return <section className="panel rs-section" aria-labelledby="policy-editor-title">
    <header>
      <div>
        <h2 id="policy-editor-title">{policy ? `Honor policy #${policy.id}` : 'New honor policy draft'}</h2>
        <p className="ac-muted">{policy ? <>Version {policy.version} · last changed {formatTime(policy.updated_at)}</> : 'Drafts can be edited until a principal approves them.'}</p>
      </div>
      <div className="rs-actions"><StatusChip tone={tone}>{label}</StatusChip><Button variant="secondary" onClick={onClose}>Close</Button></div>
    </header>
    {status !== 'draft' && <p className="notice">This policy is {label.toLowerCase()} and read-only. To change the rules, create and approve a new draft.
      {policy?.approval_reference && <> Approval reference: <b>{policy.approval_reference}</b>, approved {formatTime(policy.approved_at)}.</>}</p>}
    <fieldset className="rs-plain" disabled={!editable || Boolean(busy)}>
      <div>
        <h3 className="ac-subheading">Subjects and weights</h3>
        <div className="rs-rows">{form.subjects.map((row, index) => <div className="rs-row" key={index}>
          <label>Subject {index + 1}<select value={row.subjectId} onChange={event => setSubjects(form.subjects.map((item, at) => (at === index ? { ...item, subjectId: event.target.value } : item)))}>
            <option value="">Choose a subject</option>
            {row.subjectId && !subjects.some(item => String(item.id) === row.subjectId) && <option value={row.subjectId}>{subjectName(row.subjectId)}</option>}
            {subjects.map(item => <option key={item.id} value={item.id}>{item.code} · {item.name}</option>)}
          </select></label>
          <label>Weight<input type="number" min="0.0001" max="1000" step="any" value={row.weight} onChange={event => setSubjects(form.subjects.map((item, at) => (at === index ? { ...item, weight: event.target.value } : item)))} /></label>
          <Button variant="secondary" onClick={() => removeSubject(index)} disabled={form.subjects.length === 1} aria-label={`Remove subject ${index + 1}`}>Remove</Button>
        </div>)}</div>
        {editable && <Button variant="secondary" onClick={() => setSubjects([...form.subjects, { subjectId: '', weight: '1' }])} disabled={form.subjects.length >= 100}>Add subject</Button>}
      </div>
      <div className="rs-grid">
        <label>Decimal places<select value={form.decimalPlaces} onChange={event => update('decimalPlaces', event.target.value)}>{[0, 1, 2, 3, 4].map(value => <option key={value} value={value}>{value}</option>)}</select></label>
        <label>Rounding<select value={form.rounding} onChange={event => update('rounding', event.target.value)}>{roundingModes.map(([value, name]) => <option key={value} value={value}>{name}</option>)}</select></label>
        <label>Minimum subject grade<input type="number" min="0" max="100" step="any" value={form.minimumSubjectGrade} onChange={event => update('minimumSubjectGrade', event.target.value)} /></label>
      </div>
      <p className="ac-muted">Ties: students with the same rounded average share a rank, and the next rank is skipped (1, 1, 3).</p>
      <div>
        <h3 className="ac-subheading">Categories</h3>
        <p className="ac-muted">A student earns the highest category whose minimum their rounded average meets.</p>
        <div className="rs-rows">{form.categories.map((row, index) => <div className="rs-row" key={index}>
          <label>Category name<input value={row.name} maxLength={100} onChange={event => updateRow('categories', index, { name: event.target.value })} /></label>
          <label>Minimum average<input type="number" min="0" max="100" step="any" value={row.minimumAverage} onChange={event => updateRow('categories', index, { minimumAverage: event.target.value })} /></label>
          <Button variant="secondary" onClick={() => removeRow('categories', index)} disabled={form.categories.length === 1} aria-label={`Remove category ${index + 1}`}>Remove</Button>
        </div>)}</div>
        {editable && <Button variant="secondary" onClick={() => update('categories', [...form.categories, { name: '', minimumAverage: '' }])} disabled={form.categories.length >= 20}>Add category</Button>}
      </div>
      <div>
        <h3 className="ac-subheading">Worked examples</h3>
        <p className="ac-muted">School-supplied cases. Each must match what these rules compute, or the server rejects the draft.</p>
        {form.examples.map((example, index) => <div className="rs-stack panel rs-section" key={index}>
          <div className="rs-grid">
            <label>Example name<input value={example.name} maxLength={200} onChange={event => updateRow('examples', index, { name: event.target.value })} /></label>
            {form.subjects.map((subject, at) => <label key={at}>{subject.subjectId ? subjectName(subject.subjectId) : `Subject ${at + 1}`}<input type="number" min="0" max="100" step="any" value={example.values[at] ?? ''}
              onChange={event => updateRow('examples', index, { values: form.subjects.map((_, position) => (position === at ? event.target.value : example.values[position] ?? '')) })} /></label>)}
            <label>Expected average<input type="number" min="0" max="100" step="any" value={example.expectedAverage} onChange={event => updateRow('examples', index, { expectedAverage: event.target.value })} /></label>
            <label>Expected category<input value={example.expectedCategory} maxLength={100} placeholder="None" onChange={event => updateRow('examples', index, { expectedCategory: event.target.value })} /></label>
          </div>
          <div className="rs-actions">
            <span className="ac-muted" aria-live="polite">{computed[index].average === null ? 'Fill in every grade to see the computed result.' : `These rules compute ${computed[index].average}, ${computed[index].category ? `category “${computed[index].category}”` : 'no category'}.`}</span>
            {editable && computed[index].average !== null && <Button variant="secondary" onClick={() => updateRow('examples', index, { expectedAverage: computed[index].average, expectedCategory: computed[index].category ?? '' })}>Use computed result</Button>}
            {editable && <Button variant="secondary" onClick={() => removeRow('examples', index)} disabled={form.examples.length === 1}>Remove example</Button>}
          </div>
        </div>)}
        {editable && <Button variant="secondary" onClick={() => update('examples', [...form.examples, { name: '', values: form.subjects.map(() => ''), expectedAverage: '', expectedCategory: '' }])} disabled={form.examples.length >= 50}>Add example</Button>}
      </div>
    </fieldset>
    {error && <div className="notice notice-error" role="alert">{error}</div>}
    <div className="rs-actions">
      {editable && <Button onClick={save} disabled={Boolean(busy)}>{busy === 'save' ? 'Saving…' : policy ? 'Save draft' : 'Create draft'}</Button>}
    </div>
    {policy && status === 'draft' && canApprove && <div className="rs-stack rs-divider">
      <h3 className="ac-subheading">Approve</h3>
      <label className="rs-field">School approval reference<input value={reference} maxLength={2000} placeholder="For example: board resolution or minutes identifier" onChange={event => setReference(event.target.value)} /></label>
      <div className="rs-actions"><Button onClick={() => setConfirming(true)} disabled={!reference.trim() || Boolean(busy)}>Approve policy</Button></div>
    </div>}
    <Dialog open={confirming} onClose={() => setConfirming(false)} title="Approve this honor policy?" actions={<>
      <Button variant="secondary" onClick={() => setConfirming(false)} disabled={busy === 'approve'}>Cancel</Button>
      <Button onClick={approve} disabled={busy === 'approve'}>{busy === 'approve' ? 'Approving…' : 'Approve'}</Button>
    </>}>
      <p>Approving records you as the approver with reference “{reference.trim()}”. Any approved policy for the same scope becomes superseded, and this policy can no longer be edited.</p>
    </Dialog>
  </section>;
}

function PoliciesView({ scope, complete, subjects, canConfigure, canApprove }) {
  const [list, setList] = useState({ status: 'idle', items: [], error: '' });
  const [open, setOpen] = useState(null);
  const [notice, setNotice] = useState('');
  const [revision, setRevision] = useState(0);

  useEffect(() => { setOpen(null); setNotice(''); }, [scope]);
  useEffect(() => {
    const controller = new AbortController();
    setList(current => ({ ...current, status: 'loading', error: '' }));
    loadPolicies(scope, { signal: controller.signal })
      .then(data => setList({ status: 'ready', items: data?.items ?? [], error: '' }))
      .catch(failure => { if (failure?.name !== 'AbortError') setList({ status: 'error', items: [], error: failure?.message || 'The honor policies could not be loaded.' }); });
    return () => controller.abort();
  }, [scope, revision]);

  const hasDraft = list.items.some(item => item.status === 'draft');
  return <>
    <section className="panel" aria-labelledby="policies-title">
      <div className="ac-report-head">
        <h2 id="policies-title">Honor policies</h2>
        {canConfigure && <div className="ac-filter-actions">
          <Button variant="secondary" onClick={() => { setNotice(''); setOpen({ id: 'new' }); }} disabled={!complete}>New draft</Button>
        </div>}
      </div>
      {!complete && canConfigure && <p className="ac-muted rs-pad">Choose the full scope above to start a new draft.</p>}
      {complete && hasDraft && <p className="ac-muted rs-pad">This scope already has a draft. Continue that one unless you need a separate version.</p>}
      <Failure error={list.error} reload={() => setRevision(value => value + 1)} />
      {list.status === 'loading' && <p role="status" className="rs-pad">Loading policies…</p>}
      {list.status === 'ready' && list.items.length === 0 && <div className="empty-state"><h3>No honor policies for this scope yet</h3>{canConfigure && <p>Choose the full scope and create a draft.</p>}</div>}
      {list.items.length > 0 && <div className="setup-table-wrap"><table className="ac-table">
        <thead><tr><th scope="col">Policy</th><th scope="col">Status</th><th scope="col">Approval</th><th scope="col"><span className="sr-only">Actions</span></th></tr></thead>
        <tbody>{list.items.map(item => {
          const [tone, label] = statusChip[item.status] ?? ['neutral', item.status];
          return <tr key={item.id}>
            <td data-label="Policy"><strong>#{item.id} · Grade {item.grade_level}</strong><small>Version {item.version} · changed {formatTime(item.updated_at)}</small></td>
            <td data-label="Status"><StatusChip tone={tone}>{label}</StatusChip></td>
            <td data-label="Approval">{item.approval_reference ? <><strong>{item.approval_reference}</strong><small>{formatTime(item.approved_at)}</small></> : '—'}</td>
            <td data-label="Actions"><div className="ac-actions"><Button variant="secondary" onClick={() => { setNotice(''); setOpen(item); }}>{item.status === 'draft' && canConfigure ? 'Edit' : 'View'}</Button></div></td>
          </tr>;
        })}</tbody>
      </table></div>}
    </section>
    {notice && <p className="notice" role="status">{notice}</p>}
    {open && <PolicyEditor key={`${open.id}-${open.version ?? 0}`} policy={open.id === 'new' ? null : open} scope={scope} subjects={subjects}
      canConfigure={canConfigure} canApprove={canApprove} onClose={() => setOpen(null)}
      onSaved={(saved, message) => { setOpen(saved); setNotice(message); setRevision(value => value + 1); }} />}
  </>;
}

export default function HonorRoll() {
  const { roles = [] } = useAuth();
  const canConfigure = roles.includes('registrar') || roles.includes('admin');
  const canApprove = roles.includes('principal') || roles.includes('admin');
  const [tab, setTab] = useState('roll');
  const [options, setOptions] = useState(null);
  const [programs, setPrograms] = useState([]);
  const [subjects, setSubjects] = useState([]);
  const [error, setError] = useState('');
  const [scope, setScope] = useState(emptyScope);

  useEffect(() => {
    let active = true;
    loadHonorFilters().then(data => active && setOptions(data)).catch(failure => active && setError(failure?.message || 'The report filters could not be loaded.'));
    // Names are a convenience: without access to these catalogs the screen falls back to ids from the policies.
    loadPrograms().then(items => active && setPrograms(items)).catch(() => {});
    loadSubjects().then(items => active && setSubjects((items ?? []).filter(item => item.active !== false))).catch(() => {});
    return () => { active = false; };
  }, []);

  // Staff who cannot read the program catalog can still pick the programs that have policies.
  useEffect(() => {
    if (programs.length) return undefined;
    let active = true;
    loadPolicies({}).then(data => {
      if (!active) return;
      const ids = [...new Set((data?.items ?? []).map(item => item.program_id))];
      setPrograms(current => (current.length ? current : ids.map(id => ({ id, name: `Program #${id}` }))));
    }).catch(() => {});
    return () => { active = false; };
  }, [programs.length]);

  const complete = Object.values(scope).every(Boolean);
  return <div className="enrollment-workspace ac-screen rs-screen">
    <header className="page-heading">
      <p className="eyebrow">Reporting</p>
      <h1>Honor Roll</h1>
      <p>Ranked averages and honor categories under a school-approved honor policy.</p>
    </header>
    <Failure error={error} />
    <ScopePicker options={options} programs={programs} scope={scope} onChange={setScope} />
    <div className="rs-tabs" role="tablist" aria-label="Honor roll views">
      <button type="button" role="tab" aria-selected={tab === 'roll'} onClick={() => setTab('roll')}>Honor roll</button>
      <button type="button" role="tab" aria-selected={tab === 'policies'} onClick={() => setTab('policies')}>Honor policies</button>
    </div>
    {tab === 'roll'
      ? <RollView scope={scope} complete={complete} onOpenPolicies={() => setTab('policies')} />
      : <PoliciesView scope={scope} complete={complete} subjects={subjects} canConfigure={canConfigure} canApprove={canApprove} />}
  </div>;
}
