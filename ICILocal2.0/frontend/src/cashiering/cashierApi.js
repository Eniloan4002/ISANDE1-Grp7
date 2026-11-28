import { apiRequest } from '../api.js';
import { downloadFile, query } from '../api/download.js';

export const voucherGroups = ['QVR', 'ESC', 'ALS', 'OTHER', 'UNCLASSIFIED'];
export const emptyRosterFilters = { campusId: '', termId: '', sectionId: '', voucher: '' };

/** One page of confirmed enrollments, with counts for every voucher group across all pages. */
export const loadRoster = (filters, page = 0, { size = 25, signal } = {}) =>
  apiRequest(`/api/cashiering/rosters${query({ ...filters, page, size })}`, { signal });

/** Downloads every matching enrollment as CSV. The server audits each export. */
export const exportRoster = filters =>
  downloadFile(`/cashiering/rosters/export${query(filters)}`, { fallbackName: 'ici-cashiering-roster.csv' });
