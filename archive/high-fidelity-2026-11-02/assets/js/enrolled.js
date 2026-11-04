// Enrolled & Assigned (Enrollment 9) — end of the enrollment journey.
// Reads ?app=<APP-ID> and renders the officially-enrolled student record,
// section assignment, and confirmation stamp.
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

const appId = new URLSearchParams(location.search).get('app');
const app = appId ? Store.applicants[appId] : null;
const donePanel = document.querySelector('#done-panel');
const emptyState = document.querySelector('#empty-state');

function render() {
  if (!app) {
    emptyState.hidden = false;
    return;
  }
  donePanel.hidden = false;

  const sid = Store.recordByApp(appId);
  const rec = sid ? Store.getRecord(sid) : null;
  const section = (sid && Store.sections[sid]) || '—';

  document.querySelector('#done-sub').textContent =
    `${app.name} (${appId}) is now Enrolled${section && section !== '—' ? ` in section ${section}` : ''}. Confirmation and section change have been logged.`;
  document.querySelector('#enr-name').textContent = app.name;
  document.querySelector('#enr-meta').textContent = `${appId} · ${app.programLabel} · ${app.campus}`;
  document.querySelector('#enr-student-id').textContent = sid || '—';
  document.querySelector('#enr-status').textContent = rec && rec.status === 'enrolled' ? 'Enrolled' : 'Pending';
  document.querySelector('#enr-section').textContent = section;
  document.querySelector('#enr-category').textContent = rec ? rec.category : app.category;
  document.querySelector('#enr-ref').textContent = rec ? rec.ref : '—';
  document.querySelector('#enr-confirmed').textContent = (rec && rec.confirmedAt) || '—';
  document.querySelector('#enr-registrar').textContent = (rec && rec.confirmedBy) || '—';
  document.querySelector('#enr-confirm-line').textContent =
    `By ${(rec && rec.confirmedBy) || '—'} on ${(rec && rec.confirmedAt) || '—'}`;

  if (rec && rec.status === 'enrolled') {
    showToast('Enrollment complete', `${app.name} is officially enrolled and assigned to a section.`);
  }
}

render();
