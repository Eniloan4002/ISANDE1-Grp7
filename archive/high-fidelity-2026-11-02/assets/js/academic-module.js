// Academic Records module interactions backed by the shared localStorage store.
(function () {
  const app = document.querySelector('#academic-app');
  const currentPage = document.body.dataset.page;
  const params = new URLSearchParams(location.search);
  const esc = value => String(value ?? '').replace(/[&<>'"]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[char]));
  const labelize = value => String(value || '').replace(/_/g, ' ').replace(/\b\w/g, char => char.toUpperCase());
  const status = (label, tone = 'neutral') => tone === 'neutral' || tone === 'neutral-badge'
    ? `<span class="badge neutral-badge">${esc(label)}</span>`
    : `<span class="status-pill ${esc(tone)}">${esc(label)}</span>`;

  const toast = document.createElement('div');
  toast.className = 'toast';
  toast.innerHTML = '<span class="toast-icon" aria-hidden="true">&#10003;</span><div><strong></strong><small></small></div><button class="toast-dismiss" type="button" aria-label="Dismiss notification">&#10005;</button>';
  document.body.append(toast);
  let toastTimer;
  function showToast(title, detail) {
    toast.querySelector('strong').textContent = title;
    toast.querySelector('small').textContent = detail;
    toast.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toast.classList.remove('show'), 4200);
  }
  toast.querySelector('button').addEventListener('click', () => toast.classList.remove('show'));

  function pageLayout({ breadcrumb, eyebrow, title, description, action = '', content }) {
    app.innerHTML = `
      <div class="breadcrumb">${breadcrumb}</div>
      <section class="page-heading"><div><p class="eyebrow">${esc(eyebrow)}</p><h1>${esc(title)}</h1><p>${esc(description)}</p></div>${action}</section>
      <div class="academic-stack">${content}</div>`;
  }

  function requirementFor(studentId) { return Store.requirementSummary(studentId); }
  function standingFor(studentId) { return Store.academicStanding(studentId); }
  function studentFromQuery() {
    const id = params.get('student');
    return Store.academic.students[id] ? id : Object.keys(Store.academic.students)[0];
  }
  function classFromQuery() {
    const id = params.get('class');
    return Store.academic.classes[id] ? id : Object.keys(Store.academic.classes)[0];
  }
  function correctionFromQuery() {
    const id = params.get('request');
    return Store.academic.corrections[id] ? id : Object.keys(Store.academic.corrections)[0];
  }
  function incompleteFromQuery() {
    const id = params.get('case');
    return Store.academic.incompleteCases[id] ? id : null;
  }
  function enrollmentStatus(student) {
    const applicant = Store.applicants[student.appId];
    const value = applicant?.queueStatus || 'pending';
    if (value === 'enrolled') return { label: 'Enrolled', tone: 'success' };
    if (value === 'pre-enlisted') return { label: 'Pre-Enlisted', tone: 'warning' };
    return { label: labelize(value), tone: 'warning' };
  }

  function bindTableSearch(inputId, rowSelector, values) {
    const input = document.querySelector(inputId);
    if (!input) return;
    input.addEventListener('input', () => {
      const term = input.value.trim().toLowerCase();
      document.querySelectorAll(rowSelector).forEach(row => {
        row.style.display = !term || values(row).toLowerCase().includes(term) ? '' : 'none';
      });
    });
  }

  // The adviser portal is signed in as Nicole Chua, so the roster is limited to
  // her own advisory students. Without this the page would list the whole
  // school and title itself with every adviser's name at once.
  const SIGNED_IN_ADVISER = 'Nicole Chua';

  function renderAdvisory() {
    const students = Object.entries(Store.academic.students)
      .filter(([, student]) => student.adviser === SIGNED_IN_ADVISER);
    const atRisk = students.filter(([id]) => standingFor(id).label === 'At risk').length;
    const missing = students.filter(([id]) => requirementFor(id).label !== 'Complete').length;
    const good = students.filter(([id]) => standingFor(id).label === 'Good standing').length;
    const sections = [...new Set(students.map(([, student]) => student.section))];
    const advisers = [...new Set(students.map(([, student]) => student.adviser))];
    const rosterTitle = sections.length === 1 ? `${sections[0]} Student Roster` : 'Assigned Student Roster';
    const rosterSubtitle = `School Year 2026–2027 · Adviser: ${advisers.join(', ')}`;
    const sectionSummary = sections.length ? sections.map(section => `Grade 11 · ${section}`).join(' · ') : 'Current roster';
    const rows = students.map(([id, student]) => {
      const req = requirementFor(id);
      const standing = standingFor(id);
      const enrollment = enrollmentStatus(student);
      const note = (Store.academic.adviserNotes[id] || [])[0];
      return `<tr data-search="${esc(`${student.name} ${id} ${req.label} ${standing.label}`)}">
        <td><div class="student-cell"><div><strong>${esc(student.name)}</strong><small>${esc(id)} · LRN ${esc(student.lrn)}</small></div></div></td>
        <td>${status(enrollment.label, enrollment.tone)}<small>Enrollment Management</small></td>
        <td>${status(req.label, req.tone)}<small>${esc(req.detail)}</small></td>
        <td>${status(standing.label, standing.tone)}<small>${esc(standing.detail)}</small></td>
        <td>${note ? `${esc(note.type)}<small>${esc(note.adviser)} · ${esc(note.when)}</small>` : 'None<small>No adviser-authored flags</small>'}</td>
        <td class="action-cell"><a class="row-action" href="class-records.html?student=${encodeURIComponent(id)}">View</a></td>
      </tr>`;
    }).join('');
    pageLayout({
      breadcrumb: '<a href="advisory.html">Academic Records</a><span>/</span><span>Advisory Class</span>', eyebrow: 'Teacher Adviser', title: 'Advisory Class', description: 'Monitor synchronized enrollment, requirements, and academic status for your assigned section.', action: '<span class="prototype-label">Policy-dependent demo</span>',
      content: `
        <section class="academic-stats"><article class="academic-stat"><strong>${students.length}</strong><span>Assigned students</span><small>${esc(sectionSummary)}</small></article><article class="academic-stat warning"><strong>${missing}</strong><span>Requirements follow-up</span><small>From enrollment checklists</small></article><article class="academic-stat danger"><strong>${atRisk}</strong><span>At academic risk</span><small>Threshold: ${Store.academic.policy.passingGrade}</small></article><article class="academic-stat success"><strong>${good}</strong><span>Good standing</span><small>Calculated from current grades</small></article></section>
        <section class="module-note"><strong>Status sources:</strong> requirements come from Enrollment Management; academic standing is calculated from component scores; adviser flags retain their author and timestamp.</section>
        <section class="content-panel"><div class="panel-header"><div><h2>${esc(rosterTitle)}</h2><p>${esc(rosterSubtitle)}</p></div></div><div class="toolbar"><label class="search-control"><span>Search</span><input id="advisory-search" type="search" placeholder="Student name / ID"></label></div><div class="table-wrap"><table><thead><tr><th>Student</th><th>Enrollment</th><th>Requirements</th><th>Academic standing</th><th>Latest adviser flag</th><th>Record</th></tr></thead><tbody id="advisory-body">${rows}</tbody></table></div><div class="table-footer"><span>${students.length} assigned students</span><span>Core records remain read-only</span></div></section>`
    });
    bindTableSearch('#advisory-search', '#advisory-body tr', row => row.dataset.search || '');
  }

  function historyTable(studentId) {
    const rows = Store.getAcademicHistory(studentId).map(item => `<tr><td>${esc(item.subject)}<small>${esc(item.schoolYear)}</small></td><td>${item.quarters.q1 ?? '—'}</td><td>${item.quarters.q2 ?? '—'}</td><td>${item.quarters.q3 ?? '—'}</td><td>${item.quarters.q4 ?? '—'}</td><td>${item.finalGrade ?? '<span class="pending-value">Pending</span>'}</td></tr>`).join('');
    return `<div class="table-wrap"><table class="quarter-table"><thead><tr><th>Subject</th><th>Quarter 1</th><th>Quarter 2</th><th>Quarter 3</th><th>Quarter 4</th><th>Final Grade</th></tr></thead><tbody>${rows}</tbody></table></div>`;
  }

  function renderClassRecords() {
    const studentId = studentFromQuery();
    const student = Store.getAcademicStudent(studentId);
    const req = requirementFor(studentId);
    const standing = standingFor(studentId);
    const enrollment = enrollmentStatus(student);
    const notes = (Store.academic.adviserNotes[studentId] || []).slice(0, 3);
    pageLayout({
      breadcrumb: '<a href="advisory.html">Advisory Class</a><span>/</span><span>Student Class Record</span>', eyebrow: 'Teacher Adviser', title: 'Student Class Record', description: 'A synchronized, read-only view of the selected student’s current records.', action: '<a class="button button-outline" href="advisory.html">Back to roster</a>',
      content: `
        <section class="identity-card"><span class="identity-mark">${esc(student.initials)}</span><div><h2>${esc(student.name)}</h2><p>${esc(studentId)} · LRN ${esc(student.lrn)}</p><small>${esc(student.gradeLevel)} · ${esc(student.section)} · ${esc(student.campus)}</small></div><div class="identity-actions">${status(enrollment.label, enrollment.tone)}<small>Adviser: ${esc(student.adviser)}</small></div></section>
        <section class="module-note"><strong>Read-only access:</strong> core enrollment, requirement, and grade data come from their owning workflows. Adviser notes are maintained separately.</section>
        <section class="content-panel"><div class="panel-header"><div><h2>Enrollment and Requirements</h2><p>Retrieved from the shared enrollment checklist</p></div><span class="source-chip">Registrar data</span></div><div class="panel-body"><dl class="definition-grid"><div><dt>Enrollment</dt><dd>${esc(enrollment.label)}</dd></div><div><dt>Requirements</dt><dd>${esc(req.label)}</dd></div><div><dt>Checklist detail</dt><dd>${esc(req.detail)}</dd></div><div><dt>Academic standing</dt><dd>${esc(standing.label)}</dd></div><div><dt>Standing source</dt><dd>${esc(standing.detail)}</dd></div><div><dt>Passing threshold</dt><dd>${Store.academic.policy.passingGrade} · demo policy</dd></div></dl></div></section>
        <section class="content-panel"><div class="panel-header"><div><h2>Quarterly Academic Record</h2><p>Subjects by quarter with final-grade column</p></div><span class="source-chip calculated">Calculated grade data</span></div><div class="policy-callout warning"><strong>Demo policy:</strong> component weights and the passing threshold remain subject to school confirmation.</div>${historyTable(studentId)}</section>
        <div class="academic-grid equal"><section class="content-panel"><div class="panel-header"><div><h2>Section History</h2><p>Registrar-maintained assignments</p></div></div><div class="panel-body"><ul class="audit-list">${student.sectionHistory.map(item => `<li><strong>${esc(item.label)}</strong><span>${esc(item.detail)}</span></li>`).join('')}</ul></div></section><section class="content-panel"><div class="panel-header"><div><h2>Latest Adviser Activity</h2><p>Authored notes and flags</p></div><span class="source-chip authored">Adviser-authored</span></div><div class="panel-body"><ul class="audit-list">${notes.length ? notes.map(note => `<li><strong>${esc(note.type)}</strong><span>${esc(note.text)}</span><small>${esc(note.adviser)} · ${esc(note.when)}</small></li>`).join('') : '<li><strong>No adviser activity</strong></li>'}</ul><a class="button button-outline" href="student-monitoring.html?student=${encodeURIComponent(studentId)}">Open monitoring history</a></div></section></div>`
    });
  }

  function renderStudentMonitoring() {
    const studentId = studentFromQuery();
    const student = Store.getAcademicStudent(studentId);
    const notes = Store.academic.adviserNotes[studentId] || [];
    const req = requirementFor(studentId);
    const standing = standingFor(studentId);
    const enrollment = enrollmentStatus(student);
    pageLayout({
      breadcrumb: '<a href="advisory.html">Academic Records</a><span>/</span><span>Student Monitoring</span>', eyebrow: 'Teacher Adviser', title: 'Student Monitoring', description: 'Record accountable adviser notes and flags for the selected student.', action: `<a class="button button-outline" href="class-records.html?student=${encodeURIComponent(studentId)}">Student record</a>`,
      content: `
        <section class="identity-card"><span class="identity-mark">${esc(student.initials)}</span><div><h2>${esc(student.name)}</h2><p>${esc(studentId)} · ${esc(student.gradeLevel)} ${esc(student.section)}</p><small>Selected from your advisory roster</small></div><div class="identity-actions">${status(`${notes.filter(note => note.status === 'Active').length} active flags`, notes.some(note => note.status === 'Active') ? 'warning' : 'success')}<small>Adviser: ${esc(student.adviser)}</small></div></section>
        <section class="module-note"><strong>Accountability:</strong> every saved note records its responsible adviser and timestamp. Core statuses remain read-only.</section>
        <div class="academic-grid"><section class="content-panel"><div class="panel-header"><div><h2>Monitoring History</h2><p>Newest adviser activity first</p></div><span class="source-chip authored">Adviser-authored</span></div><div class="panel-body"><ul class="timeline">${notes.length ? notes.map((note, index) => `<li class="${index === 0 && note.status === 'Active' ? 'current' : note.status === 'Resolved' ? 'complete' : ''}"><span class="timeline-marker"></span><strong>${esc(note.type)} · ${esc(note.status)}</strong><small>${esc(note.text)}<br>${esc(note.adviser)} · ${esc(note.when)}</small></li>`).join('') : '<li><span class="timeline-marker"></span><strong>No notes recorded</strong></li>'}</ul></div></section><section class="content-panel"><div class="panel-header"><div><h2>New Adviser Note</h2><p>Saves to the shared academic record</p></div></div><form id="adviser-note-form" class="panel-body"><div class="field-grid"><label>Note type<select id="note-type"><option>Attendance follow-up</option><option>Academic follow-up</option><option>Requirements reminder</option><option>Parent contacted</option></select></label><label>Priority<select id="note-priority"><option>Standard</option><option>High</option><option>Urgent</option></select></label><label class="span-two">Note<textarea id="note-text" required placeholder="Record a concise observation or follow-up action."></textarea></label><label>Flag status<select id="note-status"><option>Active</option><option>Resolved</option></select></label><label>Responsible adviser<input readonly value="Nicole Chua"></label></div><div class="modal-actions"><button class="button button-secondary" type="reset">Clear</button><button class="button button-primary" type="submit">Save note</button></div></form></section></div>
        <section class="content-panel"><div class="panel-header"><div><h2>Related Statuses</h2><p>Synchronized reference data</p></div></div><div class="table-wrap"><table><thead><tr><th>Status</th><th>Current value</th><th>Source</th><th>Adviser access</th></tr></thead><tbody><tr><td>Enrollment</td><td>${status(enrollment.label, enrollment.tone)}</td><td>Enrollment Management</td><td>View only</td></tr><tr><td>Requirements</td><td>${status(req.label, req.tone)}</td><td>Enrollment checklist</td><td>View only</td></tr><tr><td>Academic standing</td><td>${status(standing.label, standing.tone)}</td><td>Configured grade rules</td><td>View only</td></tr></tbody></table></div></section>`
    });
    document.querySelector('#adviser-note-form').addEventListener('submit', event => {
      event.preventDefault();
      const text = document.querySelector('#note-text').value.trim();
      if (!text) return;
      Store.addAdviserNote(studentId, { type: document.querySelector('#note-type').value, priority: document.querySelector('#note-priority').value, text, status: document.querySelector('#note-status').value });
      showToast('Adviser note saved', `${student.name} · author and timestamp recorded.`);
    });
  }

  function classStatusTone(value) { return value === 'finalized' || value === 'submitted' ? 'success' : value === 'draft' ? 'warning' : 'neutral'; }
  function renderClassList() {
    const classes = Object.values(Store.getAcademicClasses()).filter(item => item.teacher === 'Michael Reyes');
    const open = classes.filter(item => Store.getAcademicPeriod(item.periodId).status === 'open').length;
    const submitted = classes.filter(item => item.status === 'submitted').length;
    const finalized = classes.filter(item => item.status === 'finalized').length;
    pageLayout({
      breadcrumb: '<a href="class-list.html">Academic Records</a><span>/</span><span>Assigned Classes</span>', eyebrow: 'Subject Teacher', title: 'Assigned Classes', description: 'Access only the subjects and class sections assigned to your account.', action: '<span class="prototype-label">LocalStorage-backed demo</span>',
      content: `<section class="academic-stats"><article class="academic-stat"><strong>${classes.length}</strong><span>Assigned classes</span><small>Michael Reyes</small></article><article class="academic-stat success"><strong>${open}</strong><span>Period open</span><small>Teacher editing allowed</small></article><article class="academic-stat warning"><strong>${submitted}</strong><span>Submitted classes</span><small>Awaiting finalization</small></article><article class="academic-stat"><strong>${finalized}</strong><span>Finalized classes</span><small>Corrections require approval</small></article></section>
        <section class="policy-callout warning"><strong>Submission-window rule:</strong> grades remain editable while the period is open. Finalization locks score entry and enables correction requests.</section>
        <section class="content-panel"><div class="panel-header"><div><h2>My Classes</h2><p>School Year 2026–2027</p></div></div><div class="panel-body"><div class="class-card-grid">${classes.map(item => { const period = Store.getAcademicPeriod(item.periodId); return `<a class="class-card" href="grade-entry.html?class=${encodeURIComponent(item.id)}">${status(labelize(item.status), classStatusTone(item.status))}<h3>${esc(item.subject)}</h3><p>${esc(item.gradeLevel)} · ${esc(item.section)} · ${esc(item.room)}</p><div class="class-card-meta"><span>${item.studentIds.length} students</span><span>${esc(period.label)} · ${esc(period.status)}</span></div></a>`; }).join('')}</div></div></section>
        <section class="content-panel"><div class="panel-header"><div><h2>Grading Schedule</h2><p>Access state for each grading cycle</p></div></div><div class="table-wrap"><table><thead><tr><th>School year</th><th>Period</th><th>Deadline</th><th>Access</th><th>Finalized by</th></tr></thead><tbody>${Object.values(Store.academic.periods).map(period => `<tr><td>${esc(period.schoolYear)}</td><td>${esc(period.label)}</td><td>${esc(period.deadline)}</td><td>${status(labelize(period.status), period.status === 'finalized' ? 'success' : 'warning')}</td><td>${esc(period.finalizedBy || '—')}</td></tr>`).join('')}</tbody></table></div></section>`
    });
  }

  function renderGradeEntry() {
    const classId = classFromQuery();
    const academicClass = Store.getAcademicClass(classId);
    const period = Store.getAcademicPeriod(academicClass.periodId);
    const locked = period.status === 'finalized';
    const policy = Store.academic.policy;
    const records = Store.academic.grades[classId] || {};
    const rows = academicClass.studentIds.map(studentId => {
      const student = Store.getAcademicStudent(studentId);
      const record = records[studentId];
      const result = Store.calculateAcademicGrade(classId, studentId);
      return `<tr data-student="${esc(studentId)}"><td><div class="student-cell"><div><strong>${esc(student.name)}</strong><small>${esc(studentId)}</small></div></div></td>${policy.assessments.map(assessment => `<td><input class="score-input" data-assessment="${esc(assessment.key)}" type="number" min="0" max="${assessment.max}" value="${record.scores[assessment.key] ?? ''}" ${locked ? 'disabled' : ''} aria-label="${esc(student.name)} ${esc(assessment.label)}"><small>of ${assessment.max}</small></td>`).join('')}<td class="computed-grade">${result.complete ? result.grade : '<span class="pending-value">Incomplete</span>'}</td><td>${status(result.complete ? labelize(record.status) : 'Missing score', result.complete ? classStatusTone(record.status) : 'warning')}</td></tr>`;
    }).join('');
    pageLayout({
      breadcrumb: '<a href="class-list.html">Assigned Classes</a><span>/</span><span>Grade Entry</span>', eyebrow: 'Subject Teacher', title: 'Grade Entry', description: 'Configure demo weights, encode scores, save drafts, and submit class grades.', action: status(locked ? 'Finalized · read-only' : `${period.label} window open`, locked ? 'success' : 'success'),
      content: `<section class="identity-card"><span class="identity-mark">${esc(academicClass.subject.split(' ').map(word => word[0]).join('').slice(0, 2))}</span><div><h2>${esc(academicClass.subject)}</h2><p>${esc(academicClass.gradeLevel)} · ${esc(academicClass.section)} · ${academicClass.studentIds.length} students</p><small>Assigned teacher: ${esc(academicClass.teacher)} · ${esc(period.schoolYear)}</small></div><div class="identity-actions">${status(labelize(academicClass.status), classStatusTone(academicClass.status))}<small>Deadline: ${esc(period.deadline)}</small></div></section>
        <section class="policy-callout warning"><strong>Demo grading policy:</strong> the 40/50/10 weights and passing threshold of ${policy.passingGrade} must be confirmed with the school before production use.</section>
        <div class="academic-grid equal"><section class="content-panel"><div class="panel-header"><div><h2>Component Configuration</h2><p>Weights must total 100%</p></div><span class="badge neutral-badge">Demo policy</span></div><form id="policy-form" class="panel-body"><div class="field-grid three">${policy.components.map(component => `<label>${esc(component.label)}<input class="weight-input" data-component="${esc(component.key)}" type="number" min="0" max="100" value="${component.weight}" ${locked ? 'disabled' : ''}></label>`).join('')}</div><div class="modal-actions"><span id="weight-total">Total: ${policy.components.reduce((sum, item) => sum + item.weight, 0)}%</span><button class="button button-secondary" type="submit" ${locked ? 'disabled' : ''}>Save weights</button></div></form></section><section class="content-panel"><div class="panel-header"><div><h2>Submission Details</h2><p>Authorized class and grading window</p></div></div><div class="panel-body"><dl class="definition-grid"><div><dt>Grading period</dt><dd>${esc(period.label)}</dd></div><div><dt>Access</dt><dd>${esc(labelize(period.status))}</dd></div><div><dt>Class status</dt><dd>${esc(labelize(academicClass.status))}</dd></div><div><dt>Teacher</dt><dd>${esc(academicClass.teacher)}</dd></div><div><dt>Submitted</dt><dd>${esc(academicClass.submittedAt || 'Not yet')}</dd></div><div><dt>Record owner</dt><dd>Academic Records</dd></div></dl></div></section></div>
        <section class="content-panel"><div class="panel-header"><div><h2>Student Score Sheet</h2><p>Quarter grade is calculated from configured component weights</p></div><span class="prototype-label">Persistent demo data</span></div><div class="table-wrap"><table class="grade-table"><thead><tr><th>Student</th>${policy.assessments.map(item => `<th>${esc(item.label)}</th>`).join('')}<th>Quarter Grade</th><th>Status</th></tr></thead><tbody id="score-body">${rows}</tbody></table></div><div class="table-footer"><span>${academicClass.studentIds.length} students</span><div><button id="save-grades" class="button button-secondary" type="button" ${locked ? 'disabled' : ''}>Save draft</button> <button id="submit-grades" class="button button-primary" type="button" ${locked ? 'disabled' : ''}>Submit grades</button></div></div></section>`
    });
    function collectScores() {
      const updates = {};
      document.querySelectorAll('#score-body tr').forEach(row => {
        updates[row.dataset.student] = {};
        row.querySelectorAll('.score-input').forEach(input => { updates[row.dataset.student][input.dataset.assessment] = input.value; });
      });
      return updates;
    }
    document.querySelector('#policy-form').addEventListener('submit', event => {
      event.preventDefault();
      const weights = {};
      document.querySelectorAll('.weight-input').forEach(input => { weights[input.dataset.component] = input.value; });
      if (!Store.updateAcademicPolicy(weights)) return showToast('Weights not saved', 'Component weights must be valid and total exactly 100%.');
      showToast('Demo policy saved', 'All quarter grades were recalculated from the updated weights.');
    });
    document.querySelector('#save-grades').addEventListener('click', () => {
      if (!Store.saveClassScores(classId, collectScores())) return showToast('Grades locked', 'This grading period has already been finalized.');
      showToast('Draft saved', `${academicClass.subject} scores persist across pages and reloads.`);
    });
    document.querySelector('#submit-grades').addEventListener('click', () => {
      Store.saveClassScores(classId, collectScores());
      const result = Store.submitClassGrades(classId);
      if (!result.ok) return showToast('Submission blocked', 'This grading period has already been finalized.');
      showToast('Grades submitted', result.incomplete ? `${result.incomplete} incomplete record(s) were sent to registrar monitoring.` : 'All student grades are complete.');
    });
  }

  function correctionTargets() {
    const targets = [];
    Object.entries(Store.academic.history).forEach(([studentId, rows]) => rows.forEach(row => {
      if (row.schoolYear !== '2025–2026' || row.quarters.q4 === null) return;
      targets.push({ studentId, student: Store.getAcademicStudent(studentId), subject: row.subject, schoolYear: row.schoolYear, quarterKey: 'q4', periodId: 'SY2025-Q4', currentGrade: row.quarters.q4 });
    }));
    return targets;
  }
  function correctionStatusLabel(value) { return ({ principal_review: 'Principal review', registrar_review: 'Registrar review', approved: 'Approved · awaiting encoding', encoded: 'Encoded', rejected: 'Rejected' })[value] || labelize(value); }

  function renderCorrectionTimeline(request) {
    if (!request) return '<p>No correction request selected.</p>';
    const steps = [
      { label: 'Submitted by subject teacher', done: true },
      { label: 'Principal review', done: ['registrar_review', 'approved', 'encoded'].includes(request.status), current: request.status === 'principal_review' },
      { label: 'Registrar approval', done: ['approved', 'encoded'].includes(request.status), current: request.status === 'registrar_review' },
      { label: 'Registrar encoding', done: request.status === 'encoded', current: request.status === 'approved' }
    ];
    return `<ul class="timeline">${steps.map(step => `<li class="${step.done ? 'complete' : step.current ? 'current' : ''}"><span class="timeline-marker"></span><strong>${esc(step.label)}</strong><small>${step.done ? 'Completed' : step.current ? 'Current step' : 'Pending previous approval'}</small></li>`).join('')}</ul>`;
  }

  function renderProgressReports() {
    const targets = correctionTargets();
    const requests = Object.values(Store.getCorrections()).sort((a, b) => b.id.localeCompare(a.id));
    const selected = requests.find(item => item.id === correctionFromQuery()) || requests[0];
    pageLayout({
      breadcrumb: '<a href="class-list.html">Academic Records</a><span>/</span><span>Grade Corrections</span>', eyebrow: 'Subject Teacher', title: 'Grade Corrections', description: 'Request a traceable correction for a finalized grade.', action: '<span class="prototype-label">Principal + Registrar workflow</span>',
      content: `<section class="policy-callout warning"><strong>Approval chain:</strong> Subject Teacher → Principal Review → Registrar Approval → Registrar Encoding. Finalized grades cannot be edited directly.</section>
        <div class="academic-grid"><section class="content-panel"><div class="panel-header"><div><h2>New Correction Request</h2><p>For finalized grades only</p></div></div><form id="correction-form" class="panel-body"><div class="field-grid"><label class="span-two">Finalized record<select id="correction-target">${targets.map((item, index) => `<option value="${index}">${esc(item.student.name)} · ${esc(item.subject)} · Q4 ${esc(item.schoolYear)} · ${item.currentGrade}</option>`).join('')}</select></label><label>Proposed corrected grade<input id="proposed-grade" type="number" min="0" max="100" required></label><label>Reason<select id="correction-reason"><option>Completed requirement</option><option>Computation error</option><option>Encoding error</option></select></label><label class="span-two">Explanation<textarea id="correction-explanation" required placeholder="Explain why the finalized grade must change."></textarea></label><label class="span-two">Supporting document<input id="correction-file" type="file" required><small>Only filename and metadata are stored in this prototype.</small></label></div><div class="modal-actions"><button class="button button-secondary" type="reset">Clear</button><button class="button button-primary" type="submit">Submit request</button></div></form></section><section class="content-panel"><div class="panel-header"><div><h2>${selected ? esc(selected.id) : 'Request timeline'}</h2><p>${selected ? esc(correctionStatusLabel(selected.status)) : 'No request selected'}</p></div>${selected ? status(correctionStatusLabel(selected.status), selected.status === 'rejected' ? 'danger' : selected.status === 'encoded' ? 'success' : 'warning') : ''}</div><div class="panel-body">${renderCorrectionTimeline(selected)}</div></section></div>
        <section class="content-panel"><div class="panel-header"><div><h2>My Correction Requests</h2><p>Live status for every finalized-grade request</p></div></div><div class="table-wrap"><table><thead><tr><th>Request</th><th>Student</th><th>Subject / period</th><th>Grade change</th><th>Reason</th><th>Status</th><th>Submitted</th></tr></thead><tbody>${requests.map(item => `<tr><td><a class="row-action" href="progress-reports.html?request=${encodeURIComponent(item.id)}">${esc(item.id)}</a></td><td>${esc(Store.getAcademicStudent(item.studentId).name)}<small>${esc(item.studentId)}</small></td><td>${esc(item.subject)}<small>${esc(item.schoolYear)} · ${esc(item.quarterKey.toUpperCase())}</small></td><td>${item.currentGrade} → ${item.proposedGrade}</td><td>${esc(item.reason)}</td><td>${status(correctionStatusLabel(item.status), item.status === 'rejected' ? 'danger' : item.status === 'encoded' ? 'success' : 'warning')}</td><td>${esc(item.submittedAt)}</td></tr>`).join('')}</tbody></table></div></section>`
    });
    document.querySelector('#correction-form').addEventListener('submit', event => {
      event.preventDefault();
      const target = targets[Number(document.querySelector('#correction-target').value)];
      const file = document.querySelector('#correction-file').files[0];
      if (!target || !file) return;
      const id = Store.submitCorrection({ ...target, proposedGrade: document.querySelector('#proposed-grade').value, reason: document.querySelector('#correction-reason').value, explanation: document.querySelector('#correction-explanation').value.trim(), attachment: { name: file.name, type: file.type, size: file.size } });
      if (!id) return showToast('Request not submitted', 'Choose a valid finalized grade record.');
      showToast('Correction request submitted', `${id} is awaiting Principal review.`);
    });
  }

  function renderRegistrarRecords() {
    const studentId = studentFromQuery();
    const selected = Store.getAcademicStudent(studentId);
    const students = Object.entries(Store.academic.students);
    const incomplete = Object.values(Store.getIncompleteCases()).filter(item => item.studentId === studentId && item.status !== 'resolved');
    const corrections = Object.values(Store.getCorrections()).filter(item => item.studentId === studentId);
    const requirements = requirementFor(studentId);
    const generationDate = new Date().toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' });
    pageLayout({
      breadcrumb: '<a href="registrar-records.html">Academic Records</a><span>/</span><span>Academic History</span>', eyebrow: 'Registrar Staff', title: 'Student Academic History', description: 'Search and review consolidated grades, sections, incomplete records, and correction references.', action: '<button id="print-history" class="button button-primary" type="button">Print / Save PDF</button>',
      content: `
        <div class="academic-history-screen">
          <div class="academic-grid"><section class="content-panel"><div class="panel-header"><div><h2>Student Directory</h2><p>Select a student to review</p></div></div><div class="toolbar"><label class="search-control"><span>Search</span><input id="registrar-search" type="search" placeholder="Name / student ID"></label></div><div class="table-wrap"><table><thead><tr><th>Student</th><th>Section</th><th>Standing</th><th>Incomplete</th><th>Open</th></tr></thead><tbody id="registrar-students">${students.map(([id, student]) => { const standing = standingFor(id); const count = Object.values(Store.getIncompleteCases()).filter(item => item.studentId === id && item.status !== 'resolved').length; return `<tr data-search="${esc(`${student.name} ${id}`)}"><td>${esc(student.name)}<small>${esc(id)}</small></td><td>${esc(student.section)}</td><td>${status(standing.label, standing.tone)}</td><td>${count}</td><td><a class="row-action" href="registrar-records.html?student=${encodeURIComponent(id)}">View</a></td></tr>`; }).join('')}</tbody></table></div></section><section class="content-panel"><div class="panel-header"><div><h2>${esc(selected.name)}</h2><p>${esc(studentId)} · LRN ${esc(selected.lrn)}</p></div>${status(standingFor(studentId).label, standingFor(studentId).tone)}</div><div class="panel-body"><dl class="definition-grid"><div><dt>Section</dt><dd>${esc(selected.section)}</dd></div><div><dt>Adviser</dt><dd>${esc(selected.adviser)}</dd></div><div><dt>Requirements</dt><dd>${esc(requirements.label)}</dd></div><div><dt>Open incompletes</dt><dd>${incomplete.length}</dd></div><div><dt>Corrections</dt><dd>${corrections.length}</dd></div><div><dt>Campus</dt><dd>${esc(selected.campus)}</dd></div></dl></div></section></div>
          <section class="content-panel"><div class="panel-header"><div><h2>Quarterly Academic Record</h2><p>Official layout: subject rows and Quarter 1–4 columns</p></div></div>${historyTable(studentId)}</section>
          <div class="academic-grid equal"><section class="content-panel"><div class="panel-header"><div><h2>Section History</h2><p>Registrar-maintained assignments</p></div></div><div class="panel-body"><ul class="audit-list">${selected.sectionHistory.map(item => `<li><strong>${esc(item.label)}</strong><span>${esc(item.detail)}</span></li>`).join('')}</ul></div></section><section class="content-panel"><div class="panel-header"><div><h2>Record References</h2><p>Incomplete cases and corrections</p></div></div><div class="panel-body"><ul class="audit-list">${incomplete.map(item => `<li><strong>${esc(item.id)} · ${esc(labelize(item.status))}</strong><span>${esc(Store.getAcademicClass(item.classId).subject)}</span></li>`).join('') || '<li><strong>No unresolved incomplete grades</strong></li>'}${corrections.map(item => `<li><strong>${esc(item.id)} · ${esc(correctionStatusLabel(item.status))}</strong><span>${esc(item.subject)} · ${item.currentGrade} → ${item.proposedGrade}</span></li>`).join('')}</ul></div></section></div>
        </div>
        <article class="academic-history-print" aria-hidden="true">
          <header class="academic-history-print-header">
            <p class="academic-history-institution">Iligan Computer Institute / ICI Local 2.0</p>
            <h1>Student Academic History</h1>
            <p><strong>Generation date:</strong> ${esc(generationDate)}</p>
          </header>
          <section class="academic-history-print-section">
            <h2>Student Summary</h2>
            <dl class="academic-history-print-summary">
              <div><dt>Student name</dt><dd>${esc(selected.name)}</dd></div>
              <div><dt>Student ID</dt><dd>${esc(studentId)}</dd></div>
              <div><dt>LRN</dt><dd>${esc(selected.lrn)}</dd></div>
              <div><dt>Grade level</dt><dd>${esc(selected.gradeLevel)}</dd></div>
              <div><dt>Section</dt><dd>${esc(selected.section)}</dd></div>
              <div><dt>Campus</dt><dd>${esc(selected.campus)}</dd></div>
              <div><dt>Adviser</dt><dd>${esc(selected.adviser)}</dd></div>
              <div><dt>Requirements status</dt><dd>${esc(requirements.label)}</dd></div>
            </dl>
          </section>
          <section class="academic-history-print-section">
            <h2>Quarterly Academic Record</h2>
            ${historyTable(studentId)}
          </section>
        </article>`
    });
    bindTableSearch('#registrar-search', '#registrar-students tr', row => row.dataset.search || '');
    document.querySelector('#print-history').addEventListener('click', () => window.print());
  }

  function renderIncompleteGrades() {
    const cases = Object.values(Store.getIncompleteCases());
    const caseId = incompleteFromQuery();
    const selected = caseId ? Store.getIncompleteCases()[caseId] : null;
    const selectedStudent = selected ? Store.getAcademicStudent(selected.studentId) : null;
    const selectedClass = selected ? Store.getAcademicClass(selected.classId) : null;
    const assessment = selected ? Store.academic.policy.assessments.find(item => item.key === selected.assessmentKey) : null;
    const currentScore = selected ? Store.academic.grades[selected.classId]?.[selected.studentId]?.scores[selected.assessmentKey] : '';
    const caseRows = cases.map(item => {
      const student = Store.getAcademicStudent(item.studentId) || {};
      const academicClass = Store.getAcademicClass(item.classId) || {};
      const period = Store.getAcademicPeriod(item.periodId) || {};
      const missing = Store.academic.policy.assessments.find(entry => entry.key === item.assessmentKey);
      const record = {
        item,
        student,
        academicClass,
        period,
        missing,
        schoolYear: period.schoolYear || '',
        gradingPeriod: period.label || '',
        gradeLevel: student.gradeLevel || academicClass.gradeLevel || '',
        section: academicClass.section || student.section || '',
        subject: academicClass.subject || '',
        teacher: academicClass.teacher || '',
        status: item.status || ''
      };
      record.search = `${item.id} ${student.name || ''} ${item.studentId} ${record.subject} ${record.section} ${record.teacher} ${record.schoolYear} ${record.gradingPeriod} ${missing?.label || ''} ${labelize(record.status)}`.toLowerCase();
      return record;
    });
    const filterValues = key => [...new Set(caseRows.map(record => record[key]).filter(Boolean))].sort((a, b) => a.localeCompare(b));
    const filterOptions = (key, placeholder) => `<option value="">${placeholder}</option>${filterValues(key).map(value => `<option value="${esc(value)}">${esc(key === 'status' ? labelize(value) : value)}</option>`).join('')}`;
    const filterSelect = (id, key, label) => `<label class="select-control"><span>${label}</span><select id="${id}">${filterOptions(key, `All ${label.toLowerCase()}s`)}</select></label>`;
    pageLayout({
      breadcrumb: '<a href="registrar-records.html">Academic Records</a><span>/</span><span>Incomplete Grades</span>', eyebrow: 'Registrar Staff', title: 'Incomplete Grade Monitor', description: 'Filter cases, record follow-ups, verify completed scores, and update academic records.', action: status(`${cases.filter(item => item.status !== 'resolved').length} unresolved`, cases.some(item => item.status !== 'resolved') ? 'warning' : 'success'),
      content: `<section class="content-panel"><div class="panel-header"><div><h2>Incomplete Grade Cases</h2><p>Filter all available academic periods and case records</p></div></div><div class="toolbar incomplete-filters"><label class="search-control"><span>Search</span><input id="incomplete-search" type="search" placeholder="Student / subject / case"></label>${filterSelect('incomplete-school-year', 'schoolYear', 'School year')}${filterSelect('incomplete-grading-period', 'gradingPeriod', 'Grading period')}${filterSelect('incomplete-grade-level', 'gradeLevel', 'Grade level')}${filterSelect('incomplete-section', 'section', 'Section')}${filterSelect('incomplete-subject', 'subject', 'Subject')}${filterSelect('incomplete-teacher', 'teacher', 'Teacher')}${filterSelect('incomplete-status', 'status', 'Status')}<button id="incomplete-reset" class="button button-secondary" type="button">Reset Filters</button></div><div class="incomplete-filter-summary"><p id="incomplete-result-count" role="status" aria-live="polite">Showing ${cases.length} of ${cases.length} cases</p></div><div class="table-wrap"><table><thead><tr><th>Case</th><th>Student</th><th>Subject</th><th>Period</th><th>Missing item</th><th>Status</th><th>Open</th></tr></thead><tbody id="incomplete-body">${caseRows.map(record => `<tr data-case-id="${esc(record.item.id)}"><td>${esc(record.item.id)}</td><td>${esc(record.student.name || '—')}<small>${esc(record.item.studentId)}</small></td><td>${esc(record.subject || '—')}<small>${esc(record.section)} · ${esc(record.teacher)}</small></td><td>${esc(record.schoolYear || '—')}<small>${esc(record.gradingPeriod)}</small></td><td>${esc(record.missing?.label || '—')}</td><td>${status(labelize(record.status), record.status === 'resolved' ? 'success' : 'warning')}</td><td><a class="row-action" href="incomplete-grades.html?case=${encodeURIComponent(record.item.id)}">Review</a></td></tr>`).join('')}</tbody></table></div><div id="incomplete-empty" class="empty-state" hidden><strong>No incomplete-grade cases match these filters.</strong><span>Reset the filters or adjust the search criteria.</span></div></section>
        ${selected ? `<div id="incomplete-case-detail" class="academic-grid"><section class="content-panel"><div class="panel-header"><div><h2>${esc(selected.id)}</h2><p>${esc(selectedStudent.name)} · ${esc(selectedClass.subject)}</p></div>${status(labelize(selected.status), selected.status === 'resolved' ? 'success' : 'warning')}</div><div class="panel-body"><dl class="definition-grid"><div><dt>Student</dt><dd>${esc(selectedStudent.name)}</dd></div><div><dt>Section</dt><dd>${esc(selectedClass.section)}</dd></div><div><dt>Teacher</dt><dd>${esc(selectedClass.teacher)}</dd></div><div><dt>Missing assessment</dt><dd>${esc(assessment.label)}</dd></div><div><dt>Maximum score</dt><dd>${assessment.max}</dd></div><div><dt>Current score</dt><dd>${currentScore ?? 'Missing'}</dd></div></dl><ul class="audit-list">${selected.notes.map(note => `<li><strong>${esc(note.text)}</strong><small>${esc(note.by)} · ${esc(note.when)}</small></li>`).join('')}</ul><div class="modal-actions"><button id="close-incomplete-case" class="button button-outline" type="button">Close record</button></div></div></section><section class="content-panel"><div class="panel-header"><div><h2>Registrar Action</h2><p>Follow up or resolve the incomplete grade</p></div></div><div class="panel-body"><form id="incomplete-note-form"><div class="field-grid"><label class="span-two">Follow-up note<textarea id="incomplete-note" required placeholder="Record a follow-up action."></textarea></label></div><div class="modal-actions"><button class="button button-secondary" type="submit">Save note</button></div></form><form id="resolve-form"><div class="field-grid"><label>Completed score<input id="completed-score" type="number" min="0" max="${assessment.max}" value="${currentScore ?? ''}" ${selected.status === 'resolved' ? 'disabled' : ''}></label><label>Verification<input readonly value="Registrar verification"></label></div><div class="modal-actions"><button class="button button-primary" type="submit" ${selected.status === 'resolved' ? 'disabled' : ''}>Resolve and update grade</button></div></form></div></section></div>` : ''}`
    });
    const filterIds = { schoolYear: 'incomplete-school-year', gradingPeriod: 'incomplete-grading-period', gradeLevel: 'incomplete-grade-level', section: 'incomplete-section', subject: 'incomplete-subject', teacher: 'incomplete-teacher', status: 'incomplete-status' };
    const rowsById = new Map(caseRows.map(record => [record.item.id, record]));
    const filterCases = () => {
      const term = document.querySelector('#incomplete-search').value.trim().toLowerCase();
      const activeFilters = Object.entries(filterIds).reduce((filters, [key, id]) => { filters[key] = document.querySelector(`#${id}`).value; return filters; }, {});
      let visibleCases = 0;
      document.querySelectorAll('#incomplete-body tr').forEach(row => {
        const record = rowsById.get(row.dataset.caseId);
        const matchesSearch = !term || record.search.includes(term);
        const matchesFilters = Object.entries(activeFilters).every(([key, value]) => !value || record[key] === value);
        const matches = matchesSearch && matchesFilters;
        row.style.display = matches ? '' : 'none';
        if (matches) visibleCases += 1;
      });
      document.querySelector('#incomplete-result-count').textContent = `Showing ${visibleCases} of ${cases.length} cases`;
      document.querySelector('#incomplete-empty').hidden = visibleCases > 0;
    };
    document.querySelector('#incomplete-search').addEventListener('input', filterCases);
    Object.values(filterIds).forEach(id => document.querySelector(`#${id}`).addEventListener('change', filterCases));
    document.querySelector('#incomplete-reset').addEventListener('click', () => {
      document.querySelector('#incomplete-search').value = '';
      Object.values(filterIds).forEach(id => { document.querySelector(`#${id}`).value = ''; });
      filterCases();
    });
    filterCases();
    if (selected) {
      document.querySelector('#close-incomplete-case').addEventListener('click', () => {
        const url = new URL(window.location.href);
        url.searchParams.delete('case');
        window.history.replaceState({}, '', `${url.pathname}${url.search}${url.hash}`);
        document.querySelector('#incomplete-case-detail')?.remove();
      });
      document.querySelector('#incomplete-note-form').addEventListener('submit', event => { event.preventDefault(); if (Store.addIncompleteNote(selected.id, document.querySelector('#incomplete-note').value)) showToast('Follow-up saved', `${selected.id} was updated.`); });
      document.querySelector('#resolve-form').addEventListener('submit', event => { event.preventDefault(); if (!Store.resolveIncomplete(selected.id, document.querySelector('#completed-score').value)) return showToast('Resolution blocked', 'Enter a score within the assessment maximum.'); showToast('Incomplete grade resolved', 'The score and calculated quarter grade were updated.'); });
    }
  }

  function renderGradeApprovals() {
    const requests = Object.values(Store.getCorrections()).sort((a, b) => b.id.localeCompare(a.id));
    const selected = Store.getCorrections()[correctionFromQuery()] || requests[0];
    const buttons = selected ? (selected.status === 'principal_review' ? '<button class="button button-secondary" data-review="reject">Reject</button><button class="button button-primary" data-review="principal-approve">Mark Principal Approved</button>' : selected.status === 'registrar_review' ? '<button class="button button-secondary" data-review="reject">Reject</button><button class="button button-primary" data-review="registrar-approve">Approve as Registrar</button>' : selected.status === 'approved' ? '<button class="button button-primary" data-review="encode">Encode corrected grade</button>' : '') : '';
    pageLayout({
      breadcrumb: '<a href="registrar-records.html">Academic Records</a><span>/</span><span>Grade Approvals</span>', eyebrow: 'Registrar Staff', title: 'Grade Correction Review', description: 'Advance requests through Principal review, Registrar approval, and authorized encoding.', action: '<span class="prototype-label">Principal step represented here</span>',
      content: `<section class="policy-callout warning"><strong>Prototype access:</strong> there is no separate Principal login. The distinct Principal Review step is advanced from this Registrar workspace and remains visible in the audit trail.</section>
        <div class="academic-grid"><section class="content-panel"><div class="panel-header"><div><h2>Correction Requests</h2><p>${requests.length} total requests</p></div></div><div class="table-wrap"><table><thead><tr><th>Request</th><th>Student</th><th>Change</th><th>Status</th><th>Open</th></tr></thead><tbody>${requests.map(item => `<tr><td>${esc(item.id)}</td><td>${esc(Store.getAcademicStudent(item.studentId).name)}<small>${esc(item.subject)}</small></td><td>${item.currentGrade} → ${item.proposedGrade}</td><td>${status(correctionStatusLabel(item.status), item.status === 'encoded' ? 'success' : item.status === 'rejected' ? 'danger' : 'warning')}</td><td><a class="row-action" href="grade-approvals.html?request=${encodeURIComponent(item.id)}">Review</a></td></tr>`).join('')}</tbody></table></div></section>${selected ? `<section class="content-panel"><div class="panel-header"><div><h2>${esc(selected.id)}</h2><p>${esc(correctionStatusLabel(selected.status))}</p></div>${status(correctionStatusLabel(selected.status), selected.status === 'encoded' ? 'success' : selected.status === 'rejected' ? 'danger' : 'warning')}</div><div class="panel-body"><dl class="definition-grid"><div><dt>Student</dt><dd>${esc(Store.getAcademicStudent(selected.studentId).name)}</dd></div><div><dt>Subject</dt><dd>${esc(selected.subject)}</dd></div><div><dt>Period</dt><dd>${esc(selected.schoolYear)} · ${esc(selected.quarterKey.toUpperCase())}</dd></div><div><dt>Grade change</dt><dd>${selected.currentGrade} → ${selected.proposedGrade}</dd></div><div><dt>Reason</dt><dd>${esc(selected.reason)}</dd></div><div><dt>Attachment</dt><dd>${esc(selected.attachment?.name || 'None')}</dd></div></dl><div class="spaced-block">${renderCorrectionTimeline(selected)}</div><ul class="audit-list">${selected.audit.map(item => `<li><strong>${esc(item.action)}</strong><small>${esc(item.by)} · ${esc(item.when)}</small></li>`).join('')}</ul><div class="modal-actions">${buttons}</div></div></section>` : ''}</div>`
    });
    document.querySelectorAll('[data-review]').forEach(button => button.addEventListener('click', () => {
      if (!Store.reviewCorrection(selected.id, button.dataset.review)) return showToast('Action unavailable', 'The request is not in the required workflow state.');
      showToast('Correction workflow updated', `${selected.id} advanced to its next state.`);
    }));
  }

  function renderFinalizeGrades() {
    const periodId = params.get('period') && Store.academic.periods[params.get('period')] ? params.get('period') : 'SY2026-Q1';
    const period = Store.getAcademicPeriod(periodId);
    const state = Store.finalizationStatus(periodId);
    pageLayout({
      breadcrumb: '<a href="registrar-records.html">Academic Records</a><span>/</span><span>Grade Finalization</span>', eyebrow: 'Registrar Staff', title: 'Grade Period Finalization', description: 'Verify submission completeness and lock official academic records.', action: status(labelize(period.status), period.status === 'finalized' ? 'success' : 'warning'),
      content: `<section class="content-panel"><div class="panel-header"><div><h2>Grading Period</h2><p>Select a period to review</p></div></div><div class="toolbar"><label class="select-control"><select id="period-select">${Object.values(Store.academic.periods).map(item => `<option value="${esc(item.id)}" ${item.id === periodId ? 'selected' : ''}>${esc(item.schoolYear)} · ${esc(item.label)}</option>`).join('')}</select></label></div><div class="panel-body"><dl class="definition-grid"><div><dt>School year</dt><dd>${esc(period.schoolYear)}</dd></div><div><dt>Period</dt><dd>${esc(period.label)}</dd></div><div><dt>Status</dt><dd>${esc(labelize(period.status))}</dd></div><div><dt>Opened by</dt><dd>${esc(period.openedBy)}</dd></div><div><dt>Finalized by</dt><dd>${esc(period.finalizedBy || '—')}</dd></div><div><dt>Finalized at</dt><dd>${esc(period.finalizedAt || '—')}</dd></div></dl></div></section>
        <section class="academic-stats"><article class="academic-stat ${state.unsubmitted.length ? 'warning' : 'success'}"><strong>${state.classes.length - state.unsubmitted.length}/${state.classes.length}</strong><span>Classes submitted</span><small>${state.unsubmitted.length ? 'Submission required' : 'Requirement met'}</small></article><article class="academic-stat ${state.incomplete.length ? 'danger' : 'success'}"><strong>${state.incomplete.length}</strong><span>Unresolved incompletes</span><small>${state.incomplete.length ? 'Must be resolved' : 'Requirement met'}</small></article><article class="academic-stat ${state.corrections.length ? 'warning' : 'success'}"><strong>${state.corrections.length}</strong><span>Pending corrections</span><small>${state.corrections.length ? 'Must be completed' : 'Requirement met'}</small></article><article class="academic-stat ${state.ready ? 'success' : 'warning'}"><strong>${state.ready ? 'Ready' : 'Blocked'}</strong><span>Finalization state</span><small>All checks required</small></article></section>
        <div class="academic-grid equal"><section class="content-panel"><div class="panel-header"><div><h2>Blocking Conditions</h2><p>Every item must be cleared</p></div></div><div class="panel-body"><ul class="audit-list">${state.unsubmitted.map(item => `<li><strong>Unsubmitted class</strong><span>${esc(item.subject)} · ${esc(item.section)}</span></li>`).join('')}${state.incomplete.map(item => `<li><strong>Incomplete grade</strong><span>${esc(item.id)} · ${esc(Store.getAcademicStudent(item.studentId).name)}</span></li>`).join('')}${state.corrections.map(item => `<li><strong>Pending correction</strong><span>${esc(item.id)} · ${esc(correctionStatusLabel(item.status))}</span></li>`).join('') || '<li><strong>No blocking conditions</strong><span>This period is ready for finalization.</span></li>'}</ul></div></section><section class="content-panel"><div class="panel-header"><div><h2>Finalization Action</h2><p>Locks teacher editing and updates official records</p></div></div><div class="panel-body"><section class="policy-callout ${state.ready ? '' : 'warning'}"><strong>${state.ready ? 'Ready to finalize.' : 'Finalization blocked.'}</strong> ${state.ready ? 'This action records the registrar, timestamp, and locks every grade in the selected period.' : 'Resolve every listed condition before finalizing.'}</section><div class="modal-actions"><button id="finalize-period" class="button button-primary" ${!state.ready || period.status === 'finalized' ? 'disabled' : ''}>Finalize ${esc(period.label)}</button></div><ul class="audit-list">${Store.academic.audit.slice(0, 5).map(item => `<li><strong>${esc(item.action)}</strong><small>${esc(item.by)} · ${esc(item.when)}</small></li>`).join('')}</ul></div></section></div>`
    });
    document.querySelector('#period-select').addEventListener('change', event => { location.href = `finalize-grades.html?period=${encodeURIComponent(event.target.value)}`; });
    document.querySelector('#finalize-period').addEventListener('click', () => {
      if (!Store.finalizeAcademicPeriod(periodId)) return showToast('Finalization blocked', 'Clear all submissions, incomplete grades, and correction requests first.');
      showToast('Grades finalized', `${period.schoolYear} ${period.label} is now locked.`);
    });
  }

  function renderCurrent() {
    ({
      'advisory.html': renderAdvisory,
      'class-records.html': renderClassRecords,
      'student-monitoring.html': renderStudentMonitoring,
      'class-list.html': renderClassList,
      'grade-entry.html': renderGradeEntry,
      'progress-reports.html': renderProgressReports,
      'registrar-records.html': renderRegistrarRecords,
      'incomplete-grades.html': renderIncompleteGrades,
      'grade-approvals.html': renderGradeApprovals,
      'finalize-grades.html': renderFinalizeGrades
    })[currentPage]?.();
  }

  document.addEventListener('store:change', renderCurrent);
  renderCurrent();
})();
