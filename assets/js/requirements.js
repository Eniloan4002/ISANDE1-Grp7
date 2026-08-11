// Requirements Checklist demo interactions — backed by the shared localStorage store.
const tbody = document.querySelector('#req-body');
const resultCount = document.querySelector('#result-count');
const search = document.querySelector('#req-search');
const statusFilter = document.querySelector('#req-status');
const programFilter = document.querySelector('#req-program');

const DOC_LABEL = { submitted: 'Submitted', missing: 'Missing', pending: 'Pending', invalid: 'Invalid' };

function lastUpdated(id) {
  const day = 3 + (Number(id.split('-')[2]) % 4);
  return `Aug ${day}, 2026`;
}

function docPills(docs) {
  const order = ['PSA Birth Certificate', 'Certificate of Completion', 'Form 137', 'Good Moral Certificate'];
  const chips = { submitted: 'done', missing: 'miss', pending: 'pend' };
  return order.map(doc => `<span class="doc-chip ${chips[docs[doc]] || 'pend'}">${doc === 'PSA Birth Certificate' ? 'PSA Birth Cert' : doc === 'Certificate of Completion' ? 'Completion' : doc}</span>`).join('');
}

function renderRows() {
  const applicants = Object.values(Store.applicants);
  tbody.innerHTML = applicants.map(app => {
    const req = Store.getRequirements(app.id) || { docs: {}, contact: '—', email: '' };
    const [label, tone] = overallStatus(app.id);
    return `<tr data-status="${label.toLowerCase()}" data-program="${app.program}" data-name="${app.name}" data-app="${app.id}" data-category="${app.category}">
      <td>
        <div class="student-cell">
          <div><strong>${app.name}</strong><small>LRN ${app.lrn || '—'}</small></div>
        </div>
      </td>
      <td>${app.id}</td>
      <td>${app.category}</td>
      <td>${docPills(req.docs || {})}</td>
      <td><span class="status-pill ${tone}">${label}</span></td>
      <td>${lastUpdated(app.id)}</td>
      <td class="action-cell"><button class="row-action">View</button></td>
    </tr>`;
  }).join('');
  filterList();
}

