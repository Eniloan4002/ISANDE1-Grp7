import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
test.beforeEach(async({page})=>{test.skip(!process.env.ICI_LIVE_API||!process.env.ICI_TEST_USERNAME||!process.env.ICI_TEST_PASSWORD,'Use a disposable backend.');test.setTimeout(150000);page.setDefaultTimeout(15000);await page.route('https://fonts.googleapis.com/**',r=>r.abort());await page.route('https://fonts.gstatic.com/**',r=>r.abort());});
async function login(page,path='/enrollment/queue',username=process.env.ICI_TEST_USERNAME,password=process.env.ICI_TEST_PASSWORD){await page.goto(path);await page.getByLabel('Username',{exact:true}).fill(username);await page.getByLabel('Password',{exact:true}).fill(password);await page.getByRole('button',{name:'Sign in',exact:true}).click();await expect(page.getByRole('button',{name:'Sign out',exact:true})).toBeVisible();return {Authorization:`Bearer ${await page.evaluate(()=>sessionStorage.getItem('ici_access_token'))}`};}
async function registrarLogin(page){
 const h=await login(page);const options=await (await page.request.get('/api/admin/accounts/reference-data',{headers:h})).json();
 const name=`duplicate_registrar_${Date.now()}`,password='PrivateDuplicateRegistrar2026';
 const created=await page.request.post('/api/admin/accounts',{headers:h,data:{username:name,fullName:'Duplicate Registrar',email:`${name}@example.invalid`,password,role:'ROLE_REGISTRAR',campusIds:[options.campuses[0].id]}});expect(created.status()).toBe(201);
 await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.getByRole('heading',{name:'Staff sign in',exact:true})).toBeVisible();return login(page,'/enrollment/queue',name,password);
}

async function fixture(page,h){const ref=await (await page.request.get('/api/intake/reference-data',{headers:h})).json();const y=await page.request.post('/api/setup/academic-years',{headers:h,data:{label:`Gap-${Date.now()}`,startsOn:'2026-06-01',endsOn:'2027-05-31'}});expect(y.status()).toBe(201);const year=await y.json();const t=await page.request.post('/api/setup/terms',{headers:h,data:{academicYearId:year.id,code:'S1',name:'Gap semester',startsOn:'2026-06-01',endsOn:'2026-12-31'}});expect(t.status()).toBe(201);return {ref,year,term:await t.json(),campus:ref.campuses[0],program:ref.programs.find(p=>p.code==='STEM')};}
function input(f){return {firstName:`Gap${randomUUID().replace(/[^a-z]/g,'')}`,lastName:'Verified',birthDate:'2009-04-16',campusId:f.campus.id,termId:f.term.id,programId:f.program.id,category:'new',track:'ACADEMIC',voucher:'QVR',gradeLevel:'11',guardians:[]};}
async function create(page,h,data){const r=await page.request.post('/api/intake/applications',{headers:h,data});expect(r.status()).toBe(201);return r.json();}
async function detail(page,h,id){return (await page.request.get(`/api/intake/applications/${id}`,{headers:h})).json();}
async function firstCase(page,h,id){const r=await (await page.request.get(`/api/duplicate-cases?applicationId=${id}`,{headers:h})).json();return r.items[0].id;}
async function decide(page,id,action,reason){await page.goto(`/enrollment/duplicate?caseId=${id}`);await page.getByLabel('Decision',{exact:true}).selectOption(action);await page.getByLabel('Decision reason',{exact:true}).fill(reason);await page.getByRole('button',{name:'Review decision',exact:true}).click();await page.getByRole('button',{name:'Confirm',exact:true}).click();await expect(page.getByText('Decision saved with its staff attribution and history.')).toBeVisible();}

