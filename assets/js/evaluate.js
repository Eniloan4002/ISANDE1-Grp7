// Evaluate Applications demo interactions. No server or persistent data is used.
const rows = [...document.querySelectorAll('#app-body tr')];
const resultCount = document.querySelector('#result-count');
const search = document.querySelector('#app-search');
const status = document.querySelector('#app-status');

function filterList() {
  let visible = 0;
  const term = search.value.toLowerCase();
  rows.forEach(row => {
    const show = row.textContent.toLowerCase().includes(term) &&
      (status.value === 'all' || row.dataset.status === status.value);
    row.hidden = !show;
    if (show) visible++;
  });
  resultCount.textContent = `Showing ${visible} of 5 applicants`;
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
const APPLICANTS = {
  'APP-2026-0148': {
    name: 'Dela Cruz, Juan', flag: '', category: 'New Student — Regular', ref: 'CSH-0089', bracket: 'Bracket A', campus: 'Main — Iligan',
    grade: 'Grade 11', track: 'ACADEMIC', strand: 'HUMSS', delivery: ['Modular (Print)'], deliveryOther: '',
    lrnStatus: 'With LRN', psa: 'PSA-2023-000412', lrn: '119270140008', last: 'Dela Cruz', first: 'Juan', middle: 'Ramon', ext: '',
    dob: '03/14/2009', sex: 'Male', age: '17', contact: '0917 555 0148', ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: 'With Honors (Grade 10)',
    house: 'Zone 2, Purok 5', province: 'Lanao del Norte', city: 'Iligan City', barangay: 'Pala-o',
    enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
    father: 'Ramon Dela Cruz', mother: 'Liza Dela Cruz', guardian: 'Ramon Dela Cruz', guardianContact: '0917 555 0148', regDate: '07/28/2026', referred: 'STAFF'
  },
  'APP-2026-0149': {
    name: 'Santos, Maria A.', flag: 'dup', category: 'Transferee — Voucher (ESC)', ref: 'CSH-0091', bracket: 'Bracket B', campus: 'Main — Iligan',
    grade: 'Grade 11', track: 'ACADEMIC', strand: 'STEM', delivery: ['Modular (Digital)', 'Online'], deliveryOther: '',
    lrnStatus: 'With LRN', psa: 'PSA-2022-001830', lrn: '108517230184', last: 'Santos', first: 'Maria', middle: 'Aguinaldo', ext: '',
    dob: '07/22/2008', sex: 'Female', age: '18', contact: '0918 555 0149', ip: 'Yes', ipOther: 'Meranaw', motherTongue: 'Maranao', honors: 'With Honors (Grade 10)',
    house: 'Purok 4, Sitio Raya', province: 'Lanao del Sur', city: 'Marawi City', barangay: 'Tambo',
    enrolled: 'Yes', school: 'Kapatagan National High School', schoolAddress: 'Poblacion, Kapatagan, Lanao del Norte', schoolId: 'KNH-2024-118',
    father: 'Manuel Santos', mother: 'Aida Aguinaldo Santos', guardian: 'Manuel Santos', guardianContact: '0917 555 0991', regDate: '07/30/2026', referred: 'GUIDANCE'
  },
  'APP-2026-0150': {
    name: 'Reyes, Pedro', flag: '', category: 'New Student — Regular', ref: 'CSH-0093', bracket: 'Bracket A', campus: 'Bugo — CDO',
    grade: 'Grade 11', track: 'TVL', strand: 'CA', delivery: ['Online'], deliveryOther: '',
    lrnStatus: 'With LRN', psa: 'PSA-2024-005621', lrn: '120381650117', last: 'Reyes', first: 'Pedro', middle: 'Lopez', ext: 'Jr.',
    dob: '11/02/2007', sex: 'Male', age: '18', contact: '0919 555 0150', ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: '',
    house: 'Blk 7, Brgy. San Isidro', province: 'Misamis Oriental', city: 'Cagayan de Oro', barangay: 'Bugo',
    enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
    father: 'Dante Reyes', mother: 'Carmen Lopez Reyes', guardian: 'Dante Reyes', guardianContact: '0919 555 0150', regDate: '08/01/2026', referred: 'TVL'
  },
  'APP-2026-0151': {
    name: 'Lim, Angela', flag: '', category: 'Returnee — Regular', ref: 'CSH-0095', bracket: 'Bracket B', campus: 'Kapatagan',
    grade: 'Grade 12', track: 'ACADEMIC', strand: 'GAS', delivery: ['Modular (Print)', 'Educational TV'], deliveryOther: '',
    lrnStatus: 'Returning (Balik-Aral)', psa: 'PSA-2021-002944', lrn: '112500710292', last: 'Lim', first: 'Angela', middle: 'Tan', ext: '',
    dob: '05/30/2008', sex: 'Female', age: '18', contact: '0920 555 0151', ip: 'No', ipOther: '', motherTongue: 'Hiligaynon', honors: 'With High Honors (G11)',
    house: 'Purok 3, Poblacion', province: 'Lanao del Norte', city: 'Kapatagan', barangay: 'Central',
    enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
    father: 'Ramon Lim', mother: 'Grace Tan Lim', guardian: 'Ramon Lim', guardianContact: '0920 555 0151', regDate: '08/02/2026', referred: 'STAFF'
  },
  'APP-2026-0152': {
    name: 'Abad, Carla', flag: '', category: 'New Student — Regular', ref: 'CSH-0096', bracket: 'Bracket A', campus: 'Main — Iligan',
    grade: 'Grade 11', track: 'ACADEMIC', strand: 'ABM', delivery: ['Modular (Print)', 'Online'], deliveryOther: '',
    lrnStatus: 'With LRN', psa: 'PSA-2023-000918', lrn: '119770320691', last: 'Abad', first: 'Carla', middle: 'Mercado', ext: '',
    dob: '09/11/2008', sex: 'Female', age: '17', contact: '0921 555 0152', ip: 'No', ipOther: '', motherTongue: 'Ilonggo', honors: 'With Honors (Grade 10)',
    house: 'Zone 5, Purok 2', province: 'Lanao del Norte', city: 'Iligan City', barangay: 'Hinaplanon',
    enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
    father: 'Eduardo Abad', mother: 'Marites Mercado Abad', guardian: 'Eduardo Abad', guardianContact: '0921 555 0152', regDate: '08/03/2026', referred: 'ENGLISH'
  }
};

// Form field bindings: element id -> applicant key.
const FIELD_MAP = {
  'f-category': 'category', 'f-ref': 'ref', 'f-bracket': 'bracket', 'f-campus': 'campus',
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

// Conditional fields: IP "Other", school details, and delivery "Other".
document.querySelector('#f-ip').addEventListener('change', event => {
  document.querySelector('#f-ip-other-wrap').classList.toggle('hidden', event.target.value !== 'Yes');
});
document.querySelector('#f-enrolled').addEventListener('change', event => {
  const show = event.target.value === 'Yes';
  document.querySelectorAll('.school-field').forEach(el => el.classList.toggle('hidden', !show));
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
  document.querySelectorAll('.school-field').forEach(el => el.classList.toggle('hidden', app.enrolled !== 'Yes'));
}

function refreshDuplicateSuggestion() {
  const suggest = document.querySelector('#dup-suggest');
  const lastName = document.querySelector('#f-last').value.trim().toLowerCase();
  const flagged = activeApp && APPLICANTS[activeApp].flag === 'dup';
  suggest.classList.toggle('show', Boolean(flagged || lastName.includes('santos')));
}
document.querySelector('#f-last').addEventListener('input', refreshDuplicateSuggestion);

// Load an application row into the encode form.
document.querySelector('#app-body').addEventListener('click', event => {
  const row = event.target.closest('tr');
  if (!row) return;
  rows.forEach(r => r.style.background = '');
  row.style.background = '#eaf3f8';
  activeApp = row.dataset.app;
  const app = APPLICANTS[activeApp];
  applyApplicant(app);
  document.querySelector('#selected-name').textContent = app.name;
  document.querySelector('#selected-id').textContent = `· ${activeApp}`;
  document.querySelector('#selected-banner').classList.add('show');
  refreshDuplicateSuggestion();
});

// Clear form for a fresh walk-in application.
document.querySelector('#clear-form').addEventListener('click', () => {
  activeApp = null;
  rows.forEach(r => r.style.background = '');
  Object.keys(FIELD_MAP).forEach(id => { const el = document.querySelector('#' + id); if (el) el.value = ''; });
  deliveryChecks.forEach(check => { check.checked = false; });
  document.querySelector('#f-delivery-other').value = '';
  document.querySelector('#f-delivery-other').style.display = 'none';
  document.querySelector('#f-ip-other-wrap').classList.add('hidden');
  document.querySelectorAll('.school-field').forEach(el => el.classList.add('hidden'));
  document.querySelector('#selected-banner').classList.remove('show');
  document.querySelector('#dup-suggest').classList.remove('show');
  document.querySelector('#f-track').value = 'ACADEMIC';
  renderStrands('ACADEMIC');
  showToast('Form cleared', 'Ready for a new walk-in application.');
});

// Prevent Enter-key form submission from reloading the page.
document.querySelector('#profile-form').addEventListener('submit', event => event.preventDefault());

// Save actions.
document.querySelector('#btn-draft').addEventListener('click', () => {
  if (!activeApp) { showToast('No application selected', 'Select an application from the list first.'); return; }
  showToast('Draft saved', `${APPLICANTS[activeApp].name} — profile saved as a draft.`);
});

document.querySelector('#btn-check').addEventListener('click', () => {
  if (!activeApp) { showToast('No application selected', 'Select an application from the list first.'); return; }
  const lastName = document.querySelector('#f-last').value.trim().toLowerCase();
  const duplicate = APPLICANTS[activeApp].flag === 'dup' || lastName.includes('santos');
  if (duplicate) {
    showToast('Possible duplicate detected', 'Routed to Duplicate Records for review.');
  } else {
    showToast('No duplicate found', `${APPLICANTS[activeApp].name} — record saved to the queue.`);
  }
});

// Initialize the strand list for the default track.
renderStrands(document.querySelector('#f-track').value);
