// Enrollment Submission demo interactions. No server or persistent data is used.
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

// Strand options depend on the selected track (Academic vs TVL).
const STRANDS = {
  ACADEMIC: [['ABM', 'Accountancy and Business Management (ABM)'], ['GAS', 'General Academic Strand (GAS)'], ['STEM', 'Science, Technology, Engineering and Mathematics (STEM)'], ['HUMSS', 'Humanities and Social Sciences (HUMSS)']],
  TVL: [['CA', 'Culinary Arts (CA)'], ['CE', 'Computer Engineering (CE)'], ['EIM', 'Electrical Installation and Management (EIM)'], ['HRM', 'Hotel and Restaurant Management (HRM)'], ['IT', 'Information Technology (IT)'], ['OM', 'Office Management (OM)'], ['PN', 'Practical Nursing (PN)']]
};

function renderStrands(track) {
  const select = document.querySelector('#s-strand');
  if (!track || !STRANDS[track]) {
    select.innerHTML = '<option value="">Select a track first</option>';
    return;
  }
  select.innerHTML = STRANDS[track].map(([value, label]) => `<option value="${value}">${label}</option>`).join('');
}
document.querySelector('#s-track').addEventListener('change', event => {
  renderStrands(event.target.value);
});

// Conditional fields: IP "Other" and delivery "Other".
document.querySelector('#s-ip').addEventListener('change', event => {
  document.querySelector('#s-ip-other-wrap').classList.toggle('hidden', event.target.value !== 'Yes');
});

const deliveryChecks = [...document.querySelectorAll('input[name="delivery"]')];
deliveryChecks.forEach(check => check.addEventListener('change', () => {
  const otherCheck = deliveryChecks.find(c => c.value === 'Other');
  document.querySelector('#s-delivery-other').style.display = otherCheck.checked ? 'inline-block' : 'none';
}));

// File uploads: show the chosen file name and expose a Remove control that
// detaches the selection so the document can be re-uploaded.
document.querySelectorAll('.upload-item').forEach(item => {
  const input = item.querySelector('.file-pick input[type="file"]');
  const label = item.querySelector('.pick-label');
  const removeBtn = item.querySelector('.file-remove');
  const docName = item.querySelector('.name').childNodes[0].textContent.trim();

  function syncFileState() {
    const hasFile = Boolean(input.files && input.files[0]);
    label.textContent = hasFile ? input.files[0].name : 'Choose file';
    removeBtn.hidden = !hasFile;
  }

  input.addEventListener('change', () => {
    syncFileState();
    if (input.files && input.files[0]) {
      showToast('File attached', `${docName} — ${input.files[0].name} selected.`);
    }
  });

  removeBtn.addEventListener('click', () => {
    input.value = '';
    syncFileState();
    // Honor the removal for simulated uploads too, so the document is not
    // silently attached on submit.
    delete pendingUploads[docName];
    item.querySelector('.pick-label').focus();
    showToast('File removed', `${docName} cleared — no file attached. Choose a file to re-upload.`);
  });
});

const successBox = document.querySelector('#submit-success');

// Save Draft — store nothing, just confirm the action.
document.querySelector('#btn-draft').addEventListener('click', () => {
  const last = document.querySelector('#s-last').value.trim();
  const first = document.querySelector('#s-first').value.trim();
  const who = (last || first) ? `${last}, ${first}` : 'Application';
  showToast('Draft saved', `${who} — draft stored locally. You can resume any time.`);
});

