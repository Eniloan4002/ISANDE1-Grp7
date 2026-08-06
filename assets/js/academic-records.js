// Academic Records (enrollment) demo interactions. No server or persistent data is used.
const rows = [...document.querySelectorAll('#ar-body tr')];
const resultCount = document.querySelector('#result-count');
const search = document.querySelector('#ar-search');
const statusFilter = document.querySelector('#ar-status');
const campusFilter = document.querySelector('#ar-campus');

function filterList() {
  let visible = 0;
  const term = search.value.toLowerCase();
  rows.forEach(row => {
    const show = row.textContent.toLowerCase().includes(term) &&
      (statusFilter.value === 'all' || row.dataset.status === statusFilter.value) &&
      (campusFilter.value === 'all' || row.dataset.campus === campusFilter.value);
    row.hidden = !show;
    if (show) visible++;
  });
  resultCount.textContent = `Showing ${visible} of 34 records`;
}
[search, statusFilter, campusFilter].forEach(item => item.addEventListener('input', filterList));

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

// Consolidated record data per student ID.
const RECORDS = {
  'STU-2026-0452': {
    name: 'Abad, Carla', initials: 'CA', meta: 'Grade 11 · ABM · Main — Iligan', status: 'pending',
    category: 'New Student — Regular', ref: 'CSH-0096', bracket: 'Bracket A',
    req: ['Complete', 'success'], reqDate: 'Aug 6, 2026',
    attend: ['present', 'present', 'present', 'present', 'present'], attendStatus: 'Verified ✓',
    confirmable: true, confirmHint: 'Requirements complete AND attendance verified. Ready to confirm enrollment.'
  },
  'STU-2026-0453': {
    name: 'Dela Cruz, Juan', initials: 'JD', meta: 'Grade 11 · HUMSS · Main — Iligan', status: 'pending',
    category: 'New Student — Regular', ref: 'CSH-0089', bracket: 'Bracket A',
    req: ['Incomplete', 'danger'], reqDate: 'Aug 3, 2026',
    attend: ['present', 'present', 'absent', 'present', 'pending'], attendStatus: 'Not yet verified',
    confirmable: false, confirmHint: 'Cannot confirm yet — requirements are incomplete and attendance is not fully verified.'
  },
  'STU-2026-0454': {
    name: 'Santos, Maria A.', initials: 'MS', meta: 'Grade 11 · STEM · Main — Iligan', status: 'enrolled',
    category: 'Transferee — Voucher (ESC)', ref: 'CSH-0091', bracket: 'Bracket B',
    req: ['Complete', 'success'], reqDate: 'Aug 6, 2026',
    attend: ['present', 'present', 'present', 'present', 'present'], attendStatus: 'Verified ✓',
    confirmable: false, confirmHint: 'Student is already officially enrolled.'
  },
  'STU-2026-0455': {
    name: 'Reyes, Pedro', initials: 'PR', meta: 'Grade 11 · TVL-Cookery · Bugo — CDO', status: 'enrolled',
    category: 'New Student — Regular', ref: 'CSH-0093', bracket: 'Bracket A',
    req: ['Complete', 'success'], reqDate: 'Aug 5, 2026',
    attend: ['present', 'present', 'present', 'absent', 'present'], attendStatus: 'Verified ✓',
    confirmable: false, confirmHint: 'Student is already officially enrolled.'
  },
  'STU-2026-0451': {
    name: 'Lim, Angela', initials: 'AL', meta: '1st Year · BSOA · Kapatagan', status: 'enrolled',
    category: 'Returnee — Regular', ref: 'CSH-0095', bracket: 'Bracket B',
    req: ['Complete', 'success'], reqDate: 'Aug 6, 2026',
    attend: ['present', 'present', 'present', 'present', 'present'], attendStatus: 'Verified ✓',
    confirmable: false, confirmHint: 'Student is already officially enrolled. Assign a section to complete sectioning.'
  }
};

