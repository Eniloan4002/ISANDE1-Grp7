import { useEffect, useMemo, useState } from 'react';
import { Failure } from '../EnrollmentWorkspace.jsx';
import {
  buildDateRangeDays,
  extractLastChanged,
  loadAttendanceWindow,
  loadGuidancePolicySetting,
  loadLatestSettingAudit,
  loadSchoolCalendar,
  loadSettingsReferenceData,
  saveAttendanceWindow,
  saveGuidancePolicySetting,
  saveSchoolCalendar,
} from './systemSettingsApi.js';
import './system-settings.css';

function formatChangedStamp(meta) {
  if (!meta) return 'Last changed: Not recorded yet';
  const parts = [];
  if (meta.who) parts.push(`by ${meta.who}`);
  if (meta.when) {
    const parsed = new Date(meta.when);
    parts.push(`on ${Number.isNaN(parsed.getTime()) ? meta.when : parsed.toLocaleString()}`);
  }
  return `Last changed ${parts.join(' ')}`;
}

export default function SystemSettings() {
  const [campuses, setCampuses] = useState([]);
  const [terms, setTerms] = useState([]);
  const [refLoading, setRefLoading] = useState(true);
  const [refError, setRefError] = useState(null);
  const [refReload, setRefReload] = useState(0);

  const [campusId, setCampusId] = useState('');
  const [termId, setTermId] = useState('');
  const [reloadTick, setReloadTick] = useState(0);

  // 1. Attendance window state
  const [windowData, setWindowData] = useState(null);
  const [windowAudit, setWindowAudit] = useState(null);
  const [windowLoading, setWindowLoading] = useState(false);
  const [windowError, setWindowError] = useState(null);
  const [windowForm, setWindowForm] = useState({
    startsOn: '',
    endsOn: '',
    orientationStartsOn: '',
    orientationEndsOn: '',
    approvalNote: '',
  });
  const [windowSaving, setWindowSaving] = useState(false);
  const [windowSaveError, setWindowSaveError] = useState('');
  const [windowNotice, setWindowNotice] = useState('');

  // 2. Guidance risk policy state
  const [policyData, setPolicyData] = useState(null);
  const [policyAudit, setPolicyAudit] = useState(null);
  const [policyLoading, setPolicyLoading] = useState(false);
  const [policyError, setPolicyError] = useState(null);
  const [policyForm, setPolicyForm] = useState({
    failingBelow: '',
    consecutiveAbsences: '',
    credentialsDueOn: '',
    reason: '',
  });
  const [policySaving, setPolicySaving] = useState(false);
  const [policySaveError, setPolicySaveError] = useState('');
  const [policyNotice, setPolicyNotice] = useState('');

  // 3. School-day calendar state
  const [calendarData, setCalendarData] = useState(null);
  const [calendarAudit, setCalendarAudit] = useState(null);
  const [calendarLoading, setCalendarLoading] = useState(false);
  const [calendarError, setCalendarError] = useState(null);
  const [singleDay, setSingleDay] = useState({ date: '', schoolDay: 'true', reason: '' });
  const [rangeForm, setRangeForm] = useState({ start: '', end: '', weekendsOff: true, reason: '' });
  const [calendarSaving, setCalendarSaving] = useState(false);
  const [calendarSaveError, setCalendarSaveError] = useState('');
  const [calendarNotice, setCalendarNotice] = useState('');

  // Load campuses & academic terms
  useEffect(() => {
    const controller = new AbortController();
    setRefLoading(true);
    setRefError(null);
    loadSettingsReferenceData(controller.signal)
      .then(data => {
        if (controller.signal.aborted) return;
        const nextCampuses = Array.isArray(data?.campuses) ? data.campuses : [];
        const nextTerms = Array.isArray(data?.terms) ? data.terms : [];
        setCampuses(nextCampuses);
        setTerms(nextTerms);
        setCampusId(prev => prev || (nextCampuses[0] ? String(nextCampuses[0].id) : ''));
        setTermId(prev => prev || (nextTerms[0] ? String(nextTerms[0].id) : ''));
      })
      .catch(err => {
        if (err?.name === 'AbortError' || controller.signal.aborted) return;
        setRefError(err);
      })
      .finally(() => {
        if (!controller.signal.aborted) setRefLoading(false);
      });
    return () => controller.abort();
  }, [refReload]);

  const selectedTerm = useMemo(
    () => terms.find(item => String(item.id) === String(termId)) || null,
    [terms, termId]
  );

  // Sync default calendar range dates with the selected term bounds
  useEffect(() => {
    if (!selectedTerm) return;
    setSingleDay(prev => ({
      ...prev,
      date: prev.date || selectedTerm.startsOn || '',
    }));
    setRangeForm(prev => ({
      ...prev,
      start: selectedTerm.startsOn || '',
      end: selectedTerm.endsOn || '',
    }));
  }, [selectedTerm]);

  // Load all 3 settings whenever campusId / termId / reloadTick changes
  useEffect(() => {
    if (!campusId || !termId) return;
    const controller = new AbortController();

    setWindowLoading(true);
    setPolicyLoading(true);
    setCalendarLoading(true);
    setWindowError(null);
    setPolicyError(null);
    setCalendarError(null);
    setWindowSaveError('');
    setPolicySaveError('');
    setCalendarSaveError('');
    setWindowNotice('');
    setPolicyNotice('');
    setCalendarNotice('');

    loadAttendanceWindow(campusId, termId, controller.signal)
      .then(data => {
        if (controller.signal.aborted) return;
        setWindowData(data);
        setWindowForm({
          startsOn: data?.startsOn ? String(data.startsOn) : '',
          endsOn: data?.endsOn ? String(data.endsOn) : '',
          orientationStartsOn: data?.orientationStartsOn ? String(data.orientationStartsOn) : '',
          orientationEndsOn: data?.orientationEndsOn ? String(data.orientationEndsOn) : '',
          approvalNote: data?.approvalNote || '',
        });
      })
      .catch(err => {
        if (err?.name !== 'AbortError' && !controller.signal.aborted) setWindowError(err);
      })
      .finally(() => {
        if (!controller.signal.aborted) setWindowLoading(false);
      });

    loadGuidancePolicySetting(campusId, termId, controller.signal)
      .then(data => {
        if (controller.signal.aborted) return;
        setPolicyData(data);
        setPolicyForm({
          failingBelow: data?.failingBelow != null ? String(data.failingBelow) : '',
          consecutiveAbsences: data?.consecutiveAbsences != null ? String(data.consecutiveAbsences) : '',
          credentialsDueOn: data?.credentialsDueOn ? String(data.credentialsDueOn) : '',
          reason: data?.reason || '',
        });
      })
      .catch(err => {
        if (err?.name !== 'AbortError' && !controller.signal.aborted) setPolicyError(err);
      })
      .finally(() => {
        if (!controller.signal.aborted) setPolicyLoading(false);
      });

    loadSchoolCalendar(campusId, termId, controller.signal)
      .then(data => {
        if (controller.signal.aborted) return;
        setCalendarData(data);
      })
      .catch(err => {
        if (err?.name !== 'AbortError' && !controller.signal.aborted) setCalendarError(err);
      })
      .finally(() => {
        if (!controller.signal.aborted) setCalendarLoading(false);
      });

    // Best-effort audit lookup for "who last changed and when"
    loadLatestSettingAudit('enrollment_attendance_windows', campusId, controller.signal).then(meta => {
      if (!controller.signal.aborted) setWindowAudit(meta);
    });
    loadLatestSettingAudit('guidance_policies', campusId, controller.signal).then(meta => {
      if (!controller.signal.aborted) setPolicyAudit(meta);
    });
    loadLatestSettingAudit('school_calendar_days', campusId, controller.signal).then(meta => {
      if (!controller.signal.aborted) setCalendarAudit(meta);
    });

    return () => controller.abort();
  }, [campusId, termId, reloadTick]);

  const calendarItems = useMemo(
    () => (Array.isArray(calendarData?.items) ? calendarData.items : []),
    [calendarData]
  );

  const calendarStats = useMemo(() => {
    let schoolDays = 0;
    let nonSchoolDays = 0;
    for (const item of calendarItems) {
      if (item.schoolDay) schoolDays++;
      else nonSchoolDays++;
    }
    return { total: calendarItems.length, schoolDays, nonSchoolDays };
  }, [calendarItems]);

  async function handleSaveWindow(event) {
    event.preventDefault();
    setWindowSaveError('');
    setWindowNotice('');
    if (!windowForm.startsOn || !windowForm.endsOn) {
      setWindowSaveError('Provide both first-week start and end dates.');
      return;
    }
    if (windowForm.endsOn < windowForm.startsOn) {
      setWindowSaveError('First-week end date must be on or after the start date.');
      return;
    }
    if (Boolean(windowForm.orientationStartsOn) !== Boolean(windowForm.orientationEndsOn)) {
      setWindowSaveError('Provide both orientation start and end dates, or leave both blank.');
      return;
    }
    if (
      windowForm.orientationStartsOn &&
      windowForm.orientationEndsOn &&
      windowForm.orientationEndsOn < windowForm.orientationStartsOn
    ) {
      setWindowSaveError('Orientation end date must be on or after the orientation start date.');
      return;
    }
    if (!windowForm.approvalNote.trim()) {
      setWindowSaveError('Enter an approval note explaining this attendance window.');
      return;
    }

    setWindowSaving(true);
    try {
      const updated = await saveAttendanceWindow(campusId, termId, {
        ...windowForm,
        version: windowData?.version ?? 0,
      });
      setWindowData(updated);
      setWindowNotice(`Attendance window saved (version ${updated.version}).`);
      const freshAudit = await loadLatestSettingAudit('enrollment_attendance_windows', campusId);
      if (freshAudit) setWindowAudit(freshAudit);
    } catch (err) {
      setWindowSaveError(err?.message || 'Unable to save attendance window.');
    } finally {
      setWindowSaving(false);
    }
  }

  async function handleSavePolicy(event) {
    event.preventDefault();
    setPolicySaveError('');
    setPolicyNotice('');
    if (!policyForm.reason.trim()) {
      setPolicySaveError('Enter a reason for updating the risk thresholds.');
      return;
    }
    setPolicySaving(true);
    try {
      const updated = await saveGuidancePolicySetting(campusId, termId, {
        ...policyForm,
        version: policyData?.version ?? 0,
      });
      setPolicyData(updated);
      setPolicyNotice(`Guidance risk thresholds saved (version ${updated.version}).`);
      const freshAudit = await loadLatestSettingAudit('guidance_policies', campusId);
      if (freshAudit) setPolicyAudit(freshAudit);
    } catch (err) {
      setPolicySaveError(err?.message || 'Unable to save guidance risk thresholds.');
    } finally {
      setPolicySaving(false);
    }
  }

  async function handleSaveSingleDay(event) {
    event.preventDefault();
    setCalendarSaveError('');
    setCalendarNotice('');
    if (!singleDay.date) {
      setCalendarSaveError('Choose a calendar date.');
      return;
    }
    if (!singleDay.reason.trim()) {
      setCalendarSaveError('Enter a reason for the calendar change.');
      return;
    }
    const existing = calendarItems.find(item => String(item.date) === singleDay.date);
    setCalendarSaving(true);
    try {
      const updated = await saveSchoolCalendar(
        campusId,
        termId,
        [
          {
            date: singleDay.date,
            schoolDay: singleDay.schoolDay === 'true',
            version: existing?.version ?? 0,
          },
        ],
        singleDay.reason
      );
      setCalendarData(updated);
      setCalendarNotice(`Calendar updated for ${singleDay.date}.`);
      const freshAudit = await loadLatestSettingAudit('school_calendar_days', campusId);
      if (freshAudit) setCalendarAudit(freshAudit);
    } catch (err) {
      setCalendarSaveError(err?.message || 'Unable to save calendar day.');
    } finally {
      setCalendarSaving(false);
    }
  }

  async function handleSaveRange(event) {
    event.preventDefault();
    setCalendarSaveError('');
    setCalendarNotice('');
    if (!rangeForm.start || !rangeForm.end || rangeForm.end < rangeForm.start) {
      setCalendarSaveError('Choose a valid start and end date range.');
      return;
    }
    if (!rangeForm.reason.trim()) {
      setCalendarSaveError('Enter a reason for generating the school-day range.');
      return;
    }
    const days = buildDateRangeDays(rangeForm.start, rangeForm.end, calendarItems, rangeForm.weekendsOff);
    if (!days.length) {
      setCalendarSaveError('No dates were generated for the chosen range.');
      return;
    }
    setCalendarSaving(true);
    try {
      const updated = await saveSchoolCalendar(campusId, termId, days, rangeForm.reason);
      setCalendarData(updated);
      setCalendarNotice(`Saved ${days.length} calendar days (${rangeForm.start} to ${rangeForm.end}).`);
      const freshAudit = await loadLatestSettingAudit('school_calendar_days', campusId);
      if (freshAudit) setCalendarAudit(freshAudit);
    } catch (err) {
      setCalendarSaveError(err?.message || 'Unable to save school-day range.');
    } finally {
      setCalendarSaving(false);
    }
  }

  async function handleToggleExistingDay(item) {
    setCalendarSaveError('');
    setCalendarNotice('');
    const reason =
      singleDay.reason.trim() ||
      `Toggled ${item.date} to ${item.schoolDay ? 'non-school day' : 'school day'}`;
    setCalendarSaving(true);
    try {
      const updated = await saveSchoolCalendar(
        campusId,
        termId,
        [{ date: String(item.date), schoolDay: !item.schoolDay, version: item.version ?? 0 }],
        reason
      );
      setCalendarData(updated);
      setCalendarNotice(
        `Marked ${item.date} as ${!item.schoolDay ? 'school day' : 'non-school day'}.`
      );
      const freshAudit = await loadLatestSettingAudit('school_calendar_days', campusId);
      if (freshAudit) setCalendarAudit(freshAudit);
    } catch (err) {
      setCalendarSaveError(err?.message || 'Unable to update calendar day.');
    } finally {
      setCalendarSaving(false);
    }
  }

  const windowLastChanged = extractLastChanged(windowData, windowAudit);
  const policyLastChanged = extractLastChanged(policyData, policyAudit);
  const calendarLastChanged = extractLastChanged(calendarData, calendarAudit);

  return (
    <div className="settings-page">
      <header className="settings-header">
        <div>
          <p className="eyebrow">System Administrator · Configuration</p>
          <h1>System Settings</h1>
          <p>
            Manage campus-and-term attendance windows, guidance risk thresholds, and official school-day calendars with
            version-checked saves.
          </p>
        </div>
      </header>

      <Failure error={refError} reload={() => setRefReload(v => v + 1)} />

      <section className="panel" aria-labelledby="settings-scope-heading">
        <h2 id="settings-scope-heading">Campus &amp; academic term scope</h2>
        {refLoading && <p role="status">Loading campuses and academic terms…</p>}
        <div className="settings-scope-grid">
          <label className="settings-field">
            Campus
            <select
              aria-label="Settings campus"
              value={campusId}
              disabled={refLoading || campuses.length === 0}
              onChange={event => setCampusId(event.target.value)}
            >
              <option value="">Select campus…</option>
              {campuses.map(campus => (
                <option key={campus.id} value={String(campus.id)}>
                  {campus.name}
                </option>
              ))}
            </select>
          </label>

          <label className="settings-field">
            Academic term
            <select
              aria-label="Settings academic term"
              value={termId}
              disabled={refLoading || terms.length === 0}
              onChange={event => setTermId(event.target.value)}
            >
              <option value="">Select term…</option>
              {terms.map(term => (
                <option key={term.id} value={String(term.id)}>
                  {term.academicYear ? `${term.academicYear} / ` : ''}
                  {term.name}
                </option>
              ))}
            </select>
          </label>

          <button
            type="button"
            className="button button-outline"
            disabled={!campusId || !termId || windowLoading || policyLoading || calendarLoading}
            onClick={() => setReloadTick(v => v + 1)}
          >
            Reload all settings
          </button>
        </div>

        {selectedTerm?.startsOn && selectedTerm?.endsOn && (
          <p className="field-help" style={{ marginTop: '0.65rem', marginBottom: 0 }}>
            Selected term window: <strong>{selectedTerm.startsOn}</strong> to <strong>{selectedTerm.endsOn}</strong>
          </p>
        )}
      </section>

      {campusId && termId && (
        <>
          {/* 1. Orientation & First-Week Attendance Window */}
          <section className="panel settings-section" aria-labelledby="window-settings-heading">
            <div className="settings-section-head">
              <div>
                <h2 id="window-settings-heading">Orientation &amp; First-Week Attendance Window</h2>
                <p>Approved date window used to validate enrollment orientation and first-week attendance.</p>
              </div>
              <div className="settings-badges">
                <span className={`status status-${windowData?.configured ? 'success' : 'warning'}`}>
                  {windowData?.configured ? `Configured (v${windowData.version})` : 'Not configured'}
                </span>
              </div>
            </div>

            <div className="settings-audit-stamp" data-testid="window-last-changed">
              {formatChangedStamp(windowLastChanged)}
            </div>

            <Failure error={windowError} reload={() => setReloadTick(v => v + 1)} />
            {windowSaveError && (
              <div className="notice notice-error" role="alert">
                {windowSaveError}
              </div>
            )}
            {windowNotice && (
              <p className="notice" role="status">
                {windowNotice}
              </p>
            )}
            {windowLoading && <p role="status">Loading attendance window…</p>}

            {!windowLoading && !windowError && (
              <form className="settings-section" onSubmit={handleSaveWindow} noValidate>
                <div className="settings-form-grid settings-form-grid-2">
                  <label className="settings-field">
                    First-week start date
                    <input
                      type="date"
                      aria-label="First-week start date"
                      value={windowForm.startsOn}
                      onChange={event => setWindowForm(prev => ({ ...prev, startsOn: event.target.value }))}
                    />
                  </label>
                  <label className="settings-field">
                    First-week end date
                    <input
                      type="date"
                      aria-label="First-week end date"
                      value={windowForm.endsOn}
                      onChange={event => setWindowForm(prev => ({ ...prev, endsOn: event.target.value }))}
                    />
                  </label>
                  <label className="settings-field">
                    Orientation start date (optional)
                    <input
                      type="date"
                      aria-label="Orientation start date"
                      value={windowForm.orientationStartsOn}
                      onChange={event =>
                        setWindowForm(prev => ({ ...prev, orientationStartsOn: event.target.value }))
                      }
                    />
                  </label>
                  <label className="settings-field">
                    Orientation end date (optional)
                    <input
                      type="date"
                      aria-label="Orientation end date"
                      value={windowForm.orientationEndsOn}
                      onChange={event =>
                        setWindowForm(prev => ({ ...prev, orientationEndsOn: event.target.value }))
                      }
                    />
                  </label>
                </div>

                <label className="settings-field">
                  Approval note
                  <textarea
                    aria-label="Attendance window approval note"
                    maxLength={2000}
                    placeholder="Memorandum reference or approval reason"
                    value={windowForm.approvalNote}
                    onChange={event => setWindowForm(prev => ({ ...prev, approvalNote: event.target.value }))}
                  />
                </label>

                <div className="settings-actions">
                  <button type="submit" className="button" disabled={windowSaving}>
                    {windowSaving ? 'Saving attendance window…' : 'Save attendance window'}
                  </button>
                  <button
                    type="button"
                    className="button button-outline"
                    disabled={windowSaving}
                    onClick={() => setReloadTick(v => v + 1)}
                  >
                    Reload latest version
                  </button>
                </div>
              </form>
            )}
          </section>

          {/* 2. Guidance Risk Thresholds */}
          <section className="panel settings-section" aria-labelledby="policy-settings-heading">
            <div className="settings-section-head">
              <div>
                <h2 id="policy-settings-heading">Guidance Risk Thresholds</h2>
                <p>Per-campus, per-term thresholds that flag at-risk students for Guidance and Advisers.</p>
              </div>
              <div className="settings-badges">
                <span className={`status status-${policyData?.configured ? 'success' : 'warning'}`}>
                  {policyData?.configured ? `Configured (v${policyData.version})` : 'Not configured'}
                </span>
              </div>
            </div>

            <div className="settings-audit-stamp" data-testid="policy-last-changed">
              {formatChangedStamp(policyLastChanged)}
            </div>

            <Failure error={policyError} reload={() => setReloadTick(v => v + 1)} />
            {policySaveError && (
              <div className="notice notice-error" role="alert">
                {policySaveError}
              </div>
            )}
            {policyNotice && (
              <p className="notice" role="status">
                {policyNotice}
              </p>
            )}
            {policyLoading && <p role="status">Loading risk thresholds…</p>}

            {!policyLoading && !policyError && (
              <form className="settings-section" onSubmit={handleSavePolicy} noValidate>
                <div className="settings-form-grid settings-form-grid-3">
                  <label className="settings-field">
                    Failing grade below (0–100)
                    <input
                      type="number"
                      aria-label="Failing grade below"
                      min="0"
                      max="100"
                      step="0.01"
                      placeholder="e.g. 75"
                      value={policyForm.failingBelow}
                      onChange={event => setPolicyForm(prev => ({ ...prev, failingBelow: event.target.value }))}
                    />
                  </label>
                  <label className="settings-field">
                    Consecutive unexcused absences
                    <input
                      type="number"
                      aria-label="Consecutive unexcused absences"
                      min="1"
                      max="366"
                      step="1"
                      placeholder="e.g. 3"
                      value={policyForm.consecutiveAbsences}
                      onChange={event =>
                        setPolicyForm(prev => ({ ...prev, consecutiveAbsences: event.target.value }))
                      }
                    />
                  </label>
                  <label className="settings-field">
                    Credentials due date
                    <input
                      type="date"
                      aria-label="Credentials due date"
                      value={policyForm.credentialsDueOn}
                      onChange={event =>
                        setPolicyForm(prev => ({ ...prev, credentialsDueOn: event.target.value }))
                      }
                    />
                  </label>
                </div>

                <label className="settings-field">
                  Policy change reason
                  <textarea
                    aria-label="Risk thresholds reason"
                    maxLength={2000}
                    placeholder="Board policy or memo justifying these thresholds"
                    value={policyForm.reason}
                    onChange={event => setPolicyForm(prev => ({ ...prev, reason: event.target.value }))}
                  />
                </label>

                <div className="settings-actions">
                  <button type="submit" className="button" disabled={policySaving}>
                    {policySaving ? 'Saving risk thresholds…' : 'Save risk thresholds'}
                  </button>
                  <button
                    type="button"
                    className="button button-outline"
                    disabled={policySaving}
                    onClick={() => setReloadTick(v => v + 1)}
                  >
                    Reload latest version
                  </button>
                </div>
              </form>
            )}
          </section>

          {/* 3. School-Day Calendar */}
          <section className="panel settings-section" aria-labelledby="calendar-settings-heading">
            <div className="settings-section-head">
              <div>
                <h2 id="calendar-settings-heading">School-Day Calendar</h2>
                <p>
                  Explicitly configure school days and holidays within the academic term for daily attendance and
                  consecutive-absence checks.
                </p>
              </div>
              <div className="settings-badges">
                <span className={`status status-${calendarStats.total > 0 ? 'success' : 'warning'}`}>
                  {calendarStats.total > 0 ? `${calendarStats.total} days configured` : 'No days configured'}
                </span>
              </div>
            </div>

            <div className="settings-audit-stamp" data-testid="calendar-last-changed">
              {formatChangedStamp(calendarLastChanged)}
            </div>

            <Failure error={calendarError} reload={() => setReloadTick(v => v + 1)} />
            {calendarSaveError && (
              <div className="notice notice-error" role="alert">
                {calendarSaveError}
              </div>
            )}
            {calendarNotice && (
              <p className="notice" role="status">
                {calendarNotice}
              </p>
            )}
            {calendarLoading && <p role="status">Loading school-day calendar…</p>}

            {!calendarLoading && !calendarError && (
              <>
                <div className="settings-calendar-stats">
                  <div className="settings-stat-box">
                    <span>Configured days</span>
                    <strong>{calendarStats.total}</strong>
                  </div>
                  <div className="settings-stat-box">
                    <span>School days</span>
                    <strong>{calendarStats.schoolDays}</strong>
                  </div>
                  <div className="settings-stat-box">
                    <span>Non-school / Holidays</span>
                    <strong>{calendarStats.nonSchoolDays}</strong>
                  </div>
                </div>

                <div className="settings-form-grid settings-form-grid-2">
                  <form className="settings-subpanel" onSubmit={handleSaveSingleDay} noValidate>
                    <h3>Add or update a single date</h3>
                    <label className="settings-field">
                      Date
                      <input
                        type="date"
                        aria-label="Calendar single date"
                        min={selectedTerm?.startsOn || undefined}
                        max={selectedTerm?.endsOn || undefined}
                        value={singleDay.date}
                        onChange={event => setSingleDay(prev => ({ ...prev, date: event.target.value }))}
                      />
                    </label>
                    <label className="settings-field">
                      Day type
                      <select
                        aria-label="Calendar day type"
                        value={singleDay.schoolDay}
                        onChange={event => setSingleDay(prev => ({ ...prev, schoolDay: event.target.value }))}
                      >
                        <option value="true">School day (classes held)</option>
                        <option value="false">Non-school day / Holiday</option>
                      </select>
                    </label>
                    <label className="settings-field">
                      Reason
                      <input
                        type="text"
                        aria-label="Single date change reason"
                        maxLength={2000}
                        placeholder="e.g. Regular class day or Charter Day holiday"
                        value={singleDay.reason}
                        onChange={event => setSingleDay(prev => ({ ...prev, reason: event.target.value }))}
                      />
                    </label>
                    <button type="submit" className="button" disabled={calendarSaving}>
                      {calendarSaving ? 'Saving day…' : 'Save calendar day'}
                    </button>
                  </form>

                  <form className="settings-subpanel" onSubmit={handleSaveRange} noValidate>
                    <h3>Configure a date range</h3>
                    <div className="settings-form-grid settings-form-grid-2">
                      <label className="settings-field">
                        Range start
                        <input
                          type="date"
                          aria-label="Calendar range start"
                          min={selectedTerm?.startsOn || undefined}
                          max={selectedTerm?.endsOn || undefined}
                          value={rangeForm.start}
                          onChange={event => setRangeForm(prev => ({ ...prev, start: event.target.value }))}
                        />
                      </label>
                      <label className="settings-field">
                        Range end
                        <input
                          type="date"
                          aria-label="Calendar range end"
                          min={selectedTerm?.startsOn || undefined}
                          max={selectedTerm?.endsOn || undefined}
                          value={rangeForm.end}
                          onChange={event => setRangeForm(prev => ({ ...prev, end: event.target.value }))}
                        />
                      </label>
                    </div>
                    <label className="settings-checkbox-row">
                      <input
                        type="checkbox"
                        checked={rangeForm.weekendsOff}
                        onChange={event => setRangeForm(prev => ({ ...prev, weekendsOff: event.target.checked }))}
                      />
                      <span>Mark Saturdays &amp; Sundays as non-school days</span>
                    </label>
                    <label className="settings-field">
                      Reason
                      <input
                        type="text"
                        aria-label="Range change reason"
                        maxLength={2000}
                        placeholder="e.g. Approved semester school calendar"
                        value={rangeForm.reason}
                        onChange={event => setRangeForm(prev => ({ ...prev, reason: event.target.value }))}
                      />
                    </label>
                    <button type="submit" className="button button-outline" disabled={calendarSaving}>
                      {calendarSaving ? 'Saving range…' : 'Save calendar range'}
                    </button>
                  </form>
                </div>

                {calendarItems.length > 0 && (
                  <div className="settings-table-wrap">
                    <table className="settings-table">
                      <thead>
                        <tr>
                          <th>Date</th>
                          <th>Type</th>
                          <th>Reason</th>
                          <th>Version</th>
                          <th>Action</th>
                        </tr>
                      </thead>
                      <tbody>
                        {calendarItems.map(item => (
                          <tr key={item.date}>
                            <td>{item.date}</td>
                            <td>
                              <span className={`status status-${item.schoolDay ? 'success' : 'warning'}`}>
                                {item.schoolDay ? 'School day' : 'Non-school day'}
                              </span>
                            </td>
                            <td>{item.reason || '—'}</td>
                            <td>v{item.version}</td>
                            <td>
                              <button
                                type="button"
                                className="button button-outline"
                                disabled={calendarSaving}
                                aria-label={`Toggle ${item.date}`}
                                onClick={() => handleToggleExistingDay(item)}
                              >
                                {item.schoolDay ? 'Mark holiday' : 'Mark school day'}
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </>
            )}
          </section>
        </>
      )}
    </div>
  );
}
