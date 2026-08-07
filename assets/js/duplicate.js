// Duplicate Records demo interactions — backed by the shared localStorage store.
const tbody = document.querySelector('#dup-body');
const resultCount = document.querySelector('#result-count');
const search = document.querySelector('#dup-search');
const statusFilter = document.querySelector('#dup-status');

function renderPairs() {
  const pairs = Object.entries(Store.pairs).map(([app, pair]) => {
    const status = Store.getPairStatus(app);
    const existingId = pair.existing.sub.match(/STU-[\d-]+/);
    const meter = Number(pair.meter.match(/(\d+)%/)?.[1] || 0);
    const meterTone = meter >= 90 ? 'danger' : meter >= 80 ? 'warning' : 'success';
    const badge = status === 'merge' ? ['Merged', 'blue-badge'] : status === 'reject' ? ['Rejected', 'neutral-badge'] : ['For review', 'amber-badge'];
    const existingName = pair.existing.fields[0][1];
    const incomingName = pair.incoming.fields[0][1];
    return `<tr data-status="${status ? 'resolved' : 'open'}" data-name="${incomingName}" data-app="${app}">
      <td><strong>${incomingName}</strong></td>
      <td>${app}</td>
      <td><strong>${existingName}</strong></td>
      <td>${existingId ? existingId[0] : '—'}</td>
      <td><span class="status-pill ${meterTone}">${meter}%</span></td>
      <td><span class="badge ${badge[1]}">${badge[0]}</span></td>
      <td class="action-cell"><button class="row-action">${status ? 'View' : 'Review'}</button></td>
    </tr>`;
  }).join('');
  tbody.innerHTML = pairs;
  filterList();
}

function filterList() {
  let visible = 0;
  const term = search.value.toLowerCase();
  [...tbody.querySelectorAll('tr')].forEach(row => {
    const show = row.textContent.toLowerCase().includes(term) &&
      (statusFilter.value === 'all' || row.dataset.status === statusFilter.value);
    row.hidden = !show;
    if (show) visible++;
  });
  resultCount.textContent = `Showing ${visible} of ${Object.keys(Store.pairs).length} duplicate pairs`;
}
[search, statusFilter].forEach(item => item.addEventListener('input', filterList));

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

// Comparison data comes from the shared store.
const panel = document.querySelector('#compare-panel');
const note = document.querySelector('#resolve-note');
const mergeBtn = document.querySelector('#btn-merge');
const rejectBtn = document.querySelector('#btn-reject');
let currentPair = null;

function renderFields(container, data) {
  container.innerHTML = data.fields.map(([k, v, match]) =>
    `<div class="field-row ${match ? 'match' : ''}"><span class="k">${k}</span><span class="v">${v}</span>${match ? '<span class="tag-match">Match</span>' : ''}</div>`
  ).join('');
}

function openPair(row) {
  currentPair = row.dataset.app;
  const pair = Store.getPair(currentPair);
  const status = Store.getPairStatus(currentPair);
  document.querySelector('#match-meter').textContent = pair.meter;
  document.querySelector('#cmp-app').textContent = pair.incoming.sub;
  document.querySelector('#cmp-existing').textContent = pair.existing.sub;
  renderFields(document.querySelector('#cmp-incoming'), pair.incoming);
  renderFields(document.querySelector('#cmp-existing-fields'), pair.existing);

  if (status) {
    note.className = 'resolve-note show';
    note.innerHTML = `Resolution: <strong>${status === 'merge' ? 'Merged' : 'Rejected'}</strong> — ${status === 'merge' ? 'application data was merged into the existing student record.' : 'records are distinct; the application continues as a new student record.'} Logged in the audit trail.`;
    mergeBtn.disabled = true;
    rejectBtn.disabled = true;
  } else {
    note.className = 'resolve-note';
    note.textContent = '';
    mergeBtn.disabled = false;
    rejectBtn.disabled = false;
  }
  panel.classList.add('show');
}

document.querySelector('#dup-body').addEventListener('click', event => {
  const row = event.target.closest('tr');
  if (row) {
    [...tbody.querySelectorAll('tr')].forEach(r => r.style.background = '');
    row.style.background = '#eaf3f8';
    openPair(row);
  }
});

function resolve(action) {
  Store.resolvePair(currentPair, action);
  const row = [...tbody.querySelectorAll('tr')].find(r => r.dataset.app === currentPair);
  if (row) {
    row.dataset.status = 'resolved';
    const badge = row.querySelector('td:nth-child(6) .badge');
    badge.className = action === 'merge' ? 'badge blue-badge' : 'badge neutral-badge';
    badge.textContent = action === 'merge' ? 'Merged' : 'Rejected';
  }
  note.className = 'resolve-note show';
  note.innerHTML = action === 'merge'
    ? `Resolution: <strong>Merged</strong> — application data merged into the existing record. Student ID preserved. Logged in the audit trail.`
    : `Resolution: <strong>Rejected</strong> — records are distinct. The application continues as a new student record.`;
  mergeBtn.disabled = true;
  rejectBtn.disabled = true;
  showToast(action === 'merge' ? 'Records merged' : 'Duplicate rejected', `${currentPair} resolved and logged.`);
}

mergeBtn.addEventListener('click', () => resolve('merge'));
rejectBtn.addEventListener('click', () => resolve('reject'));

// Resolve (simulate) — runs the Duplicate Review stage for the active
// journey student (or resolves the open pair as a merge for any other pair).
document.querySelector('#btn-sim').addEventListener('click', () => {
  if (!currentPair) { showToast('Select a pair', 'Choose a flagged pair first.'); return; }
  const j = Store.getJourney();
  if (j && j.active && j.appId === currentPair) {
    Sim.runStage(3);
  } else {
    resolve('merge');
    return;
  }
  const row = [...tbody.querySelectorAll('tr')].find(r => r.dataset.app === currentPair);
  if (row) openPair(row);
  showToast('Duplicate resolved', `${currentPair} — merged (simulated). Logged in the audit trail.`);
});

document.addEventListener('store:change', renderPairs);
renderPairs();

// Deep-link support: when arriving from the Enrollment Queue (?app=<ID>), open
// that flagged pair's side-by-side comparison automatically.
function openById(id) {
  const row = [...tbody.querySelectorAll('tr')].find(r => r.dataset.app === id);
  if (!row || !Store.getPair(id)) return false;
  [...tbody.querySelectorAll('tr')].forEach(r => r.style.background = '');
  row.style.background = '#eaf3f8';
  openPair(row);
  showToast('Duplicate review opened', `${row.dataset.name} (${id}) — side-by-side comparison loaded from the Enrollment Queue.`);
  panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
  return true;
}
const deepLink = new URLSearchParams(location.search).get('app');
if (deepLink && !openById(deepLink)) {
  showToast('No duplicate pair', `${deepLink} has no flagged duplicate — showing all flagged pairs.`);
}
