import {test,expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';

test.beforeEach(async({page})=>{
 test.skip(!process.env.ICI_LIVE_API||!process.env.ICI_TEST_USERNAME||!process.env.ICI_TEST_PASSWORD,'Requires disposable PostgreSQL and administrator.');
 test.setTimeout(120000);page.setDefaultTimeout(15000);
 await page.route('https://fonts.googleapis.com/**',route=>route.abort());await page.route('https://fonts.gstatic.com/**',route=>route.abort());
});
async function signIn(page,path='/enrollment/queue'){
 await page.goto(path);await page.getByLabel('Username',{exact:true}).fill(process.env.ICI_TEST_USERNAME);await page.getByLabel('Password',{exact:true}).fill(process.env.ICI_TEST_PASSWORD);await page.getByRole('button',{name:'Sign in',exact:true}).click();
 await expect(page.getByRole('button',{name:'Sign out',exact:true})).toBeVisible();
 return {Authorization:`Bearer ${await page.evaluate(()=>sessionStorage.getItem('ici_access_token'))}`};
}
async function setup(page,headers){
 const ref=await (await page.request.get('/api/intake/reference-data',{headers})).json();
 const yearResponse=await page.request.post('/api/setup/academic-years',{headers,data:{label:`QA-${Date.now()}`,startsOn:'2026-06-01',endsOn:'2027-05-31'}});expect(yearResponse.status()).toBe(201);const year=await yearResponse.json();
 const termResponse=await page.request.post('/api/setup/terms',{headers,data:{academicYearId:year.id,code:'S1',name:'QA term',startsOn:'2026-06-01',endsOn:'2026-12-31'}});expect(termResponse.status()).toBe(201);const term=await termResponse.json();
 return {ref,term,campus:ref.campuses[0],program:ref.programs.find(item=>item.code==='STEM')};
}
function input(fixture){return {campusId:fixture.campus.id,termId:fixture.term.id,programId:fixture.program.id,category:'new',firstName:`Qatest${randomUUID().replace(/[^a-z]/g,'')}`,lastName:'Acceptance',birthDate:'2009-08-17',voucher:'QVR',track:'ACADEMIC',gradeLevel:'11',guardians:[]};}
async function create(page,headers,data){const r=await page.request.post('/api/intake/applications',{headers,data});expect(r.status()).toBe(201);return r.json();}
async function review(page,headers,app){const r=await page.request.post(`/api/intake/applications/${app.id}/review`,{headers,data:{version:app.version}});expect(r.status()).toBe(200);return r.json();}
async function ready(page,headers,app){
 for(const code of ['PSA','FORM137','COMPLETION','GOOD_MORAL']){const r=await page.request.put(`/api/applications/${app.id}/requirements/${code}`,{headers,data:{status:'submitted',version:0,notes:'QA credential inspected'}});expect(r.status()).toBe(200);}
 expect((await page.request.put(`/api/applications/${app.id}/attendance/2026-06-01`,{headers,data:{status:'present',version:0}})).status()).toBe(200);
 const response=await page.request.post(`/api/applications/${app.id}/confirm-enrollment`,{headers,data:{version:app.version}});expect(response.status()).toBe(200);return response.json();
}
test('[ICI2-13/14] matching gates, false positives, and available resolution queue',async({page})=>{
 const headers=await signIn(page),fixture=await setup(page,headers),base=input(fixture);
 const original=await create(page,headers,{...base,lrn:`8${String(Date.now()).slice(-11)}`});expect(original.status).toBe('submitted');
 const otherCampus=fixture.ref.campuses.find(c=>c.id!==fixture.campus.id);
 const exact=await create(page,headers,{...input(fixture),campusId:otherCampus.id,lrn:original.lrn,birthDate:'2008-02-03'});expect(exact.status).toBe('duplicate_flag');expect(exact.studentId).toBeNull();
 const fuzzy=await create(page,headers,{...base,firstName:`${base.firstName}a`});expect(fuzzy.status).toBe('duplicate_flag');
 const differentDob=await create(page,headers,{...base,birthDate:'2008-05-12'});expect(differentDob.status).toBe('submitted');
 const unrelated=await create(page,headers,{...base,firstName:randomUUID().replace(/[^a-z]/g,''),lastName:`Unrelated${randomUUID().replace(/[^a-z]/g,'')}`});expect(unrelated.status).toBe('submitted');
 const blocked=await page.request.post(`/api/intake/applications/${exact.id}/review`,{headers,data:{version:exact.version}});expect(blocked.status()).toBe(409);
 await page.goto(`/enrollment/applicants/${exact.id}`);await expect(page.getByText('Duplicate flagged',{exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'Review and pre-enlist'})).toHaveCount(0);
 // Existing-student intake remains guarded until the staff member chooses a verified identity.
 const returning=await create(page,headers,{...input(fixture),category:'continuing'});
 const result=await page.request.post(`/api/intake/applications/${returning.id}/review`,{headers,data:{version:returning.version}});expect(result.status()).toBe(409);expect((await result.json()).detail).toContain('link');
 const duplicatePage=await page.request.get('/api/duplicate-cases',{headers});expect(duplicatePage.status()).toBe(200);
 const reviewerName=`qa_registrar_${Date.now()}`,reviewerPassword='TemporaryQaRegistrar2026';
 const account=await page.request.post('/api/admin/accounts',{headers,data:{username:reviewerName,email:`${reviewerName}@example.invalid`,fullName:'QA Registrar',password:reviewerPassword,role:'ROLE_REGISTRAR',campusIds:[fixture.campus.id]}});expect(account.status()).toBe(201);
 await page.getByRole('button',{name:'Sign out',exact:true}).click();await expect(page.getByRole('heading',{name:'Staff sign in',exact:true})).toBeVisible();
 await page.goto('/enrollment/duplicate');await page.getByLabel('Username',{exact:true}).fill(reviewerName);await page.getByLabel('Password',{exact:true}).fill(reviewerPassword);await page.getByRole('button',{name:'Sign in',exact:true}).click();
 await expect(page.getByRole('heading',{name:'Duplicate Records',exact:true})).toBeVisible();
 await page.screenshot({path:'test-results/qa-13-resolution-gap.png',fullPage:true,animations:'disabled'});
});

test('[ICI2-15–19] live two-tab conflict, confirmation blockers, section controls and last seat',async({page})=>{
 const headers=await signIn(page),fixture=await setup(page,headers);
 let app=await review(page,headers,await create(page,headers,input(fixture)));
 await page.goto(`/enrollment/attendance?applicationId=${app.id}`);await expect(page.getByRole('button',{name:'Confirm enrollment',exact:true})).toBeDisabled();
 await page.goto(`/enrollment/requirements?applicationId=${app.id}`);await expect(page.getByLabel('Status: PSA',{exact:true})).toHaveValue('pending');
 const other=await page.context().newPage();await other.route('https://fonts.googleapis.com/**',route=>route.abort());await other.route('https://fonts.gstatic.com/**',route=>route.abort());
 await signIn(other,`/enrollment/requirements?applicationId=${app.id}`);await expect(other.getByLabel('Status: PSA',{exact:true})).toHaveValue('pending');
 await other.getByLabel('Verification notes: PSA',{exact:true}).fill('Unsaved second registrar note');
 await page.getByLabel('Status: PSA',{exact:true}).selectOption('missing');await page.getByLabel('Verification notes: PSA',{exact:true}).fill('Original absent at review');await page.getByRole('button',{name:'Save PSA',exact:true}).click();await expect(page.getByLabel('Status: PSA',{exact:true})).toHaveValue('missing');
 await other.getByRole('button',{name:'Save PSA',exact:true}).click();await expect(other.getByRole('alert')).toContainText(/changed|reload/i);await expect(other.getByLabel('Verification notes: PSA',{exact:true})).toHaveValue('Unsaved second registrar note');
 await other.screenshot({path:'test-results/qa-16-conflict-draft.png',fullPage:true,animations:'disabled'});await other.close();
 await expect(page.locator('.verification-history').getByText('Original absent at review',{exact:true})).toBeVisible();await page.reload();await expect(page.getByLabel('Verification notes: PSA',{exact:true})).toHaveValue('Original absent at review');
 await page.keyboard.press('Escape');await expect(page.getByRole('dialog',{name:'Requirements Checklist'})).toHaveCount(0);await page.getByRole('button',{name:`Open ${app.applicationNumber}`,exact:true}).click();await expect(page.getByLabel('Verification notes: PSA',{exact:true})).toHaveValue('Original absent at review');
 for(const code of ['PSA','FORM137','COMPLETION','GOOD_MORAL']){await page.getByLabel(`Status: ${code}`,{exact:true}).selectOption('submitted');const saved=page.waitForResponse(r=>r.url().endsWith(`/requirements/${code}`)&&r.request().method()==='PUT');await page.getByRole('button',{name:`Save ${code}`,exact:true}).click();expect((await saved).status()).toBe(200);await expect(page.getByLabel(`Status: ${code}`,{exact:true})).toHaveValue('submitted');}
 await page.getByRole('link',{name:'Continue to attendance'}).click();await expect(page.getByRole('button',{name:'Confirm enrollment',exact:true})).toBeDisabled();
 await page.getByLabel('Attendance date',{exact:true}).fill('2026-06-01');await page.getByLabel('Attendance status',{exact:true}).selectOption('absent');await page.getByRole('button',{name:'Save attendance'}).click();await expect(page.getByText('Attendance recorded.',{exact:true})).toBeVisible();await expect(page.locator('.verification-history').getByText('absent',{exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'Confirm enrollment',exact:true})).toBeDisabled();
 await page.getByLabel('Attendance status',{exact:true}).selectOption('present');await page.getByRole('button',{name:'Save attendance'}).click();await expect(page.getByRole('button',{name:'Confirm enrollment',exact:true})).toBeEnabled();await page.getByRole('button',{name:'Confirm enrollment',exact:true}).click();await page.getByRole('button',{name:'Confirm',exact:true}).click();await expect(page.getByText('Enrollment confirmed',{exact:true})).toBeVisible();
 await page.goto('/enrollment/sections');const sectionForm=page.locator('form').filter({has:page.getByLabel('Section name',{exact:true})});await sectionForm.getByLabel('Campus',{exact:true}).selectOption(String(fixture.campus.id));await sectionForm.getByLabel('Academic term',{exact:true}).selectOption(String(fixture.term.id));await sectionForm.getByLabel('Program / strand',{exact:true}).selectOption(String(fixture.program.id));
 const name=`QA Capacity ${Date.now()}`;await page.getByLabel('Section name',{exact:true}).fill(name);await page.getByLabel('Grade level',{exact:true}).fill('11');await page.getByLabel('Maximum seats',{exact:true}).fill('1');await page.getByRole('button',{name:'Create section',exact:true}).click();await expect(page.getByRole('heading',{name,exact:true})).toBeVisible();
 const createdSection=await (await page.request.get(`/api/sections?termId=${fixture.term.id}`,{headers})).json();const section=createdSection.items.find(s=>s.name===name);expect(section.capacity).toBe(1);
 await page.goto(`/enrollment/enrolled?applicationId=${app.id}`);const card=page.locator('.section-card').filter({has:page.getByRole('heading',{name,exact:true})});await card.getByRole('button',{name:'Assign to this section'}).click();await page.getByRole('button',{name:'Confirm',exact:true}).click();await expect(card.getByRole('button',{name:'Currently assigned'})).toBeDisabled();
 const second=await review(page,headers,await create(page,headers,input(fixture)));const enrolled=await ready(page,headers,second);
 await page.goto(`/enrollment/enrolled?applicationId=${second.id}`);await expect(page.getByRole('button',{name:'Assign to this section'})).toBeDisabled();
 const overflow=await page.request.put(`/api/enrollments/${enrolled.enrollment.id}/section`,{headers,data:{sectionId:section.id,version:enrolled.enrollment.version}});expect(overflow.status()).toBe(409);
 const full=await (await page.request.get(`/api/sections/${section.id}`,{headers})).json();expect(full.occupied).toBe(1);expect(full.available).toBe(0);
 await page.screenshot({path:'test-results/qa-19-full-section.png',fullPage:true,animations:'disabled'});
 await page.goto('/enrollment/sections');await page.getByRole('button',{name:`Edit ${name}`,exact:true}).click();await page.getByLabel('Room',{exact:true}).fill('QA Room 204');await page.getByRole('button',{name:'Save section',exact:true}).click();await expect(page.getByText('Section updated.',{exact:true})).toBeVisible();
 await page.getByRole('button',{name:`Delete ${name}`,exact:true}).click();await page.getByRole('button',{name:'Confirm',exact:true}).click();await expect(page.getByRole('alert')).toBeVisible();
 const retained=await page.request.get(`/api/sections/${section.id}`,{headers});expect(retained.status()).toBe(200);expect((await retained.json()).room).toBe('QA Room 204');
});
