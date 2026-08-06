// Requirements Checklist demo interactions. No server or persistent data is used.
const rows = [...document.querySelectorAll('#req-body tr')];
const resultCount = document.querySelector('#result-count');
const search = document.querySelector('#req-search');
const statusFilter = document.querySelector('#req-status');
const programFilter = document.querySelector('#req-program');

function filterList() {
  let visible = 0;
  const term = search.value.toLowerCase();
  rows.forEach(row => {
    const show = row.textContent.toLowerCase().includes(term) &&
      (statusFilter.value === 'all' || row.dataset.status === statusFilter.value) &&
      (programFilter.value === 'all' || row.dataset.program === programFilter.value);
    row.hidden = !show;
    if (show) visible++;
  });
  resultCount.textContent = `Showing ${visible} of 27 students`;
}
[search, statusFilter, programFilter].forEach(item => item.addEventListener('input', filterList));

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

// Per-student document state (status per requirement).
const DOCS = ['PSA Birth Certificate', 'Form 137', 'Certificate of Completion', 'Good Moral Certificate'];
const STATUS_LABEL = { submitted: 'Submitted', missing: 'Missing', pending: 'Pending', invalid: 'Invalid' };
const STATUS_PILL = { submitted: 'success', missing: 'danger', pending: 'warning', invalid: 'danger' };
const state = {
  'APP-2026-0149': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'pending', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'missing' }, contact: '0918 555 0149', email: 'maria.santos@email.com' },
  'APP-2026-0150': { docs: { 'PSA Birth Certificate': 'pending', 'Form 137': 'pending', 'Certificate of Completion': 'pending', 'Good Moral Certificate': 'pending' }, contact: '0919 555 0150', email: 'pedro.reyes@email.com' },
  'APP-2026-0151': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' }, contact: '0920 555 0151', email: 'angela.lim@email.com' },
  'APP-2026-0148': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'pending', 'Certificate of Completion': 'missing', 'Good Moral Certificate': 'missing' }, contact: '0917 555 0148', email: 'juan.delacruz@email.com' },
  'APP-2026-0152': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' }, contact: '0921 555 0152', email: 'carla.abad@email.com' }
};
const AUDITS = {
  'APP-2026-0149': ['Form 137 marked Pending by R. Santos — Aug 5, 2026 09:14 AM', 'PSA Birth Certificate marked Submitted by R. Santos — Aug 3, 2026 02:41 PM', 'Application created by R. Santos — Aug 2, 2026 10:05 AM'],
  'APP-2026-0150': ['Good Moral Certificate still Pending — last checked by R. Santos — Aug 4, 2026 11:22 AM', 'Application created by R. Santos — Aug 2, 2026 01:18 PM'],
  'APP-2026-0151': ['All documents verified by R. Santos — Aug 6, 2026 08:37 AM', 'Form 137 marked Submitted by R. Santos — Aug 5, 2026 03:02 PM'],
  'APP-2026-0148': ['Certificate of Completion marked Missing by R. Santos — Aug 3, 2026 04:19 PM', 'PSA Birth Certificate marked Submitted by R. Santos — Aug 3, 2026 09:58 AM'],
  'APP-2026-0152': ['All documents verified by R. Santos — Aug 6, 2026 10:44 AM', 'Application created by R. Santos — Aug 3, 2026 02:30 PM']
};

function overallStatus(appId) {
  const docs = state[appId].docs;
  const values = Object.values(docs);
  if (values.every(v => v === 'submitted')) return ['Complete', 'success'];
  if (values.some(v => v === 'missing' || v === 'invalid')) return ['Incomplete', 'danger'];
  return ['Pending', 'warning'];
}

const modal = document.querySelector('#req-modal');
let currentApp = null;

function openModal() {
  modal.setAttribute('aria-hidden', 'false');
  modal.classList.add('open');
}

function closeModal() {
  modal.setAttribute('aria-hidden', 'true');
  modal.classList.remove('open');
}

function showRequirements(row) {
  currentApp = row.dataset.app;
  const student = state[currentApp];
  const meta = document.querySelector('#req-meta');
  meta.innerHTML = `<span><strong>Student:</strong> ${row.dataset.name}</span><span><strong>App ID:</strong> ${currentApp}</span><span><strong>Category:</strong> ${row.dataset.category}</span><span><strong>Contact:</strong> ${student.contact}</span>`;

  const list = document.querySelector('#doc-list');
  list.innerHTML = DOCS.map(doc => {
    const value = student.docs[doc];
    return `<div class="doc-row"><span class="name">${doc}</span>
      <span class="status-pill ${STATUS_PILL[value]}">${STATUS_LABEL[value]}</span>
      <select class="status-select" data-doc="${doc}" aria-label="${doc} status">
        ${Object.entries(STATUS_LABEL).map(([k, v]) => `<option value="${k}" ${k === value ? 'selected' : ''}>${v}</option>`).join('')}
      </select>
      <a class="followup" href="tel:${student.contact}" data-followup>Follow up</a></div>`;
  }).join('');

  renderOverall();
  renderAudit(AUDITS[currentApp]);
  openModal();
}

function renderOverall() {
  const [label, tone] = overallStatus(currentApp);
  const docs = state[currentApp].docs;
  const count = Object.values(docs).filter(v => v === 'submitted').length;
  const el = document.querySelector('#req-overall');
  el.innerHTML = `<span class="status-pill ${tone}">${label}</span> ${count} of 4 submitted`;
}

function renderAudit(entries) {
  document.querySelector('#audit-list').innerHTML = entries.map(entry => {
    const when = entry.match(/(\w{3} \d{1,2}, \d{4} \d{1,2}:\d{2} (AM|PM))$/);
    const text = when ? entry.slice(0, when.index).trim() : entry;
    return `<div class="audit-item"><span class="dot"></span><span>${text}</span><span class="when">${when ? when[1] : ''}</span></div>`;
  }).join('');
}

document.querySelector('#req-body').addEventListener('click', event => {
  const row = event.target.closest('tr');
  if (row) showRequirements(row);
});
document.querySelectorAll('.modal-close').forEach(button => button.addEventListener('click', closeModal));
modal.addEventListener('click', event => { if (event.target === modal) closeModal(); });

// Status updates re-compute the overall status and append to the audit trail.
document.querySelector('#doc-list').addEventListener('change', event => {
  if (event.target.matches('.status-select')) {
    const doc = event.target.dataset.doc;
    const value = event.target.value;
    state[currentApp].docs[doc] = value;
    event.target.previousElementSibling.className = `status-pill ${STATUS_PILL[value]}`;
    event.target.previousElementSibling.textContent = STATUS_LABEL[value];
    renderOverall();
    const now = 'Aug 7, 2026 10:42 AM';
    AUDITS[currentApp].unshift(`${doc} marked ${STATUS_LABEL[value]} by R. Santos — ${now}`);
    renderAudit(AUDITS[currentApp]);
    showToast('Requirement updated', `${doc} set to ${STATUS_LABEL[value]}.`);
  }
});

// Follow-up: registrar contacts the student or parent via phone or email.
document.querySelector('#doc-list').addEventListener('click', event => {
  const link = event.target.closest('[data-followup]');
  if (link) showToast('Follow-up sent', `Contacting ${currentApp} via SMS / call.`);
});

document.querySelector('#btn-send-followup').addEventListener('click', () => {
  showToast('Follow-up notice sent', `Reminder emailed to ${state[currentApp].email}`);
});
