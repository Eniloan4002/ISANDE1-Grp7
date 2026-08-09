// First-Week Attendance Monitoring demo interactions — backed by the shared localStorage store.
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

const tbody = document.querySelector('#att-body');
const confirmLink = document.querySelector('#btn-confirm');
const searchInput = document.querySelector('#att-search');
const statusFilterEl = document.querySelector('#att-status');
const resultCountEl = document.querySelector('#att-result-count');
// Single source of truth for the "requirements complete AND attendance verified"
// gate — used by the master list, the detail panel, and the confirm handler.
function gateStatus(appId) {
  const att = Store.getAttendance(appId);
  if (!att) {
    return { key: 'not-started', label: 'Not started', tone: 'neutral', present: 0, absent: 0, total: 5, reqOk: false, attendanceOk: false, reqLabel: 'Pending' };
  }
  const total = att.log.length;
  const present = att.log.filter(d => d.status === 'Present').length;
  const absent = att.log.filter(d => d.status === 'Absent').length;
  const reqOk = att.req === 'Complete';
  const attendanceOk = Store.attendanceVerified(appId);

  let key, label, tone;
  if (reqOk && attendanceOk) {
    key = 'ready'; label = 'Ready to confirm'; tone = 'success';
  } else if (!reqOk && present === 0 && absent === 0) {
    key = 'not-started'; label = 'Not started'; tone = 'neutral';
  } else if (!reqOk) {
    key = 'docs'; label = 'Needs documents'; tone = 'warning';
  } else {
    key = 'attendance'; label = 'Needs attendance'; tone = absent > 0 ? 'danger' : 'warning';
  }
  return { key, label, tone, present, absent, total, reqOk, attendanceOk, reqLabel: att.req };
}

function highlightActiveRow() {
  [...tbody.querySelectorAll('tr')].forEach(r => r.style.background = (r.dataset.app === activeApp) ? '#eaf3f8' : '');
}

function renderList() {
  const applicants = Object.values(Store.applicants);
  tbody.innerHTML = applicants.map(app => {
    const gate = gateStatus(app.id);
    const toneClass = gate.tone === 'neutral' ? 'status-pill' : `status-pill ${gate.tone}`;
    return `<tr data-app="${app.id}" data-status="${gate.key}">
      <td><strong>${app.name}</strong></td>
      <td>${app.id}</td>
      <td>${app.category}</td>
      <td><span class="${toneClass}">${gate.label}</span> <small>${gate.present}/${gate.total} present</small></td>
      <td class="action-cell"><button class="row-action">Monitor</button></td>
    </tr>`;
  }).join('');
  highlightActiveRow();
  filterList();
  renderStats(applicants);
}

function filterList() {
  let visible = 0;
  const term = searchInput.value.toLowerCase();
  [...tbody.querySelectorAll('tr')].forEach(row => {
    const show = row.textContent.toLowerCase().includes(term) &&
      (statusFilterEl.value === 'all' || row.dataset.status === statusFilterEl.value);
    row.hidden = !show;
    if (show) visible++;
  });
  resultCountEl.textContent = `Showing ${visible} of ${Object.keys(Store.applicants).length} applicants under first-week monitoring`;
}
[searchInput, statusFilterEl].forEach(el => el.addEventListener('input', filterList));

// Cohort-level stat cards (not the currently-selected student).
function renderStats(applicants) {
  const list = applicants || Object.values(Store.applicants);
  const counts = { ready: 0, docs: 0, attendance: 0 };
  list.forEach(app => {
    const gate = gateStatus(app.id);
    if (gate.key === 'ready') counts.ready++;
    else if (gate.key === 'attendance') counts.attendance++;
    else counts.docs++; // 'docs' and 'not-started' both mean requirements aren't in yet.
  });
  document.querySelector('#st-total').textContent = list.length;
  document.querySelector('#st-ready').textContent = counts.ready;
  document.querySelector('#st-docs').textContent = counts.docs;
  document.querySelector('#st-attn').textContent = counts.attendance;
}

// Per-student day tally, shown in the detail panel's own header (not the cohort cards).
function renderDetailTally(data) {
  const present = data.log.filter(d => d.status === 'Present').length;
  const absent = data.log.filter(d => d.status === 'Absent').length;
  const pending = data.log.filter(d => d.status === 'Pending').length;
  document.querySelector('#log-tally').textContent = `${present} present · ${absent} absent · ${pending} pending (of ${data.log.length} days)`;
}

const STATUS_CYCLE = { Present: 'Absent', Absent: 'Pending', Pending: 'Present' };
const STATUS_TONE = { Present: 'success', Absent: 'danger', Pending: 'warning' };

let activeApp = null;

