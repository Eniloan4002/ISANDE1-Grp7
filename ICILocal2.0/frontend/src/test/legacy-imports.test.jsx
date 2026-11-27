import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import LegacyImports from '../imports/LegacyImports.jsx';
import { pages } from '../routes/routeConfig.js';
import * as importApi from '../api/legacyImports.js';
import { api } from '../api/client.js';

let mockRoles = ['registrar'];

vi.mock('../auth/AuthContext.jsx', () => ({
  useAuth: () => ({
    user: {
      userId: 7,
      fullName: 'Registrar User',
      grants: [{ campusId: 1, campusCode: 'ILIGAN', campusName: 'Iligan Main', role: 'ROLE_REGISTRAR' }],
    },
    roles: mockRoles,
  }),
}));

vi.mock('../api/legacyImports.js', () => ({
  listImportMappings: vi.fn(),
  getImportMapping: vi.fn(),
  createImportMapping: vi.fn(),
  updateImportMapping: vi.fn(),
  uploadImportBatch: vi.fn(),
  listImportBatches: vi.fn(),
  getImportBatch: vi.fn(),
  listImportBatchRows: vi.fn(),
  listImportBatchErrors: vi.fn(),
  reviewImportRow: vi.fn(),
  commitImportBatch: vi.fn(),
  discardImportBatch: vi.fn(),
  voidImportBatch: vi.fn(),
}));

HTMLDialogElement.prototype.showModal ??= function showModal() {
  this.open = true;
};
HTMLDialogElement.prototype.close ??= function close() {
  this.open = false;
};

const defaultStudentMapping = {
  id: 1,
  name: 'Assumed default (unvalidated)',
  version: 0,
  active: true,
  isDefault: true,
  realFileValidated: false,
  mapping: {
    fields: {
      studentNumber: { required: true, aliases: ['student_no'] },
      lastName: { required: true, aliases: ['last_name'] },
      firstName: { required: true, aliases: ['first_name'] },
      birthDate: { required: true, aliases: ['birth_date'] },
      voucher: { required: true, aliases: ['voucher'] },
      specialization: { required: true, aliases: ['strand'] },
    },
    valueMaps: { voucher: {}, specialization: {}, campus: {}, sex: {} },
  },
  settings: {
    encoding: 'UTF-8',
    delimiter: ',',
    headerRow: 1,
    dateFormats: ['yyyy-MM-dd', 'MM/dd/yyyy'],
    normalizeNameCase: false,
  },
};

const defaultGradeMapping = {
  id: 3,
  name: 'Assumed grade matrix default (unvalidated)',
  version: 0,
  active: true,
  isDefault: true,
  realFileValidated: false,
  mapping: {
    fields: {
      studentNumber: { required: false, aliases: ['student_no'] },
      lrn: { required: false, aliases: ['lrn'] },
    },
    grid: { layout: 'subject_period_flat', separator: ' - ', reportedFinalLabels: [] },
    valueMaps: { subject: {}, period: {}, term: {}, schoolYear: {}, mark: {} },
  },
  settings: {
    encoding: 'UTF-8',
    delimiter: ',',
    headerRow: 1,
    decimalSeparator: '.',
    ignoredColumns: [],
  },
};

const sampleStudentBatch = {
  id: 42,
  sourceName: 'iligan-2025.csv',
  status: 'validated',
  campusId: 1,
  checksum: '9f2c8b0a1234567890abcdef',
  fileSizeBytes: 2048,
  sourceCount: 3,
  validCount: 2,
  invalidCount: 0,
  flaggedCount: 1,
  skippedCount: 0,
  importedCount: 0,
  version: 2,
  startedAt: '2026-11-27T08:00:00Z',
  createdBy: 7,
  importedBy: null,
  importedAt: null,
  discarded: false,
  discardedAt: null,
  discardedBy: null,
  discardReason: null,
  mapping: { id: 1, name: 'Assumed default (unvalidated)', version: 0, realFileValidated: false },
  batchExceptions: [],
  errorSummary: [{ code: 'DUPLICATE_CANDIDATE', severity: 'warning', count: 1 }],
};