// Submit Application — validate key fields, then simulate the queue entry.
document.querySelector('#submission-form').addEventListener('submit', event => {
  event.preventDefault();
  const required = ['s-last', 's-first', 's-middle', 's-dob', 's-age', 's-contact', 's-house', 's-city', 's-barangay', 's-father', 's-mother', 's-guardian-contact', 's-grade', 's-track', 's-strand', 's-campus'];
  // LRN is only required when the applicant indicates they have one.
  const lrnStatus = document.querySelector('#s-lrn-status').value;
  if (lrnStatus === 'With LRN' || lrnStatus === 'Returning (Balik-Aral)') {
    required.push('s-lrn');
  }
  const missing = required.filter(id => !document.querySelector('#' + id).value.trim());
  if (missing.length) {
    showToast('Missing required fields', 'Please complete the marked fields before submitting.');
    missing.forEach(id => {
      const el = document.querySelector('#' + id);
      el.style.borderColor = '#b04343';
      el.style.background = '#fff7f7';
    });
    const first = missing[0];
    document.querySelector('#' + first)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
    return;
  }

  // Actually create the record in the shared store so it appears in the Queue.
  const id = Store.addApplicant({
    last: document.querySelector('#s-last').value.trim(),
    first: document.querySelector('#s-first').value.trim(),
    middle: document.querySelector('#s-middle').value.trim(),
    ext: document.querySelector('#s-ext').value.trim(),
    name: `${document.querySelector('#s-last').value.trim()}, ${document.querySelector('#s-first').value.trim()}`,
    dob: document.querySelector('#s-dob').value.trim(),
    age: document.querySelector('#s-age').value.trim(),
    sex: document.querySelector('#s-sex').value,
    contact: document.querySelector('#s-contact').value.trim(),
    email: document.querySelector('#s-email').value.trim(),
    lrn: document.querySelector('#s-lrn').value.trim(),
    lrnStatus: document.querySelector('#s-lrn-status').value,
    psa: document.querySelector('#s-psa').value.trim(),
    ip: document.querySelector('#s-ip').value, ipOther: document.querySelector('#s-ip-other').value.trim(),
    motherTongue: document.querySelector('#s-mother-tongue').value,
    honors: document.querySelector('#s-honors').value.trim(),
    house: document.querySelector('#s-house').value.trim(), province: document.querySelector('#s-province').value,
    city: document.querySelector('#s-city').value.trim(), barangay: document.querySelector('#s-barangay').value.trim(),
    enrolled: document.querySelector('#s-enrolled').value, school: document.querySelector('#s-school').value.trim(),
    schoolAddress: document.querySelector('#s-school-address').value.trim(), schoolId: document.querySelector('#s-school-id').value.trim(),
    father: document.querySelector('#s-father').value.trim(), mother: document.querySelector('#s-mother').value.trim(),
    guardian: document.querySelector('#s-guardian').value.trim(), guardianContact: document.querySelector('#s-guardian-contact').value.trim(),
    regDate: document.querySelector('#s-regdate').value.trim(), referred: document.querySelector('#s-referred').value,
    grade: document.querySelector('#s-grade').value, track: document.querySelector('#s-track').value,
    strand: document.querySelector('#s-strand').value, campus: document.querySelector('#s-campus').value,
    campusKey: document.querySelector('#s-campus').value.toLowerCase().startsWith('main') ? 'main' : document.querySelector('#s-campus').value.toLowerCase().startsWith('kapat') ? 'kapatagan' : 'bugo',
    category: (document.querySelector('#s-category')?.value) || 'New',
    program: document.querySelector('#s-grade').value.includes('11') || document.querySelector('#s-grade').value.includes('12') ? 'shs' : 'college',
    programLabel: `${document.querySelector('#s-strand').value} · ${document.querySelector('#s-grade').value}`,
    queueStatus: 'pre-enlisted'
  });
  document.querySelector('#success-app').textContent = id;
  successBox.classList.add('show');
  successBox.scrollIntoView({ behavior: 'smooth', block: 'center' });
  document.querySelector('#submission-form').classList.add('hidden');
  document.querySelector('.editor-bar').classList.add('hidden');
  showToast('Application submitted', `${id} — entered the Enrollment Queue as Pre-Enlisted.`);
});

