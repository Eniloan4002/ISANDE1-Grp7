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
        submittedAt: 'Aug 2, 2026',
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
        submittedAt: 'Aug 2, 2026',
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
        submittedAt: 'Aug 2, 2026',
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
        submittedAt: 'Aug 5, 2026',
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
        submittedAt: 'Aug 3, 2026',
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
      },
      'APP-2026-0156': {
        name: 'Cruz, Bianca', lrn: '117744552201', category: 'Continuing', programLabel: 'STEM · Grade 12', program: 'shs',
        submittedAt: 'Aug 9, 2026',
        campus: 'Main — Iligan', campusKey: 'main',
        queueStatus: 'pre-enlisted', evalStatus: 'review', flag: '',
        grade: 'Grade 12', track: 'ACADEMIC', strand: 'STEM', delivery: ['Online'], deliveryOther: '',
        lrnStatus: 'With LRN', psa: 'PSA-2021-004477', last: 'Cruz', first: 'Bianca', middle: 'Ochoa', ext: '',
        dob: '02/10/2008', sex: 'Female', age: '18', contact: '0917 555 0164', email: 'bianca.cruz@email.com',
        ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: 'With Honors (Grade 11)',
        house: 'Purok 6, Villa Verde', province: 'Lanao del Norte', city: 'Iligan City', barangay: 'Tibanga',
        enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
        father: 'Reynaldo Cruz', mother: 'Estrella Ochoa Cruz', guardian: 'Reynaldo Cruz',
        guardianContact: '0917 555 0164', regDate: '08/09/2026', referred: 'STAFF',
        continuing: false, priorStudentId: ''
      },
      'APP-2026-0153': {
        name: 'Bautista, Carlo', lrn: '121456789012', category: 'Transferee', programLabel: 'TVL-Culinary Arts · Grade 11', program: 'shs',
        submittedAt: 'Aug 4, 2026',
        campus: 'Bugo — CDO', campusKey: 'bugo',
        queueStatus: 'review', evalStatus: 'flag', flag: 'dup',
        grade: 'Grade 11', track: 'TVL', strand: 'CA', delivery: ['Online'], deliveryOther: '',
        lrnStatus: 'With LRN', psa: 'PSA-2024-002091', last: 'Bautista', first: 'Carlo', middle: 'Ramirez', ext: '',
        dob: '03/09/2009', sex: 'Male', age: '17', contact: '0922 555 0554', email: 'carlo.bautista@email.com',
        ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: '',
        house: 'Purok 3, Brgy. Bugo', province: 'Misamis Oriental', city: 'Cagayan de Oro', barangay: 'Bugo',
        enrolled: 'Yes', school: 'Iligan City National High School', schoolAddress: 'Iligan City', schoolId: '',
        father: 'Edwin Bautista', mother: 'Elena Ramirez Bautista', guardian: 'Edwin Bautista',
        guardianContact: '0922 555 0153', regDate: '08/04/2026', referred: 'TVL'
      },
      'APP-2026-0154': {
        name: 'Villanueva, Carlo', lrn: '116481720635', category: 'New', programLabel: 'ABM · Grade 11', program: 'shs',
        submittedAt: 'Aug 5, 2026',
        campus: 'Main — Iligan', campusKey: 'main',
        queueStatus: 'pending', evalStatus: 'review', flag: '',
        grade: 'Grade 11', track: 'ACADEMIC', strand: 'ABM', delivery: ['Online'], deliveryOther: '',
        lrnStatus: 'With LRN', psa: '', last: 'Villanueva', first: 'Carlo', middle: 'Bautista', ext: '',
        dob: '05/17/2009', sex: 'Male', age: '17', contact: '0917 555 0154', email: 'carlo.villanueva@email.com',
        ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: '',
        house: 'Blk 12, Villa Verde', province: 'Lanao del Norte', city: 'Iligan City', barangay: 'Tibanga',
        enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
        father: 'Noel Villanueva', mother: 'Grace Bautista Villanueva', guardian: 'Noel Villanueva',
        guardianContact: '0917 555 0154', regDate: '08/02/2026', referred: 'HUMSS/ABM'
      },
      'APP-2026-0155': {
        name: 'Garcia, Anne', lrn: '118270140264', category: 'New', programLabel: 'STEM · Grade 11', program: 'shs',
        submittedAt: 'Aug 5, 2026',
        campus: 'Main — Iligan', campusKey: 'main',
        queueStatus: 'pending', evalStatus: 'ready', flag: '',
        grade: 'Grade 11', track: 'ACADEMIC', strand: 'STEM', delivery: ['Modular (Digital)'], deliveryOther: '',
        lrnStatus: 'With LRN', psa: 'PSA-2023-004417', last: 'Garcia', first: 'Anne', middle: 'Reyes', ext: '',
        dob: '09/28/2009', sex: 'Female', age: '17', contact: '0918 555 0155', email: 'anne.garcia@email.com',
        ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: 'With Honors (Grade 10)',
        house: 'Purok 6, Sitio Bagong Silang', province: 'Lanao del Norte', city: 'Iligan City', barangay: 'Hinaplanon',
        enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
        father: 'Rolando Garcia', mother: 'Mila Reyes Garcia', guardian: 'Mila Reyes Garcia',
        guardianContact: '0918 555 0155', regDate: '08/02/2026', referred: 'STEM'
      },
      'APP-2026-0157': {
        name: 'Tan, Reggie', lrn: '119830246571', category: 'Transferee', programLabel: 'TVL-IT · Grade 12', program: 'shs',
        submittedAt: 'Aug 6, 2026',
        campus: 'Bugo — CDO', campusKey: 'bugo',
        queueStatus: 'enrolled', evalStatus: 'ready', flag: '',
        grade: 'Grade 12', track: 'TVL', strand: 'IT', delivery: ['Online'], deliveryOther: '',
        lrnStatus: 'With LRN', psa: 'PSA-2021-008812', last: 'Tan', first: 'Reggie', middle: 'Uy', ext: '',
        dob: '02/11/2008', sex: 'Male', age: '18', contact: '0919 555 0157', email: 'reggie.tan@email.com',
        ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: '',
        house: 'Door 4, Gusa Road', province: 'Misamis Oriental', city: 'Cagayan de Oro', barangay: 'Gusa',
        enrolled: 'Yes', school: 'Gusa Regional Science High School', schoolAddress: 'Gusa, Cagayan de Oro', schoolId: 'GRS-2023-441',
        father: 'Wilson Tan', mother: 'Rosa Uy Tan', guardian: 'Wilson Tan',
        guardianContact: '0919 555 0157', regDate: '08/03/2026', referred: 'TVL'
      },
      'APP-2026-0158': {
        name: 'Mabini, Joy', lrn: '113920557744', category: 'Returnee', programLabel: 'HUMSS · Grade 12', program: 'shs',
        submittedAt: 'Aug 7, 2026',
        campus: 'Kapatagan', campusKey: 'kapatagan',
        queueStatus: 'review', evalStatus: 'review', flag: '',
        grade: 'Grade 12', track: 'ACADEMIC', strand: 'HUMSS', delivery: ['Modular (Print)', 'Radio-based Instruction'], deliveryOther: '',
        lrnStatus: 'Returning (Balik-Aral)', psa: 'PSA-2020-006190', last: 'Mabini', first: 'Joy', middle: 'Sarmiento', ext: '',
        dob: '12/03/2007', sex: 'Female', age: '18', contact: '0920 555 0158', email: 'joy.mabini@email.com',
        ip: 'No', ipOther: '', motherTongue: 'Ilonggo', honors: '',
        house: 'Purok 1, Poblacion', province: 'Lanao del Norte', city: 'Kapatagan', barangay: 'Poblacion',
        enrolled: 'No', school: 'Kapatagan National High School', schoolAddress: 'Poblacion, Kapatagan', schoolId: 'KNH-2022-077',
        father: 'Efren Mabini', mother: 'Dolores Sarmiento Mabini', guardian: 'Dolores Sarmiento Mabini',
        guardianContact: '0920 555 0158', regDate: '08/04/2026', referred: 'GUIDANCE'
      },
      'APP-2026-0159': {
        name: 'Ocampo, Liza', lrn: '121077340915', category: 'New', programLabel: 'TVL-HRM · Grade 11', program: 'shs',
        submittedAt: 'Aug 8, 2026',
        campus: 'Bugo — CDO', campusKey: 'bugo',
        queueStatus: 'pre-enlisted', evalStatus: 'review', flag: '',
        grade: 'Grade 11', track: 'TVL', strand: 'HRM', delivery: ['Online', 'Modular (Digital)'], deliveryOther: '',
        lrnStatus: 'With LRN', psa: 'PSA-2024-001177', last: 'Ocampo', first: 'Liza', middle: 'Padilla', ext: '',
        dob: '06/21/2009', sex: 'Female', age: '17', contact: '0921 555 0159', email: 'liza.ocampo@email.com',
        ip: 'No', ipOther: '', motherTongue: 'Tagalog', honors: '',
        house: 'Purok 8, Brgy. Puerto', province: 'Misamis Oriental', city: 'Cagayan de Oro', barangay: 'Puerto',
        enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
        father: 'Arnel Ocampo', mother: 'Vilma Padilla Ocampo', guardian: 'Arnel Ocampo',
        guardianContact: '0921 555 0159', regDate: '08/05/2026', referred: 'TVL'
      },
      'APP-2026-0160': {
        name: 'Fernandez, Miguel', lrn: '110455829063', category: 'Returnee', programLabel: 'STEM · Grade 12', program: 'shs',
        submittedAt: 'Aug 9, 2026',
        campus: 'Main — Iligan', campusKey: 'main',
        queueStatus: 'pre-enlisted', evalStatus: 'review', flag: '',
        grade: 'Grade 12', track: 'ACADEMIC', strand: 'STEM', delivery: ['Online'], deliveryOther: '',
        lrnStatus: 'Returning (Balik-Aral)', psa: 'PSA-2019-003355', last: 'Fernandez', first: 'Miguel', middle: 'Cortez', ext: 'III',
        dob: '04/08/2007', sex: 'Male', age: '19', contact: '0922 555 0160', email: 'miguel.fernandez@email.com',
        ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: '',
        house: 'Unit 3, Mahogany St.', province: 'Lanao del Norte', city: 'Iligan City', barangay: 'Pala-o',
        enrolled: 'No', school: 'Iligan City National High School', schoolAddress: 'Iligan City', schoolId: 'ICN-2021-902',
        father: 'Miguel Fernandez Jr.', mother: 'Teresa Cortez Fernandez', guardian: 'Teresa Cortez Fernandez',
        guardianContact: '0922 555 0160', regDate: '08/06/2026', referred: 'STEM'
      }
    },
    priorStudents: {
      'STU-2025-0301': {
        name: 'Cruz, Bianca', lrn: '117744552201', lastGradeLevel: 'Grade 11', lastSchoolYear: '2025–2026',
        track: 'ACADEMIC', strand: 'STEM', campus: 'Main — Iligan',
        docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' }
      }
    },
    requirements: {
      'APP-2026-0148': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'pending', 'Certificate of Completion': 'missing', 'Good Moral Certificate': 'missing' }, contact: '0917 555 0148', email: 'juan.delacruz@email.com' },
      'APP-2026-0149': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'pending', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'missing' }, contact: '0918 555 0149', email: 'maria.santos@email.com' },
      'APP-2026-0150': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' }, contact: '0919 555 0150', email: 'pedro.reyes@email.com' },
      'APP-2026-0151': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' }, contact: '0920 555 0151', email: 'angela.lim@email.com' },
      'APP-2026-0152': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' }, contact: '0921 555 0152', email: 'carla.abad@email.com' },
      'APP-2026-0156': { docs: { 'PSA Birth Certificate': 'pending', 'Form 137': 'pending', 'Certificate of Completion': 'pending', 'Good Moral Certificate': 'pending' }, contact: '0917 555 0164', email: 'bianca.cruz@email.com' },
      'APP-2026-0153': { docs: { 'PSA Birth Certificate': 'pending', 'Form 137': 'pending', 'Certificate of Completion': 'pending', 'Good Moral Certificate': 'pending' }, contact: '0922 555 0554', email: 'carlo.bautista@email.com' },
      'APP-2026-0154': { docs: { 'PSA Birth Certificate': 'missing', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' }, contact: '0917 555 0154', email: 'carlo.villanueva@email.com' },
      'APP-2026-0155': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' }, contact: '0918 555 0155', email: 'anne.garcia@email.com' },
      'APP-2026-0157': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'submitted', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'submitted' }, contact: '0919 555 0157', email: 'reggie.tan@email.com' },
      'APP-2026-0158': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'pending', 'Certificate of Completion': 'submitted', 'Good Moral Certificate': 'missing' }, contact: '0920 555 0158', email: 'joy.mabini@email.com' },
      'APP-2026-0159': { docs: { 'PSA Birth Certificate': 'pending', 'Form 137': 'pending', 'Certificate of Completion': 'pending', 'Good Moral Certificate': 'pending' }, contact: '0921 555 0159', email: 'liza.ocampo@email.com' },
      'APP-2026-0160': { docs: { 'PSA Birth Certificate': 'submitted', 'Form 137': 'pending', 'Certificate of Completion': 'missing', 'Good Moral Certificate': 'pending' }, contact: '0922 555 0160', email: 'miguel.fernandez@email.com' }
    },
    audits: {
      'APP-2026-0148': ['Certificate of Completion marked Missing by R. Santos — Aug 3, 2026 04:19 PM', 'PSA Birth Certificate marked Submitted by R. Santos — Aug 3, 2026 09:58 AM'],
      'APP-2026-0149': ['Form 137 marked Pending by R. Santos — Aug 5, 2026 09:14 AM', 'PSA Birth Certificate marked Submitted by R. Santos — Aug 3, 2026 02:41 PM', 'Application created by R. Santos — Aug 2, 2026 10:05 AM'],
      'APP-2026-0150': ['All documents verified by R. Santos — Aug 5, 2026 08:10 AM', 'Application created by R. Santos — Aug 2, 2026 01:18 PM'],
      'APP-2026-0151': ['All documents verified by R. Santos — Aug 6, 2026 08:37 AM', 'Form 137 marked Submitted by R. Santos — Aug 5, 2026 03:02 PM'],
      'APP-2026-0152': ['All documents verified by R. Santos — Aug 6, 2026 10:44 AM', 'Application created by R. Santos — Aug 3, 2026 02:30 PM'],
      'APP-2026-0156': ['Application created by R. Santos — Aug 9, 2026 09:00 AM'],
      'APP-2026-0153': ['Application created by R. Santos — Aug 4, 2026 10:15 AM'],
      'APP-2026-0154': ['PSA Birth Certificate marked Missing by R. Santos — Aug 6, 2026 11:20 AM', 'Application created by R. Santos — Aug 5, 2026 09:40 AM'],
      'APP-2026-0155': ['All documents verified by R. Santos — Aug 6, 2026 02:15 PM', 'Application created by R. Santos — Aug 5, 2026 10:05 AM'],
      'APP-2026-0157': ['Enrollment confirmed by R. Santos — Aug 7, 2026 09:30 AM', 'All documents verified by R. Santos — Aug 6, 2026 04:50 PM'],
      'APP-2026-0158': ['Good Moral Certificate marked Missing by R. Santos — Aug 8, 2026 10:12 AM', 'Application created by R. Santos — Aug 7, 2026 01:35 PM'],
      'APP-2026-0159': ['Application created by R. Santos — Aug 8, 2026 08:55 AM'],
      'APP-2026-0160': ['Certificate of Completion marked Missing by R. Santos — Aug 9, 2026 03:05 PM', 'Application created by R. Santos — Aug 9, 2026 09:20 AM']
    },
    attendance: {
      'APP-2026-0148': { req: 'Incomplete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Present' }, { day: 'Thu', date: 'Aug 6', status: 'Present' }, { day: 'Fri', date: 'Aug 7', status: 'Present' }] },
      'APP-2026-0149': { req: 'Incomplete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Present' }, { day: 'Thu', date: 'Aug 6', status: 'Present' }, { day: 'Fri', date: 'Aug 7', status: 'Pending' }] },
      'APP-2026-0150': { req: 'Complete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Absent' }, { day: 'Thu', date: 'Aug 6', status: 'Present' }, { day: 'Fri', date: 'Aug 7', status: 'Present' }] },
      'APP-2026-0151': { req: 'Complete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Present' }, { day: 'Thu', date: 'Aug 6', status: 'Present' }, { day: 'Fri', date: 'Aug 7', status: 'Present' }] },
      'APP-2026-0152': { req: 'Complete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Present' }, { day: 'Thu', date: 'Aug 6', status: 'Present' }, { day: 'Fri', date: 'Aug 7', status: 'Present' }] },
      'APP-2026-0156': { req: 'Pending', log: [{ day: 'Mon', date: 'Aug 10', status: 'Pending' }, { day: 'Tue', date: 'Aug 11', status: 'Pending' }, { day: 'Wed', date: 'Aug 12', status: 'Pending' }, { day: 'Thu', date: 'Aug 13', status: 'Pending' }, { day: 'Fri', date: 'Aug 14', status: 'Pending' }] },
      'APP-2026-0153': { req: 'Pending', log: [{ day: 'Mon', date: 'Aug 4', status: 'Pending' }, { day: 'Tue', date: 'Aug 5', status: 'Pending' }, { day: 'Wed', date: 'Aug 6', status: 'Pending' }, { day: 'Thu', date: 'Aug 7', status: 'Pending' }, { day: 'Fri', date: 'Aug 8', status: 'Pending' }] },
      'APP-2026-0154': { req: 'Incomplete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Present' }, { day: 'Thu', date: 'Aug 6', status: 'Present' }, { day: 'Fri', date: 'Aug 7', status: 'Present' }] },
      'APP-2026-0155': { req: 'Complete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Absent' }, { day: 'Tue', date: 'Aug 4', status: 'Absent' }, { day: 'Wed', date: 'Aug 5', status: 'Present' }, { day: 'Thu', date: 'Aug 6', status: 'Absent' }, { day: 'Fri', date: 'Aug 7', status: 'Present' }] },
      'APP-2026-0157': { req: 'Complete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Present' }, { day: 'Thu', date: 'Aug 6', status: 'Present' }, { day: 'Fri', date: 'Aug 7', status: 'Present' }] },
      'APP-2026-0158': { req: 'Incomplete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Absent' }, { day: 'Wed', date: 'Aug 5', status: 'Present' }, { day: 'Thu', date: 'Aug 6', status: 'Present' }, { day: 'Fri', date: 'Aug 7', status: 'Pending' }] },
      'APP-2026-0159': { req: 'Pending', log: [{ day: 'Mon', date: 'Aug 10', status: 'Pending' }, { day: 'Tue', date: 'Aug 11', status: 'Pending' }, { day: 'Wed', date: 'Aug 12', status: 'Pending' }, { day: 'Thu', date: 'Aug 13', status: 'Pending' }, { day: 'Fri', date: 'Aug 14', status: 'Pending' }] },
      'APP-2026-0160': { req: 'Incomplete', log: [{ day: 'Mon', date: 'Aug 3', status: 'Present' }, { day: 'Tue', date: 'Aug 4', status: 'Present' }, { day: 'Wed', date: 'Aug 5', status: 'Pending' }, { day: 'Thu', date: 'Aug 6', status: 'Pending' }, { day: 'Fri', date: 'Aug 7', status: 'Pending' }] }
    },
    pairs: {
      'APP-2026-0149': {
        meter: '92% match confidence',
        incoming: { sub: 'Transferee · APP-2026-0149 · Submitted Aug 2, 2026', fields: [['Full name', 'Santos, Maria A.', true], ['Date of birth', '07/22/2008', true], ['Sex', 'Female', true], ['LRN', '108517230184', true], ['Previous school', 'Kapatagan National HS', true], ['Guardian contact', '0918 555 0149', false], ['Status', 'Pre-Enlisted', false]] },
        existing: { sub: 'Student STU-2024-0771 · Record since 2024', fields: [['Full name', 'Santos, Maria Ann', true], ['Date of birth', '07/22/2008', true], ['Sex', 'Female', true], ['LRN', '108517230184', true], ['Previous school', 'Kapatagan National HS', true], ['Guardian contact', '0917 555 0991', false], ['Status', 'Inactive (Withdrawn)', false]] }
      },
      'APP-2026-0140': {
        meter: '81% match confidence',
        incoming: { sub: 'Returnee · APP-2026-0140 · Submitted Jul 28, 2026', fields: [['Full name', 'Garcia, Ana', true], ['Date of birth', '01/15/2008', true], ['Sex', 'Female', true], ['LRN', '112377981523', true], ['Previous school', 'St. Michael Academy', false], ['Guardian contact', '0923 555 0140', false], ['Status', 'Pending Requirements', false]] },
        existing: { sub: 'Student STU-2022-0503 · Record since 2022', fields: [['Full name', 'Garcia, Ana Marie', true], ['Date of birth', '01/15/2008', true], ['Sex', 'Female', true], ['LRN', '112377981523', true], ['Previous school', 'Tambo Central School', false], ['Guardian contact', '0923 555 0140', false], ['Status', 'Enrolled', false]] }
      },
      'APP-2026-0153': {
        meter: '78% match confidence',
        incoming: { sub: 'Transferee · APP-2026-0153 · Submitted Aug 4, 2026', fields: [['Full name', 'Bautista, Carlo', false], ['Date of birth', '03/09/2009', true], ['Sex', 'Male', true], ['LRN', '121456789012', false], ['Previous school', 'Iligan City NHS', true], ['Guardian contact', '0922 555 0153', true], ['Status', 'Pre-Enlisted', false]] },
        existing: { sub: 'Student STU-2023-1210 · Record since 2023', fields: [['Full name', 'Bautista, Carlos D.', false], ['Date of birth', '03/09/2009', true], ['Sex', 'Male', true], ['LRN', '120987654321', false], ['Previous school', 'Iligan City NHS', true], ['Guardian contact', '0922 555 0153', true], ['Status', 'Active', false]] }      }
    },
    pairStatus: {},
    records: {
      'STU-2026-0452': { app: 'APP-2026-0152', name: 'Abad, Carla', initials: 'CA', meta: 'Grade 11 · ABM · Main — Iligan', status: 'pending', category: 'New Student — Regular', ref: 'CSH-0096', bracket: 'Bracket A', req: ['Complete', 'success'], reqDate: 'Aug 6, 2026', attend: ['present', 'present', 'present', 'present', 'present'], attendStatus: 'Verified', confirmable: true, confirmHint: 'Requirements complete AND attendance verified. Ready to confirm enrollment.' },
      'STU-2026-0453': { app: 'APP-2026-0148', name: 'Dela Cruz, Juan', initials: 'JD', meta: 'Grade 11 · HUMSS · Main — Iligan', status: 'pending', category: 'New Student — Regular', ref: 'CSH-0089', bracket: 'Bracket A', req: ['Incomplete', 'danger'], reqDate: 'Aug 3, 2026', attend: ['present', 'present', 'present', 'present', 'present'], attendStatus: 'Verified', confirmable: false, confirmHint: 'Cannot confirm yet — requirements are still incomplete. Attendance is fully verified.' },
      'STU-2026-0454': { app: 'APP-2026-0149', name: 'Santos, Maria A.', initials: 'MS', meta: 'Grade 11 · STEM · Main — Iligan', status: 'pending', category: 'Transferee — Voucher (ESC)', ref: 'CSH-0091', bracket: 'Bracket B', req: ['Incomplete', 'danger'], reqDate: 'Aug 5, 2026', attend: ['present', 'present', 'present', 'present', 'pending'], attendStatus: 'Not yet verified', confirmable: false, confirmHint: 'Cannot confirm yet — requirements are incomplete and attendance is not fully verified.' },
      'STU-2026-0455': { app: 'APP-2026-0150', name: 'Reyes, Pedro', initials: 'PR', meta: 'Grade 11 · TVL-Cookery · Bugo — CDO', status: 'pending', category: 'New Student — Regular', ref: 'CSH-0093', bracket: 'Bracket A', req: ['Complete', 'success'], reqDate: 'Aug 5, 2026', attend: ['present', 'present', 'absent', 'present', 'present'], attendStatus: 'Not yet verified', confirmable: false, confirmHint: 'Requirements complete — attendance is not yet fully verified.' },
      'STU-2026-0451': { app: 'APP-2026-0151', name: 'Lim, Angela', initials: 'AL', meta: '1st Year · BSOA · Kapatagan', status: 'enrolled', category: 'Returnee — Regular', ref: 'CSH-0095', bracket: 'Bracket B', req: ['Complete', 'success'], reqDate: 'Aug 6, 2026', attend: ['present', 'present', 'present', 'present', 'present'], attendStatus: 'Verified', confirmable: false, confirmHint: 'Student is already officially enrolled. Assign a section to complete sectioning.' },
      'STU-2026-0457': { app: 'APP-2026-0157', name: 'Tan, Reggie', initials: 'RT', meta: 'Grade 12 · TVL-IT · Bugo — CDO', status: 'enrolled', category: 'Transferee — Regular', ref: 'CSH-0098', bracket: 'Bracket A', req: ['Complete', 'success'], reqDate: 'Aug 6, 2026', attend: ['present', 'present', 'present', 'present', 'present'], attendStatus: 'Verified', confirmable: false, confirmHint: 'Student is already officially enrolled and assigned to a section.' },
      'STU-2026-0458': { app: 'APP-2026-0154', name: 'Villanueva, Carlo', initials: 'CV', meta: 'Grade 11 · ABM · Main — Iligan', status: 'pending', category: 'New Student — Regular', ref: 'CSH-0099', bracket: 'Bracket A', req: ['Incomplete', 'danger'], reqDate: 'Aug 6, 2026', attend: ['present', 'present', 'present', 'present', 'present'], attendStatus: 'Verified', confirmable: false, confirmHint: 'Cannot confirm yet — the PSA Birth Certificate is still missing.' },
      'STU-2026-0459': { app: 'APP-2026-0155', name: 'Garcia, Anne', initials: 'AG', meta: 'Grade 11 · STEM · Main — Iligan', status: 'pending', category: 'New Student — Regular', ref: 'CSH-0100', bracket: 'Bracket B', req: ['Complete', 'success'], reqDate: 'Aug 6, 2026', attend: ['absent', 'absent', 'present', 'absent', 'present'], attendStatus: 'Not yet verified', confirmable: false, confirmHint: 'Requirements complete — attendance is below the first-week threshold.' }
    },
    sections: { 'STU-2026-0454': 'STEM-B', 'STU-2026-0455': 'Cookery-1', 'STU-2026-0457': 'ICT-2' },
    monitoring: {
      'APP-2026-0153': {
        status: 'Open',
        action: '',
        notes: '',
        by: '',
        when: ''
      }
    },    reportLog: [
      { when: 'Aug 7, 2026 10:42 AM', title: 'Enrollment Summary', criteria: 'Enrollment Summary · 2026-2027 · First Semester · Main Campus · All Tracks · Aug 01, 2026 - Aug 31, 2026', by: 'R. Santos', format: 'PDF' },
      { when: 'Aug 6, 2026 03:20 PM', title: 'Missing Requirements', criteria: 'Missing Requirements · 2026-2027 · First Semester · All Campuses · All Tracks · Jul 01, 2026 - Aug 31, 2026', by: 'R. Santos', format: 'CSV' }
    ],
    // Form 137 / 138 / TOR requests handled by the registrar. Backs the
    // "Registrar Documents" report so it reads from the store like the rest.
    documentRequests: [
      { id: 'DOC-2026-0011', studentId: 'STU-2026-0187', student: 'Lim, Angela', document: 'Form 138 (Report Card)', status: 'Issued', released: 'Aug 6, 2026', requestedBy: 'Guardian' },
      { id: 'DOC-2026-0012', studentId: 'STU-2026-0185', student: 'Santos, Maria', document: 'Form 137 (Transcript)', status: 'For review', released: '', requestedBy: 'Receiving school' },
      { id: 'DOC-2026-0013', studentId: 'STU-2026-0184', student: 'Dela Cruz, Juan', document: 'Certificate of Enrolment', status: 'Issued', released: 'Aug 5, 2026', requestedBy: 'Student' },
      { id: 'DOC-2026-0014', studentId: 'STU-2026-0186', student: 'Reyes, Pedro', document: 'Form 138 (Report Card)', status: 'Pending docs', released: '', requestedBy: 'Guardian' },
      { id: 'DOC-2026-0015', studentId: 'STU-2026-0456', student: 'Bautista, Carlo', document: 'Form 137 (Transcript)', status: 'For review', released: '', requestedBy: 'Registrar (transferee intake)' },
      { id: 'DOC-2026-0016', studentId: 'STU-2026-0457', student: 'Tan, Reggie', document: 'TOR', status: 'Issued', released: 'Aug 8, 2026', requestedBy: 'Student' }
    ],
    academic: {
      schemaVersion: 2,
      policy: {
        passingGrade: 75,
        confirmed: false,
        components: [
          { key: 'written', label: 'Written Work', weight: 40 },
          { key: 'performance', label: 'Performance Tasks', weight: 50 },
          { key: 'other', label: 'Recitation / Other', weight: 10 }
        ],
        assessments: [
          { key: 'ww1', label: 'Written Work 1', category: 'written', max: 20 },
          { key: 'ww2', label: 'Written Work 2', category: 'written', max: 50 },
          { key: 'pt1', label: 'Performance Task 1', category: 'performance', max: 100 },
          { key: 'other1', label: 'Recitation / Other', category: 'other', max: 10 }
        ]
      },
      periods: {
        'SY2026-Q1': { id: 'SY2026-Q1', schoolYear: '2026–2027', label: 'Quarter 1', deadline: 'Aug 7, 2026', status: 'open', openedBy: 'R. Santos', openedAt: 'Jul 29, 2026 8:00 AM', finalizedBy: '', finalizedAt: '' },
        'SY2025-Q4': { id: 'SY2025-Q4', schoolYear: '2025–2026', label: 'Quarter 4', deadline: 'Mar 28, 2026', status: 'finalized', openedBy: 'R. Santos', openedAt: 'Mar 1, 2026 8:00 AM', finalizedBy: 'R. Santos', finalizedAt: 'Apr 1, 2026 4:30 PM' }
      },
      students: {
        'STU-2026-0184': { appId: 'APP-2026-0148', name: 'Dela Cruz, Juan', initials: 'JD', lrn: '119270140008', gradeLevel: 'Grade 11', section: 'STEM A', campus: 'Iligan Main Campus', adviser: 'Nicole Chua', sectionHistory: [{ label: 'Grade 11 · STEM A', detail: 'SY 2026–2027 · Iligan Main Campus' }, { label: 'Grade 10 · Rizal', detail: 'SY 2025–2026 · Previous school' }] },
        'STU-2026-0185': { appId: 'APP-2026-0149', name: 'Santos, Maria', initials: 'MS', lrn: '108517230184', gradeLevel: 'Grade 11', section: 'STEM A', campus: 'Iligan Main Campus', adviser: 'Nicole Chua', sectionHistory: [{ label: 'Grade 11 · STEM A', detail: 'SY 2026–2027 · Iligan Main Campus' }, { label: 'Grade 10 · STEM', detail: 'SY 2025–2026 · Kapatagan NHS' }] },
        'STU-2026-0186': { appId: 'APP-2026-0150', name: 'Reyes, Pedro', initials: 'PR', lrn: '120381650117', gradeLevel: 'Grade 11', section: 'STEM A', campus: 'Iligan Main Campus', adviser: 'Nicole Chua', sectionHistory: [{ label: 'Grade 11 · STEM A', detail: 'SY 2026–2027 · Iligan Main Campus' }, { label: 'Grade 10 · Mabini', detail: 'SY 2025–2026 · Previous school' }] },
        'STU-2026-0187': { appId: 'APP-2026-0151', name: 'Lim, Angela', initials: 'AL', lrn: '112500710292', gradeLevel: 'Grade 11', section: 'STEM A', campus: 'Iligan Main Campus', adviser: 'Nicole Chua', sectionHistory: [{ label: 'Grade 11 · STEM A', detail: 'SY 2026–2027 · Iligan Main Campus' }, { label: 'Grade 10 · Bonifacio', detail: 'SY 2025–2026 · Previous school' }] },
        'STU-2026-0456': { appId: 'APP-2026-0153', name: 'Bautista, Carlo', initials: 'CB', lrn: '121456789012', gradeLevel: 'Grade 11', section: 'Cookery-1', campus: 'Bugo — CDO', adviser: 'Nicole Chua', sectionHistory: [{ label: 'Grade 11 · Cookery-1', detail: 'SY 2026–2027 · Bugo — CDO' }, { label: 'Grade 10 · Iligan City NHS', detail: 'SY 2025–2026 · Previous school' }] }
      },
      classes: {
        'CLS-GM-STEMA': { id: 'CLS-GM-STEMA', subject: 'General Mathematics', section: 'STEM A', gradeLevel: 'Grade 11', room: 'Room 204', teacher: 'Michael Reyes', periodId: 'SY2026-Q1', studentIds: ['STU-2026-0184', 'STU-2026-0185', 'STU-2026-0186', 'STU-2026-0187'], status: 'draft', submittedBy: '', submittedAt: '' },
        'CLS-STAT-ABMA': { id: 'CLS-STAT-ABMA', subject: 'Statistics and Probability', section: 'ABM A', gradeLevel: 'Grade 11', room: 'Room 301', teacher: 'Michael Reyes', periodId: 'SY2026-Q1', studentIds: ['STU-2026-0185', 'STU-2026-0187'], status: 'submitted', submittedBy: 'Michael Reyes', submittedAt: 'Aug 4, 2026 11:05 AM' },
        'CLS-OC-STEMA': { id: 'CLS-OC-STEMA', subject: 'Oral Communication', section: 'STEM A', gradeLevel: 'Grade 11', room: 'Room 202', teacher: 'Michael Reyes', periodId: 'SY2026-Q1', studentIds: ['STU-2026-0184', 'STU-2026-0185', 'STU-2026-0186', 'STU-2026-0187'], status: 'submitted', submittedBy: 'Michael Reyes', submittedAt: 'Aug 4, 2026 9:30 AM' },
        'CLS-CA-COOKERY1': { id: 'CLS-CA-COOKERY1', subject: 'Cookery Fundamentals', section: 'Cookery-1', gradeLevel: 'Grade 11', room: 'Cookery Lab 1', teacher: 'Michael Reyes', periodId: 'SY2026-Q1', studentIds: ['STU-2026-0456'], status: 'draft', submittedBy: '', submittedAt: '' }
      },
      grades: {
        'CLS-GM-STEMA': {
          'STU-2026-0184': { scores: { ww1: 18, ww2: 42, pt1: 88, other1: 9 }, quarterGrade: 87, status: 'draft', updatedAt: 'Aug 4, 2026 11:20 AM' },
          'STU-2026-0185': { scores: { ww1: 20, ww2: 46, pt1: 92, other1: 10 }, quarterGrade: 94, status: 'draft', updatedAt: 'Aug 4, 2026 11:20 AM' },
          'STU-2026-0186': { scores: { ww1: 14, ww2: null, pt1: 76, other1: 8 }, quarterGrade: null, status: 'incomplete', updatedAt: 'Aug 4, 2026 11:20 AM' },
          'STU-2026-0187': { scores: { ww1: 17, ww2: 40, pt1: 85, other1: 9 }, quarterGrade: 84, status: 'draft', updatedAt: 'Aug 4, 2026 11:20 AM' }
        },
        'CLS-STAT-ABMA': {
          'STU-2026-0185': { scores: { ww1: 19, ww2: 44, pt1: 91, other1: 9 }, quarterGrade: 91, status: 'submitted', updatedAt: 'Aug 4, 2026 11:05 AM' },
          'STU-2026-0187': { scores: { ww1: 16, ww2: 41, pt1: 84, other1: 9 }, quarterGrade: 84, status: 'submitted', updatedAt: 'Aug 4, 2026 11:05 AM' }
        },
        'CLS-OC-STEMA': {
          'STU-2026-0184': { scores: { ww1: 18, ww2: 43, pt1: 90, other1: 9 }, quarterGrade: 89, status: 'submitted', updatedAt: 'Aug 4, 2026 9:30 AM' },
          'STU-2026-0185': { scores: { ww1: 19, ww2: 45, pt1: 93, other1: 10 }, quarterGrade: 93, status: 'submitted', updatedAt: 'Aug 4, 2026 9:30 AM' },
          'STU-2026-0186': { scores: { ww1: 15, ww2: 38, pt1: 79, other1: 8 }, quarterGrade: 79, status: 'submitted', updatedAt: 'Aug 4, 2026 9:30 AM' },
          'STU-2026-0187': { scores: { ww1: 17, ww2: 42, pt1: 86, other1: 9 }, quarterGrade: 86, status: 'submitted', updatedAt: 'Aug 4, 2026 9:30 AM' }
        },
        'CLS-CA-COOKERY1': {
          'STU-2026-0456': { scores: { ww1: 17, ww2: 38, pt1: null, other1: 8 }, quarterGrade: null, status: 'incomplete', updatedAt: 'Aug 7, 2026 9:15 AM' }
        }
      },
      history: {
        'STU-2026-0184': [{ subject: 'Oral Communication', schoolYear: '2026–2027', quarters: { q1: 89, q2: null, q3: null, q4: null }, finalGrade: null }, { subject: 'General Mathematics', schoolYear: '2026–2027', quarters: { q1: 87, q2: null, q3: null, q4: null }, finalGrade: null }, { subject: 'Earth Science', schoolYear: '2026–2027', quarters: { q1: 86, q2: null, q3: null, q4: null }, finalGrade: null }],
        'STU-2026-0185': [{ subject: 'Oral Communication', schoolYear: '2026–2027', quarters: { q1: 93, q2: null, q3: null, q4: null }, finalGrade: null }, { subject: 'General Mathematics', schoolYear: '2026–2027', quarters: { q1: 94, q2: null, q3: null, q4: null }, finalGrade: null }, { subject: 'Statistics and Probability', schoolYear: '2026–2027', quarters: { q1: 91, q2: null, q3: null, q4: null }, finalGrade: null }],
        'STU-2026-0186': [{ subject: 'Oral Communication', schoolYear: '2026–2027', quarters: { q1: 79, q2: null, q3: null, q4: null }, finalGrade: null }, { subject: 'General Mathematics', schoolYear: '2025–2026', quarters: { q1: 76, q2: 75, q3: 74, q4: 74 }, finalGrade: 75 }, { subject: 'Earth Science', schoolYear: '2026–2027', quarters: { q1: 78, q2: null, q3: null, q4: null }, finalGrade: null }],
        'STU-2026-0187': [{ subject: 'Oral Communication', schoolYear: '2026–2027', quarters: { q1: 86, q2: null, q3: null, q4: null }, finalGrade: null }, { subject: 'General Mathematics', schoolYear: '2026–2027', quarters: { q1: 84, q2: null, q3: null, q4: null }, finalGrade: null }, { subject: 'Statistics and Probability', schoolYear: '2026–2027', quarters: { q1: 84, q2: null, q3: null, q4: null }, finalGrade: null }],
        'STU-2026-0456': [{ subject: 'Cookery Fundamentals', schoolYear: '2026–2027', quarters: { q1: null, q2: null, q3: null, q4: null }, finalGrade: null }]
      },
      adviserNotes: {
        'STU-2026-0184': [{ type: 'Attendance follow-up', priority: 'Standard', text: 'Student recorded three absences this period. Coordinate with subject teachers before parent follow-up.', status: 'Active', adviser: 'Nicole Chua', when: 'Aug 4, 2026 2:20 PM' }, { type: 'Parent contacted', priority: 'Standard', text: 'Attendance concern discussed by phone; guardian acknowledged the notice.', status: 'Resolved', adviser: 'Nicole Chua', when: 'Aug 4, 2026 1:45 PM' }],
        'STU-2026-0185': [],
        'STU-2026-0186': [{ type: 'Academic follow-up', priority: 'High', text: 'Missing Written Work 2 score requires completion.', status: 'Active', adviser: 'Nicole Chua', when: 'Aug 4, 2026 1:25 PM' }],
        'STU-2026-0187': [{ type: 'Requirements reminder', priority: 'Standard', text: 'Pending enrollment document follow-up.', status: 'Active', adviser: 'Nicole Chua', when: 'Aug 4, 2026 2:05 PM' }],
        'STU-2026-0456': []
      },
      incompleteCases: {
        'INC-2026-001': { id: 'INC-2026-001', studentId: 'STU-2026-0186', classId: 'CLS-GM-STEMA', periodId: 'SY2026-Q1', assessmentKey: 'ww2', status: 'pending_teacher', notes: [{ text: 'Teacher notified about the missing Written Work 2 score.', by: 'R. Santos', when: 'Aug 5, 2026 9:15 AM' }], resolvedAt: '', resolvedBy: '' },
        'INC-2026-002': { id: 'INC-2026-002', studentId: 'STU-2026-0456', classId: 'CLS-CA-COOKERY1', periodId: 'SY2026-Q1', assessmentKey: 'pt1', status: 'pending_teacher', notes: [{ text: 'Performance Task 1 score is pending after the transferee’s first practical assessment.', by: 'R. Santos', when: 'Aug 7, 2026 9:15 AM' }], resolvedAt: '', resolvedBy: '' }
      },
      corrections: {
        'GCR-2026-004': { id: 'GCR-2026-004', studentId: 'STU-2026-0186', subject: 'General Mathematics', schoolYear: '2025–2026', quarterKey: 'q4', periodId: 'SY2025-Q4', currentGrade: 74, proposedGrade: 78, reason: 'Completed requirement', explanation: 'Student completed the documented missing performance task within the authorized completion period.', attachment: { name: 'completion-evidence.pdf', type: 'application/pdf', size: 184320 }, status: 'principal_review', submittedBy: 'Michael Reyes', submittedAt: 'Aug 4, 2026 10:32 AM', audit: [{ action: 'Submitted by subject teacher', by: 'Michael Reyes', when: 'Aug 4, 2026 10:32 AM' }] }
      },
      audit: [{ action: 'Quarter 1 grading period opened', by: 'R. Santos', when: 'Jul 29, 2026 8:00 AM' }]
    }
  };

  // Section catalog — real capacity/building/room data, replacing the old
  // decorative hardcoded table. `baseline` represents seats already used by
  // other cohorts/programs this prototype doesn't model (kept so the demo
  // still has a genuinely full section to show the capacity block).
  const SECTIONS = {
    'STEM-A': { track: 'ACADEMIC', strand: 'STEM', campus: 'Main — Iligan', building: 'Iligan Main — Bldg. A (Senior High Wing)', room: 'Room 201', capacity: 40, baseline: 40 },
    'STEM-B': { track: 'ACADEMIC', strand: 'STEM', campus: 'Main — Iligan', building: 'Iligan Main — Bldg. A (Senior High Wing)', room: 'Room 202', capacity: 40, baseline: 35 },
    'STEM-C': { track: 'ACADEMIC', strand: 'STEM', campus: 'Main — Iligan', building: 'Iligan Main — Bldg. A (Senior High Wing)', room: 'Room 203', capacity: 40, baseline: 22 },
    'HUMSS-1': { track: 'ACADEMIC', strand: 'HUMSS', campus: 'Main — Iligan', building: 'Iligan Main — Bldg. B (Annex, rented)', room: 'Room 105', capacity: 40, baseline: 0 },
    'ABM-1': { track: 'ACADEMIC', strand: 'ABM', campus: 'Main — Iligan', building: 'Iligan Main — Bldg. B (Annex, rented)', room: 'Room 106', capacity: 40, baseline: 0 },
    'GAS-1': { track: 'ACADEMIC', strand: 'GAS', campus: 'Kapatagan', building: 'Kapatagan Campus — Main Building', room: 'Room 3', capacity: 35, baseline: 0 },
    'Cookery-1': { track: 'TVL', strand: 'CA', campus: 'Bugo — CDO', building: 'Bugo Campus — TVL Workshop Building', room: 'Culinary Lab 1', capacity: 25, baseline: 18 },
    'ICT-2': { track: 'TVL', strand: 'IT', campus: 'Bugo — CDO', building: 'Bugo Campus — TVL Workshop Building', room: 'Computer Lab 2', capacity: 30, baseline: 24 },
    'HRM-1': { track: 'TVL', strand: 'HRM', campus: 'Bugo — CDO', building: 'Bugo Campus — TVL Workshop Building', room: 'Hospitality Lab', capacity: 30, baseline: 12 },
    // Kapatagan and the remaining TVL specialisations, so every campus can
    // actually place the strands it accepts applications for.
    'STEM-K1': { track: 'ACADEMIC', strand: 'STEM', campus: 'Kapatagan', building: 'Kapatagan Campus — Main Building', room: 'Room 5', capacity: 35, baseline: 9 },
    'HUMSS-K1': { track: 'ACADEMIC', strand: 'HUMSS', campus: 'Kapatagan', building: 'Kapatagan Campus — Main Building', room: 'Room 6', capacity: 35, baseline: 6 },
    'ABM-K1': { track: 'ACADEMIC', strand: 'ABM', campus: 'Kapatagan', building: 'Kapatagan Campus — Annex', room: 'Room 8', capacity: 35, baseline: 4 },
    'EIM-1': { track: 'TVL', strand: 'EIM', campus: 'Bugo — CDO', building: 'Bugo Campus — TVL Workshop Building', room: 'Electrical Shop', capacity: 30, baseline: 10 },
    'OM-1': { track: 'TVL', strand: 'OM', campus: 'Main — Iligan', building: 'Iligan Main — Bldg. B (Annex, rented)', room: 'Room 108', capacity: 30, baseline: 7 },
    'PN-1': { track: 'TVL', strand: 'PN', campus: 'Main — Iligan', building: 'Iligan Main — Bldg. C (Health Sciences)', room: 'Nursing Skills Lab', capacity: 30, baseline: 11 },
    'CE-1': { track: 'TVL', strand: 'CE', campus: 'Bugo — CDO', building: 'Bugo Campus — TVL Workshop Building', room: 'Computer Lab 1', capacity: 30, baseline: 15 }
  };

  // ---------------------------------------------------------------------------
  // Bulk demo cohort.
  //
  // The handwritten records in SEED are the ones the scripted walkthroughs
  // depend on; they are never touched here. The reports, though, only mean
  // something with a real cohort behind them, so the rest of the student body is
  // generated below from regional name and address pools (Iligan City, Cagayan
  // de Oro, Lanao del Norte, Misamis Occidental).
  //
  // Generation is deterministic — a fixed-seed PRNG, never Math.random() — so
  // the same cohort appears on every load and after "Reset Demo". A rehearsed
  // demo therefore shows exactly the same figures as the live one.
  // ---------------------------------------------------------------------------
  const BULK_COUNT = 100;
  const BULK_FIRST_APP = 161;   // APP-2026-0161 onward; below that is handwritten
  const BULK_FIRST_STU = 500;   // STU-2026-0500 onward, clear of the seeded IDs

  function makeRng(seed) {
    let t = seed >>> 0;
    return function rng() {
      t = (t + 0x6D2B79F5) >>> 0;
      let x = Math.imul(t ^ (t >>> 15), 1 | t);
      x = (x + Math.imul(x ^ (x >>> 7), 61 | x)) ^ x;
      return ((x ^ (x >>> 14)) >>> 0) / 4294967296;
    };
  }

  const SURNAMES = ['Abella', 'Acosta', 'Alcantara', 'Amper', 'Aquino', 'Baguio', 'Bacus', 'Balacuit', 'Bello', 'Cabalhin', 'Cabrera', 'Cagampang', 'Calderon', 'Canoy', 'Capistrano', 'Carbonell', 'Chavez', 'Cortes', 'Dagondon', 'Daligdig', 'Deloso', 'Dumaguing', 'Echavez', 'Emano', 'Enriquez', 'Escalante', 'Espina', 'Fernandez', 'Flores', 'Fuentes', 'Gabuya', 'Gamboa', 'Gaviola', 'Gerona', 'Gonzaga', 'Gutierrez', 'Hinlo', 'Jamisola', 'Labadan', 'Lagrosas', 'Limbaga', 'Lopez', 'Lumapas', 'Macasero', 'Maglangit', 'Mangubat', 'Maslog', 'Melendez', 'Montalban', 'Nacua', 'Neri', 'Obsioma', 'Olvido', 'Pacana', 'Padayhag', 'Pagayon', 'Palma', 'Quijano', 'Ramiro', 'Rodriguez', 'Roa', 'Sabuero', 'Salvana', 'Sanchez', 'Seno', 'Sudaria', 'Tagalog', 'Tenorio', 'Ubaldo', 'Uy', 'Valmores', 'Velez', 'Yap', 'Zamora', 'Alonto', 'Balindong', 'Dimaporo', 'Lucman', 'Macarambon', 'Maruhom', 'Pangandaman', 'Sarip', 'Tomawis'];
  const NAMES_M = ['Aljun', 'Angelo', 'Benjie', 'Carlito', 'Christian', 'Dexter', 'Edgar', 'Emmanuel', 'Francis', 'Gerald', 'Harold', 'Ian', 'Jayson', 'Jerome', 'Joemar', 'Jomari', 'Kenneth', 'Kevin', 'Lloyd', 'Marlon', 'Nathaniel', 'Nico', 'Oliver', 'Patrick', 'Rafael', 'Randy', 'Renz', 'Rico', 'Rodel', 'Ryan', 'Samuel', 'Sherwin', 'Vincent', 'Abdul', 'Ahmad', 'Alinader', 'Faisal', 'Norodin', 'Saidamen'];
  const NAMES_F = ['Aiza', 'Alexa', 'Angelica', 'Bea', 'Camille', 'Charmaine', 'Cherry', 'Cristina', 'Daisy', 'Danica', 'Divine', 'Ellaine', 'Erika', 'Fatima', 'Gemma', 'Grace', 'Hazel', 'Ivy', 'Jamaica', 'Jenny', 'Jessa', 'Joan', 'Kimberly', 'Kristine', 'Lorena', 'Maricar', 'Marijoy', 'Melody', 'Nica', 'Princess', 'Rhea', 'Roselle', 'Sheila', 'Trisha', 'Yvonne', 'Ainah', 'Farhana', 'Johaira', 'Norhana', 'Sittie'];

  // weight = roughly how many of the cohort come from each locality, so the
  // campus split in the Enrollment Summary looks like a real catchment area.
  const LOCALITIES = [
    { weight: 34, city: 'Iligan City', province: 'Lanao del Norte', campus: 'Main — Iligan', campusKey: 'main', barangays: ['Pala-o', 'Tibanga', 'Hinaplanon', 'Tubod', 'Saray', 'Mahayahay', 'San Miguel', 'Villa Verde', 'Santiago', 'Bara-as', 'Buru-un', 'Dalipuga', 'Kiwalan', 'Maria Cristina', 'Suarez', 'Ubaldo Laya', 'Acmac', 'Poblacion'], schools: ['Iligan City National High School', 'Iligan City East National High School', 'Lanao del Norte National High School', 'MSU-IIT Integrated Developmental School', 'Saint Peter College'] },
    { weight: 3, city: 'Tubod', province: 'Lanao del Norte', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Poblacion', 'Baroy Daku', 'Dalama', 'Pinilingan'], schools: ['Tubod National High School', 'Lanao del Norte National High School'] },
    { weight: 3, city: 'Lala', province: 'Lanao del Norte', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Maranding', 'Poblacion', 'Darumawang Bucana', 'Tenazas'], schools: ['Maranding National High School', 'Lala National High School'] },
    { weight: 5, city: 'Kapatagan', province: 'Lanao del Norte', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Poblacion', 'Bansarvil', 'Buenavista', 'Malinas', 'Taguitic', 'Butadon'], schools: ['Kapatagan National High School', 'Bansarvil National High School'] },
    { weight: 2, city: 'Baroy', province: 'Lanao del Norte', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Poblacion', 'Andil', 'Bagong Bayan'], schools: ['Baroy National High School'] },
    { weight: 2, city: 'Kolambugan', province: 'Lanao del Norte', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Poblacion', 'Inudaran', 'Muntay'], schools: ['Kolambugan National High School'] },
    { weight: 2, city: 'Sultan Naga Dimaporo', province: 'Lanao del Norte', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Payong', 'Pandanan', 'Karim'], schools: ['Sultan Naga Dimaporo National High School'] },
    { weight: 2, city: 'Linamon', province: 'Lanao del Norte', campus: 'Main — Iligan', campusKey: 'main', barangays: ['Poblacion', 'Larapan', 'Samburon'], schools: ['Linamon National High School'] },
    { weight: 2, city: 'Bacolod', province: 'Lanao del Norte', campus: 'Main — Iligan', campusKey: 'main', barangays: ['Poblacion', 'Delabayan', 'Esperanza'], schools: ['Bacolod National High School'] },
    { weight: 2, city: 'Maigo', province: 'Lanao del Norte', campus: 'Main — Iligan', campusKey: 'main', barangays: ['Poblacion', 'Balagatasa', 'Inoboran'], schools: ['Maigo National High School'] },
    { weight: 26, city: 'Cagayan de Oro', province: 'Misamis Oriental', campus: 'Bugo — CDO', campusKey: 'bugo', barangays: ['Bugo', 'Puerto', 'Gusa', 'Lapasan', 'Carmen', 'Nazareth', 'Kauswagan', 'Balulang', 'Macasandig', 'Agusan', 'Tablon', 'Bulua', 'Patag', 'Camaman-an'], schools: ['Cagayan de Oro City National High School', 'Gusa Regional Science High School', 'Bugo National High School', 'Misamis Oriental General Comprehensive High School'] },
    { weight: 6, city: 'Ozamiz City', province: 'Misamis Occidental', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Aguada', 'Carangan', 'Catadman-Manabay', 'Lam-an', 'Tinago', 'Banadero'], schools: ['Ozamiz City National High School', 'La Salle University Integrated School'] },
    { weight: 4, city: 'Oroquieta City', province: 'Misamis Occidental', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Poblacion I', 'Layawan', 'Taboc Norte', 'Mobod'], schools: ['Oroquieta City National High School', 'Misamis Occidental National High School'] },
    { weight: 3, city: 'Tangub City', province: 'Misamis Occidental', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Maloro', 'Poblacion', 'Migpange'], schools: ['Tangub City National High School'] },
    { weight: 2, city: 'Clarin', province: 'Misamis Occidental', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Poblacion', 'Lupagan'], schools: ['Clarin National High School'] },
    { weight: 2, city: 'Jimenez', province: 'Misamis Occidental', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Poblacion', 'Naga', 'Taraka'], schools: ['Jimenez National High School'] },
    { weight: 1, city: 'Plaridel', province: 'Misamis Occidental', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Poblacion', 'Lao Proper'], schools: ['Plaridel National High School'] },
    { weight: 1, city: 'Tudela', province: 'Misamis Occidental', campus: 'Kapatagan', campusKey: 'kapatagan', barangays: ['Poblacion', 'Barra'], schools: ['Tudela National High School'] }
  ];
  const LOCALITY_PICKS = LOCALITIES.flatMap(place => Array(place.weight).fill(place));

  const STRAND_POOL = [
    { track: 'ACADEMIC', strand: 'STEM' }, { track: 'ACADEMIC', strand: 'STEM' },
    { track: 'ACADEMIC', strand: 'HUMSS' }, { track: 'ACADEMIC', strand: 'HUMSS' },
    { track: 'ACADEMIC', strand: 'ABM' }, { track: 'ACADEMIC', strand: 'GAS' },
    { track: 'TVL', strand: 'CA' }, { track: 'TVL', strand: 'IT' },
    { track: 'TVL', strand: 'HRM' }, { track: 'TVL', strand: 'EIM' },
    { track: 'TVL', strand: 'CE' }, { track: 'TVL', strand: 'OM' }, { track: 'TVL', strand: 'PN' }
  ];
  const CATEGORY_POOL = ['New', 'New', 'New', 'New', 'Transferee', 'Transferee', 'Returnee'];
  const QUEUE_POOL = ['pre-enlisted', 'pre-enlisted', 'pre-enlisted', 'pending', 'pending', 'review', 'review', 'enrolled', 'enrolled', 'enrolled'];
  const MOTHER_TONGUES = ['Bisaya/Cebuano', 'Bisaya/Cebuano', 'Bisaya/Cebuano', 'Maranao', 'Ilonggo', 'Hiligaynon', 'Tagalog'];
  const DELIVERY_POOL = [['Online'], ['Modular (Print)'], ['Modular (Digital)'], ['Online', 'Modular (Digital)'], ['Educational TV'], ['Modular (Print)', 'Radio-based Instruction']];
  const REFERRERS = ['STEM', 'HUMSS/ABM', 'TVL', 'PE/FILIPINO', 'ENGLISH', 'STAFF', 'GUIDANCE'];
  const DOC_NAMES = ['PSA Birth Certificate', 'Form 137', 'Certificate of Completion', 'Good Moral Certificate'];

  // Requirement mixes, weighted so roughly half the cohort is complete and the
  // rest gives the Missing Requirements report something to list.
  const DOC_MIXES = [
    ['submitted', 'submitted', 'submitted', 'submitted'],
    ['submitted', 'submitted', 'submitted', 'submitted'],
    ['submitted', 'submitted', 'submitted', 'submitted'],
    ['submitted', 'pending', 'submitted', 'submitted'],
    ['submitted', 'submitted', 'submitted', 'missing'],
    ['submitted', 'pending', 'submitted', 'missing'],
    ['submitted', 'missing', 'pending', 'missing'],
    ['pending', 'pending', 'pending', 'pending'],
    ['submitted', 'submitted', 'pending', 'pending'],
    ['missing', 'submitted', 'submitted', 'submitted']
  ];
  const ATTENDANCE_MIXES = [
    ['Present', 'Present', 'Present', 'Present', 'Present'],
    ['Present', 'Present', 'Present', 'Present', 'Present'],
    ['Present', 'Present', 'Present', 'Present', 'Present'],
    ['Present', 'Present', 'Absent', 'Present', 'Present'],
    ['Present', 'Absent', 'Present', 'Present', 'Pending'],
    ['Present', 'Present', 'Present', 'Present', 'Pending'],
    ['Absent', 'Absent', 'Present', 'Absent', 'Present'],
    ['Pending', 'Pending', 'Pending', 'Pending', 'Pending'],
    ['Present', 'Present', 'Pending', 'Pending', 'Pending']
  ];
  const WEEK_DAYS = [['Mon', 'Aug 3'], ['Tue', 'Aug 4'], ['Wed', 'Aug 5'], ['Thu', 'Aug 6'], ['Fri', 'Aug 7']];

  function buildBulkCohort(seed) {
    const rng = makeRng(seed);
    const pick = list => list[Math.floor(rng() * list.length)];
    const between = (lo, hi) => lo + Math.floor(rng() * (hi - lo + 1));
    const pad = (value, size) => String(value).padStart(size, '0');
    const emailSeen = {};
    // Seats left per section, so generated students never overfill a room.
    const seatsLeft = {};
    Object.entries(SECTIONS).forEach(([name, info]) => { seatsLeft[name] = info.capacity - info.baseline; });
    Object.values(SEED.sections).forEach(name => { if (seatsLeft[name] !== undefined) seatsLeft[name] -= 1; });

    const out = {
      applicants: {}, requirements: {}, audits: {}, attendance: {}, records: {}, sections: {}, documentRequests: [],
      academic: { students: {}, classes: {}, grades: {}, history: {}, adviserNotes: {} }
    };
    let studentSeq = BULK_FIRST_STU;

    for (let i = 0; i < BULK_COUNT; i += 1) {
      const appId = `APP-2026-${pad(BULK_FIRST_APP + i, 4)}`;
      const place = pick(LOCALITY_PICKS);
      const female = rng() < 0.5;
      const first = pick(female ? NAMES_F : NAMES_M);
      const last = pick(SURNAMES);
      const middle = pick(SURNAMES);
      const { track, strand } = pick(STRAND_POOL);
      const category = pick(CATEGORY_POOL);
      const queueStatus = pick(QUEUE_POOL);
      const grade = rng() < 0.62 ? 'Grade 11' : 'Grade 12';
      const age = grade === 'Grade 11' ? between(16, 18) : between(17, 19);
      const birthYear = 2026 - age;
      const name = `${last}, ${first}`;

      let emailBase = `${first}.${last}`.toLowerCase();
      emailSeen[emailBase] = (emailSeen[emailBase] || 0) + 1;
      if (emailSeen[emailBase] > 1) emailBase += emailSeen[emailBase];

      const fromSchool = category !== 'New';
      const school = fromSchool ? pick(place.schools) : '';
      const submittedDay = between(1, 10);
      const submittedAt = `Aug ${submittedDay}, 2026`;
      const guardianIsMother = rng() < 0.45;
      const fatherName = `${pick(NAMES_M)} ${last}`;
      const motherName = `${pick(NAMES_F)} ${middle} ${last}`;
      const contact = `09${between(15, 99)} ${pad(between(0, 999), 3)} ${pad(between(0, 9999), 4)}`;
      const guardianContact = `09${between(15, 99)} ${pad(between(0, 999), 3)} ${pad(between(0, 9999), 4)}`;

      out.applicants[appId] = {
        name, lrn: `1${pad(between(0, 99999999999), 11)}`, category,
        programLabel: `${strand} · ${grade}`, program: 'shs',
        submittedAt,
        campus: place.campus, campusKey: place.campusKey,
        queueStatus, evalStatus: queueStatus === 'enrolled' ? 'ready' : queueStatus === 'review' ? 'review' : 'review', flag: '',
        grade, track, strand, delivery: pick(DELIVERY_POOL), deliveryOther: '',
        lrnStatus: category === 'Returnee' ? 'Returning (Balik-Aral)' : 'With LRN',
        psa: rng() < 0.85 ? `PSA-${between(2019, 2024)}-${pad(between(1, 9999), 6)}` : '',
        last, first, middle, ext: '',
        dob: `${pad(between(1, 12), 2)}/${pad(between(1, 28), 2)}/${birthYear}`,
        sex: female ? 'Female' : 'Male', age: String(age),
        contact, email: `${emailBase}@email.com`,
        ip: rng() < 0.12 ? 'Yes' : 'No', ipOther: '', motherTongue: pick(MOTHER_TONGUES), honors: rng() < 0.18 ? 'With Honors (Grade 10)' : '',
        house: `${pick(['Purok', 'Zone', 'Sitio'])} ${between(1, 8)}, ${place.barangays[0] === 'Poblacion' ? 'Poblacion' : pick(place.barangays)}`,
        province: place.province, city: place.city, barangay: pick(place.barangays),
        enrolled: fromSchool ? 'Yes' : 'No', school, schoolAddress: fromSchool ? `${place.city}, ${place.province}` : '', schoolId: '',
        father: fatherName, mother: motherName, guardian: guardianIsMother ? motherName : fatherName,
        guardianContact, regDate: `08/${pad(Math.max(1, submittedDay - 2), 2)}/2026`, referred: pick(REFERRERS)
      };

      const mix = pick(DOC_MIXES);
      const docs = {};
      DOC_NAMES.forEach((doc, idx) => { docs[doc] = mix[idx]; });
      out.requirements[appId] = { docs, contact, email: `${emailBase}@email.com` };

      const audit = [`Application created by R. Santos — ${submittedAt} ${pad(between(8, 11), 2)}:${pad(between(0, 59), 2)} AM`];
      const flagged = DOC_NAMES.filter(doc => docs[doc] === 'missing');
      if (flagged.length) audit.unshift(`${flagged[0]} marked Missing by R. Santos — Aug ${Math.min(11, submittedDay + 1)}, 2026 ${pad(between(1, 4), 2)}:${pad(between(0, 59), 2)} PM`);
      if (mix.every(status => status === 'submitted')) audit.unshift(`All documents verified by R. Santos — Aug ${Math.min(11, submittedDay + 2)}, 2026 ${pad(between(8, 11), 2)}:${pad(between(0, 59), 2)} AM`);
      out.audits[appId] = audit;

      const attendanceMix = pick(ATTENDANCE_MIXES);
      const complete = mix.every(status => status === 'submitted');
      out.attendance[appId] = {
        req: complete ? 'Complete' : attendanceMix.every(day => day === 'Pending') ? 'Pending' : 'Incomplete',
        log: WEEK_DAYS.map(([day, date], idx) => ({ day, date, status: attendanceMix[idx] }))
      };

      // Confirmed and near-confirmed applicants get a student record, which is
      // what the Student Records report and Enrollment Records screen read.
      if (queueStatus === 'enrolled' || queueStatus === 'pending') {
        const studentId = `STU-2026-${pad(studentSeq, 4)}`;
        studentSeq += 1;
        const enrolledNow = queueStatus === 'enrolled';
        out.records[studentId] = {
          app: appId, name, initials: `${first[0]}${last[0]}`,
          meta: `${grade} · ${strand} · ${place.campus}`,
          status: enrolledNow ? 'enrolled' : 'pending',
          category: `${category} Student — Regular`,
          ref: `CSH-${pad(200 + i, 4)}`, bracket: pick(['Bracket A', 'Bracket B', 'Bracket C']),
          req: complete ? ['Complete', 'success'] : ['Incomplete', 'danger'],
          reqDate: submittedAt,
          attend: attendanceMix.map(status => status.toLowerCase()),
          attendStatus: attendanceMix.every(day => day === 'Present') ? 'Verified' : 'Not yet verified',
          confirmable: !enrolledNow && complete && attendanceMix.every(day => day === 'Present'),
          confirmHint: enrolledNow
            ? 'Student is already officially enrolled.'
            : complete ? 'Requirements complete — check attendance before confirming.' : 'Cannot confirm yet — requirements are still incomplete.'
        };

        if (enrolledNow) {
          const match = Object.keys(SECTIONS).find(sectionName =>
            SECTIONS[sectionName].strand === strand && SECTIONS[sectionName].campus === place.campus && seatsLeft[sectionName] > 0);
          if (match) { out.sections[studentId] = match; seatsLeft[match] -= 1; }
          if (rng() < 0.22) {
            out.documentRequests.push({
              id: `DOC-2026-${pad(100 + out.documentRequests.length, 4)}`,
              studentId, student: name,
              document: pick(['Form 137 (Transcript)', 'Form 138 (Report Card)', 'TOR', 'Certificate of Enrolment', 'Good Moral Certificate']),
              status: pick(['Issued', 'Issued', 'For review', 'Pending docs']),
              released: '', requestedBy: pick(['Student', 'Guardian', 'Receiving school'])
            });
          }
        }
      }
    }

    out.documentRequests.forEach(request => {
      if (request.status === 'Issued') request.released = `Aug ${6 + (Number(request.id.slice(-2)) % 5)}, 2026`;
    });

    // ---- academic side -----------------------------------------------------
    // Sectioned students carry the SAME student id into Academic Records, which
    // is the "one verified record across modules" claim the demo makes. Their
    // grades give the Honor Students report a genuine ranked list.
    const SUBJECT_BY_STRAND = {
      STEM: 'Earth Science', HUMSS: 'Philippine Politics', ABM: 'Business Mathematics', GAS: 'Applied Economics',
      CA: 'Cookery Fundamentals', IT: 'Computer Systems Servicing', HRM: 'Food and Beverage Services',
      EIM: 'Electrical Installation', OM: 'Records Management', PN: 'Nursing Fundamentals', CE: 'Computer Programming'
    };
    const TEACHERS = ['Ernesto Villar', 'Grace Sabandal', 'Jomar Tuban', 'Lorna Bacareza', 'Rey Anthony Ong'];
    // Generated students report to advisers other than Nicole Chua, so her
    // advisory class stays the small scripted roster used in the walkthrough.
    const ADVISERS = ['Maria Lopez', 'Ernesto Villar', 'Grace Sabandal', 'Jomar Tuban'];

    const bySection = {};
    Object.entries(out.records).forEach(([studentId, record]) => {
      const section = out.sections[studentId];
      if (!section || record.status !== 'enrolled') return;
      (bySection[section] = bySection[section] || []).push({ studentId, record });
    });

    Object.entries(bySection).forEach(([section, members], sectionIdx) => {
      const info = SECTIONS[section];
      const adviser = ADVISERS[sectionIdx % ADVISERS.length];
      const subjects = ['General Mathematics', 'Oral Communication', SUBJECT_BY_STRAND[info.strand] || 'Research Methods'];
      const studentIds = members.map(member => member.studentId);

      members.forEach(({ studentId, record }) => {
        const app = out.applicants[record.app];
        out.academic.students[studentId] = {
          appId: record.app, name: record.name, initials: record.initials, lrn: app.lrn,
          gradeLevel: app.grade, section, campus: info.campus, adviser,
          sectionHistory: [
            { label: `${app.grade} · ${section}`, detail: `SY 2026–2027 · ${info.campus}` },
            { label: `${app.grade === 'Grade 12' ? 'Grade 11' : 'Grade 10'} · ${app.strand}`, detail: `SY 2025–2026 · ${app.school || 'Previous school'}` }
          ]
        };
        out.academic.adviserNotes[studentId] = [];
        out.academic.history[studentId] = [];
      });

      subjects.forEach((subject, subjectIdx) => {
        const classId = `CLS-BULK-${section.toUpperCase().replace(/[^A-Z0-9]/g, '')}-${subjectIdx + 1}`;
        out.academic.classes[classId] = {
          id: classId, subject, section, gradeLevel: members[0].record.meta.split('·')[0].trim(),
          room: info.room, teacher: TEACHERS[(sectionIdx + subjectIdx) % TEACHERS.length],
          periodId: 'SY2026-Q1', studentIds,
          status: 'submitted', submittedBy: TEACHERS[(sectionIdx + subjectIdx) % TEACHERS.length], submittedAt: 'Aug 6, 2026 10:00 AM'
        };
        out.academic.grades[classId] = {};

        members.forEach(({ studentId }) => {
          // Per-student ability, nudged per subject, gives a believable spread
          // of averages rather than everyone landing on the same grade.
          const ability = 70 + Math.floor(rng() * 29);
          const target = Math.max(65, Math.min(99, ability + between(-4, 4)));
          const ratio = target / 100;
          const scores = {
            ww1: Math.round(20 * ratio), ww2: Math.round(50 * ratio),
            pt1: Math.round(100 * ratio), other1: Math.round(10 * ratio)
          };
          // A few unfinished assessments so the Incomplete Grades monitor and
          // the at-risk view have real cases to work with.
          if (rng() < 0.06) scores.pt1 = null;
          const complete = scores.pt1 !== null;
          const quarterGrade = complete
            ? Math.round(((scores.ww1 + scores.ww2) / 70) * 40 + (scores.pt1 / 100) * 50 + (scores.other1 / 10) * 10)
            : null;
          out.academic.grades[classId][studentId] = {
            scores, quarterGrade, status: complete ? 'submitted' : 'incomplete', updatedAt: 'Aug 6, 2026 10:00 AM'
          };
          out.academic.history[studentId].push({
            subject, schoolYear: '2026–2027',
            quarters: { q1: quarterGrade, q2: null, q3: null, q4: null }, finalGrade: null
          });
        });
      });
    });

    return out;
  }

  // Fold the generated cohort into SEED. Handwritten entries win on any key
  // clash, so the walkthrough records can never be overwritten by generated ones.
  (function applyBulkCohort() {
    const bulk = buildBulkCohort(20262027);
    ['applicants', 'requirements', 'audits', 'attendance', 'records', 'sections'].forEach(key => {
      SEED[key] = Object.assign({}, bulk[key], SEED[key]);
    });
    ['students', 'classes', 'grades', 'history', 'adviserNotes'].forEach(key => {
      SEED.academic[key] = Object.assign({}, bulk.academic[key], SEED.academic[key]);
    });
    SEED.documentRequests = SEED.documentRequests.concat(bulk.documentRequests);
  })();

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
  function mergeMissing(target, defaults) {
    Object.keys(defaults).forEach(key => {
      if (target[key] === undefined || target[key] === null) {
        target[key] = clone(defaults[key]);
      } else if (typeof target[key] === 'object' && !Array.isArray(target[key]) && typeof defaults[key] === 'object' && !Array.isArray(defaults[key])) {
        mergeMissing(target[key], defaults[key]);
      }
    });
  }

  function normalize() {
    Object.keys(data.applicants).forEach(key => { data.applicants[key].id = key; });
    if (!data.academic) data.academic = clone(SEED.academic);
    else mergeMissing(data.academic, SEED.academic);
    if (!data.monitoring) data.monitoring = clone(SEED.monitoring);
    else mergeMissing(data.monitoring, SEED.monitoring);
    if (!data.priorStudents) data.priorStudents = clone(SEED.priorStudents);
    recomputeAllPairs();    persist(false);
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
      submittedAt: '',
      campus: 'Main — Iligan', campusKey: 'main',
      queueStatus: 'pre-enlisted', evalStatus: 'ready', flag: '',
      grade: 'Grade 11', track: 'ACADEMIC', strand: '', delivery: [], deliveryOther: '',
      lrnStatus: 'With LRN', psa: '', last: '', first: '', middle: '', ext: '',
      dob: '', sex: 'Female', age: '', contact: '', email: '',
      ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: '',
      house: '', province: 'Lanao del Norte', city: '', barangay: '',
      enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
      father: '', mother: '', guardian: '', guardianContact: '', regDate: '', referred: 'STAFF',
      uploads: {}, continuing: false, priorStudentId: ''
    }, partial, { id });
    // Stamp the submission date for any new application (keeps the queue sortable).
    if (!data.applicants[id].submittedAt) data.applicants[id].submittedAt = stampDate();
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

  // Save a submitted profile without resetting any workflow state already
  // attached to the applicant. This is especially useful for fixed-ID demo
  // submissions opened with an older persisted store that lacks newer seed
  // records.
  function upsertApplicant(partial) {
    const id = partial.id || nextAppId();
    if (!data.applicants[id]) return addApplicant(Object.assign({}, partial, { id }));

    data.applicants[id] = Object.assign(data.applicants[id], partial, { id });
    if (!data.applicants[id].submittedAt) data.applicants[id].submittedAt = stampDate();
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
    deriveRequirements(id);
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

  // ---- name-similarity matching (replaces hand-authored match flags) ----
  // Simple, explainable Levenshtein-ratio on the normalized full name, with
  // exact-match bonuses for DOB/LRN (less error-prone identifiers than a
  // typed name) — no external library, deliberately easy to defend live.
  function normalizeName(s) {
    return String(s || '').toLowerCase().replace(/[^a-z\s]/g, '').replace(/\s+/g, ' ').trim();
  }

  function levenshtein(a, b) {
    const m = a.length, n = b.length;
    if (!m) return n;
    if (!n) return m;
    let prev = Array.from({ length: n + 1 }, (_, j) => j);
    for (let i = 1; i <= m; i++) {
      const row = [i];
      for (let j = 1; j <= n; j++) {
        row[j] = a[i - 1] === b[j - 1] ? prev[j - 1] : 1 + Math.min(prev[j - 1], prev[j], row[j - 1]);
      }
      prev = row;
    }
    return prev[n];
  }

  function nameSimilarity(a, b) {
    const x = normalizeName(a), y = normalizeName(b);
    if (!x || !y) return 0;
    const dist = levenshtein(x, y);
    const maxLen = Math.max(x.length, y.length);
    return maxLen ? Math.max(0, 1 - dist / maxLen) : 0;
  }

  // Full-name fields use fuzzy similarity; every other identifying field
  // ("Status" excluded — it's contextual, not identity) requires an exact match.
  function deriveFieldMatch(label, a, b) {
    if (label === 'Status') return false;
    if (label === 'Full name') return nameSimilarity(a, b) >= 0.7;
    const la = String(a || '').trim().toLowerCase();
    const lb = String(b || '').trim().toLowerCase();
    return Boolean(la) && la === lb;
  }

  // Composite confidence score for two {name, dob, lrn} identities.
  function computeMatch(a, b) {
    const nameSim = nameSimilarity(a.name, b.name);
    let score = nameSim;
    const dobMatch = Boolean(a.dob) && a.dob === b.dob;
    const lrnMatch = Boolean(a.lrn) && a.lrn === b.lrn;
    if (dobMatch) score += (1 - score) * 0.5;
    if (lrnMatch) score += (1 - score) * 0.6;
    return { score: Math.min(0.99, score), nameSim, dobMatch, lrnMatch };
  }

  function getFieldValue(sub, label) {
    const row = (sub && sub.fields || []).find(f => f[0] === label);
    return row ? row[1] : '';
  }

  // Recomputes a pair's per-field match flags + overall meter from its own
  // field values — keeps seeded names/values intact, derives the rest honestly.
  function recomputePair(pair) {
    if (!pair) return pair;
    pair.incoming.fields = pair.incoming.fields.map(([label, value]) => [label, value, deriveFieldMatch(label, value, getFieldValue(pair.existing, label))]);
    pair.existing.fields = pair.existing.fields.map(([label, value]) => [label, value, deriveFieldMatch(label, value, getFieldValue(pair.incoming, label))]);
    const a = { name: getFieldValue(pair.incoming, 'Full name'), dob: getFieldValue(pair.incoming, 'Date of birth'), lrn: getFieldValue(pair.incoming, 'LRN') };
    const b = { name: getFieldValue(pair.existing, 'Full name'), dob: getFieldValue(pair.existing, 'Date of birth'), lrn: getFieldValue(pair.existing, 'LRN') };
    pair.meter = `${Math.round(computeMatch(a, b).score * 100)}% match confidence`;
    return pair;
  }

  function recomputeAllPairs() {
    Object.values(data.pairs).forEach(recomputePair);
  }

  // Live scan for a possible duplicate against every other applicant + every
  // existing student record — used by the Encode Profile "as you type" suggestion.
  function suggestDuplicate(candidate, excludeAppId) {
    let best = null;
    Object.values(data.applicants).forEach(app => {
      if (app.id === excludeAppId) return;
      const { score } = computeMatch(candidate, { name: app.name, dob: app.dob, lrn: app.lrn });
      if (score >= 0.55 && (!best || score > best.score)) best = { kind: 'applicant', id: app.id, name: app.name, score };
    });
    Object.entries(data.records).forEach(([sid, rec]) => {
      if (rec.app === excludeAppId) return;
      const linked = data.applicants[rec.app];
      const { score } = computeMatch(candidate, { name: rec.name, dob: linked ? linked.dob : '', lrn: linked ? linked.lrn : '' });
      if (score >= 0.55 && (!best || score > best.score)) best = { kind: 'record', id: sid, name: rec.name, score };
    });
    return best;
  }

  // Builds a real (non-persona) duplicate pair from a live suggestDuplicate() match.
  function createPairFromMatch(appId, match) {
    if (data.pairs[appId] || !match) return null;
    const app = data.applicants[appId];
    if (!app) return null;
    let existingDob = '', existingLrn = '', existingSex = app.sex, existingSchool = app.school || '—', existingContact = app.guardianContact || '—', existingStatus = 'Active';
    if (match.kind === 'record') {
      const rec = data.records[match.id];
      const linked = rec ? data.applicants[rec.app] : null;
      if (linked) { existingDob = linked.dob; existingLrn = linked.lrn; existingSex = linked.sex; existingSchool = linked.school || '—'; existingContact = linked.guardianContact || '—'; }
      existingStatus = rec && rec.status === 'enrolled' ? 'Active' : 'Inactive';
    } else {
      const other = data.applicants[match.id];
      if (other) { existingDob = other.dob; existingLrn = other.lrn; existingSex = other.sex; existingSchool = other.school || '—'; existingContact = other.guardianContact || '—'; }
      existingStatus = 'Pre-Enlisted';
    }
    data.pairs[appId] = {
      meter: '',
      incoming: {
        sub: `${app.category} · ${appId} · Submitted ${stampDate()}`,
        fields: [
          ['Full name', app.name, false], ['Date of birth', app.dob, false], ['Sex', app.sex, false], ['LRN', app.lrn || '—', false],
          ['Previous school', app.school || '—', false], ['Guardian contact', app.guardianContact || '—', false], ['Status', 'Pre-Enlisted', false]
        ]
      },
      existing: {
        sub: `${match.kind === 'record' ? 'Student ' + match.id : 'Application ' + match.id} · ${match.name}`,
        fields: [
          ['Full name', match.name, false], ['Date of birth', existingDob, false], ['Sex', existingSex, false], ['LRN', existingLrn || '—', false],
          ['Previous school', existingSchool, false], ['Guardian contact', existingContact, false], ['Status', existingStatus, false]
        ]
      }
    };
    recomputePair(data.pairs[appId]);
    persist();
    return data.pairs[appId];
  }

  // ---- record helpers ----
  function getRecords() { return data.records; }
  function getRecord(id) { return data.records[id]; }
  function confirmRecord(id) {
    const rec = data.records[id];
    if (!rec) return false;
    // Live-recompute the gate so fresh records (and manual edits elsewhere) stay honest.
    const overall = deriveRequirements(rec.app);
    const verified = attendanceVerified(rec.app);
    rec.confirmable = overall === 'Complete' && verified;
    if (!rec.confirmable) return false;
    rec.status = 'enrolled';
    rec.req = [overall, 'success'];
    rec.attend = attendanceSummary(rec.app);
    rec.attendStatus = 'Verified';
    rec.confirmedAt = nowStamp();
    rec.confirmedBy = 'R. Santos (RS)';
    rec.confirmHint = 'Student is already officially enrolled.';
    if (data.applicants[rec.app]) {
      data.applicants[rec.app].queueStatus = 'enrolled';
      data.applicants[rec.app].evalStatus = 'ready';
    }
    persist();
    return true;
  }


  // Real capacity-validated assignment (was a blind overwrite).
  function assignSection(id, section) {
    const info = SECTIONS[section];
    if (info && data.sections[id] !== section) {
      const live = Object.values(data.sections).filter(s => s === section).length;
      const enrolled = (info.baseline || 0) + live;
      if (enrolled >= info.capacity) return false;
    }
    data.sections[id] = section;
    persist();
    return true;
  }

  // Rooms/locations roster — Section | Track/Specialization | Campus | Building | Room | capacity.
  // Enrolled counts are derived live from data.sections, not hardcoded.
  function sectionRoster() {
    return Object.entries(SECTIONS).map(([name, info]) => {
      const live = Object.values(data.sections).filter(s => s === name).length;
      const enrolled = Math.min(info.capacity, (info.baseline || 0) + live);
      return { name, track: info.track, strand: info.strand, campus: info.campus, building: info.building, room: info.room, capacity: info.capacity, enrolled, full: enrolled >= info.capacity };
    });
  }
  function removeRecord(id) {
    delete data.records[id];
    delete data.sections[id];
    persist();
  }

  // ---- derived statuses (keeps the attendance gate + records truthful) ----
  function deriveRequirements(id) {
    const req = data.requirements[id];
    if (!req) return 'Pending';
    const values = Object.values(req.docs || {});
    const overall = !values.length ? 'Pending'
      : values.every(v => v === 'submitted') ? 'Complete'
      : values.some(v => v === 'missing' || v === 'invalid') ? 'Incomplete'
      : 'Pending';
    if (data.attendance[id]) data.attendance[id].req = overall;
    return overall;
  }

  function attendanceVerified(id) {
    const att = data.attendance[id];
    return Boolean(att && Array.isArray(att.log) && att.log.length && att.log.every(d => d.status === 'Present'));
  }

  function attendanceSummary(id) {
    const att = data.attendance[id];
    if (!att || !Array.isArray(att.log)) return [];
    return att.log.map(d => d.status === 'Present' ? 'present' : d.status === 'Absent' ? 'absent' : 'pending');
  }

  // ---- student records (created on demand so fresh applicants can be confirmed) ----
  function nextStudentId() {
    const ids = Object.keys(data.records).filter(id => /^STU-2026-/.test(id)).map(id => Number(id.split('-')[2])).filter(Boolean);
    const max = ids.length ? Math.max(...ids) : 455;
    return 'STU-2026-' + String(max + 1).padStart(4, '0');
  }

  function nextRef() {
    const refs = Object.keys(data.records).map(sid => Number(data.records[sid].ref.split('-')[1])).filter(Boolean);
    const max = refs.length ? Math.max(...refs) : 95;
    return 'CSH-' + String(max + 1).padStart(4, '0');
  }

  function recordByApp(appId) {
    for (const sid of Object.keys(data.records)) if (data.records[sid].app === appId) return sid;
    return null;
  }

  function initialsOf(name) {
    const parts = String(name || '').replace(/,/g, ' ').split(/\s+/).filter(Boolean);
    return (parts.map(p => p[0] || '').join('').toUpperCase() || 'ST').slice(0, 2);
  }

  function categoryLabel(app) {
    const map = { New: 'New Student — Regular', Transferee: 'Transferee — Voucher (ESC)', Returnee: 'Returnee — Regular' };
    return map[app.category] || app.category || 'New Student — Regular';
  }

  function createRecord(appId) {
    const app = data.applicants[appId];
    if (!app) return null;
    const existing = recordByApp(appId);
    if (existing) return existing;
    const sid = nextStudentId();
    const overall = deriveRequirements(appId);
    const verified = attendanceVerified(appId);
    data.records[sid] = {
      app: appId, name: app.name,
      initials: initialsOf(app.name),
      meta: `${app.programLabel} · ${app.campus}`,
      status: 'pending',
      category: categoryLabel(app),
      ref: nextRef(),
      bracket: 'Bracket A',
      req: [overall, overall === 'Complete' ? 'success' : 'danger'],
      reqDate: stampDate(),
      attend: attendanceSummary(appId),
      attendStatus: verified ? 'Verified' : 'Not yet verified',
      confirmable: overall === 'Complete' && verified,
      confirmHint: (overall === 'Complete' && verified)
        ? 'Requirements complete AND attendance verified. Ready to confirm enrollment.'
        : (overall === 'Complete' ? 'Attendance not yet fully verified.' : 'Requirements are not yet complete.'),
      confirmedAt: '', confirmedBy: ''
    };
    persist();
    return sid;
  }

  // ---- simulated uploads (browsers forbid setting input[type=file] values) ----
  function attachUpload(appId, doc, fakeName) {
    const app = data.applicants[appId];
    if (!app) return;
    if (!app.uploads) app.uploads = {};
    app.uploads[doc] = fakeName;
    persist();
  }

  // ---- duplicate flagging for simulated transferees ----
  function flagPair(appId) {
    if (data.pairs[appId]) return;
    const app = data.applicants[appId];
    if (!app) return;
    const p = getPersona(data.simulation ? data.simulation.persona : 'transferee');
    data.pairs[appId] = {
      meter: p.meter || '78% match confidence',
      incoming: {
        sub: `${app.category} · ${appId} · Submitted ${stampDate()}`,
        fields: [
          ['Full name', app.name, true],
          ['Date of birth', app.dob, true],
          ['Sex', app.sex, true],
          ['LRN', app.lrn || '—', true],
          ['Previous school', app.school || '—', true],
          ['Guardian contact', app.guardianContact || '—', false],
          ['Status', 'Pre-Enlisted', false]
        ]
      },
      existing: {
        sub: `Student ${p.existingId} · Record since 2023`,
        fields: [
          ['Full name', p.existingName, false],
          ['Date of birth', app.dob, true],
          ['Sex', app.sex, true],
          ['LRN', p.existingLrn, false],
          ['Previous school', app.school || '—', true],
          ['Guardian contact', app.guardianContact || '—', false],
          ['Status', 'Active', false]
        ]
      }
    };
    persist();
  }

  // ---- journey simulation (persisted so the journey survives navigation) ----
  const JOURNEY_STAGES = [
    { n: 0, label: 'Submission', screen: 'submission.html' },
    { n: 1, label: 'Enrollment Queue', screen: 'queue.html' },
    { n: 2, label: 'Encode Profile', screen: 'evaluate.html' },
    { n: 3, label: 'Duplicate Review', screen: 'duplicate.html' },
    { n: 4, label: 'Requirements', screen: 'requirements.html' },
    { n: 5, label: 'Attendance', screen: 'attendance.html' },
    { n: 6, label: 'Confirm Enrollment', screen: 'academic-records.html' },
    { n: 7, label: 'Section Assignment', screen: 'academic-records.html' },
    { n: 8, label: 'Enrolled & Assigned', screen: 'enrolled.html' }
  ];

  // Demo personas — realistic applicant data + per-stage scripts.
  const PERSONAS = {
    new: {
      label: 'New Student — Clean Run', dup: false, section: 'STEM-B',
      meter: '', existingName: '', existingId: '', existingLrn: '',
      category: 'New', grade: 'Grade 11', track: 'ACADEMIC', strand: 'STEM',
      campus: 'Main — Iligan', campusKey: 'main', program: 'shs',
      last: 'Villanueva', first: 'Bea', middle: 'Marie', ext: '',
      lrnStatus: 'With LRN', lrn: '121567830945', psa: 'PSA-2026-000771',
      dob: '05/12/2009', sex: 'Female', age: '17', contact: '0917 555 0156', email: 'bea.villanueva@email.com',
      ip: 'No', ipOther: '', motherTongue: 'Bisaya/Cebuano', honors: 'With Honors (Grade 10)',
      house: 'Blk 4, Purok 7', province: 'Lanao del Norte', city: 'Iligan City', barangay: 'Hinaplanon',
      enrolled: 'No', school: '', schoolAddress: '', schoolId: '',
      father: 'Ramon Villanueva', mother: 'Lourdes Marie Villanueva', guardian: 'Ramon Villanueva',
      guardianContact: '0917 555 0156', regDate: '08/07/2026', referred: 'STAFF',
      delivery: ['Modular (Print)', 'Online'], deliveryOther: '',
      uploads: { 'PSA Birth Certificate': 'PSA-Villanueva.pdf', 'Form 137': 'Form137-Villanueva.pdf', 'Certificate of Completion': 'Completion-Villanueva.pdf', 'Good Moral Certificate': 'GoodMoral-Villanueva.pdf' }
    },
    transferee: {
      label: 'Transferee — Duplicate Flag', dup: true, section: 'Cookery-1',
      meter: '78% match confidence', existingName: 'Bautista, Carlos D.', existingId: 'STU-2023-1210', existingLrn: '120987654321',
      category: 'Transferee', grade: 'Grade 11', track: 'TVL', strand: 'CA',
      campus: 'Bugo — CDO', campusKey: 'bugo', program: 'shs',
      last: 'Bautista', first: 'Carlo', middle: 'Dizon', ext: '',
      lrnStatus: 'With LRN', lrn: '121456789012', psa: 'PSA-2024-003182',
      dob: '03/09/2009', sex: 'Male', age: '17', contact: '0922 555 0153', email: 'carlo.bautista@email.com',
      ip: 'No', ipOther: '', motherTongue: 'Hiligaynon', honors: '',
      house: 'Purok 2, Brgy. Bugo', province: 'Misamis Oriental', city: 'Cagayan de Oro', barangay: 'Bugo',
      enrolled: 'Yes', school: 'Iligan City National High School', schoolAddress: 'Pala-o, Iligan City', schoolId: 'ICN-2023-221',
      father: 'Edwin Bautista', mother: 'Marilou Dizon Bautista', guardian: 'Edwin Bautista',
      guardianContact: '0922 555 0153', regDate: '08/07/2026', referred: 'GUIDANCE',
      delivery: ['Online'], deliveryOther: '',
      uploads: { 'PSA Birth Certificate': 'PSA-Bautista.pdf', 'Form 137': 'Form137-Bautista.pdf', 'Certificate of Completion': 'Completion-Bautista.pdf', 'Good Moral Certificate': 'GoodMoral-Bautista.pdf' }
    },
    returnee: {
      label: 'Returnee — Follow-Up Needed', dup: false, section: 'GAS-1',
      meter: '', existingName: '', existingId: '', existingLrn: '',
      category: 'Returnee', grade: 'Grade 12', track: 'ACADEMIC', strand: 'GAS',
      campus: 'Kapatagan', campusKey: 'kapatagan', program: 'shs',
      last: 'Fernandez', first: 'Miguel', middle: 'Santos', ext: 'Jr.',
      lrnStatus: 'Returning (Balik-Aral)', lrn: '112588901234', psa: 'PSA-2021-006044',
      dob: '08/21/2008', sex: 'Male', age: '18', contact: '0923 555 0158', email: 'miguel.fernandez@email.com',
      ip: 'Yes', ipOther: 'Meranaw', motherTongue: 'Maranao', honors: '',
      house: 'Sitio Raya, Purok 4', province: 'Lanao del Sur', city: 'Marawi City', barangay: 'Tambo',
      enrolled: 'No', school: 'Tambo Central School', schoolAddress: 'Tambo, Marawi City', schoolId: 'TCS-2020-158',
      father: 'Arturo Fernandez', mother: 'Rowena Santos Fernandez', guardian: 'Rowena Santos Fernandez',
      guardianContact: '0923 555 0158', regDate: '08/07/2026', referred: 'TVL',
      delivery: ['Modular (Print)', 'Educational TV'], deliveryOther: '',
      uploads: { 'PSA Birth Certificate': 'PSA-Fernandez.pdf', 'Form 137': 'Form137-Fernandez.pdf', 'Certificate of Completion': 'Completion-Fernandez.pdf', 'Good Moral Certificate': 'GoodMoral-Fernandez.pdf' }
    }
  };

  function getPersona(name) { return PERSONAS[name] || PERSONAS.new; }

  function startJourney(opts) {
    if (data.simulation && data.simulation.active && data.simulation.appId !== opts.appId) {
      delete data.pairs[data.simulation.appId];
    }
    data.simulation = {
      active: true, appId: opts.appId, persona: opts.persona || 'new',
      mode: opts.mode || 'auto', speed: opts.speed || 650,
      stage: 1, startedAt: nowStamp(), timeline: []
    };
    logStage(0, 'Student submitted the enrollment form');
    logStage(1, 'Application entered the Enrollment Queue as Pre-Enlisted');
    persist();
    return opts.appId;
  }

  function getJourney() { return data.simulation || null; }

  function logStage(n, label) {
    if (!data.simulation) return;
    data.simulation.timeline = data.simulation.timeline.filter(e => e.stage !== n);
    data.simulation.timeline.push({ stage: n, label, at: nowStamp() });
  }

  function setStage(n, label) {
    if (!data.simulation) return;
    data.simulation.stage = Math.max(data.simulation.stage, n);
    if (label) logStage(n, label);
    persist();
  }

  // Next applicable stage, skipping Duplicate Review for personas with no flag.
  function nextJourneyStage() {
    const j = data.simulation;
    if (!j || !j.active) return null;
    for (let n = j.stage + 1; n <= 8; n++) {
      if (n === 3 && getPersona(j.persona).dup !== true) continue;
      return n;
    }
    return null;
  }

  // ---- continuing-student lookup (real prior-enrollment linkage, not simulation) ----
  // A continuing student (e.g. Grade 11 -> Grade 12 at the same school) already has
  // requirements on file from a prior enrollment; the registrar looks them up by LRN
  // and links the record instead of collecting the same documents again.
  function findPriorStudent(lrn) {
    const clean = String(lrn || '').trim();
    if (!clean) return null;
    for (const id of Object.keys(data.priorStudents)) {
      if (data.priorStudents[id].lrn === clean) return Object.assign({ id }, data.priorStudents[id]);
    }
    return null;
  }

  function linkPriorStudent(appId, priorId) {
    const app = data.applicants[appId];
    const prior = data.priorStudents[priorId];
    if (!app || !prior) return false;
    app.continuing = true;
    app.priorStudentId = priorId;
    const req = data.requirements[appId];
    if (req) {
      req.docs = clone(prior.docs);
      if (!data.audits[appId]) data.audits[appId] = [];
      data.audits[appId].unshift(`Requirements carried over from prior enrollment — ${priorId}, ${prior.lastGradeLevel}, SY ${prior.lastSchoolYear} — no resubmission required — R. Santos — ${stamp()}`);
      deriveRequirements(appId);
    }
    persist();
    return true;
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

  // ---- reporting and intervention helpers ----
  function getMonitoringCase(appId) { return data.monitoring[appId] || null; }
  function recordIntervention(appId, action, notes) {
    if (!data.monitoring[appId]) {
      data.monitoring[appId] = { status: 'Open', action: '', notes: '', by: '', when: '' };
    }
    data.monitoring[appId].status = 'Monitoring';
    data.monitoring[appId].action = action;
    data.monitoring[appId].notes = notes;
    data.monitoring[appId].by = 'Angela Cruz';
    data.monitoring[appId].when = nowStamp();
    persist();
    return true;
  }

  // ---- academic records helpers ----
  function getAcademicStudent(id) { return data.academic.students[id]; }
  function getAcademicClass(id) { return data.academic.classes[id]; }
  function getAcademicPeriod(id) { return data.academic.periods[id]; }
  function getAcademicClasses() { return data.academic.classes; }
  function getCorrections() { return data.academic.corrections; }
  function getIncompleteCases() { return data.academic.incompleteCases; }

  function requirementSummary(studentId) {
    const student = getAcademicStudent(studentId);
    const req = student ? data.requirements[student.appId] : null;
    if (!req) return { label: 'Not available', tone: 'neutral-badge', detail: 'No enrollment checklist' };
    const values = Object.values(req.docs || {});
    const submitted = values.filter(value => value === 'submitted').length;
    if (values.length && values.every(value => value === 'submitted')) return { label: 'Complete', tone: 'success', detail: `${submitted} of ${values.length} submitted` };
    if (values.some(value => value === 'missing' || value === 'invalid')) return { label: 'Incomplete', tone: 'danger', detail: `${submitted} of ${values.length} submitted` };
    return { label: 'Pending', tone: 'warning', detail: `${submitted} of ${values.length} submitted` };
  }

  function calculateAcademicGrade(classId, studentId) {
    const record = data.academic.grades[classId]?.[studentId];
    if (!record) return { complete: false, grade: null, missing: [] };
    const policy = data.academic.policy;
    const missing = policy.assessments.filter(assessment => record.scores[assessment.key] === null || record.scores[assessment.key] === '' || record.scores[assessment.key] === undefined).map(assessment => assessment.key);
    if (missing.length) return { complete: false, grade: null, missing };
    let grade = 0;
    policy.components.forEach(component => {
      const assessments = policy.assessments.filter(assessment => assessment.category === component.key);
      const earned = assessments.reduce((sum, assessment) => sum + Number(record.scores[assessment.key] || 0), 0);
      const possible = assessments.reduce((sum, assessment) => sum + Number(assessment.max || 0), 0);
      grade += possible ? (earned / possible) * Number(component.weight || 0) : 0;
    });
    return { complete: true, grade: Math.round(grade), missing: [] };
  }

  function refreshGradeRecord(classId, studentId, targetStatus) {
    const record = data.academic.grades[classId]?.[studentId];
    if (!record) return null;
    const result = calculateAcademicGrade(classId, studentId);
    record.quarterGrade = result.grade;
    record.status = result.complete ? (targetStatus || record.status || 'draft') : 'incomplete';
    record.updatedAt = stamp();
    return result;
  }

  function updateAcademicPolicy(weights) {
    const policy = data.academic.policy;
    const next = policy.components.map(component => Number(weights[component.key]));
    if (next.some(value => !Number.isFinite(value) || value < 0) || next.reduce((sum, value) => sum + value, 0) !== 100) return false;
    policy.components.forEach((component, index) => { component.weight = next[index]; });
    Object.entries(data.academic.grades).forEach(([classId, records]) => {
      Object.keys(records).forEach(studentId => refreshGradeRecord(classId, studentId));
    });
    data.academic.audit.unshift({ action: 'Demo grading component weights updated', by: 'Michael Reyes', when: stamp() });
    persist();
    return true;
  }

  function saveClassScores(classId, updates) {
    const academicClass = getAcademicClass(classId);
    const period = academicClass ? getAcademicPeriod(academicClass.periodId) : null;
    if (!academicClass || !period || period.status === 'finalized') return false;
    Object.entries(updates).forEach(([studentId, scores]) => {
      const record = data.academic.grades[classId]?.[studentId];
      if (!record) return;
      Object.entries(scores).forEach(([key, value]) => { record.scores[key] = value === '' || value === null ? null : Number(value); });
      refreshGradeRecord(classId, studentId, 'draft');
      const openCase = Object.values(data.academic.incompleteCases).find(item => item.classId === classId && item.studentId === studentId && item.status !== 'resolved');
      if (openCase && calculateAcademicGrade(classId, studentId).complete) openCase.status = 'for_registrar';
    });
    academicClass.status = 'draft';
    academicClass.submittedAt = '';
    academicClass.submittedBy = '';
    persist();
    return true;
  }

  function nextIncompleteId() {
    const max = Object.keys(data.academic.incompleteCases).map(id => Number(id.split('-').pop())).filter(Number.isFinite).reduce((value, current) => Math.max(value, current), 0);
    return `INC-2026-${String(max + 1).padStart(3, '0')}`;
  }

  function submitClassGrades(classId) {
    const academicClass = getAcademicClass(classId);
    const period = academicClass ? getAcademicPeriod(academicClass.periodId) : null;
    if (!academicClass || !period || period.status === 'finalized') return { ok: false, incomplete: 0 };
    let incomplete = 0;
    academicClass.studentIds.forEach(studentId => {
      const result = refreshGradeRecord(classId, studentId, 'submitted');
      const currentCase = Object.values(data.academic.incompleteCases).find(item => item.classId === classId && item.studentId === studentId && item.status !== 'resolved');
      if (!result?.complete) {
        incomplete += 1;
        if (!currentCase) {
          const id = nextIncompleteId();
          data.academic.incompleteCases[id] = { id, studentId, classId, periodId: academicClass.periodId, assessmentKey: result?.missing[0] || '', status: 'pending_teacher', notes: [{ text: 'Incomplete grade identified during teacher submission.', by: academicClass.teacher, when: stamp() }], resolvedAt: '', resolvedBy: '' };
        }
      } else if (currentCase) {
        currentCase.status = 'for_registrar';
        currentCase.notes.unshift({ text: 'Teacher supplied the missing score; awaiting registrar verification.', by: academicClass.teacher, when: stamp() });
      }
    });
    academicClass.status = 'submitted';
    academicClass.submittedBy = academicClass.teacher;
    academicClass.submittedAt = stamp();
    data.academic.audit.unshift({ action: `${academicClass.subject} grades submitted`, by: academicClass.teacher, when: stamp() });
    persist();
    return { ok: true, incomplete };
  }

  function academicStanding(studentId) {
    const grades = [];
    let incomplete = false;
    Object.entries(data.academic.classes).forEach(([classId, academicClass]) => {
      if (!academicClass.studentIds.includes(studentId) || academicClass.periodId !== 'SY2026-Q1') return;
      const result = calculateAcademicGrade(classId, studentId);
      if (result.complete) grades.push(result.grade);
      else incomplete = true;
    });
    if (!grades.length && !incomplete) return { label: 'Not available', tone: 'neutral-badge', detail: 'No submitted grades' };
    const average = grades.length ? Math.round(grades.reduce((sum, grade) => sum + grade, 0) / grades.length) : null;
    if (incomplete || (average !== null && average < data.academic.policy.passingGrade)) return { label: 'At risk', tone: 'danger', detail: incomplete ? 'Incomplete academic record' : `Average ${average}` };
    return { label: 'Good standing', tone: 'success', detail: `Average ${average}` };
  }

  function getAcademicHistory(studentId) {
    const history = clone(data.academic.history[studentId] || []);
    Object.entries(data.academic.classes).forEach(([classId, academicClass]) => {
      if (!academicClass.studentIds.includes(studentId) || academicClass.periodId !== 'SY2026-Q1') return;
      const result = calculateAcademicGrade(classId, studentId);
      let row = history.find(item => item.subject === academicClass.subject && item.schoolYear === '2026–2027');
      if (!row) {
        row = { subject: academicClass.subject, schoolYear: '2026–2027', quarters: { q1: null, q2: null, q3: null, q4: null }, finalGrade: null };
        history.push(row);
      }
      row.quarters.q1 = result.grade;
    });
    return history;
  }

  function addAdviserNote(studentId, note) {
    if (!data.academic.students[studentId]) return false;
    if (!data.academic.adviserNotes[studentId]) data.academic.adviserNotes[studentId] = [];
    data.academic.adviserNotes[studentId].unshift({ type: note.type, priority: note.priority, text: note.text, status: note.status, adviser: 'Nicole Chua', when: stamp() });
    data.academic.audit.unshift({ action: `Adviser note added for ${data.academic.students[studentId].name}`, by: 'Nicole Chua', when: stamp() });
    persist();
    return true;
  }

  function nextCorrectionId() {
    const max = Object.keys(data.academic.corrections).map(id => Number(id.split('-').pop())).filter(Number.isFinite).reduce((value, current) => Math.max(value, current), 0);
    return `GCR-2026-${String(max + 1).padStart(3, '0')}`;
  }

  function submitCorrection(request) {
    const history = data.academic.history[request.studentId] || [];
    const row = history.find(item => item.subject === request.subject && item.schoolYear === request.schoolYear);
    const currentGrade = row?.quarters?.[request.quarterKey];
    if (!row || currentGrade === null || currentGrade === undefined) return null;
    const id = nextCorrectionId();
    data.academic.corrections[id] = { id, studentId: request.studentId, subject: request.subject, schoolYear: request.schoolYear, quarterKey: request.quarterKey, periodId: request.periodId, currentGrade: Number(currentGrade), proposedGrade: Number(request.proposedGrade), reason: request.reason, explanation: request.explanation, attachment: request.attachment || { name: '', type: '', size: 0 }, status: 'principal_review', submittedBy: 'Michael Reyes', submittedAt: stamp(), audit: [{ action: 'Submitted by subject teacher', by: 'Michael Reyes', when: stamp() }] };
    persist();
    return id;
  }

  function reviewCorrection(id, action) {
    const request = data.academic.corrections[id];
    if (!request) return false;
    if (action === 'principal-approve' && request.status === 'principal_review') {
      request.status = 'registrar_review';
      request.audit.push({ action: 'Approved by Principal', by: 'Principal Review (prototype)', when: stamp() });
    } else if (action === 'registrar-approve' && request.status === 'registrar_review') {
      request.status = 'approved';
      request.audit.push({ action: 'Approved by Registrar', by: 'R. Santos', when: stamp() });
    } else if (action === 'reject' && ['principal_review', 'registrar_review'].includes(request.status)) {
      request.status = 'rejected';
      request.audit.push({ action: 'Request rejected', by: 'R. Santos', when: stamp() });
    } else if (action === 'encode' && request.status === 'approved') {
      const row = (data.academic.history[request.studentId] || []).find(item => item.subject === request.subject && item.schoolYear === request.schoolYear);
      if (!row) return false;
      row.quarters[request.quarterKey] = Number(request.proposedGrade);
      const available = Object.values(row.quarters).filter(value => value !== null && value !== undefined).map(Number);
      row.finalGrade = available.length === 4 ? Math.round(available.reduce((sum, grade) => sum + grade, 0) / available.length) : row.finalGrade;
      request.status = 'encoded';
      request.encodedAt = stamp();
      request.encodedBy = 'R. Santos';
      request.audit.push({ action: `Corrected grade encoded (${request.currentGrade} → ${request.proposedGrade})`, by: 'R. Santos', when: stamp() });
    } else return false;
    persist();
    return true;
  }

  function addIncompleteNote(id, text) {
    const item = data.academic.incompleteCases[id];
    if (!item || !text.trim()) return false;
    item.notes.unshift({ text: text.trim(), by: 'R. Santos', when: stamp() });
    persist();
    return true;
  }

  function resolveIncomplete(id, completedScore) {
    const item = data.academic.incompleteCases[id];
    if (!item || item.status === 'resolved') return false;
    const record = data.academic.grades[item.classId]?.[item.studentId];
    const assessment = data.academic.policy.assessments.find(entry => entry.key === item.assessmentKey);
    if (!record || !assessment) return false;
    const score = completedScore === '' || completedScore === null ? record.scores[item.assessmentKey] : Number(completedScore);
    if (!Number.isFinite(Number(score)) || Number(score) < 0 || Number(score) > assessment.max) return false;
    record.scores[item.assessmentKey] = Number(score);
    refreshGradeRecord(item.classId, item.studentId, 'submitted');
    item.status = 'resolved';
    item.resolvedAt = stamp();
    item.resolvedBy = 'R. Santos';
    item.notes.unshift({ text: `Completed score verified and recorded: ${score}/${assessment.max}.`, by: 'R. Santos', when: stamp() });
    persist();
    return true;
  }

  function finalizationStatus(periodId) {
    const classes = Object.values(data.academic.classes).filter(item => item.periodId === periodId);
    const unsubmitted = classes.filter(item => !['submitted', 'finalized'].includes(item.status));
    const incomplete = Object.values(data.academic.incompleteCases).filter(item => item.periodId === periodId && item.status !== 'resolved');
    const corrections = Object.values(data.academic.corrections).filter(item => item.periodId === periodId && !['encoded', 'rejected'].includes(item.status));
    return { classes, unsubmitted, incomplete, corrections, ready: Boolean(classes.length) && !unsubmitted.length && !incomplete.length && !corrections.length };
  }

  function finalizeAcademicPeriod(periodId) {
    const period = getAcademicPeriod(periodId);
    const state = finalizationStatus(periodId);
    if (!period || period.status === 'finalized' || !state.ready) return false;
    period.status = 'finalized';
    period.finalizedAt = stamp();
    period.finalizedBy = 'R. Santos';
    state.classes.forEach(academicClass => {
      academicClass.status = 'finalized';
      Object.values(data.academic.grades[academicClass.id] || {}).forEach(record => { record.status = 'finalized'; });
    });
    data.academic.audit.unshift({ action: `${period.schoolYear} ${period.label} finalized`, by: 'R. Santos', when: stamp() });
    persist();
    return true;
  }

  function stamp() { return 'Aug 7, 2026 10:42 AM'; }

  function stampDate() {
    return new Date().toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
  }

  function nowStamp() {
    return new Date().toLocaleString('en-US', { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', hour12: true });
  }

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
    get documentRequests() { return data.documentRequests || []; },
    get academic() { return data.academic; },
    nextAppId, addApplicant, upsertApplicant, updateApplicant, removeApplicant,
    getRequirements, setRequirement, getAudits,
    getAttendance, setAttendance,
    startJourney, getJourney, getPersona, flagPair, setStage,
    getPair, getPairStatus, resolvePair, suggestDuplicate, createPairFromMatch,
    getRecords, getRecord, confirmRecord, assignSection, removeRecord,
    SECTIONS, sectionRoster,
    getReportLog, addReport, removeReport, getMonitoringCase, recordIntervention,
    getAcademicStudent, getAcademicClass, getAcademicPeriod, getAcademicClasses,
    getCorrections, getIncompleteCases, requirementSummary, calculateAcademicGrade,
    updateAcademicPolicy, saveClassScores, submitClassGrades, academicStanding,
    getAcademicHistory, addAdviserNote, submitCorrection, reviewCorrection,
    addIncompleteNote, resolveIncomplete, finalizationStatus, finalizeAcademicPeriod,
    deriveRequirements, attendanceVerified, attendanceSummary, createRecord, recordByApp, attachUpload,
    findPriorStudent, linkPriorStudent,
    resetDemo
  };
})();
