// Duplicate Records demo interactions. No server or persistent data is used.
const rows = [...document.querySelectorAll('#dup-body tr')];
const resultCount = document.querySelector('#result-count');
const search = document.querySelector('#dup-search');
const statusFilter = document.querySelector('#dup-status');

function filterList() {
  let visible = 0;
  const term = search.value.toLowerCase();
  rows.forEach(row => {
    const show = row.textContent.toLowerCase().includes(term) &&
      (statusFilter.value === 'all' || row.dataset.status === statusFilter.value);
    row.hidden = !show;
    if (show) visible++;
  });
  resultCount.textContent = `Showing ${visible} of 7 duplicate pairs`;
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

// Comparison data per pair. Field rows flagged `match: true` are highlighted.
const PAIRS = {
  'APP-2026-0149': {
    meter: '92% match confidence',
    incoming: { sub: 'Transferee · APP-2026-0149 · Submitted Aug 2, 2026', fields: [
      ['Full name', 'Santos, Maria A.', true], ['Date of birth', '07/22/2008', true],
      ['Sex', 'Female', true], ['LRN', '108517230184', true],
      ['Previous school', 'Kapatagan National HS', true],
      ['Guardian contact', '0918 555 0149', false], ['Status', 'Pre-Enlisted', false]
    ] },
    existing: { sub: 'Student STU-2024-0771 · Record since 2024', fields: [
      ['Full name', 'Santos, Maria Ann', true], ['Date of birth', '07/22/2008', true],
      ['Sex', 'Female', true], ['LRN', '108517230184', true],
      ['Previous school', 'Kapatagan National HS', true],
      ['Guardian contact', '0917 555 0991', false], ['Status', 'Inactive (Withdrawn)', false]
    ] }
  },
  'APP-2026-0153': {
    meter: '78% match confidence',
    incoming: { sub: 'New · APP-2026-0153 · Submitted Aug 4, 2026', fields: [
      ['Full name', 'Bautista, Carlo', false], ['Date of birth', '03/09/2009', true],
      ['Sex', 'Male', true], ['LRN', '121456789012', false],
      ['Previous school', 'Iligan City NHS', true],
      ['Guardian contact', '0922 555 0153', true], ['Status', 'Pre-Enlisted', false]
    ] },
    existing: { sub: 'Student STU-2023-1210 · Record since 2023', fields: [
      ['Full name', 'Bautista, Carlos D.', false], ['Date of birth', '03/09/2009', true],
      ['Sex', 'Male', true], ['LRN', '120987654321', false],
      ['Previous school', 'Iligan City NHS', true],
      ['Guardian contact', '0922 555 0153', true], ['Status', 'Active', false]
    ] }
  },
  'APP-2026-0140': {
    meter: '81% match confidence',
    incoming: { sub: 'Returnee · APP-2026-0140 · Submitted Jul 28, 2026', fields: [
      ['Full name', 'Garcia, Ana', true], ['Date of birth', '01/15/2008', true],
      ['Sex', 'Female', true], ['LRN', '112377981523', true],
      ['Previous school', 'St. Michael Academy', false],
      ['Guardian contact', '0923 555 0140', false], ['Status', 'Pending Requirements', false]
    ] },
    existing: { sub: 'Student STU-2022-0503 · Record since 2022', fields: [
      ['Full name', 'Garcia, Ana Marie', true], ['Date of birth', '01/15/2008', true],
      ['Sex', 'Female', true], ['LRN', '112377981523', true],
      ['Previous school', 'Tambo Central School', false],
      ['Guardian contact', '0923 555 0140', false], ['Status', 'Enrolled', false]
    ] }
  }
};

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
  const pair = PAIRS[currentPair];
  const resolved = row.dataset.status === 'resolved';
  document.querySelector('#match-meter').textContent = pair.meter;
  document.querySelector('#cmp-app').textContent = pair.incoming.sub;
  document.querySelector('#cmp-existing').textContent = pair.existing.sub;
  renderFields(document.querySelector('#cmp-incoming'), pair.incoming);
  renderFields(document.querySelector('#cmp-existing-fields'), pair.existing);

  if (resolved) {
    note.className = 'resolve-note show';
    note.innerHTML = 'Resolution: <strong>Merged</strong> — application data was merged into the existing student record. Logged in the audit trail on Jul 29, 2026.';
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
    rows.forEach(r => r.style.background = '');
    row.style.background = '#eaf3f8';
    openPair(row);
  }
});

function resolve(action) {
  const row = rows.find(r => r.dataset.app === currentPair);
  row.dataset.status = 'resolved';
  const badge = row.querySelector('td:nth-child(6) .badge');
  badge.className = action === 'merge' ? 'badge blue-badge' : 'badge neutral-badge';
  badge.textContent = action === 'merge' ? 'Merged' : 'Rejected';
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
