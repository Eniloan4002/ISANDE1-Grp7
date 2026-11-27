import { apiRequest } from '../api.js';
import { api } from '../api/client.js';

export const MAX_EVIDENCE_BYTES = 1_000_000;
export const ALLOWED_EVIDENCE_TYPES = ['application/pdf', 'image/png', 'image/jpeg'];
const ALLOWED_EXTENSIONS = ['.pdf', '.png', '.jpg', '.jpeg'];
const NUMERIC_GRADE_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

export function validateProposedGrade(raw) {
  const text = String(raw ?? '').trim();
  if (!text) return 'Enter a proposed numeric grade from 0 to 100.';
  if (!NUMERIC_GRADE_PATTERN.test(text)) {
    return 'Enter a numeric grade from 0 to 100 with at most two decimal places.';
  }
  const value = Number(text);
  if (Number.isNaN(value) || value < 0 || value > 100) {
    return 'Proposed grade must be between 0.00 and 100.00.';
  }
  return '';
}

export function validateEvidenceFile(file) {
  if (!file) return 'Attach a supporting evidence file (PDF, PNG, or JPEG up to 1 MB).';
  const name = String(file.name || '');
  if (!name.trim() || name.length > 200 || name.includes('/') || name.includes('\\')) {
    return 'Use a plain filename (at most 200 characters) without folder paths.';
  }
  const lower = name.toLowerCase();
  const hasAllowedExtension = ALLOWED_EXTENSIONS.some(ext => lower.endsWith(ext));
  const hasAllowedType = ALLOWED_EVIDENCE_TYPES.includes(file.type);
  if (!hasAllowedType || !hasAllowedExtension) {
    return 'Unsupported file type. Upload a PDF, PNG, or JPEG image.';
  }
  if (file.size <= 0) {
    return 'The selected evidence file is empty.';
  }
  if (file.size > MAX_EVIDENCE_BYTES) {
    return 'Evidence file is too large. Maximum allowed size is 1 MB (1,000,000 bytes).';
  }
  return '';
}

function formatServerError(original) {
  if (original?.code === 'ERR_CANCELED' || original?.name === 'AbortError') {
    throw new DOMException('Request canceled', 'AbortError');
  }
  const status = original?.response?.status;
  const detail = original?.response?.data?.detail;
  let message = detail;
  if (!message) {
    if (status === 403) message = 'You are not authorized to submit a correction for this grade.';
    else if (status === 409) message = 'This grade changed, is not locked, or already has a pending correction. Reload and try again.';
    else if (status === 413) message = 'Evidence file exceeds the 1 MB (1,000,000 bytes) limit.';
    else if (original?.response) message = 'The correction request could not be completed. Try again.';
    else message = 'The service is unavailable. Check your connection and try again.';
  }
  const error = new Error(message);
  error.status = status;
  error.fields = original?.response?.data?.errors || {};
  return error;
}

export async function loadCorrectionGradebook(classId, periodId, { academicYearId, termId }) {
  const query = new URLSearchParams({
    academicYearId: String(academicYearId),
    termId: String(termId),
    gradingPeriodId: String(periodId),
  });
  const result = await apiRequest(`/api/academic/classes/${classId}/grades?${query}`);
  const periodLocked = result?.period?.status === 'locked';
  const students = (result?.students || []).map(row => {
    const gradeId = row.gradeId ?? row.id ?? null;
    const state = row.state || 'draft';
    const eligible =
      gradeId != null &&
      state !== 'draft' &&
      (state === 'finalized' || periodLocked);
    return {
      gradeId,
      enrollmentId: row.enrollmentId,
      studentNumber: row.studentNumber,
      name: row.name,
      value: row.value,
      mark: row.mark,
      state,
      version: row.version ?? 0,
      eligible,
    };
  });
  return {
    class: result?.class || null,
    period: result?.period || null,
    state: result?.state || 'draft',
    periodLocked,
    students,
  };
}

export function listMyGradeCorrections({ status = 'all', page = 0, size = 25 } = {}) {
  const query = new URLSearchParams({
    status,
    page: String(page),
    size: String(size),
  });
  return apiRequest(`/api/academic/grade-corrections?${query}`);
}

export function getGradeCorrectionDetail(id) {
  return apiRequest(`/api/academic/grade-corrections/${id}`);
}

export async function submitGradeCorrection({
  gradeId,
  gradeVersion,
  proposedValue,
  justification,
  evidence,
}) {
  const form = new FormData();
  form.append(
    'request',
    new Blob(
      [
        JSON.stringify({
          gradeId: Number(gradeId),
          gradeVersion: Number(gradeVersion),
          proposedValue: Number(proposedValue),
          justification: String(justification || '').trim(),
        }),
      ],
      { type: 'application/json' }
    )
  );
  form.append('evidence', evidence);
  try {
    const response = await api.post('/academic/grade-corrections', form);
    return response.data;
  } catch (original) {
    throw formatServerError(original);
  }
}

function filenameFromDisposition(header, fallback) {
  if (!header) return fallback;
  const utf8Match = /filename\*=UTF-8''([^;]+)/i.exec(header);
  if (utf8Match?.[1]) {
    try {
      return decodeURIComponent(utf8Match[1].replace(/["']/g, ''));
    } catch {
      return utf8Match[1];
    }
  }
  const plainMatch = /filename="?([^";]+)"?/i.exec(header);
  return plainMatch?.[1] || fallback;
}

export async function downloadCorrectionEvidence(id, fallbackFilename = `grade-correction-${id}-evidence`) {
  try {
    const response = await api.get(`/academic/grade-corrections/${id}/evidence`, {
      responseType: 'blob',
    });
    const filename = filenameFromDisposition(
      response.headers?.['content-disposition'],
      fallbackFilename
    );
    const url = URL.createObjectURL(response.data);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    return filename;
  } catch (original) {
    throw formatServerError(original);
  }
}