test('same-person resolution, continuing/returnee linking and approved attendance dates preserve the master',async({page})=>{
 const h=await registrarLogin(page),f=await fixture(page,h),data=input(f);data.lrn=`7${String(Date.now()).slice(-11)}`;
 const masterResponse=await page.request.post('/api/students',{headers:h,data:{...data,voucherCode:'QVR',active:true,address:{}}});expect(masterResponse.status()).toBe(201);const master=await masterResponse.json();
 const app=await create(page,h,{...data,category:'continuing'});expect(app.status).toBe('duplicate_flag');const caseId=await firstCase(page,h,app.id);
 await page.goto(`/enrollment/duplicate?applicationId=${app.id}`);
 await expect(page.getByRole('button',{name:`Review case ${caseId}`,exact:true})).toBeVisible();
 await page.getByLabel('Search this page').fill('no-such-application-identity');
 await expect(page.getByRole('button',{name:`Review case ${caseId}`,exact:true})).toHaveCount(0);
 await page.getByLabel('Search this page').fill(data.firstName);
 await page.getByRole('button',{name:`Review case ${caseId}`,exact:true}).click();
 await expect(page.locator('.match-tag').first()).toBeVisible();
 await decide(page,caseId,'same_person','Original DOB and permanent LRN checked; retain canonical identity');
 await page.reload();
 await expect(page.getByRole('heading',{name:'Decision history'})).toBeVisible();
 await expect(page.getByText('Original DOB and permanent LRN checked; retain canonical identity',{exact:true})).toBeVisible();
 const linked=await detail(page,h,app.id);expect(linked.studentId).toBe(master.id);expect(linked.status).toBe('submitted');
 await page.goto(`/enrollment/applicants/${app.id}`);await page.getByRole('button',{name:'Review and pre-enlist'}).click();await expect(page.getByText('Pre-enlisted',{exact:true})).toBeVisible();
 await page.getByRole('link',{name:'Attendance and confirmation'}).click();await page.locator('summary').click();
 await page.getByLabel('Approved attendance start').fill('2026-08-03');await page.getByLabel('Approved attendance end').fill('2026-08-07');await page.getByLabel('Orientation event start (optional)',{exact:true}).fill('2026-07-20');await page.getByLabel('Orientation event end (optional)',{exact:true}).fill('2026-07-20');await page.getByLabel('Approval reference / reason').fill('QA fictional approved schedule, not an actual ICI policy');await page.getByRole('button',{name:'Save approved attendance dates'}).click();await expect(page.getByText('Approved attendance dates saved.')).toBeVisible();
 await page.getByLabel('Attendance date',{exact:true}).fill('2026-07-25');await page.getByLabel('Attendance status',{exact:true}).selectOption('present');await page.getByRole('button',{name:'Save attendance'}).click();await expect(page.getByRole('alert')).toContainText('outside the approved');
 await page.getByLabel('Attendance date',{exact:true}).fill('2026-08-03');await page.getByLabel('Attendance status',{exact:true}).selectOption('present');await page.getByRole('button',{name:'Save attendance'}).click();await expect(page.locator('.verification-history').getByText('present',{exact:true})).toBeVisible();
 for(const code of ['PSA','FORM137','COMPLETION','GOOD_MORAL'])expect((await page.request.put(`/api/applications/${app.id}/requirements/${code}`,{headers:h,data:{status:'submitted',notes:'QA original inspected',version:0}})).status()).toBe(200);
 await page.getByRole('button',{name:'Reload application',exact:true}).click();await expect(page.getByRole('button',{name:'Confirm enrollment',exact:true})).toBeEnabled();await page.getByRole('button',{name:'Confirm enrollment',exact:true}).click();await page.getByRole('button',{name:'Confirm',exact:true}).click();await expect(page.getByText('Enrollment confirmed',{exact:true})).toBeVisible();
 const term2Response=await page.request.post('/api/setup/terms',{headers:h,data:{academicYearId:f.year.id,code:'S2',name:'Returnee semester',startsOn:'2027-01-01',endsOn:'2027-05-31'}});expect(term2Response.status()).toBe(201);const term2=await term2Response.json();
 const returning=await create(page,h,{...data,termId:term2.id,category:'returnee'});await page.goto(`/enrollment/applicants/${returning.id}`);await page.getByLabel('Find existing student').fill(data.lrn);await page.getByRole('button',{name:`Select student ${master.id}`,exact:true}).click();await page.getByLabel('Identity verification reason').fill('Verified returning student against permanent LRN and DOB');await page.getByRole('button',{name:'Confirm student link'}).click();await expect(page.getByText(`Linked student #${master.id}. Review and pre-enlist when duplicate checks are clear.`,{exact:true})).toBeVisible();await page.getByRole('button',{name:'Review and pre-enlist'}).click();await expect(page.getByText('Pre-enlisted',{exact:true})).toBeVisible();
 const students=await (await page.request.get(`/api/students?search=${data.lrn}`,{headers:h})).json();expect(students.total).toBe(1);expect((await detail(page,h,returning.id)).studentId).toBe(master.id);
 await page.screenshot({path:'test-results/gap-returnee-linked.png',fullPage:true,animations:'disabled'});
});