// Clear the error highlight once the user edits a field again.
// Selects fire 'change' (not 'input'), so listen for both.
document.querySelectorAll('#submission-form input, #submission-form select, #submission-form textarea').forEach(el => {
  ['input', 'change'].forEach(evt => el.addEventListener(evt, () => {
    el.style.borderColor = '';
    el.style.background = '';
  }));
});

// Initialize the strand list for the default (empty) track.
renderStrands(document.querySelector('#s-track').value);

// ---- Editor / Student Preview toggle ----
// The submission screen acts like a form editor: "Preview as Student" renders
// the application Google-Forms-style — each form section is one page, with
// Next/Back navigation and a progress bar. Read-only by design.
const tabEdit = document.querySelector('#tab-edit');
const tabPreview = document.querySelector('#tab-preview');
const formEl = document.querySelector('#submission-form');
const previewPanel = document.querySelector('#preview-panel');
const previewBody = document.querySelector('#preview-body');
const previewStep = document.querySelector('#preview-step');
const previewProgress = document.querySelector('#preview-progress');
const previewBack = document.querySelector('#preview-back');
const previewNext = document.querySelector('#preview-next');
const previewNote = document.querySelector('#preview-note');

// The six form sections, mirroring the Google Form layout (one page each).
const PREVIEW_SECTIONS = [
  {
    title: 'Enrollment Preferences',
    note: 'Grade level, track, and preferred campus for SY 2026-2027.',
    fields: [
      ['Grade level to Enroll', '#s-grade'],
      ['Which Track do you want to Enroll?', '#s-track'],
      ['Strand / Specialization', '#s-strand'],
      ['Preferred campus', '#s-campus'],
      ['Preferred distance learning modality/modalities', deliveryPreviewValue]
    ]
  },
  {
    title: 'Student Information',
    note: 'Please refrain from using ALL CAPS.',
    fields: [
      ['Check the appropriate box only', '#s-lrn-status'],
      ['PSA Birth Certificate No.', '#s-psa'],
      ["Learner's Reference Number (LRN)", '#s-lrn'],
      ['Last Name', '#s-last'],
      ['First Name', '#s-first'],
      ['Middle Name', '#s-middle'],
      ['Extension Name', '#s-ext'],
      ['Date of Birth', '#s-dob'],
      ['Sex', '#s-sex'],
      ['Age', '#s-age'],
      ["Student's Contact Number", '#s-contact'],
      ['Email', '#s-email'],
      ['Belonging to any Indigenous People (IP)?', ipPreviewValue],
      ['Mother Tongue', '#s-mother-tongue'],
      ['Academic Honors Received', '#s-honors']
    ]
  },
  {
    title: 'Complete Home Address',
    note: 'Your permanent home address.',
    fields: [
      ['House Number and Street', '#s-house'],
      ['Province', '#s-province'],
      ['City / Municipality', '#s-city'],
      ['Barangay', '#s-barangay']
    ]
  },
  {
    title: 'Current School',
    note: 'If you are currently enrolled, provide your previous school details.',
    fields: [
      ['Are you currently enrolled?', '#s-enrolled'],
      ['Name of your School', '#s-school'],
      ["School's Address", '#s-school-address'],
      ["School's ID", '#s-school-id']
    ]
  },
  {
    title: 'Parent / Guardian',
    note: 'Certification consent: information is used for the Learner Information System (DepEd) and is confidential.',
    fields: [
      ["Father's Name", '#s-father'],
      ["Mother's Name", '#s-mother'],
      ["Guardian's Name", '#s-guardian'],
      ["Parent's / Guardian's Contact Number", '#s-guardian-contact'],
      ['Date of Registration', '#s-regdate'],
      ['Referred by', '#s-referred']
    ]
  },
  {
    title: 'Upload Requirements',
    note: 'Upload a scanned copy of each required document.',
    fields: [
      ['PSA Birth Certificate', () => uploadPreviewValue(0)],
      ['Form 137', () => uploadPreviewValue(1)],
      ['Certificate of Completion', () => uploadPreviewValue(2)],
      ['Good Moral Certificate', () => uploadPreviewValue(3)]
    ]
  }
];

