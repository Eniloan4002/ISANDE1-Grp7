// Enrolled & Assigned (Enrollment 9) — end of the enrollment journey.
// Reads ?app=<APP-ID> and renders the officially-enrolled student record,
// section assignment, confirmation stamp, and the journey timeline recap.
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
  const journey = Store.getJourney();

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

  const timeline = document.querySelector('#enr-timeline');
  const entries = (journey && journey.timeline) ? journey.timeline : [];
  const persona = journey ? Store.getPersona(journey.persona) : null;
  if (entries.length) {
    timeline.innerHTML = Store.JOURNEY_STAGES.map(s => {
      const skipped = s.n === 3 && persona && !persona.dup;
      const entry = entries.find(e => e.stage === s.n);
      if (skipped) {
        return `<li class="tl-item skip"><span class="tl-dot">·</span><span class="tl-label">${s.label}</span><span class="tl-when">—</span></li>`;
      }
      if (!entry) {
        return `<li class="tl-item"><span class="tl-dot">○</span><span class="tl-label">${s.label}</span><span class="tl-when">—</span></li>`;
      }
      return `<li class="tl-item done"><span class="tl-dot">•</span><span class="tl-label">${entry.label || s.label}</span><span class="tl-when">${entry.at}</span></li>`;
    }).join('');
  } else {
    timeline.innerHTML = `<li class="tl-item done"><span class="tl-dot">•</span><span class="tl-label">Enrollment confirmed</span><span class="tl-when">${(rec && rec.confirmedAt) || '—'}</span></li>`;
  }

  if (rec && rec.status === 'enrolled') {
    showToast('Enrollment complete', `${app.name} is officially enrolled and assigned to a section.`);
  }
}

render();
