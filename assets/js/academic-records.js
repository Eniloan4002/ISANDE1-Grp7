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
const viewEnrolledLink = document.querySelector('#rec-view-enrolled');
let currentRow = null;
let currentRecord = null;

// Section Assignment — selectable rooms/locations table (replaces the old
// flat <select> + hardcoded capacity table). Selection state lives here
// rather than on a form control since rows, not options, are now the input.
let selectedSection = null;
const sectionBody = document.querySelector('#rec-section-body');
const sectionScope = document.querySelector('#rec-section-scope');
const sectionShowAll = document.querySelector('#rec-section-showall');
const assignHint = document.querySelector('#rec-section-selected');
const assignBtn = document.querySelector('#btn-assign');

function updateAssignButton() {
  if (selectedSection) {
    assignBtn.disabled = false;
    assignBtn.textContent = `Assign to ${selectedSection}`;
    assignHint.textContent = `Selected: ${selectedSection}`;
  } else {
    assignBtn.disabled = true;
    assignBtn.textContent = 'Assign Student';
    assignHint.textContent = 'No section selected';
  }
}

// Renders the rooms/locations table, default-filtered to the student's own
// track/strand + campus (real sectioning is track + geography first), with a
// "Show all sections" toggle to reveal the rest. Falls back to showing every
// section if nothing matches the student's own track/campus (e.g. college
// programs that don't map to an SHS strand).
function renderSectionTable() {
  if (!sectionBody) return;
  const roster = Store.sectionRoster();
  const app = currentRecord ? Store.applicants[currentRecord.app] : null;
  const showAll = sectionShowAll.checked;
  let list = roster;
  let scopeText = 'Showing all sections';
  if (app && !showAll) {
    const scoped = roster.filter(s => s.strand === app.strand && s.campus === app.campus);
    if (scoped.length) {
      list = scoped;
      scopeText = `Showing ${app.strand} sections at ${app.campus}`;
    } else {
      scopeText = "No sections match this student's track/campus — showing all sections";
    }
  }
  sectionScope.textContent = scopeText;

  // Drop the current selection if it scrolled out of the visible (filtered) list.
  if (selectedSection && !list.some(s => s.name === selectedSection)) selectedSection = null;

  sectionBody.innerHTML = list.length ? list.map(s => `
    <tr class="sec-row${s.full ? ' is-full' : ''}${s.name === selectedSection ? ' is-selected' : ''}" data-section="${s.name}" tabindex="0">
      <td>${s.name}</td>
      <td>${s.track} <small>${s.strand}</small></td>
      <td>${s.campus}</td>
      <td>${s.building}</td>
      <td>${s.room}</td>
      <td>${s.enrolled}/${s.capacity}</td>
      <td><span class="status-pill ${s.full ? 'danger' : 'success'}">${s.full ? 'Full' : 'Open'}</span></td>
    </tr>`).join('') : '<tr><td colspan="7" style="text-align:center;color:#7a8487;padding:14px;">No sections found.</td></tr>';

  updateAssignButton();
}

sectionBody.addEventListener('click', event => {
  const row = event.target.closest('.sec-row');
  if (!row) return;
  if (row.classList.contains('is-full')) {
    showToast('Section is full', 'Choose an open section — capacity limits are enforced.');
    return;
  }
  selectedSection = row.dataset.section;
  renderSectionTable();
});
sectionShowAll.addEventListener('change', renderSectionTable);

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

  // Section Assignment table — pre-select whatever section the student is
  // already assigned to (so reopening the record shows it highlighted), and
  // reset the scope back to the student's own track/campus by default.
  selectedSection = Store.sections[row.dataset.id] || null;
  sectionShowAll.checked = false;
  renderSectionTable();

  confirmBanner.className = 'confirm-banner' + (rec.confirmable ? ' good' : '');
  confirmBanner.innerHTML = `<span>${rec.confirmable ? 'OK' : '!'}</span><div><strong>${rec.confirmable ? 'All conditions met.' : 'Action required.'}</strong> ${rec.confirmHint}</div>`;
  confirmBtn.textContent = rec.status === 'enrolled' ? 'Already Enrolled' : 'Confirm Enrollment';
  confirmBtn.disabled = rec.status === 'enrolled';
  viewEnrolledLink.hidden = rec.status !== 'enrolled';
  viewEnrolledLink.href = `enrolled.html?app=${encodeURIComponent(rec.app)}`;
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
// The dead `.includes('Full')` check is gone: a plain section name like
// "STEM-A" never contained that string, so it could never fire. Capacity is
// now enforced by Store.assignSection's own return value.
assignBtn.addEventListener('click', () => {
  if (!selectedSection || !currentRow) return;
  const assigned = Store.assignSection(currentRow.dataset.id, selectedSection);
  if (!assigned) {
    showToast('Section is full', 'Choose another section — capacity limits are enforced.');
    renderSectionTable();
    return;
  }
  currentRow.querySelector('td:nth-child(6)').textContent = selectedSection;
  showToast('Student assigned', `${currentRecord.name} assigned to ${selectedSection}. Change logged in the audit trail.`);
  renderSectionTable();
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
  viewEnrolledLink.hidden = false;
  viewEnrolledLink.href = `enrolled.html?app=${encodeURIComponent(currentRecord.app)}`;
  showToast('Enrollment confirmed', `${currentRecord.name} is now officially enrolled.`);
});

document.addEventListener('store:change', renderRows);
renderRows();

// Deep-link support: arriving from the journey (?app=<APP-ID>) opens the matching
// student record. The record is created on demand if it does not exist yet.
(function openJourneyRecord() {
  const appId = new URLSearchParams(location.search).get('app');
  if (!appId) return;
  let sid = Store.recordByApp(appId);
  if (!sid) sid = Store.createRecord(appId);
  const row = [...tbody.querySelectorAll('tr')].find(r => r.dataset.id === sid);
  if (!row) return;
  [...tbody.querySelectorAll('tr')].forEach(r => r.style.background = '');
  row.style.background = '#eaf3f8';
  openRecord(row);
  showToast('Student record opened', `${Store.getRecord(sid).name} (${sid}) — loaded from the enrollment journey.`);
  setTimeout(() => modal.scrollIntoView({ behavior: 'smooth', block: 'center' }), 60);
}());