test('distinct and rejected duplicate decisions retain both application records and audit reasons',async({page})=>{
 const h=await registrarLogin(page),f=await fixture(page,h),data=input(f);const first=await create(page,h,data),second=await create(page,h,{...data,firstName:`${data.firstName}a`});
 const distinct=await firstCase(page,h,second.id);await decide(page,distinct,'distinct','Reviewed originals: different people with similar names and same DOB');
 for(const app of [first,second]){const current=await detail(page,h,app.id);const r=await page.request.post(`/api/intake/applications/${app.id}/review`,{headers:h,data:{version:current.version}});expect(r.status()).toBe(200);}
 expect((await detail(page,h,first.id)).studentId).not.toBe((await detail(page,h,second.id)).studentId);
 const exactData={...input(f),lrn:`6${String(Date.now()).slice(-11)}`},canonical=await create(page,h,exactData),duplicate=await create(page,h,exactData);const reject=await firstCase(page,h,duplicate.id);
 await decide(page,reject,'reject','Repeated application: retain canonical application and reject duplicate');expect((await detail(page,h,duplicate.id)).status).toBe('rejected');const keep=await detail(page,h,canonical.id);expect((await page.request.post(`/api/intake/applications/${canonical.id}/review`,{headers:h,data:{version:keep.version}})).status()).toBe(200);
 const c=await (await page.request.get(`/api/duplicate-cases/${reject}`,{headers:h})).json();expect(c.status).toBe('rejected');expect(c.history.some(event=>event.reason==='Repeated application: retain canonical application and reject duplicate')).toBe(true);
 await page.screenshot({path:'test-results/gap-duplicate-rejection.png',fullPage:true,animations:'disabled'});
});

test('admin edits access and recovers an account; temporary password must be changed and old sessions end',async({page})=>{
 const h=await login(page,'/administration/users'),ref=await (await page.request.get('/api/admin/accounts/reference-data',{headers:h})).json();const name=`gap_staff_${Date.now()}`,old='InitialPersonalPassword2026',temporary='TemporaryRecoveryPassword2026',personal='ReplacementPersonalPassword2026';
 const created=await page.request.post('/api/admin/accounts',{headers:h,data:{username:name,email:`${name}@example.invalid`,fullName:'Gap Staff',password:old,role:'ROLE_TEACHER',campusIds:[ref.campuses[0].id]}});expect(created.status()).toBe(201);
 const before=await (await page.request.post('/api/auth/login',{data:{username:name,password:old}})).json();
 await page.getByLabel('Search staff').fill(name);await page.getByRole('button',{name:`Edit ${name}`,exact:true}).click();await page.getByLabel('Role 1',{exact:true}).selectOption('ROLE_REGISTRAR');await page.getByLabel('Change reason (exclude passwords)').fill('Verified registrar appointment');await page.getByRole('button',{name:'Review account change'}).click();await page.getByRole('button',{name:'Confirm',exact:true}).click();await expect(page.getByText('Account access updated. Existing sessions ended; the staff member must sign in again.')).toBeVisible();expect((await page.request.get('/api/auth/me',{headers:{Authorization:`Bearer ${before.accessToken}`}})).status()).toBe(401);
 await page.getByRole('button',{name:`Recover ${name}`,exact:true}).click();await page.getByLabel('Temporary password',{exact:true}).fill(temporary);await page.getByLabel('I verified this staff member’s identity').check();await page.getByLabel('Change reason (exclude passwords)').fill('Identity checked against school personnel record');await page.getByRole('button',{name:'Review account change'}).click();await page.getByRole('button',{name:'Confirm',exact:true}).click();await expect(page.getByText(/Temporary password set/)).toBeVisible();
 const member=await page.context().newPage();await member.route('https://fonts.googleapis.com/**',r=>r.abort());await member.route('https://fonts.gstatic.com/**',r=>r.abort());await login(member,'/enrollment/queue',name,temporary);await expect(member.getByRole('heading',{name:'Account Security',exact:true})).toBeVisible();await expect(member.getByText('You must replace the temporary password before accessing other workflows.')).toBeVisible();
 const limited=await member.evaluate(()=>sessionStorage.getItem('ici_access_token'));expect((await member.request.get('/api/students',{headers:{Authorization:`Bearer ${limited}`}})).status()).toBe(403);
 await member.getByLabel('Current password',{exact:true}).fill(temporary);await member.getByLabel('New password',{exact:true}).fill(personal);await member.getByLabel('Confirm new password',{exact:true}).fill(personal);await member.getByRole('button',{name:'Change password',exact:true}).click();await expect(member.getByRole('heading',{name:'Staff sign in',exact:true})).toBeVisible();await login(member,'/enrollment/queue',name,personal);await expect(member.getByRole('heading',{name:'Enrollment Queue',exact:true})).toBeVisible();
 expect((await page.request.post('/api/auth/login',{data:{username:name,password:temporary}})).status()).toBe(401);await member.close();
 await page.screenshot({path:'test-results/gap-account-recovery.png',fullPage:true,animations:'disabled'});
});