const sampleGradeBatch = {
  id: 77,
  sourceName: 'grades-2025-q1.csv',
  status: 'validated',
  campusId: 1,
  checksum: '4be199887766554433221100',
  fileSizeBytes: 1024,
  sourceCount: 2,
  validCount: 1,
  invalidCount: 0,
  flaggedCount: 1,
  skippedCount: 0,
  importedCount: 0,
  version: 1,
  startedAt: '2026-11-27T08:30:00Z',
  createdBy: 7,
  importedBy: null,
  importedAt: null,
  voidedAt: null,
  voidedBy: null,
  voidReason: null,
  discarded: false,
  uploadContext: { academicYearId: 9, termId: 14, gradeLevel: 'Grade 11', section: '11-STEM-A' },
  recordCount: 4,
  blankCellCount: 1,
  liveRecordCount: 0,
  voidedRecordCount: 0,
  columns: [
    { column: 'MATH101 - Q1', records: 2 },
    { column: 'SCI101 - Q1', records: 2 },
  ],
  mapping: { id: 3, name: 'Assumed grade matrix default (unvalidated)', version: 0, realFileValidated: false },
  batchExceptions: [],
  errorSummary: [],
};

const flaggedStudentRow = {
  id: 311,
  rowNumber: 2,
  status: 'duplicate_flagged',
  version: 0,
  rawValues: { 'Student No': 'SYN-01', LRN: '991234567801', 'Last Name': 'Santos', 'First Name': 'Ana' },
  normalizedValues: {
    studentNumber: 'SYN-01',
    lrn: '991234567801',
    lastName: 'Santos',
    firstName: 'Ana',
    birthDate: '2009-03-14',
    voucherCode: 'QVR',
    programCode: 'STEM',
    track: 'ACADEMIC',
    campusId: 1,
  },
  duplicateEvidence: [{ kind: 'student', rule: 'LRN_EXACT', nameScore: 0.92, campusVisible: true, id: 118 }],
  review: null,
  studentId: null,
  messages: [{ code: 'DUPLICATE_CANDIDATE', field: null, severity: 'warning', detail: 'Possible duplicate (same LRN).' }],
};

const flaggedGradeRow = {
  id: 501,
  rowNumber: 3,
  status: 'duplicate_flagged',
  version: 0,
  rawValues: { 'Student No': 'SYN-01', 'Last Name': 'Santos', 'MATH101 - Q1': '89.50', 'SCI101 - Q1': 'INC' },
  normalizedValues: {
    studentId: null,
    matchedBy: null,
    context: { academicYearId: 9, termId: 14, gradeLevel: 'Grade 11', section: '11-STEM-A' },
    cells: [
      { column: 'MATH101 - Q1', kind: 'period', value: '89.50' },
      { column: 'SCI101 - Q1', kind: 'period', mark: 'INC' },
    ],
    blankCells: 0,
  },
  duplicateEvidence: [{ kind: 'student', rule: 'NAME_MISMATCH', nameScore: 0.65, id: 118 }],
  review: null,
  studentId: null,
  messages: [{ code: 'NAME_MISMATCH', field: null, severity: 'warning', detail: 'Name differs from student record.' }],
};

function setupMocks({
  batch = sampleStudentBatch,
  rows = [flaggedStudentRow],
  mapping = defaultStudentMapping,
  roles = ['registrar'],
} = {}) {
  mockRoles = roles;
  api.defaults.adapter = async config => {
    if (config.url.startsWith('/setup/academic-years')) {
      return { status: 200, statusText: 'OK', headers: {}, config, data: [{ id: 9, label: '2025-2026' }] };
    }
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: {
        campuses: [{ id: 1, code: 'ILIGAN', name: 'Iligan Main' }],
        terms: [{ id: 14, name: '1st Semester', academicYear: '2025-2026', academicYearId: 9 }],
        programs: [],
        advisers: [],
        vouchers: [],
      },
    };
  };
  importApi.listImportMappings.mockResolvedValue({ items: [mapping] });
  importApi.listImportBatches.mockResolvedValue({ items: [batch], total: 1, page: 0, size: 25 });
  importApi.getImportBatch.mockResolvedValue(batch);
  importApi.listImportBatchRows.mockResolvedValue({ items: rows, total: rows.length, page: 0, size: 25 });
  importApi.listImportBatchErrors.mockResolvedValue({ items: [], total: 0, page: 0, size: 25 });
}