function filterList() {
  let visible = 0;
  const term = search.value.toLowerCase();
  [...tbody.querySelectorAll('tr')].forEach(row => {
    const show = row.textContent.toLowerCase().includes(term) &&
      (statusFilter.value === 'all' || row.dataset.status === statusFilter.value) &&
      (programFilter.value === 'all' || row.dataset.program === programFilter.value);
    row.hidden = !show;
    if (show) visible++;
  });
  resultCount.textContent = `Showing ${visible} of ${Object.keys(Store.applicants).length} students`;
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

// Per-student document state (status per requirement) — stored centrally.
const DOCS = ['PSA Birth Certificate', 'Form 137', 'Certificate of Completion', 'Good Moral Certificate'];
const STATUS_LABEL = { submitted: 'Submitted', missing: 'Missing', pending: 'Pending', invalid: 'Invalid' };
const STATUS_PILL = { submitted: 'success', missing: 'danger', pending: 'warning', invalid: 'danger' };

function stateFor(id) { return Store.getRequirements(id) || { docs: {}, contact: '—', email: '' }; }

// ---- Contact Details popup: student's own info + parent/guardian info,
// so the registrar can see who's actually reachable before following up. ----
const contactModal = document.querySelector('#contact-modal');
function openContactModal() { contactModal.setAttribute('aria-hidden', 'false'); contactModal.classList.add('open'); }
function closeContactModal() { contactModal.setAttribute('aria-hidden', 'true'); contactModal.classList.remove('open'); }

function showContactDetails(appId) {
  const app = Store.applicants[appId];
  if (!app) return;
  document.querySelector('#cd-student-meta').innerHTML =
    `<span><strong>Name:</strong> ${app.name}</span><span><strong>Contact No.:</strong> ${app.contact || '—'}</span><span><strong>Email:</strong> ${app.email || '—'}</span>`;
  document.querySelector('#cd-guardian-meta').innerHTML =
    `<span><strong>Father:</strong> ${app.father || '—'}</span><span><strong>Mother:</strong> ${app.mother || '—'}</span><span><strong>Guardian:</strong> ${app.guardian || '—'}</span><span><strong>Guardian Contact No.:</strong> ${app.guardianContact || '—'}</span>`;
  document.querySelector('#cd-call-student').href = app.contact ? `tel:${app.contact}` : '#';
  document.querySelector('#cd-call-guardian').href = app.guardianContact ? `tel:${app.guardianContact}` : '#';
  openContactModal();
}
document.querySelectorAll('.cd-modal-close').forEach(button => button.addEventListener('click', closeContactModal));
contactModal.addEventListener('click', event => { if (event.target === contactModal) closeContactModal(); });
document.querySelector('#btn-confirm-followup').addEventListener('click', () => {
  showToast('Follow-up notice sent', `Reminder sent regarding ${Store.applicants[currentApp]?.name || currentApp}.`);
  closeContactModal();
});

function overallStatus(appId) {
  const docs = stateFor(appId).docs;
  const values = Object.values(docs);
  if (!values.length) return ['Pending', 'warning'];
  if (values.every(v => v === 'submitted')) return ['Complete', 'success'];
  if (values.some(v => v === 'missing' || v === 'invalid')) return ['Incomplete', 'danger'];
  return ['Pending', 'warning'];
}

const modal = document.querySelector('#req-modal');
let currentApp = null;
let currentRow = null;

function openModal() {
  modal.setAttribute('aria-hidden', 'false');
  modal.classList.add('open');
}

function closeModal() {
  modal.setAttribute('aria-hidden', 'true');
  modal.classList.remove('open');
}

function showRequirements(row) {
  currentRow = row;
  currentApp = row.dataset.app;
  const student = stateFor(currentApp);
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
      <button type="button" class="followup" data-followup>Follow up</button></div>`;
  }).join('');

  renderOverall();
  renderAudit(Store.getAudits(currentApp));
  openModal();
}

function renderOverall() {
  const [label, tone] = overallStatus(currentApp);
  const docs = stateFor(currentApp).docs;
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

// Status updates persist to the store, re-compute the overall status, and append to the audit trail.
document.querySelector('#doc-list').addEventListener('change', event => {
  if (event.target.matches('.status-select')) {
    const doc = event.target.dataset.doc;
    const value = event.target.value;
    Store.setRequirement(currentApp, doc, value);
    event.target.previousElementSibling.className = `status-pill ${STATUS_PILL[value]}`;
    event.target.previousElementSibling.textContent = STATUS_LABEL[value];
    renderOverall();
    renderAudit(Store.getAudits(currentApp));
    showToast('Requirement updated', `${doc} set to ${STATUS_LABEL[value]}.`);
  }
});

// Follow-up: show contact details (student + parent/guardian) so the
// registrar can see who's actually reachable before calling or emailing.
document.querySelector('#doc-list').addEventListener('click', event => {
  const link = event.target.closest('[data-followup]');
  if (link) showContactDetails(currentApp);
});

document.querySelector('#btn-send-followup').addEventListener('click', () => {
  showContactDetails(currentApp);
});

// Keep the list in sync with the shared store (e.g. after a removal elsewhere).
document.addEventListener('store:change', renderRows);
renderRows();

// Deep-link support: when arriving from the Enrollment Queue (?app=<ID>),
// highlight that student and open their requirements checklist automatically.
function openById(id) {
  const row = [...tbody.querySelectorAll('tr')].find(r => r.dataset.app === id);
  if (!row) return false;
  [...tbody.querySelectorAll('tr')].forEach(r => r.style.background = '');
  row.style.background = '#eaf3f8';
  showRequirements(row);
  showToast('Application opened', `${row.dataset.name} (${id}) — requirements checklist loaded from the Enrollment Queue.`);
  return true;
}
const deepLink = new URLSearchParams(location.search).get('app');
if (deepLink && !openById(deepLink)) {
  showToast('Application not found', `${deepLink} is not in the queue — showing the full list.`);
}
