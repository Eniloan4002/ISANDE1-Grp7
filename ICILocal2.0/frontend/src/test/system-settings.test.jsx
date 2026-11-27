import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import SystemSettings from '../admin/SystemSettings.jsx';
import { pages } from '../routes/routeConfig.js';
import { api } from '../api/client.js';
import { setAccessToken } from '../api.js';

const MOCK_REFERENCE = {
  campuses: [
    { id: 1, code: 'ILIGAN', name: 'Iligan Main' },
    { id: 2, code: 'KAPATAGAN', name: 'Kapatagan' },
  ],
  terms: [
    { id: 10, code: 'SEM1', name: 'First Semester', academicYear: '2026-2027', startsOn: '2026-06-01', endsOn: '2026-10-31' },
  ],
  programs: [],
  advisers: [],
};

afterEach(() => {
  cleanup();
  setAccessToken(null);
  vi.restoreAllMocks();
});

describe('System Settings screen (ICI2-72)', () => {
  it('restricts /administration/system-settings exclusively to System Administrator', () => {
    const route = pages.find(page => page.path === '/administration/system-settings');
    expect(route).toBeDefined();
    expect(route.label).toBe('System Settings');
    expect(route.allowedRoles).toEqual(['admin']);
  });

  it('loads all three setting groups, shows last-changed metadata, and performs version-checked saves', async () => {
    const putCalls = [];
    api.defaults.adapter = async config => {
      const method = (config.method || 'get').toLowerCase();
      if (config.url === '/enrollment/reference-data') {
        return { status: 200, statusText: 'OK', headers: {}, config, data: MOCK_REFERENCE };
      }
      if (config.url.startsWith('/enrollment/attendance-window') && method === 'get') {
        return {
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
          data: {
            configured: true,
            startsOn: '2026-06-01',
            endsOn: '2026-06-05',
            orientationStartsOn: '2026-05-28',
            orientationEndsOn: '2026-05-29',
            approvalNote: 'Memo 2026-01',
            version: 2,
          },
        };
      }
      if (config.url.startsWith('/enrollment/attendance-window') && method === 'put') {
        const parsed = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
        putCalls.push({ url: config.url, data: parsed });
        return {
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
          data: { ...parsed, configured: true, version: 3 },
        };
      }
      if (config.url === '/guidance/policies/1/10' && method === 'get') {
        return {
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
          data: {
            configured: true,
            campusId: 1,
            termId: 10,
            failingBelow: 75,
            consecutiveAbsences: 3,
            credentialsDueOn: '2026-07-15',
            reason: 'Initial policy',
            version: 4,
          },
        };
      }
      if (config.url === '/guidance/policies/1/10' && method === 'put') {
        const parsed = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
        putCalls.push({ url: config.url, data: parsed });
        return {
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
          data: { ...parsed, configured: true, campusId: 1, termId: 10, version: 5 },
        };
      }
      if (config.url === '/academic/school-days/1/10' && method === 'get') {
        return {
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
          data: {
            campusId: 1,
            termId: 10,
            items: [{ date: '2026-06-01', schoolDay: true, reason: 'Opening day', version: 1 }],
          },
        };
      }
      if (config.url === '/academic/school-days/1/10' && method === 'put') {
        const parsed = typeof config.data === 'string' ? JSON.parse(config.data) : config.data;
        putCalls.push({ url: config.url, data: parsed });
        return {
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
          data: {
            campusId: 1,
            termId: 10,
            items: [
              { date: '2026-06-01', schoolDay: true, reason: 'Opening day', version: 1 },
              { date: '2026-06-12', schoolDay: false, reason: 'Independence Day', version: 1 },
            ],
          },
        };
      }
      if (config.url.startsWith('/admin/audit-log')) {
        const table = new URLSearchParams(config.url.split('?')[1]).get('recordType');
        return {
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
          data: {
            items: [
              {
                id: 99,
                recordType: table,
                actorName: 'Linus Admin',
                occurredAt: '2026-11-27T08:00:00Z',
              },
            ],
            total: 1,
          },
        };
      }
      throw { config, response: { status: 404, data: { detail: 'Unmocked ' + config.url } } };
    };

    render(
      <MemoryRouter>
        <SystemSettings />
      </MemoryRouter>
    );

    expect(await screen.findByRole('heading', { name: 'System Settings' })).toBeInTheDocument();
    expect(await screen.findByText('Configured (v2)')).toBeInTheDocument();
    expect(await screen.findByText('Configured (v4)')).toBeInTheDocument();
    expect(await screen.findByText('1 days configured')).toBeInTheDocument();

    // Verify last-changed attribution renders for each setting
    await waitFor(() => {
      expect(screen.getByTestId('window-last-changed')).toHaveTextContent(/by Linus Admin/i);
      expect(screen.getByTestId('policy-last-changed')).toHaveTextContent(/by Linus Admin/i);
      expect(screen.getByTestId('calendar-last-changed')).toHaveTextContent(/by Linus Admin/i);
    });

    // Save attendance window with optimistic version 2
    fireEvent.change(screen.getByLabelText('Attendance window approval note'), {
      target: { value: 'Updated Memo 2026-02' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save attendance window' }));
    expect(await screen.findByText('Attendance window saved (version 3).')).toBeInTheDocument();

    // Save guidance thresholds with optimistic version 4
    fireEvent.change(screen.getByLabelText('Failing grade below'), { target: { value: '78' } });
    fireEvent.change(screen.getByLabelText('Risk thresholds reason'), {
      target: { value: 'Stricter intervention threshold' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save risk thresholds' }));
    expect(await screen.findByText('Guidance risk thresholds saved (version 5).')).toBeInTheDocument();

    // Save a new non-school day on the school-day calendar with version 0
    fireEvent.change(screen.getByLabelText('Calendar single date'), { target: { value: '2026-06-12' } });
    fireEvent.change(screen.getByLabelText('Calendar day type'), { target: { value: 'false' } });
    fireEvent.change(screen.getByLabelText('Single date change reason'), {
      target: { value: 'Independence Day' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save calendar day' }));
    expect(await screen.findByText('Calendar updated for 2026-06-12.')).toBeInTheDocument();

    // Verify version numbers sent to backend
    expect(putCalls).toHaveLength(3);
    expect(putCalls[0].data.version).toBe(2);
    expect(putCalls[1].data.version).toBe(4);
    expect(putCalls[1].data.failingBelow).toBe(78);
    expect(putCalls[2].data.days).toEqual([{ date: '2026-06-12', schoolDay: false, version: 0 }]);
  });

  it('surfaces optimistic version conflict errors clearly', async () => {
    api.defaults.adapter = async config => {
      const method = (config.method || 'get').toLowerCase();
      if (config.url === '/enrollment/reference-data') {
        return { status: 200, statusText: 'OK', headers: {}, config, data: MOCK_REFERENCE };
      }
      if (config.url.startsWith('/enrollment/attendance-window') && method === 'get') {
        return {
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
          data: { configured: true, startsOn: '2026-06-01', endsOn: '2026-06-05', approvalNote: 'Memo', version: 1 },
        };
      }
      if (config.url.startsWith('/enrollment/attendance-window') && method === 'put') {
        throw {
          config,
          response: { status: 409, data: { detail: 'Attendance dates changed. Reload before saving.' } },
        };
      }
      if (config.url.startsWith('/guidance/policies/')) {
        return { status: 200, statusText: 'OK', headers: {}, config, data: { configured: false, version: 0 } };
      }
      if (config.url.startsWith('/academic/school-days/')) {
        return { status: 200, statusText: 'OK', headers: {}, config, data: { items: [] } };
      }
      return { status: 200, statusText: 'OK', headers: {}, config, data: { items: [] } };
    };

    render(
      <MemoryRouter>
        <SystemSettings />
      </MemoryRouter>
    );

    await screen.findByText('Configured (v1)');
    fireEvent.click(screen.getByRole('button', { name: 'Save attendance window' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Attendance dates changed. Reload before saving.');
  });
});
