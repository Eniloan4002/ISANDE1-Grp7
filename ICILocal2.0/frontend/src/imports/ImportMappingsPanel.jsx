import { useEffect, useRef, useState } from 'react';
import { Failure } from '../EnrollmentWorkspace.jsx';
import { createImportMapping, updateImportMapping } from '../api/legacyImports.js';

const DEFAULT_STUDENT_FIELDS = {
  studentNumber: { required: true, aliases: ['student_no', 'student_number'] },
  lrn: { required: false, aliases: ['lrn'] },
  lastName: { required: true, aliases: ['last_name', 'surname'] },
  firstName: { required: true, aliases: ['first_name', 'given_name'] },
  middleName: { required: false, aliases: ['middle_name'] },
  birthDate: { required: true, aliases: ['birth_date', 'birthdate', 'dob'] },
  sex: { required: false, aliases: ['sex', 'gender'] },
  previousSchool: { required: false, aliases: ['previous_school'] },
  voucher: { required: true, aliases: ['voucher', 'voucher_code'] },
  specialization: { required: true, aliases: ['strand', 'specialization', 'program'] },
  campus: { required: false, aliases: ['campus', 'campus_code'] },
};

const DEFAULT_STUDENT_VALUE_MAPS = { voucher: {}, specialization: {}, campus: {}, sex: {} };

const DEFAULT_GRADE_FIELDS = {
  studentNumber: { required: false, aliases: ['student_no', 'student_number'] },
  lrn: { required: false, aliases: ['lrn'] },
  lastName: { required: false, aliases: ['last_name', 'surname'] },
  firstName: { required: false, aliases: ['first_name', 'given_name'] },
  schoolYear: { required: false, aliases: ['school_year', 'academic_year'] },
  term: { required: false, aliases: ['term', 'semester'] },
  gradeLevel: { required: false, aliases: ['grade_level', 'year_level'] },
  section: { required: false, aliases: ['section', 'section_name'] },
};

const DEFAULT_GRADE_VALUE_MAPS = { subject: {}, period: {}, term: {}, schoolYear: {}, mark: {} };

function delimiterLabel(value) {
  if (value === '\t') return 'Tab (\\t)';
  if (value === ';') return 'Semicolon (;)';
  return 'Comma (,)';
}

function splitCommaList(value) {
  return String(value || '')
    .split(',')
    .map(item => item.trim())
    .filter(Boolean);
}

function toFormState(kind, item, fallback) {
  const isGrades = kind === 'grades';
  const source = item || fallback;
  const settings = source?.settings || {};
  const mapping = source?.mapping || {};
  const grid = mapping?.grid || {};
  return {
    name: item ? item.name : '',
    encoding: settings.encoding || 'UTF-8',
    delimiter: settings.delimiter || ',',
    headerRow: String(settings.headerRow ?? 1),
    dateFormats: Array.isArray(settings.dateFormats)
      ? settings.dateFormats.join(', ')
      : 'yyyy-MM-dd, MM/dd/yyyy',
    normalizeNameCase: Boolean(settings.normalizeNameCase),
    decimalSeparator: settings.decimalSeparator || '.',
    ignoredColumns: Array.isArray(settings.ignoredColumns)
      ? settings.ignoredColumns.join(', ')
      : '',
    gridSeparator: grid.separator || ' - ',
    reportedFinalLabels: Array.isArray(grid.reportedFinalLabels)
      ? grid.reportedFinalLabels.join(', ')
      : '',
    active: item ? Boolean(item.active) : true,
    isDefault: item ? Boolean(item.isDefault) : false,
    realFileValidated: item ? Boolean(item.realFileValidated) : false,
    fieldsJson: JSON.stringify(
      mapping.fields || (isGrades ? DEFAULT_GRADE_FIELDS : DEFAULT_STUDENT_FIELDS),
      null,
      2
    ),
    valueMapsJson: JSON.stringify(
      mapping.valueMaps || (isGrades ? DEFAULT_GRADE_VALUE_MAPS : DEFAULT_STUDENT_VALUE_MAPS),
      null,
      2
    ),
  };
}

