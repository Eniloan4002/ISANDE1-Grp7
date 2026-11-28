import { apiRequest } from '../api.js';

export const statusLabel = {
  pending_teacher_submission: 'Pending Teacher Submission',
  registrar_review: 'For Registrar Review / Update',
  grade_correction: 'For Grade Correction',
  completed: 'Completed',
};
const base = '/api/academic/incomplete-cases';
const normalize = item => ({ ...item, id: String(item.id) });
const filterKeys = ['search', 'status', 'academicYearId', 'gradingPeriodId', 'sectionId', 'subjectId', 'teacherId'];
function query(filters, page) {
  const params = new URLSearchParams({ size: '100', page: String(page) });
  for (const key of filterKeys) if (filters[key] !== undefined && filters[key] !== null && filters[key] !== '') params.set(key, String(filters[key]));
  return params.toString();
}
export async function listIncompleteCases(filters = {}) {
  const first = await apiRequest(`${base}?${query(filters, 0)}`);
  if (first.total > 10000) throw new Error('More than 10,000 incomplete cases are in scope. Use a narrower authorized campus scope for this workspace.');
  const items = [...first.items];
  for (let page = 1; items.length < first.total; page++) {
    const next = await apiRequest(`${base}?${query(filters, page)}`);
    if (!next.items.length) break;
    items.push(...next.items);
  }
  return { ...first, items: items.map(normalize) };
}
export async function getIncompleteCase(id) { return normalize(await apiRequest(`${base}/${encodeURIComponent(id)}`)); }
async function write(id, action, input) {
  return normalize(await apiRequest(`${base}/${encodeURIComponent(id)}/${action}`, { method: 'POST', body: JSON.stringify(input) }));
}
export function addFollowUp(id, { note, method, version }) { return write(id, 'follow-ups', { note, method, version }); }
export function proposeCompletion(id, { grade, note, version, gradeVersion }) { return write(id, 'proposals', { grade: Number(grade), note, version, gradeVersion }); }
export function finalizeCompletion(id, { grade, note, version, gradeVersion }) { return write(id, 'complete', { ...(grade === undefined ? {} : { grade }), note, version, gradeVersion }); }
