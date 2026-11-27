import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import AuditLog from '../admin/AuditLog.jsx';
import { pages } from '../routes/routeConfig.js';
import { api } from '../api/client.js';
import { setAccessToken } from '../api.js';

const MOCK_REFERENCE = {
  campuses: [
    { id: 1, code: 'ILIGAN', name: 'Iligan Main' },
    { id: 2, code: 'KAPATAGAN', name: 'Kapatagan' },
  ],
  users: [
    { id: 7, username: 'registrar1', fullName: 'Maria Registrar' },
    { id: 9, username: 'admin1', fullName: 'Linus Admin' },
  ],
  recordTypes: ['applications', 'enrollments', 'grades', 'users'],
  operations: ['INSERT', 'UPDATE', 'DELETE'],
};

const PAGE_0_ITEMS = [
  {
    id: 101,
    occurredAt: '2026-11-27T08:15:00Z',
    actorId: 7,
    actorUsername: 'registrar1',
    actorName: 'Maria Registrar',
    operation: 'UPDATE',
    recordType: 'applications',
    recordId: '{"id": 42}',
    campusId: 1,
    campusName: 'Iligan Main',
    oldData: { id: 42, status: 'submitted', campus_id: 1 },
    newData: { id: 42, status: 'pre_enlisted', campus_id: 1 },
  },
  {
    id: 100,
    occurredAt: '2026-11-26T14:00:00Z',
    actorId: 9,
    actorUsername: 'admin1',
    actorName: 'Linus Admin',
    operation: 'INSERT',
    recordType: 'users',
    recordId: '{"id": 15}',
    campusId: 2,
    campusName: 'Kapatagan',
    oldData: null,
    newData: { id: 15, username: 'teacher2', active: true },
  },
];

const PAGE_1_ITEMS = [
  {
    id: 75,
    occurredAt: '2026-11-20T09:00:00Z',
    actorId: 7,
    actorUsername: 'registrar1',
    actorName: 'Maria Registrar',
    operation: 'DELETE',
    recordType: 'grades',
    recordId: '{"id": 88}',
    campusId: 1,
    campusName: 'Iligan Main',
    oldData: { id: 88, value: 89 },
    newData: null,
  },
];

afterEach(() => {
  cleanup();
  setAccessToken(null);
  vi.restoreAllMocks();
});

describe('Audit Log screen (ICI2-71)', () => {
  it('restricts /administration/audit-log exclusively to System Administrator', () => {
    const route = pages.find(page => page.path === '/administration/audit-log');
    expect(route).toBeDefined();
    expect(route.label).toBe('Audit Log');
    expect(route.allowedRoles).toEqual(['admin']);
  });

  it('renders paginated read-only entries and displays before/after snapshots', async () => {
    const requests = [];
    api.defaults.adapter = async config => {
      requests.push({ method: config.method, url: config.url });
      if (config.url === '/admin/audit-log/reference-data') {
        return { status: 200, statusText: 'OK', headers: {}, config, data: MOCK_REFERENCE };
      }
      if (config.url.startsWith('/admin/audit-log')) {
        const isPage1 = config.url.includes('page=1');
        return {
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
          data: {
            items: isPage1 ? PAGE_1_ITEMS : PAGE_0_ITEMS,
            total: 30,
            page: isPage1 ? 1 : 0,
            size: 25,
          },
        };
      }
      throw { config, response: { status: 404, data: { detail: 'Unexpected ' + config.url } } };
    };

    render(
      <MemoryRouter>
        <AuditLog />
      </MemoryRouter>
    );

    expect(await screen.findByRole('heading', { name: 'Audit Log' })).toBeInTheDocument();
    expect(screen.getByText('Read-only')).toBeInTheDocument();
    expect(await screen.findByText('30 audit entries matched')).toBeInTheDocument();

    // Verify first entry before/after diff is displayed
    expect(screen.getByText('submitted')).toBeInTheDocument();
    expect(screen.getByText('pre_enlisted')).toBeInTheDocument();

    // Toggle "Show changed fields only" hides unchanged fields like campus_id
    expect(screen.getByText('campus_id')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('Show changed fields only'));
    expect(screen.queryByText('campus_id')).not.toBeInTheDocument();
    expect(screen.getByText('status')).toBeInTheDocument();

    // Switch to second entry (INSERT)
    fireEvent.click(screen.getByRole('button', { name: 'Inspect audit entry 100' }));
    expect(screen.getByText('teacher2')).toBeInTheDocument();

    // Paginate to page 2
    fireEvent.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => {
      expect(requests.some(req => req.url.includes('page=1'))).toBe(true);
    });
    expect(await screen.findByRole('heading', { name: 'Grades (#88)' })).toBeInTheDocument();
    expect(screen.getAllByText('#88').length).toBeGreaterThan(0);

    // Confirm strictly read-only (only GET requests issued)
    expect(requests.every(req => req.method === 'get')).toBe(true);
  });

  it('filters by date range, user, record type, and campus and rejects inverted date ranges', async () => {
    const queryUrls = [];
    api.defaults.adapter = async config => {
      if (config.url === '/admin/audit-log/reference-data') {
        return { status: 200, statusText: 'OK', headers: {}, config, data: MOCK_REFERENCE };
      }
      if (config.url.startsWith('/admin/audit-log')) {
        queryUrls.push(config.url);
        return {
          status: 200,
          statusText: 'OK',
          headers: {},
          config,
          data: { items: PAGE_0_ITEMS, total: 2, page: 0, size: 25 },
        };
      }
      throw { config, response: { status: 404 } };
    };

    render(
      <MemoryRouter>
        <AuditLog />
      </MemoryRouter>
    );

    await screen.findByRole('option', { name: 'Maria Registrar (registrar1)' });

    fireEvent.change(screen.getByLabelText('From date'), { target: { value: '2026-11-01' } });
    fireEvent.change(screen.getByLabelText('To date'), { target: { value: '2026-11-30' } });
    fireEvent.change(screen.getByLabelText('Filter by user'), { target: { value: '7' } });
    fireEvent.change(screen.getByLabelText('Filter by record type'), { target: { value: 'applications' } });
    fireEvent.change(screen.getByLabelText('Filter by campus'), { target: { value: '1' } });

    await waitFor(() => {
      const latest = queryUrls[queryUrls.length - 1] || '';
      expect(latest).toContain('from=2026-11-01');
      expect(latest).toContain('to=2026-11-30');
      expect(latest).toContain('actorId=7');
      expect(latest).toContain('recordType=applications');
      expect(latest).toContain('campusId=1');
    });

    // Validate inverted date range blocks query and shows alert
    fireEvent.change(screen.getByLabelText('To date'), { target: { value: '2026-10-01' } });
    expect(screen.getByRole('alert')).toHaveTextContent('End date must be on or after the start date.');
  });
});