const uploadInputs = [...document.querySelectorAll('.upload-item input[type="file"]')];
function deliveryPreviewValue() {
  const picked = deliveryChecks.filter(c => c.checked).map(c => c.value);
  const other = document.querySelector('#s-delivery-other').value.trim();
  if (other) picked.push('Other: ' + other);
  return picked.length ? picked.join(', ') : '';
}
function ipPreviewValue() {
  const ip = document.querySelector('#s-ip').value;
  if (ip === 'Yes' && document.querySelector('#s-ip-other').value.trim()) {
    return 'Yes — ' + document.querySelector('#s-ip-other').value.trim();
  }
  return ip;
}
function uploadPreviewValue(i) {
  const input = uploadInputs[i];
  if (input && input.files && input.files[0]) return 'Attached: ' + input.files[0].name;
  return '';
}

function fieldValue(spec) {
  return typeof spec === 'function' ? spec() : (document.querySelector(spec).value || '');
}

function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, ch => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]));
}

let previewIndex = 0;
function renderPreviewPage() {
  const section = PREVIEW_SECTIONS[previewIndex];
  previewStep.textContent = `Section ${previewIndex + 1} of ${PREVIEW_SECTIONS.length}`;
  previewProgress.style.width = ((previewIndex + 1) / PREVIEW_SECTIONS.length * 100) + '%';
  previewBody.innerHTML =
    `<h3 class="preview-section-title">${previewIndex + 1}. ${section.title}</h3>` +
    `<p class="preview-section-note">${section.note}</p>` +
    section.fields.map(([label, spec]) => {
      const value = escapeHtml(fieldValue(spec).trim());
      return `<div class="preview-row"><span class="q">${escapeHtml(label)}</span><div class="a${value ? '' : ' empty'}">${value || '— no answer —'}</div></div>`;
    }).join('');
  previewBack.disabled = previewIndex === 0;
  const isLast = previewIndex === PREVIEW_SECTIONS.length - 1;
  previewNext.textContent = isLast ? 'Finish Preview' : 'Next →';
  previewNote.textContent = isLast ? 'This is the final section. The student would press “Submit” here.' : '';
}

