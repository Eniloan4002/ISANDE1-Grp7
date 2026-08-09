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

// Collects the checked delivery modalities (+ the "Other" free-text field) from
// the DOM. Shared by the Google-Forms preview and the real Submit handler so
// the two never drift apart.
function collectDelivery() {
  return {
    delivery: deliveryChecks.filter(c => c.checked).map(c => c.value),
    deliveryOther: document.querySelector('#s-delivery-other').value.trim()
  };
}

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
  const required = ['s-last', 's-first', 's-middle', 's-dob', 's-age', 's-contact', 's-house', 's-city', 's-barangay', 's-father', 's-mother', 's-guardian-contact', 's-grade', 's-track', 's-strand', 's-campus', 's-category'];
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
    ...collectDelivery(),
    program: document.querySelector('#s-grade').value.includes('11') || document.querySelector('#s-grade').value.includes('12') ? 'shs' : 'college',
    programLabel: `${document.querySelector('#s-strand').value} · ${document.querySelector('#s-grade').value}`,
    queueStatus: 'pre-enlisted'
  });
  document.querySelector('#success-app').textContent = id;
  successBox.classList.add('show');
  successBox.scrollIntoView({ behavior: 'smooth', block: 'center' });
  document.querySelector('#submission-form').classList.add('hidden');
  document.querySelector('.editor-bar').classList.add('hidden');
  previewPanel.classList.remove('show');
  previewPanel.setAttribute('aria-hidden', 'true');
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
      ['Enrollment Category', '#s-category'],
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
  const { delivery, deliveryOther } = collectDelivery();
  const picked = [...delivery];
  if (deliveryOther) picked.push('Other: ' + deliveryOther);
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
    return;
  }
  showPreview(false);
  showToast('Preview finished', 'Returned to the all-fields view — the form is ready for a real submission.');
});

// The Enrollment Submission screen is the applicant's own screen — open on the
// Google Form view by default.
showPreview(true);