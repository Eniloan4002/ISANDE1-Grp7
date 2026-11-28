import { apiRequest } from '../api.js';
import { query } from '../api/download.js';

// ICI2-73 client for the ICI2-40 cutover reconciliation API.
const BASE = '/api/legacy-imports/reconciliation';

/** Every live import batch in scope with its totals and discrepancies. Not paginated. */
export const loadReconciliation = (campusId = '', { signal } = {}) =>
  apiRequest(`${BASE}${query({ campusId })}`, { signal });

/**
 * Signs off one committed batch. The server checks that `version` and `expectedImportedCount` still match what
 * the registrar saw, so a batch that changed since loading is refused with 409.
 */
export const signOffBatch = (batchId, { version, expectedImportedCount, note }) =>
  apiRequest(`${BASE}/batches/${batchId}/sign-off`, {
    method: 'POST',
    body: JSON.stringify({ version, expectedImportedCount, note: note?.trim() ? note.trim() : null }),
  });

export const sourceLabels = {
  legacy_student_csv: 'Student records (CSV)',
  legacy_grade_matrix_csv: 'Grade matrix',
  section_allocation: 'Section allocations',
  document_log: 'Document log',
};

export const discrepancyLabels = {
  NOT_COMMITTED: 'Not committed yet',
  ROW_COUNT_MISMATCH: 'Row count does not match',
  IMPORTED_COUNT_MISMATCH: 'Imported count does not match',
  UNIMPORTED_ROWS: 'Rows not imported',
  IMPORTED_WITHOUT_STUDENT: 'Rows without a student record',
  VOIDED_GRADE_RECORDS: 'Grade records voided',
};

/** One state per batch, so filters, counts and chips agree. */
export function batchState(batch) {
  if (batch.status === 'reconciled') return 'signed';
  if (batch.canSignOff) return 'ready';
  return 'blocked';
}
