import { apiRequest } from '../api.js';

export function loadAuditReferenceData(signal) {
  return apiRequest('/api/admin/audit-log/reference-data', { signal });
}

export function loadAuditLogs(filters = {}, signal) {
  const params = new URLSearchParams();
  if (filters.from) params.set('from', filters.from);
  if (filters.to) params.set('to', filters.to);
  if (filters.actorId) params.set('actorId', String(filters.actorId));
  if (filters.recordType) params.set('recordType', filters.recordType);
  if (filters.campusId) params.set('campusId', String(filters.campusId));
  if (filters.operation) params.set('operation', filters.operation);
  if (filters.search) params.set('search', filters.search.trim());
  params.set('page', String(filters.page ?? 0));
  params.set('size', String(filters.size ?? 25));
  return apiRequest(`/api/admin/audit-log?${params.toString()}`, { signal });
}

export function formatRecordType(tableName) {
  if (!tableName) return 'Unknown';
  return String(tableName)
    .split('_')
    .filter(Boolean)
    .map(part => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

export function formatRecordId(rawId) {
  if (rawId == null || rawId === '') return '—';
  const text = String(rawId).trim();
  if (text.startsWith('{') && text.endsWith('}')) {
    try {
      const parsed = JSON.parse(text);
      const entries = Object.entries(parsed);
      if (entries.length === 1 && entries[0][0] === 'id') {
        return `#${entries[0][1]}`;
      }
      if (entries.length > 0) {
        return entries.map(([k, v]) => `${k}: ${v}`).join(', ');
      }
    } catch {
      return text;
    }
  }
  return /^\d+$/.test(text) ? `#${text}` : text;
}

export function computeAuditDiff(oldData, newData) {
  const before = oldData && typeof oldData === 'object' ? oldData : {};
  const after = newData && typeof newData === 'object' ? newData : {};
  const keys = Array.from(new Set([...Object.keys(before), ...Object.keys(after)])).sort();
  return keys.map(key => {
    const hasBefore = Object.prototype.hasOwnProperty.call(before, key);
    const hasAfter = Object.prototype.hasOwnProperty.call(after, key);
    const beforeVal = hasBefore ? before[key] : undefined;
    const afterVal = hasAfter ? after[key] : undefined;
    const beforeSerialized = JSON.stringify(beforeVal);
    const afterSerialized = JSON.stringify(afterVal);
    const changed = beforeSerialized !== afterSerialized;
    return {
      field: key,
      before: beforeVal,
      after: afterVal,
      changed,
    };
  });
}

export function formatAuditValue(value) {
  if (value === undefined) return '—';
  if (value === null) return 'null';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (typeof value === 'object') return JSON.stringify(value, null, 2);
  return String(value);
}
