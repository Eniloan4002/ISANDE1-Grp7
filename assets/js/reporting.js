// Reporting & Monitoring demo interactions — backed by the shared localStorage store.
// Toast helper (shared by all enrollment screens).
const toast = document.querySelector('#toast');
let timer;
function showToast(title, detail) {
  toast.querySelector('strong').textContent = title;
  toast.querySelector('small').textContent = detail;
  toast.classList.add('show');
  clearTimeout(timer);
  timer = setTimeout(() => toast.classList.remove('show'), 3500);
}
toast.querySelector('button').addEventListener('click', () => toast.classList.remove('show'));

// Report definitions with demo data compiled from the centralized records.
const REPORTS = {
  enrollment: {
    title: 'Enrollment Summary', desc: 'Pre-enlisted, pending, and enrolled counts by campus.',
    head: ['Campus', 'Pre-enlisted', 'Pending req.', 'Enrolled', 'Total'],
    rows: [] // live — see buildEnrollmentRows()
  },
  records: {
    title: 'Student Records', desc: 'Directory of active student records.',
    head: ['Student', 'Student ID', 'Track', 'Campus', 'Status'],
    rows: [
      ['Abad, Carla', 'STU-2026-0452', 'ABM', 'Main', 'Pending confirmation'],
      ['Dela Cruz, Juan', 'STU-2026-0453', 'HUMSS', 'Main', 'Pending confirmation'],
      ['Lim, Angela', 'STU-2026-0451', 'BSOA', 'Kapatagan', 'Enrolled'],
      ['Reyes, Pedro', 'STU-2026-0455', 'TVL-Cookery', 'Bugo', 'Enrolled'],
      ['Santos, Maria A.', 'STU-2026-0454', 'STEM', 'Main', 'Enrolled']
    ]
  },
  missing: {
    title: 'Missing Requirements', desc: 'Students with incomplete or pending credentials.',
    head: ['Student', 'App ID', 'Missing / pending docs', 'Last update', 'Contact'],
    rows: [
      ['Dela Cruz, Juan', 'APP-2026-0148', 'Completion, Good Moral, Form 137', 'Aug 3, 2026', '0917 555 0148'],
      ['Reyes, Pedro', 'APP-2026-0150', 'All documents pending', 'Aug 4, 2026', '0919 555 0150'],
      ['Santos, Maria A.', 'APP-2026-0149', 'Form 137, Good Moral', 'Aug 5, 2026', '0918 555 0149']
    ]
  },
  attendance: {
    title: 'Attendance', desc: 'Per-applicant first-week attendance, derived live from the centralized attendance log.',
    head: ['Student', 'App ID', 'Present', 'Absent', 'Pending', 'Status'],
    rows: []
  },
  honor: {
    title: 'Honor Students', desc: 'Top students by general weighted average (GWA).',
    head: ['Rank', 'Student', 'Section', 'GWA', 'Honor'],
    rows: [
      ['1', 'Ramos, Sofia', 'STEM-B', '96.4', '<span class="status-pill success">With Highest Honors</span>'],
      ['2', 'Ong, Matthew', 'STEM-A', '95.1', '<span class="status-pill success">With Highest Honors</span>'],
      ['3', 'Villanueva, Bea', 'ABM-1', '93.8', '<span class="status-pill success">With High Honors</span>'],
      ['4', 'Cruz, Andrei', 'HUMSS-1', '92.0', '<span class="status-pill success">With High Honors</span>']
    ]
  },
  docs: {
    title: 'Registrar Documents', desc: 'Form 137 / 138 / TOR requests issued by the registrar.',
    head: ['Request ID', 'Student', 'Document', 'Status', 'Released'],
    rows: [
      ['DOC-2026-0011', 'Ramos, Sofia', 'Form 138 (Report Card)', '<span class="status-pill success">Issued</span>', 'Aug 6, 2026'],
      ['DOC-2026-0012', 'Ong, Matthew', 'Form 137 (Transcript)', '<span class="status-pill warning">For review</span>', '—'],
      ['DOC-2026-0013', 'Villanueva, Bea', 'TOR', '<span class="status-pill success">Issued</span>', 'Aug 5, 2026'],
      ['DOC-2026-0014', 'Cruz, Andrei', 'Form 138 (Report Card)', '<span class="status-pill warning">Pending docs</span>', '—']
    ]
  }
};

const typeButtons = [...document.querySelectorAll('.type-item')];
const preview = document.querySelector('#preview-panel');
const previewTable = document.querySelector('#preview-table');
const previewMeta = document.querySelector('#preview-meta');

let syncedScenarioAppId = '';
let syncedScenarioStudentId = '';