const modal = document.querySelector('#record-modal');
const confirmBtn = document.querySelector('#btn-confirm');
const confirmBanner = document.querySelector('#rec-confirm');
let currentRow = null;
let currentRecord = null;

function openModal() {
  modal.setAttribute('aria-hidden', 'false');
  modal.classList.add('open');
}

function closeModal() {
  modal.setAttribute('aria-hidden', 'true');
  modal.classList.remove('open');
}

function openRecord(row) {
  currentRow = row;
  currentRecord = RECORDS[row.dataset.id];
  const rec = currentRecord;
  document.querySelector('#rec-avatar').textContent = rec.initials;
  document.querySelector('#rec-name').textContent = rec.name;
  document.querySelector('#rec-meta').textContent = `${row.dataset.id} · ${rec.meta}`;
  document.querySelector('#rec-status').innerHTML = rec.status === 'enrolled'
    ? '<span class="badge blue-badge">Enrolled</span>'
    : '<span class="badge amber-badge">Pending confirmation</span>';
  document.querySelector('#rec-category').textContent = rec.category;
  document.querySelector('#rec-ref').textContent = rec.ref;
  document.querySelector('#rec-bracket').textContent = rec.bracket;
  document.querySelector('#rec-req').innerHTML = `<span class="status-pill ${rec.req[1]}">${rec.req[0]}</span>`;
  document.querySelector('#rec-reqdate').textContent = rec.reqDate;
  document.querySelector('#rec-attend').innerHTML = rec.attend.map((d, i) =>
    `<span class="attend-chip ${d}">${['Mon', 'Tue', 'Wed', 'Thu', 'Fri'][i]}</span>`).join('');
  document.querySelector('#rec-attend-status').textContent = rec.attendStatus;

  confirmBanner.className = 'confirm-banner' + (rec.confirmable ? ' good' : '');
  confirmBanner.innerHTML = `<span>${rec.confirmable ? '✓' : '⚠'}</span><div><strong>${rec.confirmable ? 'All conditions met.' : 'Action required.'}</strong> ${rec.confirmHint}</div>`;
  confirmBtn.textContent = rec.status === 'enrolled' ? 'Already Enrolled' : 'Confirm Enrollment';
  confirmBtn.disabled = rec.status === 'enrolled';
  openModal();
}

document.querySelector('#ar-body').addEventListener('click', event => {
  const row = event.target.closest('tr');
  if (row) {
    rows.forEach(r => r.style.background = '');
    row.style.background = '#eaf3f8';
    openRecord(row);
  }
});
document.querySelectorAll('.modal-close').forEach(button => button.addEventListener('click', closeModal));
modal.addEventListener('click', event => { if (event.target === modal) closeModal(); });

// Section assignment with capacity validation.
document.querySelector('#btn-assign').addEventListener('click', () => {
  const select = document.querySelector('#rec-section');
  const section = select.value;
  const full = section.includes('Full');
  if (full) { showToast('Section is full', 'Choose an open section — capacity limits are enforced.'); return; }
  currentRow.querySelector('td:nth-child(6)').textContent = section;
  showToast('Student assigned', `${currentRecord.name} assigned to ${section}. Change logged in the audit trail.`);
});

// Confirm enrollment (only when requirements + attendance conditions are met).
confirmBtn.addEventListener('click', () => {
  if (!currentRecord.confirmable) {
    showToast('Cannot confirm', 'Requirements are incomplete and/or attendance is not verified.');
    return;
  }
  currentRow.querySelector('td:nth-child(5)').innerHTML = '<span class="badge blue-badge">Enrolled</span>';
  currentRow.dataset.status = 'enrolled';
  confirmBtn.textContent = 'Already Enrolled';
  confirmBtn.disabled = true;
  showToast('Enrollment confirmed', `${currentRecord.name} is now officially enrolled.`);
});
