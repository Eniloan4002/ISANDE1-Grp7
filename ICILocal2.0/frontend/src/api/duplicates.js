import { apiRequest } from '../api.js';

// ---------------------------------------------------------------------------
// Data layer for the Duplicate Records screen (ICI2-13 / ICI2-14).
//
// Until the backend endpoints exist, the screen runs on an in-memory mock so
// the UI can be built and demoed. Set VITE_DUPLICATES_LIVE=1 in frontend/.env
// to call the real API instead.
//
// PROPOSED API CONTRACT (confirm with whoever owns the backend):
//   GET  /api/enrollment/duplicate-flags
//        -> [ DuplicateFlag ]
//   POST /api/enrollment/duplicate-flags/{id}/resolution
//        body: { action: 'MERGE' | 'REJECT', version }
//        -> updated DuplicateFlag (or 204)
//
// DuplicateFlag:
//   {
//     id, applicationId, version,
//     status: 'OPEN' | 'MERGED' | 'REJECTED',
//     matchType: 'LRN_EXACT' | 'NAME_DOB',
//     confidence: 0..1,
//     matchedFields: ['fullName', 'dateOfBirth', ...],   // decided by the server
//     incoming: { summary, fullName, dateOfBirth, sex, lrn, previousSchool, guardianContact, status },
//     existing: { summary, studentId, fullName, dateOfBirth, sex, lrn, previousSchool, guardianContact, status }
//   }
// The matching rules stay on the server; the UI only displays the result.
// ---------------------------------------------------------------------------

const LIVE = import.meta.env.VITE_DUPLICATES_LIVE === '1';
const BASE = '/api/enrollment/duplicate-flags';

export function listDuplicateFlags() {
  return LIVE ? apiRequest(BASE) : mock.list();
}

export function resolveDuplicateFlag(id, action, version) {
  return LIVE
    ? apiRequest(`${BASE}/${id}/resolution`, { method: 'POST', body: JSON.stringify({ action, version }) })
    : mock.resolve(id, action, version);
}

// ----------------------------- mock (no backend) ---------------------------

let mockRows = [
  {
    id: 1, applicationId: 'APP-2026-0153', version: 1, status: 'OPEN', matchType: 'NAME_DOB', confidence: 0.92,
    matchedFields: ['fullName', 'dateOfBirth', 'sex', 'guardianContact'],
    incoming: { summary: 'Transferee · APP-2026-0153 · Submitted Aug 4, 2026', fullName: 'Bautista, Carlo', dateOfBirth: '03/09/2009', sex: 'Male', lrn: '121456789012', previousSchool: 'Iligan City National High School', guardianContact: '0922 555 0153', status: 'Pre-Enlisted' },
    existing: { summary: 'Student STU-2023-1210 · Bautista, Carlos D.', studentId: 'STU-2023-1210', fullName: 'Bautista, Carlos D.', dateOfBirth: '03/09/2009', sex: 'Male', lrn: '120987654321', previousSchool: 'Iligan City NHS', guardianContact: '0922 555 0153', status: 'Active' }
  },
  {
    id: 2, applicationId: 'APP-2026-0149', version: 1, status: 'OPEN', matchType: 'LRN_EXACT', confidence: 0.99,
    matchedFields: ['fullName', 'dateOfBirth', 'sex', 'lrn'],
    incoming: { summary: 'Transferee · APP-2026-0149 · Submitted Aug 2, 2026', fullName: 'Santos, Maria A.', dateOfBirth: '07/22/2008', sex: 'Female', lrn: '108517230184', previousSchool: 'Kapatagan National High School', guardianContact: '0917 555 0991', status: 'Pre-Enlisted' },
    existing: { summary: 'Student STU-2024-0771 · Santos, Maria Ann', studentId: 'STU-2024-0771', fullName: 'Santos, Maria Ann', dateOfBirth: '07/22/2008', sex: 'Female', lrn: '108517230184', previousSchool: 'Kapatagan National HS', guardianContact: '0917 555 0991', status: 'Inactive (Withdrawn)' }
  },
  {
    id: 3, applicationId: 'APP-2026-0140', version: 2, status: 'MERGED', matchType: 'LRN_EXACT', confidence: 0.99,
    matchedFields: ['fullName', 'dateOfBirth', 'sex', 'lrn', 'guardianContact'],
    incoming: { summary: 'Returnee · APP-2026-0140 · Submitted Jul 28, 2026', fullName: 'Garcia, Ana', dateOfBirth: '01/15/2008', sex: 'Female', lrn: '112377981523', previousSchool: 'St. Michael Academy', guardianContact: '0923 555 0140', status: 'Pending Requirements' },
    existing: { summary: 'Student STU-2022-0503 · Garcia, Ana Marie', studentId: 'STU-2022-0503', fullName: 'Garcia, Ana Marie', dateOfBirth: '01/15/2008', sex: 'Female', lrn: '112377981523', previousSchool: 'Tambo Central School', guardianContact: '0923 555 0140', status: 'Enrolled' }
  }
];

const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
const copy = value => JSON.parse(JSON.stringify(value));

const mock = {
  async list() {
    await wait(150);
    return copy(mockRows);
  },
  async resolve(id, action, version) {
    await wait(150);
    const row = mockRows.find(item => item.id === id);
    if (!row) throw new Error('This duplicate flag no longer exists.');
    if (row.version !== version) throw new Error('Someone else already updated this record. Reload and try again.');
    if (row.status !== 'OPEN') throw new Error('This flag has already been resolved.');
    row.status = action === 'MERGE' ? 'MERGED' : 'REJECTED';
    row.version += 1;
    return copy(row);
  }
};