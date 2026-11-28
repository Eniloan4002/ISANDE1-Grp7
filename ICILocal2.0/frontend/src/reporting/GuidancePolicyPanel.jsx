import { useEffect, useState } from 'react';
import { Failure } from '../EnrollmentWorkspace.jsx';
import { formatGuidanceError, getGuidancePolicy, saveGuidancePolicy } from './guidanceApi.js';

export default function GuidancePolicyPanel({
  campuses = [],
  terms = [],
  initialCampusId = '',
  initialTermId = '',
  canConfigure = false,
  onSaved,
}) {
  const [campusId, setCampusId] = useState(initialCampusId ? String(initialCampusId) : '');
  const [termId, setTermId] = useState(initialTermId ? String(initialTermId) : '');
  const [policy, setPolicy] = useState(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState('');

  const [failingBelow, setFailingBelow] = useState('');
  const [consecutiveAbsences, setConsecutiveAbsences] = useState('');
  const [credentialsDueOn, setCredentialsDueOn] = useState('');
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [notice, setNotice] = useState('');
  const [tick, setTick] = useState(0);

  useEffect(() => {
    if (initialCampusId && String(initialCampusId) !== campusId) {
      setCampusId(String(initialCampusId));
    } else if (!campusId && campuses.length > 0) {
      setCampusId(String(campuses[0].id));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialCampusId, campuses]);

  useEffect(() => {
    if (initialTermId && String(initialTermId) !== termId) {
      setTermId(String(initialTermId));
    } else if (!termId && terms.length > 0) {
      setTermId(String(terms[0].id));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialTermId, terms]);

  useEffect(() => {
    if (!campusId || !termId) {
      setPolicy(null);
      return;
    }
    let live = true;
    setLoading(true);
    setLoadError('');
    setSaveError('');
    getGuidancePolicy(campusId, termId)
      .then(data => {
        if (!live) return;
        setPolicy(data);
        setFailingBelow(data.failingBelow != null ? String(data.failingBelow) : '');
        setConsecutiveAbsences(data.consecutiveAbsences != null ? String(data.consecutiveAbsences) : '');
        setCredentialsDueOn(data.credentialsDueOn ? String(data.credentialsDueOn) : '');
        setReason(data.reason || '');
      })
      .catch(err => {
        if (live && err?.name !== 'AbortError') {
          setPolicy(null);
          setLoadError(formatGuidanceError(err, 'Guidance policy thresholds could not be loaded.'));
        }
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [campusId, termId, tick]);

  async function handleSave(event) {
    event.preventDefault();
    if (!campusId || !termId || !reason.trim()) return;
    setBusy(true);
    setSaveError('');
    setNotice('');
    try {
      const updated = await saveGuidancePolicy(campusId, termId, {
        failingBelow,
        consecutiveAbsences,
        credentialsDueOn,
        reason: reason.trim(),
        version: policy?.version ?? 0,
      });
      setPolicy(updated);
      setNotice('Guidance risk thresholds saved.');
      onSaved?.(updated);
    } catch (err) {
      setSaveError(formatGuidanceError(err, 'Guidance risk thresholds could not be saved.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="panel" aria-labelledby="guidance-policy-heading">
      <h2 id="guidance-policy-heading">Risk thresholds policy</h2>
      <div className="panel-body">
        <p className="field-help" style={{ marginTop: 0 }}>
          At-risk signals are evaluated explicitly from approved per-campus, per-term thresholds (never guessed).
        </p>

        <div className="guidance-filters">
          <div>
            <label htmlFor="policy-campus">Campus</label>
            <select
              id="policy-campus"
              disabled={busy || campuses.length === 0}
              value={campusId}
              onChange={event => {
                setCampusId(event.target.value);
                setNotice('');
              }}
            >
              <option value="">Select campus…</option>
              {campuses.map(campus => (
                <option key={campus.id} value={campus.id}>
                  {campus.name}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label htmlFor="policy-term">Academic term</label>
            <select
              id="policy-term"
              disabled={busy || terms.length === 0}
              value={termId}
              onChange={event => {
                setTermId(event.target.value);
                setNotice('');
              }}
            >
              <option value="">Select term…</option>
              {terms.map(term => (
                <option key={term.id} value={term.id}>
                  {term.academicYear ? `${term.academicYear} / ` : ''}
                  {term.name}
                </option>
              ))}
            </select>
          </div>
          <button
            type="button"
            className="button button-outline"
            disabled={loading || !campusId || !termId}
            onClick={() => setTick(value => value + 1)}
          >
            Reload policy
          </button>
        </div>

        <Failure error={loadError} reload={() => setTick(value => value + 1)} />
        <Failure error={saveError} />
        {notice && (
          <p className="notice" role="status">
            {notice}
          </p>
        )}
        {loading && <p role="status">Loading risk thresholds…</p>}

        {!loading && (!campusId || !termId) && (
          <p className="field-help">Select a campus and term to inspect or configure risk thresholds.</p>
        )}

        {!loading && policy && (
          <>
            <dl className="guidance-meta-grid">
              <div>
                <dt>Configuration state</dt>
                <dd>
                  <span className={`status ${policy.configured ? 'status-success' : 'status-warning'}`}>
                    {policy.configured ? `Configured (v${policy.version})` : 'Not configured'}
                  </span>
                </dd>
              </div>
              <div>
                <dt>Failing grade below</dt>
                <dd>{policy.failingBelow != null ? `${policy.failingBelow}` : 'Not set'}</dd>
              </div>
              <div>
                <dt>Consecutive unexcused absences</dt>
                <dd>
                  {policy.consecutiveAbsences != null
                    ? `${policy.consecutiveAbsences} school day(s)`
                    : 'Not set'}
                </dd>
              </div>
              <div>
                <dt>Credentials due date</dt>
                <dd>{policy.credentialsDueOn || 'Not set'}</dd>
              </div>
            </dl>

            {canConfigure ? (
              <form onSubmit={handleSave}>
                <div className="guidance-form-grid">
                  <div>
                    <label htmlFor="policy-failing-below">Failing grade below (0–100)</label>
                    <input
                      id="policy-failing-below"
                      type="number"
                      min="0"
                      max="100"
                      step="0.01"
                      disabled={busy}
                      value={failingBelow}
                      onChange={event => setFailingBelow(event.target.value)}
                      placeholder="e.g. 75.00"
                    />
                  </div>
                  <div>
                    <label htmlFor="policy-consecutive-absences">Consecutive absences (days)</label>
                    <input
                      id="policy-consecutive-absences"
                      type="number"
                      min="1"
                      max="366"
                      step="1"
                      disabled={busy}
                      value={consecutiveAbsences}
                      onChange={event => setConsecutiveAbsences(event.target.value)}
                      placeholder="e.g. 3"
                    />
                  </div>
                  <div>
                    <label htmlFor="policy-credentials-due">Credentials due date</label>
                    <input
                      id="policy-credentials-due"
                      type="date"
                      disabled={busy}
                      value={credentialsDueOn}
                      onChange={event => setCredentialsDueOn(event.target.value)}
                    />
                  </div>
                </div>

                <label htmlFor="policy-reason">Approval reference / policy reason (required)</label>
                <textarea
                  id="policy-reason"
                  className="guidance-textarea"
                  required
                  maxLength={2000}
                  disabled={busy}
                  value={reason}
                  onChange={event => setReason(event.target.value)}
                  placeholder="Cite the approved academic memo or policy update…"
                />

                <button type="submit" className="button" disabled={busy || !reason.trim()}>
                  {busy ? 'Saving thresholds…' : 'Save risk thresholds'}
                </button>
              </form>
            ) : (
              <p className="field-help">
                {policy.reason && (
                  <>
                    <strong>Policy note:</strong> {policy.reason}
                    <br />
                  </>
                )}
                Threshold edits require Registrar or System Administrator configuration access (<code>guidance.configure</code>).
              </p>
            )}
          </>
        )}
      </div>
    </section>
  );
}
