import { apiRequest } from '../api.js';
import { advisoryRosterFixture } from './advisoryRosterFixture.js';

const source = import.meta.env.VITE_ADVISORY_DATA_SOURCE || 'fixture';
const endpoint = import.meta.env.VITE_ADVISORY_ROSTER_ENDPOINT || '';

export function advisoryDataSource() {
  return source === 'api' ? 'api' : 'development-fixture';
}

export async function loadAdvisoryRoster({ signal } = {}) {
  if (source !== 'api') return advisoryRosterFixture;
  if (!endpoint) throw new Error('Advisory roster API is not configured.');
  return apiRequest(endpoint, { signal });
}
