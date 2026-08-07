// Enrollment Journey Simulation — drives the end-to-end lifecycle on top of the
// real store. Every step writes through the same Store.* APIs a human would use,
// so the journey can be paused mid-way and continued by hand at any screen.
(function () {
  const STAGES = window.Store.JOURNEY_STAGES;

  function page() { return document.body.dataset.page || ''; }

  function wait(ms) { return new Promise(res => setTimeout(res, ms)); }

  // ---- stage mutations (called from the Queue's control banner) ----
  function runStage(n) {
    const j = window.Store.getJourney();
    if (!j || !j.active) return false;
    const id = j.appId;
    const app = window.Store.applicants[id];
    if (!app) return false;
    const persona = window.Store.getPersona(j.persona);
    switch (n) {
      case 2:
        window.Store.updateApplicant(id, {
          queueStatus: 'review',
          evalStatus: persona.dup ? 'flag' : 'ready',
          flag: persona.dup ? 'dup' : ''
        });
        if (persona.dup) window.Store.flagPair(id);
        window.Store.setStage(2, 'Registrar encoded the student profile (duplicate check ran)');
        break;
      case 3:
        if (window.Store.getPair(id)) window.Store.resolvePair(id, 'merge');
        window.Store.updateApplicant(id, { evalStatus: 'ready' });
        window.Store.setStage(3, 'Duplicate review resolved — records merged');
        break;
      case 4:
        ['PSA Birth Certificate', 'Form 137', 'Certificate of Completion', 'Good Moral Certificate'].forEach(doc =>
          window.Store.setRequirement(id, doc, 'submitted'));
        window.Store.updateApplicant(id, { queueStatus: 'pending' });
        window.Store.setStage(4, 'All requirements verified — 4 of 4 submitted');
        break;
      case 5:
        window.Store.completeWeekAttendance(id);
        window.Store.setStage(5, 'First-week attendance verified — 5 of 5 present');
        break;
      case 6: {
        const sid = window.Store.createRecord(id);
        window.Store.confirmRecord(sid);
        window.Store.setStage(6, 'Enrollment confirmed — status set to Enrolled');
        break;
      }
      case 7: {
        const sec = sectionFor(id);
        const sid = window.Store.recordByApp(id);
        if (sid) window.Store.assignSection(sid, sec);
        window.Store.setStage(7, `Assigned to section ${sec} — capacity validated`);
        break;
      }
      case 8:
        window.Store.completeJourney();
        break;
      default:
        return false;
    }
    return true;
  }

  function sectionFor(appId) {
    const j = window.Store.getJourney();
    const persona = j ? window.Store.getPersona(j.persona) : null;
    if (persona && persona.section) return persona.section;
    const app = window.Store.applicants[appId];
    const label = app && app.programLabel ? app.programLabel : '';
    if (label.includes('STEM')) return 'STEM-B';
    if (label.includes('CA') || label.includes('Cookery')) return 'Cookery-1';
    return 'STEM-B';
  }

  // ---- journey banner (Enrollment Queue control room) ----
  function bannerHTML() {
    const j = window.Store.getJourney();
    if (!j || !j.active) return '';
    const app = window.Store.applicants[j.appId];
    if (!app) return '';
    const persona = window.Store.getPersona(j.persona);
    const next = window.Store.nextJourneyStage();
    const dots = STAGES.map(s => {
      const skipped = s.n === 3 && !persona.dup;
      const done = s.n <= j.stage;
      const cls = (done ? 'done ' : '') + (s.n === j.stage ? 'active ' : '') + (skipped ? 'skip' : '');
      return `<span class="jstage ${cls}" title="${s.label}"><i>${skipped ? '·' : done ? '✓' : s.n}</i><small>${s.label}</small></span>`;
    }).join('');
    const nextLabel = next === null ? 'Enrolled & Assigned' : (STAGES[next] ? STAGES[next].label : '—');
    return `<section class="journey-banner" id="journey-banner" aria-label="Enrollment journey simulation">
      <div class="jb-head">
        <div class="jb-title"><strong>🎬 Enrollment Journey Simulation</strong>
          <span class="jb-sub"><strong>${app.name}</strong> (${j.appId}) · ${persona.label} · ${j.mode === 'auto' ? 'Auto-play' : 'Step-through'}</span></div>
        <div class="jb-actions">
          <button class="button button-outline" type="button" id="jb-next">Next stage →</button>
          <button class="button button-primary" type="button" id="jb-play">▶ Play full journey</button>
          <button class="button button-secondary" type="button" id="jb-end" title="End this journey">✕ End</button>
        </div>
      </div>
      <div class="jb-pipe">${dots}</div>
      <p class="jb-note" id="jb-note">Stage ${j.stage} complete · next: <strong>${nextLabel}</strong> — ${next === null ? 'the student is ready to be marked Officially Enrolled & Assigned.' : 'run it to advance the journey.'}</p>
    </section>`;
  }

  function renderBanner() {
    const existing = document.getElementById('journey-holder');
    if (existing) existing.remove();
    const html = bannerHTML();
    if (!html) return;
    const wrap = document.createElement('div');
    wrap.id = 'journey-holder';
    wrap.innerHTML = html;
    const heading = document.querySelector('.page-heading');
    if (heading && heading.parentNode) heading.insertAdjacentElement('afterend', wrap);
    wireBanner();
  }

  function wireBanner() {
    const nextBtn = document.getElementById('jb-next');
    const playBtn = document.getElementById('jb-play');
    const endBtn = document.getElementById('jb-end');
    if (nextBtn) nextBtn.addEventListener('click', onNext);
    if (playBtn) playBtn.addEventListener('click', onPlay);
    if (endBtn) endBtn.addEventListener('click', onEnd);
  }

  function journeyUrl(id) { return `queue.html?journey=${encodeURIComponent(id)}`; }

  function onNext() {
    const j = window.Store.getJourney();
    if (!j) return;
    const n = window.Store.nextJourneyStage();
    if (n === null) {
      window.Store.completeJourney();
      location.href = `enrolled.html?app=${encodeURIComponent(j.appId)}`;
      return;
    }
    runStage(n);
    if (n === 8) {
      location.href = `enrolled.html?app=${encodeURIComponent(j.appId)}`;
      return;
    }
    const stage = STAGES[n];
    location.href = `${stage.screen}?app=${encodeURIComponent(j.appId)}`;
  }

  let playing = false;
  async function onPlay() {
    if (playing) return;
    playing = true;
    const j = window.Store.getJourney();
    if (!j) { playing = false; return; }
    const delay = Math.max(Number(j.speed) || 650, 320);
    let n = window.Store.nextJourneyStage();
    while (n !== null) {
      runStage(n);
      renderBanner();
      const note = document.getElementById('jb-note');
      if (note) note.textContent = `${STAGES[n] ? STAGES[n].label : 'Stage ' + n} complete — advancing…`;
      await wait(delay);
      n = window.Store.nextJourneyStage();
    }
    window.Store.completeJourney();
    playing = false;
    location.href = `enrolled.html?app=${encodeURIComponent(j.appId)}`;
  }

  function onEnd() {
    window.Store.cancelJourney();
    location.reload();
  }

  // Highlight the journey applicant's row + attach a ✦ marker (after any re-render).
  function markJourneyRow() {
    const j = window.Store.getJourney();
    if (!j || !j.active) return;
    const rows = [...document.querySelectorAll('#queue-body tr[data-app]')];
    const row = rows.find(r => r.dataset.app === j.appId);
    if (!row) return;
    const strong = row.querySelector('.student-cell strong');
    if (strong && !strong.querySelector('.jdot')) {
      strong.appendChild(Object.assign(document.createElement('span'), { className: 'jdot', title: 'Journey in progress', textContent: '✦' }));
    }
    row.style.background = row.style.background || '#eef6fa';
    row.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  // Compact journey chip for the mid-flow registrar screens.
  function attachChip() {
    const j = window.Store.getJourney();
    if (!j || !j.active) return;
    const app = window.Store.applicants[j.appId];
    if (!app) return;
    const stage = STAGES[j.stage];
    const next = window.Store.nextJourneyStage();
    const nextLabel = next === null ? 'Enrolled & Assigned' : (STAGES[next] ? STAGES[next].label : '—');
    const chip = document.createElement('div');
    chip.className = 'journey-chip';
    chip.innerHTML = `<span class="jchip-icon">🎬</span>
      <span class="jchip-text">Journey in progress — <strong>${app.name}</strong> · Stage ${j.stage}/9 <em>${stage ? stage.label : ''}</em></span>
      <span class="jchip-next">Next: <strong>${nextLabel}</strong></span>
      <a class="button button-outline" href="${journeyUrl(j.appId)}">Continue journey →</a>`;
    const heading = document.querySelector('.page-heading');
    if (heading && heading.parentNode) heading.insertAdjacentElement('afterend', chip);
  }

  // ---- boot per page ----
  function init() {
    const p = page();
    const j = window.Store.getJourney();
    if (p === 'queue.html') {
      renderBanner();
      if (j && j.active) {
        markJourneyRow();
        const params = new URLSearchParams(location.search);
        if (params.get('auto') === '1' && j.mode === 'auto') {
          setTimeout(onPlay, 900);
        }
      }
      // Re-apply the row marker when the list re-renders (store:change).
      document.addEventListener('store:change', markJourneyRow);
      return;
    }
    if (p === 'submission.html' || p === 'enrolled.html') return;
    if (j && j.active) attachChip();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();

  window.Sim = { runStage, sectionFor, bannerHTML, renderBanner, attachChip };
})();
