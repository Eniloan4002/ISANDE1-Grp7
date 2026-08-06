// Reporting & Monitoring demo interactions. No server or persistent data is used.
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
    rows: [
      ['Main — Iligan', '6', '3', '19', '28'],
      ['Kapatagan', '3', '2', '8', '13'],
      ['Bugo — CDO', '3', '2', '7', '12'],
      ['<strong>Total</strong>', '<strong>12</strong>', '<strong>7</strong>', '<strong>34</strong>', '<strong>53</strong>']
    ]
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
    title: 'Attendance', desc: 'First-week attendance rates by section.',
    head: ['Section', 'Students', 'Week 1 present', 'Rate', 'Status'],
    rows: [
      ['STEM-A', '40', '38', '95%', '<span class="status-pill success">Good</span>'],
      ['STEM-B', '36', '35', '97%', '<span class="status-pill success">Good</span>'],
      ['HUMSS-1', '38', '31', '82%', '<span class="status-pill warning">Monitor</span>'],
      ['ABM-1', '35', '34', '97%', '<span class="status-pill success">Good</span>']
    ]
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

function generate() {
  const type = typeButtons.find(button => button.classList.contains('active')).dataset.type;
  const report = REPORTS[type];
  previewTable.querySelector('thead').innerHTML = `<tr>${report.head.map(h => `<th>${h}</th>`).join('')}</tr>`;
  previewTable.querySelector('tbody').innerHTML = report.rows.map(row => `<tr>${row.map(cell => `<td>${cell}</td>`).join('')}</tr>`).join('');
  const now = 'Aug 7, 2026 10:42 AM';
  previewMeta.innerHTML = `<span><strong>Report:</strong> ${report.title}</span><span><strong>Generated:</strong> ${now}</span><span><strong>Criteria:</strong> ${paramSummary()}</span><span><strong>By:</strong> R. Santos (Registrar Staff)</span>`;
  preview.classList.add('show');
  preview.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

  const log = document.querySelector('#log-body');
  log.insertAdjacentHTML('afterbegin', `<tr><td>${now}</td><td>${report.title}</td><td>${paramSummary()}</td><td>R. Santos</td><td>PDF</td></tr>`);
  showToast('Report generated', `${report.title} compiled and logged for audit.`);
}

document.querySelector('#btn-generate').addEventListener('click', generate);
document.querySelector('#btn-print').addEventListener('click', () => showToast('Printing', 'The report is being sent to the printer.'));
document.querySelectorAll('[data-export]').forEach(button => button.addEventListener('click', () => showToast('Export prepared', 'CSV file downloaded — demonstration prototype.')));
document.querySelectorAll('[data-export-pdf]').forEach(button => button.addEventListener('click', () => showToast('Export prepared', 'PDF file downloaded — demonstration prototype.')));
