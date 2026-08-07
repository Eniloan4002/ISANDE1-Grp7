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

function gateFor(appId) {
  const att = Store.getAttendance(appId);
  if (!att) return { label: 'Not met', tone: 'danger' };
  const present = att.log.filter(d => d.status === 'Present').length;
  const ok = att.req === 'Complete' && present === att.log.length;
  return ok ? { label: 'Both met', tone: 'success' } : { label: 'Not met', tone: 'danger' };
}

function renderList() {
  const applicants = Object.values(Store.applicants);
  tbody.innerHTML = applicants.map(app => {
    const att = Store.getAttendance(app.id);
    const present = att ? att.log.filter(d => d.status === 'Present').length : 0;
    const gate = gateFor(app.id);
    const reqPill = att && att.req === 'Complete'
      ? '<span class="status-pill success">Complete</span>'
      : '<span class="status-pill warning">' + (att && att.req ? att.req : 'Pending') + '</span>';
    return `<tr data-app="${app.id}">
      <td><strong>${app.name}</strong></td>
      <td>${app.id}</td>
      <td>${app.category}</td>
      <td>${reqPill}</td>
      <td><span class="badge ${present === 5 ? 'blue-badge' : 'amber-badge'}">${present} of 5 present</span></td>
      <td><span class="status-pill ${gate.tone}">${gate.label}</span></td>
      <td class="action-cell"><button class="row-action">Monitor</button></td>
    </tr>`;
  }).join('');
}

function updateStats(data) {
  const present = data.log.filter(d => d.status === 'Present').length;
  const absent = data.log.filter(d => d.status === 'Absent').length;
  const pending = data.log.filter(d => d.status === 'Pending').length;
  document.querySelector('#st-days').textContent = data.log.length;
  document.querySelector('#st-present').textContent = present;
  document.querySelector('#st-absent').textContent = absent;
  document.querySelector('#st-pending').textContent = pending;
}

let activeApp = null;

function renderLog(appId) {
  activeApp = appId;
  let data = Store.getAttendance(appId);
  if (!data) {
    // Newly added applicants without a profile get a default log.
    data = { req: 'Pending', log: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'].map(day => ({ day, date: '', status: 'Pending' })) };
  }
  updateStats(data);

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
      <select class="status-select" data-index="${i}" aria-label="${d.day} attendance">
        <option ${d.status === 'Present' ? 'selected' : ''}>Present</option>
        <option ${d.status === 'Absent' ? 'selected' : ''}>Absent</option>
        <option ${d.status === 'Pending' ? 'selected' : ''}>Pending</option>
      </select>
    </div>`).join('');

  recomputeGate(data);
}

function recomputeGate(data) {
  const present = data.log.filter(d => d.status === 'Present').length;
  const attendanceOk = present === data.log.length;
  const reqOk = data.req === 'Complete';

  const setState = (id, ok, label) => {
    const el = document.querySelector('#' + id);
    el.textContent = label;
    el.className = ok ? 'ok' : 'no';
  };
  setState('gate-req', reqOk, reqOk ? 'Complete' : `${data.req}`);
  setState('gate-att', attendanceOk, attendanceOk ? 'Verified' : 'Not verified');
  setState('gate-overall', reqOk && attendanceOk, reqOk && attendanceOk ? 'Both met' : 'Not met');

  document.querySelector('#btn-confirm').classList.toggle('disabled', !(reqOk && attendanceOk));
  document.querySelector('#btn-keep-tracking').disabled = reqOk && attendanceOk;
}

// Pick a student from the monitoring list.
tbody.addEventListener('click', event => {
  const row = event.target.closest('tr');
  if (!row) return;
  [...tbody.querySelectorAll('tr')].forEach(r => r.style.background = '');
  row.style.background = '#eaf3f8';
  renderLog(row.dataset.app);
});

// Editing a day's status persists to the store and re-runs the gate live.
document.querySelector('#att-log').addEventListener('change', event => {
  if (!event.target.classList.contains('status-select')) return;
  const idx = Number(event.target.dataset.index);
  const data = Store.getAttendance(activeApp);
  if (!data) return;
  Store.setAttendance(activeApp, idx, event.target.value);
  updateStats(data);
  recomputeGate(data);

  const present = data.log.filter(d => d.status === 'Present').length;
  showToast('Attendance updated', `${Store.applicants[activeApp].name} — ${event.target.value} for ${data.log[idx].day}. ${present} of ${data.log.length} days present.`);
});

// "Not met — keep tracking" (disabled when the gate is met).
document.querySelector('#btn-keep-tracking').addEventListener('click', () => {
  if (!activeApp) { showToast('Select a student', 'Choose an applicant from the monitoring list first.'); return; }
  const data = Store.getAttendance(activeApp);
  showToast('Still tracking', `${Store.applicants[activeApp].name} — requirements and attendance will keep being monitored until both conditions are met.`);
});

// Verify week 1 (simulate) — marks all five days present for the active
// journey student (or whatever student is selected).
document.querySelector('#btn-sim').addEventListener('click', () => {
  if (!activeApp) { showToast('Select a student', 'Choose an applicant from the monitoring list first.'); return; }
  const j = Store.getJourney();
  if (j && j.active && j.appId === activeApp) {
    Sim.runStage(5);
  } else {
    Store.completeWeekAttendance(activeApp);
  }
  renderLog(activeApp);
  renderList();
  showToast('Week 1 verified', `${Store.applicants[activeApp].name} — 5 of 5 days present. Gate: both met.`);
});

// Prevent navigation when the confirm gate is not met.
document.querySelector('#btn-confirm').addEventListener('click', event => {
  if (!activeApp) {
    event.preventDefault();
    showToast('Select a student', 'Choose an applicant from the monitoring list first.');
    return;
  }
  const data = Store.getAttendance(activeApp);
  const present = data.log.filter(d => d.status === 'Present').length;
  const reqOk = data.req === 'Complete';
  const attOk = present === data.log.length;
  if (!(reqOk && attOk)) {
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
  [...tbody.querySelectorAll('tr')].forEach(r => r.style.background = '');
  row.style.background = '#eaf3f8';
  renderLog(row.dataset.app);
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
