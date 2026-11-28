import { api } from './client.js';

// A failed file request arrives as a blob because the request asked for a file. Read the server's message out of it.
function readBlob(blob) {
  if (typeof blob.text === 'function') return blob.text();
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error);
    reader.readAsText(blob);
  });
}

async function failure(original) {
  const data = original?.response?.data;
  let message = original?.response ? 'The file could not be prepared. Try again.' : 'The service is unavailable. Check your connection and try again.';
  if (data instanceof Blob) {
    try {
      const body = JSON.parse(await readBlob(data));
      if (body?.detail) message = body.detail;
    } catch { /* keep the generic message */ }
  } else if (data?.detail) message = data.detail;
  const error = new Error(message);
  error.status = original?.response?.status;
  return error;
}

function fileName(response, fallback) {
  const match = /filename="?([^";]+)"?/.exec(response.headers?.['content-disposition'] || '');
  return match ? match[1] : fallback;
}

/**
 * Fetches a file from the API and hands it to the browser as a download.
 * `path` is relative to the API base (for example `/cashiering/rosters/export`). Returns the saved file name.
 */
export async function downloadFile(path, { method = 'GET', data, fallbackName = 'download' } = {}) {
  let response;
  try {
    response = await api.request({ url: path, method, data, responseType: 'blob' });
  } catch (original) {
    throw await failure(original);
  }
  const url = URL.createObjectURL(response.data);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName(response, fallbackName);
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
  return link.download;
}

/** Builds a query string from the params that have a value, so "All" never narrows a request. */
export function query(params) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params || {})) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}
