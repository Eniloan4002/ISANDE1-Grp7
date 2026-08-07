// Enrollment Queue demo interactions — backed by the shared localStorage store.
const search = document.querySelector('#queue-search');
const status = document.querySelector('#status-filter');
const program = document.querySelector('#program-filter');
const tbody = document.querySelector('#queue-body');
const resultCount = document.querySelector('#result-count');

const STATUS_BADGE = {
  'pre-enlisted': ['Pre-Enlisted', 'blue-badge'],
  pending: ['Pending Req.', 'amber-badge'],
  review: ['For review', 'neutral-badge'],
  enrolled: ['Enrolled', 'blue-badge']
};

function renderQueue() {
  const applicants = Object.values(Store.applicants);
  tbody.innerHTML = applicants.map(app => {
    const [label, tone] = STATUS_BADGE[app.queueStatus] || STATUS_BADGE['pre-enlisted'];
    const reqClass = app.queueStatus === 'pending' || app.queueStatus === 'review' ? 'warning' : 'success';
    return `<tr data-status="${app.queueStatus}" data-program="${app.program}" data-app="${app.id}" title="Click to open in Evaluate Applications">
      <td>
        <div class="student-cell">
          <div><strong>${app.name}</strong><small>LRN ${app.lrn || '—'}</small></div>
        </div>
      </td>
      <td>${app.id}</td>
      <td>${app.submittedAt || '—'}</td>
      <td>${app.programLabel}</td>
      <td>${app.category}</td>
      <td><span class="status-pill ${reqClass}">${app.queueStatus === 'enrolled' ? 'Complete' : app.queueStatus === 'pre-enlisted' ? 'Pending' : 'Incomplete'}</span></td>
      <td><span class="badge ${tone}">${label}</span></td>
      <td class="action-cell">
        <button class="row-action" data-go="requirements.html?app=${encodeURIComponent(app.id)}" title="Open requirements checklist">Req</button>
        <button class="row-action" data-go="attendance.html?app=${encodeURIComponent(app.id)}" title="Open first-week attendance">Att</button>
        ${Store.getPair(app.id) ? `<button class="row-action" data-go="duplicate.html?app=${encodeURIComponent(app.id)}" title="Open duplicate review">Dup</button>` : ''}
        <button class="row-action" data-remove="${app.id}" title="Remove this application">Remove</button>
      </td>
    </tr>`;
  }).join('');
  filterQueue();
}

function filterQueue() {
  let visible = 0;
  const term = search.value.toLowerCase();
  [...tbody.querySelectorAll('tr')].forEach(row => {
    const show = row.textContent.toLowerCase().includes(term) &&
      (status.value === 'all' || row.dataset.status === status.value) &&
      (program.value === 'all' || row.dataset.program === program.value);
    row.hidden = !show;
    if (show) visible++;
  });
  resultCount.textContent = `Showing ${visible} of ${Object.keys(Store.applicants).length} applicants`;
}
[search, status, program].forEach(item => item.addEventListener('input', filterQueue));

// Toast helper.
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
document.querySelector('[data-alert]').addEventListener('click', () => showToast('Export prepared', 'This is a demonstration prototype.'));

// Walk-in modal: actually creates a record in the store.
const modal = document.querySelector('#walkin-modal');
document.querySelector('[data-modal-open]').addEventListener('click', () => modal.classList.add('open'));
document.querySelectorAll('.modal-close').forEach(button => button.addEventListener('click', () => modal.classList.remove('open')));
modal.addEventListener('click', event => { if (event.target === modal) modal.classList.remove('open'); });

document.querySelector('#walkin-form').addEventListener('submit', event => {
  event.preventDefault();
  const first = document.querySelector('#w-first').value.trim();
  const last = document.querySelector('#w-last').value.trim();
  const track = document.querySelector('#w-track').value;
  const strand = track === 'TVL' ? 'CA' : 'HUMSS';
  const programLabel = document.querySelector('#w-program').value === 'college' ? 'BSIT · 1st Year' : (strand + ' · Grade 11');
  const contact = document.querySelector('#w-contact').value.trim();
  const id = Store.addApplicant({
    name: `${last}, ${first}`, last, first, middle: '', contact, email: '',
    category: document.querySelector('#w-category').value, program: document.querySelector('#w-program').value,
    programLabel, track: track || 'ACADEMIC', strand: track ? strand : 'HUMSS', queueStatus: 'pre-enlisted', lrnStatus: 'No LRN'
  });
  modal.classList.remove('open');
  document.querySelector('#w-first').value = '';
  document.querySelector('#w-last').value = '';
  document.querySelector('#w-contact').value = '';
  document.querySelector('#w-track').value = '';
  renderQueue();
  showToast('Application created', `${id} — a new walk-in record was added to the queue.`);
});

// Row actions. Clicking a row opens the application in Evaluate; the action
// buttons jump straight to Requirements / Attendance / Duplicate (with that
// student pre-loaded); Remove is exempt.
tbody.addEventListener('click', event => {
  const goBtn = event.target.closest('[data-go]');
  if (goBtn) {
    location.href = goBtn.dataset.go;
    return;
  }
  const removeBtn = event.target.closest('[data-remove]');
  if (removeBtn) {
    const name = Store.applicants[removeBtn.dataset.remove]?.name || 'Application';
    Store.removeApplicant(removeBtn.dataset.remove);
    renderQueue();
    showToast('Application removed', `${name} (${removeBtn.dataset.remove}) was deleted from the queue.`);
    return;
  }
  const row = event.target.closest('tr[data-app]');
  if (!row) return;
  const app = Store.applicants[row.dataset.app];
  if (!app) return;
  location.href = 'evaluate.html?app=' + encodeURIComponent(app.id);
});

// Demo helper: reset all data to the seeded state.
const resetBtn = document.querySelector('#reset-demo');
resetBtn?.addEventListener('click', () => {
  if (confirm('Reset all demo data to its original state?')) Store.resetDemo();
});

// Listen for changes made on other tabs/pages.
document.addEventListener('store:change', renderQueue);

renderQueue();

// Return-from-Evaluate support: after saving in Evaluate Applications, the
// queue opens with ?highlight=<ID>&saved=<draft|check> — highlight that
// applicant, scroll it into view, and confirm the save outcome.
(function handleSavedReturn() {
  const params = new URLSearchParams(location.search);
  const id = params.get('highlight');
  if (!id) return;
  const saved = params.get('saved');
  const row = tbody.querySelector(`tr[data-app="${id}"]`);
  const app = Store.applicants[id];
  if (row && app) {
    row.classList.add('row-flash');
    row.style.background = '#eef6fa';
    row.scrollIntoView({ behavior: 'smooth', block: 'center' });
    if (saved === 'draft') {
      showToast('Draft saved', `${app.name} (${id}) — profile updated in the queue.`);
    } else {
      const flagged = app.evalStatus === 'flag';
      showToast(flagged ? 'Possible duplicate detected' : 'Saved & checked', `${app.name} (${id}) — ${flagged ? 'routed to Duplicate Records for review.' : 'no duplicate found. Record saved to the queue.'}`);
    }
  }
  // Remove the params so refreshing / bookmarking doesn't re-flash.
  // (replaceState is skipped on file:// where it can throw a SecurityError.)
  if (location.protocol !== 'file:') history.replaceState(null, '', location.pathname);
}());