export default function ImportMappingsPanel({ kind = 'students', mappings, loading, error, reload, isAdmin }) {
  const isGrades = kind === 'grades';
  const defaultMapping = mappings.find(item => item.isDefault) || mappings[0] || null;
  const [editing, setEditing] = useState(null);
  const [inspecting, setInspecting] = useState(null);
  const [form, setForm] = useState(() => toFormState(kind, null, defaultMapping));
  const [busy, setBusy] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [notice, setNotice] = useState('');
  const nameInputRef = useRef(null);

  useEffect(() => {
    setEditing(null);
    setInspecting(null);
    setSaveError('');
    setNotice('');
    setForm(toFormState(kind, null, defaultMapping));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [kind, defaultMapping?.id]);

  function startCreate() {
    setEditing(null);
    setSaveError('');
    setForm(toFormState(kind, null, defaultMapping));
    nameInputRef.current?.focus();
  }

  function startEdit(item) {
    setEditing(item);
    setInspecting(null);
    setSaveError('');
    setNotice('');
    setForm(toFormState(kind, item, defaultMapping));
    nameInputRef.current?.focus();
  }

  async function handleSubmit(event) {
    event.preventDefault();
    setSaveError('');
    setNotice('');

    let parsedFields;
    let parsedValueMaps;
    try {
      parsedFields = JSON.parse(form.fieldsJson);
    } catch {
      setSaveError('Field aliases JSON is not valid JSON.');
      return;
    }
    try {
      parsedValueMaps = JSON.parse(form.valueMapsJson);
    } catch {
      setSaveError('Value maps JSON is not valid JSON.');
      return;
    }

    const mappingPayload = isGrades
      ? {
          fields: parsedFields,
          grid: {
            layout: 'subject_period_flat',
            separator: form.gridSeparator,
            reportedFinalLabels: splitCommaList(form.reportedFinalLabels),
          },
          valueMaps: parsedValueMaps,
        }
      : {
          fields: parsedFields,
          valueMaps: parsedValueMaps,
        };

    const settingsPayload = isGrades
      ? {
          encoding: form.encoding,
          delimiter: form.delimiter,
          headerRow: Number(form.headerRow),
          decimalSeparator: form.decimalSeparator,
          ignoredColumns: splitCommaList(form.ignoredColumns),
        }
      : {
          encoding: form.encoding,
          delimiter: form.delimiter,
          headerRow: Number(form.headerRow),
          dateFormats: splitCommaList(form.dateFormats),
          normalizeNameCase: form.normalizeNameCase,
        };

    const payload = {
      name: form.name.trim(),
      mapping: mappingPayload,
      settings: settingsPayload,
      isDefault: form.isDefault,
      active: form.active,
      realFileValidated: form.realFileValidated,
      ...(editing ? { version: editing.version } : {}),
    };

    setBusy(true);
    try {
      if (editing) {
        await updateImportMapping(editing.id, payload, kind);
        setNotice(`Mapping "${payload.name}" updated.`);
      } else {
        await createImportMapping(payload, kind);
        setNotice(`Mapping "${payload.name}" created.`);
      }
      setEditing(null);
      setForm(toFormState(kind, null, defaultMapping));
      reload();
    } catch (requestError) {
      setSaveError(requestError.message || 'The mapping could not be saved.');
    } finally {
      setBusy(false);
    }
  }

  const listPanel = (
    <section className="panel">
      <h2>{isGrades ? 'Stored grade matrix mappings' : 'Stored student CSV mappings'}</h2>
      <div className="panel-body">
        {!isAdmin && (
          <p className="notice">
            Only administrators can create or edit column mappings. You can inspect mappings here and select any active mapping when uploading a CSV.
          </p>
        )}
        <Failure error={error} reload={reload} />
        {loading && <p role="status">Loading column mappings…</p>}
        {!loading && !error && mappings.length === 0 && (
          <div className="empty-state">
            <h3>No column mappings found</h3>
            <p>An administrator must configure a default mapping before uploading CSV files.</p>
          </div>
        )}
        {mappings.length > 0 && (
          <div className="setup-table-wrap">
            <table className="setup-table">
              <thead>
                <tr>
                  <th>Mapping</th>
                  <th>Parse settings</th>
                  <th>Status</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {mappings.map(item => (
                  <tr
                    key={item.id}
                    className={editing?.id === item.id || inspecting?.id === item.id ? 'selected-row' : undefined}
                  >
                    <td>
                      <strong>{item.name}</strong>
                      <small>Version {item.version}</small>
                    </td>
                    <td>
                      {item.settings?.encoding || 'UTF-8'} / {delimiterLabel(item.settings?.delimiter)}
                      <small>
                        Header row {item.settings?.headerRow ?? 1}
                        {isGrades
                          ? ` · Decimal "${item.settings?.decimalSeparator || '.'}" · Separator "${item.mapping?.grid?.separator || ' - '}"`
                          : ` · Dates: ${(item.settings?.dateFormats || []).join(', ') || 'Default'}`}
                      </small>
                    </td>
                    <td>
                      <span className="imports-status-row">
                        <span className={`status ${item.active ? 'status-success' : 'status-danger'}`}>
                          {item.active ? 'Active' : 'Inactive'}
                        </span>
                        {item.isDefault && <span className="status status-warning">Default</span>}
                        {item.realFileValidated ? (
                          <span className="status status-success">Validated</span>
                        ) : (
                          <span className="status">Unvalidated</span>
                        )}
                      </span>
                    </td>
                    <td>
                      <div className="table-actions">
                        <button
                          type="button"
                          className="button button-outline"
                          onClick={() => setInspecting(inspecting?.id === item.id ? null : item)}
                        >
                          {inspecting?.id === item.id ? 'Hide' : 'Inspect'}
                        </button>
                        {isAdmin && (
                          <button
                            type="button"
                            className="button button-outline"
                            disabled={busy}
                            onClick={() => startEdit(item)}
                          >
                            Edit
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {inspecting && (
          <div className="imports-row-detail imports-mapping-preview">
            <div className="imports-row-detail-head">
              <h3>{inspecting.name}</h3>
              <button
                type="button"
                className="button button-outline"
                onClick={() => setInspecting(null)}
              >
                Close details
              </button>
            </div>
            <p className="field-help">
              Encoding: <strong>{inspecting.settings?.encoding}</strong> · Delimiter:{' '}
              <strong>{delimiterLabel(inspecting.settings?.delimiter)}</strong> · Header row:{' '}
              <strong>{inspecting.settings?.headerRow}</strong>
              {isGrades ? (
                <>
                  {' '}· Decimal separator: <strong>{inspecting.settings?.decimalSeparator || '.'}</strong> · Grid separator:{' '}
                  <strong>{inspecting.mapping?.grid?.separator || ' - '}</strong>
                  {inspecting.settings?.ignoredColumns?.length > 0 && (
                    <> · Ignored: <strong>{inspecting.settings.ignoredColumns.join(', ')}</strong></>
                  )}
                </>
              ) : (
                <>
                  {' '}· Name case normalization:{' '}
                  <strong>{inspecting.settings?.normalizeNameCase ? 'On' : 'Off'}</strong>
                </>
              )}
            </p>
            <div className="imports-kv-grid">
              <div className="imports-kv-box">
                <h4>Field aliases</h4>
                <pre>{JSON.stringify(inspecting.mapping?.fields || {}, null, 2)}</pre>
              </div>
              <div className="imports-kv-box">
                <h4>{isGrades ? 'Value maps & grid' : 'Value maps'}</h4>
                <pre>
                  {JSON.stringify(
                    isGrades
                      ? { grid: inspecting.mapping?.grid || {}, valueMaps: inspecting.mapping?.valueMaps || {} }
                      : inspecting.mapping?.valueMaps || {},
                    null,
                    2
                  )}
                </pre>
              </div>
            </div>
          </div>
        )}
      </div>
    </section>
  );

  if (!isAdmin) {
    return listPanel;
  }

  return (
    <>
      {notice && (
        <p className="notice" role="status">
          {notice}
        </p>
      )}
      <div className="setup-layout">
        <section className="panel">
          <h2>
            {editing
              ? `Edit mapping #${editing.id}`
              : isGrades
                ? 'Create grade matrix mapping'
                : 'Create column mapping'}
          </h2>
          <form className="panel-body" onSubmit={handleSubmit}>
            <Failure error={saveError} />
            <label htmlFor="mapping-name">Mapping name</label>
            <input
              ref={nameInputRef}
              id="mapping-name"
              required
              maxLength={120}
              disabled={busy}
              value={form.name}
              onChange={event => setForm({ ...form, name: event.target.value })}
              placeholder={isGrades ? 'e.g. Grade Matrix 2025' : 'e.g. Registrar export 2026'}
            />

            <label htmlFor="mapping-encoding">File encoding</label>
            <select
              id="mapping-encoding"
              disabled={busy}
              value={form.encoding}
              onChange={event => setForm({ ...form, encoding: event.target.value })}
            >
              <option value="UTF-8">UTF-8</option>
              <option value="windows-1252">windows-1252</option>
            </select>

            <label htmlFor="mapping-delimiter">Delimiter</label>
            <select
              id="mapping-delimiter"
              disabled={busy}
              value={form.delimiter}
              onChange={event => setForm({ ...form, delimiter: event.target.value })}
            >
              <option value=",">Comma (,)</option>
              <option value=";">Semicolon (;)</option>
              <option value={'\t'}>Tab (\t)</option>
            </select>

            <label htmlFor="mapping-header-row">Header row (1–20)</label>
            <input
              id="mapping-header-row"
              type="number"
              min={1}
              max={20}
              required
              disabled={busy}
              value={form.headerRow}
              onChange={event => setForm({ ...form, headerRow: event.target.value })}
            />

            {isGrades ? (
              <>
                <label htmlFor="mapping-decimal-separator">Decimal separator</label>
                <select
                  id="mapping-decimal-separator"
                  disabled={busy}
                  value={form.decimalSeparator}
                  onChange={event => setForm({ ...form, decimalSeparator: event.target.value })}
                >
                  <option value=".">Period (.)</option>
                  <option value=",">Comma (,)</option>
                </select>

                <label htmlFor="mapping-grid-separator">Subject / period header separator</label>
                <input
                  id="mapping-grid-separator"
                  required
                  maxLength={10}
                  disabled={busy}
                  value={form.gridSeparator}
                  onChange={event => setForm({ ...form, gridSeparator: event.target.value })}
                  placeholder=" - "
                />

                <label htmlFor="mapping-final-labels">Reported final labels (comma-separated, optional)</label>
                <input
                  id="mapping-final-labels"
                  disabled={busy}
                  value={form.reportedFinalLabels}
                  onChange={event => setForm({ ...form, reportedFinalLabels: event.target.value })}
                  placeholder="e.g. Final Grade, Final Rating"
                />

                <label htmlFor="mapping-ignored-columns">Ignored columns (comma-separated, optional)</label>
                <input
                  id="mapping-ignored-columns"
                  disabled={busy}
                  value={form.ignoredColumns}
                  onChange={event => setForm({ ...form, ignoredColumns: event.target.value })}
                  placeholder="e.g. Remarks, Average"
                />
              </>
            ) : (
              <>
                <label htmlFor="mapping-date-formats">Date formats (comma-separated)</label>
                <input
                  id="mapping-date-formats"
                  required
                  disabled={busy}
                  value={form.dateFormats}
                  onChange={event => setForm({ ...form, dateFormats: event.target.value })}
                  placeholder="yyyy-MM-dd, MM/dd/yyyy"
                />
                <p className="field-help">
                  Use 4-digit years with `-`, `/` or `.`. Avoid mixing `MM/dd/yyyy` and `dd/MM/yyyy`.
                </p>
              </>
            )}

            <div className="imports-checkbox-row">
              {!isGrades && (
                <label htmlFor="mapping-normalize-case">
                  <input
                    id="mapping-normalize-case"
                    type="checkbox"
                    disabled={busy}
                    checked={form.normalizeNameCase}
                    onChange={event => setForm({ ...form, normalizeNameCase: event.target.checked })}
                  />
                  Normalize all-caps/lower names
                </label>
              )}
              <label htmlFor="mapping-active">
                <input
                  id="mapping-active"
                  type="checkbox"
                  disabled={busy}
                  checked={form.active}
                  onChange={event => setForm({ ...form, active: event.target.checked })}
                />
                Active
              </label>
              <label htmlFor="mapping-default">
                <input
                  id="mapping-default"
                  type="checkbox"
                  disabled={busy}
                  checked={form.isDefault}
                  onChange={event => setForm({ ...form, isDefault: event.target.checked })}
                />
                Default mapping
              </label>
              <label htmlFor="mapping-validated">
                <input
                  id="mapping-validated"
                  type="checkbox"
                  disabled={busy}
                  checked={form.realFileValidated}
                  onChange={event => setForm({ ...form, realFileValidated: event.target.checked })}
                />
                Real-file validated
              </label>
            </div>

            <label htmlFor="mapping-fields-json">Field aliases (JSON)</label>
            <textarea
              id="mapping-fields-json"
              className="imports-textarea imports-code-textarea"
              required
              disabled={busy}
              value={form.fieldsJson}
              onChange={event => setForm({ ...form, fieldsJson: event.target.value })}
            />

            <label htmlFor="mapping-valuemaps-json">Value maps (JSON)</label>
            <textarea
              id="mapping-valuemaps-json"
              className="imports-textarea imports-code-textarea"
              required
              disabled={busy}
              value={form.valueMapsJson}
              onChange={event => setForm({ ...form, valueMapsJson: event.target.value })}
            />

            <div className="component-row">
              <button className="button" type="submit" disabled={busy}>
                {busy ? 'Saving…' : editing ? 'Save mapping' : 'Create mapping'}
              </button>
              {editing && (
                <button
                  type="button"
                  className="button button-outline"
                  disabled={busy}
                  onClick={startCreate}
                >
                  Cancel edit
                </button>
              )}
            </div>
          </form>
        </section>
        {listPanel}
      </div>
    </>
  );
}