function syncCarloReports() {
  if (syncedScenarioStudentId) REPORTS.records.rows = REPORTS.records.rows.filter(row => row[1] !== syncedScenarioStudentId);
  if (syncedScenarioAppId) REPORTS.missing.rows = REPORTS.missing.rows.filter(row => row[1] !== syncedScenarioAppId);

  const journey = Store.getJourney();
  const appId = journey?.persona === 'transferee' ? journey.appId : '';
  const app = appId ? Store.applicants[appId] : null;
  syncedScenarioAppId = app ? appId : '';
  syncedScenarioStudentId = app ? Store.recordByApp(appId) || '' : '';
  if (!app) return;

  if (syncedScenarioStudentId) {
    const record = Store.getRecord(syncedScenarioStudentId);
    REPORTS.records.rows.push([
      app.name,
      syncedScenarioStudentId,
      String(app.programLabel || '—').split('·')[0].trim(),
      app.campus || '—',
      record?.status === 'enrolled' ? 'Enrolled' : 'Pending confirmation'
    ]);
  }

  const docs = Store.getRequirements(appId)?.docs || {};
  const missing = Object.entries(docs).filter(([, value]) => value !== 'submitted').map(([doc]) => doc);
  if (missing.length) {
    REPORTS.missing.rows.push([
      app.name,
      appId,
      missing.join(', '),
      app.submittedAt || 'Current',
      app.contact || '—'
    ]);
  }
}

function selectType(type) {
  typeButtons.forEach(button => button.classList.toggle('active', button.dataset.type === type));
  document.querySelector('#gen-title').textContent = REPORTS[type].title;
  document.querySelector('#gen-desc').textContent = REPORTS[type].desc;
}

typeButtons.forEach(button => button.addEventListener('click', () => selectType(button.dataset.type)));

function paramSummary() {
  const selects = [...document.querySelectorAll('.params select')];
  return selects.map(select => select.options[select.selectedIndex].text).join(' · ');
}

// The params row is [School year, Grading period, Grade level, Campus, Section] —
// only Campus and Grade level map cleanly onto applicant fields, so those are the
// only ones applied to the live reports below (best-effort, not exhaustive).
function readFilters() {
  const selects = [...document.querySelectorAll('.params select')];
  const valueOf = (select) => select ? select.options[select.selectedIndex].text : '';
  return {
    grade: valueOf(selects[2]),
    campus: valueOf(selects[3])
  };
}

function matchesFilters(app, filters) {
  if (filters.campus && filters.campus !== 'All campuses' && app.campus !== filters.campus) return false;
  if (filters.grade && filters.grade !== 'All levels' && app.grade !== filters.grade) return false;
  return true;
}

// Last-update timestamp for an applicant: prefer the most recent audit entry
// (real change history), fall back to the applicant's own submission date.
function lastUpdateFor(appId, app) {
  const audits = Store.getAudits(appId);
  if (audits && audits.length) {
    const parts = audits[0].split('—');
    if (parts.length > 1) return parts[parts.length - 1].trim();
  }
  return app.submittedAt || '—';
}

// "Missing Requirements" — live: any applicant whose derived requirement status
// isn't Complete, listing the doc names that are not yet submitted.
function buildMissingRows(filters) {
  return Object.values(Store.applicants)
    .filter(app => matchesFilters(app, filters))
    .filter(app => Store.deriveRequirements(app.id) !== 'Complete')
    .map(app => {
      const req = Store.getRequirements(app.id);
      const docs = (req && req.docs) || {};
      const missingDocs = Object.entries(docs).filter(([, status]) => status !== 'submitted').map(([doc]) => doc);
      const missingText = missingDocs.length ? missingDocs.join(', ') : 'All documents pending';
      const contact = (req && req.contact) || app.contact || '—';
      return [app.name, app.id, missingText, lastUpdateFor(app.id, app), contact];
    });
}

// "Enrollment Summary" — live: pre-enlisted / pending-requirements / enrolled
// counts per campus, grouped from the applicant queue itself.
function buildEnrollmentRows(filters) {
  const applicants = Object.values(Store.applicants).filter(app => matchesFilters(app, filters));
  const byCampus = {};
  applicants.forEach(app => {
    const key = app.campus || 'Unassigned';
    if (!byCampus[key]) byCampus[key] = { preEnlisted: 0, pending: 0, enrolled: 0, total: 0 };
    byCampus[key].total += 1;
    if (app.queueStatus === 'pre-enlisted') byCampus[key].preEnlisted += 1;
    else if (app.queueStatus === 'pending') byCampus[key].pending += 1;
    else if (app.queueStatus === 'enrolled') byCampus[key].enrolled += 1;
  });
  const rows = Object.entries(byCampus).map(([campus, c]) =>
    [campus, String(c.preEnlisted), String(c.pending), String(c.enrolled), String(c.total)]);
  const totals = rows.reduce((acc, row) => ({
    pre: acc.pre + Number(row[1]), pend: acc.pend + Number(row[2]), enr: acc.enr + Number(row[3]), tot: acc.tot + Number(row[4])
  }), { pre: 0, pend: 0, enr: 0, tot: 0 });
  rows.push([
    '<strong>Total</strong>', `<strong>${totals.pre}</strong>`, `<strong>${totals.pend}</strong>`,
    `<strong>${totals.enr}</strong>`, `<strong>${totals.tot}</strong>`
  ]);
  return rows;
}