function renderLog(appId) {
  activeApp = appId;
  confirmLink.href = `academic-records.html?app=${encodeURIComponent(appId)}`;
  let data = Store.getAttendance(appId);
  if (!data) {
    // Newly added applicants without a profile get a default log.
    data = { req: 'Pending', log: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'].map(day => ({ day, date: '', status: 'Pending' })) };
  }
  renderDetailTally(data);

  const app = Store.applicants[appId];
  document.querySelector('#selected-name').textContent = app ? app.name : '—';
  document.querySelector('#selected-id').textContent = `· ${appId}`;
  document.querySelector('#selected-banner').classList.add('show');
  document.querySelector('#log-hint').textContent = 'Grade 11 • Week 1 · Aug 3–7, 2026';

  const container = document.querySelector('#att-log');
  container.innerHTML = data.log.map((d, i) => `
    <div class="att-day-row">
      <span class="day">${d.day}</span>
      <span class="date">${d.date || '—'}</span>
      <button type="button" class="status-pill ${STATUS_TONE[d.status]} day-chip" data-index="${i}" aria-label="${d.day} attendance: ${d.status}. Click to change.">${d.status}</button>
    </div>`).join('');

  recomputeGate(appId);
}

function recomputeGate(appId) {
  const gate = gateStatus(appId);

  const setState = (id, ok, label) => {
    const el = document.querySelector('#' + id);
    el.textContent = label;
    el.className = ok ? 'ok' : 'no';
  };
  setState('gate-req', gate.reqOk, gate.reqOk ? 'Complete' : gate.reqLabel);
  setState('gate-att', gate.attendanceOk, gate.attendanceOk ? 'Verified' : 'Not verified');
  setState('gate-overall', gate.key === 'ready', gate.key === 'ready' ? 'Both met' : 'Not met');

  document.querySelector('#btn-confirm').classList.toggle('disabled', gate.key !== 'ready');
  document.querySelector('#btn-keep-tracking').disabled = gate.key === 'ready';
}

// Pick a student from the monitoring list.
tbody.addEventListener('click', event => {
  const row = event.target.closest('tr');
  if (!row) return;
  renderLog(row.dataset.app);
  highlightActiveRow();
});

// Clicking a day chip cycles Present -> Absent -> Pending -> Present, persists to the
// store, and refreshes BOTH the detail panel and the master list (fixes the staleness
// bug where the master row used to go stale until some other event refreshed it).
document.querySelector('#att-log').addEventListener('click', event => {
  const chip = event.target.closest('.day-chip');
  if (!chip) return;
  const idx = Number(chip.dataset.index);
  const data = Store.getAttendance(activeApp);
  if (!data) return;
  const day = data.log[idx].day;
  const next = STATUS_CYCLE[data.log[idx].status] || 'Present';
  Store.setAttendance(activeApp, idx, next);

  renderLog(activeApp);
  renderList();

  const gate = gateStatus(activeApp);
  showToast('Attendance updated', `${Store.applicants[activeApp].name} — ${next} for ${day}. ${gate.present} of ${gate.total} days present.`);
});

// "Not met — keep tracking" (disabled when the gate is met).
document.querySelector('#btn-keep-tracking').addEventListener('click', () => {
  if (!activeApp) { showToast('Select a student', 'Choose an applicant from the monitoring list first.'); return; }
  showToast('Still tracking', `${Store.applicants[activeApp].name} — requirements and attendance will keep being monitored until both conditions are met.`);
});

// Prevent navigation when the confirm gate is not met.
document.querySelector('#btn-confirm').addEventListener('click', event => {
  if (!activeApp) {
    event.preventDefault();
    showToast('Select a student', 'Choose an applicant from the monitoring list first.');
    return;
  }
  const gate = gateStatus(activeApp);
  if (gate.key !== 'ready') {
    event.preventDefault();
    showToast('Gate not met', `${Store.applicants[activeApp].name} — complete requirements and attendance first, or keep tracking.`);
  }
});

// Keep the list in sync with the shared store.
document.addEventListener('store:change', () => { renderList(); });
renderList();

function selectById(id) {
  const row = [...tbody.querySelectorAll('tr')].find(r => r.dataset.app === id);
  if (!row) return false;
  renderLog(row.dataset.app);
  highlightActiveRow();
  return true;
}

// Deep-link support: arriving from the Enrollment Queue (?app=<ID>) selects that
// student; otherwise default to the official screen example (APP-2026-0150, Reyes, Pedro).
const deepLink = new URLSearchParams(location.search).get('app');
if (deepLink && selectById(deepLink)) {
  const app = Store.applicants[deepLink];
  showToast('Application opened', `${app ? app.name : deepLink} (${deepLink}) — attendance log loaded from the Enrollment Queue.`);
} else {
  // No deep link (or unknown ID) — default to the official screen example.
  const firstRow = tbody.querySelector('tr');
  if (firstRow) selectById(firstRow.dataset.app);
}
