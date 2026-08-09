// Evaluate Applications demo interactions — backed by the shared localStorage store.
const tbody = document.querySelector('#app-body');
const resultCount = document.querySelector('#result-count');
const search = document.querySelector('#app-search');
const status = document.querySelector('#app-status');

const EVAL_BADGE = {
  review: ['Pending review', 'neutral-badge'],
  ready: ['Ready to encode', 'blue-badge'],
  flag: ['For review', 'neutral-badge']
};

function renderList() {
  const applicants = Object.values(Store.applicants);
  tbody.innerHTML = applicants.map(app => {
    const [label, tone] = EVAL_BADGE[app.evalStatus] || EVAL_BADGE.ready;
    return `<tr data-app="${app.id}" data-status="${app.evalStatus}">
      <td><strong>${app.name}</strong></td>
      <td>${app.id}</td>
      <td>${app.category}</td>
      <td>${app.programLabel}</td>
      <td>${app.campus}</td>
      <td><span class="badge ${tone}">${label}</span></td>
      <td class="action-cell"><button class="row-action" data-load="${app.id}">Select</button> <button class="row-action" data-del="${app.id}" title="Remove application">Remove</button></td>
    </tr>`;
  }).join('');
  filterList();
}

function filterList() {
  let visible = 0;
  const term = search.value.toLowerCase();
  [...tbody.querySelectorAll('tr')].forEach(row => {
    const show = row.textContent.toLowerCase().includes(term) &&
      (status.value === 'all' || row.dataset.status === status.value);
    row.hidden = !show;
    if (show) visible++;
  });
  resultCount.textContent = `Showing ${visible} of ${Object.keys(Store.applicants).length} applicants`;
}
[search, status].forEach(item => item.addEventListener('input', filterList));

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
document.querySelector('[data-alert]')?.addEventListener('click', () => showToast('Export prepared', 'This is a demonstration prototype.'));

// Full application data per applicant (mirrors the Google Form field set).
const STRANDS = {
  ACADEMIC: [['ABM', 'Accountancy and Business Management (ABM)'], ['GAS', 'General Academic Strand (GAS)'], ['STEM', 'Science, Technology, Engineering and Mathematics (STEM)'], ['HUMSS', 'Humanities and Social Sciences (HUMSS)']],
  TVL: [['CA', 'Culinary Arts (CA)'], ['CE', 'Computer Engineering (CE)'], ['EIM', 'Electrical Installation and Management (EIM)'], ['HRM', 'Hotel and Restaurant Management (HRM)'], ['IT', 'Information Technology (IT)'], ['OM', 'Office Management (OM)'], ['PN', 'Practical Nursing (PN)']]
};
// Applicants come from the shared store (seeded with the same demo data).
const APPLICANTS = Store.applicants;

// Form field bindings: element id -> applicant key.
const FIELD_MAP = {
  'f-campus': 'campus',
  'f-grade': 'grade', 'f-track': 'track', 'f-strand': 'strand',
  'f-lrn-status': 'lrnStatus', 'f-psa': 'psa', 'f-lrn': 'lrn', 'f-last': 'last', 'f-first': 'first',
  'f-middle': 'middle', 'f-ext': 'ext', 'f-dob': 'dob', 'f-sex': 'sex', 'f-age': 'age',
  'f-contact': 'contact', 'f-ip': 'ip', 'f-ip-other': 'ipOther', 'f-mother-tongue': 'motherTongue', 'f-honors': 'honors',
  'f-house': 'house', 'f-province': 'province', 'f-city': 'city', 'f-barangay': 'barangay',
  'f-enrolled': 'enrolled', 'f-school': 'school', 'f-school-address': 'schoolAddress', 'f-school-id': 'schoolId',
  'f-father': 'father', 'f-mother': 'mother', 'f-guardian': 'guardian', 'f-guardian-contact': 'guardianContact',
  'f-regdate': 'regDate', 'f-referred': 'referred'
};
const deliveryChecks = [...document.querySelectorAll('input[name="delivery"]')];
let activeApp = null;

// Strand options depend on the selected track (Academic vs TVL).
function renderStrands(track) {
  const select = document.querySelector('#f-strand');
  select.innerHTML = STRANDS[track].map(([value, label]) => `<option value="${value}">${label}</option>`).join('');
}
document.querySelector('#f-track').addEventListener('change', event => {
  renderStrands(event.target.value);
});

