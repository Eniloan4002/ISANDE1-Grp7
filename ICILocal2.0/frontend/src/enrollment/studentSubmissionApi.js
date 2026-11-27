import { api } from '../api/client.js';

export const DRAFT_STORAGE_KEY = 'ici.enrollment.submission.draft.v1';
export const MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
export const ALLOWED_EXTENSIONS = ['pdf', 'jpg', 'jpeg', 'png'];
export const ALLOWED_MIME_TYPES = ['application/pdf', 'image/jpeg', 'image/jpg', 'image/png'];

export const DEFAULT_REQUIREMENT_TYPES = [
  { code: 'PSA', name: 'PSA Birth Certificate', mandatory: true },
  { code: 'FORM137', name: 'Form 137 / Report Card', mandatory: true },
  { code: 'COMPLETION', name: 'Certificate of Completion', mandatory: true },
  { code: 'GOOD_MORAL', name: 'Certificate of Good Moral Character', mandatory: true },
];

function unwrapError(original) {
  if (original?.code === 'ERR_CANCELED' || original?.name === 'AbortError') {
    throw new DOMException('Request canceled', 'AbortError');
  }
  const data = original?.response?.data;
  const error = new Error(
    data?.detail ||
      (original?.response
        ? 'The request could not be completed. Please review your details and try again.'
        : 'The enrollment service is unavailable. Check your connection and try again.')
  );
  error.status = original?.response?.status;
  error.fields = data?.errors || {};
  throw error;
}

export async function loadPortalReferenceData(signal) {
  try {
    const response = await api.get('/public/enrollment/reference-data', { signal });
    return response.data;
  } catch (error) {
    unwrapError(error);
  }
}

export async function submitEnrollmentApplication(payload, attachedFiles = {}) {
  const formData = new FormData();
  formData.append('application', JSON.stringify(payload));
  for (const [code, file] of Object.entries(attachedFiles)) {
    if (file) {
      formData.append('documentCodes', code);
      formData.append('files', file);
    }
  }
  try {
    const response = await api.post('/public/enrollment/submissions', formData, {
      headers: { 'Content-Type': undefined },
    });
    return response.data;
  } catch (error) {
    unwrapError(error);
  }
}

export function validateRequirementFile(file) {
  if (!file) return 'Choose a file to attach.';
  if (file.size <= 0) return 'The selected file is empty.';
  if (file.size > MAX_DOCUMENT_BYTES) return 'File must be 2 MB or smaller.';
  const name = (file.name || '').toLowerCase();
  const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1) : '';
  const type = (file.type || '').toLowerCase();
  if (!ALLOWED_EXTENSIONS.includes(ext) && !ALLOWED_MIME_TYPES.includes(type)) {
    return 'Unsupported format. Upload a PDF, JPG, or PNG file.';
  }
  return '';
}

export function formatFileSize(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}

export function saveSubmissionDraft(draft) {
  try {
    const record = {
      ...draft,
      savedAt: new Date().toISOString(),
    };
    window.localStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify(record));
    return record;
  } catch {
    return null;
  }
}

export function loadSubmissionDraft() {
  try {
    const raw = window.localStorage.getItem(DRAFT_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== 'object' || !parsed.values) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function clearSubmissionDraft() {
  try {
    window.localStorage.removeItem(DRAFT_STORAGE_KEY);
  } catch {
    // Ignore storage errors in restricted browsing contexts.
  }
}
