import { apiRequest } from '../api.js';
import { downloadFile, query } from '../api/download.js';
import { isoDay } from '../api/dates.js';

export const documentTypes = [['FORM137', 'Form 137'], ['TOR', 'Transcript of Records (TOR)']];
export const typeLabel = Object.fromEntries(documentTypes);

const base = '/api/official-documents';
const json = (method, body) => ({ method, body: JSON.stringify(body) });

export const loadReference = () => apiRequest('/api/enrollment/reference-data');
export const searchStudents = (search, page = 0) => apiRequest(`/api/academic/history/students${query({ search, page, size: 10 })}`);
export const loadStudentRecord = studentId => apiRequest(`/api/academic/history/students/${studentId}`);

export const loadDocumentPolicies = () => apiRequest(`${base}/policies`);
export const createDocumentPolicy = input => apiRequest(`${base}/policies`, json('POST', input));
export const updateDocumentPolicy = (id, input) => apiRequest(`${base}/policies/${id}`, json('PUT', input));
export const approveDocumentPolicy = (id, version, approvalReference) =>
  apiRequest(`${base}/policies/${id}/approve`, json('POST', { version, approvalReference: String(approvalReference || '').trim() }));

const withDay = record => (record?.issued_on == null ? record : { ...record, issued_on: isoDay(record.issued_on) });
/** The stored SO for an enrollment, or `{ version: -1 }` when none has been recorded yet. */
export const loadSpecialOrder = enrollmentId => apiRequest(`${base}/special-orders/${enrollmentId}`).then(withDay);
export const saveSpecialOrder = (enrollmentId, { version, number, issuedOn, issuingAuthority }) =>
  apiRequest(`${base}/special-orders/${enrollmentId}`, json('PUT', { version, number: number.trim(), issuedOn, issuingAuthority: issuingAuthority.trim() })).then(withDay);

export const verifyDocument = selection => apiRequest(`${base}/verify`, json('POST', selection));
export const issueDocument = selection => apiRequest(`${base}/issue`, json('POST', selection));
export const loadGeneration = id => apiRequest(`${base}/generations/${id}`);

export const previewDocument = selection =>
  downloadFile('/official-documents/preview', { method: 'POST', data: selection, fallbackName: 'DRAFT-academic-record.pdf' });
export const downloadIssuedDocument = id =>
  downloadFile(`/official-documents/generations/${id}/pdf`, { fallbackName: `academic-record-${id}.pdf` });

const reasonTitles = {
  TEMPLATE_POLICY_UNAPPROVED: 'The document policy is not approved',
  ENROLLMENT_NOT_CONFIRMED: 'An enrollment is not confirmed',
  MISSING_CREDENTIALS: 'Credentials are missing or unverified',
  INCOMPLETE_ACADEMIC_RECORD: 'The academic record is incomplete',
  UNRESOLVED_GRADES: 'Some grades are not finalized',
  PENDING_GRADE_REVIEW: 'Grade corrections or INC marks are pending',
  SPECIAL_ORDER_REQUIRED: 'The SO number is missing',
  SPECIAL_ORDER_FORMAT: 'The SO number does not match the policy',
  WITHDRAWAL_CLEARANCE: 'A withdrawal is not completed',
};

/**
 * Splits a server blocker such as `UNRESOLVED_GRADES: ... enrollment 12.` into a plain title and detail.
 * `termLabel` turns enrollment ids in the detail into term names the registrar recognises.
 */
export function describeBlocker(reason, termLabel = () => null) {
  const match = /^([A-Z_]+):\s*(.*)$/.exec(String(reason));
  const code = match?.[1] ?? '';
  const detail = (match?.[2] ?? String(reason)).replace(/enrollment (\d+)/g, (whole, id) => {
    const label = termLabel(id);
    return label ? label : whole;
  });
  return { code, title: reasonTitles[code] ?? (code ? code.replaceAll('_', ' ').toLowerCase() : 'Blocked'), detail };
}

export function newRequestKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  const bytes = Array.from({ length: 16 }, () => Math.floor(Math.random() * 256));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.map(byte => byte.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}
