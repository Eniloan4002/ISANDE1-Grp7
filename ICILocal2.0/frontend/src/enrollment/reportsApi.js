import { apiRequest } from '../api.js';
import { api } from '../api/client.js';

export const filterKeys = ['campusId', 'academicYearId', 'termId', 'gradingPeriodId', 'sectionId', 'gradeLevel', 'voucher', 'requirementStatus', 'enrollmentStatus'];
export const emptyFilters = Object.fromEntries(filterKeys.map(key => [key, '']));

/** Only filters that have a value are sent, so "All" never narrows the report. */
export function reportQuery(filters, extra = {}) {
  const query = new URLSearchParams();
  for (const key of filterKeys) if (filters[key]) query.set(key, filters[key]);
  for (const [key, value] of Object.entries(extra)) query.set(key, String(value));
  return query;
}

export const loadFilterOptions = () => apiRequest('/api/reports/filter-options');

export const loadReportPreview = (filters, page, signal) =>
  apiRequest(`/api/reports/enrollments?${reportQuery(filters, { page, size: 25 })}`, { signal });

// An export failure arrives as a blob because the request asks for a file. Read the server's message out of it.
function readBlob(blob) {
  if (typeof blob.text === 'function') return blob.text();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}

async function exportMessage(error) {
  const data = error?.response?.data;
  if (data instanceof Blob) {
    try {
      const body = JSON.parse(await readBlob(data));
      if (body?.detail) return body.detail;
    } catch { /* fall through to the generic message */ }
  }
  if (error?.response) return 'The export could not be completed. Try again.';
  return 'The service is unavailable. Check your connection and try again.';
}

function fileName(response, format) {
  const match = /filename="?([^";]+)"?/.exec(response.headers?.['content-disposition'] || '');
  return match ? match[1] : `ici-enrollments.${format}`;
}

/** Downloads the report for the current filters. Throws an Error carrying the server's message. */
export async function downloadReport(filters, format) {
  let response;
  try {
    response = await api.get(`/reports/enrollments/export?${reportQuery(filters, { format })}`, { responseType: 'blob' });
  } catch (error) {
    throw new Error(await exportMessage(error));
  }
  const url = URL.createObjectURL(response.data);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName(response, format);
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
  return link.download;
}