// "Attendance" — live, reshaped to one row per applicant (Store.attendance is
// applicant-level, not section-level): present/absent/pending day counts plus
// the same verification gate used elsewhere (Store.attendanceVerified).
function buildAttendanceRows(filters) {
  return Object.values(Store.applicants)
    .filter(app => matchesFilters(app, filters))
    .map(app => {
      const att = Store.getAttendance(app.id);
      if (!att || !Array.isArray(att.log) || !att.log.length) return null;
      const counts = { Present: 0, Absent: 0, Pending: 0 };
      att.log.forEach(day => { counts[day.status] = (counts[day.status] || 0) + 1; });
      const verified = Store.attendanceVerified(app.id);
      const statusHtml = verified
        ? '<span class="status-pill success">Verified</span>'
        : '<span class="status-pill warning">Not yet verified</span>';
      return [app.name, app.id, String(counts.Present || 0), String(counts.Absent || 0), String(counts.Pending || 0), statusHtml];
    })
    .filter(Boolean);
}

function renderLog() {
  const log = document.querySelector('#log-body');
  const entries = Store.getReportLog();
  log.innerHTML = entries.map((entry, idx) =>
    `<tr>
      <td>${entry.when}</td><td>${entry.title}</td><td>${entry.criteria}</td><td>${entry.by}</td><td>${entry.format}</td>
      <td class="action-cell"><button class="row-action" data-del-log="${idx}" title="Remove log entry">Remove</button></td>
    </tr>`
  ).join('');
  const count = document.querySelector('#log-count');
  if (count) count.textContent = `Showing ${entries.length} of ${entries.length} logged generations`;
}

// These 3 report types read live Store data at generate-time; the other 3
// (records/honor/docs) stay on the hardcoded REPORTS rows — see the
// "(sample data)" badges on their tiles in reporting-monitoring.html.
const LIVE_ROW_BUILDERS = {
  missing: buildMissingRows,
  enrollment: buildEnrollmentRows,
  attendance: buildAttendanceRows
};

function generate() {
  syncCarloReports();
  const type = typeButtons.find(button => button.classList.contains('active')).dataset.type;
  const report = REPORTS[type];
  const builder = LIVE_ROW_BUILDERS[type];
  const rows = builder ? builder(readFilters()) : report.rows;
  previewTable.querySelector('thead').innerHTML = `<tr>${report.head.map(h => `<th>${h}</th>`).join('')}</tr>`;
  previewTable.querySelector('tbody').innerHTML = rows.map(row => `<tr>${row.map(cell => `<td>${cell}</td>`).join('')}</tr>`).join('');
  const now = 'Aug 7, 2026 10:42 AM';
  previewMeta.innerHTML = `<span><strong>Report:</strong> ${report.title}</span><span><strong>Generated:</strong> ${now}</span><span><strong>Criteria:</strong> ${paramSummary()}</span><span><strong>By:</strong> R. Santos (Registrar Staff)</span>`;
  preview.classList.add('show');
  preview.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  Store.addReport({ when: now, title: report.title, criteria: paramSummary(), by: 'R. Santos', format: 'PDF' });
  renderLog();
  showToast('Report generated', `${report.title} compiled and logged for audit.`);
}

document.querySelector('#log-body').addEventListener('click', event => {
  const btn = event.target.closest('[data-del-log]');
  if (!btn) return;
  Store.removeReport(Number(btn.dataset.delLog));
  renderLog();
  showToast('Log entry removed', 'Report generation log updated.');
});

document.querySelector('#btn-generate').addEventListener('click', generate);
document.querySelector('#btn-print').addEventListener('click', () => showToast('Printing', 'The report is being sent to the printer.'));
document.querySelectorAll('[data-export]').forEach(button => button.addEventListener('click', () => showToast('Export prepared', 'CSV file downloaded — demonstration prototype.')));
document.querySelectorAll('[data-export-pdf]').forEach(button => button.addEventListener('click', () => showToast('Export prepared', 'PDF file downloaded — demonstration prototype.')));

document.addEventListener('store:change', () => { syncCarloReports(); renderLog(); });
syncCarloReports();
renderLog();