// Conditional fields: IP "Other" and delivery "Other".
document.querySelector('#f-ip').addEventListener('change', event => {
  document.querySelector('#f-ip-other-wrap').classList.toggle('hidden', event.target.value !== 'Yes');
});

function setDelivery(values, other) {
  deliveryChecks.forEach(check => { check.checked = values.includes(check.value); });
  document.querySelector('#f-delivery-other').value = other || '';
  document.querySelector('#f-delivery-other').style.display = (values.includes('Other') || other) ? 'inline-block' : 'none';
}
deliveryChecks.forEach(check => check.addEventListener('change', () => {
  const otherCheck = deliveryChecks.find(c => c.value === 'Other');
  document.querySelector('#f-delivery-other').style.display = otherCheck.checked ? 'inline-block' : 'none';
}));

function applyApplicant(app) {
  Object.entries(FIELD_MAP).forEach(([id, key]) => {
    const el = document.querySelector('#' + id);
    if (el && app[key] !== undefined) el.value = app[key];
  });
  renderStrands(app.track);
  document.querySelector('#f-strand').value = app.strand;
  setDelivery(app.delivery, app.deliveryOther);
  document.querySelector('#f-ip-other-wrap').classList.toggle('hidden', app.ip !== 'Yes');
}

function refreshDuplicateSuggestion() {
  const suggest = document.querySelector('#dup-suggest');
  const lastName = document.querySelector('#f-last').value.trim().toLowerCase();
  const journey = Store.getJourney();
  const persona = journey && journey.appId === activeApp ? Store.getPersona(journey.persona) : null;
  const pair = activeApp && (APPLICANTS[activeApp].flag === 'dup' || persona?.dup) ? Store.getPair(activeApp) : null;
  const flagged = activeApp && (APPLICANTS[activeApp].flag === 'dup' || Boolean(persona && persona.dup));
  const incomingFields = pair?.incoming?.fields || [];
  const existingFields = pair?.existing?.fields || [];
  const existingName = existingFields.find(([label]) => label === 'Full name')?.[1] || persona?.existingName || 'Existing student record';
  const existingId = pair?.existing?.sub?.match(/STU-[\d-]+/)?.[0] || persona?.existingId || '';
  const matches = incomingFields.filter(([, , match]) => match).map(([label]) => label.toLowerCase());
  const matchLabels = matches.length
    ? matches.join(', ')
    : persona?.dup ? 'date of birth, sex, previous school, guardian contact' : 'identity details';
  const existingLink = document.querySelector('#dup-existing-link');
  const matchReason = document.querySelector('#dup-match-reason');
  if (existingLink) {
    existingLink.textContent = `${existingName}${existingId ? ` · ${existingId}` : ''}`;
    existingLink.href = activeApp ? `duplicate.html?app=${encodeURIComponent(activeApp)}` : 'duplicate.html';
  }
  if (matchReason) {
    matchReason.textContent = `Matching fields: ${matchLabels}. Review the incoming and existing records before saving this application.`;
  }
  suggest.classList.toggle('show', Boolean(flagged || lastName.includes('santos')));
}
document.querySelector('#f-last').addEventListener('input', refreshDuplicateSuggestion);

// Load an application into the encode form, highlight its row, and show the banner.
function selectApplication(id) {
  const app = APPLICANTS[id];
  if (!app) return false;
  [...tbody.querySelectorAll('tr')].forEach(r => r.style.background = '');
  const row = [...tbody.querySelectorAll('tr')].find(r => r.dataset.app === id);
  if (row) row.style.background = '#eaf3f8';
  activeApp = id;
  applyApplicant(app);
  document.querySelector('#selected-name').textContent = app.name;
  document.querySelector('#selected-id').textContent = `· ${id}`;
  document.querySelector('#selected-banner').classList.add('show');
  refreshDuplicateSuggestion();
  return true;
}

// Load an application row into the encode form (or remove it).
document.querySelector('#app-body').addEventListener('click', event => {
  const btn = event.target.closest('[data-load], [data-del]');
  const row = event.target.closest('tr');
  if (!row) return;
  if (btn && btn.dataset.del) {
    const app = APPLICANTS[btn.dataset.del];
    Store.removeApplicant(btn.dataset.del);
    if (activeApp === btn.dataset.del) {
      activeApp = null;
      document.querySelector('#selected-banner').classList.remove('show');
    }
    renderList();
    showToast('Application removed', `${app ? app.name : 'Application'} (${btn.dataset.del}) was deleted.`);
    return;
  }
  if (!btn || !btn.dataset.load) return;
  selectApplication(row.dataset.app);
});

