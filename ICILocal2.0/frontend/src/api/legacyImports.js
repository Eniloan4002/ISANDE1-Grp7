import { apiRequest } from '../api.js';
import { api } from './client.js';

const BASES = {
  students: '/api/legacy-student-imports',
  grades: '/api/legacy-grade-imports',
};

function basePath(kind = 'students') {
  return BASES[kind] || BASES.students;
}

function clientPath(kind = 'students') {
  return basePath(kind).replace(/^\/api(?=\/)/, '');
}

function qs(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params || {})) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value));
    }
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

function toImportError(original) {
  if (original?.code === 'ERR_CANCELED' || original?.name === 'AbortError') {
    throw new DOMException('Request canceled', 'AbortError');
  }
  const data = original?.response?.data;
  const error = new Error(
    data?.detail ||
      (original?.response
        ? 'The request could not be completed. Try again.'
        : 'The service is unavailable. Check your connection and try again.')
  );
  error.status = original?.response?.status;
  error.code = data?.code || null;
  error.batchId = data?.batchId ?? null;
  error.batchStatus = data?.batchStatus ?? null;
  error.problems = Array.isArray(data?.problems) ? data.problems : [];
  error.fields = data?.errors || {};
  return error;
}

export function listImportMappings(kind = 'students', options = {}) {
  return apiRequest(`${basePath(kind)}/mappings`, options);
}

export function getImportMapping(id, kind = 'students', options = {}) {
  return apiRequest(`${basePath(kind)}/mappings/${id}`, options);
}

export function createImportMapping(payload, kind = 'students') {
  return apiRequest(`${basePath(kind)}/mappings`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

export function updateImportMapping(id, payload, kind = 'students') {
  return apiRequest(`${basePath(kind)}/mappings/${id}`, {
    method: 'PUT',
    body: JSON.stringify(payload),
  });
}

export async function uploadImportBatch({
  kind = 'students',
  campusId,
  mappingId,
  academicYearId,
  termId,
  gradeLevel,
  section,
  file,
}) {
  const form = new FormData();
  form.append('campusId', String(campusId));
  if (mappingId !== undefined && mappingId !== null && mappingId !== '') {
    form.append('mappingId', String(mappingId));
  }
  if (kind === 'grades') {
    if (academicYearId !== undefined && academicYearId !== null && academicYearId !== '') {
      form.append('academicYearId', String(academicYearId));
    }
    if (termId !== undefined && termId !== null && termId !== '') {
      form.append('termId', String(termId));
    }
    if (gradeLevel !== undefined && gradeLevel !== null && String(gradeLevel).trim() !== '') {
      form.append('gradeLevel', String(gradeLevel).trim());
    }
    if (section !== undefined && section !== null && String(section).trim() !== '') {
      form.append('section', String(section).trim());
    }
  }
  form.append('file', file);
  try {
    const response = await api.post(`${clientPath(kind)}/batches`, form);
    return response.data;
  } catch (original) {
    throw toImportError(original);
  }
}

export function listImportBatches(
  { kind = 'students', campusId = '', status = '', page = 0, size = 25 } = {},
  options = {}
) {
  return apiRequest(`${basePath(kind)}/batches${qs({ campusId, status, page, size })}`, options);
}

export function getImportBatch(id, kind = 'students', options = {}) {
  return apiRequest(`${basePath(kind)}/batches/${id}`, options);
}

export function listImportBatchRows(
  id,
  { kind = 'students', status = '', page = 0, size = 25 } = {},
  options = {}
) {
  return apiRequest(`${basePath(kind)}/batches/${id}/rows${qs({ status, page, size })}`, options);
}

export function listImportBatchErrors(
  id,
  { kind = 'students', severity = '', rowNumber = '', page = 0, size = 25 } = {},
  options = {}
) {
  return apiRequest(
    `${basePath(kind)}/batches/${id}/errors${qs({ severity, rowNumber, page, size })}`,
    options
  );
}

export function reviewImportRow(
  batchId,
  rowId,
  { kind = 'students', version, decision, reason, studentId }
) {
  const body = { version, decision, reason };
  if (studentId !== undefined && studentId !== null && studentId !== '') {
    body.studentId = Number(studentId);
  }
  return apiRequest(`${basePath(kind)}/batches/${batchId}/rows/${rowId}/review`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function commitImportBatch(batchId, { kind = 'students', version, expectedValidCount }) {
  return apiRequest(`${basePath(kind)}/batches/${batchId}/commit`, {
    method: 'POST',
    body: JSON.stringify({ version, expectedValidCount }),
  });
}

export function discardImportBatch(batchId, { kind = 'students', version, reason }) {
  return apiRequest(`${basePath(kind)}/batches/${batchId}/discard`, {
    method: 'POST',
    body: JSON.stringify({ version, reason }),
  });
}

export function voidImportBatch(batchId, { version, reason }) {
  return apiRequest(`${BASES.grades}/batches/${batchId}/void`, {
    method: 'POST',
    body: JSON.stringify({ version, reason }),
  });
}
