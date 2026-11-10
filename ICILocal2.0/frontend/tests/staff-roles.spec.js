import {test,expect} from '@playwright/test';

test('administrator provisions accounts and every staff role signs in to an allowed landing screen',async({page})=>{
 test.skip(!process.env.ICI_LIVE_API||!process.env.ICI_TEST_USERNAME||!process.env.ICI_TEST_PASSWORD,'Requires a disposable database and administrator.');
 test.setTimeout(180000);page.setDefaultTimeout(15000);
 const errors=[];page.on('pageerror',error=>errors.push(error.message));
 await page.route('https://fonts.googleapis.com/**',route=>route.abort());await page.route('https://fonts.gstatic.com/**',route=>route.abort());
 await page.goto('/administration/users');await page.getByLabel('Username',{exact:true}).fill(process.env.ICI_TEST_USERNAME);await page.getByLabel('Password',{exact:true}).fill(process.env.ICI_TEST_PASSWORD);await page.getByRole('button',{name:'Sign in',exact:true}).click();
 await expect(page.getByRole('heading',{name:'User Accounts',exact:true})).toBeVisible();
 const token=await page.evaluate(()=>sessionStorage.getItem('ici_access_token'));const headers={Authorization:`Bearer ${token}`};
 const reference=await (await page.request.get('/api/admin/accounts/reference-data',{headers})).json();const campus=reference.campuses[0],suffix=Date.now();
 const accounts=[],password=`Temporary-Role-Check-${suffix}`;
 const roles=[['ROLE_REGISTRAR','Enrollment Queue'],['ROLE_PRINCIPAL','Academic History'],['ROLE_ADVISER','Advisory Class'],['ROLE_TEACHER','Grade Entry'],['ROLE_GUIDANCE','Guidance Records'],['ROLE_CASHIER_REF','Enrolled Student Reference'],['ROLE_ADMIN','User Accounts']];
 // Verify actual UI provisioning for the registrar; use the same API contract for other roles.
 const username=`role_registrar_${suffix}`;
 await page.getByLabel('Username',{exact:true}).fill(username);await page.getByLabel('Full name',{exact:true}).fill('Browser Registrar');await page.getByLabel('Email',{exact:true}).fill(`${username}@example.invalid`);await page.getByLabel('Initial password').fill(password);
 await page.getByLabel('Staff role').selectOption('ROLE_REGISTRAR');await page.getByLabel(campus.name,{exact:true}).check();
 const created=page.waitForResponse(response=>response.url().endsWith('/api/admin/accounts')&&response.request().method()==='POST');await page.getByRole('button',{name:'Create staff account',exact:true}).click();expect((await created).status()).toBe(201);
 await expect(page.getByText(/Staff account created/)).toBeVisible();await expect(page.getByLabel('Initial password')).toHaveValue('');accounts.push({username,role:roles[0][0],heading:roles[0][1]});
 await page.screenshot({path:'test-results/staff-accounts-desktop.png',fullPage:true});
 await page.setViewportSize({width:390,height:844});expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);await page.screenshot({path:'test-results/staff-accounts-mobile.png',fullPage:true});await page.setViewportSize({width:1440,height:1000});
 for(const [role,heading] of roles.slice(1)){
  const name=`role_${role.toLowerCase()}_${suffix}`;
  const response=await page.request.post('/api/admin/accounts',{headers,data:{username:name,email:`${name}@example.invalid`,fullName:`Browser ${role}`,password,role,campusIds:[campus.id]}});expect(response.status()).toBe(201);accounts.push({username:name,role,heading});
 }
 for(const account of accounts){
  await page.getByRole('button',{name:'Sign out',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Staff sign in',exact:true})).toBeVisible();
  // Preserve a requested admin-only URL to exercise role-safe post-login redirects.
  await page.goto('/administration/users');await page.getByLabel('Username',{exact:true}).fill(account.username);await page.getByLabel('Password',{exact:true}).fill(password);await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.getByRole('heading',{name:account.heading,exact:true})).toBeVisible();await expect(page.getByRole('heading',{name:'Access denied',exact:true})).toHaveCount(0);
  await page.reload();await expect(page.getByRole('heading',{name:account.heading,exact:true})).toBeVisible();
  if(!['ROLE_ADMIN','ROLE_REGISTRAR'].includes(account.role))await expect(page.getByText(/You are signed in. This workflow is still under development/)).toBeVisible();
 }
 expect(errors).toEqual([]);
});