function renderScreen(initialEntry = '/migration/legacy-imports') {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <LegacyImports />
    </MemoryRouter>
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe('Legacy Import Review screen (ICI2-64)', () => {
  it('restricts the route to registrar and admin roles', () => {
    const route = pages.find(page => page.path === '/migration/legacy-imports');
    expect(route).toBeDefined();
    expect(route.label).toBe('Legacy Import Review');
    expect(route.allowedRoles).toEqual(['registrar', 'admin']);
  });

  it('lists staged student CSV batches and runs dry-run CSV uploads', async () => {
    setupMocks();
    importApi.uploadImportBatch.mockResolvedValue({ ...sampleStudentBatch, id: 55, sourceName: 'new-export.csv' });

    renderScreen();

    expect(await screen.findByRole('heading', { name: 'Legacy Import Review' })).toBeInTheDocument();
    expect(await screen.findByText('iligan-2025.csv')).toBeInTheDocument();

    const fileInput = screen.getByLabelText('CSV file');
    const file = new File(['Student No\nSYN-01'], 'new-export.csv', { type: 'text/csv' });
    fireEvent.change(fileInput, { target: { files: [file] } });
    fireEvent.click(screen.getByRole('button', { name: 'Run dry-run upload' }));

    await waitFor(() =>
      expect(importApi.uploadImportBatch).toHaveBeenCalledWith({
        kind: 'students',
        campusId: 1,
        mappingId: undefined,
        academicYearId: undefined,
        termId: undefined,
        gradeLevel: undefined,
        section: undefined,
        file,
      })
    );
    expect(await screen.findByText(/Dry-run batch #55 staged/)).toBeInTheDocument();
  });

  it('reviews a duplicate-flagged student row and blocks distinct on LRN_EXACT', async () => {
    setupMocks();
    importApi.reviewImportRow.mockResolvedValue({
      row: { ...flaggedStudentRow, status: 'valid', version: 1 },
      batch: { ...sampleStudentBatch, validCount: 3, flaggedCount: 0, version: 3 },
    });

    renderScreen('/migration/legacy-imports?batchId=42');

    expect(await screen.findByText(/Commit blocked:/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Commit batch/ })).toBeDisabled();

    fireEvent.click(await screen.findByRole('button', { name: 'Review row #2' }));
    const decisionSelect = await screen.findByLabelText('Decision');
    expect(screen.getByRole('option', { name: /Distinct person/ })).toBeDisabled();

    fireEvent.change(decisionSelect, { target: { value: 'same_person' } });
    fireEvent.change(screen.getByLabelText('Review reason (required)'), {
      target: { value: 'Confirmed same student by registrar' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save row decision' }));

    await waitFor(() =>
      expect(importApi.reviewImportRow).toHaveBeenCalledWith(42, 311, {
        kind: 'students',
        version: 0,
        decision: 'same_person',
        reason: 'Confirmed same student by registrar',
        studentId: 118,
      })
    );
  });

  it('commits a clean validated student batch with confirmation', async () => {
    const cleanBatch = { ...sampleStudentBatch, validCount: 3, flaggedCount: 0 };
    setupMocks({ batch: cleanBatch, rows: [] });
    importApi.commitImportBatch.mockResolvedValue({
      batchId: 42,
      status: 'imported',
      created: 2,
      linked: 1,
      skipped: 0,
      invalid: 0,
      importedCount: 3,
      version: 3,
      alreadyImported: false,
    });

    renderScreen('/migration/legacy-imports?batchId=42');

    const commitButton = await screen.findByRole('button', { name: 'Commit batch (3)' });
    expect(commitButton).not.toBeDisabled();
    fireEvent.click(commitButton);

    const dialog = screen.getByRole('dialog', { name: 'Commit batch #42?' });
    expect(dialog).toHaveTextContent('iligan-2025.csv');
    fireEvent.click(screen.getByRole('button', { name: 'Commit to student records' }));

    await waitFor(() =>
      expect(importApi.commitImportBatch).toHaveBeenCalledWith(42, {
        kind: 'students',
        version: 2,
        expectedValidCount: 3,
      })
    );
    expect(await screen.findByText(/Batch #42 committed: 2 student\(s\) created, 1 linked/)).toBeInTheDocument();
  });

  it('discards a staged or validated batch with a required reason and shows discarded metadata', async () => {
    setupMocks();
    const discardedBatch = {
      ...sampleStudentBatch,
      status: 'failed',
      version: 3,
      discarded: true,
      discardedAt: '2026-11-27T09:15:00Z',
      discardedBy: 7,
      discardReason: 'Uploaded under wrong campus',
    };
    importApi.discardImportBatch.mockResolvedValue({ batchId: 42, status: 'failed', discarded: true });

    renderScreen('/migration/legacy-imports?batchId=42');

    fireEvent.click(await screen.findByRole('button', { name: 'Discard batch' }));
    const dialog = screen.getByRole('dialog', { name: 'Discard batch #42?' });
    expect(dialog).toBeInTheDocument();

    const confirmBtn = screen.getByRole('button', { name: 'Confirm discard' });
    expect(confirmBtn).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Discard reason (required)'), {
      target: { value: 'Uploaded under wrong campus' },
    });
    expect(confirmBtn).not.toBeDisabled();

    importApi.getImportBatch.mockResolvedValue(discardedBatch);
    importApi.listImportBatches.mockResolvedValue({ items: [discardedBatch], total: 1, page: 0, size: 25 });

    fireEvent.click(confirmBtn);

    await waitFor(() =>
      expect(importApi.discardImportBatch).toHaveBeenCalledWith(42, {
        kind: 'students',
        version: 2,
        reason: 'Uploaded under wrong campus',
      })
    );
    expect(await screen.findByText(/Batch #42 discarded/)).toBeInTheDocument();
    expect(await screen.findByText('Uploaded under wrong campus')).toBeInTheDocument();
  });

  it('switches to Grade Matrix mode, uploads with academic context, and reviews a flagged row without distinct', async () => {
    setupMocks({
      batch: sampleGradeBatch,
      rows: [flaggedGradeRow],
      mapping: defaultGradeMapping,
    });
    const uploadedBatch = { ...sampleGradeBatch, id: 88, sourceName: 'matrix-q2.csv' };
    importApi.uploadImportBatch.mockResolvedValue(uploadedBatch);
    importApi.reviewImportRow.mockResolvedValue({
      row: { ...flaggedGradeRow, status: 'valid', version: 1 },
      batch: { ...uploadedBatch, validCount: 2, flaggedCount: 0, version: 2 },
    });

    renderScreen('/migration/legacy-imports?type=grades&batchId=77');

    expect(await screen.findByRole('heading', { name: 'Upload historical grade matrix CSV (dry run)' })).toBeInTheDocument();
    expect(await screen.findByText('MATH101 - Q1: 2')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Academic year (optional)'), { target: { value: '9' } });
    fireEvent.change(screen.getByLabelText('Academic term (optional)'), { target: { value: '14' } });
    fireEvent.change(screen.getByLabelText('Grade level (optional)'), { target: { value: 'Grade 11' } });
    fireEvent.change(screen.getByLabelText('Section (optional)'), { target: { value: '11-STEM-A' } });

    importApi.getImportBatch.mockResolvedValue(uploadedBatch);
    const file = new File(['Student No,MATH101 - Q1\nSYN-01,90'], 'matrix-q2.csv', { type: 'text/csv' });
    fireEvent.change(screen.getByLabelText('CSV file'), { target: { files: [file] } });
    fireEvent.click(screen.getByRole('button', { name: 'Run dry-run upload' }));

    await waitFor(() =>
      expect(importApi.uploadImportBatch).toHaveBeenCalledWith({
        kind: 'grades',
        campusId: 1,
        mappingId: undefined,
        academicYearId: 9,
        termId: 14,
        gradeLevel: 'Grade 11',
        section: '11-STEM-A',
        file,
      })
    );

    expect(await screen.findByRole('heading', { name: 'Grade matrix batch #88' })).toBeInTheDocument();
    fireEvent.click(await screen.findByRole('button', { name: 'Review row #3' }));
    expect(await screen.findByText('Parsed grade cells (2)')).toBeInTheDocument();
    expect(screen.getAllByText('89.50').length).toBeGreaterThanOrEqual(2);
    expect(screen.queryByRole('option', { name: /Distinct person/ })).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('Decision'), { target: { value: 'same_person' } });
    fireEvent.change(screen.getByLabelText('Review reason (required)'), {
      target: { value: 'Verified against paper class record' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Save row decision' }));

    await waitFor(() =>
      expect(importApi.reviewImportRow).toHaveBeenCalledWith(88, 501, {
        kind: 'grades',
        version: 0,
        decision: 'same_person',
        reason: 'Verified against paper class record',
        studentId: 118,
      })
    );
  });

  it('voids a committed grade matrix batch with a required reason', async () => {
    const importedGradeBatch = {
      ...sampleGradeBatch,
      status: 'imported',
      validCount: 0,
      flaggedCount: 0,
      importedCount: 2,
      liveRecordCount: 4,
      voidedRecordCount: 0,
      version: 4,
      importedAt: '2026-11-27T09:00:00Z',
    };
    const voidedGradeBatch = {
      ...importedGradeBatch,
      version: 5,
      liveRecordCount: 0,
      voidedRecordCount: 4,
      voidedAt: '2026-11-27T10:00:00Z',
      voidedBy: 7,
      voidReason: 'Uploaded under wrong academic term',
    };
    setupMocks({ batch: importedGradeBatch, rows: [], mapping: defaultGradeMapping });
    importApi.voidImportBatch.mockResolvedValue({
      batchId: 77,
      status: 'imported',
      voided: true,
      voidedRecords: 4,
      voidedAt: '2026-11-27T10:00:00Z',
      version: 5,
      alreadyVoided: false,
    });

    renderScreen('/migration/legacy-imports?type=grades&batchId=77');

    const voidBtn = await screen.findByRole('button', { name: 'Void batch' });
    fireEvent.click(voidBtn);
    expect(screen.getByRole('dialog', { name: 'Void committed grade batch #77?' })).toBeInTheDocument();

    const confirmVoid = screen.getByRole('button', { name: 'Confirm void' });
    expect(confirmVoid).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Void reason (required)'), {
      target: { value: 'Uploaded under wrong academic term' },
    });
    expect(confirmVoid).not.toBeDisabled();

    importApi.getImportBatch.mockResolvedValue(voidedGradeBatch);
    importApi.listImportBatches.mockResolvedValue({ items: [voidedGradeBatch], total: 1, page: 0, size: 25 });

    fireEvent.click(confirmVoid);

    await waitFor(() =>
      expect(importApi.voidImportBatch).toHaveBeenCalledWith(77, {
        version: 4,
        reason: 'Uploaded under wrong academic term',
      })
    );
    expect(await screen.findByText(/Grade batch #77 voided: 4 legacy grade record\(s\) marked voided/)).toBeInTheDocument();
    expect(await screen.findByText('Uploaded under wrong academic term')).toBeInTheDocument();
  });

  it('shows read-only mappings for registrars and editable grade/student mapping forms for administrators', async () => {
    setupMocks({ roles: ['registrar'] });
    const { unmount } = renderScreen();

    fireEvent.click(await screen.findByRole('tab', { name: /Column Mappings/ }));
    expect(await screen.findByText(/Only administrators can create or edit column mappings/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Create mapping' })).not.toBeInTheDocument();
    unmount();

    setupMocks({ roles: ['admin'], mapping: defaultGradeMapping, batch: sampleGradeBatch });
    renderScreen('/migration/legacy-imports?type=grades');
    fireEvent.click(await screen.findByRole('tab', { name: /Column Mappings/ }));
    expect(await screen.findByLabelText('Subject / period header separator')).toBeInTheDocument();
    expect(screen.getByLabelText('Ignored columns (comma-separated, optional)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create mapping' })).toBeInTheDocument();
  });
});
