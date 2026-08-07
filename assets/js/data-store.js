// ICI Local 2.0 — shared demo data store.
// Persists to localStorage so add/remove/status actions carry across every
// enrollment screen. Reset any time with Store.resetDemo().
// No server is used — this simulates the centralized student database.
(function () {
  const STORE_KEY = 'ici_local_demo_v1';

  const SEED = {
    applicants: {
      'APP-2026-0148': {
        name: 'Dela Cruz, Juan', lrn: '119270140008', category: 'New', programLabel: 'HUMSS · Grade 11', program: 'shs',
        campus: 'Main — Iligan', campusKey: 'main',
        queueStatus: 'pre-enlisted', evalStatus: 'review', flag: '',
        grade: 'Grade 11', track: 'ACADEMIC', strand: 'HUMSS', delivery: ['Modular (Print)'], deliveryOther: '',
        lrnStatus: 'With LRN', psa: 'PSA-2023-000412', last: 'Dela Cruz', first: 'Juan', middle: 'Ramon', ext: '',
        dob: '03/14/2009', sex: 'Male', age: '17', contact: '0917 555 0148', email: 'juan.delacruz@email.com',
        ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: 'With Honors (Grade 10)',
        house: 'Zone 2, Purok 5', province: 'Lanao del Norte', city: 'Iligan City', barangay: 'Pala-o',
        enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
        father: 'Ramon Dela Cruz', mother: 'Liza Dela Cruz', guardian: 'Ramon Dela Cruz',
        guardianContact: '0917 555 0148', regDate: '07/28/2026', referred: 'STAFF'
      },
      'APP-2026-0149': {
        name: 'Santos, Maria A.', lrn: '108517230184', category: 'Transferee', programLabel: 'STEM · Grade 11', program: 'shs',
        campus: 'Main — Iligan', campusKey: 'main',
        queueStatus: 'review', evalStatus: 'flag', flag: 'dup',
        grade: 'Grade 11', track: 'ACADEMIC', strand: 'STEM', delivery: ['Modular (Digital)', 'Online'], deliveryOther: '',
        lrnStatus: 'With LRN', psa: 'PSA-2022-001830', last: 'Santos', first: 'Maria', middle: 'Aguinaldo', ext: '',
        dob: '07/22/2008', sex: 'Female', age: '18', contact: '0918 555 0149', email: 'maria.santos@email.com',
        ip: 'Yes', ipOther: 'Meranaw', motherTongue: 'Maranao', honors: 'With Honors (Grade 10)',
        house: 'Purok 4, Sitio Raya', province: 'Lanao del Sur', city: 'Marawi City', barangay: 'Tambo',
        enrolled: 'Yes', school: 'Kapatagan National High School', schoolAddress: 'Poblacion, Kapatagan, Lanao del Norte', schoolId: 'KNH-2024-118',
        father: 'Manuel Santos', mother: 'Aida Aguinaldo Santos', guardian: 'Manuel Santos',
        guardianContact: '0917 555 0991', regDate: '07/30/2026', referred: 'GUIDANCE'
      },
      'APP-2026-0150': {
        name: 'Reyes, Pedro', lrn: '120381650117', category: 'New', programLabel: 'TVL-Cookery · Grade 11', program: 'shs',
        campus: 'Bugo — CDO', campusKey: 'bugo',
        queueStatus: 'pending', evalStatus: 'review', flag: '',
        grade: 'Grade 11', track: 'TVL', strand: 'CA', delivery: ['Online'], deliveryOther: '',
        lrnStatus: 'With LRN', psa: 'PSA-2024-005621', last: 'Reyes', first: 'Pedro', middle: 'Lopez', ext: 'Jr.',
        dob: '11/02/2007', sex: 'Male', age: '18', contact: '0919 555 0150', email: 'pedro.reyes@email.com',
        ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: '',
        house: 'Blk 7, Brgy. San Isidro', province: 'Misamis Oriental', city: 'Cagayan de Oro', barangay: 'Bugo',
        enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
        father: 'Dante Reyes', mother: 'Carmen Lopez Reyes', guardian: 'Dante Reyes',
        guardianContact: '0919 555 0150', regDate: '08/01/2026', referred: 'TVL'
      },
      'APP-2026-0151': {
        name: 'Lim, Angela', lrn: '112500710292', category: 'Returnee', programLabel: 'BSOA · 1st Year', program: 'college',
        campus: 'Kapatagan', campusKey: 'kapatagan',
        queueStatus: 'pending', evalStatus: 'ready', flag: '',
        grade: 'Grade 12', track: 'ACADEMIC', strand: 'GAS', delivery: ['Modular (Print)', 'Educational TV'], deliveryOther: '',
        lrnStatus: 'Returning (Balik-Aral)', psa: 'PSA-2021-002944', last: 'Lim', first: 'Angela', middle: 'Tan', ext: '',
        dob: '05/30/2008', sex: 'Female', age: '18', contact: '0920 555 0151', email: 'angela.lim@email.com',
        ip: 'No', ipOther: '', motherTongue: 'Hiligaynon', honors: 'With High Honors (G11)',
        house: 'Purok 3, Poblacion', province: 'Lanao del Norte', city: 'Kapatagan', barangay: 'Central',
        enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
        father: 'Ramon Lim', mother: 'Grace Tan Lim', guardian: 'Ramon Lim',
        guardianContact: '0920 555 0151', regDate: '08/02/2026', referred: 'STAFF'
      },
      'APP-2026-0152': {
        name: 'Abad, Carla', lrn: '119770320691', category: 'New', programLabel: 'ABM · Grade 11', program: 'shs',
        campus: 'Main — Iligan', campusKey: 'main',
        queueStatus: 'pre-enlisted', evalStatus: 'ready', flag: '',
        grade: 'Grade 11', track: 'ACADEMIC', strand: 'ABM', delivery: ['Modular (Print)', 'Online'], deliveryOther: '',
        lrnStatus: 'With LRN', psa: 'PSA-2023-000918', last: 'Abad', first: 'Carla', middle: 'Mercado', ext: '',
        dob: '09/11/2008', sex: 'Female', age: '17', contact: '0921 555 0152', email: 'carla.abad@email.com',
        ip: 'No', ipOther: '', motherTongue: 'Ilonggo', honors: 'With Honors (Grade 10)',
        house: 'Zone 5, Purok 2', province: 'Lanao del Norte', city: 'Iligan City', barangay: 'Hinaplanon',
        enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
        father: 'Eduardo Abad', mother: 'Marites Mercado Abad', guardian: 'Eduardo Abad',
        guardianContact: '0921 555 0152', regDate: '08/03/2026', referred: 'ENGLISH'
      }
    },
    requirements: {
      'APP-2026-0148': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'pending', 'Certificate of Completion': 'missing', 'Good Moral Certificate': 'missing' }, contact: '0917 555 0148', email: 'juan.delacruz@email.com' },
      'APP-2026-0149': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'pending', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'missing' }, contact: '0918 555 0149', email: 'maria.santos@email.com' },
      'APP-2026-0150': { docs: { 'PSA Birth Certificate': 'pending', 'Form 137': 'pending', 'Certificate of Completion': 'pending', 'Good Moral Certificate': 'pending' }, contact: '0919 555 0150', email: 'pedro.reyes@email.com' },
      'APP-2026-0151': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' }, contact: '0920 555 0151', email: 'angela.lim@email.com' },
      'APP-2026-0152': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' }, contact: '0921 555 0152', email: 'carla.abad@email.com' }
    },
    audits: {
      'APP-2026-0148': ['Certificate of Completion marked Missing by R. Santos — Aug 3, 2026 04:19 PM', 'PSA Birth Certificate marked Submitted by R. Santos — Aug 3, 2026 09:58 AM'],
      'APP-2026-0149': ['Form 137 marked Pending by R. Santos — Aug 5, 2026 09:14 AM', 'PSA Birth Certificate marked Submitted by R. Santos — Aug 3, 2026 02:41 PM', 'Application created by R. Santos — Aug 2, 2026 10:05 AM'],
      'APP-2026-0150': ['Good Moral Certificate still Pending — last checked by R. Santos — Aug 4, 2026 11:22 AM', 'Application created by R. Santos — Aug 2, 2026 01:18 PM'],
      'APP-2026-0151': ['All documents verified by R. Santos — Aug 6, 2026 08:37 AM', 'Form 137 marked Submitted by R. Santos — Aug 5, 2026 03:02 PM'],
      'APP-2026-0152': ['All documents verified by R. Santos — Aug 6, 2026 10:44 AM', 'Application created by R. Santos — Aug 3, 2026 02:30 PM']
    },
    attendance: {
      'APP-2026-0148': { req: 'Incomplete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Present' }, { day: 'Thu', date: 'Aug 6', status: 'Pending' }, { day: 'Fri', date: 'Aug 7', status: 'Pending' }] },
      'APP-2026-0149': { req: 'Incomplete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Present' }, { day: 'Thu', date: 'Aug 6', status: 'Present' }, { day: 'Fri', date: 'Aug 7', status: 'Pending' }] },
      'APP-2026-0150': { req: 'Incomplete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Absent' }, { day: 'Thu', date: 'Aug 6', status: 'Present' }, { day: 'Fri', date: 'Aug 7', status: 'Pending' }] },
      'APP-2026-0151': { req: 'Complete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Present' }, { day: 'Thu', date: 'Aug 6', status: 'Present' }, { day: 'Fri', date: 'Aug 7', status: 'Present' }] },
      'APP-2026-0152': { req: 'Complete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Present' }, { day: 'Thu', date: 'Aug 6', status: 'Present' }, { day: 'Fri', date: 'Aug 7', status: 'Present' }] }
    },
    pairs: {
      'APP-2026-0149': {
        meter: '92% match confidence',
        incoming: { sub: 'Transferee · APP-2026-0149 · Submitted Aug 2, 2026', fields: [['Full name', 'Santos, Maria A.', true], ['Date of birth', '07/22/2008', true], ['Sex', 'Female', true], ['LRN', '108517230184', true], ['Previous school', 'Kapatagan National HS', true], ['Guardian contact', '0918 555 0149', false], ['Status', 'Pre-Enlisted', false]] },
        existing: { sub: 'Student STU-2024-0771 · Record since 2024', fields: [['Full name', 'Santos, Maria Ann', true], ['Date of birth', '07/22/2008', true], ['Sex', 'Female', true], ['LRN', '108517230184', true], ['Previous school', 'Kapatagan National HS', true], ['Guardian contact', '0917 555 0991', false], ['Status', 'Inactive (Withdrawn)', false]] }
      },
      'APP-2026-0153': {
        meter: '78% match confidence',
        incoming: { sub: 'New · APP-2026-0153 · Submitted Aug 4, 2026', fields: [['Full name', 'Bautista, Carlo', false], ['Date of birth', '03/09/2009', true], ['Sex', 'Male', true], ['LRN', '121456789012', false], ['Previous school', 'Iligan City NHS', true], ['Guardian contact', '0922 555 0153', true], ['Status', 'Pre-Enlisted', false]] },
        existing: { sub: 'Student STU-2023-1210 · Record since 2023', fields: [['Full name', 'Bautista, Carlos D.', false], ['Date of birth', '03/09/2009', true], ['Sex', 'Male', true], ['LRN', '120987654321', false], ['Previous school', 'Iligan City NHS', true], ['Guardian contact', '0922 555 0153', true], ['Status', 'Active', false]] }
      },
      'APP-2026-0140': {
        meter: '81% match confidence',
        incoming: { sub: 'Returnee · APP-2026-0140 · Submitted Jul 28, 2026', fields: [['Full name', 'Garcia, Ana', true], ['Date of birth', '01/15/2008', true], ['Sex', 'Female', true], ['LRN', '112377981523', true], ['Previous school', 'St. Michael Academy', false], ['Guardian contact', '0923 555 0140', false], ['Status', 'Pending Requirements', false]] },
        existing: { sub: 'Student STU-2022-0503 · Record since 2022', fields: [['Full name', 'Garcia, Ana Marie', true], ['Date of birth', '01/15/2008', true], ['Sex', 'Female', true], ['LRN', '112377981523', true], ['Previous school', 'Tambo Central School', false], ['Guardian contact', '0923 555 0140', false], ['Status', 'Enrolled', false]] }
      }
    },
    pairStatus: { 'APP-2026-0140': 'merged' },
    records: {
      'STU-2026-0452': { app: 'APP-2026-0152', name: 'Abad, Carla', initials: 'CA', meta: 'Grade 11 · ABM · Main — Iligan', status: 'pending', category: 'New Student — Regular', ref: 'CSH-0096', bracket: 'Bracket A', req: ['Complete', 'success'], reqDate: 'Aug 6, 2026', attend: ['present', 'present', 'present', 'present', 'present'], attendStatus: 'Verified ✓', confirmable: true, confirmHint: 'Requirements complete AND attendance verified. Ready to confirm enrollment.' },
      'STU-2026-0453': { app: 'APP-2026-0148', name: 'Dela Cruz, Juan', initials: 'JD', meta: 'Grade 11 · HUMSS · Main — Iligan', status: 'pending', category: 'New Student — Regular', ref: 'CSH-0089', bracket: 'Bracket A', req: ['Incomplete', 'danger'], reqDate: 'Aug 3, 2026', attend: ['present', 'present', 'absent', 'present', 'pending'], attendStatus: 'Not yet verified', confirmable: false, confirmHint: 'Cannot confirm yet — requirements are incomplete and attendance is not fully verified.' },
      'STU-2026-0454': { app: 'APP-2026-0149', name: 'Santos, Maria A.', initials: 'MS', meta: 'Grade 11 · STEM · Main — Iligan', status: 'enrolled', category: 'Transferee — Voucher (ESC)', ref: 'CSH-0091', bracket: 'Bracket B', req: ['Complete', 'success'], reqDate: 'Aug 6, 2026', attend: ['present', 'present', 'present', 'present', 'present'], attendStatus: 'Verified ✓', confirmable: false, confirmHint: 'Student is already officially enrolled.' },
      'STU-2026-0455': { app: 'APP-2026-0150', name: 'Reyes, Pedro', initials: 'PR', meta: 'Grade 11 · TVL-Cookery · Bugo — CDO', status: 'enrolled', category: 'New Student — Regular', ref: 'CSH-0093', bracket: 'Bracket A', req: ['Complete', 'success'], reqDate: 'Aug 5, 2026', attend: ['present', 'present', 'present', 'absent', 'present'], attendStatus: 'Verified ✓', confirmable: false, confirmHint: 'Student is already officially enrolled.' },
      'STU-2026-0451': { app: 'APP-2026-0151', name: 'Lim, Angela', initials: 'AL', meta: '1st Year · BSOA · Kapatagan', status: 'enrolled', category: 'Returnee — Regular', ref: 'CSH-0095', bracket: 'Bracket B', req: ['Complete', 'success'], reqDate: 'Aug 6, 2026', attend: ['present', 'present', 'present', 'present', 'present'], attendStatus: 'Verified ✓', confirmable: false, confirmHint: 'Student is already officially enrolled. Assign a section to complete sectioning.' }
    },
    sections: { 'STU-2026-0454': 'STEM-B', 'STU-2026-0455': 'Cookery-1' },
    reportLog: [
      { when: 'Aug 7, 2026 10:42 AM', title: 'Enrollment Summary', criteria: 'Enrollment Summary · 2026-2027 · First Semester · Main Campus · All Tracks · Aug 01, 2026 - Aug 31, 2026', by: 'R. Santos', format: 'PDF' },
      { when: 'Aug 6, 2026 03:20 PM', title: 'Missing Requirements', criteria: 'Missing Requirements · 2026-2027 · First Semester · All Campuses · All Tracks · Jul 01, 2026 - Aug 31, 2026', by: 'R. Santos', format: 'CSV' }
    ]
  };

  let data = null;

  function clone(obj) { return JSON.parse(JSON.stringify(obj)); }

  function load() {
    try {
      const raw = localStorage.getItem(STORE_KEY);
      if (raw) { data = JSON.parse(raw); return; }
    } catch (e) { /* corrupted storage — reseed */ }
    data = clone(SEED);
    persist(false);
  }

  // Ensure every applicant carries its own id (seed data is keyed by id but the
  // objects themselves need an id field for list rendering).
  function normalize() {
    Object.keys(data.applicants).forEach(key => { data.applicants[key].id = key; });
  }

  function persist(emit = true) {
    try { localStorage.setItem(STORE_KEY, JSON.stringify(data)); } catch (e) { /* storage full/unavailable — keep in memory */ }
    if (emit) document.dispatchEvent(new CustomEvent('store:change'));
  }

  function emit() { document.dispatchEvent(new CustomEvent('store:change')); }

  // ---- applicant helpers ----
  function nextAppId() {
    const ids = Object.keys(data.applicants).filter(id => /^APP-2026-/.test(id)).map(id => Number(id.split('-')[2])).filter(Boolean);
    const max = ids.length ? Math.max(...ids) : 152;
    return 'APP-2026-' + String(max + 1).padStart(4, '0');
  }

  function addApplicant(partial) {
    const id = partial.id || nextAppId();
    data.applicants[id] = Object.assign({
      name: '', lrn: '', category: 'New', programLabel: '—', program: 'shs',
      campus: 'Main — Iligan', campusKey: 'main',
      queueStatus: 'pre-enlisted', evalStatus: 'ready', flag: '',
      grade: 'Grade 11', track: 'ACADEMIC', strand: '', delivery: [], deliveryOther: '',
      lrnStatus: 'With LRN', psa: '', last: '', first: '', middle: '', ext: '',
      dob: '', sex: 'Female', age: '', contact: '', email: '',
      ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: '',
      house: '', province: 'Lanao del Norte', city: '', barangay: '',
      enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
      father: '', mother: '', guardian: '', guardianContact: '', regDate: '', referred: 'STAFF'
    }, partial, { id });
    // Give new applicants a default requirement + attendance profile so every screen sees them.
    if (!data.requirements[id]) {
      data.requirements[id] = { docs: { 'PSA Birth Certificate': 'pending', 'Form 137': 'pending', 'Certificate of Completion': 'pending', 'Good Moral Certificate': 'pending' }, contact: partial.contact || '—', email: partial.email || '' };
    }
    if (!data.audits[id]) data.audits[id] = ['Application created by R. Santos — ' + stamp()];
    if (!data.attendance[id]) {
      data.attendance[id] = { req: 'Pending', log: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'].map(day => ({ day, date: '', status: 'Pending' })) };
    }
    persist();
    return id;
  }

  function updateApplicant(id, patch) {
    if (!data.applicants[id]) return;
    data.applicants[id] = Object.assign(data.applicants[id], patch);
    persist();
  }

  function removeApplicant(id) {
    delete data.applicants[id];
    delete data.requirements[id];
    delete data.audits[id];
    delete data.attendance[id];
    delete data.pairs[id];
    delete data.pairStatus[id];
    // Also drop the matching student record if the applicant was confirmed.
    Object.keys(data.records).forEach(sid => {
      if (data.records[sid].app === id) { delete data.records[sid]; delete data.sections[sid]; }
    });
    normalize();
    persist();
  }

  // ---- requirement helpers ----
  function getRequirements(id) { return data.requirements[id]; }
  function setRequirement(id, doc, status) {
    const req = data.requirements[id];
    if (!req) return;
    req.docs[doc] = status;
    if (!data.audits[id]) data.audits[id] = [];
    data.audits[id].unshift(`${doc} marked ${status} by R. Santos — ${stamp()}`);
    persist();
  }
  function getAudits(id) { return data.audits[id] || []; }

  // ---- attendance helpers ----
  function getAttendance(id) { return data.attendance[id]; }
  function setAttendance(id, idx, status) {
    const att = data.attendance[id];
    if (!att || !att.log[idx]) return;
    att.log[idx].status = status;
    persist();
  }

  // ---- duplicate helpers ----
  function getPair(id) { return data.pairs[id]; }
  function getPairStatus(id) { return data.pairStatus[id] || ''; }
  function resolvePair(id, action) {
    data.pairStatus[id] = action;
    persist();
  }

  // ---- record helpers ----
  function getRecords() { return data.records; }
  function getRecord(id) { return data.records[id]; }
  function confirmRecord(id) {
    const rec = data.records[id];
    if (!rec || !rec.confirmable) return false;
    rec.status = 'enrolled';
    if (data.applicants[rec.app]) data.applicants[rec.app].queueStatus = 'enrolled';
    persist();
    return true;
  }


  function assignSection(id, section) {
    data.sections[id] = section;
    persist();
  }
  function removeRecord(id) {
    delete data.records[id];
    delete data.sections[id];
    persist();
  }

  // ---- reporting helpers ----
  function getReportLog() { return data.reportLog; }
  function addReport(entry) {
    data.reportLog.unshift(entry);
    persist();
  }
  function removeReport(idx) {
    data.reportLog.splice(idx, 1);
    persist();
  }

  function stamp() { return 'Aug 7, 2026 10:42 AM'; }

  function resetDemo() {
    data = clone(SEED);
    persist();
    location.reload();
  }

  load();
  normalize();

  window.Store = {
    get applicants() { return data.applicants; },
    get requirements() { return data.requirements; },
    get attendance() { return data.attendance; },
    get pairs() { return data.pairs; },
    get pairStatus() { return data.pairStatus; },
    get records() { return data.records; },
    get sections() { return data.sections; },
    get reportLog() { return data.reportLog; },
    nextAppId, addApplicant, updateApplicant, removeApplicant,
    getRequirements, setRequirement, getAudits,
    getAttendance, setAttendance,
    getPair, getPairStatus, resolvePair,
    getRecords, getRecord, confirmRecord, assignSection, removeRecord,
    getReportLog, addReport, removeReport,
    resetDemo
  };
})();
