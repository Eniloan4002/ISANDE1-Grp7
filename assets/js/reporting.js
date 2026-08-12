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
    rows: [] // live — see buildRecordsRows()
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
    rows: [] // live — see buildHonorRows()
  },
  docs: {
    title: 'Registrar Documents', desc: 'Form 137 / 138 / TOR requests issued by the registrar.',
    head: ['Request ID', 'Student', 'Document', 'Status', 'Released'],
    rows: [] // live — see buildDocsRows()
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

// "Student Records" — live: the confirmed/pending student directory, read from
// the same records the Enrollment Records screen uses.
function buildRecordsRows(filters) {
  return Object.entries(Store.getRecords())
    .map(([studentId, record]) => {
      const app = record.app ? Store.applicants[record.app] : null;
      if (app && !matchesFilters(app, filters)) return null;
      // record.meta reads "Grade 11 · TVL-Cookery · Bugo — CDO"; the middle
      // segment is the track, the last is the campus.
      const parts = String(record.meta || '').split('·').map(part => part.trim());
      return [
        record.name,
        studentId,
        parts[1] || '—',
        parts[2] || (app && app.campus) || '—',
        record.status === 'enrolled' ? 'Enrolled' : 'Pending confirmation'
      ];
    })
    .filter(Boolean)
    .sort((a, b) => a[0].localeCompare(b[0]));
}

// Quarter-1 average for an academic student, using the same component
// calculation the Academic Records module uses. Null when nothing is complete.
function averageFor(studentId) {
  const grades = [];
  Object.entries(Store.getAcademicClasses()).forEach(([classId, academicClass]) => {
    if (!academicClass.studentIds.includes(studentId) || academicClass.periodId !== 'SY2026-Q1') return;
    const result = Store.calculateAcademicGrade(classId, studentId);
    if (result.complete) grades.push(result.grade);
  });
  return grades.length ? Math.round(grades.reduce((sum, grade) => sum + grade, 0) / grades.length) : null;
}

// "Honor Students" — live: ranked by the computed general weighted average.
// Students with an incomplete record have no average and are excluded, which is
// the same rule that keeps them off a real honor roll.
const HONOR_TIERS = [
  { min: 95, label: 'With Highest Honors' },
  { min: 90, label: 'With High Honors' },
  { min: 85, label: 'With Honors' }
];
function buildHonorRows() {
  return Object.entries(Store.academic.students)
    .map(([studentId, student]) => {
      const average = averageFor(studentId);
      if (average === null) return null;
      const tier = HONOR_TIERS.find(candidate => average >= candidate.min);
      if (!tier) return null;
      return { name: student.name, section: student.section || '—', average, honor: tier.label };
    })
    .filter(Boolean)
    .sort((a, b) => b.average - a.average)
    .map((entry, index) => [
      String(index + 1),
      entry.name,
      entry.section,
      entry.average.toFixed(1),
      `<span class="status-pill success">${entry.honor}</span>`
    ]);
}

// "Registrar Documents" — live: Form 137 / 138 / TOR requests from the store.
const DOC_STATUS_TONE = { Issued: 'success', 'For review': 'warning', 'Pending docs': 'attention' };
function buildDocsRows() {
  return Store.documentRequests.map(request => [
    request.id,
    request.student,
    request.document,
    `<span class="status-pill ${DOC_STATUS_TONE[request.status] || 'neutral'}">${request.status}</span>`,
    request.released || '—'
  ]);
}

// Headline counters, derived from the store. The old "128 reports / SY 2025-2026"
// figures were fixed in the markup and could not respond to anything the
// registrar actually did on this screen.
function renderReportingStats() {
  const applicants = Object.values(Store.applicants);
  const followUp = applicants.filter(app => Store.deriveRequirements(app.id) !== 'Complete').length;
  const set = (selector, value) => { const el = document.querySelector(selector); if (el) el.textContent = String(value); };
  set('#st-reports', Store.getReportLog().length);
  set('#st-students', applicants.length);
  set('#st-docreq', Store.documentRequests.length);
  set('#st-followup', followUp);
}

const logPager = Pagination.attach({ controls: '#pager' });
// Paging the log only re-slices what is already rendered — no re-render needed.
logPager.setOnChange(() => paintLogPage());

function paintLogPage() {
  const rows = [...document.querySelectorAll('#log-body tr')];
  const view = logPager.render(rows);
  const count = document.querySelector('#log-count');
  if (count) {
    count.textContent = view.total
      ? `Showing ${view.from}-${view.to} of ${view.total} logged generations`
      : 'No report generations logged yet';
  }
}

function renderLog() {
  renderReportingStats();
  const log = document.querySelector('#log-body');
  const entries = Store.getReportLog();
  log.innerHTML = entries.map((entry, idx) =>
    `<tr>
      <td>${entry.when}</td><td>${entry.title}</td><td>${entry.criteria}</td><td>${entry.by}</td><td>${entry.format}</td>
      <td class="action-cell"><button class="row-action" data-del-log="${idx}" title="Remove log entry">Remove</button></td>
    </tr>`
  ).join('');
  paintLogPage();
}

// Every report type now reads live Store data at generate-time — there are no
// hardcoded preview rows left.
const LIVE_ROW_BUILDERS = {
  missing: buildMissingRows,
  enrollment: buildEnrollmentRows,
  attendance: buildAttendanceRows,
  records: buildRecordsRows,
  honor: buildHonorRows,
  docs: buildDocsRows
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
