// Academic Records (enrollment) demo interactions — backed by the shared localStorage store.
const tbody = document.querySelector('#ar-body');
const resultCount = document.querySelector('#result-count');
const search = document.querySelector('#ar-search');
const statusFilter = document.querySelector('#ar-status');
const campusFilter = document.querySelector('#ar-campus');

const CAMPUS_KEY = { main: 'main', kapatagan: 'kapatagan', bugo: 'bugo' };

function renderRows() {
  const records = Object.entries(Store.records);
  tbody.innerHTML = records.map(([id, rec]) => {
    const campusKey = CAMPUS_KEY[rec.meta.includes('Bugo') ? 'bugo' : rec.meta.includes('Kapatagan') ? 'kapatagan' : 'main'];
    const program = rec.meta.split('·')[1] ? rec.meta.split('·')[1].trim() : '—';
    const section = Store.sections[id] || '—';
    const lrn = Store.applicants[rec.app]?.lrn || '';
    return `<tr data-status="${rec.status}" data-campus="${campusKey}" data-name="${rec.name}" data-id="${id}" data-program="${program}">
      <td>
        <div class="student-cell">
          <div><strong>${rec.name}</strong><small>LRN ${lrn || '—'}</small></div>
        </div>
      </td>
      <td>${id}</td>
      <td>${program} <small>${rec.meta.includes('Grade 11') ? 'Grade 11' : '1st Year'}</small></td>
      <td>${rec.meta.includes('Bugo') ? 'Bugo' : rec.meta.includes('Kapatagan') ? 'Kapatagan' : 'Main'}</td>
      <td><span class="badge ${rec.status === 'enrolled' ? 'blue-badge' : 'amber-badge'}">${rec.status === 'enrolled' ? 'Enrolled' : 'Pending confirm.'}</span></td>
      <td>${section}</td>
      <td class="action-cell"><button class="row-action" data-open="${id}">View</button> <button class="row-action" data-del="${id}" title="Remove record">Remove</button></td>
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
      (campusFilter.value === 'all' || row.dataset.campus === campusFilter.value);
    row.hidden = !show;
    if (show) visible++;
  });
  resultCount.textContent = `Showing ${visible} of ${Object.keys(Store.records).length} records`;
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

// Consolidated record data comes from the shared store.
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
  currentRecord = Store.getRecord(row.dataset.id);
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
  const btn = event.target.closest('[data-open], [data-del]');
  const row = event.target.closest('tr');
  if (!row) return;
  if (btn && btn.dataset.del) {
    const rec = Store.getRecord(btn.dataset.del);
    Store.removeRecord(btn.dataset.del);
    renderRows();
    showToast('Record removed', `${rec ? rec.name : 'Record'} (${btn.dataset.del}) was deleted.`);
    return;
  }
  if (!btn || !btn.dataset.open) return;
  [...tbody.querySelectorAll('tr')].forEach(r => r.style.background = '');
  row.style.background = '#eaf3f8';
  openRecord(row);
});
document.querySelectorAll('.modal-close').forEach(button => button.addEventListener('click', closeModal));
modal.addEventListener('click', event => { if (event.target === modal) closeModal(); });

// Section assignment with capacity validation — persists to the store.
document.querySelector('#btn-assign').addEventListener('click', () => {
  const select = document.querySelector('#rec-section');
  const section = select.value;
  const full = section.includes('Full');
  if (full) { showToast('Section is full', 'Choose an open section — capacity limits are enforced.'); return; }
  Store.assignSection(currentRow.dataset.id, section);
  currentRow.querySelector('td:nth-child(6)').textContent = section;
  showToast('Student assigned', `${currentRecord.name} assigned to ${section}. Change logged in the audit trail.`);
});

// Confirm enrollment (only when requirements + attendance conditions are met) — persists.
confirmBtn.addEventListener('click', () => {
  if (!currentRecord.confirmable) {
    showToast('Cannot confirm', 'Requirements are incomplete and/or attendance is not verified.');
    return;
  }
  Store.confirmRecord(currentRow.dataset.id);
  currentRow.querySelector('td:nth-child(5)').innerHTML = '<span class="badge blue-badge">Enrolled</span>';
  currentRow.dataset.status = 'enrolled';
  confirmBtn.textContent = 'Already Enrolled';
  confirmBtn.disabled = true;
  showToast('Enrollment confirmed', `${currentRecord.name} is now officially enrolled.`);
});

document.addEventListener('store:change', renderRows);
renderRows();
