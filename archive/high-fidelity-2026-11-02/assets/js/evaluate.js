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

// Headline counters, derived from the evaluation queue rather than fixed in the
// markup, so they stay truthful as the cohort changes.
function renderEvalStats(applicants) {
  const counts = { review: 0, ready: 0, flag: 0 };
  applicants.forEach(app => { if (counts[app.evalStatus] !== undefined) counts[app.evalStatus] += 1; });
  const set = (id, value) => { const el = document.querySelector(id); if (el) el.textContent = String(value); };
  set('#st-review', counts.review);
  set('#st-ready', counts.ready);
  set('#st-flag', counts.flag);
  // "Encoded" = profiles that have moved past evaluation into a student record.
  set('#st-encoded', Object.keys(Store.getRecords()).length);
}

function renderList() {
  const applicants = Object.values(Store.applicants);
  renderEvalStats(applicants);
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

const pager = Pagination.attach({ controls: '#pager' });
pager.setOnChange(() => filterList({ keepPage: true }));

function filterList(options) {
  if (!options || !options.keepPage) pager.reset();
  const term = search.value.toLowerCase();
  const matched = [];
  [...tbody.querySelectorAll('tr')].forEach(row => {
    const show = row.textContent.toLowerCase().includes(term) &&
      (status.value === 'all' || row.dataset.status === status.value);
    row.hidden = !show;
    if (show) matched.push(row);
  });
  const view = pager.render(matched);
  const total = Object.keys(Store.applicants).length;
  resultCount.textContent = view.total
    ? `Showing ${view.from}-${view.to} of ${view.total} applicants${view.total === total ? '' : ` (filtered from ${total})`}`
    : `No applications match the current filters (${total} total)`;
}
[search, status].forEach(item => item.addEventListener('input', () => filterList()));

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
  'f-campus': 'campus', 'f-category': 'category',
  'f-grade': 'grade', 'f-track': 'track', 'f-strand': 'strand',
  'f-lrn-status': 'lrnStatus', 'f-psa': 'psa', 'f-lrn': 'lrn', 'f-last': 'last', 'f-first': 'first',
  'f-middle': 'middle', 'f-ext': 'ext', 'f-dob': 'dob', 'f-sex': 'sex', 'f-age': 'age',
  'f-contact': 'contact', 'f-email': 'email', 'f-ip': 'ip', 'f-ip-other': 'ipOther', 'f-mother-tongue': 'motherTongue', 'f-honors': 'honors',
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

// Builds the candidate {name, dob, lrn} from the form's own fields, in the
// same "Last, First" format applicant records already use (see app.name).
function currentCandidate() {
  const last = document.querySelector('#f-last').value.trim();
  const first = document.querySelector('#f-first').value.trim();
  return {
    name: (last || first) ? `${last}, ${first}` : '',
    dob: document.querySelector('#f-dob').value.trim(),
    lrn: document.querySelector('#f-lrn').value.trim()
  };
}

function refreshDuplicateSuggestion() {
  const suggest = document.querySelector('#dup-suggest');
  const link = suggest.querySelector('strong a');
  const note = suggest.querySelector('span');
  const seedFlag = Boolean(activeApp && APPLICANTS[activeApp].flag === 'dup');
  const candidate = currentCandidate();
  const match = candidate.name ? Store.suggestDuplicate(candidate, activeApp) : null;
  suggest.classList.toggle('show', Boolean(seedFlag || match));
  if (match) {
    const pct = Math.round(match.score * 100);
    link.textContent = match.name;
    link.href = `duplicate.html?app=${encodeURIComponent(activeApp)}`;
    note.textContent = `Possible match: ${match.name} — ${pct}% confidence. Review before saving this application.`;
  } else if (seedFlag) {
    const seeded = APPLICANTS[activeApp];
    link.textContent = seeded.name;
    link.href = `duplicate.html?app=${encodeURIComponent(activeApp)}`;
    note.textContent = 'Same name and birth date. Review before saving this application.';
  }}
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
  document.querySelector('#prior-banner').classList.remove('show');
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
  document.querySelector('#prior-banner').classList.remove('show');
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
  let duplicate = false;
  if (kind === 'check') {
    const seedFlag = APPLICANTS[activeApp].flag === 'dup';
    const candidate = currentCandidate();
    const match = candidate.name ? Store.suggestDuplicate(candidate, activeApp) : null;
    if (match && match.score >= 0.75 && !Store.getPair(activeApp)) {
      Store.createPairFromMatch(activeApp, match);
    }
    duplicate = Boolean(seedFlag || match);
    Store.updateApplicant(activeApp, duplicate ? { evalStatus: 'flag' } : { evalStatus: 'ready', queueStatus: 'review' });  }
  // A flagged duplicate needs a decision before anything else can proceed —
  // send the registrar straight to the comparison instead of back to the
  // queue, so they don't have to find their way there themselves.
  if (kind === 'check' && duplicate) {
    location.href = `duplicate.html?app=${encodeURIComponent(activeApp)}`;
    return;
  }
  location.href = `queue.html?highlight=${encodeURIComponent(activeApp)}&saved=${kind}`;
}

document.querySelector('#btn-draft').addEventListener('click', () => saveAndReturn('draft'));
document.querySelector('#btn-check').addEventListener('click', () => saveAndReturn('check'));

// Check for prior ICI record — for continuing students (e.g. Grade 11 -> 12
// at the same school) who already have requirements on file. Looks up the
// typed LRN against the prior-enrollment catalog and, on a match, links the
// record so the checklist doesn't ask for documents already submitted.
document.querySelector('#btn-check-prior').addEventListener('click', () => {
  if (!activeApp) { showToast('No application selected', 'Select an application from the list first.'); return; }
  const banner = document.querySelector('#prior-banner');
  const lrn = document.querySelector('#f-lrn').value.trim();
  const match = Store.findPriorStudent(lrn);
  if (!match) {
    banner.classList.remove('show');
    showToast('No prior record found', 'No matching ICI enrollment record for this LRN.');
    return;
  }
  Store.linkPriorStudent(activeApp, match.id);
  document.querySelector('#prior-banner-text').textContent = `Completed ${match.lastGradeLevel}, SY ${match.lastSchoolYear}, ${match.campus}. Requirements on file — no resubmission required.`;
  banner.classList.add('show');
  showToast('Continuing student confirmed', `${match.name} (${match.id}) — prior ICI record linked.`);
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