function showPreview(show) {
  formEl.classList.toggle('hidden', show);
  previewPanel.classList.toggle('show', show);
  previewPanel.setAttribute('aria-hidden', String(!show));
  tabEdit.classList.toggle('active', !show);
  tabPreview.classList.toggle('active', show);
  tabEdit.setAttribute('aria-selected', String(!show));
  tabPreview.setAttribute('aria-selected', String(show));
  if (show) {
    previewIndex = 0;
    renderPreviewPage();
    previewPanel.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}

tabEdit.addEventListener('click', () => showPreview(false));
tabPreview.addEventListener('click', () => showPreview(true));
previewBack.addEventListener('click', () => { if (previewIndex > 0) { previewIndex--; renderPreviewPage(); } });
previewNext.addEventListener('click', () => {
  if (previewIndex < PREVIEW_SECTIONS.length - 1) {
    previewIndex++;
    renderPreviewPage();
  } else {
    showPreview(false);
    showToast('Preview finished', 'Returned to the editor — the form is ready for a real submission.');
  }
});

// ---- Simulate a Student Submission (click-through) ----
// The presenter arms the simulation, then fills the application one section at
// a time — each section header carries a Fill button that populates that
// section's fields from the chosen persona. After all six sections are filled,
// the real Submit button fires and the persisted journey starts in the Queue.
const simStart = document.querySelector('#sim-start');
const simCancel = document.querySelector('#sim-cancel');
const simPersona = document.querySelector('#sim-persona');
const simMode = document.querySelector('#sim-mode');
const simSpeed = document.querySelector('#sim-speed');
const simProgress = document.querySelector('#sim-progress');
const simProgressFill = document.querySelector('#sim-progress-fill');
const simProgressText = document.querySelector('#sim-progress-text');
const successLink = document.querySelector('#success-queue-link');
const formSections = [...document.querySelectorAll('#submission-form .form-section')];

const SPEED = {
  fast: { char: 5, pause: 20, banner: 400 },
  normal: { char: 9, pause: 60, banner: 650 },
  slow: { char: 14, pause: 160, banner: 1000 }
};

const TOTAL_SECTIONS = 6;

let simulated = false;    // armed once Start Simulation is clicked
let filledSections = 0;   // sections completed via Fill clicks
let pendingUploads = {};

const wait = ms => new Promise(res => setTimeout(res, ms));

const S = id => document.querySelector(id);

async function typeInto(el, value, perChar) {
  if (!el) return;
  value = String(value || '');
  el.value = '';
  if (el.tagName === 'SELECT' || el.tagName === 'TEXTAREA') {
    el.value = value;
  } else {
    for (let i = 0; i < value.length; i++) {
      el.value = value.slice(0, i + 1);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      await wait(perChar);
    }
  }
  el.dispatchEvent(new Event('change', { bubbles: true }));
}

function setProgress(pct, text) {
  simProgress.hidden = false;
  simProgressFill.style.width = pct + '%';
  simProgressText.textContent = text;
}

// One field plan per section (mirrors the Google Form sections 1–6). Each plan
// returns closures that type the persona's values into that section's inputs.
const SECTION_PLANS = [
  { label: '1 · Enrollment Preferences', run: (persona, speed) => [
      () => typeInto(S('#s-grade'), persona.grade, speed.char),
      () => typeInto(S('#s-track'), persona.track, speed.char),
      () => typeInto(S('#s-strand'), persona.strand, speed.char),
      () => typeInto(S('#s-campus'), persona.campus, speed.char),
      () => deliveryChecks.forEach(c => { c.checked = persona.delivery.includes(c.value); c.dispatchEvent(new Event('change', { bubbles: true })); }),
      () => typeInto(S('#s-delivery-other'), persona.deliveryOther, speed.char)
    ] },
  { label: '2 · Student Information', run: (persona, speed) => [
      () => typeInto(S('#s-lrn-status'), persona.lrnStatus, speed.char),
      () => typeInto(S('#s-psa'), persona.psa, speed.char),
      () => typeInto(S('#s-lrn'), persona.lrn, speed.char),
      () => typeInto(S('#s-last'), persona.last, speed.char),
      () => typeInto(S('#s-first'), persona.first, speed.char),
      () => typeInto(S('#s-middle'), persona.middle, speed.char),
      () => typeInto(S('#s-ext'), persona.ext, speed.char),
      () => typeInto(S('#s-dob'), persona.dob, speed.char),
      () => typeInto(S('#s-sex'), persona.sex, speed.char),
      () => typeInto(S('#s-age'), persona.age, speed.char),
      () => typeInto(S('#s-contact'), persona.contact, speed.char),
      () => typeInto(S('#s-email'), persona.email, speed.char),
      () => typeInto(S('#s-ip'), persona.ip, speed.char),
      () => typeInto(S('#s-ip-other'), persona.ipOther, speed.char),
      () => typeInto(S('#s-mother-tongue'), persona.motherTongue, speed.char),
      () => typeInto(S('#s-honors'), persona.honors, speed.char)
    ] },
  { label: '3 · Complete Home Address', run: (persona, speed) => [
      () => typeInto(S('#s-house'), persona.house, speed.char),
      () => typeInto(S('#s-province'), persona.province, speed.char),
      () => typeInto(S('#s-city'), persona.city, speed.char),
      () => typeInto(S('#s-barangay'), persona.barangay, speed.char)
    ] },
  { label: '4 · Current School', run: (persona, speed) => [
      () => typeInto(S('#s-enrolled'), persona.enrolled, speed.char),
      () => typeInto(S('#s-school'), persona.school, speed.char),
      () => typeInto(S('#s-school-address'), persona.schoolAddress, speed.char),
      () => typeInto(S('#s-school-id'), persona.schoolId, speed.char)
    ] },
  { label: '5 · Parent / Guardian', run: (persona, speed) => [
      () => typeInto(S('#s-father'), persona.father, speed.char),
      () => typeInto(S('#s-mother'), persona.mother, speed.char),
      () => typeInto(S('#s-guardian'), persona.guardian, speed.char),
      () => typeInto(S('#s-guardian-contact'), persona.guardianContact, speed.char),
      () => typeInto(S('#s-regdate'), persona.regDate, speed.char),
      () => typeInto(S('#s-referred'), persona.referred, speed.char)
    ] }
];

// Fill ONE section (0–5) from the selected persona. Section 6 (index 5) is the
// upload grid — browsers forbid setting file inputs, so we mark documents as
// "attached (simulated)" instead.
async function fillSection(idx) {
  const persona = Store.getPersona(simPersona.value);
  const speed = SPEED[simSpeed.value] || SPEED.normal;
  if (idx === 5) {
    pendingUploads = {};
    document.querySelectorAll('.upload-item').forEach(item => {
      const docName = item.querySelector('.name').childNodes[0].textContent.trim();
      const file = persona.uploads[docName];
      if (!file) return;
      pendingUploads[docName] = file;
      item.querySelector('.pick-label').textContent = 'Attached: ' + file + ' (simulated)';
      const removeBtn = item.querySelector('.file-remove');
      if (removeBtn) removeBtn.hidden = false;
    });
    showToast('Documents attached', 'All four scanned documents attached (simulated).');
    return;
  }
  const plan = SECTION_PLANS[idx];
  for (const fn of plan.run(persona, speed)) { await fn(); await wait(speed.pause); }
}

function markSectionFilled(idx) {
  filledSections++;
  const btn = document.querySelector(`.section-fill[data-section="${idx}"]`);
  if (btn) { btn.disabled = true; btn.textContent = 'Filled'; btn.classList.add('filled'); }
  if (filledSections === TOTAL_SECTIONS) {
    const submitBtn = formEl.querySelector('button[type="submit"]');
    if (submitBtn) submitBtn.disabled = false;
    setProgress(100, 'All sections filled — click Submit Application →');
    showToast('Form complete', 'Every section is filled. Click “Submit Application” to finish.');
  } else {
    setProgress(Math.round((filledSections / TOTAL_SECTIONS) * 100), `${filledSections} of ${TOTAL_SECTIONS} sections filled — click the next Fill.`);
  }
}

// Arm the simulation: clear the form, unlock the per-section Fill buttons,
// and lock the real Submit until every section has been filled by hand.
function cancelSimulation() {
  simulated = false;
  filledSections = 0;
  pendingUploads = {};
  document.querySelectorAll('.section-fill').forEach(btn => {
    btn.disabled = true;
    btn.textContent = 'Fill';
    btn.classList.remove('filled');
  });
  const draftBtn = document.querySelector('#btn-draft');
  const submitBtn = formEl.querySelector('button[type="submit"]');
  if (draftBtn) draftBtn.disabled = false;
  if (submitBtn) submitBtn.disabled = false;
  simStart.disabled = false;
  simPersona.disabled = false;
  simMode.disabled = false;
  simSpeed.disabled = false;
  setProgress(0, 'Simulation cancelled — the form is back to manual mode.');
}

// Arm the simulation: clear the form, unlock the per-section Fill buttons,
// and lock the real Submit until every section has been filled by hand.
function resetSimulation() {
  simulated = true;
  filledSections = 0;
  pendingUploads = {};
  formEl.querySelectorAll('input, select').forEach(el => {
    if (el.type !== 'checkbox' && el.type !== 'file') el.value = '';
  });
  deliveryChecks.forEach(c => { c.checked = false; });
  document.querySelector('#s-delivery-other').style.display = 'none';
  document.querySelector('#s-ip-other-wrap').classList.add('hidden');
  renderStrands('');
  document.querySelectorAll('.upload-item').forEach(item => {
    item.querySelector('.pick-label').textContent = 'Choose file';
    const removeBtn = item.querySelector('.file-remove');
    if (removeBtn) removeBtn.hidden = true;
  });
  document.querySelectorAll('.section-fill').forEach(btn => {
    btn.disabled = false;
    btn.textContent = 'Fill';
    btn.classList.remove('filled');
  });
  const draftBtn = document.querySelector('#btn-draft');
  const submitBtn = formEl.querySelector('button[type="submit"]');
  if (draftBtn) draftBtn.disabled = true;
  if (submitBtn) submitBtn.disabled = true;
  if (simCancel) simCancel.hidden = false;
  setProgress(4, 'Simulation armed — click each section to fill it.');
}

simCancel?.addEventListener('click', () => {
  cancelSimulation();
  if (simCancel) simCancel.hidden = true;
  showToast('Simulation cancelled', 'The form is back to manual mode — you can edit and submit normally.');
});

simStart.addEventListener('click', () => {
  const persona = Store.getPersona(simPersona.value);
  simStart.disabled = true;
  simPersona.disabled = true;
  simMode.disabled = true;
  simSpeed.disabled = true;
  resetSimulation();
  showToast('Simulation started', `Filling as ${persona.label} — click each section's Fill button, then submit.`);
  if (formSections[0]) formSections[0].scrollIntoView({ behavior: 'smooth', block: 'start' });
});

// Each section header's Fill button populates just that section's fields.
// The whole header is clickable too — the chip is the visible affordance.
function armSectionFill(btn) {
  if (!simulated || btn.disabled) {
    if (!simulated) showToast('Start the simulation first', 'Click Start Simulation, then fill each section.');
    return;
  }
  const idx = Number(btn.dataset.section);
  btn.disabled = true;
  btn.textContent = 'Filling…';
  fillSection(idx).then(() => markSectionFilled(idx));
}

document.querySelectorAll('.section-fill').forEach(btn => {
  btn.addEventListener('click', event => {
    event.stopPropagation();
    armSectionFill(btn);
  });
});

// Clicking anywhere on a section header fills that section (when armed).
document.querySelectorAll('#submission-form .section-head').forEach(head => {
  head.addEventListener('click', () => {
    const btn = head.querySelector('.section-fill');
    if (btn) armSectionFill(btn);
  });
});

// Runs after the main submit handler: attach uploads, start the journey, and
// re-point the success action at the journey continuation.
formEl.addEventListener('submit', () => {
  if (!simulated) return;
  simulated = false;
  const draftBtn = document.querySelector('#btn-draft');
  const submitBtn = formEl.querySelector('button[type="submit"]');
  if (draftBtn) draftBtn.disabled = false;
  if (submitBtn) submitBtn.disabled = false;
  const id = document.querySelector('#success-app').textContent.trim();
  if (!id) return;

  Object.entries(pendingUploads).forEach(([doc, name]) => Store.attachUpload(id, doc, name));
  // The form has no category field (Enrollment Details was dissolved) — patch it from the persona.
  Store.updateApplicant(id, { category: Store.getPersona(simPersona.value).category });

  const mode = simMode.value;
  const bannerSpeed = (SPEED[simSpeed.value] || SPEED.normal).banner;
  Store.startJourney({ appId: id, persona: simPersona.value, mode, speed: bannerSpeed });

  const auto = mode === 'auto' ? '&auto=1' : '';
  successLink.href = `queue.html?journey=${encodeURIComponent(id)}${auto}`;
  successLink.textContent = mode === 'auto'
    ? 'Continue journey — watch it reach Officially Enrolled →'
    : 'Continue journey in the Enrollment Queue →';
  showToast('Simulation started', `${Store.applicants[id].name} (${id}) — the journey is saved and ready to follow.`);
});