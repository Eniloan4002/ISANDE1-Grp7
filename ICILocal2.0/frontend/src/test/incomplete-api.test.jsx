import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiRequest } from '../api.js';
import { addFollowUp, finalizeCompletion, getIncompleteCase, listIncompleteCases, proposeCompletion } from '../academic/incompleteGradesApi.js';
vi.mock('../api.js', () => ({ apiRequest: vi.fn() }));
beforeEach(() => apiRequest.mockReset());

describe('persistent incomplete-case adapter', () => {
  it('collects authorized pages without hiding cases and normalizes selected IDs', async () => {
    apiRequest.mockResolvedValueOnce({ items: [{ id: 1 }], total: 2, filters: { status: ['registrar_review'] } })
      .mockResolvedValueOnce({ items: [{ id: 2 }], total: 2 });
    const result = await listIncompleteCases({ role: 'registrar', actor: 'untrusted' });
    expect(result.items.map(item => item.id)).toEqual(['1', '2']);
    expect(result.filters.status).toEqual(['registrar_review']);
    expect(apiRequest.mock.calls.map(([path]) => path)).toEqual(['/api/academic/incomplete-cases?size=100&page=0', '/api/academic/incomplete-cases?size=100&page=1']);
  });
  it('reports an explicit bound rather than silently truncating', async () => {
    apiRequest.mockResolvedValue({ items: [], total: 10001, filters: {} });
    await expect(listIncompleteCases()).rejects.toThrow('More than 10,000');
  });
  it('sends expected versions and excludes supplied identity fields', async () => {
    apiRequest.mockResolvedValue({ id: 7, version: 3 });
    await addFollowUp('7', { note: 'Verified call', method: 'Phone', version: 2, actor: 'Fake approver' });
    expect(JSON.parse(apiRequest.mock.calls[0][1].body)).toEqual({ note: 'Verified call', method: 'Phone', version: 2 });
    await proposeCompletion('7', { grade: '88.50', note: 'Work assessed', version: 3, gradeVersion: 4, actor: 'Fake teacher' });
    expect(JSON.parse(apiRequest.mock.calls[1][1].body)).toEqual({ grade: 88.5, note: 'Work assessed', version: 3, gradeVersion: 4 });
    await finalizeCompletion('7', { note: 'Proposal verified', version: 4, gradeVersion: 4 });
    expect(JSON.parse(apiRequest.mock.calls[2][1].body)).toEqual({ note: 'Proposal verified', version: 4, gradeVersion: 4 });
  });
  it('loads details with stable string IDs', async () => {
    apiRequest.mockResolvedValue({ id: 9 });
    expect((await getIncompleteCase('9', 'teacher')).id).toBe('9');
    expect(apiRequest).toHaveBeenCalledWith('/api/academic/incomplete-cases/9');
  });
});