// Clear form for a fresh walk-in application.
document.querySelector('#clear-form').addEventListener('click', () => {
  activeApp = null;
  [...tbody.querySelectorAll('tr')].forEach(r => r.style.background = '');
  Object.keys(FIELD_MAP).forEach(id => { const el = document.querySelector('#' + id); if (el) el.value = ''; });
  deliveryChecks.forEach(check => { check.checked = false; });
  document.querySelector('#f-delivery-other').value = '';
  document.querySelector('#f-delivery-other').style.display = 'none';
  document.querySelector('#f-ip-other-wrap').classList.add('hidden');
  document.querySelector('#selected-banner').classList.remove('show');
  document.querySelector('#dup-suggest').classList.remove('show');
  document.querySelector('#f-track').value = 'ACADEMIC';
  renderStrands('ACADEMIC');
  showToast('Form cleared', 'Ready for a new walk-in application.');
});

// Prevent Enter-key form submission from reloading the page.
document.querySelector('#profile-form').addEventListener('submit', event => event.preventDefault());

// Save actions — persist edits back into the shared store.
function collectForm() {
  const out = {};
  Object.entries(FIELD_MAP).forEach(([id, key]) => {
    const el = document.querySelector('#' + id);
    if (el) out[key] = el.value;
  });
  out.delivery = deliveryChecks.filter(c => c.checked).map(c => c.value);
  return out;
}

// Save actions — persist edits to the shared store, then route back to the
// Enrollment Queue with the updated applicant highlighted (?highlight=<ID>).
function saveAndReturn(kind) {
  if (!activeApp) { showToast('No application selected', 'Select an application from the list first.'); return; }
  Store.updateApplicant(activeApp, collectForm());
  if (kind === 'check') {
    const lastName = document.querySelector('#f-last').value.trim().toLowerCase();
    const journey = Store.getJourney();
    const transfereeScenario = journey?.appId === activeApp && journey.persona === 'transferee';
    const duplicate = transfereeScenario || APPLICANTS[activeApp].flag === 'dup' || lastName.includes('santos');
    if (duplicate) {
      Store.updateApplicant(activeApp, transfereeScenario
        ? { evalStatus: 'flag', queueStatus: 'review', flag: 'dup' }
        : { evalStatus: 'flag' });
      if (transfereeScenario) Store.flagPair(activeApp);
    } else {
      Store.updateApplicant(activeApp, { evalStatus: 'ready', queueStatus: 'review' });
    }
  }
  location.href = `queue.html?highlight=${encodeURIComponent(activeApp)}&saved=${kind}`;
}

document.querySelector('#btn-draft').addEventListener('click', () => saveAndReturn('draft'));
document.querySelector('#btn-check').addEventListener('click', () => saveAndReturn('check'));

// Auto-encode (simulate) — runs the Encode Profile stage for the active
// journey student (or simply stores the form for any other applicant).
document.querySelector('#btn-sim').addEventListener('click', () => {
  if (!activeApp) { showToast('No application selected', 'Select an application from the list first.'); return; }
  const j = Store.getJourney();
  if (j && j.active && j.appId === activeApp) {
    Sim.runStage(2);
    showToast('Encode simulated', `${APPLICANTS[activeApp].name} (${activeApp}) — profile encoded; automated duplicate check ran.`);
    return;
  }
  Store.updateApplicant(activeApp, collectForm());
  showToast('Profile saved', `${APPLICANTS[activeApp].name} — encode details stored.`);
});

// Initialize the strand list for the default track.
renderStrands(document.querySelector('#f-track').value);

// Keep the applicant list in sync with the shared store.
document.addEventListener('store:change', renderList);
renderList();

// Deep-link support: when arriving from the Enrollment Queue (?app=<ID>),
// auto-load that application and scroll the encode form into view.
const deepLink = new URLSearchParams(location.search).get('app');
if (deepLink && selectApplication(deepLink)) {
  const banner = document.querySelector('#selected-banner');
  showToast('Application opened', `${APPLICANTS[deepLink].name} (${deepLink}) loaded from the Enrollment Queue.`);
  setTimeout(() => banner.scrollIntoView({ behavior: 'smooth', block: 'center' }), 60);
}
